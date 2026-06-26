/*
 * ssdp-kmp — URL resolution tests (pure, no HTTP).
 *
 * Covers the reference forms real UPnP devices emit (eero `/l3f`, Sonos
 * `/AlarmClock/Control`, relative merges) plus the UPnP rule that URLBase wins
 * over the description URL when present.
 */
package com.happycodelucky.ssdp.internal

import kotlin.test.Test
import kotlin.test.assertEquals

class UpnpUrlTest {
    @Test
    fun absoluteRefReturnedUnchanged() {
        assertEquals(
            "http://example.com/x",
            UpnpUrl.resolve("http://example.com/x", base = "http://192.168.1.1:1900/desc.xml"),
        )
        assertEquals(
            "https://www.sonos.com/products",
            UpnpUrl.resolve("https://www.sonos.com/products", base = "http://192.168.4.20:1400/xml/device_description.xml"),
        )
    }

    @Test
    fun absolutePathResolvesAgainstSchemeAndAuthority() {
        // eero: SCPDURL "/l3f.xml", no URLBase → resolve against the LOCATION url.
        assertEquals(
            "http://192.168.4.1:1900/l3f.xml",
            UpnpUrl.resolve("/l3f.xml", base = "http://192.168.4.1:1900/igd.xml"),
        )
        // Sonos: controlURL "/AlarmClock/Control".
        assertEquals(
            "http://192.168.4.20:1400/AlarmClock/Control",
            UpnpUrl.resolve("/AlarmClock/Control", base = "http://192.168.4.20:1400/xml/device_description.xml"),
        )
    }

    @Test
    fun relativePathMergesOntoBaseDirectory() {
        // base dir is /xml/, ref "icon.png" → /xml/icon.png
        assertEquals(
            "http://192.168.4.20:1400/xml/icon.png",
            UpnpUrl.resolve("icon.png", base = "http://192.168.4.20:1400/xml/device_description.xml"),
        )
        // base path is just "/desc.xml" (dir "/"), ref "sub/x" → /sub/x
        assertEquals(
            "http://10.0.0.5:80/sub/x",
            UpnpUrl.resolve("sub/x", base = "http://10.0.0.5:80/desc.xml"),
        )
    }

    @Test
    fun protocolRelativeRefTakesBaseScheme() {
        assertEquals(
            "http://cdn.example.com/icon.png",
            UpnpUrl.resolve("//cdn.example.com/icon.png", base = "http://192.168.1.1:1900/desc.xml"),
        )
    }

    @Test
    fun blankRefReturnsBase() {
        assertEquals(
            "http://192.168.1.1:1900/desc.xml",
            UpnpUrl.resolve("", base = "http://192.168.1.1:1900/desc.xml"),
        )
        assertEquals(
            "http://192.168.1.1:1900/desc.xml",
            UpnpUrl.resolve("   ", base = "http://192.168.1.1:1900/desc.xml"),
        )
    }

    @Test
    fun authorityWithoutPathTreatedAsRoot() {
        assertEquals(
            "http://192.168.1.1:1900/foo",
            UpnpUrl.resolve("/foo", base = "http://192.168.1.1:1900"),
        )
        assertEquals(
            "http://192.168.1.1:1900/foo",
            UpnpUrl.resolve("foo", base = "http://192.168.1.1:1900"),
        )
    }

    @Test
    fun queryAndFragmentOnBaseAreIgnoredForResolution() {
        assertEquals(
            "http://192.168.1.1:1900/xml/icon.png",
            UpnpUrl.resolve("icon.png", base = "http://192.168.1.1:1900/xml/desc.xml?v=2"),
        )
    }

    @Test
    fun descriptionResolveUrlUsesUrlBaseWhenPresent() {
        // URLBase present and different host → wins over sourceUrl.
        val desc =
            com.happycodelucky.ssdp.DeviceDescription(
                specVersion = com.happycodelucky.ssdp.SpecVersion(1, 0),
                urlBase = "http://192.168.4.1:1900/",
                device =
                    com.happycodelucky.ssdp.Device(
                        deviceType = "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
                        friendlyName = "eero",
                        manufacturer = null,
                        manufacturerUrl = null,
                        modelName = null,
                        modelNumber = null,
                        modelDescription = null,
                        modelUrl = null,
                        serialNumber = null,
                        udn = "uuid:x",
                        upc = null,
                        presentationUrl = null,
                    ),
                sourceUrl = "http://192.168.4.1:1900/igd.xml",
            )
        assertEquals("http://192.168.4.1:1900/l3f", desc.resolveUrl("/l3f"))
    }

    @Test
    fun descriptionResolveUrlFallsBackToSourceUrlWhenUrlBaseBlank() {
        val desc =
            com.happycodelucky.ssdp.DeviceDescription(
                specVersion = com.happycodelucky.ssdp.SpecVersion(1, 0),
                urlBase = null,
                device =
                    com.happycodelucky.ssdp.Device(
                        deviceType = "urn:schemas-upnp-org:device:ZonePlayer:1",
                        friendlyName = "Sonos",
                        manufacturer = null,
                        manufacturerUrl = null,
                        modelName = null,
                        modelNumber = null,
                        modelDescription = null,
                        modelUrl = null,
                        serialNumber = null,
                        udn = "uuid:RINCON",
                        upc = null,
                        presentationUrl = null,
                    ),
                sourceUrl = "http://192.168.4.20:1400/xml/device_description.xml",
            )
        assertEquals(
            "http://192.168.4.20:1400/AlarmClock/Control",
            desc.resolveUrl("/AlarmClock/Control"),
        )
    }
}
