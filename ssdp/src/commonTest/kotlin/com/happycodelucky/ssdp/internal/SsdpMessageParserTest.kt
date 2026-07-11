/*
 * ssdp-kmp — parser tests against real-world device fixtures.
 *
 * These lock down the lenient-parsing behaviors ported from swift-ssdp: spaced
 * `max-age`, missing/empty EXT, byebye-without-LOCATION, vendor headers landing
 * in otherHeaders, and required-header enforcement.
 */
package com.happycodelucky.ssdp.internal

import com.happycodelucky.ssdp.Notification
import com.happycodelucky.ssdp.SearchTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

class SsdpMessageParserTest {
    private fun parseResponse(raw: String): com.happycodelucky.ssdp.MSearchResponse {
        val msg = SsdpMessageParser.parse(raw) ?: fail("expected a parseable message")
        return (msg as? SsdpMessage.SearchResponse)?.response ?: fail("expected a SearchResponse, got $msg")
    }

    private fun parseNotification(raw: String): Notification {
        val msg = SsdpMessageParser.parse(raw) ?: fail("expected a parseable message")
        return (msg as? SsdpMessage.Notify)?.notification ?: fail("expected a Notify, got $msg")
    }

    @Test
    fun sonosResponseParsesSpacedMaxAgeAndEmptyExtAndVendorHeaders() {
        val r = parseResponse(Fixtures.MSEARCH_RESPONSE_SONOS)
        // `max-age = 1800` with spaces around `=` must still parse.
        assertEquals(1800.seconds, r.cacheControl)
        // Empty `EXT:` is present (presence-only) → ext == true.
        assertTrue(r.ext)
        assertEquals("http://192.168.1.42:1400/xml/device_description.xml", r.location)
        assertEquals(
            SearchTarget.DeviceType(schema = "schemas-upnp-org", deviceType = "ZonePlayer", version = 1),
            r.searchTarget,
        )
        assertEquals("uuid:RINCON_000E58A1B2C300400::urn:schemas-upnp-org:device:ZonePlayer:1", r.usn)
        // Vendor headers land in otherHeaders, case-insensitively.
        assertEquals("Sonos_household_1", r.otherHeaders["x-rincon-household"])
        assertEquals("23", r.otherHeaders["X-RINCON-BOOTSEQ"])
        // DATE is preserved raw (unparsed by design).
        assertEquals("Sun, 06 Nov 1994 08:49:37 GMT", r.date)
        // Consumed headers must NOT appear in otherHeaders.
        assertNull(r.otherHeaders["LOCATION"])
        assertNull(r.otherHeaders["ST"])
    }

    @Test
    fun hueResponseToleratesMissingExt() {
        val r = parseResponse(Fixtures.MSEARCH_RESPONSE_HUE)
        assertTrue(!r.ext) // EXT absent → false, but still parses.
        assertEquals(100.seconds, r.cacheControl)
        assertEquals(SearchTarget.RootDevice, r.searchTarget)
        assertEquals("001788FFFE112233", r.otherHeaders["hue-bridgeid"])
    }

    @Test
    fun malformedMissingExtStillParses() {
        val r = parseResponse(Fixtures.MALFORMED_MISSING_EXT)
        assertEquals(300.seconds, r.cacheControl)
        assertEquals(SearchTarget.RootDevice, r.searchTarget)
        assertEquals("uuid:malformed-but-real::upnp:rootdevice", r.usn)
    }

    @Test
    fun rokuAliveNotificationParsesBootAndConfigIds() {
        val n = parseNotification(Fixtures.NOTIFY_ALIVE_ROKU)
        assertTrue(n is Notification.Alive)
        val ad = n.advertisement
        assertEquals(1800.seconds, ad.cacheControl)
        assertEquals("http://192.168.1.77:8060/dial/dd.xml", ad.location)
        assertEquals(7, ad.bootId)
        assertEquals(1, ad.configId)
        assertNull(ad.nextBootId)
        assertEquals(
            SearchTarget.DeviceType(schema = "dial-multiscreen-org", deviceType = "dial", version = 1),
            ad.notificationTarget,
        )
    }

