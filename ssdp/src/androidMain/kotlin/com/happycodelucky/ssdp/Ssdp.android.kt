/*
 * ssdp-kmp — Android `Ssdp` factory actual.
 *
 * `createClient()` builds a normal multicast client using the application Context
 * captured by SsdpInitializer (androidx.startup) — no Context argument needed. It
 * warns (Kermit) when the device looks like an emulator, where inbound UDP
 * multicast is NAT-dropped and discovery would silently hear nothing.
 *
 * `createBridgeAwareClient(useBridge)` is the Android-only extra: when `useBridge`
 * is true it tunnels discovery through the host bridge daemon (for emulators);
 * when false it behaves like `createClient()` (and still warns on a likely
 * emulator). `useBridge` defaults to `isSsdpBridgeNeeded()`, so the common case
 * is zero-arg:
 *
 *   val client = Ssdp.createBridgeAwareClient()
 */
package com.happycodelucky.ssdp

import com.happycodelucky.reachable.Reachability
import com.happycodelucky.ssdp.internal.AndroidMulticastSocket
import com.happycodelucky.ssdp.internal.SsdpClientImpl
import com.happycodelucky.ssdp.internal.androidApplicationContext
import com.happycodelucky.ssdp.internal.bridge.BridgeMulticastSocket
import com.happycodelucky.ssdp.internal.newClientScope
import com.happycodelucky.ssdp.internal.reachableTransportTags
import com.happycodelucky.ssdp.internal.ssdpLog
import kotlin.time.Clock
import kotlin.time.TimeSource

private const val EMULATOR_WARNING =
    "You might be running on an Android emulator. Incoming UDP packets are dropped due to NAT " +
        "configuration. Consider using a bridge (Ssdp.createBridgeAwareClient(useBridge = true) " +
        "or SsdpClient.bridged()) with the host bridge daemon (`mise run app:bridge`)."

public object Ssdp {
    /**
     * Create a normal multicast [SsdpClient] using the Context captured at
     * startup by [SsdpInitializer]. Logs a warning if the device looks like an
     * emulator (discovery will hear nothing — use [createBridgeAwareClient]
     * there).
     *
     * @param bindInterface optional local interface/address hint; `null` lets the
     *   OS pick the default route.
     * @throws SsdpError if the multicast group cannot be joined.
     */
    @Throws(SsdpError::class)
    public fun createClient(bindInterface: String? = null): SsdpClient {
        warnIfBridgeNeeded()
        return multicastClient(bindInterface)
    }

    /**
     * Create an [SsdpClient], routing discovery through the host bridge daemon
     * when [useBridge] is true (required on Android emulators, which NAT inbound
     * UDP multicast away).
     *
     * [useBridge] defaults to [isSsdpBridgeNeeded], so the zero-arg
     * `createBridgeAwareClient()` does the right thing automatically — bridge on
     * an emulator, normal multicast on a physical device. Pass it explicitly to
     * override (e.g. `useBridge = false` to force multicast; it then warns if the
     * device still looks like an emulator).
     *
     * @param useBridge true to tunnel over TCP to the host daemon; false for
     *   normal multicast. Defaults to [isSsdpBridgeNeeded].
     * @param host the daemon's address from inside the emulator (default
     *   [EMULATOR_HOST_LOOPBACK], `10.0.2.2`).
     * @param port the daemon's TCP port (default `1901`).
     * @throws SsdpError if the multicast group cannot be joined (multicast path).
     */
    @Throws(SsdpError::class)
    public fun createBridgeAwareClient(
        useBridge: Boolean = isSsdpBridgeNeeded(),
        host: String = EMULATOR_HOST_LOOPBACK,
        port: Int = 1901,
    ): SsdpClient =
        if (useBridge) {
            SsdpClientImpl(
                socketFactory = { BridgeMulticastSocket(host = host, port = port) },
                parentScope = newClientScope(),
                clock = Clock.System,
                timeSource = TimeSource.Monotonic,
                // Emulator NAT changes are meaningless to LAN-scoped discovery.
                networkTransportTags = null,
            )
        } else {
            createClient()
        }

    private fun multicastClient(bindInterface: String?): SsdpClient =
        SsdpClientImpl(
            socketFactory = { AndroidMulticastSocket(bindInterface, androidApplicationContext) },
            parentScope = newClientScope(),
            clock = Clock.System,
            timeSource = TimeSource.Monotonic,
            networkTransportTags = reachableTransportTags(Reachability.shared),
        )

    private fun warnIfBridgeNeeded() {
        if (isSsdpBridgeNeeded()) ssdpLog.w { EMULATOR_WARNING }
    }
}
