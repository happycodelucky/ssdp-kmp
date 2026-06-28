/*
 * ssdp-kmp — BridgeMulticastSocket tests under virtual time.
 *
 * The socket tunnels SSDP over a DuplexConnection; we inject a fake connection
 * (no TCP) and drive everything with runTest's scheduler. Covers: send() frames a
 * DATAGRAM_OUT; an inbound DATAGRAM_IN surfaces on `incoming`; and a dropped
 * connection triggers a backoff-delayed reconnect while `incoming` survives.
 *
 * The connection loop is perpetual, so the socket runs on `backgroundScope` and
 * `incoming` is observed via Turbine — a bare collect on the TestScope would hang
 * runTest (LESSONS B-005). Backoff is delay-driven, so virtual time advances it.
 */
package com.happycodelucky.ssdp.internal.bridge

import app.cash.turbine.test
import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class BridgeMulticastSocketTest {
    /**
     * A [Connect] that hands out fake connections in order and records how many
     * times it was asked to connect (so a reconnect is observable). Each call
     * waits on a gate so the test controls when the connection "succeeds".
     */
    private class FakeConnector {
        val connections = mutableListOf<FakeDuplexConnection>()
        var connectCount = 0
            private set

        val connect =
            Connect { _, _ ->
                connectCount++
                FakeDuplexConnection().also { connections.add(it) }
            }
    }

    @Test
    fun sendFramesOutboundMSearch() =
        runTest {
            val connector = FakeConnector()
            val socket =
                BridgeMulticastSocket(
                    host = "10.0.2.2",
                    port = 1901,
                    connect = connector.connect,
                    parentScope = backgroundScope,
                )
            runCurrent() // let the connection loop connect.
            assertEquals(1, connector.connectCount)

            val mSearch = "M-SEARCH * HTTP/1.1\r\n\r\n".encodeToByteArray()
            socket.send(mSearch)
            runCurrent()

            val conn = connector.connections.single()
            // One DATAGRAM_OUT frame written, wrapping the M-SEARCH verbatim.
            assertEquals(BridgeFrameCodec.DATAGRAM_OUT, conn.writtenBytes[0])
            // Decode it back through a fresh fake to confirm the payload.
            val echo = FakeDuplexConnection()
            echo.feed(conn.writtenBytes)
            val decoded = BridgeFrameCodec.decode(echo)
            assertTrue(mSearch.contentEquals(decoded.payload))

            socket.close()
        }

    @Test
    fun inboundDatagramSurfacesOnIncoming() =
        runTest {
            val connector = FakeConnector()
            val socket =
                BridgeMulticastSocket(
                    host = "10.0.2.2",
                    port = 1901,
                    connect = connector.connect,
                    parentScope = backgroundScope,
                )
            runCurrent()
            val conn = connector.connections.single()

            socket.incoming.test {
                conn.feed(
                    BridgeFrameCodec.encodeIn(
                        source = "192.168.1.50:1900",
                        text = "HTTP/1.1 200 OK\r\nUSN: uuid:abc\r\n\r\n",
                    ),
                )
                val datagram = awaitItem()
                assertEquals("192.168.1.50:1900", datagram.source)
                assertTrue(datagram.text.contains("USN: uuid:abc"))
                cancelAndIgnoreRemainingEvents()
            }

            socket.close()
        }

    @Test
    fun reconnectsAfterDroppedConnectionWithBackoff() =
        runTest {
            val connector = FakeConnector()
            val socket =
                BridgeMulticastSocket(
                    host = "10.0.2.2",
                    port = 1901,
                    connect = connector.connect,
                    parentScope = backgroundScope,
                )
            runCurrent()
            assertEquals(1, connector.connectCount)

            // Drop the first connection — the read loop sees EOF and the loop backs off.
            connector.connections.first().close()
            runCurrent()
            // No immediate reconnect (backoff hasn't elapsed).
            assertEquals(1, connector.connectCount)

            // After the initial 200ms backoff, it reconnects.
            advanceTimeBy(250.milliseconds)
            runCurrent()
            assertEquals(2, connector.connectCount)

            // The new connection works: an inbound datagram still surfaces.
            socket.incoming.test {
                connector.connections.last().feed(
                    BridgeFrameCodec.encodeIn("10.0.0.9:1900", "NOTIFY * HTTP/1.1\r\n\r\n"),
                )
                val datagram = awaitItem()
                assertEquals("10.0.0.9:1900", datagram.source)
                cancelAndIgnoreRemainingEvents()
            }

            socket.close()
        }

    @Test
    fun sendBeforeConnectDoesNotThrow() =
        runTest {
            // A connector that never completes its first connect (daemon absent).
            val gate = CompletableDeferred<Unit>()
            val neverConnects =
                Connect { _, _ ->
                    gate.await() // suspends forever within this test
                    FakeDuplexConnection()
                }
            val socket =
                BridgeMulticastSocket(
                    host = "10.0.2.2",
                    port = 1901,
                    connect = neverConnects,
                    parentScope = backgroundScope,
                )
            runCurrent()
            // send must not throw even though no connection exists yet.
            socket.send("M-SEARCH".encodeToByteArray())
            runCurrent()
            socket.close()
        }

    @Test
    fun warnsOnceWhileDaemonDownThenInfoOnReconnect() =
        runTest {
            val capture = CapturingLogWriter()
            withGlobalLogWriter(capture) {
                // Fail the first 3 connects (daemon down), then succeed.
                var attempts = 0
                val conn = FakeDuplexConnection()
                val connector =
                    Connect { _, _ ->
                        if (attempts++ < 3) error("connection refused") else conn
                    }
                val socket =
                    BridgeMulticastSocket(
                        host = "10.0.2.2",
                        port = 1901,
                        connect = connector,
                        parentScope = backgroundScope,
                    )
                // Drive through the 3 failed attempts (backoff 200ms, 400ms, 800ms).
                repeat(4) {
                    runCurrent()
                    advanceTimeBy(1_000.milliseconds)
                }
                runCurrent()

                // Exactly ONE warning for the whole outage (not one per retry),
                // and one info line once it finally connected.
                assertEquals(
                    1,
                    capture.warnings.count { it.contains("bridge daemon unreachable") },
                    "expected a single de-duped outage warning, got: ${capture.warnings}",
                )
                assertEquals(
                    1,
                    capture.infos.count { it.contains("bridge connected") },
                    "expected one reconnect info line, got: ${capture.infos}",
                )

                socket.close()
            }
        }

    /** Captures log lines emitted to Kermit's global logger during a test. */
    private class CapturingLogWriter : LogWriter() {
        val warnings = mutableListOf<String>()
        val infos = mutableListOf<String>()

        override fun log(
            severity: Severity,
            message: String,
            tag: String,
            throwable: Throwable?,
        ) {
            when (severity) {
                Severity.Warn -> warnings.add(message)
                Severity.Info -> infos.add(message)
                else -> Unit
            }
        }
    }

    /** Run [block] with [writer] attached to the global Kermit logger, then restore. */
    private inline fun withGlobalLogWriter(
        writer: LogWriter,
        block: () -> Unit,
    ) {
        Logger.addLogWriter(writer)
        try {
            block()
        } finally {
            // Restore the default platform writer so the capture doesn't leak
            // into other tests (Kermit's global config is process-wide).
            Logger.setLogWriters(co.touchlab.kermit.platformLogWriter())
        }
    }
}
