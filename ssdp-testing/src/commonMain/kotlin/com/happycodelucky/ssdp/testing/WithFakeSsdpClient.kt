/*
 * :ssdp-testing — exception-safe construct / close helper around FakeSsdpClient.
 *
 * SsdpClient has no process singleton (it's constructed per-use), so unlike
 * reachable's withFakeReachability there's nothing to install/uninstall — this
 * helper just builds a fresh fake, runs the block, and closes the fake on exit
 * (even when the block throws). Pass the fake into the unit under test however it
 * takes its SsdpClient (constructor arg, DI, etc.).
 *
 * Single `suspend` overload; non-suspending tests wrap in `runTest { }`, the
 * idiomatic KMP pattern that also avoids K2 overload-resolution ambiguity.
 */
package com.happycodelucky.ssdp.testing

import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * Construct a fresh [FakeSsdpClient], run [block] with it, and [FakeSsdpClient.close]
 * it on exit. Exception-safe: the close runs even when [block] throws.
 *
 * ```kotlin
 * @Test
 * fun rendersDiscoveredDevices() = runTest {
 *     withFakeSsdpClient { fake ->
 *         val vm = DeviceListViewModel(fake)   // takes an SsdpClient
 *         fake.emitFound(sampleDevice)
 *         assertEquals(1, vm.devices.value.size)
 *     }
 * }
 * ```
 *
 * @param block receives the fake; drive discovery with [FakeSsdpClient.emitFound]
 *   and friends.
 * @return the value returned from [block].
 */
@Throws(Throwable::class)
@OptIn(ExperimentalObjCName::class)
@ObjCName(swiftName = "withFakeSsdpClient")
public suspend fun <R> withFakeSsdpClient(block: suspend (FakeSsdpClient) -> R): R {
    val fake = FakeSsdpClient()
    try {
        return block(fake)
    } finally {
        fake.close()
    }
}
