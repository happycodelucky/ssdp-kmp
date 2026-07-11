/*
 * ssdp-kmp — SearchTarget round-trip tests (example-based + property-based).
 *
 * The wire string is the contract: anything we serialize via rawValue must
 * parse back to an equal value, and the canonical UPnP forms must map to the
 * right cases. Property tests (Kotest) fuzz the parametric cases.
 */
package com.happycodelucky.ssdp

import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.alphanumeric
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SearchTargetTest {
    @Test
    fun canonicalFormsRoundTrip() {
        val cases =
            listOf(
                SearchTarget.All to "ssdp:all",
                SearchTarget.RootDevice to "upnp:rootdevice",
                SearchTarget.Uuid("2f402f80-da50-11e1") to "uuid:2f402f80-da50-11e1",
                SearchTarget.DeviceType("schemas-upnp-org", "MediaServer", 1) to
                    "urn:schemas-upnp-org:device:MediaServer:1",
                SearchTarget.ServiceType("schemas-upnp-org", "AVTransport", 1) to
                    "urn:schemas-upnp-org:service:AVTransport:1",
            )
        for ((target, wire) in cases) {
            assertEquals(wire, target.rawValue, "rawValue mismatch for $target")
            assertEquals(target, SearchTarget.parse(wire), "parse mismatch for $wire")
        }
    }

    @Test
    fun unrecognizedFormsReturnNull() {
        listOf(
            "",
            "ssdp:something",
            "upnp:notroot",
            "urn:schemas-upnp-org:widget:Foo:1", // neither device nor service
            "urn:schemas-upnp-org:device:Foo:notanumber", // non-int version
            "urn:too:few", // wrong component count
            "garbage",
        ).forEach { assertNull(SearchTarget.parse(it), "expected null for '$it'") }
    }

    @Test
    fun parseOrCustomPrefersCanonicalFormsOverCustom() {
        // Every canonical form parseOrCustom sees must resolve to its specific
        // case, never Custom — parse is tried first.
        assertEquals(SearchTarget.All, SearchTarget.parseOrCustom("ssdp:all"))
        assertEquals(SearchTarget.RootDevice, SearchTarget.parseOrCustom("upnp:rootdevice"))
        assertEquals(SearchTarget.Uuid("abc-123"), SearchTarget.parseOrCustom("uuid:abc-123"))
        assertEquals(
            SearchTarget.DeviceType("schemas-upnp-org", "MediaServer", 1),
            SearchTarget.parseOrCustom("urn:schemas-upnp-org:device:MediaServer:1"),
        )
    }

    @Test
    fun parseOrCustomFallsBackToCustomForNonUpnpForms() {
        // The strings the strict parse rejects (except blank) become Custom,
        // carried verbatim — including a vendor target like roku:ecp.
        listOf(
            "roku:ecp",
            "ssdp:something",
            "upnp:notroot",
            "urn:schemas-upnp-org:widget:Foo:1",
            "urn:schemas-upnp-org:device:Foo:notanumber",
            "urn:too:few",
            "garbage",
        ).forEach { wire ->
            assertEquals(SearchTarget.Custom(wire), SearchTarget.parseOrCustom(wire), "expected Custom for '$wire'")
            // Custom round-trips: its rawValue is the wire string verbatim.
            assertEquals(wire, SearchTarget.parseOrCustom(wire)?.rawValue)
        }
    }

    @Test
    fun parseOrCustomReturnsNullForBlank() {
        // A blank ST/NT is junk, not a custom target — parseOrCustom drops it.
        listOf("", " ", "   ", "\t").forEach {
            assertNull(SearchTarget.parseOrCustom(it), "expected null for blank '$it'")
        }
    }

    @Test
    fun deviceAndServiceTypesRoundTripForArbitrarySchemaTypeVersion() =
        runTest {
            // Tokens are colon-free, whitespace-free, non-empty alphanumeric
            // identifiers — a colon is the wire separator, so a component
            // containing ':' is out of contract. Built from Codepoint.alphanumeric
            // (multiplatform; Arb.stringPattern is JVM-only — it depends on a
            // regex generator with no Kotlin/Native impl, so it breaks the iOS
            // test compile).
            val token: Arb<String> = Arb.string(1..12, Codepoint.alphanumeric())
            checkAll(token, token, Arb.int(0, 9999)) { schema, type, version ->
                val device = SearchTarget.DeviceType(schema, type, version)
                assertEquals(device, SearchTarget.parse(device.rawValue))
                val service = SearchTarget.ServiceType(schema, type, version)
                assertEquals(service, SearchTarget.parse(service.rawValue))
            }
        }
}
