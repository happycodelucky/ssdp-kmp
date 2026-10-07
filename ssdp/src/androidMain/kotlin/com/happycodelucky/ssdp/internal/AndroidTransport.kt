/*
 * ssdp-kmp — how an Android client reaches the network.
 *
 * The public Android factories (SsdpClient.android.kt) only *choose* a transport;
 * [androidSsdpClient] opens it. Keeping the choice a plain value is what lets the
 * host tests check which transport, and which multicast-lock Context, each
 * factory picks without opening a real socket (the client opens its socket on
 * construction).
 */
package com.happycodelucky.ssdp.internal

import android.content.Context
import com.happycodelucky.reachable.Reachability
import com.happycodelucky.ssdp.SsdpClient
import com.happycodelucky.ssdp.SsdpError
import com.happycodelucky.ssdp.internal.bridge.BridgeMulticastSocket
import com.happycodelucky.ssdp.isSsdpBridgeNeeded
import kotlin.time.Clock
import kotlin.time.TimeSource

private const val EMULATOR_WARNING =
    "You might be running on an Android emulator. Incoming UDP packets are dropped due to NAT " +
        "configuration. Consider using a bridge (SsdpClient.bridgeAware() or SsdpClient.bridged()) " +
        "with the host bridge daemon (`mise run app:bridge`)."

private const val NO_CONTEXT_WARNING =
    "No application Context was captured at startup (the SsdpInitializer androidx.startup entry is " +
        "disabled), so this client holds no WifiManager.MulticastLock and Android may drop inbound " +
        "multicast. Construct the client with SsdpClient(context) instead."

/** The transport an Android [SsdpClient] opens. */
internal sealed interface AndroidTransport {
    /**
     * Real UDP multicast on the device's LAN.
     *
     * @property bindInterface optional interface name/address hint.
     * @property lockContext the `WifiManager.MulticastLock` source; `null` opens
     *   the sockets without a lock.
     */
    data class Multicast(val bindInterface: String?, val lockContext: Context?) : AndroidTransport

    /** A TCP tunnel to the host bridge daemon, for an emulator (no inbound multicast). */
    data class Bridge(val host: String, val port: Int) : AndroidTransport
}

/**
 * The multicast transport for the Context-free `SsdpClient(bindInterface)`: locked
 * by the Context [SsdpInitializer] captured, or lock-less with a warning naming
 * `SsdpClient(context)` when startup capture was disabled.
 *
 * @param captured the startup-captured Context; a parameter only so tests can
 *   model a disabled initializer.
 */
internal fun contextFreeMulticast(bindInterface: String?, captured: Context? = capturedApplicationContext): AndroidTransport.Multicast {
    if (captured == null) ssdpLog.w { NO_CONTEXT_WARNING }
    return AndroidTransport.Multicast(bindInterface = bindInterface, lockContext = captured)
}

/**
 * The transport for `SsdpClient.bridgeAware(useBridge, host, port)`: the bridge
 * when [useBridge] is true, otherwise exactly what `SsdpClient()` opens.
 *
 * @param captured the startup-captured Context; a parameter only so tests can
 *   model a disabled initializer.
 */
internal fun bridgeAwareTransport(
    useBridge: Boolean,
    host: String,
    port: Int,
    captured: Context? = capturedApplicationContext,
): AndroidTransport =
    if (useBridge) {
        AndroidTransport.Bridge(host = host, port = port)
    } else {
        contextFreeMulticast(bindInterface = null, captured = captured)
    }

/**
 * Build the production client over [transport]. A multicast transport logs a
 * warning on a likely emulator, whose NAT drops inbound multicast whether or not
 * a lock is held.
 *
 * @throws SsdpError if the multicast group cannot be joined.
 */
@Throws(SsdpError::class)
internal fun androidSsdpClient(transport: AndroidTransport): SsdpClient =
    when (transport) {
        is AndroidTransport.Multicast -> {
            if (isSsdpBridgeNeeded()) ssdpLog.w { EMULATOR_WARNING }
            SsdpClientImpl(
                socketFactory = { openAndroidMulticastSocket(transport.bindInterface, transport.lockContext) },
                parentScope = newClientScope(),
                clock = Clock.System,
                timeSource = TimeSource.Monotonic,
                // reachable's ConnectivityManager-backed singleton (attached via
                // androidx.startup); transport changes drive the registry reset.
                networkTransportTags = reachableTransportTags(Reachability.shared),
            )
        }

        is AndroidTransport.Bridge -> {
            SsdpClientImpl(
                socketFactory = { BridgeMulticastSocket(host = transport.host, port = transport.port) },
                parentScope = newClientScope(),
                clock = Clock.System,
                timeSource = TimeSource.Monotonic,
                // Emulator NAT changes are meaningless to LAN-scoped discovery; the
                // host's real network is what matters, and the daemon owns that side.
                networkTransportTags = null,
            )
        }
    }
