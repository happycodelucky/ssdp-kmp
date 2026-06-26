/*
 * ssdp-kmp — Apple (iOS + macOS) multicast socket over POSIX BSD sockets.
 *
 * Plan decision 2: POSIX rather than Network.framework. The newer
 * NWConnectionGroup / NWMulticastGroup APIs the Swift client uses are not in
 * Kotlin/Native's `platform.Network` cinterop bindings, whereas the BSD socket
 * APIs (`socket`, `setsockopt`, `bind`, `sendto`, `recvfrom`) are fully exposed
 * via `platform.posix` and behave identically on iOS and macOS. This also makes
 * the Apple transport's datagram model match the JVM/Android one exactly.
 *
 * IMPORTANT (iOS): joining 239.255.255.250 requires the
 * `com.apple.developer.networking.multicast` entitlement. Without it, the
 * IP_ADD_MEMBERSHIP setsockopt fails and construction throws
 * SsdpError.MulticastJoinFailed. See docs/platforms/ios.md.
 *
 * The blocking recvfrom loop runs on a dedicated worker thread (a coroutine on
 * Dispatchers.IO) so it never blocks the caller; received datagrams are emitted
 * into a SharedFlow.
 */
@file:OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)

package com.happycodelucky.ssdp.internal

import com.happycodelucky.ssdp.SsdpError
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pin
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import platform.posix.AF_INET
import platform.posix.IPPROTO_IP
import platform.posix.IP_ADD_MEMBERSHIP
import platform.posix.SOCK_DGRAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_REUSEADDR
import platform.posix.SO_REUSEPORT
import platform.posix.bind
import platform.posix.close
import platform.posix.ip_mreq
import platform.posix.recvfrom
import platform.posix.sendto
import platform.posix.setsockopt
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar

private const val SSDP_GROUP = "239.255.255.250"
private const val SSDP_PORT = 1900
private const val MAX_DATAGRAM = 65_507

/**
 * Port 1900 in network byte order (big-endian) for `sin_port`. Computed in
 * Kotlin rather than via `htons` so we don't depend on that symbol being
 * exposed as a function (vs a macro) across Kotlin/Native posix variants.
 */
private val SSDP_PORT_BE: UShort =
    (((SSDP_PORT and 0xFF) shl 8) or ((SSDP_PORT shr 8) and 0xFF)).toUShort()

/** `INADDR_ANY` is 0 in any byte order. */
private const val IN_ADDR_ANY: UInt = 0u

/**
 * Parse a dotted-quad IPv4 string to its `in_addr.s_addr` value. `s_addr` holds
 * the address in *network byte order* (big-endian): the first octet is the byte
 * sent first on the wire. All Apple targets (iOS/macOS arm64) are little-endian
 * hosts, so in the host-order `UInt` that K/N reads back, the first octet lands
 * in the least-significant byte. This replaces `inet_addr` to avoid the
 * macro/function ambiguity on Apple K/N. Returns `0xFFFFFFFF` (INADDR_NONE) for
 * malformed input, matching `inet_addr`.
 */
private fun ipv4ToNetworkOrder(dotted: String): UInt {
    val parts = dotted.split(".")
    if (parts.size != 4) return 0xFFFF_FFFFu
    var result = 0u
    for ((index, part) in parts.withIndex()) {
        val octet = part.toUIntOrNull() ?: return 0xFFFF_FFFFu
        if (octet > 255u) return 0xFFFF_FFFFu
        result = result or (octet shl (8 * index))
    }
    return result
}

