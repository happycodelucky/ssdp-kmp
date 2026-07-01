/*
 * ssdp-kmp — the device registry.
 *
 * The piece swift-ssdp deliberately left to the consumer (plan decision 1).
 * Folds parsed M-SEARCH responses and NOTIFY advertisements into a deduplicated,
 * lifecycle-tracked map exposed as a StateFlow, and emits delta events on a
 * SharedFlow. Handles the four removal paths: explicit ssdp:byebye, silent
 * cache-control (max-age) expiry, a full network-change reset, and an explicit
 * consumer-initiated clear.
 *
 * Concurrency: all map mutations happen on a single-threaded confinement via the
 * registry's own coroutine actor-like loop is overkill here; instead mutations
 * are funneled through suspend functions invoked from the client's single
 * collection coroutine, plus expiry coroutines launched on the injected scope.
 * A Mutex guards the map because expiry timers and ingest can interleave
 * (CLAUDE.md §3: Mutex for shared mutable state across suspend boundaries).
 */
package com.happycodelucky.ssdp.internal

import com.happycodelucky.ssdp.Advertisement
import com.happycodelucky.ssdp.DeviceChange
import com.happycodelucky.ssdp.DiscoveredDevice
import com.happycodelucky.ssdp.MSearchResponse
import com.happycodelucky.ssdp.Notification
import com.happycodelucky.ssdp.SearchTarget
import com.happycodelucky.ssdp.SsdpHeaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Tracks the set of devices currently believed present on the network.
 *
 * @param scope the coroutine scope expiry timers run on — owned by the client,
 *   cancelled on close. Injecting it (rather than reading a wall clock from a
 *   timer) keeps the whole registry drivable from `runTest` virtual time
 *   (LESSONS N-011 / T-003).
 * @param clock source of "now" for firstSeen/lastSeen/expiresAt. Inject a test
 *   clock under virtual time; production passes [Clock.System].
 */
