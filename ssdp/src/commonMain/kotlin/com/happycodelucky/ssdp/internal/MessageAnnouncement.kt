/*
 * ssdp-kmp — internal announcement-type enum (ported from swift-ssdp's
 * SSDPMessageAnnouncement). Used in `MAN` (M-SEARCH) and `NTS` (NOTIFY)
 * headers. Internal: consumers see the higher-level [com.happycodelucky.ssdp.Notification]
 * sealed type, not this wire-level enum.
 */
package com.happycodelucky.ssdp.internal

/**
 * SSDP announcement type used in `MAN` (M-SEARCH) and `NTS` (NOTIFY) headers.
 */
internal enum class MessageAnnouncement(
    val rawValue: String,
) {
    /** `MAN: "ssdp:discover"` — used in M-SEARCH requests. */
    DISCOVER("ssdp:discover"),

    /** `NTS: ssdp:alive` — device or service is now reachable. */
    ALIVE("ssdp:alive"),

    /** `NTS: ssdp:byebye` — device or service is leaving the network. */
    BYEBYE("ssdp:byebye"),

    /** `NTS: ssdp:update` (UPnP 1.1) — device's BOOTID is changing. */
    UPDATE("ssdp:update"),
    ;

    companion object {
        fun parse(rawValue: String): MessageAnnouncement? = entries.firstOrNull { it.rawValue == rawValue }
    }
}
