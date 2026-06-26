/*
 * ssdp-kmp — internal header-key constants (ported from swift-ssdp's
 * SSDPHeaderKeys). Header names in the SSDP wire format are case-insensitive;
 * these constants use the canonical UPnP-1.0 / UPnP-1.1 capitalization for
 * outbound serialization and for `SsdpHeaders` lookups (which uppercase keys).
 */
package com.happycodelucky.ssdp.internal

internal object SsdpHeaderKeys {
    // RFC 2616 / UPnP 1.0
    const val CACHE_CONTROL = "CACHE-CONTROL"
    const val DATE = "DATE"
    const val EXT = "EXT"
    const val HOST = "HOST"
    const val LOCATION = "LOCATION"
    const val MAN = "MAN"
    const val MAX_WAIT = "MX"
    const val NOTIFY_TYPE = "NT"
    const val NOTIFY_SUB_TYPE = "NTS"
    const val SEARCH_TARGET = "ST"
    const val SERVER = "SERVER"
    const val USN = "USN"

    // UPnP 1.1 additions
    const val BOOT_ID = "BOOTID.UPNP.ORG"
    const val CONFIG_ID = "CONFIGID.UPNP.ORG"
    const val SEARCH_PORT = "SEARCHPORT.UPNP.ORG"
    const val SECURE_LOCATION = "SECURELOCATION.UPNP.ORG"
    const val NEXT_BOOT_ID = "NEXTBOOTID.UPNP.ORG"
}
