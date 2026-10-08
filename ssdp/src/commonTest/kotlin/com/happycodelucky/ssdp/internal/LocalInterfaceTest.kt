/*
 * ssdp-kmp — which interfaces the transport joins and searches on.
 *
 * The interfaces below are a Mac's real listing with a VPN up (the JVM's order),
 * the case that broke discovery: picking the first multicast-capable interface
 * chose an IPv6-only utun tunnel and the IPv4 join failed (LESSONS B-015).
 */
package com.happycodelucky.ssdp.internal

import com.happycodelucky.ssdp.SsdpError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LocalInterfaceTest {
    private fun local(
        name: String,
        ipv4: String,
        isUp: Boolean = true,
        isLoopback: Boolean = false,
        isPointToPoint: Boolean = false,
        supportsMulticast: Boolean = true,
    ) = LocalInterface(name, ipv4, isUp, isLoopback, isPointToPoint, supportsMulticast)

    // utun/awdl/llw interfaces carry only IPv6, so a platform never lists them.
    private val vpnTunnel = local("utun6", "10.8.0.2", isPointToPoint = true)
    private val vmBridge = local("bridge100", "192.168.139.3")
    private val wifi = local("en0", "192.168.0.229")
    private val loopback = local("lo0", "127.0.0.1", isLoopback = true)
    private val unplugged = local("en7", "169.254.10.4", isUp = false)
    private val noMulticast = local("en9", "10.0.0.9", supportsMulticast = false)

    @Test
    fun picksEveryLanInterfaceByDefault() {
        val picked = selectMulticastInterfaces(listOf(vpnTunnel, vmBridge, wifi, loopback, unplugged, noMulticast), null)

        assertEquals(listOf(vmBridge, wifi), picked)
    }

    @Test
    fun bindInterfaceNarrowsToOneByNameOrAddress() {
        val all = listOf(vmBridge, wifi, loopback)

        assertEquals(listOf(wifi), selectMulticastInterfaces(all, "en0"))
        assertEquals(listOf(wifi), selectMulticastInterfaces(all, "192.168.0.229"))
        // An explicit choice is honored even where the default policy would skip it.
        assertEquals(listOf(loopback), selectMulticastInterfaces(all, "lo0"))
    }

    @Test
    fun anUnknownBindInterfaceFailsInsteadOfSearchingElsewhere() {
        val error = assertFailsWith<SsdpError.MulticastJoinFailed> { selectMulticastInterfaces(listOf(wifi), "en5") }

        assertTrue("en5" in error.details && "en0 (192.168.0.229)" in error.details, error.details)
    }

    @Test
    fun noQualifyingInterfaceMeansTheOsDefault() {
        assertEquals(emptyList(), selectMulticastInterfaces(listOf(loopback, vpnTunnel), null))
    }

    @Test
    fun onEachInterfaceSucceedsWhenAnyInterfaceDoes() {
        val tried = mutableListOf<LocalInterface?>()

        onEachInterface(
            listOf(vmBridge, wifi),
            action = {
                tried += it
                if (it == vmBridge) error("no route")
            },
            wrap = { details, cause -> SsdpError.TransportFailed(details, cause) },
        )

        assertEquals<List<LocalInterface?>>(listOf(vmBridge, wifi), tried)
    }

    @Test
    fun onEachInterfaceFailsWithEveryFailureWhenAllFail() {
        val error =
            assertFailsWith<SsdpError.MulticastJoinFailed> {
                onEachInterface(
                    listOf(vmBridge, wifi),
                    action = { error("down: ${it?.name}") },
                    wrap = { details, cause -> SsdpError.MulticastJoinFailed(details, cause) },
                )
            }

        assertEquals("bridge100 (192.168.139.3): down: bridge100; en0 (192.168.0.229): down: en0", error.details)
    }

    @Test
    fun onEachInterfaceUsesTheOsDefaultWithNoInterfaces() {
        val tried = mutableListOf<LocalInterface?>()

        onEachInterface(emptyList<LocalInterface>(), action = { tried += it }, wrap = { d, c -> SsdpError.TransportFailed(d, c) })

        assertEquals<List<LocalInterface?>>(listOf(null), tried)
    }
}
