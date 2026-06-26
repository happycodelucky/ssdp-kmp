/*
 * ssdp-kmp — real-world SSDP wire fixtures, transcribed from swift-ssdp's
 * Tests/SwiftSSDPTests/Fixtures. Embedded as string constants (not file
 * resources) so they load identically on every KMP target — common tests can't
 * read the filesystem portably.
 *
 * Lines are CRLF-terminated to match on-wire bytes; the parser must tolerate
 * the real quirks these capture (spaces around `=`, empty EXT, missing EXT,
 * byebye without LOCATION, extra vendor headers).
 */
package com.happycodelucky.ssdp.internal

internal object Fixtures {
    private fun wire(vararg lines: String): String = lines.joinToString("\r\n") + "\r\n\r\n"

    /** Sonos ZonePlayer M-SEARCH response: `max-age = 1800` (spaced), empty EXT, DATE, vendor headers. */
    val MSEARCH_RESPONSE_SONOS =
        wire(
            "HTTP/1.1 200 OK",
            "CACHE-CONTROL: max-age = 1800",
            "EXT:",
            "LOCATION: http://192.168.1.42:1400/xml/device_description.xml",
            "SERVER: Linux UPnP/1.0 Sonos/76.1-37220 (ZP120)",
            "ST: urn:schemas-upnp-org:device:ZonePlayer:1",
            "USN: uuid:RINCON_000E58A1B2C300400::urn:schemas-upnp-org:device:ZonePlayer:1",
            "X-RINCON-HOUSEHOLD: Sonos_household_1",
            "X-RINCON-BOOTSEQ: 23",
            "DATE: Sun, 06 Nov 1994 08:49:37 GMT",
        )

    /** Philips Hue bridge M-SEARCH response: omits EXT, carries a vendor `hue-bridgeid` header. */
    val MSEARCH_RESPONSE_HUE =
        wire(
            "HTTP/1.1 200 OK",
            "HOST: 239.255.255.250:1900",
            "CACHE-CONTROL: max-age=100",
            "LOCATION: http://192.168.1.55:80/description.xml",
            "SERVER: Hue/1.0 UPnP/1.0 IpBridge/1.55.0",
            "hue-bridgeid: 001788FFFE112233",
            "ST: upnp:rootdevice",
            "USN: uuid:2f402f80-da50-11e1-9b23-001788112233::upnp:rootdevice",
        )

    /** Minimal-but-valid response missing the spec-required EXT header. */
    val MALFORMED_MISSING_EXT =
        wire(
            "HTTP/1.1 200 OK",
            "CACHE-CONTROL: max-age=300",
            "LOCATION: http://192.168.1.99:80/description.xml",
            "SERVER: SomeOldDevice/1.0 UPnP/1.0",
            "ST: upnp:rootdevice",
            "USN: uuid:malformed-but-real::upnp:rootdevice",
        )

    /** Roku NOTIFY ssdp:alive with BOOTID/CONFIGID. */
    val NOTIFY_ALIVE_ROKU =
        wire(
            "NOTIFY * HTTP/1.1",
            "HOST: 239.255.255.250:1900",
            "CACHE-CONTROL: max-age=1800",
            "LOCATION: http://192.168.1.77:8060/dial/dd.xml",
            "NT: urn:dial-multiscreen-org:device:dial:1",
            "NTS: ssdp:alive",
            "SERVER: Roku UPnP/1.0 Roku/12.0.0",
            "USN: uuid:roku:ecp:YR0070123456::urn:dial-multiscreen-org:device:dial:1",
            "BOOTID.UPNP.ORG: 7",
            "CONFIGID.UPNP.ORG: 1",
        )

    /** NOTIFY ssdp:byebye — no LOCATION (device is leaving). */
    val NOTIFY_BYEBYE =
        wire(
            "NOTIFY * HTTP/1.1",
            "HOST: 239.255.255.250:1900",
            "NT: urn:schemas-upnp-org:device:MediaServer:1",
            "NTS: ssdp:byebye",
            "USN: uuid:00000000-0000-0000-0000-aabbccddeeff::urn:schemas-upnp-org:device:MediaServer:1",
        )

    /** NOTIFY ssdp:update — BOOTID changing to NEXTBOOTID (UPnP 1.1). */
    val NOTIFY_UPDATE =
        wire(
            "NOTIFY * HTTP/1.1",
            "HOST: 239.255.255.250:1900",
            "LOCATION: http://192.168.1.42:1400/xml/device_description.xml",
            "NT: upnp:rootdevice",
            "NTS: ssdp:update",
            "USN: uuid:RINCON_000E58A1B2C300400::upnp:rootdevice",
            "BOOTID.UPNP.ORG: 23",
            "NEXTBOOTID.UPNP.ORG: 24",
            "CONFIGID.UPNP.ORG: 1",
        )
}
