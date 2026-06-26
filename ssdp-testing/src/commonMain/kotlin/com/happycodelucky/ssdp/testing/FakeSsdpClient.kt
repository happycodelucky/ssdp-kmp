/*
 * :ssdp-testing — public, scriptable fake for the SsdpClient interface.
 *
 * Unlike reachable (which has a process singleton + installForTesting), SsdpClient
 * is always constructed per-use, so the fake simply implements SsdpClient directly
 * with an in-memory registry tests can drive. No real multicast socket, no
 * platform dependency — usable from commonTest on every target.
 */
package com.happycodelucky.ssdp.testing

import com.happycodelucky.ssdp.DescriptionResult
import com.happycodelucky.ssdp.DeviceChange
import com.happycodelucky.ssdp.DiscoveredDevice
import com.happycodelucky.ssdp.SearchTarget
import com.happycodelucky.ssdp.SsdpClient
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * Scriptable fake [SsdpClient] for tests.
 *
 * Drives [devices] and [changes] from in-memory state instead of a multicast
 * socket. Push devices in with [emitFound] / [emitUpdated] / [emitRemoved] (which
 * keep [devices] and [changes] consistent) or replace the whole set with
 * [setDevices]. Records every [search] / [stopSearch] / [close] call so a test can
 * assert the unit under test drove discovery as expected.
 *
 * ### Driving discovery
 *
 * ```kotlin
 * val fake = FakeSsdpClient()
 * fake.emitFound(device)          // adds to devices, emits DeviceChange.Found
 * fake.emitRemoved(device, DeviceChange.Removed.Reason.Byebye)
 * ```
 *
 * ### Asserting calls
 *
 * ```kotlin
 * fake.search(setOf(SearchTarget.All))
 * assertEquals(listOf(setOf(SearchTarget.All)), fake.searchedTargets)
 * assertEquals(1, fake.searchCallCount)
 * ```
 *
 * Renames cleanly across the Swift bridge: in Swift the class reads as
 * `FakeSsdpClient` with `emitFound(_:)`, `emitRemoved(_:reason:)`, etc.
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName(name = "SsdpTestingFakeSsdpClient", swiftName = "FakeSsdpClient")
public class FakeSsdpClient : SsdpClient {
    private val _devices = MutableStateFlow<Map<String, DiscoveredDevice>>(emptyMap())
    override val devices: StateFlow<Map<String, DiscoveredDevice>> = _devices.asStateFlow()

    private val _changes = MutableSharedFlow<DeviceChange>(replay = 0, extraBufferCapacity = 64)
    override val changes: SharedFlow<DeviceChange> = _changes.asSharedFlow()

    private val _searchCallCount = atomic(0)
    private val _stopSearchCallCount = atomic(0)
    private val _closeCallCount = atomic(0)

    /** Targets passed to each [search] call, in order. */
    public val searchedTargets: MutableList<Set<SearchTarget>> = mutableListOf()

    /** Timeout passed to each [search] call, in order (`null` = no timeout). */
    public val searchedTimeouts: MutableList<kotlin.time.Duration?> = mutableListOf()

    /** Number of [search] invocations. */
    public val searchCallCount: Int get() = _searchCallCount.value

    /** Number of [stopSearch] invocations. */
    public val stopSearchCallCount: Int get() = _stopSearchCallCount.value

    /** Number of [close] invocations. */
    public val closeCallCount: Int get() = _closeCallCount.value

    /** `true` once [close] has been called at least once. */
    public val wasClosed: Boolean get() = _closeCallCount.value > 0

    // --- Description scripting -----------------------------------------------

    private val scriptedDescriptions = mutableMapOf<String, DescriptionResult>()

    /**
     * Result returned by [description] for a USN with no specific stub. Defaults
     * to [DescriptionResult.NotFound] (discovery doesn't auto-fetch, so an
     * un-stubbed device legitimately has no description yet).
     */
    public var defaultDescriptionResult: DescriptionResult = DescriptionResult.NotFound

    /** USNs passed to [description], in call order — asserts lazy-fetch timing. */
    public val descriptionRequests: MutableList<String> = mutableListOf()

    /** Script the [description] result for a specific USN. */
    public fun stubDescription(
        usn: String,
        result: DescriptionResult,
    ) {
        scriptedDescriptions[usn] = result
    }

    // --- Scripting the registry ---------------------------------------------

    /** Add [device] to [devices] and emit a [DeviceChange.Found]. */
    @OptIn(ExperimentalObjCName::class)
    @ObjCName("emitFound")
    public suspend fun emitFound(device: DiscoveredDevice) {
        _devices.value = _devices.value + (device.usn to device)
        _changes.emit(DeviceChange.Found(device))
    }

    /** Replace [device] in [devices] and emit a [DeviceChange.Updated]. */
    @OptIn(ExperimentalObjCName::class)
    @ObjCName("emitUpdated")
    public suspend fun emitUpdated(device: DiscoveredDevice) {
        _devices.value = _devices.value + (device.usn to device)
        _changes.emit(DeviceChange.Updated(device))
    }

    /** Remove [device] from [devices] and emit a [DeviceChange.Removed] with [reason]. */
    @OptIn(ExperimentalObjCName::class)
    @ObjCName("emitRemoved")
    public suspend fun emitRemoved(
        device: DiscoveredDevice,
        reason: DeviceChange.Removed.Reason,
    ) {
        _devices.value = _devices.value - device.usn
        _changes.emit(DeviceChange.Removed(device, reason))
    }

    /** Replace the entire device set without emitting change events. */
    public fun setDevices(devices: List<DiscoveredDevice>) {
        _devices.value = devices.associateBy { it.usn }
    }

    // --- SsdpClient ---------------------------------------------------------

    override suspend fun search(
        targets: Set<SearchTarget>,
        maxWaitSeconds: Int,
        timeout: kotlin.time.Duration?,
    ) {
        _searchCallCount.incrementAndGet()
        searchedTargets.add(targets)
        searchedTimeouts.add(timeout)
    }

    override suspend fun stopSearch() {
        _stopSearchCallCount.incrementAndGet()
    }

    override suspend fun description(device: DiscoveredDevice): DescriptionResult = recordAndResolve(device.usn)

    override suspend fun description(usn: String): DescriptionResult = recordAndResolve(usn)

    private fun recordAndResolve(usn: String): DescriptionResult {
        descriptionRequests.add(usn)
        return scriptedDescriptions[usn] ?: defaultDescriptionResult
    }

    override fun close() {
        _closeCallCount.incrementAndGet()
    }
}
