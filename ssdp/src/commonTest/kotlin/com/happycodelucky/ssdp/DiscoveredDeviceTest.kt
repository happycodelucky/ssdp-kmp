/*
 * ssdp-kmp — DiscoveredDevice property tests.
 *
 * Covers `address`, the host derived from `location` (scheme/port/path stripped).
 * The host-parsing edge cases live in UpnpUrlTest; here we only assert the
 * property is wired to location and degrades to null when location is absent.
 */
package com.happycodelucky.ssdp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class DiscoveredDeviceTest {
    private fun device(location: String?) =
        DiscoveredDevice(
            usn = "uuid:x::urn:schemas-upnp-org:device:ZonePlayer:1",
            target = SearchTarget.RootDevice,
            location = location,
            server = null,
            cacheControl = null,
            bootId = null,
            configId = null,
            firstSeen = Instant.fromEpochSeconds(0),
            lastSeen = Instant.fromEpochSeconds(0),
            expiresAt = null,
            otherHeaders = SsdpHeaders.EMPTY,
        )

    @Test
    fun addressIsHostOfLocation() {
        assertEquals(
            "192.168.4.20",
            device("http://192.168.4.20:1400/xml/device_description.xml").address,
        )
    }

    @Test
    fun addressNullWhenNoLocation() {
        assertNull(device(null).address)
    }

    @Test
    fun addressNullWhenLocationUnparseable() {
        assertNull(device("garbage").address)
    }
}
