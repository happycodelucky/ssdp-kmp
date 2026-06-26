/*
 * ssdp-kmp — JVM multicast socket (java.net.MulticastSocket).
 *
 * Serves desktop / server / Linux / Windows. The receive loop runs on a
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
import kotlin.concurrent.thread
import java.net.MulticastSocket as JdkMulticastSocket

private const val SSDP_GROUP = "239.255.255.250"
private const val SSDP_PORT = 1900
private const val MAX_DATAGRAM = 65_507

internal class JvmMulticastSocket(
    bindInterface: String?,
) : MulticastSocket {
    private val group = InetAddress.getByName(SSDP_GROUP)
    private val socket: JdkMulticastSocket
    private val networkInterface: NetworkInterface?

    private val _incoming =
        MutableSharedFlow<Datagram>(
            replay = 0,
            extraBufferCapacity = 256,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    override val incoming: Flow<Datagram> = _incoming.asSharedFlow()

    @Volatile
    private var running = true
    private val receiveThread: Thread

    init {
        try {
            networkInterface = selectInterface(bindInterface)
            socket =
                JdkMulticastSocket(SSDP_PORT).apply {
                    reuseAddress = true
                    // Join on a specific interface when we found a multicast-capable
                    // one; otherwise let the OS pick the default route.
                    val groupAddress = InetSocketAddress(group, SSDP_PORT)
                    if (networkInterface != null) {
                        joinGroup(groupAddress, networkInterface)
                    } else {
                        joinGroup(groupAddress, null)
                    }
                }
        } catch (e: Exception) {
            throw SsdpError.MulticastJoinFailed(details = e.message ?: e.toString(), cause = e)
        }

        receiveThread =
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
                        // socket.receive throws when the socket is closed during
                        // teardown — exit the loop cleanly. Transient errors also
                        // land here; the loop continues while running.
                        if (!running) break
                    }
                }
            }
    }

    override suspend fun send(bytes: ByteArray) {
        try {
            val packet = DatagramPacket(bytes, bytes.size, group, SSDP_PORT)
            socket.send(packet)
        } catch (e: Exception) {
            throw SsdpError.TransportFailed(details = e.message ?: e.toString(), cause = e)
        }
    }

    override fun close() {
        running = false
        runCatching {
            val groupAddress = InetSocketAddress(group, SSDP_PORT)
            socket.leaveGroup(groupAddress, networkInterface)
        }
        runCatching { socket.close() }
    }

    private fun selectInterface(bindInterface: String?): NetworkInterface? {
        // Explicit hint by name or address.
        if (bindInterface != null) {
            runCatching { NetworkInterface.getByName(bindInterface) }.getOrNull()?.let { return it }
            runCatching { NetworkInterface.getByInetAddress(InetAddress.getByName(bindInterface)) }
                .getOrNull()
                ?.let { return it }
        }
        // Otherwise pick the first up, non-loopback, multicast-capable interface.
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList().firstOrNull {
                it.isUp && !it.isLoopback && it.supportsMulticast()
            }
        }.getOrNull()
    }
}

internal actual fun openMulticastSocket(bindInterface: String?): MulticastSocket = JvmMulticastSocket(bindInterface)
