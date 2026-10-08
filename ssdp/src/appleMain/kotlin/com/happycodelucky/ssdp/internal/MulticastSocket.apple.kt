/*
 * ssdp-kmp — Apple (iOS + macOS) SSDP transport over POSIX BSD sockets.
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
 * The transport is an SsdpSocketPair (see SsdpSocketPair.kt): a NOTIFY socket
 * bound to 1900 and joined to the group, plus an M-SEARCH socket on an
 * ephemeral port whose unicast replies no other 1900 socket on the host can
 * steal. Each socket's blocking recvfrom loop runs on Dispatchers.IO so it never
 * blocks the caller; received datagrams are emitted into a SharedFlow. The group
 * is joined, and every M-SEARCH sent, on each interface selectMulticastInterfaces
 * picks (LocalInterface.kt) from the getifaddrs listing.
 */
@file:OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)

package com.happycodelucky.ssdp.internal

import com.happycodelucky.ssdp.SsdpError
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.MemScope
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
import kotlinx.coroutines.IO
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
import platform.posix.IP_MULTICAST_IF
import platform.posix.SOCK_DGRAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_REUSEADDR
import platform.posix.SO_REUSEPORT
import platform.posix.bind
import platform.posix.close
import platform.posix.getsockname
import platform.posix.in_addr
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

/** Port 0 asks `bind()` for a free ephemeral port; 0 in any byte order. */
private const val EPHEMERAL_PORT_BE: UShort = 0u

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

/**
 * One POSIX UDP socket of the Apple transport: a recvfrom loop feeding
 * [incoming], [send] to the SSDP group, and [close]. Both halves of the
 * [SsdpSocketPair] are one of these; only how [fd] was bound and joined
 * differs ([openAppleNotifySocket], [openAppleSearchSocket]). Takes ownership
 * of [fd] and starts receiving immediately.
 *
 * @param sendInterfaces the interfaces [send] goes out on, one datagram each;
 *   empty sends once on the OS default route.
 */
internal class AppleUdpSocket(private val fd: Int, private val sendInterfaces: List<LocalInterface> = emptyList()) : MulticastSocket {
    // IP_MULTICAST_IF is a socket option, so choosing the interface and sending
    // must not interleave with another retransmit loop's send.
    private val sendLock = SynchronizedObject()

    // Each recvfrom loop parks a thread for the socket's whole life, and a
    // client now runs two. Dispatchers.IO is elastic for exactly this; parking
    // them on Default (sized to the CPU count) could starve the client's own
    // coroutines, which run there, on a low-core device or with several clients.
    private val workerScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val closed = atomic(false)

    private val _incoming =
        MutableSharedFlow<Datagram>(
            replay = 0,
            extraBufferCapacity = 256,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    override val incoming: Flow<Datagram> = _incoming.asSharedFlow()

    /** The blocking receive loop; completes once [close] closes [fd]. Exposed for tests. */
    internal val receiveJob: Job = startReceiveLoop()

    /** The local UDP port [fd] is bound to (host order), or -1 if unknown; tests address unicast replies to it. */
    internal val localPort: Int
        get() =
            memScoped {
                val addr = alloc<sockaddr_in>()
                val len = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in>().convert() }
                if (getsockname(fd, addr.ptr.reinterpret(), len.ptr) != 0) return@memScoped -1
                // sin_port is network order; arm64 is little-endian (D-002), so swap.
                val networkOrder = addr.sin_port.toInt()
                ((networkOrder and 0xFF) shl 8) or ((networkOrder shr 8) and 0xFF)
            }

    private fun startReceiveLoop(): Job =
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

    override suspend fun send(bytes: ByteArray) {
        synchronized(sendLock) {
            onEachInterface(
                sendInterfaces,
                action = { networkInterface ->
                    if (networkInterface != null) selectOutgoingInterface(networkInterface)
                    sendToGroup(bytes)
                },
                wrap = { details, cause -> SsdpError.TransportFailed(details = details, cause = cause) },
            )
        }
    }

    private fun selectOutgoingInterface(networkInterface: LocalInterface) {
        memScoped {
            val addr = alloc<in_addr>().apply { s_addr = ipv4ToNetworkOrder(networkInterface.ipv4) }
            if (setsockopt(fd, IPPROTO_IP, IP_MULTICAST_IF, addr.ptr, sizeOf<in_addr>().convert()) != 0) {
                throw SsdpError.TransportFailed(details = "IP_MULTICAST_IF failed (errno=${platform.posix.errno})")
            }
        }
    }

    private fun sendToGroup(bytes: ByteArray) {
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
        // Guarded: closing an fd twice could close an unrelated descriptor that
        // reused the number in between.
        if (!closed.compareAndSet(expect = false, update = true)) return
        // The cancel is bookkeeping only: recvfrom ignores coroutine cancellation.
        // Closing the fd is what unblocks it (it returns -1 and the loop exits).
        receiveJob.cancel()
        workerScope.cancel()
        close(fd)
    }

