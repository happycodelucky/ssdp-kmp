/*
 * ssdp-kmp — M-SEARCH wire serialization tests.
 *
 * The serialized message is deterministic (alphabetically-ordered headers,
 * CRLF line endings, trailing blank line) so it can be asserted byte-for-byte.
 */
package com.happycodelucky.ssdp.internal

import com.happycodelucky.ssdp.SearchTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MSearchRequestTest {
    @Test
    fun serializesCanonicalMSearchForAllTargets() {
        val msg = MSearchRequest(SearchTarget.All, maxWaitSeconds = 2).message()
        val expected =
            "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 2\r\n" +
                "ST: ssdp:all\r\n" +
                "\r\n"
        assertEquals(expected, msg)
    }

    @Test
    fun usesProvidedSearchTargetRawValue() {
        val target = SearchTarget.DeviceType("schemas-upnp-org", "MediaRenderer", 1)
        val msg = MSearchRequest(target).message()
        assertTrue(msg.contains("ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n"))
        // Default MX is 1.
        assertTrue(msg.contains("MX: 1\r\n"))
    }

    @Test
    fun bytesAreUtf8OfMessage() {
        val req = MSearchRequest(SearchTarget.RootDevice)
        assertEquals(req.message().encodeToByteArray().toList(), req.bytes().toList())
    }

    @Test
    fun messageTerminatesWithBlankLine() {
        assertTrue(MSearchRequest(SearchTarget.All).message().endsWith("\r\n\r\n"))
    }
}
