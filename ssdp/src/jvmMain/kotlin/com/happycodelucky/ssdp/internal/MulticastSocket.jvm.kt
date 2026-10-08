/*
 * ssdp-kmp — JVM SSDP transport (java.net.MulticastSocket).
 *
 * Serves desktop / server / Linux / Windows. The transport is an SsdpSocketPair
 * (see SsdpSocketPair.kt): a NOTIFY socket bound to 1900 and joined to the
 * group, plus an M-SEARCH socket on an ephemeral port whose unicast replies no
 * other 1900 socket on the host can steal. Each socket's receive loop runs on a
 * dedicated daemon thread (blocking recv), bridging datagrams into a Flow. The
 * NOTIFY socket joins the group, and every M-SEARCH goes out, on each interface
 * selectMulticastInterfaces picks (LocalInterface.kt). The Android actual is
 * nearly identical but additionally holds a WifiManager MulticastLock — see
 * androidMain.
 */
package com.happycodelucky.ssdp.internal

import com.happycodelucky.ssdp.SsdpError
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import java.net.MulticastSocket as JdkMulticastSocket

private const val SSDP_GROUP = "239.255.255.250"
private const val SSDP_PORT = 1900
private const val MAX_DATAGRAM = 65_507

/** Lets the OS pick a free port: the M-SEARCH socket's replies come back to it alone. */
private const val EPHEMERAL_PORT = 0

/**
 * One UDP socket of the JVM transport: a daemon receive thread feeding
 * [incoming], [send] to the SSDP group, and [close]. Both halves of the
 * [SsdpSocketPair] are one of these; only how the socket is bound and joined
 * differs ([openJvmNotifySocket], [openJvmSearchSocket]).
 *
 * @param onClose extra teardown run before the socket closes (the NOTIFY socket
 *   leaves the group here).
 * @param sendInterfaces the interfaces [send] goes out on, one datagram each;
 *   empty sends once on the OS default route.
 */
internal class JvmUdpSocket(
    private val socket: JdkMulticastSocket,
    threadName: String,
    private val onClose: () -> Unit = {},
    private val sendInterfaces: List<NetworkInterface> = emptyList(),
) : MulticastSocket {
    private val group = InetAddress.getByName(SSDP_GROUP)

    // The outgoing multicast interface is a socket option, so choosing it and
    // sending must not interleave with another retransmit loop's send.
    private val sendLock = SynchronizedObject()

    private val _incoming =
        MutableSharedFlow<Datagram>(
            replay = 0,
            extraBufferCapacity = 256,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    override val incoming: Flow<Datagram> = _incoming.asSharedFlow()

    private val running = AtomicBoolean(true)

    /** The local UDP port the socket is bound to; tests address unicast replies to it. */
    internal val localPort: Int get() = socket.localPort

    /** The blocking receive loop; exits once [close] closes the socket. Exposed for tests. */
    internal val receiveThread: Thread =
        thread(name = threadName, isDaemon = true) {
            val buffer = ByteArray(MAX_DATAGRAM)
            while (running.get()) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                    val source = "${packet.address?.hostAddress}:${packet.port}"
                    _incoming.tryEmit(Datagram(text = text, source = source))
                } catch (_: Exception) {
                    // socket.receive ignores thread interrupts and coroutine
                    // cancellation; it only throws once close() closes the socket,
                    // which is how teardown exits the loop. Transient errors also
                    // land here; the loop continues while running.
                    if (!running.get()) break
                }
            }
        }

    override suspend fun send(bytes: ByteArray) {
        val packet = DatagramPacket(bytes, bytes.size, group, SSDP_PORT)
        synchronized(sendLock) {
            onEachInterface(
                sendInterfaces,
                action = { networkInterface ->
                    if (networkInterface != null) socket.networkInterface = networkInterface
                    socket.send(packet)
                },
                wrap = { details, cause -> SsdpError.TransportFailed(details = details, cause = cause) },
            )
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        runCatching { onClose() }
        // Closing the socket is what unblocks the receive thread.
        runCatching { socket.close() }
    }
}

