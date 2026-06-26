/*
 * ssdp-kmp — network identity for cache-reset decisions (plan decision 4).
 *
 * SSDP devices are LAN-scoped: a device found on home Wi-Fi is meaningless once
 * you're on a café network, so the registry must reset when the LAN changes.
 * Reachable tells us *that* the path changed (Wi-Fi ↔ cellular, reconnects);
 * the local IPv4 subnet tells us *which* LAN. A [NetworkKey] combines both into
 * one comparable token — when it changes, the registry resets.
 */
package com.happycodelucky.ssdp.internal

/**
 * An opaque, comparable identity for "the network we're discovering on". Two
 * keys that compare equal are treated as the same LAN; any change triggers a
 * registry reset.
 *
 * @property transportTag a coarse transport label sourced from reachable
 *   (e.g. "Wifi", "Ethernet", "None"). A transport flip alone is enough to
 *   reset even when the subnet probe is unavailable.
 * @property subnet the active interface's IPv4 subnet in CIDR-ish form
 *   (e.g. "192.168.1.0/24"), or `null` when it can't be determined. Distinguishes
 *   two different Wi-Fi networks that share the same transport tag.
 */
internal data class NetworkKey(
    val transportTag: String,
    val subnet: String?,
) {
    companion object {
        /** A sentinel for "no usable network". */
        val NONE = NetworkKey(transportTag = "None", subnet = null)
    }
}

/**
 * The active interface's IPv4 subnet identity (e.g. "192.168.1.0/24"), or `null`
 * if it can't be determined. Implemented per platform:
 *   - jvm / android: NetworkInterface enumeration
 *   - apple: getifaddrs
 *
 * Cheap, synchronous, best-effort — called only when reachable reports a path
 * change, never in a hot loop.
 */
internal expect fun localSubnetKey(): String?
