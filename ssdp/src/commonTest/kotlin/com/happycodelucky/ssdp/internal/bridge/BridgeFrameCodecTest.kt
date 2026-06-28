/*
 * ssdp-kmp — bridge wire-codec round-trip + framing tests.
 *
 * The codec is the contract between the emulator socket and the host daemon, so
 * anything encoded must decode back equal, the source/text split must be exact,
 * oversized payloads must be rejected, and the streaming decoder must reassemble
 * a frame that arrives in arbitrary chunks (TCP segment boundaries).
 */
package com.happycodelucky.ssdp.internal.bridge

import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.alphanumeric
import io.kotest.property.arbitrary.byte
import io.kotest.property.arbitrary.byteArray
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BridgeFrameCodecTest {
    @Test
    fun encodeOutFramesTypeAndLength() {
        val payload = "M-SEARCH * HTTP/1.1\r\n\r\n".encodeToByteArray()
        val frame = BridgeFrameCodec.encodeOut(payload)
        assertEquals(BridgeFrameCodec.DATAGRAM_OUT, frame[0])
        // u32 BE length == payload size.
        val len =
            ((frame[1].toInt() and 0xFF) shl 24) or
                ((frame[2].toInt() and 0xFF) shl 16) or
                ((frame[3].toInt() and 0xFF) shl 8) or
                (frame[4].toInt() and 0xFF)
        assertEquals(payload.size, len)
        assertEquals(5 + payload.size, frame.size)
    }

    @Test
    fun decodeReadsBackEncodedOutFrame() =
        runTest {
            val payload = byteArrayOf(1, 2, 3, 4, 5)
            val conn = FakeDuplexConnection()
            conn.feed(BridgeFrameCodec.encodeOut(payload))
            val frame = BridgeFrameCodec.decode(conn)
            assertEquals(BridgeFrameCodec.DATAGRAM_OUT, frame.type)
            assertTrue(payload.contentEquals(frame.payload))
        }

    @Test
    fun inFrameRoundTripsSourceAndText() =
        runTest {
            val source = "192.168.1.42:1900"
            val text = "HTTP/1.1 200 OK\r\nLOCATION: http://x\r\n\r\n"
            val conn = FakeDuplexConnection()
            conn.feed(BridgeFrameCodec.encodeIn(source, text))
            val frame = BridgeFrameCodec.decode(conn)
            assertEquals(BridgeFrameCodec.DATAGRAM_IN, frame.type)
            val datagram = BridgeFrameCodec.decodeIn(frame.payload)
            assertEquals(source, datagram.source)
            assertEquals(text, datagram.text)
        }

    @Test
    fun inFrameHandlesEmptySource() =
        runTest {
            val conn = FakeDuplexConnection()
            conn.feed(BridgeFrameCodec.encodeIn(source = "", text = "NOTIFY * HTTP/1.1\r\n\r\n"))
            val datagram = BridgeFrameCodec.decodeIn(BridgeFrameCodec.decode(conn).payload)
            assertEquals("", datagram.source)
            assertEquals("NOTIFY * HTTP/1.1\r\n\r\n", datagram.text)
        }

    @Test
    fun decodeReassemblesFrameDeliveredInChunks() =
        runTest {
            // A frame split across many feeds must still decode — readFully
            // suspends until the whole frame has arrived.
            val payload = "datagram-body".encodeToByteArray()
            val whole = BridgeFrameCodec.encodeOut(payload)
            val conn = FakeDuplexConnection()
            // Feed byte-by-byte; decode must not see a partial frame.
            for (b in whole) conn.feed(byteArrayOf(b))
            val frame = BridgeFrameCodec.decode(conn)
            assertTrue(payload.contentEquals(frame.payload))
        }

    @Test
    fun decodeRejectsOversizedLengthPrefix() =
        runTest {
            // Header declaring a payload longer than MAX_PAYLOAD must be refused
            // before any payload allocation.
            val bogusHeader =
                byteArrayOf(
                    BridgeFrameCodec.DATAGRAM_OUT,
                    0x7F,
                    0xFF.toByte(),
                    0xFF.toByte(),
                    0xFF.toByte(), // ~2.1 billion
                )
            val conn = FakeDuplexConnection()
            conn.feed(bogusHeader)
            assertFailsWith<IllegalStateException> { BridgeFrameCodec.decode(conn) }
        }

    @Test
    fun encodeRejectsOversizedPayload() {
        val tooBig = ByteArray(BridgeFrameCodec.MAX_PAYLOAD + 1)
        assertFailsWith<IllegalArgumentException> { BridgeFrameCodec.encodeOut(tooBig) }
    }

    @Test
    fun decodeInRejectsTruncatedPayload() {
        // srcLen says 10 but only 3 bytes follow.
        val truncated = byteArrayOf(0x00, 0x0A, 1, 2, 3)
        assertFailsWith<IllegalArgumentException> { BridgeFrameCodec.decodeIn(truncated) }
    }

    @Test
    fun outFrameRoundTripsArbitraryPayloads() =
        runTest {
            // Multiplatform arbs only (Arb.byteArray / Arb.byte); Arb.stringPattern
            // is JVM-only and breaks the native test compile (LESSONS B-007).
            val payload = Arb.byteArray(Arb.int(0..512), Arb.byte())
            checkAll(20, payload) { bytes ->
                val conn = FakeDuplexConnection()
                conn.feed(BridgeFrameCodec.encodeOut(bytes))
                val frame = BridgeFrameCodec.decode(conn)
                assertEquals(BridgeFrameCodec.DATAGRAM_OUT, frame.type)
                assertTrue(bytes.contentEquals(frame.payload))
            }
        }

    @Test
    fun inFrameRoundTripsArbitrarySourceAndText() =
        runTest {
            val str = Arb.string(0..64, Codepoint.alphanumeric())
            checkAll(20, str, str) { source, text ->
                val conn = FakeDuplexConnection()
                conn.feed(BridgeFrameCodec.encodeIn(source, text))
                val datagram = BridgeFrameCodec.decodeIn(BridgeFrameCodec.decode(conn).payload)
                assertEquals(source, datagram.source)
                assertEquals(text, datagram.text)
            }
        }
}
