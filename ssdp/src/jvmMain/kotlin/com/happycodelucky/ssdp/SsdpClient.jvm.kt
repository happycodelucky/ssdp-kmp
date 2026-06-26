/*
 * ssdp-kmp — JVM SsdpClient factory.
 *
 * Opens a java.net.MulticastSocket-backed transport and wires the shared
 * SsdpClientImpl. The reachable-driven network-change reset (task #5) is wired
 * in commonMain-shared helper code; the JVM uses reachable's jvm slice
 * (reachable 0.14.0+, the floor that ships reachable-jvm).
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
        // reachable.shared is the JVM-slice singleton (reachable 0.14.0+ ships a
        // jvm artifact — D-003); its transport changes drive the registry reset.
        networkTransportTags = reachableTransportTags(Reachability.shared),
    )
