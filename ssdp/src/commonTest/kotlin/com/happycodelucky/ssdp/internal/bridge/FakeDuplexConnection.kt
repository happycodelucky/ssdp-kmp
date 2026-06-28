/*
 * ssdp-kmp — in-memory DuplexConnection for bridge tests.
 *
 * Backs both directions with byte queues so the codec and BridgeMulticastSocket
 * run over a real DuplexConnection under runTest virtual time — no TCP, no ktor.
 * `toRead` is the stream the code-under-test reads (what the peer "sent");
 * `written` captures what the code-under-test wrote (what the peer "receives").
 *
 * readFully suspends until enough bytes are queued, matching the production
 * contract (ktor's readByteArray(n) suspends until exactly n bytes arrive). A
 * Mutex + CompletableDeferred wakeups keep `now` and `delay` in lockstep with the
 * test scheduler.
 */
package com.happycodelucky.ssdp.internal.bridge

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class FakeDuplexConnection : DuplexConnection {
    private val lock = Mutex()
    private val readBuffer = ArrayDeque<Byte>()
    private var waiter: CompletableDeferred<Unit>? = null

    /** Everything the code-under-test has written, in order. */
    val written = mutableListOf<ByteArray>()

    var closed = false
        private set

    /** Flatten all writes into one stream (frames may span calls). */
    val writtenBytes: ByteArray
        get() = written.fold(ByteArray(0)) { acc, b -> acc + b }

    /** Queue bytes for the code-under-test to read (simulates the peer sending). */
    suspend fun feed(bytes: ByteArray) {
        lock.withLock {
            readBuffer.addAll(bytes.toList())
            waiter?.complete(Unit)
            waiter = null
        }
    }

    override suspend fun readFully(count: Int): ByteArray {
        val out = ByteArray(count)
        var filled = 0
        while (filled < count) {
            val wake =
                lock.withLock {
                    while (filled < count && readBuffer.isNotEmpty()) {
                        out[filled++] = readBuffer.removeFirst()
                    }
                    if (filled < count) {
                        if (closed) throw BridgeConnectionClosed
                        CompletableDeferred<Unit>().also { waiter = it }
                    } else {
                        null
                    }
                }
            wake?.await()
        }
        return out
    }

    override suspend fun write(bytes: ByteArray) {
        written.add(bytes)
    }

    override fun close() {
        closed = true
        waiter?.complete(Unit)
        waiter = null
    }

    private object BridgeConnectionClosed : RuntimeException("fake connection closed")
}
