/*
 * ssdp-kmp — M-SEARCH wire serializer (ported from swift-ssdp's
 * SSDPMSearchRequest). Internal: the public search API takes a Set<SearchTarget>
 * and fans out one of these per target (plan decision 3).
 */
package com.happycodelucky.ssdp.internal

/**
 * An SSDP M-SEARCH request for a single search target.
 *
 * @property searchTarget `ST` — what to search for.
 * @property maxWaitSeconds `MX` — maximum response wait time in seconds.
 *   Devices choose a random delay in `[0, MX]` before replying, to avoid
 *   response storms. Per UPnP recommendations this should be 1–5 seconds.
 */
internal data class MSearchRequest(
    val searchTarget: com.happycodelucky.ssdp.SearchTarget,
    val maxWaitSeconds: Int = DEFAULT_MX,
) {
    /**
     * The fully serialized M-SEARCH wire message. Headers are emitted in
     * deterministic (alphabetical) order so the output is testable. UPnP does
     * not require any particular header ordering. Terminates with CRLF and a
     * final blank line.
     */
    fun message(): String {
        // Alphabetical header order for deterministic, testable output. A sorted
        // List<Pair> rather than sortedMapOf — the latter isn't in the common
        // stdlib (it's JVM-only).
        val headers =
            listOf(
                SsdpHeaderKeys.HOST to "$SSDP_HOST:$SSDP_PORT",
                SsdpHeaderKeys.MAN to "\"${MessageAnnouncement.DISCOVER.rawValue}\"",
                SsdpHeaderKeys.MAX_WAIT to maxWaitSeconds.toString(),
                SsdpHeaderKeys.SEARCH_TARGET to searchTarget.rawValue,
            ).sortedBy { it.first }

        return buildString {
            append(MESSAGE_HEADER)
            for ((key, value) in headers) {
                append("\r\n")
                append(key)
                append(": ")
                append(value)
            }
            append("\r\n\r\n")
        }
    }

    /** UTF-8 bytes of [message], ready to hand to a multicast socket. */
    fun bytes(): ByteArray = message().encodeToByteArray()

    companion object {
        /** The leading request line. */
        const val MESSAGE_HEADER = "M-SEARCH * HTTP/1.1"

        /** SSDP multicast group address (IPv4). */
        const val SSDP_HOST = "239.255.255.250"

        /** SSDP multicast port. */
        const val SSDP_PORT = 1900

        /** Default `MX` value (seconds). */
        const val DEFAULT_MX = 1
    }
}
