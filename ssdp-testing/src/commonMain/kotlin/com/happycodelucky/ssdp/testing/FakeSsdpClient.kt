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
import com.happycodelucky.ssdp.DeviceDescription
import com.happycodelucky.ssdp.DiscoveredDevice
import com.happycodelucky.ssdp.SearchTarget
import com.happycodelucky.ssdp.SsdpClient
import com.happycodelucky.ssdp.SsdpDeviceListener
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
 * Registered [SsdpDeviceListener]s are driven in lockstep with the emit helpers —
 * each `emit*` (and [clearDevices]) synchronously invokes the matching listener
 * callback right after emitting on [changes], so a test asserting on a listener
 * sees callbacks without advancing the scheduler.
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
// This test double necessarily mirrors the full SsdpClient surface (every
// interface method) plus its scripting/assertion helpers, so its function count
// tracks the interface and only grows. TooManyFunctions doesn't apply to a fake.
@Suppress("TooManyFunctions")
public class FakeSsdpClient : SsdpClient {
    private val _devices = MutableStateFlow<Map<String, DiscoveredDevice>>(emptyMap())
    override val devices: StateFlow<Map<String, DiscoveredDevice>> = _devices.asStateFlow()

    private val _changes = MutableSharedFlow<DeviceChange>(replay = 0, extraBufferCapacity = 64)
    override val changes: SharedFlow<DeviceChange> = _changes.asSharedFlow()

    // Registered listeners, driven in lockstep with the emit* helpers below.
    // Single-threaded test usage, so a plain list suffices (no lock).
    private val listeners = mutableListOf<SsdpDeviceListener>()

    private val _searchCallCount = atomic(0)
    private val _stopSearchCallCount = atomic(0)
    private val _clearDevicesCallCount = atomic(0)
    private val _closeCallCount = atomic(0)

    /** Targets passed to each [search] call, in order. */
    public val searchedTargets: MutableList<Set<SearchTarget>> = mutableListOf()

    /** Timeout passed to each [search] call, in order (`null` = no timeout). */
    public val searchedTimeouts: MutableList<kotlin.time.Duration?> = mutableListOf()

    /** Number of [search] invocations. */
    public val searchCallCount: Int get() = _searchCallCount.value

    /** Number of [stopSearch] invocations. */
    public val stopSearchCallCount: Int get() = _stopSearchCallCount.value

    /** Number of [clearDevices] invocations. */
    public val clearDevicesCallCount: Int get() = _clearDevicesCallCount.value

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

    /**
     * USNs passed to [description] with `refresh = true`, in call order — lets a
     * test assert a manual reload actually requested a refresh.
     */
    public val descriptionRefreshRequests: MutableList<String> = mutableListOf()

    // Fake cache backing [cachedDescription]: populated when a [description] call
    // resolves to Success (mirroring the real client's cache-on-success), and
    // directly settable via [stubCachedDescription].
    private val cachedDescriptions = mutableMapOf<String, DeviceDescription>()

    /** Script the [description] result for a specific USN. */
    public fun stubDescription(
        usn: String,
        result: DescriptionResult,
    ) {
        scriptedDescriptions[usn] = result
    }

    /**
     * Seed the synchronous [cachedDescription] cache for [usn] directly, without a
     * [description] call — for testing a consumer's render-time cache peek.
     */
    public fun stubCachedDescription(
        usn: String,
        description: DeviceDescription,
    ) {
        cachedDescriptions[usn] = description
    }

    // --- Scripting the registry ---------------------------------------------

    /** Add [device] to [devices] and emit a [DeviceChange.Found]. */
    @OptIn(ExperimentalObjCName::class)
    @ObjCName("emitFound")
    public suspend fun emitFound(device: DiscoveredDevice) {
        _devices.value = _devices.value + (device.usn to device)
        val change = DeviceChange.Found(device)
        _changes.emit(change)
        notifyListeners(change)
    }

    /** Replace [device] in [devices] and emit a [DeviceChange.Updated]. */
    @OptIn(ExperimentalObjCName::class)
    @ObjCName("emitUpdated")
    public suspend fun emitUpdated(device: DiscoveredDevice) {
        _devices.value = _devices.value + (device.usn to device)
        val change = DeviceChange.Updated(device)
        _changes.emit(change)
        notifyListeners(change)
    }

    /** Remove [device] from [devices] and emit a [DeviceChange.Removed] with [reason]. */
    @OptIn(ExperimentalObjCName::class)
    @ObjCName("emitRemoved")
    public suspend fun emitRemoved(
        device: DiscoveredDevice,
        reason: DeviceChange.Removed.Reason,
    ) {
        _devices.value = _devices.value - device.usn
        val change = DeviceChange.Removed(device, reason)
        _changes.emit(change)
        notifyListeners(change)
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

    override suspend fun clearDevices() {
        _clearDevicesCallCount.incrementAndGet()
        val evicted = _devices.value.values.toList()
        _devices.value = emptyMap()
        evicted.forEach { device ->
            val change = DeviceChange.Removed(device, DeviceChange.Removed.Reason.Cleared)
            _changes.emit(change)
            notifyListeners(change)
        }
    }

    override fun addListener(listener: SsdpDeviceListener) {
        if (wasClosed) return
        if (listener !in listeners) listeners.add(listener)
    }

    override fun removeListener(listener: SsdpDeviceListener) {
        listeners.remove(listener)
    }

    /** Mirror [change] to registered listeners, matching the real client's fan-out. */
    private fun notifyListeners(change: DeviceChange) {
        listeners.toList().forEach { listener ->
            when (change) {
                is DeviceChange.Found -> listener.onFound(change.device)
                is DeviceChange.Updated -> listener.onUpdated(change.device)
                is DeviceChange.Removed -> listener.onRemoved(change.device, change.reason)
            }
        }
    }

    override suspend fun description(
        device: DiscoveredDevice,
        refresh: Boolean,
    ): DescriptionResult = recordAndResolve(device.usn, refresh)

    override suspend fun description(
        usn: String,
        refresh: Boolean,
    ): DescriptionResult = recordAndResolve(usn, refresh)

    private fun recordAndResolve(
        usn: String,
        refresh: Boolean,
    ): DescriptionResult {
        descriptionRequests.add(usn)
        if (refresh) descriptionRefreshRequests.add(usn)
        val result = scriptedDescriptions[usn] ?: defaultDescriptionResult
        // Mirror the real client: a successful fetch populates the sync cache.
        if (result is DescriptionResult.Success) cachedDescriptions[usn] = result.description
        return result
    }

    override fun cachedDescription(device: DiscoveredDevice): DeviceDescription? = cachedDescriptions[device.usn]

    override fun cachedDescription(usn: String): DeviceDescription? = cachedDescriptions[usn]

    override fun close() {
        _closeCallCount.incrementAndGet()
        listeners.clear()
    }
}
