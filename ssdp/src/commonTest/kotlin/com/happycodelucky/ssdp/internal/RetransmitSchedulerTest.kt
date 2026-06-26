/*
 * ssdp-kmp — retransmit cadence tests under virtual time.
 *
 * Verifies the 1s/3s/10s/60s step boundaries (nextDelay) and that the run loop
 * fires retransmits on that cadence using a virtual elapsed clock.
 */
package com.happycodelucky.ssdp.internal

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class RetransmitSchedulerTest {
    @Test
    fun nextDelayStepsThroughCadence() {
        assertEquals(1.seconds, RetransmitScheduler.nextDelay(0.seconds))
        assertEquals(1.seconds, RetransmitScheduler.nextDelay(4.seconds))
        assertEquals(3.seconds, RetransmitScheduler.nextDelay(5.seconds))
        assertEquals(3.seconds, RetransmitScheduler.nextDelay(9.seconds))
        assertEquals(10.seconds, RetransmitScheduler.nextDelay(10.seconds))
        assertEquals(10.seconds, RetransmitScheduler.nextDelay(59.seconds))
        assertEquals(60.seconds, RetransmitScheduler.nextDelay(60.seconds))
        assertEquals(60.seconds, RetransmitScheduler.nextDelay(600.seconds))
    }

    @Test
    fun runFiresRetransmitsOnSteppedCadence() =
        runTest {
            var fires = 0
            val start = testScheduler.currentTime
            val job =
                launch {
                    RetransmitScheduler.run(
                        elapsedSince = { (testScheduler.currentTime - start).milliseconds },
                        retransmit = { fires++ },
                    )
                }
            runCurrent()
            assertEquals(0, fires) // nothing yet — first repeat is at +1s.

            // 0–5s: 1s steps → fires at 1,2,3,4,5 = 5 fires.
            advanceTimeBy(5.seconds)
            runCurrent()
            assertEquals(5, fires)

            // 5–10s: 3s steps. Next fire at 5+3=8, then 8+3=11 (past 10). So at
            // t=10 we've added the fire at 8 → 6 total.
            advanceTimeBy(5.seconds)
            runCurrent()
            assertEquals(6, fires)

            job.cancel()
        }
}
