/*
 * ssdp-kmp — which local interfaces the transport joins and searches on.
 *
 * SSDP is link-local: a device hears an M-SEARCH, and we hear its NOTIFY, only on
 * the network segment the datagram went out on. A host on several networks (Wi-Fi
 * plus Ethernet, a VM bridge, a docked laptop) has devices on each, so the
 * transport joins the group and sends every M-SEARCH on every interface that can
 * carry IPv4 multicast, as mDNS responders and browsers do. `bindInterface`
 * narrows that to one.
 *
 * Picking a single "first" interface instead is what broke discovery on a Mac
 * with a VPN up: the JVM lists `utun` tunnels (IPv6-only, point-to-point) first,
 * the IPv4 join on one fails with "Can't assign requested address", and the
 * client hears nothing (LESSONS B-015).
 *
 * The policy is common; each platform only enumerates its interfaces into
 * [LocalInterface]s (java.net.NetworkInterface on JVM/Android, getifaddrs on Apple).
 */
package com.happycodelucky.ssdp.internal

import com.happycodelucky.ssdp.SsdpError

/**
 * One local interface with an IPv4 address, as a platform enumerates it. An
 * interface with several IPv4 addresses appears once, with its first; one with
 * none (an IPv6-only tunnel, AWDL) isn't listed at all, since SSDP's group
 * `239.255.255.250` is IPv4.
 */
internal data class LocalInterface(
    /** The OS interface name (`en0`, `wlan0`, `eth0`). */
    val name: String,
    /** The interface's IPv4 address, dotted-quad. */
    val ipv4: String,
    val isUp: Boolean,
    val isLoopback: Boolean,
    /** A tunnel (VPN `utun`, PPP): it reaches a remote network, not the local segment. */
    val isPointToPoint: Boolean,
    val supportsMulticast: Boolean,
) {
    /** `en0 (192.168.0.229)`: how the interface appears in failure details. */
    override fun toString(): String = "$name ($ipv4)"
}

/**
 * The interfaces to join the SSDP group and send M-SEARCH on.
 *
 * With [bindInterface] (a name like `en0`, or one of the host's IPv4 addresses)
 * it's that interface alone. Otherwise it's every interface that is up, not
 * loopback, not point-to-point, and multicast-capable. An empty result means no
 * interface qualifies: the transport then falls back to the OS default route,
 * as it did before it enumerated interfaces.
 *
 * @throws SsdpError.MulticastJoinFailed if [bindInterface] matches no interface
 *   with an IPv4 address. Silently searching somewhere else would hide the typo.
 */
internal fun selectMulticastInterfaces(candidates: List<LocalInterface>, bindInterface: String?): List<LocalInterface> {
    if (bindInterface != null) {
        val match =
            candidates.firstOrNull { it.name == bindInterface || it.ipv4 == bindInterface }
                ?: throw SsdpError.MulticastJoinFailed(
                    details =
                        "no local IPv4 interface matches bindInterface=$bindInterface " +
                            "(have: ${candidates.joinToString().ifEmpty { "none" }})",
                )
        return listOf(match)
    }
    return candidates.filter { it.isUp && !it.isLoopback && !it.isPointToPoint && it.supportsMulticast }
}

/**
 * Run [action] once per interface and succeed if any run succeeds: one dead
 * interface (a VM bridge with nothing behind it, a NIC mid-teardown) mustn't stop
 * discovery on the others. With no [interfaces], runs [action] once with `null`,
 * the OS default. If every run fails, throws the failure [wrap] builds from a
 * summary of them, with the first as its cause.
 */
internal inline fun <T> onEachInterface(
    interfaces: List<T>,
    action: (T?) -> Unit,
    wrap: (details: String, cause: Throwable) -> Throwable,
) {
    if (interfaces.isEmpty()) {
        runCatching { action(null) }.onFailure { throw wrap(it.message ?: it.toString(), it) }
        return
    }
    val failures = mutableListOf<Pair<T, Throwable>>()
    for (each in interfaces) {
        runCatching { action(each) }.onFailure { failures += each to it }
    }
    if (failures.size == interfaces.size) {
        val summary = failures.joinToString("; ") { (each, error) -> "$each: ${error.message ?: error}" }
        throw wrap(summary, failures.first().second)
    }
}
