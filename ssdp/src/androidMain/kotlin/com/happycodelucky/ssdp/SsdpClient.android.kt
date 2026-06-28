/*
 * ssdp-kmp — Android SsdpClient factories.
 *
 * Three entry points:
 *   - SsdpClient(context, bindInterface) — RECOMMENDED on a physical device.
 *     Threads a Context so the transport can hold a WifiManager.MulticastLock;
 *     without it Android drops inbound multicast and discovery hears nothing.
 *   - SsdpClient(bindInterface) — the commonMain expect actual, Context-less.
 *     Provided for API symmetry but unreliable on Android; prefer the Context
 *     overload.
 *   - SsdpClient.bridged(host, port) — for ANDROID EMULATORS, which can't receive
 *     inbound UDP multicast at all. Tunnels SSDP over TCP to a host-side bridge
 *     daemon (run `mise run app:bridge` on the host). See `bridged` below.
 */
package com.happycodelucky.ssdp

import android.content.Context
import com.happycodelucky.reachable.Reachability
import com.happycodelucky.ssdp.internal.AndroidMulticastSocket
import com.happycodelucky.ssdp.internal.SsdpClientImpl
import com.happycodelucky.ssdp.internal.bridge.BridgeMulticastSocket
import com.happycodelucky.ssdp.internal.newClientScope
import com.happycodelucky.ssdp.internal.openMulticastSocket
import com.happycodelucky.ssdp.internal.reachableTransportTags
import kotlin.time.Clock
import kotlin.time.TimeSource

/**
 * Emulator's view of the host loopback. The Android emulator NATs the host's
 * `127.0.0.1` to this well-known alias.
 */
public const val EMULATOR_HOST_LOOPBACK: String = "10.0.2.2"

/**
 * Create an [SsdpClient] on Android, holding a multicast lock for reliable
 * inbound discovery.
 *
 * @param context any Context (the application Context is used internally) — the
 *   `WifiManager.MulticastLock` source.
 * @param bindInterface optional interface name/address hint; `null` lets the OS
 *   pick the default route.
 * @throws SsdpError if the multicast group cannot be joined.
 */
@Throws(SsdpError::class)
public fun SsdpClient(
    context: Context,
    bindInterface: String? = null,
): SsdpClient =
    SsdpClientImpl(
        socketFactory = { AndroidMulticastSocket(bindInterface, context.applicationContext) },
        parentScope = newClientScope(),
        clock = Clock.System,
        timeSource = TimeSource.Monotonic,
        // reachable's ConnectivityManager-backed singleton (attached via
        // androidx.startup); transport changes drive the registry reset.
        networkTransportTags = reachableTransportTags(Reachability.shared),
    )

@Throws(SsdpError::class)
public actual fun SsdpClient(bindInterface: String?): SsdpClient =
    SsdpClientImpl(
        socketFactory = { openMulticastSocket(bindInterface) },
        parentScope = newClientScope(),
        clock = Clock.System,
        timeSource = TimeSource.Monotonic,
        networkTransportTags = reachableTransportTags(Reachability.shared),
    )

/**
 * Create an [SsdpClient] that tunnels SSDP over TCP to a host-side bridge daemon,
 * for use on an **Android emulator**.
 *
 * Emulators sit behind a user-mode NAT and never receive inbound UDP multicast,
 * so normal discovery hears nothing. This client connects to a bridge daemon
 * running on the host machine (start it with `mise run app:bridge`), which does
 * the real multicast on the host LAN and relays replies/NOTIFY back. The returned
 * client is otherwise identical to a normal one — the registry, retransmit, and
 * `search()`/`description()` semantics are unchanged; only the transport differs.
 *
 * Most callers should prefer [Ssdp.createBridgeAwareClient], whose `useBridge`
 * defaults to [isSsdpBridgeNeeded] — so the common case is zero-arg:
 * ```
 * val client = Ssdp.createBridgeAwareClient()
 * ```
 * This `bridged()` factory is the lower-level building block it delegates to.
 *
 * No [Context] is needed (there is no multicast, hence no `MulticastLock`).
 * The per-network registry reset is disabled — the emulator's NAT network never
 * changes in a way that should clear the host LAN's registry.
 *
 * @param host the daemon's address from inside the emulator. Defaults to
 *   [EMULATOR_HOST_LOOPBACK] (`10.0.2.2`), the emulator alias for the host
 *   loopback.
 * @param port the daemon's TCP port (default `1901`, matching the daemon's
 *   `DEFAULT_BRIDGE_PORT`).
 */
@Suppress("UnusedReceiverParameter")
public fun SsdpClient.Companion.bridged(
    host: String = EMULATOR_HOST_LOOPBACK,
    port: Int = 1901,
): SsdpClient =
    SsdpClientImpl(
        socketFactory = { BridgeMulticastSocket(host = host, port = port) },
        parentScope = newClientScope(),
        clock = Clock.System,
        timeSource = TimeSource.Monotonic,
        // Emulator NAT changes are meaningless to LAN-scoped discovery; the host's
        // real network is what matters, and the daemon owns that side.
        networkTransportTags = null,
    )
