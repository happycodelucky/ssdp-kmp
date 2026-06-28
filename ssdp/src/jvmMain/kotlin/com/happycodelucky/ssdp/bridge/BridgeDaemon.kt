/*
 * ssdp-kmp — the host-side bridge daemon (JVM only).
 *
 * Emulators can't receive inbound UDP multicast (CLAUDE.md §9). This daemon runs
 * on the developer's host machine (Mac/Linux), does the REAL SSDP multicast on
 * the host LAN, and relays it to one or more emulator clients over TCP. The
 * emulator app connects with `SsdpClient.bridged(host = "10.0.2.2", port = …)`.
 *
 * It is a DUMB PIPE: per connected client it opens a raw [JvmMulticastSocket]
 * (the same transport the JVM `SsdpClient` uses — no registry, no retransmit) and
 * relays bytes both ways:
 *   client → daemon: DATAGRAM_OUT frames → re-multicast verbatim on the LAN.
 *   daemon → client: every datagram the real socket saw → DATAGRAM_IN frames.
 * The app keeps owning retransmit and the registry, so its `SsdpClient` behaves
 * identically to a physical device — only the wire hop differs.
 *
 * The pipe logic ([runBridgePipe]) is split from the TCP server so it can be
 * unit-tested in `:ssdp` jvmTest with an in-memory duplex + a fake transport,
 * with no real TCP and no real multicast (the gated check covers it). The server
 * shell ([runSsdpBridgeDaemon]) is exercised only by the live end-to-end run.
 */
package com.happycodelucky.ssdp.bridge

import com.happycodelucky.ssdp.internal.MulticastSocket
import com.happycodelucky.ssdp.internal.bridge.BridgeFrameCodec
import com.happycodelucky.ssdp.internal.bridge.DuplexConnection
import com.happycodelucky.ssdp.internal.openMulticastSocket
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readByteArray
import io.ktor.utils.io.writeByteArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Default TCP port the bridge daemon listens on (1900 is SSDP itself). */
public const val DEFAULT_BRIDGE_PORT: Int = 1901

/**
 * Start the bridge daemon and block forever, accepting emulator clients on
 * [port]. One coroutine per client; each gets its own real multicast socket so a
 * disconnect tears down only that client's socket.
 *
 * @param port TCP port to listen on (default [DEFAULT_BRIDGE_PORT]).
 * @param bindInterface optional host interface hint for the real multicast
 *   socket (multi-homed hosts) — forwarded to [openMulticastSocket].
 */
public fun runSsdpBridgeDaemon(
    port: Int = DEFAULT_BRIDGE_PORT,
    bindInterface: String? = null,
): Unit =
    runBlocking {
        val selector = SelectorManager(Dispatchers.IO)
        val server = aSocket(selector).tcp().bind(hostname = "0.0.0.0", port = port)
        println("ssdp-bridge: listening on 0.0.0.0:$port (multicast iface: ${bindInterface ?: "default"})")
        try {
            while (true) {
                val client = server.accept()
                val remote = client.remoteAddress
                println("ssdp-bridge: client connected — $remote")
                launch {
                    val transport = openMulticastSocket(bindInterface)
                    val conn = client.asDuplexConnection()
                    try {
                        runBridgePipe(conn, transport)
                    } catch (_: Throwable) {
                        // Client dropped or transport error — clean up this client.
                    } finally {
                        transport.close()
                        client.close()
                        println("ssdp-bridge: client disconnected — $remote")
                    }
                }
            }
        } finally {
            server.close()
            selector.close()
        }
    }

/**
 * Relay one client connection both ways against [transport] until either side
 * ends. Runs the reader (client → multicast) and forwarder (multicast → client)
 * concurrently; when one ends, `coroutineScope` cancels the other.
 *
 * Pulled out of [runSsdpBridgeDaemon] so it is unit-testable with an in-memory
 * [DuplexConnection] and a fake [MulticastSocket].
 */
internal suspend fun runBridgePipe(
    conn: DuplexConnection,
    transport: MulticastSocket,
): Unit =
    coroutineScope {
        // multicast → client: frame every datagram the real socket received.
        val forwarder =
            launch {
                transport.incoming.collect { datagram ->
                    conn.write(BridgeFrameCodec.encodeIn(datagram.source, datagram.text))
                }
            }
        // client → multicast: re-multicast each DATAGRAM_OUT verbatim.
        launch {
            try {
                while (true) {
                    val frame = BridgeFrameCodec.decode(conn)
                    if (frame.type == BridgeFrameCodec.DATAGRAM_OUT) {
                        transport.send(frame.payload)
                    }
                }
            } finally {
                // Client read ended (EOF/close) → stop forwarding and let the
                // scope complete so the caller recycles the connection.
                forwarder.cancel()
            }
        }
    }

/**
 * Adapt an accepted ktor [io.ktor.network.sockets.Socket] to the shared
 * [DuplexConnection] seam, so the daemon pipe speaks the same interface the
 * emulator-side socket and the tests use.
 */
private fun io.ktor.network.sockets.Socket.asDuplexConnection(): DuplexConnection {
    val read = openReadChannel()
    val write = openWriteChannel(autoFlush = true)
    val socket = this
    return object : DuplexConnection {
        override suspend fun readFully(count: Int): ByteArray = read.readByteArray(count)

        override suspend fun write(bytes: ByteArray) {
            write.writeByteArray(bytes)
            write.flush()
        }

        override fun close() {
            socket.close()
        }
    }
}
