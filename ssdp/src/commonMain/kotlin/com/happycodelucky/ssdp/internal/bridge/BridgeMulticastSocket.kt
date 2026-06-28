/*
 * ssdp-kmp — the emulator-side bridge transport.
 *
 * A [MulticastSocket] that does NO multicast: instead it opens a TCP connection
 * to a host-side bridge daemon (CLAUDE.md §9) and tunnels SSDP over it. On an
 * Android emulator inbound UDP multicast is dropped by the user-mode NAT, so the
 * real socket lives on the host; this class is the emulator's view of it.
 *
 *   send(bytes) → frame as DATAGRAM_OUT, write to the daemon (it re-multicasts).
 *   incoming    → every DATAGRAM_IN the daemon streams back (replies + NOTIFY).
 *
 * Because the engine's `MulticastSocket` contract is satisfied verbatim, the
 * shared `SsdpClientImpl` (registry, retransmit, parser, expiry) runs unchanged —
 * the emulator path is byte-identical to a physical device, only the transport
 * hop differs.
 *
 * Lifecycle mirrors the platform sockets (AppleMulticastSocket / JvmMulticast-
 * Socket): the socket owns its own SupervisorJob-rooted scope on Dispatchers.
 * Default and a connection loop that reconnects with capped backoff. `close()`
 * cancels that scope (LESSONS B-003 in spirit — own job, not the caller's).
 * Everything is `delay`-driven so the backoff runs under `runTest` virtual time
 * (LESSONS N-002); `send()` enqueues and never throws, matching the platform
 * sockets' "swallow transient send failure" so the retransmit scheduler is never
 * disrupted by a momentarily-absent daemon.
 */
package com.happycodelucky.ssdp.internal.bridge

import com.happycodelucky.ssdp.internal.Datagram
import com.happycodelucky.ssdp.internal.MulticastSocket
import com.happycodelucky.ssdp.internal.ssdpLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal class BridgeMulticastSocket(
    private val host: String,
    private val port: Int,
    private val connect: Connect = ktorTcpConnect,
    parentScope: CoroutineScope? = null,
) : MulticastSocket {
    // The socket's own scope. A child of `parentScope` when one is given (tests
    // pass backgroundScope so the perpetual connection loop is torn down with the
    // test — LESSONS B-005), otherwise self-rooted like the platform sockets.
    private val scope: CoroutineScope =
        if (parentScope != null) {
            CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[kotlinx.coroutines.Job]))
        } else {
            CoroutineScope(SupervisorJob() + Dispatchers.Default)
        }

    private val _incoming =
        MutableSharedFlow<Datagram>(
            replay = 0,
            extraBufferCapacity = 256,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    override val incoming: Flow<Datagram> = _incoming.asSharedFlow()

    // Outbound M-SEARCH bytes, queued so send() never blocks on the socket or
    // throws when the daemon is briefly absent. DROP_OLDEST: if a reconnect is
    // taking a while, stale retransmits are worthless — the next round resends.
    private val outbound =
        Channel<ByteArray>(
            capacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    init {
        scope.launch { connectionLoop() }
    }

    override suspend fun send(bytes: ByteArray) {
        // Best-effort enqueue; never throws (matches the platform sockets, so the
        // retransmit scheduler is never disrupted). trySend drops on a full/closed
        // channel — the next retransmit round will resend.
        outbound.trySend(bytes)
    }

    override fun close() {
        outbound.close()
        scope.cancel()
    }

    /**
     * Connect → pump both directions → on drop, backoff and reconnect. Runs for
     * the life of the socket (until [close] cancels the scope). The first
     * successful frame read resets the backoff.
     *
     * Logging is de-duped to one line per outage: the first failed connect of a
     * disconnected stretch warns (the daemon is probably not running), the retry
     * spam is silent, and a successful (re)connect after an outage logs an info
     * line. So a developer who forgot `mise run app:bridge` sees one clear hint in
     * logcat instead of either nothing or hundreds of lines.
     */
    private suspend fun connectionLoop() {
        var backoff = INITIAL_BACKOFF
        var loggedOutage = false
        while (scope.isActive) {
            try {
                val conn = connect.connect(host, port)
                if (loggedOutage) {
                    ssdpLog.i { "SSDP bridge connected to $host:$port" }
                    loggedOutage = false
                }
                try {
                    backoff = INITIAL_BACKOFF // connected — reset
                    pump(conn)
                } finally {
                    conn.close()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Daemon absent or connection dropped — no exception surfaces to
                // the app (the "no surprises" ethos); devices just stays empty
                // until the daemon appears. Warn once per outage (not per retry),
                // then back off and retry silently.
                if (!loggedOutage) {
                    loggedOutage = true
                    ssdpLog.w {
                        "SSDP bridge daemon unreachable at $host:$port — is it running? " +
                            "Start it with `mise run app:bridge`. Retrying…"
                    }
                }
            }
            if (!scope.isActive) break
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF)
        }
    }

    /**
     * Run the reader and writer concurrently over one connection. Returns (and
     * the connection is recycled) when either direction ends — `coroutineScope`
     * cancels the sibling once one throws.
     */
    private suspend fun pump(conn: DuplexConnection) {
        coroutineScope {
            // Reader: decode DATAGRAM_IN frames → emit to incoming.
            launch {
                while (true) {
                    val frame = BridgeFrameCodec.decode(conn)
                    if (frame.type == BridgeFrameCodec.DATAGRAM_IN) {
                        _incoming.emit(BridgeFrameCodec.decodeIn(frame.payload))
                    }
                    // Other frame types (e.g. OUT echoed back) are ignored.
                }
            }
            // Writer: drain queued M-SEARCH bytes → DATAGRAM_OUT frames.
            launch {
                for (bytes in outbound) {
                    conn.write(BridgeFrameCodec.encodeOut(bytes))
                }
            }
        }
    }

    private companion object {
        val INITIAL_BACKOFF: Duration = 200.milliseconds
        val MAX_BACKOFF: Duration = 5_000.milliseconds
    }
}
