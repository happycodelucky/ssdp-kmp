/*
 * :ssdp-testing — tests for the public fake.
 */
package com.happycodelucky.ssdp.testing

import app.cash.turbine.test
import com.happycodelucky.ssdp.DeviceChange
import com.happycodelucky.ssdp.DiscoveredDevice
import com.happycodelucky.ssdp.SearchTarget
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class FakeSsdpClientTest {
    private fun device(usn: String): DiscoveredDevice =
        DiscoveredDevice(
            usn = usn,
            target = SearchTarget.RootDevice,
            location = "http://192.168.1.5/desc.xml",
            server = "Test/1.0",
            cacheControl = null,
            bootId = null,
            configId = null,
            firstSeen = Instant.fromEpochMilliseconds(0),
            lastSeen = Instant.fromEpochMilliseconds(0),
            expiresAt = null,
            otherHeaders = com.happycodelucky.ssdp.SsdpHeaders.EMPTY,
        )

    @Test
    fun emitFoundUpdatesDevicesAndChanges() =
        runTest {
            withFakeSsdpClient { fake ->
                fake.changes.test {
                    fake.emitFound(device("usn-1"))
                    assertTrue(awaitItem() is DeviceChange.Found)
                    cancelAndIgnoreRemainingEvents()
                }
                assertEquals(1, fake.devices.value.size)
            }
        }

    @Test
    fun emitRemovedDropsDeviceWithReason() =
        runTest {
            withFakeSsdpClient { fake ->
                val d = device("usn-1")
                fake.emitFound(d)
                fake.changes.test {
                    fake.emitRemoved(d, DeviceChange.Removed.Reason.Byebye)
                    val change = awaitItem()
                    assertTrue(change is DeviceChange.Removed)
                    assertEquals(DeviceChange.Removed.Reason.Byebye, change.reason)
                    cancelAndIgnoreRemainingEvents()
                }
                assertTrue(fake.devices.value.isEmpty())
            }
        }

    @Test
    fun clearDevicesEmptiesAndEmitsCleared() =
        runTest {
            withFakeSsdpClient { fake ->
                fake.emitFound(device("usn-1"))
                fake.emitFound(device("usn-2"))
                fake.changes.test {
                    fake.clearDevices()
                    val first = awaitItem()
                    assertTrue(first is DeviceChange.Removed)
                    assertEquals(DeviceChange.Removed.Reason.Cleared, first.reason)
                    val second = awaitItem()
                    assertTrue(second is DeviceChange.Removed)
                    assertEquals(DeviceChange.Removed.Reason.Cleared, second.reason)
                    cancelAndIgnoreRemainingEvents()
                }
                assertTrue(fake.devices.value.isEmpty())
                assertEquals(1, fake.clearDevicesCallCount)
            }
        }

    @Test
    fun recordsSearchCalls() =
        runTest {
            withFakeSsdpClient { fake ->
                fake.search(setOf(SearchTarget.All))
                fake.search(setOf(SearchTarget.RootDevice))
                assertEquals(2, fake.searchCallCount)
                assertEquals(
                    listOf(setOf(SearchTarget.All), setOf(SearchTarget.RootDevice)),
                    fake.searchedTargets,
                )
            }
        }

    @Test
    fun withFakeSsdpClientClosesOnExit() =
        runTest {
            lateinit var captured: FakeSsdpClient
            withFakeSsdpClient { fake ->
                captured = fake
                assertEquals(0, fake.closeCallCount)
            }
            assertTrue(captured.wasClosed)
        }

    @Test
    fun setDevicesReplacesWithoutEvents() =
        runTest {
            withFakeSsdpClient { fake ->
                fake.setDevices(listOf(device("a"), device("b")))
                assertEquals(setOf("a", "b"), fake.devices.value.keys)
            }
        }
}
