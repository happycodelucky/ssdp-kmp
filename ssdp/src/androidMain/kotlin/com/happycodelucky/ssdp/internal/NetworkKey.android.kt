/*
 * ssdp-kmp — Android local-subnet probe via java.net.NetworkInterface.
 *
 * Same mechanism as the JVM probe (Android runs the same java.net APIs). Kept as
 * a separate actual because androidMain and jvmMain are sibling source sets with
 * no shared intermediate; the logic is small enough that duplication is cheaper
 * than introducing a shared module.
 */
package com.happycodelucky.ssdp.internal

import java.net.Inet4Address
import java.net.NetworkInterface

internal actual fun localSubnetKey(): String? =
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
