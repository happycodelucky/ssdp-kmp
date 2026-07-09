/*
 * ssdp-kmp — callback listener for registry changes.
 *
 * An additive, imperative complement to [SsdpClient.changes]: consumers that
 * prefer a registered callback over collecting a `SharedFlow` implement this and
 * register it via [SsdpClient.addListener]. The Flow API stays primary — this
 * listener is a thin fan-out over the same `changes` stream, no separate emission
 * path (CLAUDE.md §6/§12: a deliberate, additive exception to the "no callbacks"
 * rule). Bridges to Swift as a plain protocol.
 */
package com.happycodelucky.ssdp

/**
 * Receives registry changes as callbacks — the imperative counterpart to
 * observing [SsdpClient.changes].
 *
 * Register with [SsdpClient.addListener] and remove with
 * [SsdpClient.removeListener]. Callbacks fire on the client's internal scope in
 * the order changes occur; the same three events surface here as the sealed
 * [DeviceChange] cases do on the flow. Registration may happen before or after
 * [SsdpClient.search] — passive listening is always live — and all listeners are
 * dropped when the client is closed.
 *
 * Callbacks must not throw; a thrown exception is caught and logged so it can't
 * stall delivery to other listeners. Do not perform long-running work inline —
 * hand off to your own scope.
 *
 * From Swift this is a protocol:
 *
 * ```swift
 * final class Listener: SsdpDeviceListener {
 *     func onFound(device: DiscoveredDevice) { ... }
 *     func onUpdated(device: DiscoveredDevice) { ... }
 *     func onRemoved(device: DiscoveredDevice, reason: DeviceChangeRemovedReason) { ... }
 * }
 * client.addListener(Listener())
 * ```
 */
public interface SsdpDeviceListener {
    /** A device was seen for the first time. Mirrors [DeviceChange.Found]. */
    public fun onFound(device: DiscoveredDevice)

    /**
     * An already-tracked device re-announced with changed material.
     * Mirrors [DeviceChange.Updated].
     */
    public fun onUpdated(device: DiscoveredDevice)

    /**
     * A device left the registry. [reason] distinguishes byebye / expiry /
     * network reset / manual clear. Mirrors [DeviceChange.Removed].
     */
    public fun onRemoved(
        device: DiscoveredDevice,
        reason: DeviceChange.Removed.Reason,
    )
}
