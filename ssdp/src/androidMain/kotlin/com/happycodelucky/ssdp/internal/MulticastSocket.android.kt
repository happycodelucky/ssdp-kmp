/*
 * ssdp-kmp — Android SSDP transport.
 *
 * Mechanically identical to the JVM transport — an SsdpSocketPair (see
 * SsdpSocketPair.kt) of a NOTIFY socket bound to 1900 and an ephemeral-port
 * M-SEARCH socket, each java.net.MulticastSocket on a daemon receive thread —
 * with one Android-critical addition: a WifiManager.MulticastLock. Android's
 * Wi-Fi stack drops inbound multicast packets to save power unless an app holds
 * a multicast lock — without it the NOTIFY socket would hear nothing. The lock
 * belongs to the NOTIFY socket's lifetime: acquired before it joins, released
 * when it closes. The M-SEARCH replies are unicast, which the lock doesn't
 * gate, but it's held for the transport's whole life anyway. As on the JVM, the
 * group is joined and every M-SEARCH sent on each interface
 * selectMulticastInterfaces picks (LocalInterface.kt).
 *
 * The lock requires a Context. The public factories choose it (see
 * AndroidTransport.kt): the startup-captured application Context for
 * `SsdpClient()`, the caller's for `SsdpClient(context)`. The commonMain expect
 * `openMulticastSocket(bindInterface)` is satisfied here too, with the captured
 * Context when there is one.
 */
package com.happycodelucky.ssdp.internal

import android.content.Context
import android.net.wifi.WifiManager
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
private const val MULTICAST_LOCK_TAG = "ssdp-kmp"

/** Lets the OS pick a free port: the M-SEARCH socket's replies come back to it alone. */
private const val EPHEMERAL_PORT = 0

/**
 * One UDP socket of the Android transport: a daemon receive thread feeding
 * [incoming], [send] to the SSDP group, and [close]. Both halves of the
 * [SsdpSocketPair] are one of these; only how the socket is bound and joined
 * differs.
 *
 * @param onClose extra teardown run before the socket closes (the NOTIFY socket
 *   leaves the group and releases the multicast lock here).
 * @param sendInterfaces the interfaces [send] goes out on, one datagram each;
 *   empty sends once on the OS default route.
 */
internal class AndroidUdpSocket(
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

    init {
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
                    // socket.receive ignores interrupts and coroutine cancellation;
                    // it only throws once close() closes the socket, which is how
                    // teardown exits the loop.
                    if (!running.get()) break
                }
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
 * Open the Android SSDP transport: the NOTIFY socket (multicast lock, bind 1900,
 * join) and the ephemeral-port M-SEARCH socket. [context] supplies the
 * `WifiManager.MulticastLock`; `null` opens without one.
 *
 * @throws SsdpError.MulticastJoinFailed if the NOTIFY socket can't join.
 * @throws SsdpError.TransportFailed if the M-SEARCH socket can't be opened.
 */
internal fun openAndroidMulticastSocket(bindInterface: String?, context: Context?): MulticastSocket {
    // Chosen once, so the NOTIFY joins and the M-SEARCH sends cover the same set.
    val interfaces = selectAndroidInterfaces(bindInterface)
    return openSsdpSocketPair(
        openNotify = { openAndroidNotifySocket(interfaces, context) },
        openSearch = {
            val socket =
                try {
                    JdkMulticastSocket(EPHEMERAL_PORT)
                } catch (e: Exception) {
                    throw SsdpError.TransportFailed(details = e.message ?: e.toString(), cause = e)
                }
            // Not joined: it only ever receives the unicast replies to its port,
            // whichever interface each M-SEARCH left on. TTL stays at the OS default.
            AndroidUdpSocket(socket, threadName = "ssdp-search-recv", sendInterfaces = interfaces)
        },
    )
}

private fun openAndroidNotifySocket(interfaces: List<NetworkInterface>, context: Context?): AndroidUdpSocket {
    // Acquire the multicast lock before joining — Android won't deliver
    // multicast datagrams to the socket otherwise.
    val multicastLock =
        context?.let { ctx ->
            val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifi?.createMulticastLock(MULTICAST_LOCK_TAG)?.apply {
                setReferenceCounted(true)
                acquire()
            }
        }
    val releaseLock = { multicastLock?.let { runCatching { if (it.isHeld) it.release() } } }

    val groupAddress = InetSocketAddress(InetAddress.getByName(SSDP_GROUP), SSDP_PORT)
    val socket =
        try {
            JdkMulticastSocket(SSDP_PORT).apply { reuseAddress = true }
        } catch (e: Exception) {
            releaseLock()
            throw SsdpError.MulticastJoinFailed(details = e.message ?: e.toString(), cause = e)
        }
    // One membership per interface; a join that fails on one is skipped.
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
        releaseLock()
        throw it
    }
    return AndroidUdpSocket(
        socket,
        threadName = "ssdp-notify-recv",
        onClose = {
            joined.forEach { runCatching { socket.leaveGroup(groupAddress, it) } }
            releaseLock()
        },
    )
}

/** This interface as a [LocalInterface], or `null` when it has no IPv4 address. */
private fun NetworkInterface.toLocalInterface(): LocalInterface? =
    runCatching {
        val ipv4 = inetAddresses.toList().filterIsInstance<Inet4Address>().firstOrNull() ?: return null
        LocalInterface(
            name = name,
            ipv4 = ipv4.hostAddress ?: return null,
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
private fun selectAndroidInterfaces(bindInterface: String?): List<NetworkInterface> {
    val byName =
        runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }
            .getOrDefault(emptyList())
            .associateBy { it.name }
    val candidates = byName.values.mapNotNull { it.toLocalInterface() }
    return selectMulticastInterfaces(candidates, bindInterface).mapNotNull { byName[it.name] }
}

// The Context-less expect actual. The factories don't use it (they open through
// AndroidTransport, which also warns when no Context was captured), but it takes
// the multicast lock from the startup-captured Context all the same.
internal actual fun openMulticastSocket(bindInterface: String?): MulticastSocket =
    openAndroidMulticastSocket(bindInterface, context = capturedApplicationContext)
