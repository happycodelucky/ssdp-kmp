/*
 * ssdp-kmp — the two-socket SSDP transport every platform actual returns.
 *
 * SSDP needs two UDP sockets, as UPnP control points do:
 *   - NOTIFY: bound to 1900 with address reuse and joined to 239.255.255.250,
 *     so it hears the ssdp:alive / byebye / update multicasts.
 *   - M-SEARCH: bound to an ephemeral port and not joined. A device answers an
 *     M-SEARCH with a unicast reply to the request's source address and port
 *     (UPnP Device Architecture §1.3.3), so this socket is the only one that
 *     ever sees our replies.
 *
 * Sending M-SEARCH from the 1900 socket instead looks equivalent but loses
 * replies: when several sockets on the host share 1900 through address reuse
 * (two clients in one process, or a browser, Spotify or a media server), the
 * kernel delivers a *unicast* datagram to only one of them, so a reply often
 * lands in somebody else's socket (LESSONS B-014).
 *
 * The pair keeps the [MulticastSocket] contract intact, so the client, the
 * bridge daemon and the tests see one transport: [incoming] merges both sockets'
 * datagrams into the one parser, and [send] goes out on the M-SEARCH socket.
 */
package com.happycodelucky.ssdp.internal

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge

/**
 * A [MulticastSocket] made of a NOTIFY listener ([notifySocket], bound to 1900
 * and joined to the group) and an M-SEARCH sender ([searchSocket], ephemeral
 * port, not joined). See the file header for why they must differ.
 */
internal class SsdpSocketPair(
    /** Bound to 1900 and joined to the group; its own [MulticastSocket.send] is never used. */
    internal val notifySocket: MulticastSocket,
    /** Bound to an ephemeral port; sends every M-SEARCH and receives the unicast replies. */
    internal val searchSocket: MulticastSocket,
) : MulticastSocket {
    override val incoming: Flow<Datagram> = merge(notifySocket.incoming, searchSocket.incoming)

    override suspend fun send(bytes: ByteArray) {
        searchSocket.send(bytes)
    }

    override fun close() {
        // Each half is closed independently so a failure in one can't leak the
        // other (and its receive thread / multicast lock).
        runCatching { searchSocket.close() }
        runCatching { notifySocket.close() }
    }
}

/**
 * Open an [SsdpSocketPair], NOTIFY socket first (its join failure is the one a
 * caller most needs to see). If the M-SEARCH socket then fails to open, the
 * already-open NOTIFY socket is closed before the failure propagates, so a
 * failed construction never leaks a bound 1900 socket.
 */
internal inline fun openSsdpSocketPair(openNotify: () -> MulticastSocket, openSearch: () -> MulticastSocket): SsdpSocketPair {
    val notify = openNotify()
    val search =
        runCatching { openSearch() }.getOrElse { failure ->
            runCatching { notify.close() }
            throw failure
        }
    return SsdpSocketPair(notifySocket = notify, searchSocket = search)
}
