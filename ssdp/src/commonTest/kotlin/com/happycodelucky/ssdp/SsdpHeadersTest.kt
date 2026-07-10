/*
 * ssdp-kmp — SsdpHeaders discovery-surface tests.
 *
 * The parser already routes every non-typed header into otherHeaders (covered by
 * SsdpMessageParserTest). These tests lock down the *discovery* surface that lets
 * a consumer enumerate custom/vendor headers rather than only look them up by a
 * name they already know: case-insensitive get/contains, the canonical-uppercase
 * keys set, and for-loop iteration.
 */
package com.happycodelucky.ssdp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SsdpHeadersTest {
    private val headers =
        SsdpHeaders.of(
            mapOf(
                "LOCATION" to "http://host/desc.xml",
                "X-RINCON-Household" to "Sonos_household_1",
                "hue-bridgeid" to "001788FFFE112233",
            ),
        )

    @Test
    fun getIsCaseInsensitive() {
        assertEquals("Sonos_household_1", headers["x-rincon-household"])
        assertEquals("Sonos_household_1", headers["X-RINCON-HOUSEHOLD"])
        assertEquals("001788FFFE112233", headers["Hue-BridgeId"])
    }

    @Test
    fun containsIsCaseInsensitive() {
        assertTrue("x-rincon-household" in headers)
        assertTrue("HUE-BRIDGEID" in headers)
        assertFalse("nonexistent" in headers)
    }

    @Test
    fun keysAreCanonicalUppercase() {
        assertEquals(
            setOf("LOCATION", "X-RINCON-HOUSEHOLD", "HUE-BRIDGEID"),
            headers.keys,
        )
    }

    @Test
    fun iterationYieldsAllPairsWithUppercaseNames() {
        val collected = mutableMapOf<String, String>()
        for ((name, value) in headers) {
            collected[name] = value
        }
        assertEquals(
            mapOf(
                "LOCATION" to "http://host/desc.xml",
                "X-RINCON-HOUSEHOLD" to "Sonos_household_1",
                "HUE-BRIDGEID" to "001788FFFE112233",
            ),
            collected,
        )
    }

    @Test
    fun emptyHeadersHaveNoKeysAndContainNothing() {
        assertTrue(SsdpHeaders.EMPTY.keys.isEmpty())
        assertFalse("anything" in SsdpHeaders.EMPTY)
        assertTrue(SsdpHeaders.EMPTY.isEmpty)
        assertFalse(SsdpHeaders.EMPTY.iterator().hasNext())
    }

    @Test
    fun duplicateKeysLastWinsCaseInsensitively() {
        val h = SsdpHeaders.of(mapOf("Server" to "first", "SERVER" to "second"))
        assertEquals(1, h.size)
        assertEquals("second", h["server"])
        assertEquals(setOf("SERVER"), h.keys)
    }
}
