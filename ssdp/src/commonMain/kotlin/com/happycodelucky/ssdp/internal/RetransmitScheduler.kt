/*
 * ssdp-kmp — M-SEARCH retransmit cadence (ported from swift-ssdp's
 * SSDPDiscovery retransmitter, lines 95–111).
 *
 * UDP is unreliable, especially over Wi-Fi — the user's core motivation. Per
 * the UPnP Device Architecture, an M-SEARCH should be re-sent on a stepped
 * cadence to fill in for lost packets while the same receive socket keeps
 * draining replies. This is that cadence, as a `delay`-driven coroutine so it
 * runs under `runTest` virtual time with no wall-clock reads (LESSONS N-011).
 */
package com.happycodelucky.ssdp.internal

import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Computes the stepped retransmit cadence and drives repeat sends.
 *
 * Cadence, keyed on elapsed time since the first send (matching swift-ssdp):
 *   - elapsed < 5s   → every 1s
 *   - elapsed < 10s  → every 3s
 *   - elapsed < 60s  → every 10s
 *   - thereafter     → every 60s
 *
 * The first M-SEARCH is sent by the caller *before* starting this loop; this
 * scheduler only handles the repeats.
 */
internal object RetransmitScheduler {
    /** The delay before the *next* retransmit, given how long the search has run. */
    fun nextDelay(elapsed: Duration): Duration =
        when {
            elapsed < 5.seconds -> 1.seconds
            elapsed < 10.seconds -> 3.seconds
            elapsed < 60.seconds -> 10.seconds
            else -> 60.seconds
        }

    /**
     * Loop forever (until the coroutine is cancelled), invoking [retransmit]
     * on the stepped cadence. [elapsedSince] returns the time since the search
     * began; inject it so tests can drive it from virtual time rather than a
     * wall clock (LESSONS N-011). Cancellation propagates cleanly through
     * [delay] and is rethrown.
     */
    suspend fun run(
        elapsedSince: () -> Duration,
        retransmit: suspend () -> Unit,
    ) {
        while (true) {
            val wait = nextDelay(elapsedSince())
            delay(wait)
            // Cooperative cancellation: bail before doing more work if the
            // surrounding scope was cancelled while we slept.
            if (!coroutineContext.isActiveCompat()) break
            try {
                retransmit()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // A single failed send must not kill the retransmit loop — the
                // next round may succeed once Wi-Fi recovers. Swallow and
                // continue; the transport surfaces hard failures elsewhere.
            }
        }
    }

    private fun kotlin.coroutines.CoroutineContext.isActiveCompat(): Boolean {
        val job = this[kotlinx.coroutines.Job]
        return job?.isActive ?: true
    }
}
