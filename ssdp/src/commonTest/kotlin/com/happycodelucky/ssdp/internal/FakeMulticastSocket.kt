/*
 * ssdp-kmp — in-module fake socket for driving SsdpClientImpl under virtual time.
 *
 * Lives in :ssdp's commonTest (not :ssdp-testing) because MulticastSocket is
 * internal to :ssdp. Tests push raw wire strings through [deliver] and capture
 * everything the client sends via [sent]. Sends are recorded under a lock, so a
 * test may drive the client from real, concurrent dispatchers.
 */
package com.happycodelucky.ssdp.internal

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * @param now the time each send is stamped with in [sentAt]; pass the test
 *   scheduler's `currentTime` to assert the retransmit cadence.
 */
internal class FakeMulticastSocket(private val now: () -> Long = { 0L }) : MulticastSocket {
    private val _incoming = MutableSharedFlow<Datagram>(replay = 0, extraBufferCapacity = 256)
    override val incoming = _incoming.asSharedFlow()

    private val lock = SynchronizedObject()
    private val sends = mutableListOf<Pair<Long, ByteArray>>()

    /** Every payload sent, in order. A snapshot. */
    val sent: List<ByteArray> get() = synchronized(lock) { sends.map { it.second } }

    var closed = false
        private set

    /** Sent message payloads decoded to text, for assertions. */
    val sentText: List<String> get() = sent.map { it.decodeToString() }

    /** The [now] stamps of every M-SEARCH sent for [searchTarget] (its `ST` value), in order. */
    fun sentAt(searchTarget: String): List<Long> =
        synchronized(lock) {
            sends.filter { (_, bytes) -> bytes.decodeToString().contains("ST: $searchTarget\r\n") }.map { it.first }
        }

    override suspend fun send(bytes: ByteArray) {
        val stamp = now()
        synchronized(lock) { sends.add(stamp to bytes) }
    }

    override fun close() {
        closed = true
    }

    /** Push a raw SSDP wire message to the client's receive loop. */
    suspend fun deliver(raw: String, source: String = "192.168.1.10:1900") {
        _incoming.emit(Datagram(text = raw, source = source))
    }
}
