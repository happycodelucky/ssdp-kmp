/*
 * ssdp-kmp — adapter from reachable's Reachability to the transport-tag stream
 * the NetworkMonitor consumes (plan decision 4).
 *
 * Lives in commonMain because reachable is a commonMain dependency available on
 * every target (reachable 0.14.0+ — D-003). Keeping the mapping here (rather than
 * in each platform factory) means the transport→tag translation is single-sourced
 * and the factories just pass `reachableTransportTags(Reachability.shared)`.
 */
package com.happycodelucky.ssdp.internal

import com.happycodelucky.reachable.Reachability
import com.happycodelucky.reachable.Transport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Project a [Reachability]'s status into a [Flow] of coarse transport tags
 * (the enum name). A transport flip (Wi-Fi → cellular, or losing the path)
 * changes the tag, which the [NetworkMonitor] combines with the local subnet to
 * decide whether to reset the registry.
 */
internal fun reachableTransportTags(reachability: Reachability): Flow<String> = reachability.status.map { status -> status.transport.tag() }

private fun Transport.tag(): String =
    when (this) {
        Transport.Wifi -> "Wifi"
        Transport.Cellular -> "Cellular"
        Transport.Ethernet -> "Ethernet"
        Transport.Other -> "Other"
        Transport.None -> "None"
    }
