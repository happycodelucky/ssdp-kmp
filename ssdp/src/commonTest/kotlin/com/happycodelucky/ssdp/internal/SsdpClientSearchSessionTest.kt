/*
 * ssdp-kmp — additive search sessions (LESSONS D-016): each search() contributes
 * its targets while its SearchSession is active, targets shared by several
 * sessions have one reference-counted retransmit loop, and opening or closing a
 * session never restarts another target's cadence. Cadence assertions stamp each
 * send with the test scheduler's virtual time.
 */
package com.happycodelucky.ssdp.internal

import com.happycodelucky.ssdp.SearchSession
import com.happycodelucky.ssdp.SearchTarget
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

@OptIn(ExperimentalCoroutinesApi::class)
class SsdpClientSearchSessionTest {
    private val all = SearchTarget.All.rawValue
    private val root = SearchTarget.RootDevice.rawValue

    // None of these tests fetches a description; a mock keeps the client off real HTTP.
    private fun noHttp(): HttpClient = HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) })

    private fun TestScope.newClient(socket: FakeMulticastSocket): SsdpClientImpl =
        SsdpClientImpl(
            socketFactory = { socket },
            parentScope = this,
            clock = TestClock(testScheduler),
            timeSource = TestTimeSource(testScheduler),
            httpClient = noHttp(),
        )

    private fun TestScope.timedSocket(): FakeMulticastSocket = FakeMulticastSocket(now = { testScheduler.currentTime })

    /** Advance virtual time to exactly [at] (from test start) and run what is due then. */
    private fun TestScope.advanceTo(at: Duration) {
        advanceTimeBy(at.inWholeMilliseconds - testScheduler.currentTime)
        runCurrent()
    }

    @Test
    fun sessionsWithDifferentTargetsEachKeepTheirOwnCadence() =
        runTest {
            val socket = timedSocket()
            val client = newClient(socket)
            runCurrent()

            val a = client.search(setOf(SearchTarget.All))
            runCurrent()
            advanceTo(2500.milliseconds)
            val b = client.search(setOf(SearchTarget.RootDevice))
            runCurrent()
            assertEquals(setOf(SearchTarget.All, SearchTarget.RootDevice), client.searchingTargets)

            advanceTo(6.seconds)
            a.close()
            assertFalse(a.isActive)
            assertTrue(b.isActive)
            advanceTo(14.seconds)

            // ssdp:all kept the 1s steps from t=0: b opening at 2.5s didn't
            // restart it (a restart would add a send at 2.5s). It stopped when a
            // closed at 6s (its next step was 8s).
            assertEquals(listOf(0L, 1000, 2000, 3000, 4000, 5000), socket.sentAt(all))
            // upnp:rootdevice runs its own cadence from 2.5s, unaffected by a's
            // close: 1s steps for 5s, then 3s.
            assertEquals(listOf(2500L, 3500, 4500, 5500, 6500, 7500, 10500, 13500), socket.sentAt(root))
            assertEquals(setOf(SearchTarget.RootDevice), client.searchingTargets)

            client.close()
        }

    @Test
    fun aTargetSharedBySessionsHasOneLoopUntilTheLastSessionCloses() =
        runTest {
            val socket = timedSocket()
            val client = newClient(socket)
            runCurrent()

            val a = client.search(setOf(SearchTarget.All))
            runCurrent()
            advanceTo(2500.milliseconds)
            val b = client.search(setOf(SearchTarget.All))
            runCurrent()

            advanceTo(3200.milliseconds)
            a.close()
            advanceTo(4200.milliseconds)
            assertEquals(setOf(SearchTarget.All), client.searchingTargets) // b still holds it
            b.close()
            assertEquals(emptySet(), client.searchingTargets)
            advanceTo(20.seconds)

            // One loop: the shared cadence (0, 1, 2, 3, 4s) plus b's single
            // join-time M-SEARCH at 2.5s. A second loop would add 3.5s, 4.5s, …
            // Nothing after b — the last holder — closed at 4.2s.
            assertEquals(listOf(0L, 1000, 2000, 2500, 3000, 4000), socket.sentAt(all))

            client.close()
        }

    @Test
    fun aSharedTargetAdvertisesTheLargestMaxWaitAmongItsSessions() =
        runTest {
            val socket = timedSocket()
            val client = newClient(socket)
            runCurrent()

            client.search(setOf(SearchTarget.All), maxWaitSeconds = 1)
            runCurrent()
            val patient = client.search(setOf(SearchTarget.All), maxWaitSeconds = 3)
            runCurrent()
            assertTrue(socket.sentText.last().contains("MX: 3\r\n"), "the joining session's M-SEARCH uses the max MX")

            advanceTo(1.seconds)
            assertTrue(socket.sentText.last().contains("MX: 3\r\n"), "the shared loop uses the max MX")

            patient.close()
            advanceTo(2.seconds)
            assertTrue(socket.sentText.last().contains("MX: 1\r\n"), "MX drops back once the larger session closes")

            client.close()
        }

    @Test
    fun aSessionTimeoutEndsOnlyThatSessionsContribution() =
        runTest {
            val socket = timedSocket()
            val client = newClient(socket)
            runCurrent()

            val bounded = client.search(setOf(SearchTarget.All, SearchTarget.RootDevice), timeout = 3.seconds)
            val open = client.search(setOf(SearchTarget.RootDevice))
            runCurrent()

            advanceTo(3.seconds)
            assertFalse(bounded.isActive, "the timeout ends the session")
            assertTrue(open.isActive)
            assertEquals(setOf(SearchTarget.RootDevice), client.searchingTargets)
            bounded.close() // closing a timed-out session is a no-op

            advanceTo(20.seconds)
            assertTrue(socket.sentAt(all).all { it < 3000 }, "ssdp:all stops with its only session")
            assertTrue(socket.sentAt(root).any { it > 3000 }, "upnp:rootdevice continues for the other session")

            client.close()
        }

    @Test
    fun stopSearchEndsEverySessionAndALaterSearchStartsFresh() =
        runTest {
            val socket = timedSocket()
            val client = newClient(socket)
            runCurrent()

            val a = client.search(setOf(SearchTarget.All))
            val b = client.search(setOf(SearchTarget.RootDevice), timeout = 30.seconds)
            runCurrent()
            client.stopSearch()
            assertFalse(a.isActive)
            assertFalse(b.isActive)
            assertEquals(emptySet(), client.searchingTargets)
            a.close() // no-op after stopSearch

            advanceTo(10.seconds)
            assertEquals(listOf(0L), socket.sentAt(all))
            assertEquals(listOf(0L), socket.sentAt(root))

            val c = client.search(setOf(SearchTarget.All))
            runCurrent()
            assertTrue(c.isActive)
            assertEquals(listOf(0L, 10_000), socket.sentAt(all))

            client.close()
        }

    @Test
    fun closingTheClientEndsEverySessionAndLaterCallsAreNoOps() =
        runTest {
            val socket = timedSocket()
            val client = newClient(socket)
            runCurrent()

            val a = client.search(setOf(SearchTarget.All))
            runCurrent()
            client.close()
            assertFalse(a.isActive)
            a.close()
            a.close() // repeated close is a no-op

            val late = client.search(setOf(SearchTarget.RootDevice))
            assertFalse(late.isActive, "a session opened on a closed client is born inactive")
            late.close()
            advanceTo(10.seconds)
            assertEquals(listOf(0L), socket.sentAt(all))
            assertTrue(socket.sentAt(root).isEmpty())
        }

    @Test
    fun sessionReportsItsTargets() =
        runTest {
            val client = newClient(timedSocket())
            val targets = setOf(SearchTarget.All, SearchTarget.Custom("roku:ecp"))
            client.search(targets).use { session -> assertEquals(targets, session.targets) }
            assertEquals(emptySet(), client.searchingTargets)
            client.close()
        }

    @Test
    fun concurrentOpenAndCloseKeepsTheTargetCountsConsistent() =
        runTest {
            // Real dispatchers, so search() and close() genuinely race across threads.
            val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val socket = FakeMulticastSocket()
            val client = SsdpClientImpl({ socket }, parent, Clock.System, TimeSource.Monotonic, httpClient = noHttp())
            val targets = listOf(SearchTarget.All, SearchTarget.RootDevice, SearchTarget.Custom("roku:ecp"))

            val kept: List<SearchSession> =
                withContext(Dispatchers.Default) {
                    (0 until WORKERS)
                        .map { worker ->
                            async {
                                val mine = mutableListOf<SearchSession>()
                                repeat(ROUNDS) { round ->
                                    val session = client.search(setOf(targets[(worker + round) % 3], targets[round % 3]))
                                    // Keep a few open; close the rest straight away.
                                    if (round % KEEP_EVERY == 0) mine += session else session.close()
                                }
                                mine
                            }
                        }.awaitAll()
                        .flatten()
                }
            assertEquals(kept.flatMapTo(mutableSetOf()) { it.targets }, client.searchingTargets)

            // Close every kept session from several threads at once, twice over.
            withContext(Dispatchers.Default) {
                (0 until WORKERS).map { launch { kept.forEach { it.close() } } }.forEach { it.join() }
            }
            assertTrue(kept.none { it.isActive })
            assertEquals(emptySet(), client.searchingTargets)

            // Cancel the real-dispatcher work before closing the socket (LESSONS B-013).
            parent.coroutineContext.job.cancelAndJoin()
            client.close()
        }

    private companion object {
        const val WORKERS = 8
        const val ROUNDS = 200
        const val KEEP_EVERY = 50
    }
}