    @Test
    fun byebyeNotificationParsesWithoutLocation() {
        val n = parseNotification(Fixtures.NOTIFY_BYEBYE)
        assertTrue(n is Notification.Byebye)
        assertNull(n.advertisement.location)
        assertEquals(
            SearchTarget.DeviceType(schema = "schemas-upnp-org", deviceType = "MediaServer", version = 1),
            n.advertisement.notificationTarget,
        )
    }

    @Test
    fun updateNotificationParsesNextBootId() {
        val n = parseNotification(Fixtures.NOTIFY_UPDATE)
        assertTrue(n is Notification.Update)
        val ad = n.advertisement
        assertEquals(23, ad.bootId)
        assertEquals(24, ad.nextBootId)
        assertEquals(SearchTarget.RootDevice, ad.notificationTarget)
    }

    @Test
    fun emptyInputReturnsNull() {
        assertNull(SsdpMessageParser.parse(""))
    }

    @Test
    fun responseMissingRequiredLocationReturnsNull() {
        val raw =
            "HTTP/1.1 200 OK\r\nST: upnp:rootdevice\r\nUSN: uuid:x::upnp:rootdevice\r\n\r\n"
        assertNull(SsdpMessageParser.parse(raw))
    }

    @Test
    fun aliveMissingRequiredLocationReturnsNull() {
        val raw =
            "NOTIFY * HTTP/1.1\r\nNT: upnp:rootdevice\r\nNTS: ssdp:alive\r\n" +
                "USN: uuid:x::upnp:rootdevice\r\n\r\n"
        assertNull(SsdpMessageParser.parse(raw))
    }

    @Test
    fun observedMSearchSurfacesAsSearchRequest() {
        val raw =
            "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\nMX: 1\r\nST: ssdp:all\r\n\r\n"
        assertEquals(SsdpMessage.SearchRequest, SsdpMessageParser.parse(raw))
    }

    @Test
    fun unknownLeadingTokenReturnsNull() {
        assertNull(SsdpMessageParser.parse("GARBAGE not-a-real line\r\nfoo: bar\r\n\r\n"))
    }

    @Test
    fun responseWithNonUpnpStSurfacesAsCustomTarget() {
        // A device answering with a vendor ST (roku:ecp) must be surfaced, not
        // dropped — the parser uses parseOrCustom, so searchTarget is Custom.
        val raw =
            "HTTP/1.1 200 OK\r\nLOCATION: http://192.168.4.50:8060/\r\n" +
                "ST: roku:ecp\r\nUSN: uuid:roku:ecp:X00500ABCDEF\r\n\r\n"
        val r = parseResponse(raw)
        assertEquals(SearchTarget.Custom("roku:ecp"), r.searchTarget)
        assertEquals("http://192.168.4.50:8060/", r.location)
    }

    @Test
    fun aliveWithNonUpnpNtSurfacesAsCustomTarget() {
        val raw =
            "NOTIFY * HTTP/1.1\r\nNT: roku:ecp\r\nNTS: ssdp:alive\r\n" +
                "LOCATION: http://192.168.4.50:8060/\r\nUSN: uuid:roku:ecp:X00500ABCDEF\r\n\r\n"
        val n = parseNotification(raw)
        assertTrue(n is Notification.Alive)
        assertEquals(SearchTarget.Custom("roku:ecp"), n.advertisement.notificationTarget)
    }

    @Test
    fun responseWithBlankStReturnsNull() {
        // A present-but-empty ST is junk — parseOrCustom returns null, so the
        // datagram is still dropped (blank is not a custom target).
        val raw =
            "HTTP/1.1 200 OK\r\nLOCATION: http://192.168.4.50:8060/\r\n" +
                "ST: \r\nUSN: uuid:x\r\n\r\n"
        assertNull(SsdpMessageParser.parse(raw))
    }
}
