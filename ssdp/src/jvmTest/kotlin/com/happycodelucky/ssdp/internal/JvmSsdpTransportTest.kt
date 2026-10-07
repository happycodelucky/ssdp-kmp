/*
 * ssdp-kmp — the real JVM transport over loopback UDP.
 *
 * The regression: M-SEARCH used to leave from the socket bound to 1900, so a
 * device's unicast reply went to port 1900, and with several sockets sharing
 * 1900 (two clients in one process, a browser, a media server) the kernel hands
 * a unicast datagram to only one of them — the other client never saw its
 * reply. Each client now searches from its own ephemeral port. These tests open
 * the real M-SEARCH socket and play the device with a plain DatagramSocket on
 * 127.0.0.1, so they need no LAN or multicast route. The NOTIFY half is a fake:
 * joining the group depends on the host's interfaces (one with no IPv4 address
 * fails the join), which a CI runner doesn't promise. Real I/O and real
 * dispatchers, NOT virtual time.
 */
package com.happycodelucky.ssdp.internal

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondBadRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.TimeSource

class JvmSsdpTransportTest {
    /** The real M-SEARCH socket behind a fake NOTIFY socket. */
    private fun openPair(): SsdpSocketPair = openSsdpSocketPair(openNotify = { FakeMulticastSocket() }, openSearch = ::openJvmSearchSocket)

    private val SsdpSocketPair.searchPort: Int get() = (searchSocket as JvmUdpSocket).localPort

    /** An M-SEARCH reply for `roku:ecp`, as a Roku sends it, with a per-test USN. */
    private fun reply(usn: String): ByteArray =
        listOf(
            "HTTP/1.1 200 OK",
            "CACHE-CONTROL: max-age=3600",
            "EXT:",
            "LOCATION: http://127.0.0.1:8060/",
            "SERVER: Roku/15.2.4 UPnP/1.0 Roku/15.2.4",
            "ST: roku:ecp",
            "USN: $usn",
        ).joinToString("\r\n", postfix = "\r\n\r\n").encodeToByteArray()

    @Test
    fun searchSocketIsOnItsOwnEphemeralPort() {
        val first = openPair()
        val second = openPair()
        try {
            assertNotEquals(1900, first.searchPort)
            assertNotEquals(0, first.searchPort)
            assertNotEquals(first.searchPort, second.searchPort)
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun twoClientsInOneProcessEachReceiveTheReplyToTheirOwnSearchPort() =
        runBlocking {
            val pairs = List(2) { openPair() }
            val scopes = List(2) { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
            val clients =
                pairs.mapIndexed { index, pair ->
                    SsdpClientImpl(
                        socketFactory = { pair },
                        parentScope = scopes[index],
                        clock = Clock.System,
                        timeSource = TimeSource.Monotonic,
                        httpClient = HttpClient(MockEngine { respondBadRequest() }),
                    )
                }
            val usns = List(2) { "uuid:roku-$it::roku:ecp" }
            try {
                DatagramSocket().use { device ->
                    withTimeout(10_000) {
                        clients.forEachIndexed { index, client ->
                            // Resend until the client's collector is subscribed: a
                            // datagram that arrives before it is dropped (replay = 0).
                            while (usns[index] !in client.devices.value) {
                                val bytes = reply(usns[index])
                                device.send(DatagramPacket(bytes, bytes.size, InetAddress.getLoopbackAddress(), pairs[index].searchPort))
                                delay(50)
                            }
                        }
                    }
                }
                // Each reply reached exactly the client whose port it was sent to.
                assertEquals(setOf(usns[0]), clients[0].devices.value.keys)
                assertEquals(setOf(usns[1]), clients[1].devices.value.keys)
            } finally {
                // Cancel before the sockets close (LESSONS B-013), then close.
                scopes.forEach { it.coroutineContext.job.cancelAndJoin() }
                clients.forEach { it.close() }
            }
        }

    @Test
    fun closeUnblocksTheReceiveThread() {
        val pair = openPair()
        val thread = (pair.searchSocket as JvmUdpSocket).receiveThread
        assertTrue(thread.isAlive)

        pair.close()

        // The thread is parked in socket.receive(), which ignores interrupts;
        // only closing the socket can end it.
        thread.join(5_000)
        assertFalse(thread.isAlive, "${thread.name} still blocked in receive() after close()")
    }

    @Test
    fun enumeratesLoopbackAsAnIpv4LoopbackInterface() {
        val loopback =
            java.net.NetworkInterface
                .getNetworkInterfaces()
                .toList()
                .mapNotNull { it.toLocalInterface() }
                .single { it.ipv4 == "127.0.0.1" }

        assertTrue(loopback.isLoopback && loopback.isUp)
        // The default policy never searches on it; naming it explicitly does.
        assertEquals(listOf(loopback.name), selectJvmInterfaces(loopback.name).map { it.name })
        assertFalse(loopback.name in selectJvmInterfaces(null).map { it.name })
    }
}
