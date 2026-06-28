/*
 * ssdp-kmp — host bridge daemon pipe tests (JVM).
 *
 * runBridgePipe is the daemon's relay loop: client DATAGRAM_OUT frames →
 * transport.send, and transport.incoming datagrams → client DATAGRAM_IN frames.
 * Tested here (in the gated :ssdp jvmTest) with an in-memory DuplexConnection +
 * the shared FakeMulticastSocket, so no real TCP and no real multicast. The
 * :jvm-bridge module stays a thin untested shell.
 */
package com.happycodelucky.ssdp.bridge

import com.happycodelucky.ssdp.internal.FakeMulticastSocket
import com.happycodelucky.ssdp.internal.bridge.BridgeFrameCodec
import com.happycodelucky.ssdp.internal.bridge.FakeDuplexConnection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BridgePipeTest {
    @Test
    fun clientOutFrameIsReMulticast() =
        runTest {
            val conn = FakeDuplexConnection()
            val transport = FakeMulticastSocket()
            // Run the pipe in the background; it loops until the connection closes.
            val pipe = backgroundScope.launch { runBridgePipe(conn, transport) }
            runCurrent()

            val mSearch = "M-SEARCH * HTTP/1.1\r\nST: ssdp:all\r\n\r\n".encodeToByteArray()
            conn.feed(BridgeFrameCodec.encodeOut(mSearch))
            runCurrent()

            // The daemon re-multicast the exact bytes onto the real transport.
            assertEquals(1, transport.sent.size)
            assertTrue(mSearch.contentEquals(transport.sent.single()))

            pipe.cancel()
        }

    @Test
    fun transportDatagramIsFramedBackToClient() =
        runTest {
            val conn = FakeDuplexConnection()
            val transport = FakeMulticastSocket()
            val pipe = backgroundScope.launch { runBridgePipe(conn, transport) }
            runCurrent()

            // A reply arrives on the real socket; the daemon frames it to the client.
            transport.deliver(
                raw = "HTTP/1.1 200 OK\r\nUSN: uuid:xyz\r\n\r\n",
                source = "192.168.1.7:1900",
            )
            runCurrent()

            // Decode what was written to the client connection.
            val echo = FakeDuplexConnection()
            echo.feed(conn.writtenBytes)
            val frame = BridgeFrameCodec.decode(echo)
            assertEquals(BridgeFrameCodec.DATAGRAM_IN, frame.type)
            val datagram = BridgeFrameCodec.decodeIn(frame.payload)
            assertEquals("192.168.1.7:1900", datagram.source)
            assertTrue(datagram.text.contains("USN: uuid:xyz"))

            pipe.cancel()
        }
}
