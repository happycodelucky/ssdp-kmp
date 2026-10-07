/*
 * ssdp-kmp — the platform multicast-socket seam (CLAUDE.md §4: keep expect/actual
 * minimal; refactor to an interface + factory rather than spreading platform
 * code).
 *
 * All orchestration (retransmit, parsing, the registry) lives in commonMain and
 * talks only to this interface. Each platform supplies one `actual`
 * implementation via [openMulticastSocket], an [SsdpSocketPair] of two UDP
 * sockets (a NOTIFY listener on 1900 and an ephemeral-port M-SEARCH sender):
 *   - appleMain : POSIX BSD sockets (platform.posix)
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
 * The SSDP transport the client talks to: a joined multicast group
 * (`239.255.255.250:1900`) plus the socket M-SEARCH goes out on.
 *
 * Lifecycle: construct (which joins the group and starts receiving), [send]
 * M-SEARCH datagrams to the group as many times as the retransmit scheduler
 * asks, observe [incoming] for every datagram the transport sees (NOTIFY
 * broadcasts *and* unicast M-SEARCH replies), and [close] to leave the group
 * and stop.
 *
 * The platform transports are an [SsdpSocketPair]: M-SEARCH is sent from an
 * ephemeral port, so the unicast replies addressed to that port reach only
 * this transport, never a different socket sharing 1900 (see [SsdpSocketPair]).
 * The same interface also describes each half of the pair, and the emulator
 * bridge (`BridgeMulticastSocket`), which tunnels both directions over TCP.
 *
 * Implementations must be safe to [close] exactly once; [send] after [close] is
 * a no-op or throws [com.happycodelucky.ssdp.SsdpError.TransportFailed].
 */
internal interface MulticastSocket {
    /**
     * A cold-ish [Flow] of received datagrams. Backed by the platform receive
     * loop(s); collection starts delivery. Implementations should fan out so the
     * transport serves all collectors (the client collects this once).
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
 * Open and join the SSDP transport on the current platform: an [SsdpSocketPair]
 * of a NOTIFY listener bound to 1900 and an M-SEARCH socket on an ephemeral port.
 *
 * @param bindInterface the one interface (name or IPv4 address) to join and
 *   search on; `null` uses every interface [selectMulticastInterfaces] picks.
 * @throws com.happycodelucky.ssdp.SsdpError.MulticastJoinFailed if the group
 *   can't be joined (e.g. missing entitlement on iOS, no multicast lock on
 *   Android).
 * @throws com.happycodelucky.ssdp.SsdpError.TransportFailed if the M-SEARCH
 *   socket can't be opened.
 */
internal expect fun openMulticastSocket(bindInterface: String?): MulticastSocket
