/*
 * ssdp-kmp — the real Apple M-SEARCH socket over loopback UDP.
 *
 * Opens only the ephemeral-port M-SEARCH socket (no 1900 bind, no multicast
 * join, so it runs unchanged on macOS and the iOS simulator) and plays the
 * device with a raw POSIX socket on 127.0.0.1: a reply addressed to the search
 * socket's own port must surface on its incoming flow, and close() must end the
 * blocking recvfrom loop. Real I/O and real dispatchers, NOT virtual time.
 */
@file:OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)

package com.happycodelucky.ssdp.internal

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.AF_INET
import platform.posix.SOCK_DGRAM
import platform.posix.close
import platform.posix.sendto
import platform.posix.sockaddr_in
import platform.posix.socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val SSDP_PORT = 1900
private const val TIMEOUT_MILLIS = 10_000L
private const val CLOSE_TIMEOUT_MILLIS = 5_000L
private const val RESEND_INTERVAL_MILLIS = 50L
private const val SETTLE_MILLIS = 100L

class AppleSsdpTransportTest {
    /** 127.0.0.1 as `s_addr`: network order on a little-endian host puts octet 0 in the low byte (D-002). */
    private val loopbackNetworkOrder: UInt = 0x0100007Fu

    /** Send [text] from a throwaway socket to 127.0.0.1:[port], as a device's unicast reply. */
    private fun sendToLoopback(port: Int, text: String) {
        val fd = socket(AF_INET, SOCK_DGRAM, 0)
        check(fd >= 0) { "socket() failed" }
        try {
            memScoped {
                val dest =
                    alloc<sockaddr_in>().apply {
                        sin_family = AF_INET.convert()
                        sin_port = (((port and 0xFF) shl 8) or ((port shr 8) and 0xFF)).toUShort()
                        sin_addr.s_addr = loopbackNetworkOrder
                    }
                text.encodeToByteArray().usePinned { bytes ->
                    sendto(fd, bytes.addressOf(0), bytes.get().size.convert(), 0, dest.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
                }
            }
        } finally {
            close(fd)
        }
    }

    @Test
    fun searchSocketReceivesTheUnicastReplyToItsEphemeralPort() =
        runBlocking {
            val search = openAppleSearchSocket()
            try {
                val port = search.localPort
                assertTrue(port > 0, "search socket has no bound port")
                assertNotEquals(SSDP_PORT, port)

                val reply = "HTTP/1.1 200 OK\r\nST: roku:ecp\r\nUSN: uuid:roku::roku:ecp\r\n\r\n"
                val received = async { search.incoming.first() }
                withTimeout(TIMEOUT_MILLIS) {
                    // Resend until the collector above is subscribed: a datagram
                    // that arrives before it is dropped (replay = 0).
                    while (!received.isCompleted) {
                        sendToLoopback(port, reply)
                        delay(RESEND_INTERVAL_MILLIS)
                    }
                }
                assertEquals(reply, received.await().text)
            } finally {
                search.close()
            }
        }

    @Test
    fun closeEndsTheBlockingReceiveLoop() =
        runBlocking {
            val search = openAppleSearchSocket()
            delay(SETTLE_MILLIS) // let the loop park in recvfrom
            search.close()
            // recvfrom ignores coroutine cancellation; only closing the fd ends it.
            withTimeout(CLOSE_TIMEOUT_MILLIS) { search.receiveJob.join() }
            assertTrue(search.receiveJob.isCompleted)
        }
}
