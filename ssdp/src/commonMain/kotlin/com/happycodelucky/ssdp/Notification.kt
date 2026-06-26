/*
 * ssdp-kmp — parsed NOTIFY broadcast (ported from swift-ssdp's
 * SSDPNotification + SSDPAdvertisement).
 */
package com.happycodelucky.ssdp

import kotlin.time.Duration

/**
 * An unsolicited NOTIFY broadcast from a device on the local SSDP multicast
 * group. Devices broadcast NOTIFY messages when they join the network ([Alive]),
 * leave ([Byebye]), or change their UPnP boot identity ([Update], UPnP 1.1).
 *
 * The device registry consumes these to drive the
 * found / updated / removed lifecycle; consumers normally observe
 * [com.happycodelucky.ssdp.SsdpClient.changes] rather than raw notifications.
 *
 * Modeled as a sealed interface so SKIE renders it as an exhaustive Swift enum.
 */
public sealed interface Notification {
    /** The advertisement payload, regardless of which case. */
    public val advertisement: Advertisement

    /** The notification target (`NT` header) for the announced device or service. */
    public val notificationTarget: SearchTarget get() = advertisement.notificationTarget

    /** `NTS: ssdp:alive` — device is reachable. */
    public data class Alive(
        override val advertisement: Advertisement,
    ) : Notification

    /**
     * `NTS: ssdp:byebye` — device is leaving the network. Carries no `LOCATION`
     * header (the device is going away, so there's nothing to fetch); the
     * advertisement's [Advertisement.location] will be `null`.
     */
    public data class Byebye(
        override val advertisement: Advertisement,
    ) : Notification

    /** `NTS: ssdp:update` — device's `BOOTID.UPNP.ORG` is changing (UPnP 1.1). */
    public data class Update(
        override val advertisement: Advertisement,
    ) : Notification
}

/**
 * The data payload of a NOTIFY message.
 *
 * Closely related to [MSearchResponse] but distinct — NOTIFY uses `NT`
 * (Notification Target) where M-SEARCH responses use `ST` (Search Target), and
 * NOTIFY has no `EXT` header. [location] is optional because `byebye`
 * notifications omit it.
 *
 * Equality / hashing use `(usn, notificationTarget)` — the natural NOTIFY
 * deduplication key (matching the Swift implementation).
 *
 * @property notificationTarget `NT` — identifies what kind of device/service is
 *   announcing.
 * @property usn `USN` — Unique Service Name.
 * @property location `LOCATION` — URL string of the device description document.
 *   Always present in `alive`/`update`; `null` in `byebye`.
 * @property server `SERVER` — server identification string.
 * @property cacheControl `CACHE-CONTROL: max-age=<seconds>` validity duration,
 *   or `null` if absent.
 * @property bootId `BOOTID.UPNP.ORG` — UPnP 1.1 boot identifier.
 * @property configId `CONFIGID.UPNP.ORG` — UPnP 1.1 configuration identifier.
 * @property nextBootId `NEXTBOOTID.UPNP.ORG` — UPnP 1.1, present only on
 *   `ssdp:update`.
 * @property otherHeaders All headers not surfaced as typed properties above.
 */
public class Advertisement(
    public val notificationTarget: SearchTarget,
    public val usn: String,
    public val location: String?,
    public val server: String?,
    public val cacheControl: Duration?,
    public val bootId: Int?,
    public val configId: Int?,
    public val nextBootId: Int?,
    public val otherHeaders: SsdpHeaders,
) {
    override fun equals(other: Any?): Boolean = other is Advertisement && other.usn == usn && other.notificationTarget == notificationTarget

    override fun hashCode(): Int = 31 * usn.hashCode() + notificationTarget.hashCode()

    override fun toString(): String = "Advertisement(usn=$usn, nt=${notificationTarget.rawValue}, location=$location)"
}