internal class AppleMulticastSocket(
    bindInterface: String?,
) : MulticastSocket {
    private val fd: Int

    // Dispatchers.IO is internal on Kotlin/Native; Default is the public choice.
    // The recvfrom loop is one long-lived coroutine, so a dedicated IO pool
    // buys nothing here over Default.
    private val workerScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var receiveJob: Job? = null

    private val _incoming =
        MutableSharedFlow<Datagram>(
            replay = 0,
            extraBufferCapacity = 256,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    override val incoming: Flow<Datagram> = _incoming.asSharedFlow()

    init {
        fd = socket(AF_INET, SOCK_DGRAM, 0)
        if (fd < 0) {
            throw SsdpError.TransportFailed(details = "socket() failed (errno=${platform.posix.errno})")
        }

        try {
            configureAndJoin(bindInterface)
        } catch (e: Throwable) {
            close(fd)
            throw e
        }

        startReceiveLoop()
    }

    private fun configureAndJoin(bindInterface: String?) {
        memScoped {
            // SO_REUSEADDR + SO_REUSEPORT: let multiple sockets/apps bind 1900.
            val one = alloc<platform.posix.uint32_tVar>().apply { value = 1u }
            setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<platform.posix.uint32_tVar>().convert())
            setsockopt(fd, SOL_SOCKET, SO_REUSEPORT, one.ptr, sizeOf<platform.posix.uint32_tVar>().convert())

            // bind to 0.0.0.0:1900 so we receive both NOTIFY multicasts and
            // unicast M-SEARCH replies the kernel delivers to the bound port.
            val addr =
                alloc<sockaddr_in>().apply {
                    sin_family = AF_INET.convert()
                    sin_port = SSDP_PORT_BE
                    sin_addr.s_addr = IN_ADDR_ANY
                }
            if (bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0) {
                throw SsdpError.MulticastJoinFailed(details = "bind() failed (errno=${platform.posix.errno})")
            }

            // Join the multicast group. On iOS this is where a missing
            // com.apple.developer.networking.multicast entitlement surfaces.
            val mreq =
                alloc<ip_mreq>().apply {
                    imr_multiaddr.s_addr = ipv4ToNetworkOrder(SSDP_GROUP)
                    imr_interface.s_addr =
                        if (bindInterface != null) ipv4ToNetworkOrder(bindInterface) else IN_ADDR_ANY
                }
            if (setsockopt(fd, IPPROTO_IP, IP_ADD_MEMBERSHIP, mreq.ptr, sizeOf<ip_mreq>().convert()) != 0) {
                throw SsdpError.MulticastJoinFailed(
                    details =
                        "IP_ADD_MEMBERSHIP failed (errno=${platform.posix.errno}); " +
                            "on iOS check the com.apple.developer.networking.multicast entitlement",
                )
            }
        }
    }

    private fun startReceiveLoop() {
        receiveJob =
            workerScope.launch {
                val buffer = ByteArray(MAX_DATAGRAM)
                val pinned = buffer.pin()
                try {
                    while (true) {
                        memScoped {
                            val srcAddr = alloc<sockaddr_in>()
                            val srcLen = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in>().convert() }
                            val received =
                                recvfrom(
                                    fd,
                                    pinned.addressOf(0),
                                    MAX_DATAGRAM.convert(),
                                    0,
                                    srcAddr.ptr.reinterpret<sockaddr>(),
                                    srcLen.ptr,
                                )
                            if (received <= 0) {
                                // Socket closed or error → exit the loop.
                                throw LoopExit
                            }
                            val text = buffer.decodeToString(0, received.convert(), throwOnInvalidSequence = false)
                            // Source endpoint string is best-effort diagnostics only
                            // (the registry keys on USN, never on packet source), so
                            // we avoid fragile inet_ntoa/ntohs cinterop and report the
                            // raw network-order address bits. Good enough for logging.
                            val source = "ipv4:${srcAddr.sin_addr.s_addr}"
                            _incoming.tryEmit(Datagram(text = text, source = source))
                        }
                    }
                } catch (_: LoopExit) {
                    // normal teardown
                } finally {
                    pinned.unpin()
                }
            }
    }

    override suspend fun send(bytes: ByteArray) {
        memScoped {
            val dest =
                alloc<sockaddr_in>().apply {
                    sin_family = AF_INET.convert()
                    sin_port = SSDP_PORT_BE
                    sin_addr.s_addr = ipv4ToNetworkOrder(SSDP_GROUP)
                }
            val pinned = bytes.pin()
            try {
                val sent =
                    sendto(
                        fd,
                        pinned.addressOf(0),
                        bytes.size.convert(),
                        0,
                        dest.ptr.reinterpret<sockaddr>(),
                        sizeOf<sockaddr_in>().convert(),
                    )
                if (sent < 0) {
                    throw SsdpError.TransportFailed(details = "sendto() failed (errno=${platform.posix.errno})")
                }
            } finally {
                pinned.unpin()
            }
        }
    }

    override fun close() {
        receiveJob?.cancel()
        workerScope.cancel()
        // Closing the fd unblocks the recvfrom in the loop.
        close(fd)
    }

    private object LoopExit : RuntimeException()
}

internal actual fun openMulticastSocket(bindInterface: String?): MulticastSocket = AppleMulticastSocket(bindInterface)
