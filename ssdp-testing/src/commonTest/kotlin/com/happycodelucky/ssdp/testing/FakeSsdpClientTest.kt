/*
 * :ssdp-testing — tests for the public fake.
 */
package com.happycodelucky.ssdp.testing

import app.cash.turbine.test
import com.happycodelucky.ssdp.DescriptionResult
import com.happycodelucky.ssdp.Device
import com.happycodelucky.ssdp.DeviceChange
import com.happycodelucky.ssdp.DeviceDescription
import com.happycodelucky.ssdp.DiscoveredDevice
import com.happycodelucky.ssdp.SearchTarget
import com.happycodelucky.ssdp.SpecVersion
import com.happycodelucky.ssdp.SsdpDeviceListener
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    // --- Listener fan-out ----------------------------------------------------

    private class RecordingListener : SsdpDeviceListener {
        val events = mutableListOf<String>()

        override fun onFound(device: DiscoveredDevice) {
            events.add("found:${device.usn}")
        }

        override fun onUpdated(device: DiscoveredDevice) {
            events.add("updated:${device.usn}")
        }

        override fun onRemoved(
            device: DiscoveredDevice,
            reason: DeviceChange.Removed.Reason,
        ) {
            events.add("removed:${device.usn}:$reason")
        }
    }

    @Test
    fun listenerReceivesFoundUpdatedRemovedInLockstepWithEmits() =
        runTest {
            withFakeSsdpClient { fake ->
                val listener = RecordingListener()
                fake.addListener(listener)

                val d = device("usn-1")
                fake.emitFound(d)
                fake.emitUpdated(d)
                fake.emitRemoved(d, DeviceChange.Removed.Reason.Byebye)

                assertEquals(
                    listOf("found:usn-1", "updated:usn-1", "removed:usn-1:Byebye"),
                    listener.events,
                )
            }
        }

    @Test
    fun clearDevicesNotifiesListenersWithCleared() =
        runTest {
            withFakeSsdpClient { fake ->
                val listener = RecordingListener()
                fake.emitFound(device("a"))
                fake.emitFound(device("b"))
                fake.addListener(listener)
                fake.clearDevices()

                assertEquals(
                    listOf("removed:a:Cleared", "removed:b:Cleared"),
                    listener.events,
                )
            }
        }

    @Test
    fun removeListenerStopsDelivery() =
        runTest {
            withFakeSsdpClient { fake ->
                val listener = RecordingListener()
                fake.addListener(listener)
                fake.removeListener(listener)
                fake.emitFound(device("usn-1"))
                assertTrue(listener.events.isEmpty())
            }
        }

    @Test
    fun closeClearsListeners() =
        runTest {
            val listener = RecordingListener()
            val fake = FakeSsdpClient()
            fake.addListener(listener)
            fake.close()
            // Post-close registration is ignored; no delivery after close.
            fake.addListener(listener)
            fake.emitFound(device("usn-1"))
            assertTrue(listener.events.isEmpty())
            assertFalse(fake.devices.value.isEmpty()) // emit still mutates state
        }

    @Test
    fun stubbedSuccessPopulatesSyncCacheAndRefreshIsRecorded() =
        runTest {
            withFakeSsdpClient { fake ->
                val d = device("usn-desc")
                val desc =
                    DeviceDescription(
                        specVersion = SpecVersion(1, 0),
                        urlBase = null,
                        device =
                            Device(
                                deviceType = "urn:schemas-upnp-org:device:ZonePlayer:1",
                                friendlyName = "Fake",
                                manufacturer = null,
                                manufacturerUrl = null,
                                modelName = null,
                                modelNumber = null,
                                modelDescription = null,
                                modelUrl = null,
                                serialNumber = null,
                                udn = "uuid:usn-desc",
                                upc = null,
                                presentationUrl = null,
                            ),
                        sourceUrl = "http://192.168.1.5/desc.xml",
                    )
                fake.stubDescription("usn-desc", DescriptionResult.Success(desc))

                // Nothing cached until the (scripted) fetch runs.
                assertEquals(null, fake.cachedDescription(d))

                val result = fake.description(d, refresh = true)
                assertTrue(result is DescriptionResult.Success)
                // Success populated the sync cache (mirrors the real client).
                assertEquals(desc, fake.cachedDescription(d))
                assertEquals(desc, fake.cachedDescription("usn-desc"))
                // The refresh flag was recorded for assertion.
                assertEquals(listOf("usn-desc"), fake.descriptionRefreshRequests)
            }
        }

    @Test
    fun stubCachedDescriptionSeedsPeekWithoutAFetch() =
        runTest {
            withFakeSsdpClient { fake ->
                val desc =
                    DeviceDescription(
                        specVersion = SpecVersion(1, 1),
                        urlBase = null,
                        device =
                            Device(
                                deviceType = "urn:schemas-upnp-org:device:Basic:1",
                                friendlyName = null,
                                manufacturer = null,
                                manufacturerUrl = null,
                                modelName = null,
                                modelNumber = null,
                                modelDescription = null,
                                modelUrl = null,
                                serialNumber = null,
                                udn = "uuid:seeded",
                                upc = null,
                                presentationUrl = null,
                            ),
                        sourceUrl = "http://host/desc.xml",
                    )
                fake.stubCachedDescription("seeded", desc)
                assertEquals(desc, fake.cachedDescription("seeded"))
                // No description() call was made.
                assertTrue(fake.descriptionRequests.isEmpty())
            }
        }
}
