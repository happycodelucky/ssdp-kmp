/*
 * ssdp-kmp — public client API (CLAUDE.md §8).
 *
 * Designed for Swift consumers as much as Kotlin ones: StateFlow/SharedFlow
 * become AsyncSequences via SKIE, the sealed change/error types become
 * exhaustive Swift enums, and `close()` reads natively in both languages. No
 * callback-based surface (CLAUDE.md §7, §13).
 */
@file:OptIn(ExperimentalObjCName::class)

package com.happycodelucky.ssdp

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName
import kotlin.time.Duration

/**
 * An SSDP (UPnP discovery) client: actively searches for devices via multicast
 * M-SEARCH (with retransmit for flaky Wi-Fi), passively listens for NOTIFY
 * advertisements, and maintains a live device registry.
 *
 * ### Searching
 *
 * Start a search for one or more targets. Discovery is continuous while the
 * search is active — the client retransmits the M-SEARCH on a stepped cadence
 * (1s → 3s → 10s → 60s) to fill in for UDP packets lost over Wi-Fi, and folds
 * every response and advertisement into the registry.
 *
 * ```kotlin
 * val client: SsdpClient = SsdpClient()           // platform factory
 * client.search(setOf(SearchTarget.All))          // or specific targets
 * client.devices.collect { byUsn -> render(byUsn.values) }
 * ```
 *
 * From Swift the same flows read as `AsyncSequence`s via SKIE.
 *
 * ### Targets
 *
 * Unlike single-target SSDP clients, [search] accepts a `Set<SearchTarget>` and
 * fans out one M-SEARCH per target over the shared socket, merging matches into
 * the one registry — so an app watching for several device types issues a
 * single call.
 *
 * ### Lifecycle
 *
 * [devices] is the always-current set keyed by USN; [changes] is the live delta
 * stream (found / updated / removed). Devices are removed on `ssdp:byebye`, on
 * `CACHE-CONTROL: max-age` expiry, when the active network changes (the registry
 * resets, since SSDP devices are LAN-scoped), or when the consumer calls
 * [clearDevices]. Call [close] when done; it is idempotent.
 */
public interface SsdpClient : AutoCloseable {
    /** The always-current set of discovered devices, keyed by USN. */
    public val devices: StateFlow<Map<String, DiscoveredDevice>>

    /**
     * Live additions / updates / removals to the registry. `replay = 0`:
     * late collectors read [devices] for current state and receive only future
     * deltas here.
     */
    public val changes: SharedFlow<DeviceChange>

    /**
     * Begin (or replace) the active search. Sends an M-SEARCH for each target
     * in [targets] and keeps retransmitting on the stepped cadence. Calling
     * again replaces the target set. Passive NOTIFY listening is always on once
     * the client is constructed, independent of any active search.
     *
     * `search` returns as soon as the first M-SEARCH round has been sent — it
     * does not block for the [timeout]. Retransmission runs in the background;
     * observe [devices] / [changes] for results.
     *
     * @param targets the search targets; use `setOf(SearchTarget.All)` for a
     *   wildcard. An empty set stops active searching (equivalent to
     *   [stopSearch]) but leaves passive listening on.
     * @param maxWaitSeconds the `MX` value advertised to responders (1–5 per
     *   UPnP); devices reply after a random delay in `[0, MX]`.
     * @param timeout how long to keep retransmitting before stopping
     *   automatically. After it elapses, broadcasting stops but passive NOTIFY
     *   listening continues and already-discovered devices stay in [devices]
     *   (until they leave / expire / the network changes). `null` (the default)
     *   retransmits indefinitely until [stopSearch] or [close]. A finite timeout
     *   is the common case — devices on the LAN are usually found within a few
     *   seconds, after which continued broadcasting is just noise.
     * @throws SsdpError if the multicast group cannot be joined.
     */
    @Throws(SsdpError::class, kotlin.coroutines.cancellation.CancellationException::class)
    public suspend fun search(
        targets: Set<SearchTarget>,
        maxWaitSeconds: Int = DEFAULT_MAX_WAIT_SECONDS,
        timeout: Duration? = null,
    )

    /** Stop active M-SEARCH retransmission. Passive NOTIFY listening continues. */
    public suspend fun stopSearch()

    /**
     * Clear the device registry: drop every currently-tracked device and emit a
     * [DeviceChange.Removed] with reason [DeviceChange.Removed.Reason.Cleared]
     * for each. [devices] becomes empty.
     *
     * Use this to force a fresh enumeration — e.g. a manual "refresh" that should
     * visibly empty the list and then re-populate from a new [search]. Devices
     * that have left but not yet hit their `CACHE-CONTROL: max-age` deadline
     * disappear immediately. Neither passive NOTIFY listening nor any active
     * search is affected; the registry simply refills as responses arrive. From
     * Swift this is `try await client.clearDevices()`.
     */
    public suspend fun clearDevices()

    /**
     * Fetch (or return cached) the device's UPnP description document — the XML
     * at the device's `LOCATION` URL, parsed into a [DeviceDescription] tree
     * (friendly name, manufacturer, model, icons, services, embedded devices).
     *
     * Lazy and explicit: discovery never fetches descriptions on its own, so the
     * consumer decides *when* to pay the HTTP cost (e.g. only for a device the
     * user taps). The result is cached per device; concurrent calls for the same
     * device share a single fetch. The cache is evicted when the device leaves
     * (byebye / cache-control expiry) or the network changes.
     *
     * Never throws domain failures — every outcome is a [DescriptionResult] case
     * (only `CancellationException` propagates). From Swift this is
     * `try await client.description(of: device)`.
     *
     * ```kotlin
     * when (val r = client.description(device)) {
     *     is DescriptionResult.Success -> show(r.description.device.friendlyName)
     *     DescriptionResult.NotFound -> showUnknown()
     *     is DescriptionResult.FetchFailed -> retryLater()
     *     is DescriptionResult.ParseFailed -> logBadDevice(r.message)
     * }
     * ```
     */
    @Throws(kotlin.coroutines.cancellation.CancellationException::class)
    @ObjCName("description")
    public suspend fun description(device: DiscoveredDevice): DescriptionResult

    /**
     * Like [description], but looks the device up by [usn] in the current
     * registry first. Returns [DescriptionResult.NotFound] if no device with that
     * USN is currently tracked. From Swift this is
     * `try await client.description(forUsn: usn)`.
     */
    @Throws(kotlin.coroutines.cancellation.CancellationException::class)
    @ObjCName("descriptionForUsn")
    public suspend fun description(usn: String): DescriptionResult

    /**
     * Stop all discovery, leave the multicast group, and cancel internal
     * coroutines. Idempotent; [devices] retains its last value but stops
     * updating.
     */
    override fun close()

    public companion object {
        /** Default `MX` (seconds) advertised in M-SEARCH requests. */
        public const val DEFAULT_MAX_WAIT_SECONDS: Int = 1
    }
}
