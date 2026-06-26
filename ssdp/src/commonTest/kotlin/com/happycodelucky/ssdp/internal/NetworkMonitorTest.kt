/*
 * ssdp-kmp — NetworkMonitor tests: reachable transport stream + subnet probe →
 * reset trigger. Pure orchestration, no reachable or real NIC needed.
 */
package com.happycodelucky.ssdp.internal

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkMonitorTest {
    @Test
    fun firstObservationSeedsWithoutFiring() =
        runTest {
            var resets = 0
            val tags = MutableSharedFlow<String>(extraBufferCapacity = 8)
            NetworkMonitor(this, tags, subnetProbe = { "192.168.1.0/24" }, onChange = { resets++ }).start()
            runCurrent()

            tags.emit("Wifi")
            runCurrent()
            assertEquals(0, resets) // first key seeds the baseline, no reset.

            coroutineContext.cancelChildren()
        }

    @Test
    fun transportChangeFiresReset() =
        runTest {
            var resets = 0
            val tags = MutableSharedFlow<String>(extraBufferCapacity = 8)
            NetworkMonitor(this, tags, subnetProbe = { "192.168.1.0/24" }, onChange = { resets++ }).start()
            runCurrent()

            tags.emit("Wifi")
            runCurrent()
            tags.emit("Cellular")
            runCurrent()
            assertEquals(1, resets)

            coroutineContext.cancelChildren()
        }

    @Test
    fun subnetChangeUnderSameTransportFiresReset() =
        runTest {
            var resets = 0
            var subnet = "192.168.1.0/24"
            val tags = MutableSharedFlow<String>(extraBufferCapacity = 8)
            NetworkMonitor(this, tags, subnetProbe = { subnet }, onChange = { resets++ }).start()
            runCurrent()

            tags.emit("Wifi")
            runCurrent()
            // Same transport tag, but a Wi-Fi reconnect landed us on a different
            // LAN. NetworkMonitor re-probes the subnet on every emission and
            // dedups on the full key, so this still fires a reset.
            subnet = "10.0.0.0/24"
            tags.emit("Wifi")
            runCurrent()
            assertEquals(1, resets)

            coroutineContext.cancelChildren()
        }

    @Test
    fun repeatedIdenticalKeyDoesNotFire() =
        runTest {
            var resets = 0
            val tags = MutableSharedFlow<String>(extraBufferCapacity = 8)
            NetworkMonitor(this, tags, subnetProbe = { "192.168.1.0/24" }, onChange = { resets++ }).start()
            runCurrent()

            tags.emit("Wifi")
            runCurrent()
            // distinctUntilChanged collapses the duplicate tag; no second key, no reset.
            tags.emit("Wifi")
            runCurrent()
            assertEquals(0, resets)

            coroutineContext.cancelChildren()
        }
}

private fun kotlin.coroutines.CoroutineContext.cancelChildren() {
    this[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
}
