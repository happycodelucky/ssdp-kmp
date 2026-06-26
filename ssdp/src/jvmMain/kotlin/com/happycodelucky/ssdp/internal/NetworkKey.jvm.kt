/*
 * ssdp-kmp — JVM local-subnet probe via java.net.NetworkInterface.
 */
package com.happycodelucky.ssdp.internal

import java.net.Inet4Address
import java.net.NetworkInterface

internal actual fun localSubnetKey(): String? = jvmLocalSubnetKey()

/**
 * Shared JVM/Android implementation: find the first up, non-loopback,
 * multicast-capable interface with an IPv4 address and return its subnet in
 * `network/prefix` form (e.g. "192.168.1.0/24"). Best-effort; returns `null` if
 * nothing suitable is found.
 */
internal fun jvmLocalSubnetKey(): String? =
    runCatching {
        NetworkInterface
            .getNetworkInterfaces()
            .toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback && it.supportsMulticast() }
            .flatMap { nif -> nif.interfaceAddresses.asSequence() }
            .firstOrNull { it.address is Inet4Address }
            ?.let { ia ->
                val addr = ia.address as Inet4Address
                val prefix = ia.networkPrefixLength.toInt()
                "${networkAddress(addr.address, prefix)}/$prefix"
            }
    }.getOrNull()

/** Mask an IPv4 address (4 bytes) to its network address string for the prefix. */
private fun networkAddress(
    addr: ByteArray,
    prefix: Int,
): String {
    val masked = ByteArray(4)
    for (i in 0 until 4) {
        val bitsInByte = (prefix - i * 8).coerceIn(0, 8)
        val mask = if (bitsInByte == 0) 0 else (0xFF shl (8 - bitsInByte)) and 0xFF
        masked[i] = (addr[i].toInt() and mask).toByte()
    }
    return masked.joinToString(".") { (it.toInt() and 0xFF).toString() }
}
