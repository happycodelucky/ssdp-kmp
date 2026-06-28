/*
 * ssdp-kmp — the wire codec for the emulator host bridge.
 *
 * One TCP connection carries two independent streams (CLAUDE.md §9 host-bridge):
 *   app → daemon: outbound M-SEARCH bytes to re-multicast on the host LAN.
 *   daemon → app: every datagram the daemon's real socket saw (replies + NOTIFY).
 * This is NOT request/response — one M-SEARCH yields N inbound datagrams — so the
 * protocol is a length-prefixed frame stream, framed identically in both
 * directions. The daemon is a DUMB PIPE: the app keeps owning retransmit and the
 * registry, so the emulator path stays byte-identical to a physical device.
 *
 * Frame:  [ type:u8 ][ payloadLen:u32 BE ][ payload[payloadLen] ]
 *   0x01 DATAGRAM_OUT (app→daemon): payload = raw M-SEARCH datagram bytes.
 *   0x02 DATAGRAM_IN  (daemon→app): payload = [ srcLen:u16 BE ][ source ][ text ]
 *        — the source endpoint string (best-effort diagnostics) and the SSDP
 *          message text, both UTF-8. Length-prefixed (not newline-split) so an
 *          empty source or any byte in the text is unambiguous.
 *
 * Integers are big-endian (kotlinx-io's Buffer default — no manual byte math).
 * Encoding is pure (returns a ByteArray); decoding reads through a
 * [DuplexConnection] so the same code path runs over real TCP and the in-memory
 * test duplex.
 */
package com.happycodelucky.ssdp.internal.bridge

import com.happycodelucky.ssdp.internal.Datagram
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

/** A decoded bridge frame: a type tag and its raw payload. */
internal data class BridgeFrame(
    val type: Byte,
    val payload: ByteArray,
) {
    // ByteArray needs value-based equals/hashCode for test assertions.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BridgeFrame) return false
        return type == other.type && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = 31 * type.hashCode() + payload.contentHashCode()
}

internal object BridgeFrameCodec {
    /** app → daemon: a raw M-SEARCH datagram to re-multicast. */
    const val DATAGRAM_OUT: Byte = 0x01

    /** daemon → app: a datagram the daemon's real socket received. */
    const val DATAGRAM_IN: Byte = 0x02

    /**
     * Hard ceiling on a frame payload. SSDP datagrams are tiny (a few hundred
     * bytes); this guards against a corrupt/hostile length prefix triggering a
     * huge allocation. 65_535 also matches the UDP datagram ceiling intent of the
     * platform sockets (MAX_DATAGRAM there is 65_507).
     */
    const val MAX_PAYLOAD = 65_535

    /** type(1) + payloadLen(4). */
    private const val HEADER_SIZE = 5

    /** Frame an outbound M-SEARCH (`0x01`) for the app→daemon direction. */
    fun encodeOut(bytes: ByteArray): ByteArray = frame(DATAGRAM_OUT, bytes)

    /**
     * Frame an inbound datagram (`0x02`) for the daemon→app direction, packing
     * the source endpoint and SSDP text into one length-prefixed payload.
     */
    fun encodeIn(
        source: String,
        text: String,
    ): ByteArray {
        val sourceBytes = source.encodeToByteArray()
        require(sourceBytes.size <= UShort.MAX_VALUE.toInt()) { "source too long: ${sourceBytes.size}" }
        val payload =
            Buffer().apply {
                writeShort(sourceBytes.size.toShort())
                write(sourceBytes)
                write(text.encodeToByteArray())
            }
        return frame(DATAGRAM_IN, payload.readByteArray())
    }

    /**
     * Split a `0x02` payload back into a [Datagram]. Inverse of [encodeIn].
     * @throws IllegalArgumentException on a truncated payload (shorter than the
     *   declared source length) — surfaced to the connection loop as a protocol
     *   error that drops/reconnects.
     */
    fun decodeIn(payload: ByteArray): Datagram {
        val buffer = Buffer().apply { write(payload) }
        require(buffer.size >= Short.SIZE_BYTES) { "DATAGRAM_IN payload too short: ${payload.size}" }
        val srcLen = buffer.readShort().toInt() and UShort.MAX_VALUE.toInt()
        require(buffer.size >= srcLen) { "DATAGRAM_IN truncated: need $srcLen source bytes" }
        val source = buffer.readByteArray(srcLen).decodeToString()
        val text = buffer.readByteArray().decodeToString()
        return Datagram(text = text, source = source)
    }

    /**
     * Read one whole frame from [conn], suspending until it arrives. Reads the
     * fixed 5-byte header (type + u32 length), validates the length against
     * [MAX_PAYLOAD], then reads the payload. Propagates whatever [conn.readFully]
     * throws at EOF/close so the connection loop can reconnect.
     *
     * @throws IllegalStateException if the declared length exceeds [MAX_PAYLOAD]
     *   (treated as a corrupt stream — drop the connection rather than allocate).
     */
    suspend fun decode(conn: DuplexConnection): BridgeFrame {
        val header = Buffer().apply { write(conn.readFully(HEADER_SIZE)) }
        val type = header.readByte()
        val len = header.readInt()
        check(len in 0..MAX_PAYLOAD) { "bridge frame length out of range: $len" }
        val payload = if (len == 0) EMPTY else conn.readFully(len)
        return BridgeFrame(type, payload)
    }

    private val EMPTY = ByteArray(0)

    private fun frame(
        type: Byte,
        payload: ByteArray,
    ): ByteArray {
        require(payload.size <= MAX_PAYLOAD) { "payload too large: ${payload.size}" }
        val buffer =
            Buffer().apply {
                writeByte(type)
                writeInt(payload.size)
                write(payload)
            }
        return buffer.readByteArray()
    }
}