/**
 * Open the NOTIFY socket: bound to 1900 with address reuse (other SSDP apps on
 * the host share the port) and joined to the group on each of [interfaces], or
 * on the OS default route when there are none. One socket can hold a membership
 * per interface; a join that fails on one interface is skipped.
 *
 * @throws SsdpError.MulticastJoinFailed if the bind fails or no join succeeds.
 */
internal fun openJvmNotifySocket(interfaces: List<NetworkInterface>): JvmUdpSocket {
    val groupAddress = InetSocketAddress(InetAddress.getByName(SSDP_GROUP), SSDP_PORT)
    val socket =
        try {
            JdkMulticastSocket(SSDP_PORT).apply { reuseAddress = true }
        } catch (e: Exception) {
            throw SsdpError.MulticastJoinFailed(details = e.message ?: e.toString(), cause = e)
        }
    val joined = mutableListOf<NetworkInterface?>()
    runCatching {
        onEachInterface(
            interfaces,
            action = { networkInterface ->
                socket.joinGroup(groupAddress, networkInterface)
                joined += networkInterface
            },
            wrap = { details, cause -> SsdpError.MulticastJoinFailed(details = details, cause = cause) },
        )
    }.onFailure {
        socket.close()
        throw it
    }
    return JvmUdpSocket(
        socket,
        threadName = "ssdp-notify-recv",
        onClose = { joined.forEach { runCatching { socket.leaveGroup(groupAddress, it) } } },
    )
}

/**
 * Open the M-SEARCH socket: bound to an ephemeral port on the wildcard address
 * and never joined, so it receives only the unicast replies addressed to it.
 * Each M-SEARCH goes out once per interface in [interfaces] (once on the OS
 * default route when empty); the replies all come back to the one port. TTL is
 * left at the OS default.
 *
 * @throws SsdpError.TransportFailed if the socket can't be opened.
 */
internal fun openJvmSearchSocket(interfaces: List<NetworkInterface> = emptyList()): JvmUdpSocket {
    val socket =
        try {
            JdkMulticastSocket(EPHEMERAL_PORT)
        } catch (e: Exception) {
            throw SsdpError.TransportFailed(details = e.message ?: e.toString(), cause = e)
        }
    return JvmUdpSocket(socket, threadName = "ssdp-search-recv", sendInterfaces = interfaces)
}

/** This interface as a [LocalInterface], or `null` when it has no IPv4 address. */
internal fun NetworkInterface.toLocalInterface(): LocalInterface? =
    runCatching {
        val ipv4 = inetAddresses.toList().filterIsInstance<Inet4Address>().firstOrNull() ?: return null
        LocalInterface(
            name = name,
            ipv4 = ipv4.hostAddress,
            isUp = isUp,
            isLoopback = isLoopback,
            isPointToPoint = isPointToPoint,
            supportsMulticast = supportsMulticast(),
        )
    }.getOrNull()

/**
 * The interfaces to join and search on (see [selectMulticastInterfaces]). An
 * interface that vanishes between listing and lookup is dropped.
 *
 * @throws SsdpError.MulticastJoinFailed if [bindInterface] matches no interface.
 */
internal fun selectJvmInterfaces(bindInterface: String?): List<NetworkInterface> {
    val byName =
        runCatching { NetworkInterface.getNetworkInterfaces().toList() }
            .getOrDefault(emptyList())
            .associateBy { it.name }
    val candidates = byName.values.mapNotNull { it.toLocalInterface() }
    return selectMulticastInterfaces(candidates, bindInterface).mapNotNull { byName[it.name] }
}

internal actual fun openMulticastSocket(bindInterface: String?): MulticastSocket {
    // Chosen once, so the NOTIFY joins and the M-SEARCH sends cover the same set.
    val interfaces = selectJvmInterfaces(bindInterface)
    return openSsdpSocketPair(
        openNotify = { openJvmNotifySocket(interfaces) },
        openSearch = { openJvmSearchSocket(interfaces) },
    )
}
