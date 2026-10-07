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
 * gate, but it's held for the transport's whole life anyway.
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
 */
internal class AndroidUdpSocket(private val socket: JdkMulticastSocket, threadName: String, private val onClose: () -> Unit = {}) :
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
 * Open the Android SSDP transport: the NOTIFY socket (multicast lock, bind 1900,
 * join) and the ephemeral-port M-SEARCH socket. [context] supplies the
 * `WifiManager.MulticastLock`; `null` opens without one.
 *
 * @throws SsdpError.MulticastJoinFailed if the NOTIFY socket can't join.
 * @throws SsdpError.TransportFailed if the M-SEARCH socket can't be opened.
 */
internal fun openAndroidMulticastSocket(bindInterface: String?, context: Context?): MulticastSocket =
    openSsdpSocketPair(
        openNotify = { openAndroidNotifySocket(bindInterface, context) },
        openSearch = {
            val socket =
                try {
                    JdkMulticastSocket(EPHEMERAL_PORT)
                } catch (e: Exception) {
                    throw SsdpError.TransportFailed(details = e.message ?: e.toString(), cause = e)
                }
            // Not joined: it only ever receives the unicast replies to its port.
            // Outgoing interface and TTL stay at the OS defaults, as they were
            // when M-SEARCH went out on the NOTIFY socket.
            AndroidUdpSocket(socket, threadName = "ssdp-search-recv")
        },
    )

private fun openAndroidNotifySocket(bindInterface: String?, context: Context?): AndroidUdpSocket {
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

    val networkInterface = selectInterface(bindInterface)
    val groupAddress = InetSocketAddress(InetAddress.getByName(SSDP_GROUP), SSDP_PORT)
    val socket =
        try {
            JdkMulticastSocket(SSDP_PORT).apply {
                reuseAddress = true
                joinGroup(groupAddress, networkInterface)
            }
        } catch (e: Exception) {
            releaseLock()
            throw SsdpError.MulticastJoinFailed(details = e.message ?: e.toString(), cause = e)
        }
    return AndroidUdpSocket(
        socket,
        threadName = "ssdp-notify-recv",
        onClose = {
            runCatching { socket.leaveGroup(groupAddress, networkInterface) }
            releaseLock()
        },
    )
}

private fun selectInterface(bindInterface: String?): NetworkInterface? {
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

// The Context-less expect actual. The factories don't use it (they open through
// AndroidTransport, which also warns when no Context was captured), but it takes
// the multicast lock from the startup-captured Context all the same.
internal actual fun openMulticastSocket(bindInterface: String?): MulticastSocket =
    openAndroidMulticastSocket(bindInterface, context = capturedApplicationContext)
