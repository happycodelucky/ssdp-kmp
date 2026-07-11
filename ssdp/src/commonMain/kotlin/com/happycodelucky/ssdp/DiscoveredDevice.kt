/*
 * ssdp-kmp — a tracked device in the registry.
 *
 * This type does not exist in swift-ssdp (which stops at parse-and-stream). It
 * is the heart of ssdp-kmp's value-add: a stable, deduplicated record folded
 * from M-SEARCH responses and NOTIFY advertisements, with the lifecycle
 * timestamps the registry needs to drive found / updated / removed transitions.
 */
package com.happycodelucky.ssdp

import com.happycodelucky.ssdp.internal.UpnpUrl
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A device or service currently believed to be present on the network.
 *
 * Identity is [usn] (Unique Service Name) — the registry keys its map by USN,
 * so re-announcements and M-SEARCH responses for the same `usn` update one
 * record rather than creating duplicates.
 *
 * @property usn `USN` — the stable unique identity. The registry key.
 * @property target The `ST`/`NT` this device announced under
 *   ([SearchTarget]). For a device that matches several targets, this is the
 *   most recently observed one.
 * @property location `LOCATION` — URL string of the device description document
 *   (for the v1.1 description-XML fetch). `null` only in the rare case the
 *   device was first seen via a `byebye` (which carries no location).
 * @property server `SERVER` — server identification string, when provided.
 * @property cacheControl `CACHE-CONTROL: max-age` advertised by the device, if
 *   any. Drives [expiresAt].
 * @property bootId `BOOTID.UPNP.ORG` (UPnP 1.1), if provided. A change in this
 *   value across announcements signals a device reboot and yields a
 *   [DeviceChange.Updated].
 * @property configId `CONFIGID.UPNP.ORG` (UPnP 1.1), if provided.
 * @property firstSeen When this device was first added to the registry.
 * @property lastSeen When the registry last received an announcement for it.
 *   Reset on every alive/response.
 * @property expiresAt When the device will be evicted if not re-announced —
 *   `lastSeen + cacheControl`. `null` when the device advertised no `max-age`
 *   (such devices are evicted only by an explicit `byebye` or a network reset).
 * @property otherHeaders Remaining headers from the most recent announcement.
 * @property address The device's network address — the host portion of
 *   [location] (an IPv4/IPv6 literal or hostname), with the `http://`/`https://`
 *   scheme, any port, and the path/query stripped. E.g. a `location` of
 *   `http://192.168.4.20:1400/xml/device_description.xml` yields `192.168.4.20`.
 *   `null` when [location] is `null` or can't be parsed as a URL.
 */
public class DiscoveredDevice(
    public val usn: String,
    public val target: SearchTarget,
    public val location: String?,
    public val server: String?,
    public val cacheControl: Duration?,
    public val bootId: Int?,
    public val configId: Int?,
    public val firstSeen: Instant,
    public val lastSeen: Instant,
    public val expiresAt: Instant?,
    public val otherHeaders: SsdpHeaders,
) {
    /**
     * The device's host address derived from [location] — the IP literal or
     * hostname, with scheme, port, and path discarded. `null` when there is no
     * parseable [location]. See the class-level `address` doc for examples.
     */
    public val address: String?
        get() = location?.let { UpnpUrl.host(it) }

    override fun equals(other: Any?): Boolean = other is DiscoveredDevice && other.usn == usn

    override fun hashCode(): Int = usn.hashCode()

    override fun toString(): String = "DiscoveredDevice(usn=$usn, target=${target.rawValue}, location=$location)"
}
