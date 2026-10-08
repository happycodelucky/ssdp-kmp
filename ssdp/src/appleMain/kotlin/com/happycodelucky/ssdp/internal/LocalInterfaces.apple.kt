/*
 * ssdp-kmp — Apple interface enumeration for the SSDP transport, via getifaddrs.
 *
 * Lists each interface with an IPv4 address as a LocalInterface, so the common
 * selectMulticastInterfaces policy (LocalInterface.kt) picks which ones the
 * transport joins and searches on. `getifaddrs` lists an interface once per
 * address, so only its first IPv4 entry is kept.
 *
 * The IFF_* flag constants aren't cleanly exposed by K/N's cinterop (see
 * NetworkKey.apple.kt), so their values come from <net/if.h>; they have been
 * stable across every Darwin release.
 */
@file:OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)

package com.happycodelucky.ssdp.internal

import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.darwin.freeifaddrs
import platform.darwin.getifaddrs
import platform.darwin.ifaddrs
import platform.posix.AF_INET
import platform.posix.sockaddr_in

private const val IFF_UP = 0x1u
private const val IFF_LOOPBACK = 0x8u
private const val IFF_POINTOPOINT = 0x10u
private const val IFF_MULTICAST = 0x8000u

/** Every local interface with an IPv4 address, in `getifaddrs` order; empty if the listing fails. */
internal fun appleLocalInterfaces(): List<LocalInterface> =
    memScoped {
        val listHolder = alloc<CPointerVar<ifaddrs>>()
        if (getifaddrs(listHolder.ptr) != 0) return emptyList()
        val found = LinkedHashMap<String, LocalInterface>()
        try {
            var node = listHolder.value
            while (node != null) {
                val ifa = node.pointed
                val addr = ifa.ifa_addr
                val name = ifa.ifa_name?.toKString()
                val isIpv4 = addr != null && addr.pointed.sa_family.toInt() == AF_INET
                if (isIpv4 && name != null && name !in found) {
                    val flags = ifa.ifa_flags
                    found[name] =
                        LocalInterface(
                            name = name,
                            ipv4 =
                                networkOrderToDotted(
                                    addr
                                        .reinterpret<sockaddr_in>()
                                        .pointed.sin_addr.s_addr,
                                ),
                            isUp = flags and IFF_UP != 0u,
                            isLoopback = flags and IFF_LOOPBACK != 0u,
                            isPointToPoint = flags and IFF_POINTOPOINT != 0u,
                            supportsMulticast = flags and IFF_MULTICAST != 0u,
                        )
                }
                node = ifa.ifa_next
            }
        } finally {
            freeifaddrs(listHolder.value)
        }
        found.values.toList()
    }
