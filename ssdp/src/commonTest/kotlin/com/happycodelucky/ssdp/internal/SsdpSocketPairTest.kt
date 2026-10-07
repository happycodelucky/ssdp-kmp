/*
 * ssdp-kmp — the two-socket transport: M-SEARCH goes out on the ephemeral-port
 * socket, its unicast replies and the 1900 socket's NOTIFYs both reach the one
 * parser, and teardown closes both halves. Fakes stand in for each half, so this
 * runs under runTest virtual time on every target (the real sockets are covered
 * by the JVM and Apple platform tests).
 */
package com.happycodelucky.ssdp.internal

import com.happycodelucky.ssdp.SearchTarget
import com.happycodelucky.ssdp.SsdpError
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondBadRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SsdpSocketPairTest {
    private val sonosUsn = "uuid:RINCON_000E58A1B2C300400::urn:schemas-upnp-org:device:ZonePlayer:1"

    private fun TestScope.newClient(transport: MulticastSocket): SsdpClientImpl =
        SsdpClientImpl(
            socketFactory = { transport },
            parentScope = this,
            clock = TestClock(testScheduler),
            timeSource = TestTimeSource(testScheduler),
            httpClient = HttpClient(MockEngine { respondBadRequest() }),
        )

    @Test
    fun sendGoesOutOnTheSearchSocketOnly() =
        runTest {
            val notify = FakeMulticastSocket()
            val search = FakeMulticastSocket()
            val pair = SsdpSocketPair(notifySocket = notify, searchSocket = search)

            pair.send("M-SEARCH".encodeToByteArray())

            assertEquals(listOf("M-SEARCH"), search.sentText)
            assertTrue(notify.sent.isEmpty())
        }

    @Test
    fun incomingMergesBothSockets() =
        runTest {
            val notify = FakeMulticastSocket()
            val search = FakeMulticastSocket()
            val pair = SsdpSocketPair(notifySocket = notify, searchSocket = search)
            val received = mutableListOf<Datagram>()
            val collector = launch { pair.incoming.take(2).toList(received) }
            runCurrent()

            notify.deliver("notify", source = "192.168.1.2:1900")
            search.deliver("reply", source = "192.168.1.3:1900")
            collector.join()

            assertEquals(setOf("notify", "reply"), received.map { it.text }.toSet())
        }

    @Test
    fun closeClosesBothSockets() {
        val notify = FakeMulticastSocket()
        val search = FakeMulticastSocket()

        SsdpSocketPair(notifySocket = notify, searchSocket = search).close()

        assertTrue(notify.closed)
        assertTrue(search.closed)
    }

    @Test
    fun failedSearchSocketClosesTheNotifySocket() {
        val notify = FakeMulticastSocket()
        val failure = SsdpError.TransportFailed(details = "no ephemeral port")

        val thrown =
            assertFailsWith<SsdpError.TransportFailed> {
                openSsdpSocketPair(openNotify = { notify }, openSearch = { throw failure })
            }

        assertSame(failure, thrown)
        assertTrue(notify.closed)
    }

    @Test
    fun clientSendsMSearchOnTheSearchSocketAndIngestsItsUnicastReply() =
        runTest {
            val notify = FakeMulticastSocket()
            val search = FakeMulticastSocket()
            val client = newClient(SsdpSocketPair(notifySocket = notify, searchSocket = search))
            runCurrent()

            client.search(setOf(SearchTarget.All))
            runCurrent()
            assertTrue(search.sentText.single().startsWith("M-SEARCH"))
            assertTrue(notify.sent.isEmpty(), "M-SEARCH must not leave from the shared 1900 socket")

            // A device answers the M-SEARCH by unicast to its source port — the
            // search socket — and the reply lands in the registry.
            search.deliver(Fixtures.MSEARCH_RESPONSE_SONOS)
            runCurrent()
            assertEquals(setOf(sonosUsn), client.devices.value.keys)

            client.close()
            assertTrue(search.closed)
            assertTrue(notify.closed)
        }

    @Test
    fun clientStillHearsNotifyOnTheNotifySocket() =
        runTest {
            val notify = FakeMulticastSocket()
            val search = FakeMulticastSocket()
            val client = newClient(SsdpSocketPair(notifySocket = notify, searchSocket = search))
            runCurrent()

            notify.deliver(Fixtures.NOTIFY_ALIVE_ROKU)
            runCurrent()

            assertEquals(1, client.devices.value.size)
            client.close()
        }
}
