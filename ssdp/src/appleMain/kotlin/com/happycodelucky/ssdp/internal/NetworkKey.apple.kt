/*
 * ssdp-kmp — Apple local-subnet probe via getifaddrs.
 *
 * Walks the interface list, finds the first IPv4 interface whose address is not
 * loopback, and returns its subnet in `network/prefix` form
 * (e.g. "192.168.1.0/24") by masking the address with the interface's netmask.
 * Best-effort; `null` on any failure.
 *
 * `getifaddrs`/`ifaddrs`/`freeifaddrs` live in `platform.darwin` (from
 * <ifaddrs.h>), NOT `platform.posix` — that mis-import was the first failure.
 * We avoid the IFF_* flag constants (which aren't cleanly exposed) by filtering
 * loopback on the address itself (127.0.0.0/8).
 *
 * Byte-order: Apple arm64 is little-endian; `s_addr` is network-order, so
 * octet[0] is the LSB of the host-order UInt K/N reads back.
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
import kotlinx.cinterop.value
import platform.darwin.freeifaddrs
import platform.darwin.getifaddrs
import platform.darwin.ifaddrs
import platform.posix.AF_INET
import platform.posix.sockaddr_in

internal actual fun localSubnetKey(): String? =
    memScoped {
        val listHolder = alloc<CPointerVar<ifaddrs>>()
        if (getifaddrs(listHolder.ptr) != 0) return null
        try {
            var node = listHolder.value
            while (node != null) {
                val ifa = node.pointed
                val addr = ifa.ifa_addr
                val netmask = ifa.ifa_netmask

                if (addr != null && netmask != null && addr.pointed.sa_family.toInt() == AF_INET) {
                    val addrIn =
                        addr
                            .reinterpret<sockaddr_in>()
                            .pointed.sin_addr.s_addr
                    val maskIn =
                        netmask
                            .reinterpret<sockaddr_in>()
                            .pointed.sin_addr.s_addr
                    // Skip loopback (127.0.0.0/8): low byte (octet[0]) == 127.
                    if ((addrIn and 0xFFu) != 127u) {
                        val network = addrIn and maskIn
                        return "${networkOrderToDotted(network)}/${prefixLength(maskIn)}"
                    }
                }
                node = ifa.ifa_next
            }
            null
        } finally {
            freeifaddrs(listHolder.value)
        }
    }

/** Network-order (big-endian) s_addr → dotted-quad. octet[0] is the LSB on LE hosts. */
private fun networkOrderToDotted(networkOrder: UInt): String {
    val b0 = networkOrder and 0xFFu
    val b1 = (networkOrder shr 8) and 0xFFu
    val b2 = (networkOrder shr 16) and 0xFFu
    val b3 = (networkOrder shr 24) and 0xFFu
    return "$b0.$b1.$b2.$b3"
}

/** Count set bits in a netmask to get the CIDR prefix length. */
private fun prefixLength(maskNetworkOrder: UInt): Int {
    var count = 0
    var v = maskNetworkOrder
    while (v != 0u) {
        count += (v and 1u).toInt()
        v = v shr 1
    }
    return count
}
