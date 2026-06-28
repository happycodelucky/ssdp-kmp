/*
 * ssdp-kmp — bridge end-to-end proof over REAL loopback TCP.
 *
 * The codec/socket/pipe unit tests use an in-memory DuplexConnection, which
 * proves the logic but not that ktor-network's real ByteReadChannel /
 * ByteWriteChannel honor the readFully(n)-suspends-until-exactly-n contract the
 * codec depends on. This wires a real BridgeMulticastSocket (ktor TCP client) to
 * the real runBridgePipe daemon (ktor TCP server) over 127.0.0.1, with a fake
 * multicast transport so it needs no LAN. Uses runBlocking — real I/O and real
 * dispatchers, NOT virtual time. Loopback-only, so it is safe in CI.
 */
package com.happycodelucky.ssdp.bridge

import com.happycodelucky.ssdp.internal.FakeMulticastSocket
import com.happycodelucky.ssdp.internal.bridge.BridgeMulticastSocket
import com.happycodelucky.ssdp.internal.bridge.DuplexConnection
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readByteArray
import io.ktor.utils.io.writeByteArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BridgeEndToEndTcpTest {
    @Test
    fun realTcpRoundTripThroughDaemonPipe() =
        runBlocking {
            val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val transport = FakeMulticastSocket()
            val selector = SelectorManager(Dispatchers.IO)
            val server = aSocket(selector).tcp().bind("127.0.0.1", 0)
            val port = (server.localAddress as InetSocketAddress).port

            // Daemon: accept one client, run the real pipe against the fake transport.
            serverScope.launch {
                val accepted = server.accept()
                val read = accepted.openReadChannel()
                val write = accepted.openWriteChannel(autoFlush = true)
                val conn =
                    object : DuplexConnection {
                        override suspend fun readFully(count: Int) = read.readByteArray(count)

                        override suspend fun write(bytes: ByteArray) {
                            write.writeByteArray(bytes)
                            write.flush()
                        }

                        override fun close() = accepted.close()
                    }
                runBridgePipe(conn, transport)
            }

            // Real client socket over loopback TCP.
            val socket = BridgeMulticastSocket(host = "127.0.0.1", port = port, parentScope = clientScope)

            // 1) Client → daemon: an M-SEARCH must arrive at the transport.
            val mSearch = "M-SEARCH * HTTP/1.1\r\nST: ssdp:all\r\n\r\n".encodeToByteArray()
            withTimeout(10_000) {
                while (transport.sent.isEmpty()) {
                    socket.send(mSearch)
                    delay(50)
                }
            }
            assertTrue(mSearch.contentEquals(transport.sent.first()))

            // 2) Daemon → client: a datagram on the transport must surface on incoming.
            val received =
                withTimeout(10_000) {
                    val incoming = clientScope.async { socket.incoming.first() }
                    delay(200)
                    transport.deliver("HTTP/1.1 200 OK\r\nUSN: uuid:real\r\n\r\n", source = "10.0.0.5:1900")
                    incoming.await()
                }
            assertEquals("10.0.0.5:1900", received.source)
            assertTrue(received.text.contains("USN: uuid:real"))

            socket.close()
            server.close()
            selector.close()
            serverScope.cancel()
            clientScope.cancel()
        }
}
