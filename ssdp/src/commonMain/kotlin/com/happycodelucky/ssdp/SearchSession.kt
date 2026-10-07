/*
 * ssdp-kmp — the handle a caller holds for one SsdpClient.search() call.
 *
 * Searches are additive (LESSONS D-016): every open session contributes its
 * targets, and the client M-SEARCHes for the union of all open sessions. Closing a
 * session withdraws only its own targets, so independent callers can share one
 * client (one NOTIFY listener, one socket pair) without cancelling each other.
 */
package com.happycodelucky.ssdp

/**
 * One caller's contribution to a client's active search, returned by
 * [SsdpClient.search].
 *
 * While the session is active, the client searches for its [targets] alongside
 * every other active session's. The client sends one M-SEARCH per distinct target
 * across all sessions and retransmits it on one stepped cadence, so two sessions
 * that both want [SearchTarget.All] share a single retransmit loop. Closing the
 * session withdraws its targets. A target leaves the search when no active session
 * still includes it. Other sessions keep their cadence, because nothing is
 * restarted.
 *
 * A session ends, and [isActive] becomes `false`, when any of these happens:
 * - [close] is called;
 * - the `timeout` passed to [SsdpClient.search] elapses;
 * - [SsdpClient.stopSearch] ends every session;
 * - the client is closed.
 *
 * Ending is permanent. A session can't be restarted, so call [SsdpClient.search]
 * again for a new one. Ending a session never touches passive NOTIFY listening or
 * the devices already discovered.
 *
 * ```kotlin
 * val search = client.search(setOf(SearchTarget.All))
 * try {
 *     client.devices.first { it.isNotEmpty() }
 * } finally {
 *     search.close()
 * }
 * // or: client.search(targets).use { … }
 * ```
 *
 * From Swift the session is a `SearchSession` with `targets` (a
 * `Set<AnyHashable>` of `SearchTarget`s, the same shape `search(targets:)` takes),
 * `isActive` and `close()`. Swift warns when the result of `search` is unused, which
 * is a reminder to keep the handle:
 *
 * ```swift
 * let search = try await client.search(targets: [SearchTargetAll.shared], maxWaitSeconds: 1, timeout: nil)
 * defer { search.close() }
 * ```
 */
public interface SearchSession : AutoCloseable {
    /** The targets this session searches for, exactly as passed to [SsdpClient.search]. */
    public val targets: Set<SearchTarget>

    /**
     * `true` while this session contributes [targets] to the client's search.
     * `false` once it has ended (see [SearchSession] for when that happens), and
     * from the start for a session opened with no targets or on a closed client.
     */
    public val isActive: Boolean

    /**
     * End this session and withdraw its [targets] from the client's search.
     * Retransmission stops for each target that no other active session includes.
     *
     * Non-suspending and safe to call from any thread. Idempotent: closing a
     * session that has already ended (by an earlier [close], its timeout,
     * [SsdpClient.stopSearch] or the client's own `close()`) does nothing.
     */
    override fun close()
}
