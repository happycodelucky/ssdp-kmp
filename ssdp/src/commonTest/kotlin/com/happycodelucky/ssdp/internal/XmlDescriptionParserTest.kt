/*
 * ssdp-kmp — parser tests against real UPnP description captures.
 *
 * Locks down: recursive deviceList nesting (eero), vendor/foreign-namespace
 * tolerance (Sonos), icon + multi-service extraction, and ParseFailed on
 * malformed XML.
 */
package com.happycodelucky.ssdp.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class XmlDescriptionParserTest {
    private val parser = XmlDescriptionParser

    @Test
    fun eeroRecursiveDeviceTreeParses() {
        val desc = parser.parse(DescriptionFixtures.EERO_IGD, sourceUrl = "http://192.168.4.1:1900/igd.xml")
        assertEquals(1, desc.specVersion.major)
        assertEquals("http://192.168.4.1:1900/", desc.urlBase)

        val root = desc.device
        assertEquals("urn:schemas-upnp-org:device:InternetGatewayDevice:1", root.deviceType)
        assertEquals("eero", root.friendlyName)
        assertEquals("eero inc.", root.manufacturer)
        assertEquals("eero Pro 6", root.modelName)
        assertEquals("https://eero.com", root.presentationUrl)

        // One service on the root, with absolute-path URLs.
        assertEquals(1, root.services.size)
        assertEquals("/l3f", root.services[0].controlUrl)
        assertEquals("/l3f.xml", root.services[0].scpdUrl)

        // Recursive: root -> WANDevice -> WANConnectionDevice.
        assertEquals(1, root.embeddedDevices.size)
        val wan = root.embeddedDevices[0]
        assertEquals("urn:schemas-upnp-org:device:WANDevice:1", wan.deviceType)
        assertEquals(1, wan.embeddedDevices.size)
        val wanConn = wan.embeddedDevices[0]
        assertEquals("urn:schemas-upnp-org:device:WANConnectionDevice:1", wanConn.deviceType)
        assertEquals("urn:schemas-upnp-org:service:WANIPConnection:1", wanConn.services[0].serviceType)
    }

    @Test
    fun eeroRelativeServiceUrlResolvesAgainstUrlBase() {
        val desc = parser.parse(DescriptionFixtures.EERO_IGD, sourceUrl = "http://192.168.4.1:1900/igd.xml")
        assertEquals("http://192.168.4.1:1900/l3f", desc.resolveUrl(desc.device.services[0].controlUrl!!))
    }

    @Test
    fun sonosToleratesVendorAndForeignNamespaceElements() {
        // The decisive robustness test: the Sonos doc has softwareVersion,
        // roomName, feature1, an empty <extraVersion/>, a nested <versions>
        // block, and X_Rhapsody / qq:X_QPlay foreign-namespace extensions. None
        // of these are in our model; the parse must still succeed and extract the
        // standard fields.
        val desc =
            parser.parse(
                DescriptionFixtures.SONOS_ZONEPLAYER,
                sourceUrl = "http://192.168.4.20:1400/xml/device_description.xml",
            )
        val device = desc.device
        assertEquals("urn:schemas-upnp-org:device:ZonePlayer:1", device.deviceType)
        assertEquals("Sonos, Inc.", device.manufacturer)
        assertEquals("Sonos Arc Ultra", device.modelName)
        assertEquals("S45", device.modelNumber)
        assertEquals("uuid:RINCON_C438751026E501400", device.udn)
        assertNull(desc.urlBase) // Sonos omits URLBase.

        // Icon extracted.
        assertEquals(1, device.icons.size)
        val icon = device.icons[0]
        assertEquals("image/png", icon.mimeType)
        assertEquals(48, icon.width)
        assertEquals(24, icon.depth)
        assertEquals("/img/icon-S45.png", icon.url)

        // Both standard services extracted (vendor blocks between/around them ignored).
        assertEquals(2, device.services.size)
        assertTrue(device.services.any { it.serviceType.endsWith("AlarmClock:1") })
        assertTrue(device.services.any { it.serviceType.endsWith("MusicServices:1") })
    }

    @Test
    fun sonosRelativeUrlResolvesAgainstSourceUrlWhenNoUrlBase() {
        val desc =
            parser.parse(
                DescriptionFixtures.SONOS_ZONEPLAYER,
                sourceUrl = "http://192.168.4.20:1400/xml/device_description.xml",
            )
        val control =
            desc.device.services
                .first { it.serviceType.endsWith("AlarmClock:1") }
                .controlUrl!!
        assertEquals("http://192.168.4.20:1400/AlarmClock/Control", desc.resolveUrl(control))
        // Icon path resolves too.
        assertEquals("http://192.168.4.20:1400/img/icon-S45.png", desc.resolveUrl(desc.device.icons[0].url!!))
    }

    @Test
    fun sourceUrlIsRecorded() {
        val desc = parser.parse(DescriptionFixtures.EERO_IGD, sourceUrl = "http://192.168.4.1:1900/igd.xml")
        assertEquals("http://192.168.4.1:1900/igd.xml", desc.sourceUrl)
    }

    @Test
    fun malformedXmlThrows() {
        assertFailsWith<Exception> {
            parser.parse(DescriptionFixtures.MALFORMED, sourceUrl = "http://192.168.1.1/desc.xml")
        }
    }

    private fun assertNull(value: Any?) = assertEquals(null, value)
}
