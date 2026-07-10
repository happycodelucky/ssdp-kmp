/*
 * ssdp-kmp — the shared client implementation.
 *
 * Wires the platform socket → parser → registry → retransmit, all in commonMain.
 * Platform code supplies only the socket (via a factory) — the orchestration is
 * identical on every target (CLAUDE.md §4: keep the expect/actual seam tiny).
 *
 * The constructor takes its collaborators by injection (socket factory, clock,
 * scope) so the whole client is drivable under `runTest` virtual time with a
 * fake socket — no real multicast needed in tests (LESSONS N-011). The public
 * platform factory `SsdpClient()` supplies the production wiring.
 */
package com.happycodelucky.ssdp.internal

import com.happycodelucky.ssdp.DescriptionResult
import com.happycodelucky.ssdp.DeviceChange
import com.happycodelucky.ssdp.DeviceDescription
import com.happycodelucky.ssdp.DiscoveredDevice
import com.happycodelucky.ssdp.SearchTarget
import com.happycodelucky.ssdp.SsdpClient
import com.happycodelucky.ssdp.SsdpDeviceListener
import io.ktor.client.HttpClient
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * @param socketFactory opens a joined multicast socket; injected so tests pass a
 *   fake. Called once on construction (passive listening starts immediately).
 * @param parentScope the scope the client's work is a child of — the production
 *   factory passes a fresh [newClientScope]; tests pass the `TestScope`. The
 *   client roots its own [SupervisorJob] *under* this parent, so [close]
 *   cancels only the client's coroutines and never the caller's scope (which,
 *   for a `TestScope`, `runTest` forbids cancelling). When the parent scope is
 *   itself cancelled (app teardown), the client's child job is cancelled too.
 * @param clock "now" source for the registry; inject a virtual clock in tests.
 * @param timeSource monotonic source for retransmit elapsed-time; the test
 *   scheduler's virtual time under `runTest`, `TimeSource.Monotonic` in prod.
 * @param networkTransportTags optional stream of transport tags from reachable
 *   (plan decision 4). When non-null, the client watches it and resets the
 *   registry whenever the network key (transport + local IPv4 subnet) changes,
 *   since SSDP devices are LAN-scoped. `null` disables the reset wiring (tests
 *   that don't exercise it, or platforms without a reachable dependency).
 * @param subnetProbe resolves the active interface's IPv4 subnet; defaults to
 *   the platform [localSubnetKey]. Injected so tests drive it deterministically.
 * @param httpClient Ktor client for fetching device description documents (v1.1).
 *   Owned by this impl and closed in [close]. The platform factories pass
 *   [descriptionHttpClient]; tests pass a MockEngine-backed client.
 */
internal class SsdpClientImpl(
    private val socketFactory: () -> MulticastSocket,
    parentScope: CoroutineScope,
    clock: Clock,
    private val timeSource: TimeSource,
    networkTransportTags: Flow<String>? = null,
    subnetProbe: () -> String? = ::localSubnetKey,
    private val httpClient: HttpClient = descriptionHttpClient(),
) : SsdpClient {
    // The client's own job, a child of the parent's. Cancelling it on close()
    // tears down the receive loop, retransmit loops, and expiry timers without
    // touching the parent scope.
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope: CoroutineScope = CoroutineScope(parentScope.coroutineContext + job)

    private val registry = DeviceRegistry(scope, clock)

    // v1.1 description fetch + cache. Built after `registry` so it can subscribe
    // to registry.changes for eviction. Shares the client's child scope, so its
    // eviction collector and fetch coroutines are cancelled on close().
    private val descriptionService =
        DescriptionService(
            scope = scope,
            clock = clock,
            httpClient = httpClient,
            registryChanges = registry.changes,
        )

    override val devices: StateFlow<Map<String, DiscoveredDevice>> get() = registry.deviceSet
    override val changes: SharedFlow<DeviceChange> get() = registry.changes

    private val closed = atomic(false)

    // The single joined socket. Opened eagerly so passive NOTIFY listening is on
    // from construction, independent of any active search.
    private val socket: MulticastSocket = socketFactory()

    // Guards the active-search state (target set + retransmit jobs) against
    // concurrent search()/stopSearch()/close() calls.
    private val searchMutex = Mutex()
    private var retransmitJobs: List<Job> = emptyList()

    // Registered callback listeners (SsdpClient.addListener). A thin fan-out over
    // registry.changes — no separate emission path (CLAUDE.md §6/§12). Mutation
    // and iteration are non-suspending critical sections, so they use the
    // atomicfu synchronized tier (not searchMutex/registry's Mutex, which are for
    // suspend boundaries — CLAUDE.md §6). Guarded by `listenerLock`; kept as a
    // copy-on-read list so callbacks fire outside the lock.
    private val listenerLock = SynchronizedObject()
    private val listeners = mutableListOf<SsdpDeviceListener>()

    init {
        // Receive loop: parse every datagram and fold it into the registry.
        scope.launch {
            socket.incoming.collect { datagram ->
                when (val message = SsdpMessageParser.parse(datagram.text)) {
                    is SsdpMessage.SearchResponse -> registry.ingestResponse(message.response)
                    is SsdpMessage.Notify -> registry.ingestNotification(message.notification)
                    SsdpMessage.SearchRequest, null -> Unit // ignore observed M-SEARCH and junk
                }
            }
        }

        // Listener fan-out: mirror the change stream to registered callbacks.
        // Same source as SsdpClient.changes (no duplicate emission); collected on
        // the client's own scope so it's cancelled on close().
        scope.launch {
            registry.changes.collect { dispatchToListeners(it) }
        }

        // Network-change wiring (plan decision 4): reset the registry when the
        // LAN identity changes. Optional — only when a reachable-backed transport
        // stream is supplied by the platform factory.
        if (networkTransportTags != null) {
            NetworkMonitor(
                scope = scope,
                transportTags = networkTransportTags,
                subnetProbe = subnetProbe,
                onChange = { onNetworkChanged() },
            ).start()
        }
    }

    override suspend fun search(
        targets: Set<SearchTarget>,
        maxWaitSeconds: Int,
        timeout: Duration?,
    ) {
        if (closed.value) return
        searchMutex.withLock {
            // Replace any prior search.
            cancelRetransmitLocked()
            if (targets.isEmpty()) return@withLock

            val started = timeSource.markNow()
            retransmitJobs =
                targets.map { target ->
                    val request = MSearchRequest(target, maxWaitSeconds)
                    scope.launch {
                        // First M-SEARCH immediately, then the stepped cadence.
                        runCatching { socket.send(request.bytes()) }
                        // With a timeout, retransmission stops once it elapses —
                        // withTimeoutOrNull lets the coroutine complete cleanly
                        // (no thrown TimeoutCancellationException to surface).
                        // The socket stays joined and passive NOTIFY listening
                        // continues; only this target's broadcasting ends.
                        val retransmit: suspend () -> Unit = {
                            RetransmitScheduler.run(
                                elapsedSince = { started.elapsedNow() },
                                retransmit = { socket.send(request.bytes()) },
                            )
                        }
                        if (timeout == null) {
                            retransmit()
                        } else {
                            withTimeoutOrNull(timeout) { retransmit() }
                        }
                    }
                }
        }
    }

    override suspend fun stopSearch() {
        searchMutex.withLock { cancelRetransmitLocked() }
    }

    override suspend fun clearDevices() {
        if (closed.value) return
        registry.reset(DeviceChange.Removed.Reason.Cleared)
    }

    override fun addListener(listener: SsdpDeviceListener) {
        if (closed.value) return
        synchronized(listenerLock) {
            if (listener !in listeners) listeners.add(listener)
        }
    }

    override fun removeListener(listener: SsdpDeviceListener) {
        synchronized(listenerLock) { listeners.remove(listener) }
    }

    override suspend fun description(
        device: DiscoveredDevice,
        refresh: Boolean,
    ): DescriptionResult = if (closed.value) DescriptionResult.NotFound else descriptionService.describe(device, refresh)

    override suspend fun description(
        usn: String,
        refresh: Boolean,
    ): DescriptionResult {
        if (closed.value) return DescriptionResult.NotFound
        val device = registry.deviceSet.value[usn] ?: return DescriptionResult.NotFound
        return descriptionService.describe(device, refresh)
    }

    // Synchronous cache peeks — no fetch, no registry lookup for the usn form
    // (the cache is keyed by usn directly). Safe after close(): the snapshot just
    // stops changing. The device form ignores everything but the device's usn.
    override fun cachedDescription(device: DiscoveredDevice): DeviceDescription? = descriptionService.cachedDescription(device.usn)

    override fun cachedDescription(usn: String): DeviceDescription? = descriptionService.cachedDescription(usn)

    override fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return
        // Tear down the socket first so no further datagrams arrive, then close
        // the HTTP client, then cancel the client's own job (receive loop,
        // retransmit loops, expiry timers, description eviction collector).
        // Cancelling `job` — not the parent scope — leaves the caller's scope
        // (e.g. a runTest TestScope) untouched.
        runCatching { socket.close() }
        runCatching { httpClient.close() }
        job.cancel()
        synchronized(listenerLock) { listeners.clear() }
    }

    /**
     * Fan a single [change] out to the registered listeners. Snapshots the set
     * under [listenerLock], then invokes callbacks *outside* the lock so a
     * listener that (re-)registers or does its own locking can't deadlock, and a
     * throwing listener can't stall delivery to the others (or kill the
     * collector) — each call is guarded and logged.
     */
    private fun dispatchToListeners(change: DeviceChange) {
        val snapshot = synchronized(listenerLock) { listeners.toList() }
        if (snapshot.isEmpty()) return
        snapshot.forEach { listener ->
            runCatching {
                when (change) {
                    is DeviceChange.Found -> listener.onFound(change.device)
                    is DeviceChange.Updated -> listener.onUpdated(change.device)
                    is DeviceChange.Removed -> listener.onRemoved(change.device, change.reason)
                }
            }.onFailure { ssdpLog.w(it) { "SsdpDeviceListener threw; ignoring" } }
        }
    }

    /** Must hold [searchMutex]. */
    private fun cancelRetransmitLocked() {
        retransmitJobs.forEach { it.cancel() }
        retransmitJobs = emptyList()
    }

    /**
     * Reset the registry — invoked by the platform network-change wiring
     * (task #5) when the active network's identity changes. Exposed internally
     * so the platform `SsdpClient()` factory can connect a reachable-driven
     * trigger without widening the public API.
     */
    internal suspend fun onNetworkChanged() {
        if (closed.value) return
        registry.reset()
    }

    internal fun isActive(): Boolean = !closed.value && job.isActive
}

/**
 * Build a [SupervisorJob]-rooted scope for a client on the default dispatcher.
 * Factored out so platform factories share one scope shape.
 */
internal fun newClientScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
