/*
 * ssdp-kmp — device registry lifecycle tests, all under runTest virtual time.
 *
 * Covers the transition/removal paths the registry adds over swift-ssdp:
 * Found on first sight, Updated on a material change (bootId), Removed on
 * byebye, Removed on max-age expiry, a full NetworkChanged reset, and a
 * Cleared reset (consumer-initiated clear).
 */
package com.happycodelucky.ssdp.internal

import app.cash.turbine.test
import com.happycodelucky.ssdp.DeviceChange
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
class DeviceRegistryTest {
    private fun TestScope.newRegistry(): DeviceRegistry = DeviceRegistry(scope = this, clock = TestClock(testScheduler))

    @Test
    fun aliveAddsDeviceAsFound() =
        runTest {
            val registry = newRegistry()
            registry.changes.test {
                ingestAlive(registry)
                val change = awaitItem()
                assertTrue(change is DeviceChange.Found)
                assertEquals(ROKU_USN, change.device.usn)
                assertEquals("http://192.168.1.77:8060/dial/dd.xml", change.device.location)
                assertEquals(1800.seconds, change.device.cacheControl)
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(1, registry.deviceSet.value.size)
        }

    @Test
    fun bootIdChangeYieldsUpdated() =
        runTest {
            val registry = newRegistry()
            registry.changes.test {
                ingestAlive(registry, bootId = 7)
                assertTrue(awaitItem() is DeviceChange.Found)

                // Re-announce with a new BOOTID → device rebooted → Updated.
                ingestAlive(registry, bootId = 8)
                val change = awaitItem()
                assertTrue(change is DeviceChange.Updated)
                assertEquals(8, change.device.bootId)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun identicalReannouncementYieldsNoEvent() =
        runTest {
            val registry = newRegistry()
            registry.changes.test {
                ingestAlive(registry, bootId = 7)
                assertTrue(awaitItem() is DeviceChange.Found)

                // Same bootId/location/cacheControl → pure refresh, no event.
                ingestAlive(registry, bootId = 7)
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun byebyeRemovesDevice() =
        runTest {
            val registry = newRegistry()
            registry.changes.test {
                ingestAlive(registry)
                assertTrue(awaitItem() is DeviceChange.Found)

                registry.ingestNotification(
                    parseNotify(
                        wire(
                            "NOTIFY * HTTP/1.1",
                            "NT: urn:dial-multiscreen-org:device:dial:1",
                            "NTS: ssdp:byebye",
                            "USN: $ROKU_USN",
                        ),
                    ),
                )
                val change = awaitItem()
                assertTrue(change is DeviceChange.Removed)
                assertEquals(DeviceChange.Removed.Reason.Byebye, change.reason)
                cancelAndIgnoreRemainingEvents()
            }
            assertTrue(registry.deviceSet.value.isEmpty())
        }

    @Test
    fun maxAgeExpiryRemovesDevice() =
        runTest {
            val registry = newRegistry()
            registry.changes.test {
                // max-age = 1800s. After it elapses with no re-announce → expire.
                ingestAlive(registry)
                assertTrue(awaitItem() is DeviceChange.Found)

                advanceTimeBy(1800.seconds + 1.seconds)
                runCurrent()

                val change = awaitItem()
                assertTrue(change is DeviceChange.Removed)
                assertEquals(DeviceChange.Removed.Reason.Expired, change.reason)
                cancelAndIgnoreRemainingEvents()
            }
            assertTrue(registry.deviceSet.value.isEmpty())
        }

    @Test
    fun reannouncementBeforeExpiryKeepsDeviceAlive() =
        runTest {
            val registry = newRegistry()
            registry.changes.test {
                ingestAlive(registry, bootId = 7)
                assertTrue(awaitItem() is DeviceChange.Found)

                // Halfway to expiry, re-announce identically → refresh resets the
                // timer; no Updated (nothing material changed) and no Expired.
                advanceTimeBy(900.seconds)
                ingestAlive(registry, bootId = 7)
                runCurrent()
                expectNoEvents()

                // Advance past the *original* deadline but within the refreshed
                // window — must NOT expire.
                advanceTimeBy(1000.seconds)
                runCurrent()
                expectNoEvents()

                assertEquals(1, registry.deviceSet.value.size)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun resetEvictsAllWithNetworkChangedReason() =
        runTest {
            val registry = newRegistry()
            registry.changes.test {
                ingestAlive(registry)
                assertTrue(awaitItem() is DeviceChange.Found)

                registry.reset()
                val change = awaitItem()
                assertTrue(change is DeviceChange.Removed)
                assertEquals(DeviceChange.Removed.Reason.NetworkChanged, change.reason)
                cancelAndIgnoreRemainingEvents()
            }
            assertTrue(registry.deviceSet.value.isEmpty())
        }

    @Test
    fun resetWithClearedReasonEvictsAllAsCleared() =
        runTest {
            val registry = newRegistry()
            registry.changes.test {
                ingestAlive(registry)
                assertTrue(awaitItem() is DeviceChange.Found)

                registry.reset(DeviceChange.Removed.Reason.Cleared)
                val change = awaitItem()
                assertTrue(change is DeviceChange.Removed)
                assertEquals(DeviceChange.Removed.Reason.Cleared, change.reason)
                cancelAndIgnoreRemainingEvents()
            }
            assertTrue(registry.deviceSet.value.isEmpty())
        }

    // --- helpers ------------------------------------------------------------

    private suspend fun ingestAlive(
        registry: DeviceRegistry,
        bootId: Int = 7,
    ) {
        registry.ingestNotification(
            parseNotify(
                wire(
                    "NOTIFY * HTTP/1.1",
                    "CACHE-CONTROL: max-age=1800",
                    "LOCATION: http://192.168.1.77:8060/dial/dd.xml",
                    "NT: urn:dial-multiscreen-org:device:dial:1",
                    "NTS: ssdp:alive",
                    "USN: $ROKU_USN",
                    "BOOTID.UPNP.ORG: $bootId",
                    "CONFIGID.UPNP.ORG: 1",
                ),
            ),
        )
    }

    private fun wire(vararg lines: String): String = lines.joinToString("\r\n") + "\r\n\r\n"

    private fun parseNotify(raw: String): com.happycodelucky.ssdp.Notification =
        (SsdpMessageParser.parse(raw) as SsdpMessage.Notify).notification

    private companion object {
        const val ROKU_USN = "uuid:roku:ecp:YR0070123456::urn:dial-multiscreen-org:device:dial:1"
    }
}
