/*
 * ssdp-kmp — Android multicast socket.
 *
 * Mechanically identical to the JVM socket (java.net.MulticastSocket on a
 * daemon receive thread) with one Android-critical addition: a
 * WifiManager.MulticastLock. Android's Wi-Fi stack drops inbound multicast
 * packets to save power unless an app holds a multicast lock — without it the
 * receive loop would send M-SEARCHes and hear nothing back.
 *
 * The lock requires a Context, so the Android transport is created via the
 * Context-taking SsdpClient(context) factory (see SsdpClient.android.kt). The
 * commonMain expect `openMulticastSocket(bindInterface)` is satisfied here too,
 * but without a Context it cannot take the lock — it falls back to no-lock
 * behavior and logs nothing (discovery may be unreliable). Production callers
 * should use SsdpClient(context).
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
import kotlin.concurrent.thread
import java.net.MulticastSocket as JdkMulticastSocket

private const val SSDP_GROUP = "239.255.255.250"
private const val SSDP_PORT = 1900
private const val MAX_DATAGRAM = 65_507
private const val MULTICAST_LOCK_TAG = "ssdp-kmp"

internal class AndroidMulticastSocket(
    bindInterface: String?,
    context: Context?,
) : MulticastSocket {
    private val group = InetAddress.getByName(SSDP_GROUP)
    private val socket: JdkMulticastSocket
    private val networkInterface: NetworkInterface?
    private val multicastLock: WifiManager.MulticastLock?

    private val _incoming =
        MutableSharedFlow<Datagram>(
            replay = 0,
            extraBufferCapacity = 256,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    override val incoming: Flow<Datagram> = _incoming.asSharedFlow()

    @Volatile
    private var running = true

    init {
        // Acquire the multicast lock before joining — Android won't deliver
        // multicast datagrams to the socket otherwise. Assigned before the try
        // so it is definitely-assigned in the catch's cleanup path.
        multicastLock =
            context?.let { ctx ->
                val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                wifi?.createMulticastLock(MULTICAST_LOCK_TAG)?.apply {
                    setReferenceCounted(true)
                    acquire()
                }
            }

        try {
            networkInterface = selectInterface(bindInterface)
            socket =
                JdkMulticastSocket(SSDP_PORT).apply {
                    reuseAddress = true
                    val groupAddress = InetSocketAddress(group, SSDP_PORT)
                    joinGroup(groupAddress, networkInterface)
                }
        } catch (e: Exception) {
            multicastLock?.let { runCatching { if (it.isHeld) it.release() } }
            throw SsdpError.MulticastJoinFailed(details = e.message ?: e.toString(), cause = e)
        }

        thread(name = "ssdp-multicast-recv", isDaemon = true) {
            val buffer = ByteArray(MAX_DATAGRAM)
            while (running) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                    val source = "${packet.address?.hostAddress}:${packet.port}"
                    _incoming.tryEmit(Datagram(text = text, source = source))
                } catch (_: Exception) {
                    if (!running) break
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
        running = false
        runCatching { socket.leaveGroup(InetSocketAddress(group, SSDP_PORT), networkInterface) }
        runCatching { socket.close() }
        multicastLock?.let { runCatching { if (it.isHeld) it.release() } }
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
}

// The Context-less expect actual: usable, but without the multicast lock Android
// may not deliver inbound datagrams. SsdpClient(context) wires a Context through
// AndroidMulticastSocket directly for reliable discovery.
internal actual fun openMulticastSocket(bindInterface: String?): MulticastSocket = AndroidMulticastSocket(bindInterface, context = null)
