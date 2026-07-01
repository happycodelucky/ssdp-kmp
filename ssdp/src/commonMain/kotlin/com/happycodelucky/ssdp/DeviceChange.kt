/*
 * ssdp-kmp — registry change events.
 *
 * Emitted on [com.happycodelucky.ssdp.SsdpClient.changes]. Sealed so SKIE
 * renders it as an exhaustive Swift enum — Swift consumers `switch` over it
 * with no `default` branch.
 */
package com.happycodelucky.ssdp

/**
 * A change to the device registry.
 *
 * The registry also exposes the full current set as a `StateFlow` via
 * [com.happycodelucky.ssdp.SsdpClient.devices]; [DeviceChange] is the
 * event-stream complement for consumers that want deltas rather than snapshots.
 */
public sealed interface DeviceChange {
    /** The device the change concerns. */
    public val device: DiscoveredDevice

    /** A device was seen for the first time (added to the registry). */
    public data class Found(
        override val device: DiscoveredDevice,
    ) : DeviceChange

    /**
     * An already-tracked device re-announced with changed material — a new
     * `BOOTID.UPNP.ORG` (reboot), a changed `LOCATION`, or a refreshed
     * `max-age`. [device] is the post-update record.
     */
    public data class Updated(
        override val device: DiscoveredDevice,
    ) : DeviceChange

    /**
     * A device left the registry. [reason] distinguishes an explicit
     * `ssdp:byebye` from a silent cache-control expiry from a network reset
     * from a manual clear.
     */
    public data class Removed(
        override val device: DiscoveredDevice,
        val reason: Reason,
    ) : DeviceChange {
        /** Why a device was removed. */
        public enum class Reason {
            /** The device broadcast an `ssdp:byebye`. */
            Byebye,

            /** The device's `CACHE-CONTROL: max-age` elapsed without a re-announcement. */
            Expired,

            /** The active network changed; the registry was cleared (plan decision 4). */
            NetworkChanged,

            /** The consumer explicitly cleared the registry via [SsdpClient.clearDevices]. */
            Cleared,
        }
    }
}
