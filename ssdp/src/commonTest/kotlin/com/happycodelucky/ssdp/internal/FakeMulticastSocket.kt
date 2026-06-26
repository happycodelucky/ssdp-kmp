/*
 * ssdp-kmp — in-module fake socket for driving SsdpClientImpl under virtual time.
 *
 * Lives in :ssdp's commonTest (not :ssdp-testing) because MulticastSocket is
 * internal to :ssdp. Tests push raw wire strings through [deliver] and capture
 * everything the client sends via [sent].
 */
package com.happycodelucky.ssdp.internal

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal class FakeMulticastSocket : MulticastSocket {
    private val _incoming = MutableSharedFlow<Datagram>(replay = 0, extraBufferCapacity = 256)
    override val incoming = _incoming.asSharedFlow()

    val sent = mutableListOf<ByteArray>()
    var closed = false
        private set

    /** Sent message payloads decoded to text, for assertions. */
    val sentText: List<String> get() = sent.map { it.decodeToString() }

    override suspend fun send(bytes: ByteArray) {
        sent.add(bytes)
    }

    override fun close() {
        closed = true
    }

    /** Push a raw SSDP wire message to the client's receive loop. */
    suspend fun deliver(
        raw: String,
        source: String = "192.168.1.10:1900",
    ) {
        _incoming.emit(Datagram(text = raw, source = source))
    }
}