internal class DeviceRegistry(
    private val scope: CoroutineScope,
    private val clock: Clock,
) {
    private val mutex = Mutex()
    private val devices = MutableStateFlow<Map<String, DiscoveredDevice>>(emptyMap())

    // replay = 0: changes are live deltas; late subscribers read the StateFlow
    // snapshot for current state. extraBufferCapacity gives ingest headroom so
    // emit() never suspends the collection loop on a slow subscriber.
    private val _changes = MutableSharedFlow<DeviceChange>(replay = 0, extraBufferCapacity = 64)

    /** Per-USN expiry timer jobs, so re-announcements can reschedule cleanly. */
    private val expiryJobs = mutableMapOf<String, Job>()

    /** The always-current device set, keyed by USN. */
    val deviceSet: StateFlow<Map<String, DiscoveredDevice>> = devices.asStateFlow()

    /** Live stream of additions / updates / removals. */
    val changes: SharedFlow<DeviceChange> = _changes.asSharedFlow()

    /** Fold an M-SEARCH response into the registry. */
    suspend fun ingestResponse(response: MSearchResponse) {
        upsert(
            usn = response.usn,
            target = response.searchTarget,
            location = response.location,
            server = response.server,
            cacheControl = response.cacheControl,
            bootId = response.otherHeaders[SsdpHeaderKeys.BOOT_ID]?.toIntOrNull(),
            configId = response.otherHeaders[SsdpHeaderKeys.CONFIG_ID]?.toIntOrNull(),
            otherHeaders = response.otherHeaders,
        )
    }

    /** Fold a NOTIFY into the registry, applying the right lifecycle transition. */
    suspend fun ingestNotification(notification: Notification) {
        when (notification) {
            is Notification.Alive, is Notification.Update -> {
                val ad: Advertisement = notification.advertisement
                upsert(
                    usn = ad.usn,
                    target = ad.notificationTarget,
                    location = ad.location,
                    server = ad.server,
                    cacheControl = ad.cacheControl,
                    // On ssdp:update the *next* boot id is the device's new
                    // identity; treat it as the current bootId so a following
                    // alive with that bootId is not seen as another change.
                    bootId = (notification as? Notification.Update)?.advertisement?.nextBootId ?: ad.bootId,
                    configId = ad.configId,
                    otherHeaders = ad.otherHeaders,
                )
            }

            is Notification.Byebye -> {
                remove(notification.advertisement.usn, DeviceChange.Removed.Reason.Byebye)
            }
        }
    }

    /**
     * Clear every tracked device. Used both when the active network changes
     * (plan decision 4) and when a consumer explicitly clears the registry.
     * Emits a [DeviceChange.Removed] with [reason] for each evicted device.
     *
     * @param reason why the devices are being removed — defaults to
     *   [DeviceChange.Removed.Reason.NetworkChanged] (the network-change caller);
     *   a manual clear passes [DeviceChange.Removed.Reason.Cleared].
     */
    suspend fun reset(reason: DeviceChange.Removed.Reason = DeviceChange.Removed.Reason.NetworkChanged) {
        mutex
            .withLock {
                val evicted = devices.value.values.toList()
                cancelAllExpiryLocked()
                devices.value = emptyMap()
                evicted
            }.forEach { device ->
                _changes.emit(DeviceChange.Removed(device, reason))
            }
    }

    private suspend fun upsert(
        usn: String,
        target: SearchTarget,
        location: String?,
        server: String?,
        cacheControl: Duration?,
        bootId: Int?,
        configId: Int?,
        otherHeaders: SsdpHeaders,
    ) {
        val now = clock.now()
        val change =
            mutex.withLock {
                val existing = devices.value[usn]
                val firstSeen = existing?.firstSeen ?: now
                val expiresAt = cacheControl?.let { now + it }
                val updated =
                    DiscoveredDevice(
                        usn = usn,
                        target = target,
                        // Preserve a previously-known location if this message
                        // omits one (e.g. an alive after a byebye-less gap).
                        location = location ?: existing?.location,
                        server = server ?: existing?.server,
                        cacheControl = cacheControl ?: existing?.cacheControl,
                        bootId = bootId ?: existing?.bootId,
                        configId = configId ?: existing?.configId,
                        firstSeen = firstSeen,
                        lastSeen = now,
                        expiresAt = expiresAt,
                        otherHeaders = otherHeaders,
                    )
                devices.value = devices.value + (usn to updated)
                rescheduleExpiryLocked(usn, expiresAt, now)

                when {
                    existing == null -> DeviceChange.Found(updated)
                    isMaterialChange(existing, updated) -> DeviceChange.Updated(updated)
                    else -> null // pure refresh (same boot/location); no event, but expiry was rescheduled.
                }
            }
        if (change != null) _changes.emit(change)
    }

    private fun isMaterialChange(
        old: DiscoveredDevice,
        new: DiscoveredDevice,
    ): Boolean =
        old.bootId != new.bootId ||
            old.location != new.location ||
            old.cacheControl != new.cacheControl ||
            old.configId != new.configId

    private suspend fun remove(
        usn: String,
        reason: DeviceChange.Removed.Reason,
    ) {
        val removed =
            mutex.withLock {
                val device = devices.value[usn] ?: return@withLock null
                expiryJobs.remove(usn)?.cancel()
                devices.value = devices.value - usn
                device
            }
        if (removed != null) _changes.emit(DeviceChange.Removed(removed, reason))
    }

    /** Must be called while holding [mutex]. */
    private fun rescheduleExpiryLocked(
        usn: String,
        expiresAt: Instant?,
        now: Instant,
    ) {
        expiryJobs.remove(usn)?.cancel()
        if (expiresAt == null) return
        val ttl = expiresAt - now
        expiryJobs[usn] =
            scope.launch {
                delay(ttl)
                // Re-check under the lock: a re-announcement may have refreshed
                // expiry (replacing this job) or a byebye may have removed it.
                val expired =
                    mutex.withLock {
                        val device = devices.value[usn] ?: return@withLock null
                        // Only expire if this is still the job-of-record and the
                        // device hasn't been refreshed past its deadline.
                        if (device.expiresAt != expiresAt) return@withLock null
                        expiryJobs.remove(usn)
                        devices.value = devices.value - usn
                        device
                    }
                if (expired != null) {
                    _changes.emit(DeviceChange.Removed(expired, DeviceChange.Removed.Reason.Expired))
                }
            }
    }

    /** Must be called while holding [mutex]. */
    private fun cancelAllExpiryLocked() {
        expiryJobs.values.forEach { it.cancel() }
        expiryJobs.clear()
    }
}
