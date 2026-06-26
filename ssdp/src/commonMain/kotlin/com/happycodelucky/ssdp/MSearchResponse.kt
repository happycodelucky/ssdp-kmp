/*
 * ssdp-kmp — parsed M-SEARCH response (ported from swift-ssdp's
 * SSDPMSearchResponse).
 */
package com.happycodelucky.ssdp

import kotlin.time.Duration

/**
 * A parsed response to an M-SEARCH request, describing one discovered device or
 * service. The device registry ([com.happycodelucky.ssdp.SsdpClient.devices])
 * folds these into [DiscoveredDevice] records; consumers normally observe the
 * registry rather than raw responses.
 *
 * Equality / hashing use `(usn, location)` — the natural SSDP deduplication key.
 *
 * @property cacheControl `CACHE-CONTROL: max-age=<seconds>` as a [Duration], or
 *   `null` if absent. The registry uses this to schedule expiry. (Swift exposed
 *   this as a raw `TimeInterval`; a [Duration] is the idiomatic, SKIE-friendly
 *   Kotlin shape and is what the expiry timer consumes directly.)
 * @property date Raw `DATE` header value (RFC 1123 wall-clock string), or
 *   `null`. Left unparsed: many devices omit it or send non-standard formats,
 *   and nothing in the lifecycle depends on it. Parse on the consumer side if
 *   needed.
 * @property ext `EXT` — a presence-only header required by UPnP 1.0. Real-world
 *   devices (some Hue / Roku firmware) omit it; `ext == false` means the header
 *   was absent.
 * @property location `LOCATION` — URL string of the device description document.
 *   Kept as a `String` (not a parsed URL): description-XML fetch is a v1.1
 *   feature; v1 hands the URL back verbatim.
 * @property server `SERVER` — server identification string, e.g.
 *   `Linux/3.14 UPnP/1.0 Sonos/12.3.1`.
 * @property searchTarget `ST` — the search target the responder is matching.
 * @property usn `USN` — Unique Service Name, a globally unique device/service id.
 * @property otherHeaders All headers not surfaced as a typed property above —
 *   includes UPnP 1.1 fields (`BOOTID.UPNP.ORG`, `CONFIGID.UPNP.ORG`,
 *   `SEARCHPORT.UPNP.ORG`, `SECURELOCATION.UPNP.ORG`) when present.
 */
public class MSearchResponse(
    public val cacheControl: Duration?,
    public val date: String?,
    public val ext: Boolean,
    public val location: String,
    public val server: String?,
    public val searchTarget: SearchTarget,
    public val usn: String,
    public val otherHeaders: SsdpHeaders,
) {
    override fun equals(other: Any?): Boolean = other is MSearchResponse && other.usn == usn && other.location == location

    override fun hashCode(): Int = 31 * usn.hashCode() + location.hashCode()

    override fun toString(): String = "MSearchResponse(usn=$usn, location=$location, searchTarget=${searchTarget.rawValue})"
}
