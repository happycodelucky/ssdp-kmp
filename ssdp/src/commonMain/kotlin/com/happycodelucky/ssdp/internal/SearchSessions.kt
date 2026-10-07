/*
 * ssdp-kmp — additive M-SEARCH sessions (LESSONS D-016).
 *
 * Every SsdpClient.search() call opens a session that contributes its targets
 * until it ends; the client M-SEARCHes for the union of the active sessions'
 * targets. Each distinct target has ONE retransmit loop, reference-counted by the
 * sessions that include it, so sessions sharing a client never cancel or restart
 * each other. SsdpClientImpl delegates search(), stopSearch() and the search half
 * of close() here.
 */
package com.happycodelucky.ssdp.internal

import com.happycodelucky.ssdp.SearchSession
import com.happycodelucky.ssdp.SearchTarget
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * The client's search sessions and their per-target retransmit loops.
 *
 * All state is guarded by [searchLock]. Every critical section is non-suspending
 * (it only launches or cancels jobs), so this is the atomicfu `synchronized` tier
 * (CLAUDE.md §6). That is what lets the non-suspending [SearchSession.close]
 * withdraw targets from any thread without blocking on a coroutine Mutex.
 *
 * @param scope where retransmit loops and session timeouts run: the client's own
 *   child scope, so closing the client cancels them.
 * @param timeSource monotonic source for each loop's elapsed time (virtual time
 *   under `runTest`, LESSONS N-002).
 * @param send writes one M-SEARCH datagram to the transport.
 */
internal class SearchSessions(
    private val scope: CoroutineScope,
    private val timeSource: TimeSource,
    private val send: suspend (ByteArray) -> Unit,
) {
    private val searchLock = SynchronizedObject()
    private val activeSessions = mutableSetOf<Session>()
    private val activeTargets = mutableMapOf<SearchTarget, TargetSearch>()
    private var closed = false

    /** The union of every active session's targets: exactly the targets with a running retransmit loop. */
    val searchingTargets: Set<SearchTarget> get() = synchronized(searchLock) { activeTargets.keys.toSet() }

    /**
     * Open a session for [targets]; [com.happycodelucky.ssdp.SsdpClient.search]
     * documents the semantics. The session is inactive from the start when
     * [targets] is empty or after [close].
     */
    fun open(targets: Set<SearchTarget>, maxWaitSeconds: Int, timeout: Duration?): SearchSession {
        val session = Session(targets, maxWaitSeconds)
        // Jobs are created LAZY under the lock and started after it, so a
        // coroutine that runs eagerly (an unconfined parent dispatcher) never
        // runs inside the critical section.
        val toStart = mutableListOf<Job>()
        synchronized(searchLock) {
            // On a closed client, or with nothing to search for, the session is
            // born inactive and leaves the other sessions alone.
            if (closed || targets.isEmpty()) {
                session.active = false
                return session
            }
            activeSessions += session
            targets.forEach { target ->
                val existing = activeTargets[target]
                if (existing == null) {
                    // A target new to the client: its own retransmit loop, whose
                    // first M-SEARCH goes out immediately.
                    val job = scope.launch(start = CoroutineStart.LAZY) { retransmit(target) }
                    activeTargets[target] = TargetSearch(job, mutableListOf(session))
                    toStart += job
                } else {
                    // Already searched: join the shared loop without restarting
                    // its cadence, plus one immediate M-SEARCH so this session
                    // hears fresh replies before the loop's next step.
                    existing.sessions += session
                    toStart += scope.launch(start = CoroutineStart.LAZY) { sendMSearch(target) }
                }
            }
            if (timeout != null) {
                session.timeoutJob =
                    scope
                        .launch(start = CoroutineStart.LAZY) {
                            delay(timeout)
                            end(session)
                        }.also { toStart += it }
            }
        }
        toStart.forEach { it.start() }
        return session
    }

    /** End every active session (`stopSearch()`). Later [open] calls work as usual. */
    fun endAll() {
        synchronized(searchLock) { activeSessions.toList().forEach(::endLocked) }
    }

    /** End every active session and make every later [open] return an inactive session (client `close()`). */
    fun close() {
        synchronized(searchLock) {
            closed = true
            activeSessions.toList().forEach(::endLocked)
        }
    }

    /**
     * The retransmit loop for one [target]: an immediate M-SEARCH, then the
     * stepped cadence measured from this loop's own start. It runs until the last
     * session that includes [target] ends and cancels it.
     */
    private suspend fun retransmit(target: SearchTarget) {
        val started = timeSource.markNow()
        sendMSearch(target)
        RetransmitScheduler.run(
            elapsedSince = { started.elapsedNow() },
            retransmit = { sendMSearch(target) },
        )
    }

    /**
     * Send one M-SEARCH for [target], advertising the largest `MX` among the
     * active sessions that include it. It's read per send, so a session that
     * joins or leaves changes the next datagram without restarting the loop. Does
     * nothing once no active session includes [target]. A failed send is
     * swallowed: the next round may succeed once Wi-Fi recovers.
     */
    private suspend fun sendMSearch(target: SearchTarget) {
        val maxWaitSeconds =
            synchronized(searchLock) {
                activeTargets[target]?.sessions?.maxOf { it.maxWaitSeconds }
            } ?: return
        try {
            send(MSearchRequest(target, maxWaitSeconds).bytes())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ssdpLog.d(e) { "M-SEARCH for ${target.rawValue} failed; the next retransmit retries" }
        }
    }

    /** End [session] and withdraw its targets. Idempotent; never suspends, so [Session.close] can call it from any thread. */
    private fun end(session: Session) {
        synchronized(searchLock) { endLocked(session) }
    }

    /** Must hold [searchLock]. Cancels the retransmit loop of each target no other active session includes. */
    private fun endLocked(session: Session) {
        if (!session.active) return
        session.active = false
        activeSessions -= session
        // Cancelling from inside the timeout coroutine itself is harmless: it
        // has nothing left to do.
        session.timeoutJob?.cancel()
        session.timeoutJob = null
        session.targets.forEach { target ->
            val search = activeTargets[target] ?: return@forEach
            search.sessions -= session
            if (search.sessions.isEmpty()) {
                search.job.cancel()
                activeTargets -= target
            }
        }
    }

    /**
     * The client's [SearchSession]. Its mutable state is guarded by [searchLock];
     * [active] only ever flips from `true` to `false`.
     */
    private inner class Session(override val targets: Set<SearchTarget>, val maxWaitSeconds: Int) : SearchSession {
        var active: Boolean = true
        var timeoutJob: Job? = null

        override val isActive: Boolean get() = synchronized(searchLock) { active }

        override fun close() = end(this)

        override fun toString(): String = "SearchSession(targets=$targets, isActive=$isActive)"
    }

    /**
     * One distinct target being searched: its retransmit [job] and the active
     * [sessions] that include it (the reference count). Guarded by [searchLock].
     */
    private class TargetSearch(val job: Job, val sessions: MutableList<Session>)
}
