/*
 * ssdp-kmp — the platform multicast-socket seam (CLAUDE.md §4: keep expect/actual
 * minimal; refactor to an interface + factory rather than spreading platform
 * code).
 *
 * All orchestration (retransmit, parsing, the registry) lives in commonMain and
 * talks only to this interface. Each platform supplies one `actual`
 * implementation via [openMulticastSocket]:
 *   - appleMain : POSIX BSD multicast socket (platform.posix)
 *   - androidMain: java.net.MulticastSocket + WifiManager.MulticastLock
 *   - jvmMain   : java.net.MulticastSocket
 */
package com.happycodelucky.ssdp.internal

import kotlinx.coroutines.flow.Flow

/** A raw UDP datagram received on the SSDP multicast group. */
internal data class Datagram(
    /** The decoded UTF-8 payload (SSDP messages are ASCII/UTF-8 HTTP-like text). */
    val text: String,
    /** Source endpoint as `host:port`, best-effort — for diagnostics/logging only. */
    val source: String,
)

/**
 * A joined SSDP multicast socket (`239.255.255.250:1900`).
 *
 * Lifecycle: construct (which joins the group and starts receiving), [send]
 * M-SEARCH datagrams to the group as many times as the retransmit scheduler
 * asks, observe [incoming] for every datagram the socket sees (NOTIFY
 * broadcasts *and* unicast M-SEARCH replies — the kernel delivers both to the
 * bound port), and [close] to leave the group and stop.
 *
 * Implementations must be safe to [close] exactly once; [send] after [close] is
 * a no-op or throws [com.happycodelucky.ssdp.SsdpError.TransportFailed].
 */
internal interface MulticastSocket {
    /**
     * A cold-ish [Flow] of received datagrams. Backed by the platform receive
     * loop; collection starts delivery. Implementations should fan out so the
     * single socket serves all collectors (the client collects this once).
     */
    val incoming: Flow<Datagram>

    /**
     * Send one M-SEARCH datagram to the multicast group. Called once per
     * retransmit round. Suspends only as long as the platform send takes.
     *
     * @throws com.happycodelucky.ssdp.SsdpError.TransportFailed on send failure.
     */
    suspend fun send(bytes: ByteArray)

    /** Leave the multicast group and stop the receive loop. Idempotent. */
    fun close()
}

/**
 * Open and join an SSDP multicast socket on the current platform.
 *
 * @param bindInterface optional hint for which local interface/address to bind
 *   (platform-interpreted; `null` lets the OS choose the default route). Apple
 *   uses it as an IPv4 address for `IP_MULTICAST_IF`; the JVM/Android side uses
 *   it to pick a `NetworkInterface`.
 * @throws com.happycodelucky.ssdp.SsdpError.MulticastJoinFailed if the group
 *   can't be joined (e.g. missing entitlement on iOS, no multicast lock on
 *   Android).
 */
internal expect fun openMulticastSocket(bindInterface: String?): MulticastSocket
