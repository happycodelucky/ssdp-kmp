/*
 * ssdp-kmp — description fetch + cache (v1.1 feature).
 *
 * One collaborator owning the Ktor HTTP client, the XML parser, and the cache.
 * Folds the design's three concerns:
 *   - lazy fetch+parse of a device's LOCATION description document,
 *   - an in-memory cache keyed by USN, evicted via the registry's change stream,
 *   - in-flight de-duplication so N concurrent callers cause ONE HTTP fetch.
 *
 * Concurrency (CLAUDE.md §6): a single Mutex guards the two maps. The
 * `scope.async {}` launch is non-suspending, so it is safe to start under the
 * lock; the `await()` always happens OUTSIDE the lock, so a slow fetch never
 * blocks other USNs. Clock is injected for runTest virtual time (negative-TTL).
 */
package com.happycodelucky.ssdp.internal

import com.happycodelucky.ssdp.DescriptionResult
import com.happycodelucky.ssdp.DeviceChange
import com.happycodelucky.ssdp.DeviceDescription
import com.happycodelucky.ssdp.DiscoveredDevice
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * @param scope the client's child scope; fetch coroutines and the eviction
 *   collector run here and are cancelled on client close.
 * @param clock "now" for negative-cache expiry; inject a virtual clock in tests.
 * @param httpClient Ktor client; owned and closed by [SsdpClientImpl], passed in.
 * @param registryChanges the registry's change stream — Removed events (byebye,
 *   expiry, AND network reset, which emits Removed(_, NetworkChanged) per device)
 *   evict the matching cache entry through one collector.
 * @param parser injected so tests can supply a fake; defaults to the xmlutil one.
 * @param negativeTtl how long a failed fetch is remembered to avoid hammering a
 *   dead/broken device. Successes never self-expire — they die with the device.
 */