    private object LoopExit : RuntimeException()
}

/**
 * Open a UDP socket bound to `0.0.0.0` on [portBe] (network order), closing it
 * again if [configure] or the bind fails. [configure] runs before `bind()` (the
 * reuse options only apply to a later bind); [afterBind] runs after it.
 */
private fun openBoundSocket(portBe: UShort, configure: MemScope.(fd: Int) -> Unit = {}, afterBind: MemScope.(fd: Int) -> Unit = {}): Int {
    val fd = socket(AF_INET, SOCK_DGRAM, 0)
    if (fd < 0) {
        throw SsdpError.TransportFailed(details = "socket() failed (errno=${platform.posix.errno})")
    }
    runCatching {
        memScoped {
            configure(fd)
            val addr =
                alloc<sockaddr_in>().apply {
                    sin_family = AF_INET.convert()
                    sin_port = portBe
                    sin_addr.s_addr = IN_ADDR_ANY
                }
            if (bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0) {
                throw SsdpError.MulticastJoinFailed(details = "bind() failed (errno=${platform.posix.errno})")
            }
            afterBind(fd)
        }
    }.onFailure { close(fd) }.getOrThrow()
    return fd
}

/**
 * Open the NOTIFY socket: `0.0.0.0:1900` with SO_REUSEADDR + SO_REUSEPORT (other
 * SSDP apps on the host share the port), joined to the group on each of
 * [interfaces], or on the OS default (`INADDR_ANY`) when there are none. A join
 * that fails on one interface is skipped.
 *
 * @throws SsdpError.MulticastJoinFailed if the bind fails or no join succeeds.
 */
internal fun openAppleNotifySocket(interfaces: List<LocalInterface>): AppleUdpSocket {
    val fd =
        openBoundSocket(
            portBe = SSDP_PORT_BE,
            configure = { fd ->
                val one = alloc<platform.posix.uint32_tVar>().apply { value = 1u }
                setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<platform.posix.uint32_tVar>().convert())
                setsockopt(fd, SOL_SOCKET, SO_REUSEPORT, one.ptr, sizeOf<platform.posix.uint32_tVar>().convert())
            },
            afterBind = { fd ->
                // Join the multicast group. On iOS this is where a missing
                // com.apple.developer.networking.multicast entitlement surfaces.
                // Memberships end when the fd closes, so close() needn't leave.
                onEachInterface(
                    interfaces,
                    action = { networkInterface ->
                        val mreq =
                            alloc<ip_mreq>().apply {
                                imr_multiaddr.s_addr = ipv4ToNetworkOrder(SSDP_GROUP)
                                imr_interface.s_addr = networkInterface?.let { ipv4ToNetworkOrder(it.ipv4) } ?: IN_ADDR_ANY
                            }
                        if (setsockopt(fd, IPPROTO_IP, IP_ADD_MEMBERSHIP, mreq.ptr, sizeOf<ip_mreq>().convert()) != 0) {
                            throw SsdpError.MulticastJoinFailed(details = "IP_ADD_MEMBERSHIP failed (errno=${platform.posix.errno})")
                        }
                    },
                    wrap = { details, cause ->
                        SsdpError.MulticastJoinFailed(
                            details = "$details; on iOS check the com.apple.developer.networking.multicast entitlement",
                            cause = cause,
                        )
                    },
                )
            },
        )
    return AppleUdpSocket(fd)
}

/**
 * Open the M-SEARCH socket: `0.0.0.0` on an ephemeral port, never joined, so it
 * receives only the unicast replies addressed to it. Each M-SEARCH goes out once
 * per interface in [interfaces] (once on the OS default route when empty); the
 * replies all come back to the one port. TTL is left at the OS default.
 *
 * @throws SsdpError.TransportFailed if the socket can't be opened or bound.
 */
internal fun openAppleSearchSocket(interfaces: List<LocalInterface> = emptyList()): AppleUdpSocket {
    val fd =
        runCatching { openBoundSocket(portBe = EPHEMERAL_PORT_BE) }.getOrElse { failure ->
            // openBoundSocket reports a bind failure as a join failure (right for
            // 1900); for the unjoined M-SEARCH socket it's a transport failure.
            throw if (failure is SsdpError.MulticastJoinFailed) {
                SsdpError.TransportFailed(details = failure.details, cause = failure)
            } else {
                failure
            }
        }
    return AppleUdpSocket(fd, sendInterfaces = interfaces)
}

internal actual fun openMulticastSocket(bindInterface: String?): MulticastSocket {
    // Chosen once, so the NOTIFY joins and the M-SEARCH sends cover the same set.
    val interfaces = selectMulticastInterfaces(appleLocalInterfaces(), bindInterface)
    return openSsdpSocketPair(
        openNotify = { openAppleNotifySocket(interfaces) },
        openSearch = { openAppleSearchSocket(interfaces) },
    )
}
