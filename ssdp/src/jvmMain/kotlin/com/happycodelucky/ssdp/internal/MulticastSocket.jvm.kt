/*
 * ssdp-kmp — JVM SSDP transport (java.net.MulticastSocket).
 *
 * Serves desktop / server / Linux / Windows. The transport is an SsdpSocketPair
 * (see SsdpSocketPair.kt): a NOTIFY socket bound to 1900 and joined to the
 * group, plus an M-SEARCH socket on an ephemeral port whose unicast replies no
 * other 1900 socket on the host can steal. Each socket's receive loop runs on a
 * dedicated daemon thread (blocking recv), bridging datagrams into a Flow. The
 * Android actual is nearly identical but additionally holds a WifiManager
 * MulticastLock — see androidMain.
 */
package com.happycodelucky.ssdp.internal

import com.happycodelucky.ssdp.SsdpError
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.net.DatagramPacket
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
 */
internal class JvmUdpSocket(private val socket: JdkMulticastSocket, threadName: String, private val onClose: () -> Unit = {}) :
    MulticastSocket {
    private val group = InetAddress.getByName(SSDP_GROUP)

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
        try {
            socket.send(DatagramPacket(bytes, bytes.size, group, SSDP_PORT))
        } catch (e: Exception) {
            throw SsdpError.TransportFailed(details = e.message ?: e.toString(), cause = e)
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
 * the host share the port) and joined to the group on [networkInterface], or
 * on the OS default route when it is `null`.
 *
 * @throws SsdpError.MulticastJoinFailed if the bind or join fails.
 */
internal fun openJvmNotifySocket(networkInterface: NetworkInterface?): JvmUdpSocket {
    val groupAddress = InetSocketAddress(InetAddress.getByName(SSDP_GROUP), SSDP_PORT)
    val socket =
        try {
            JdkMulticastSocket(SSDP_PORT).apply {
                reuseAddress = true
                joinGroup(groupAddress, networkInterface)
            }
        } catch (e: Exception) {
            throw SsdpError.MulticastJoinFailed(details = e.message ?: e.toString(), cause = e)
        }
    return JvmUdpSocket(socket, threadName = "ssdp-notify-recv", onClose = { socket.leaveGroup(groupAddress, networkInterface) })
}

/**
 * Open the M-SEARCH socket: bound to an ephemeral port on the wildcard address
 * and never joined, so it receives only the unicast replies addressed to it.
 * The outgoing multicast interface and TTL are left at the OS defaults, exactly
 * as they were when M-SEARCH went out on the NOTIFY socket.
 *
 * @throws SsdpError.TransportFailed if the socket can't be opened.
 */
internal fun openJvmSearchSocket(): JvmUdpSocket {
    val socket =
        try {
            JdkMulticastSocket(EPHEMERAL_PORT)
        } catch (e: Exception) {
            throw SsdpError.TransportFailed(details = e.message ?: e.toString(), cause = e)
        }
    return JvmUdpSocket(socket, threadName = "ssdp-search-recv")
}

/**
 * Resolve the interface the NOTIFY socket joins on: [bindInterface] by name or
 * address when given, otherwise the first up, non-loopback, multicast-capable
 * interface, or `null` (the OS default route) when none qualifies.
 */
private fun selectJvmInterface(bindInterface: String?): NetworkInterface? {
    if (bindInterface != null) {
        runCatching { NetworkInterface.getByName(bindInterface) }.getOrNull()?.let { return it }
        runCatching { NetworkInterface.getByInetAddress(InetAddress.getByName(bindInterface)) }
            .getOrNull()
            ?.let { return it }
    }
    return runCatching {
        NetworkInterface.getNetworkInterfaces().toList().firstOrNull {
            it.isUp && !it.isLoopback && it.supportsMulticast()
        }
    }.getOrNull()
}

internal actual fun openMulticastSocket(bindInterface: String?): MulticastSocket =
    openSsdpSocketPair(
        openNotify = { openJvmNotifySocket(selectJvmInterface(bindInterface)) },
        openSearch = ::openJvmSearchSocket,
    )
