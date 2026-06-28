/*
 * ssdp-kmp — the TCP seam for the Android-emulator host bridge.
 *
 * Emulators can't receive inbound UDP multicast (CLAUDE.md §9), so on an
 * emulator the app tunnels SSDP over a reliable TCP connection to a host-side
 * bridge daemon that does the real multicast on the host LAN. This file is the
 * thin seam over that connection:
 *
 *   - [DuplexConnection] — a byte-stream pair (read N bytes / write bytes /
 *     close). Both directions multiplexed on ONE socket; the bridge protocol is
 *     full-duplex (one M-SEARCH out → many datagrams in), not request/response.
 *   - [ktorTcpConnect] — the production [Connect] backed by ktor-network's
 *     multiplatform TCP client. Injected into [BridgeMulticastSocket] so tests
 *     pass an in-memory duplex and never open a real socket (LESSONS N-011-style
 *     injection, mirroring the socketFactory / MockEngine seams already in use).
 *
 * Kept deliberately tiny and `java.*`-free so the whole bridge transport lives
 * in commonMain (CLAUDE.md §4) — one implementation serves the Android emulator
 * and, later, the iOS simulator.
 */
package com.happycodelucky.ssdp.internal.bridge

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readByteArray
import io.ktor.utils.io.writeByteArray
import kotlinx.coroutines.Dispatchers

/**
 * A full-duplex byte stream to the bridge daemon. Reads and writes are
 * independent (no request/response coupling). All methods suspend on I/O.
 *
 * Implementations must be safe to [close] once; a read or write after close
 * throws (the caller's connection loop treats that as a dropped connection and
 * reconnects).
 */
internal interface DuplexConnection {
    /**
     * Suspend until exactly [count] bytes have been read, returning them. Throws
     * if the stream ends or is closed before [count] bytes arrive — the codec
     * relies on this to frame fixed-width header fields without hand-rolling
     * partial-read reassembly.
     */
    suspend fun readFully(count: Int): ByteArray

    /** Write [bytes] and flush. Throws on a broken connection. */
    suspend fun write(bytes: ByteArray)

    /** Close both directions. Idempotent. */
    fun close()
}

/**
 * Opens a [DuplexConnection] to `host:port`. Injected so tests substitute an
 * in-memory duplex; production passes [ktorTcpConnect].
 */
internal fun interface Connect {
    suspend fun connect(
        host: String,
        port: Int,
    ): DuplexConnection
}

/**
 * Production [Connect]: a ktor-network TCP client. ktor 3.x byte channels are
 * backed by kotlinx-io and `readByteArray(n)` suspends until exactly `n` bytes
 * arrive (or throws at EOF), which is exactly the [DuplexConnection.readFully]
 * contract. One [SelectorManager] per connection on [Dispatchers.Default]
 * (Dispatchers.IO is unavailable on Kotlin/Native — LESSONS B-004).
 */
internal val ktorTcpConnect: Connect =
    Connect { host, port ->
        val selector = SelectorManager(Dispatchers.Default)
        val socket =
            try {
                aSocket(selector).tcp().connect(host, port)
            } catch (e: Throwable) {
                selector.close()
                throw e
            }
        val read: ByteReadChannel = socket.openReadChannel()
        val write: ByteWriteChannel = socket.openWriteChannel(autoFlush = true)
        object : DuplexConnection {
            override suspend fun readFully(count: Int): ByteArray = read.readByteArray(count)

            override suspend fun write(bytes: ByteArray) {
                write.writeByteArray(bytes)
                write.flush()
            }

            override fun close() {
                socket.close()
                selector.close()
            }
        }
    }
