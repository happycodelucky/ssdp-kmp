/*
 * ssdp-kmp — bridges reachable's network-change signal to the registry reset
 * (plan decision 4).
 *
 * Subscribes to a Flow<String> of transport tags (sourced from reachable's
 * Reachability.status by the platform factory) and recomputes a NetworkKey on
 * each change. When the key actually changes — a different transport OR a
 * different IPv4 subnet — it invokes [onChange]. The first observed key seeds
 * the baseline and does NOT fire a reset (there's nothing to clear yet).
 *
 * Pure orchestration: the reachable dependency and the subnet probe are passed
 * in, so this is fully testable in commonTest without reachable or a real NIC.
 */
package com.happycodelucky.ssdp.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

internal class NetworkMonitor(
    private val scope: CoroutineScope,
    private val transportTags: Flow<String>,
    private val subnetProbe: () -> String?,
    private val onChange: suspend () -> Unit,
) {
    private var lastKey: NetworkKey? = null

    /** Start observing. Cancelled when [scope] is cancelled (on client close). */
    fun start() {
        scope.launch {
            // Deliberately NOT distinctUntilChanged on the tag: reachable re-emits
            // status on a Wi-Fi reconnect even when the transport enum is
            // unchanged, and that reconnect may have landed us on a different LAN
            // (new subnet). We re-probe the subnet on every emission and dedup on
            // the full NetworkKey (transport + subnet) instead, so a same-transport
            // subnet change is still caught.
            transportTags.collect { tag ->
                val key = NetworkKey(transportTag = tag, subnet = subnetProbe())
                val previous = lastKey
                if (previous == null) {
                    // Seed silently on the first observation — nothing to clear yet.
                    lastKey = key
                    return@collect
                }
                if (previous != key) {
                    lastKey = key
                    onChange()
                }
            }
        }
    }
}
