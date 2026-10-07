/*
 * ssdp-kmp — Android SsdpClient factories.
 *
 * Four entry points, all type-named:
 *   - SsdpClient(bindInterface) — the commonMain expect actual. Holds a
 *     WifiManager.MulticastLock taken from the application Context SsdpInitializer
 *     captured at startup, so no Context argument is needed.
 *   - SsdpClient(context, bindInterface) — the same client with an explicit
 *     Context, for apps that disable androidx.startup's InitializationProvider.
 *   - SsdpClient.bridgeAware(useBridge, host, port) — bridges on an emulator,
 *     multicast elsewhere; `useBridge` defaults to isSsdpBridgeNeeded().
 *   - SsdpClient.bridged(host, port) — always tunnels SSDP over TCP to a
 *     host-side bridge daemon (run `mise run app:bridge` on the host).
 *
 * The factories only choose an AndroidTransport; androidSsdpClient opens it.
 */
package com.happycodelucky.ssdp

import android.content.Context
import com.happycodelucky.ssdp.internal.AndroidTransport
import com.happycodelucky.ssdp.internal.androidSsdpClient
import com.happycodelucky.ssdp.internal.bridgeAwareTransport
import com.happycodelucky.ssdp.internal.contextFreeMulticast

/**
 * Emulator's view of the host loopback. The Android emulator NATs the host's
 * `127.0.0.1` to this well-known alias.
 */
public const val EMULATOR_HOST_LOOPBACK: String = "10.0.2.2"

/** The bridge daemon's default TCP port, matching the daemon's `DEFAULT_BRIDGE_PORT`. */
private const val DEFAULT_BRIDGE_PORT = 1901

/**
 * Create an [SsdpClient] on Android from an explicit [context], holding a
 * `WifiManager.MulticastLock` for reliable inbound discovery.
 *
 * `SsdpClient()` builds the same client from the application Context the library
 * captures at startup; use this overload when your app disables androidx.startup's
 * `InitializationProvider`. Like `SsdpClient()`, it logs a warning when the device
 * looks like an emulator, where discovery hears nothing (use [bridgeAware] there).
 *
 * @param context any Context (the application Context is used internally) — the
 *   `WifiManager.MulticastLock` source.
 * @param bindInterface limits discovery to one interface, by name or IPv4
 *   address; `null` uses every multicast-capable IPv4 interface (see the common
 *   `SsdpClient(bindInterface)`).
 * @throws SsdpError if the multicast group cannot be joined.
 */
@Throws(SsdpError::class)
public fun SsdpClient(context: Context, bindInterface: String? = null): SsdpClient =
    androidSsdpClient(AndroidTransport.Multicast(bindInterface = bindInterface, lockContext = context.applicationContext))

/**
 * Android: holds a `WifiManager.MulticastLock` taken from the application Context
 * the library captures at startup (`SsdpInitializer`, via androidx.startup), and
 * logs a warning when the device looks like an emulator (use [bridgeAware] there).
 * If startup capture is disabled, the client opens without a lock — inbound
 * multicast may then be dropped — and logs a warning naming `SsdpClient(context)`.
 */
@Throws(SsdpError::class)
public actual fun SsdpClient(bindInterface: String?): SsdpClient = androidSsdpClient(contextFreeMulticast(bindInterface))

/**
 * Create an [SsdpClient] that bridges on an Android emulator and uses normal
 * multicast everywhere else.
 *
 * Emulators NAT inbound UDP multicast away, so there the client tunnels discovery
 * over TCP to the host bridge daemon ([bridged]). [useBridge] defaults to
 * [isSsdpBridgeNeeded], so the zero-arg call does the right thing on its own:
 * ```
 * val client = SsdpClient.bridgeAware()
 * ```
 * When [useBridge] is false the client is exactly `SsdpClient()` — multicast lock
 * from the startup-captured Context, and a warning if the device still looks like
 * an emulator.
 *
 * @param useBridge true to tunnel over TCP to the host daemon; false for normal
 *   multicast. Defaults to [isSsdpBridgeNeeded].
 * @param host the daemon's address from inside the emulator (default
 *   [EMULATOR_HOST_LOOPBACK], `10.0.2.2`).
 * @param port the daemon's TCP port (default `1901`).
 * @throws SsdpError if the multicast group cannot be joined (multicast path).
 */
@Suppress("UnusedReceiverParameter")
@Throws(SsdpError::class)
public fun SsdpClient.Companion.bridgeAware(
    useBridge: Boolean = isSsdpBridgeNeeded(),
    host: String = EMULATOR_HOST_LOOPBACK,
    port: Int = DEFAULT_BRIDGE_PORT,
): SsdpClient = androidSsdpClient(bridgeAwareTransport(useBridge = useBridge, host = host, port = port))

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
 * Most callers should prefer [bridgeAware], which bridges only on an emulator.
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
public fun SsdpClient.Companion.bridged(host: String = EMULATOR_HOST_LOOPBACK, port: Int = DEFAULT_BRIDGE_PORT): SsdpClient =
    androidSsdpClient(AndroidTransport.Bridge(host = host, port = port))