internal class DescriptionService(
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val httpClient: HttpClient,
    registryChanges: SharedFlow<DeviceChange>,
    private val parser: DescriptionParser = XmlDescriptionParser,
    private val negativeTtl: Duration = 30.seconds,
) {
    private val mutex = Mutex()
    private val entries = mutableMapOf<String, Entry>() // key = usn
    private val inFlight = mutableMapOf<String, Deferred<DescriptionResult>>() // key = usn

    // Lock-free snapshot of successfully-parsed descriptions (usn -> DeviceDescription),
    // mirrored from [entries] on every mutation while the [mutex] is held. Enables a
    // synchronous, non-suspending cache peek ([cachedDescription]) without locking —
    // the same immutable-snapshot-swapped-atomically pattern the registry uses. Only
    // Success descriptions are mirrored; failures and negative-cache entries are not.
    private val successSnapshot = atomic<Map<String, DeviceDescription>>(emptyMap())

    init {
        // One collector handles ALL eviction: byebye, max-age expiry, and
        // network reset (reset() emits Removed(_, NetworkChanged) per device).
        scope.launch {
            registryChanges.collect { change ->
                if (change is DeviceChange.Removed) evict(change.device.usn)
            }
        }
    }

    /**
     * Get (or fetch+parse+cache) the description for [device]. Concurrent calls
     * for the same USN share one fetch. Returns [DescriptionResult.NotFound] when
     * the device has no LOCATION.
     *
     * @param refresh when true, bypass a valid cached result and force a fresh
     *   fetch. The refetch still coalesces with any in-flight fetch for this USN
     *   (a concurrent refresh won't cause two HTTP GETs). On success the cache is
     *   replaced; on failure the previous cached [DescriptionResult.Success] (if
     *   any) is left intact rather than clobbered by a transient error — see
     *   [fetchParseAndStore].
     */
    suspend fun describe(
        device: DiscoveredDevice,
        refresh: Boolean = false,
    ): DescriptionResult {
        val location = device.location ?: return DescriptionResult.NotFound
        val usn = device.usn

        val deferred =
            mutex.withLock {
                // A valid cache entry short-circuits the fetch — UNLESS refresh is
                // requested, in which case we fall straight through to (join or
                // start) a fetch, leaving the stale entry in place for now so a
                // failed refresh can preserve it.
                if (!refresh) {
                    when (val cached = entries[usn]) {
                        is Entry.Success -> {
                            if (cached.sourceUrl == location) {
                                return cached.result // still the same URL → serve cached success.
                            } else {
                                entries.remove(usn) // device moved (new LOCATION) → stale, drop.
                                successSnapshot.value = successSnapshot.value - usn
                            }
                        }

                        is Entry.Failure -> {
                            if (cached.sourceUrl == location && clock.now() < cached.expiresAt) {
                                return cached.result // negative cache still valid → don't re-hit.
                            } else {
                                entries.remove(usn)
                            }
                        }

                        null -> {
                            // No cache entry — fall through to start a fetch below.
                        }
                    }
                }

                // Join an in-flight fetch, or start one. `async` is non-suspending
                // so starting it under the lock is safe.
                inFlight.getOrPut(usn) {
                    scope.async { fetchParseAndStore(usn, location, refresh) }
                }
            }

        // await() OUTSIDE the lock — a slow fetch must not block other USNs.
        return deferred.await()
    }

    /**
     * The already-fetched, successfully-parsed description for [usn], or `null`
     * if none is cached (never fetched, fetch failed, or evicted). Synchronous
     * and non-suspending — reads the lock-free [successSnapshot]; never triggers
     * a fetch.
     */
    fun cachedDescription(usn: String): DeviceDescription? = successSnapshot.value[usn]

    private suspend fun fetchParseAndStore(
        usn: String,
        location: String,
        refresh: Boolean,
    ): DescriptionResult {
        val result = fetchAndParse(location)
        mutex.withLock {
            // Only publish if this fetch is still the one of record (a concurrent
            // evict()/reset may have cancelled+removed our slot).
            if (inFlight.remove(usn) != null) {
                when (result) {
                    is DescriptionResult.Success -> {
                        entries[usn] = Entry.Success(location, result)
                        successSnapshot.value = successSnapshot.value + (usn to result.description)
                    }

                    else -> {
                        // A failed *refresh* must not clobber a still-valid cached
                        // Success (a transient blip shouldn't evict good data); leave
                        // the existing entry untouched. A failed *initial* fetch (no
                        // prior Success) records a negative-cache entry as before.
                        val keepPriorSuccess = refresh && entries[usn] is Entry.Success
                        if (!keepPriorSuccess) {
                            entries[usn] = Entry.Failure(location, result, clock.now() + negativeTtl)
                        }
                    }
                }
            }
        }
        return result
    }

    private suspend fun fetchAndParse(location: String): DescriptionResult {
        // [runCancellable] rethrows CancellationException and routes any other
        // throwable to its onError mapping — so cooperative cancellation always
        // propagates (CLAUDE.md §6) from a single throw site.
        val response =
            runCancellable({ httpClient.get(location) }) { e ->
                return DescriptionResult.FetchFailed(statusCode = null, message = e.message ?: "fetch error")
            }
        if (!response.status.isSuccess()) {
            return DescriptionResult.FetchFailed(statusCode = response.status.value, message = response.status.description)
        }
        val body =
            runCancellable({ response.bodyAsText() }) { e ->
                return DescriptionResult.FetchFailed(statusCode = null, message = e.message ?: "read error")
            }
        // Many LAN devices answer a path-less LOCATION (or a health endpoint) with
        // a 200 that is NOT an XML description — e.g. a plain `status=ok`. Handing
        // that to the XML parser yields a cryptic "1:N - Non-whitespace text..."
        // error. Sniff first: a UPnP description always opens with `<` (a BOM and
        // leading whitespace aside). If it doesn't, report a clear ParseFailed and
        // skip the parse; a genuinely-XML body still flows through, so a real
        // malformed document keeps the parser's line:col detail (useful to debug).
        if (!looksLikeXml(body)) {
            return DescriptionResult.ParseFailed(
                message = "response was not an XML document (got: ${snippet(body)})",
            )
        }
        return runCancellable({ DescriptionResult.Success(parser.parse(body, sourceUrl = location)) }) { e ->
            DescriptionResult.ParseFailed(message = e.message ?: "invalid description XML")
        }
    }

    /**
     * True if [body]'s first meaningful character is `<` — i.e. it looks like an
     * XML document. Tolerates a leading UTF-8 BOM and any leading whitespace
     * (some devices emit a blank line or BOM before the `<?xml` prolog).
     */
    private fun looksLikeXml(body: String): Boolean =
        body
            .removePrefix(BOM)
            .trimStart()
            .startsWith('<')

    /** A short, single-line excerpt of [body] for an error message. */
    private fun snippet(body: String): String {
        val oneLine = body.removePrefix(BOM).trim().replace(Regex("\\s+"), " ")
        return if (oneLine.length > SNIPPET_MAX) oneLine.take(SNIPPET_MAX) + "…" else oneLine
    }

    /**
     * Run [block], rethrowing [CancellationException] (so cooperative
     * cancellation propagates) and routing any other throwable to [onError].
     * One throw site for the whole fetch/parse path.
     */
    private suspend inline fun <T> runCancellable(
        block: () -> T,
        onError: (Throwable) -> T,
    ): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onError(e)
        }

    /**
     * Evict a USN: drop its cached entry and detach any in-flight fetch from the
     * cache so its result won't be stored (a later call refetches).
     *
     * We deliberately do NOT cancel the in-flight `Deferred`: callers already
     * awaiting it would then receive a raw `JobCancellationException` instead of
     * a clean [DescriptionResult]. Removing the `inFlight` slot is enough —
     * [fetchParseAndStore] checks slot ownership before caching, so an evicted
     * fetch completes for its awaiters but its result is simply not cached. The
     * detached coroutine finishes quickly (the HTTP request is already in flight)
     * and is reaped by the scope on close.
     */
    private suspend fun evict(usn: String) {
        mutex.withLock {
            inFlight.remove(usn)
            entries.remove(usn)
            successSnapshot.value = successSnapshot.value - usn
        }
    }

    private sealed interface Entry {
        val sourceUrl: String

        data class Success(
            override val sourceUrl: String,
            val result: DescriptionResult.Success,
        ) : Entry

        data class Failure(
            override val sourceUrl: String,
            val result: DescriptionResult,
            val expiresAt: Instant,
        ) : Entry
    }

    private companion object {
        /** Max characters of a non-XML body echoed back in a [DescriptionResult.ParseFailed]. */
        const val SNIPPET_MAX = 40

        /** UTF-8 byte-order mark some devices prepend to the description document. */
        const val BOM = "\uFEFF"
    }
}
