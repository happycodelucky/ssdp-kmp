/*
 * ssdp-kmp — Android SsdpClient factories.
 *
 * Two entry points:
 *   - SsdpClient(context, bindInterface) — RECOMMENDED. Threads a Context so the
 *     transport can hold a WifiManager.MulticastLock; without it Android drops
 *     inbound multicast and discovery hears nothing.
 *   - SsdpClient(bindInterface) — the commonMain expect actual, Context-less.
 *     Provided for API symmetry but unreliable on Android; prefer the Context
 *     overload.
 */
package com.happycodelucky.ssdp

import android.content.Context
import com.happycodelucky.reachable.Reachability
import com.happycodelucky.ssdp.internal.AndroidMulticastSocket
import com.happycodelucky.ssdp.internal.SsdpClientImpl
import com.happycodelucky.ssdp.internal.newClientScope
import com.happycodelucky.ssdp.internal.openMulticastSocket
import com.happycodelucky.ssdp.internal.reachableTransportTags
import kotlin.time.Clock
import kotlin.time.TimeSource

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
