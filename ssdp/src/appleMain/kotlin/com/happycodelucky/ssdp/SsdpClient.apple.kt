/*
 * ssdp-kmp — Apple (iOS + macOS) SsdpClient factory.
 *
 * Self-contained: no Context or platform handle needed (unlike Android). Opens
 * the POSIX multicast socket and wires the shared SsdpClientImpl. From Swift via
 * SKIE this reads as `SsdpClient()` / `SsdpClient(bindInterface:)`.
 */
package com.happycodelucky.ssdp

import com.happycodelucky.reachable.Reachability
import com.happycodelucky.ssdp.internal.SsdpClientImpl
import com.happycodelucky.ssdp.internal.newClientScope
import com.happycodelucky.ssdp.internal.openMulticastSocket
import com.happycodelucky.ssdp.internal.reachableTransportTags
import kotlin.time.Clock
import kotlin.time.TimeSource

@Throws(SsdpError::class)
public actual fun SsdpClient(bindInterface: String?): SsdpClient =
    SsdpClientImpl(
        socketFactory = { openMulticastSocket(bindInterface) },
        parentScope = newClientScope(),
        clock = Clock.System,
        timeSource = TimeSource.Monotonic,
        // reachable's nw_path_monitor-backed singleton; transport changes drive
        // the registry reset (plan decision 4).
        networkTransportTags = reachableTransportTags(Reachability.shared),
    )
