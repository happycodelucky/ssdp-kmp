/*
 * ssdp-kmp — a Clock driven by runTest virtual time.
 *
 * The registry stamps firstSeen/lastSeen/expiresAt from an injected Clock and
 * schedules expiry via `delay`. To keep the two consistent under `runTest`, this
 * clock reads the test scheduler's virtual `currentTime` (millis since the test
 * started), so advancing virtual time (via delay) advances "now" in lockstep —
 * no wall-clock reads anywhere (LESSONS N-011).
 */
package com.happycodelucky.ssdp.internal

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.time.TimeMark
import kotlin.time.TimeSource

@OptIn(ExperimentalCoroutinesApi::class)
internal class TestClock(
    private val scheduler: TestCoroutineScheduler,
) : Clock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(scheduler.currentTime)
}

/**
 * A monotonic [TimeSource] reading the test scheduler's virtual `currentTime`,
 * so retransmit elapsed-time advances in lockstep with `delay()` under
 * `runTest` (LESSONS N-011). Used in place of the `testTimeSource` extension,
 * which isn't available in the pinned kotlinx-coroutines-test version.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class TestTimeSource(
    private val scheduler: TestCoroutineScheduler,
) : TimeSource {
    override fun markNow(): TimeMark {
        val startMillis = scheduler.currentTime
        return object : TimeMark {
            override fun elapsedNow() = (scheduler.currentTime - startMillis).milliseconds
        }
    }
}
