/*
 * ssdp-kmp — DescriptionService tests (cache, dedup, negative-TTL, eviction).
 *
 * Uses Ktor MockEngine to count HTTP hits and a TestClock for virtual-time TTL.
 * All under runTest — no real network.
 */
package com.happycodelucky.ssdp.internal

import com.happycodelucky.ssdp.DescriptionException
import com.happycodelucky.ssdp.DeviceChange
import com.happycodelucky.ssdp.DiscoveredDevice
import com.happycodelucky.ssdp.SearchTarget
import com.happycodelucky.ssdp.SsdpHeaders
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class DescriptionServiceTest {
    private val sonosUrl = "http://192.168.4.20:1400/xml/device_description.xml"

    private fun device(usn: String = "uuid:RINCON_C438751026E501400", location: String? = sonosUrl): DiscoveredDevice =
        DiscoveredDevice(
            usn = usn,
            target = SearchTarget.RootDevice,
            location = location,
            server = null,
            cacheControl = null,
            bootId = null,
            configId = null,
            firstSeen = Instant.fromEpochMilliseconds(0),
            lastSeen = Instant.fromEpochMilliseconds(0),
            expiresAt = null,
            otherHeaders = SsdpHeaders.EMPTY,
        )

    /** A MockEngine that serves the Sonos fixture and counts requests. */
    private fun countingClient(
        body: String = DescriptionFixtures.SONOS_ZONEPLAYER,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): Pair<HttpClient, () -> Int> {
        val count = atomic(0)
        val engine =
            MockEngine {
                count.incrementAndGet()
                if (status.isSuccess()) {
                    respond(
                        content = ByteReadChannel(body),
                        status = status,
                        headers = headersOf("Content-Type", "text/xml"),
                    )
                } else {
                    respondError(status)
                }
            }
        return HttpClient(engine) to { count.value }
    }

    // The service launches a never-completing eviction collector on its scope.
    // In production that scope is the client's child job, cancelled on close().
    // In tests we give it `backgroundScope`, which runTest cancels automatically
    // when the test body finishes — otherwise runTest waits forever on the
    // collect{} and fails with UncompletedCoroutinesError. backgroundScope shares
    // the test scheduler, so virtual-time fetches still resolve.
    private fun TestScope.newService(
        client: HttpClient,
        changes: MutableSharedFlow<DeviceChange> = MutableSharedFlow(extraBufferCapacity = 16),
        negativeTtl: kotlin.time.Duration = 30.seconds,
        maxParkedNetworks: Int = 4,
        parkedTtl: kotlin.time.Duration = 24.hours,
    ): DescriptionService =
        DescriptionService(
            scope = backgroundScope,
            clock = TestClock(testScheduler),
            httpClient = client,
            registryChanges = changes,
            negativeTtl = negativeTtl,
            maxParkedNetworks = maxParkedNetworks,
            parkedTtl = parkedTtl,
        )

    @Test
    fun describeFetchesParsesAndReturnsSuccess() =
        runTest {
            val (client, hits) = countingClient()
            val service = newService(client)
            val result = service.describe(device())
            assertEquals("Sonos Arc Ultra", result.assertSuccess().device.modelName)
            assertEquals(1, hits())
        }

    @Test
    fun secondCallServesFromCacheWithNoNewHit() =
        runTest {
            val (client, hits) = countingClient()
            val service = newService(client)
            service.describe(device())
            service.describe(device())
            assertEquals(1, hits()) // second call cached.
        }

    @Test
    fun concurrentCallsForSameUsnCauseOneFetch() =
        runTest {
            // Gate the mock so both callers are in-flight before it responds.
            val gate = CompletableDeferred<Unit>()
            val count = atomic(0)
            val engine =
                MockEngine {
                    count.incrementAndGet()
                    gate.await()
                    respond(
                        content = ByteReadChannel(DescriptionFixtures.SONOS_ZONEPLAYER),
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", "text/xml"),
                    )
                }
            val service = newService(HttpClient(engine))

            val a = async { service.describe(device()) }
            val b = async { service.describe(device()) }
            runCurrent() // let both reach the in-flight await.
            gate.complete(Unit)
            val ra = a.await()
            val rb = b.await()

            ra.assertSuccess()
            rb.assertSuccess()
            assertEquals(1, count.value) // ONE fetch despite two concurrent callers.
        }

    @Test
    fun fetchFailureIsNegativeCachedThenRetriedAfterTtl() =
        runTest {
            val (client, hits) = countingClient(status = HttpStatusCode.NotFound)
            val service = newService(client, negativeTtl = 30.seconds)

            val first = service.describe(device())
            assertEquals(404, first.assertFailure<DescriptionException.FetchFailed>().statusCode)

            // Within TTL → no new hit, same failure.
            service.describe(device())
            assertEquals(1, hits())

            // Past TTL → one retry hit.
            advanceTimeBy(31.seconds)
            runCurrent()
            service.describe(device())
            assertEquals(2, hits())
        }

    @Test
    fun deviceWithoutLocationReturnsNotFound() =
        runTest {
            val (client, hits) = countingClient()
            val service = newService(client)
            val result = service.describe(device(location = null))
            result.assertFailure<DescriptionException.NotFound>()
            assertEquals(0, hits()) // never fetched.
        }

    @Test
    fun changedLocationRefetches() =
        runTest {
            val (client, hits) = countingClient()
            val service = newService(client)
            service.describe(device(location = sonosUrl))
            // Same USN, new LOCATION → cache entry is stale → refetch.
            service.describe(device(location = "http://192.168.4.20:1400/xml/device_description_v2.xml"))
            assertEquals(2, hits())
        }

    @Test
    fun removedDeviceEvictsCacheCausingRefetch() =
        runTest {
            val (client, hits) = countingClient()
            val changes = MutableSharedFlow<DeviceChange>(extraBufferCapacity = 16)
            val service = newService(client, changes = changes)
            runCurrent() // let the eviction collector subscribe.

            service.describe(device())
            assertEquals(1, hits())

            // Device leaves (byebye / expiry / network reset all arrive as Removed).
            changes.emit(DeviceChange.Removed(device(), DeviceChange.Removed.Reason.Byebye))
            runCurrent()

            service.describe(device())
            assertEquals(2, hits()) // cache was evicted → refetched.
        }

    @Test
    fun networkResetEvictsViaRemovedNetworkChanged() =
        runTest {
            val (client, hits) = countingClient()
            val changes = MutableSharedFlow<DeviceChange>(extraBufferCapacity = 16)
            val service = newService(client, changes = changes)
            runCurrent()

            service.describe(device())
            changes.emit(DeviceChange.Removed(device(), DeviceChange.Removed.Reason.NetworkChanged))
            runCurrent()

            service.describe(device())
            assertEquals(2, hits())
        }

    @Test
    fun malformedXmlYieldsParseFailed() =
        runTest {
            val (client, _) = countingClient(body = DescriptionFixtures.MALFORMED)
            val service = newService(client)
            val result = service.describe(device())
            val error = result.assertFailure<DescriptionException.ParseFailed>()
            // A genuinely-XML-but-broken body keeps the parser's detail (the xmlutil
            // message), so the friendly "not an XML document" wording must NOT apply.
            assertFalse(error.message.contains("not an XML document"))
        }

    @Test
    fun nonXmlBodyYieldsClearParseFailedWithoutHittingParser() =
        runTest {
            // 200 OK, body is not XML at all (a status endpoint). Content-Type is
            // text/xml (the mock always sets it), so this proves the sniff catches
            // it on the body, independent of the — often unreliable — header.
            val (client, _) = countingClient(body = DescriptionFixtures.NOT_XML)
            val service = newService(client)
            val result = service.describe(device())
            val error = result.assertFailure<DescriptionException.ParseFailed>()
            // Friendly, actionable message that echoes what came back — not the raw
            // "1:10 - Non-whitespace text where not expected" from xmlutil.
            assertTrue(error.message.contains("not an XML document"))
            assertTrue(error.message.contains("status=ok"))
            assertFalse(error.message.contains("Non-whitespace text"))
        }

    @Test
    fun bomPrefixedXmlStillParses() =
        runTest {
            // A valid description with a leading UTF-8 BOM + blank line must NOT be
            // mistaken for non-XML by the content sniff (regression guard).
            val (client, _) = countingClient(body = DescriptionFixtures.BOM_PREFIXED_XML)
            val service = newService(client)
            val result = service.describe(device())
            assertEquals("Sonos Arc Ultra", result.assertSuccess().device.modelName)
        }

    // --- refresh -------------------------------------------------------------

    @Test
    fun refreshBypassesCacheAndRefetches() =
        runTest {
            val (client, hits) = countingClient()
            val service = newService(client)
            service.describe(device())
            assertEquals(1, hits())
            // A non-refresh call would serve the cache; refresh forces a new hit.
            val result = service.describe(device(), refresh = true)
            result.assertSuccess()
            assertEquals(2, hits())
        }

    @Test
    fun concurrentRefreshesForSameUsnStillCauseOneFetch() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val count = atomic(0)
            val engine =
                MockEngine {
                    count.incrementAndGet()
                    gate.await()
                    respond(
                        content = ByteReadChannel(DescriptionFixtures.SONOS_ZONEPLAYER),
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", "text/xml"),
                    )
                }
            val service = newService(HttpClient(engine))

            val a = async { service.describe(device(), refresh = true) }
            val b = async { service.describe(device(), refresh = true) }
            runCurrent()
            gate.complete(Unit)
            a.await()
            b.await()
            assertEquals(1, count.value) // two concurrent refreshes coalesce.
        }

    @Test
    fun failedRefreshKeepsPreviousCachedSuccess() =
        runTest {
            // First fetch succeeds and caches. Then the device starts failing and a
            // refresh is issued: the caller sees the failure, but the prior Success
            // must survive (a transient blip shouldn't evict good data).
            var fail = false
            val engine =
                MockEngine {
                    if (fail) {
                        respondError(HttpStatusCode.InternalServerError)
                    } else {
                        respond(
                            content = ByteReadChannel(DescriptionFixtures.SONOS_ZONEPLAYER),
                            status = HttpStatusCode.OK,
                            headers = headersOf("Content-Type", "text/xml"),
                        )
                    }
                }
            val service = newService(HttpClient(engine))

            val first = service.describe(device())
            assertEquals(first.assertSuccess(), service.cachedDescription(device().usn))

            fail = true
            val refreshed = service.describe(device(), refresh = true)
            refreshed.assertFailure<DescriptionException.FetchFailed>()
            // The cached Success is intact despite the failed refresh.
            assertEquals(first.assertSuccess(), service.cachedDescription(device().usn))
        }

    // --- synchronous cache peek ----------------------------------------------

    @Test
    fun cachedDescriptionIsNullBeforeFetchAndPopulatedAfterSuccess() =
        runTest {
            val (client, _) = countingClient()
            val service = newService(client)
            assertEquals(null, service.cachedDescription(device().usn)) // never fetched.

            val result = service.describe(device())
            assertEquals(result.assertSuccess(), service.cachedDescription(device().usn))
        }

    @Test
    fun cachedDescriptionStaysNullAfterAFailedFetch() =
        runTest {
            val (client, _) = countingClient(status = HttpStatusCode.NotFound)
            val service = newService(client)
            service.describe(device())
            // A failure is negative-cached but is NOT a DeviceDescription — the sync
            // peek only ever exposes successes.
            assertEquals(null, service.cachedDescription(device().usn))
        }

    @Test
    fun cachedDescriptionClearedWhenDeviceEvicted() =
        runTest {
            val (client, _) = countingClient()
            val changes = MutableSharedFlow<DeviceChange>(extraBufferCapacity = 16)
            val service = newService(client, changes = changes)
            runCurrent()

            service.describe(device())
            assertTrue(service.cachedDescription(device().usn) != null)

            changes.emit(DeviceChange.Removed(device(), DeviceChange.Removed.Reason.Byebye))
            runCurrent()
            assertEquals(null, service.cachedDescription(device().usn)) // evicted from snapshot too.
        }

    // --- per-network memory ----------------------------------------------------

    private val home = NetworkKey(transportTag = "Wifi", subnet = "192.168.4.0/24")
    private val cafe = NetworkKey(transportTag = "Wifi", subnet = "10.0.0.0/24")

    @Test
    fun switchingNetworkClearsTheCache() =
        runTest {
            val (client, hits) = countingClient()
            val service = newService(client)
            service.describe(device())

            service.switchNetwork(home, cafe)
            assertEquals(null, service.cachedDescription(device().usn))
            service.describe(device())
            assertEquals(2, hits()) // nothing cached on the new network.
        }

    @Test
    fun returningToANetworkRestoresDescriptionWhenDeviceIsFoundAgain() =
        runTest {
            val (client, hits) = countingClient()
            val changes = MutableSharedFlow<DeviceChange>(extraBufferCapacity = 16)
            val service = newService(client, changes = changes)
            runCurrent()
            val first = service.describe(device())
            first.assertSuccess()

            service.switchNetwork(home, cafe)
            service.switchNetwork(cafe, home)
            // Parked, not yet live: the device hasn't been re-found on this network.
            assertEquals(null, service.cachedDescription(device().usn))

            changes.emit(DeviceChange.Found(device()))
            runCurrent()
            assertEquals(first.assertSuccess(), service.cachedDescription(device().usn))
            service.describe(device()).assertSuccess()
            assertEquals(1, hits()) // restored, never refetched.
        }

    @Test
    fun describeRestoresParkedDescriptionBeforeFoundIsSeen() =
        runTest {
            val (client, hits) = countingClient()
            val service = newService(client)
            service.describe(device())

            service.switchNetwork(home, cafe)
            service.switchNetwork(cafe, home)
            service.describe(device()).assertSuccess()
            assertEquals(1, hits())
        }

    @Test
    fun deviceBackAtADifferentLocationIsRefetched() =
        runTest {
            val (client, hits) = countingClient()
            val service = newService(client)
            service.describe(device())

            service.switchNetwork(home, cafe)
            service.switchNetwork(cafe, home)
            service.describe(device(location = "http://192.168.4.21:1400/xml/device_description.xml"))
            assertEquals(2, hits()) // the parked document is for the old LOCATION.
        }

    @Test
    fun networkWithoutASubnetIsNotParked() =
        runTest {
            val (client, hits) = countingClient()
            val service = newService(client)
            val unknown = NetworkKey(transportTag = "Wifi", subnet = null)
            service.describe(device())

            service.switchNetwork(unknown, cafe)
            service.switchNetwork(cafe, unknown)
            service.describe(device())
            assertEquals(2, hits()) // two LANs could share this key, so nothing was kept.
        }

    @Test
    fun parkedNetworkExpiresAfterItsTtl() =
        runTest {
            val (client, hits) = countingClient()
            val service = newService(client, parkedTtl = 1.hours)
            service.describe(device())

            service.switchNetwork(home, cafe)
            advanceTimeBy(2.hours)
            service.switchNetwork(cafe, home)
            service.describe(device())
            assertEquals(2, hits())
        }

    @Test
    fun leastRecentlyLeftNetworkIsDroppedPastTheLimit() =
        runTest {
            val (client, hits) = countingClient()
            val service = newService(client, maxParkedNetworks = 1)
            val office = NetworkKey(transportTag = "Ethernet", subnet = "172.16.0.0/24")
            service.describe(device()) // on home

            service.switchNetwork(home, cafe)
            service.describe(device()) // on cafe
            service.switchNetwork(cafe, office) // parks cafe, dropping home
            service.switchNetwork(office, home)
            service.describe(device())
            assertEquals(3, hits())
        }

    @Test
    fun byebyeDropsAParkedDescription() =
        runTest {
            val (client, hits) = countingClient()
            val changes = MutableSharedFlow<DeviceChange>(extraBufferCapacity = 16)
            val service = newService(client, changes = changes)
            runCurrent()
            service.describe(device())

            service.switchNetwork(home, cafe)
            service.switchNetwork(cafe, home)
            changes.emit(DeviceChange.Removed(device(), DeviceChange.Removed.Reason.Byebye))
            runCurrent()
            service.describe(device())
            assertEquals(2, hits())
        }

    @Test
    fun oldNetworksResetDoesNotDropTheNewNetworksParkedDescription() =
        runTest {
            val (client, hits) = countingClient()
            val changes = MutableSharedFlow<DeviceChange>(extraBufferCapacity = 16)
            val service = newService(client, changes = changes)
            runCurrent()
            service.describe(device())

            service.switchNetwork(home, cafe)
            service.switchNetwork(cafe, home)
            // The registry reset that follows a switch emits Removed(NetworkChanged)
            // for the old network's devices, which can share a USN with a parked one.
            changes.emit(DeviceChange.Removed(device(), DeviceChange.Removed.Reason.NetworkChanged))
            runCurrent()
            service.describe(device())
            assertEquals(1, hits())
        }

    @Test
    fun fetchInFlightAcrossASwitchIsNotCachedOnTheNewNetwork() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val count = atomic(0)
            val engine =
                MockEngine {
                    count.incrementAndGet()
                    gate.await()
                    respond(
                        content = ByteReadChannel(DescriptionFixtures.SONOS_ZONEPLAYER),
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", "text/xml"),
                    )
                }
            val service = newService(HttpClient(engine))

            val pending = async { service.describe(device()) }
            runCurrent()
            service.switchNetwork(home, cafe)
            gate.complete(Unit)
            pending.await().assertSuccess() // its caller still gets a result…
            assertEquals(null, service.cachedDescription(device().usn)) // …but it isn't cached here.
        }
}
