/*
 * ssdp-kmp — client wiring tests: socket → parser → registry, and
 * search() → M-SEARCH send + retransmit. All under runTest virtual time with a
 * fake socket (no real multicast).
 */
package com.happycodelucky.ssdp.internal

import app.cash.turbine.test
import com.happycodelucky.ssdp.DescriptionResult
import com.happycodelucky.ssdp.DeviceChange
import com.happycodelucky.ssdp.SearchTarget
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SsdpClientImplTest {
    // A MockEngine serving the Sonos description fixture, so the client's
    // description() path is exercised without real HTTP. (The default
    // descriptionHttpClient() would build a real CIO client — fine for tests
    // that never call description(), but we pass a mock so the one that does
    // gets a deterministic body.)
    private fun descriptionMock(): HttpClient =
        HttpClient(
            MockEngine {
                respond(
                    content = ByteReadChannel(DescriptionFixtures.SONOS_ZONEPLAYER),
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", "text/xml"),
                )
            },
        )

    private fun TestScope.newClient(
        socket: FakeMulticastSocket,
        httpClient: HttpClient = descriptionMock(),
    ): SsdpClientImpl =
        SsdpClientImpl(
            socketFactory = { socket },
            parentScope = this,
            clock = TestClock(testScheduler),
            timeSource = TestTimeSource(testScheduler),
            httpClient = httpClient,
        )

    @Test
    fun incomingResponseLandsInRegistry() =
        runTest {
            val socket = FakeMulticastSocket()
            val client = newClient(socket)
            runCurrent() // let the receive-loop coroutine start collecting.

            client.devices.test {
                assertEquals(emptyMap(), awaitItem()) // initial StateFlow value.
                socket.deliver(Fixtures.MSEARCH_RESPONSE_SONOS)
                runCurrent()
                val devices = awaitItem()
                assertEquals(1, devices.size)
                val device = devices.values.single()
                assertEquals(
                    "uuid:RINCON_000E58A1B2C300400::urn:schemas-upnp-org:device:ZonePlayer:1",
                    device.usn,
                )
                assertEquals(1800.seconds, device.cacheControl)
                cancelAndIgnoreRemainingEvents()
            }
            client.close()
        }

    @Test
    fun searchSendsMSearchPerTargetThenRetransmits() =
        runTest {
            val socket = FakeMulticastSocket()
            val client = newClient(socket)
            runCurrent()

            client.search(setOf(SearchTarget.All, SearchTarget.RootDevice), maxWaitSeconds = 2)
            runCurrent()
            // First immediate M-SEARCH for each of the two targets.
            assertEquals(2, socket.sent.size)
            assertTrue(socket.sentText.any { it.contains("ST: ssdp:all") })
            assertTrue(socket.sentText.any { it.contains("ST: upnp:rootdevice") })

            // Advance 1s → one retransmit per target → +2.
            advanceTimeBy(1.seconds)
            runCurrent()
            assertEquals(4, socket.sent.size)

            client.close()
        }

    @Test
    fun stopSearchHaltsRetransmission() =
        runTest {
            val socket = FakeMulticastSocket()
            val client = newClient(socket)
            runCurrent()

            client.search(setOf(SearchTarget.All))
            runCurrent()
            assertEquals(1, socket.sent.size)

            client.stopSearch()
            advanceTimeBy(10.seconds)
            runCurrent()
            // No further sends after stopSearch.
            assertEquals(1, socket.sent.size)

            client.close()
        }

    @Test
    fun searchTimeoutStopsRetransmissionButKeepsDevicesAndListening() =
        runTest {
            val socket = FakeMulticastSocket()
            val client = newClient(socket)
            runCurrent()

            // Retransmit only for 5s.
            client.search(setOf(SearchTarget.All), timeout = 5.seconds)
            runCurrent()
            assertEquals(1, socket.sent.size) // first M-SEARCH.

            // During the window, the stepped cadence retransmits (1s steps to 5s).
            advanceTimeBy(5.seconds)
            runCurrent()
            val sentDuringWindow = socket.sent.size
            assertTrue(sentDuringWindow > 1, "expected retransmits during the window")

            // Well past the timeout — no further broadcasts.
            advanceTimeBy(60.seconds)
            runCurrent()
            assertEquals(sentDuringWindow, socket.sent.size)

            // Passive NOTIFY listening still works after the search timed out:
            // a device arriving now still lands in the registry.
            socket.deliver(Fixtures.NOTIFY_ALIVE_ROKU)
            runCurrent()
            assertEquals(1, client.devices.value.size)

            client.close()
        }

    @Test
    fun emptyTargetSetStopsActiveSearch() =
        runTest {
            val socket = FakeMulticastSocket()
            val client = newClient(socket)
            runCurrent()

            client.search(setOf(SearchTarget.All))
            runCurrent()
            assertEquals(1, socket.sent.size)

            client.search(emptySet())
            advanceTimeBy(10.seconds)
            runCurrent()
            assertEquals(1, socket.sent.size)

            client.close()
        }

    @Test
    fun onNetworkChangedResetsRegistry() =
        runTest {
            val socket = FakeMulticastSocket()
            val client = newClient(socket)
            runCurrent()

            client.changes.test {
                socket.deliver(Fixtures.NOTIFY_ALIVE_ROKU)
                runCurrent()
                assertTrue(awaitItem() is DeviceChange.Found)

                client.onNetworkChanged()
                val change = awaitItem()
                assertTrue(change is DeviceChange.Removed)
                assertEquals(DeviceChange.Removed.Reason.NetworkChanged, change.reason)
                cancelAndIgnoreRemainingEvents()
            }
            client.close()
        }

    @Test
    fun closeClosesSocket() =
        runTest {
            val socket = FakeMulticastSocket()
            val client = newClient(socket)
            runCurrent()
            client.close()
            assertTrue(socket.closed)
        }

    @Test
    fun descriptionFetchesForDiscoveredDevice() =
        runTest {
            val socket = FakeMulticastSocket()
            val client = newClient(socket)
            runCurrent()

            // Discover the Sonos device (its description fixture is what the mock serves).
            socket.deliver(Fixtures.MSEARCH_RESPONSE_SONOS)
            runCurrent()
            val device =
                client.devices.value.values
                    .single()

            val result = client.description(device)
            assertTrue(result is DescriptionResult.Success)
            assertEquals("Sonos Arc Ultra", result.description.device.modelName)

            client.close()
        }

    @Test
    fun descriptionByUsnLooksUpRegistry() =
        runTest {
            val socket = FakeMulticastSocket()
            val client = newClient(socket)
            runCurrent()
            socket.deliver(Fixtures.MSEARCH_RESPONSE_SONOS)
            runCurrent()
            val usn =
                client.devices.value.keys
                    .single()

            val result = client.description(usn)
            assertTrue(result is DescriptionResult.Success)

            // Unknown USN → NotFound.
            assertEquals(DescriptionResult.NotFound, client.description("uuid:does-not-exist"))

            client.close()
        }

    @Test
    fun descriptionAfterCloseReturnsNotFound() =
        runTest {
            val socket = FakeMulticastSocket()
            val client = newClient(socket)
            runCurrent()
            socket.deliver(Fixtures.MSEARCH_RESPONSE_SONOS)
            runCurrent()
            val device =
                client.devices.value.values
                    .single()

            client.close()
            assertEquals(DescriptionResult.NotFound, client.description(device))
        }
}
