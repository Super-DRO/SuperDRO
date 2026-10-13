package org.superdro.core.dataoutput

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.util.Clock

/** The TCP server with fake sockets and virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class TcpOutputServerTest {

    private class Setup(
        val server: TcpOutputServer,
        val sockets: FakeSocketFactory,
        val errors: MutableList<DataOutputError>,
        val writes: MutableList<Boolean>,
        var hosts: List<String>,
    )

    private fun TestScope.setup(
        security: StreamSecurity = PlainTextSecurity,
        hosts: List<String> = listOf("192.168.1.20"),
    ): Setup {
        val sockets = FakeSocketFactory()
        val errors = mutableListOf<DataOutputError>()
        val writes = mutableListOf<Boolean>()
        lateinit var setup: Setup
        val server = TcpOutputServer(
            scope = backgroundScope,
            sockets = sockets,
            security = security,
            bindHosts = { setup.hosts },
            port = 5800,
            clock = Clock { testScheduler.currentTime },
            onError = { error -> errors += error },
            onWrite = { success -> writes += success },
        )
        setup = Setup(server, sockets, errors, writes, hosts)
        return setup
    }

    private fun record(text: String) = text.encodeToByteArray()

    @Test
    fun theServerHasNoSocketBeforeTheStartAndAfterTheStop() = runTest {
        val setup = setup()
        runCurrent()
        assertTrue(setup.sockets.sockets.isEmpty())
        assertEquals(SenderState.Stopped, setup.server.state.value)
        setup.server.start()
        runCurrent()
        val socket = setup.sockets.open.single()
        assertEquals("192.168.1.20", socket.host)
        assertEquals(5800, socket.port)
        assertEquals(SenderState.Ready, setup.server.state.value)
        assertEquals(listOf("192.168.1.20:5800"), setup.server.listening.value)
        setup.server.stop()
        assertTrue(socket.closed)
        assertEquals(SenderState.Stopped, setup.server.state.value)
        assertTrue(setup.server.listening.value.isEmpty())
    }

    @Test
    fun theServerListensOnEachSelectedAddress() = runTest {
        val setup = setup(hosts = listOf("192.168.1.20", "fe80::1%wlan0"))
        setup.server.start()
        runCurrent()
        assertEquals(listOf("192.168.1.20", "fe80::1%wlan0"), setup.sockets.open.map { socket -> socket.host })
        assertEquals(listOf("192.168.1.20:5800", "[fe80::1%wlan0]:5800"), setup.server.listening.value)
    }

    @Test
    fun eachClientGetsTheRecordsWithNoFirstMessage() = runTest {
        val setup = setup()
        setup.server.start()
        runCurrent()
        val socket = setup.sockets.open.single()
        val first = socket.connect("192.168.1.30")
        val second = socket.connect("192.168.1.31")
        runCurrent()
        assertEquals(SenderState.Active, setup.server.state.value)
        assertEquals(listOf("192.168.1.30", "192.168.1.31"), setup.server.clients.value)
        assertEquals("", first.receivedText)
        setup.server.publish(record("a\n"))
        runCurrent()
        assertEquals("a\n", first.receivedText)
        assertEquals("a\n", second.receivedText)
        assertEquals(listOf(true, true), setup.writes)
    }

    @Test
    fun theSlotOfAClientHasOnlyTheNewestRecord() = runTest {
        val setup = setup()
        setup.server.start()
        runCurrent()
        val client = setup.sockets.open.single().connect()
        runCurrent()
        // The idle writer takes record 1 at once. Record 3 replaces record 2 in the slot.
        setup.server.publish(record("1\n"))
        setup.server.publish(record("2\n"))
        setup.server.publish(record("3\n"))
        runCurrent()
        assertEquals("1\n3\n", client.receivedText)
        // A new client gets the next record, not an old one.
        val late = setup.sockets.open.single().connect("192.168.1.40")
        runCurrent()
        assertEquals("", late.receivedText)
        setup.server.publish(record("4\n"))
        runCurrent()
        assertEquals("4\n", late.receivedText)
        assertEquals("1\n3\n4\n", client.receivedText)
    }

    @Test
    fun theInboundBytesAreDiscardedAndTheEndOfTheStreamClosesTheClient() = runTest {
        val setup = setup()
        setup.server.start()
        runCurrent()
        val client = setup.sockets.open.single().connect()
        runCurrent()
        client.send("#Z;\n".encodeToByteArray())
        runCurrent()
        assertFalse(client.closed)
        assertEquals("", client.receivedText)
        client.disconnect()
        runCurrent()
        assertTrue(client.closed)
        assertTrue(setup.server.clients.value.isEmpty())
        assertEquals(SenderState.Ready, setup.server.state.value)
    }

    @Test
    fun aWriteErrorClosesTheClientAndCountsAFailure() = runTest {
        val setup = setup()
        setup.server.start()
        runCurrent()
        val client = setup.sockets.open.single().connect("192.168.1.30")
        runCurrent()
        client.writeFailure = IllegalStateException("Broken pipe")
        setup.server.publish(record("a\n"))
        runCurrent()
        assertTrue(client.closed)
        assertEquals(listOf(false), setup.writes)
        val error = setup.errors.single()
        assertEquals(DataOutputErrorType.PublishFailed, error.type)
        assertEquals("192.168.1.30", error.host)
        assertEquals("Broken pipe", error.detail)
        assertEquals(SenderState.Ready, setup.server.state.value)
    }

    @Test
    fun theStopClosesTheServerAndAllClientsAtOnce() = runTest {
        val setup = setup()
        setup.server.start()
        runCurrent()
        val socket = setup.sockets.open.single()
        val clients = listOf(socket.connect("192.168.1.30"), socket.connect("192.168.1.31"))
        runCurrent()
        setup.server.stop()
        // No runCurrent: the sockets are closed when stop() returns.
        assertTrue(socket.closed)
        assertTrue(clients.all { client -> client.closed })
        assertTrue(setup.server.clients.value.isEmpty())
        setup.server.publish(record("a\n"))
        runCurrent()
        assertTrue(clients.all { client -> client.receivedText.isEmpty() })
    }

    @Test
    fun anAcceptErrorGivesFailedAMessageAndANewServerAfter5Seconds() = runTest {
        val setup = setup()
        setup.server.start()
        runCurrent()
        val socket = setup.sockets.open.single()
        val client = socket.connect()
        runCurrent()
        socket.failAccept(IllegalStateException("Too many open files"))
        runCurrent()
        val state = assertIs<SenderState.Failed>(setup.server.state.value)
        assertEquals(DataOutputErrorType.AcceptFailed, state.error.type)
        assertEquals("Too many open files", state.error.detail)
        assertEquals(state.error.atMs + 5000L, state.retryAtMs)
        assertEquals(state.error, setup.errors.last())
        assertTrue(socket.closed)
        assertTrue(client.closed)
        assertTrue(setup.sockets.open.isEmpty())
        advanceTimeBy(4999L)
        runCurrent()
        assertTrue(setup.sockets.open.isEmpty())
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(2, setup.sockets.sockets.size)
        assertEquals(1, setup.sockets.open.size)
        assertEquals(SenderState.Ready, setup.server.state.value)
    }

    @Test
    fun aBindErrorGivesFailedAndARetryAfter5Seconds() = runTest {
        val setup = setup()
        setup.sockets.bindFailure = { _, port -> IllegalStateException("Address already in use: $port") }
        setup.server.start()
        runCurrent()
        val state = assertIs<SenderState.Failed>(setup.server.state.value)
        assertEquals(DataOutputErrorType.PortBindFailed, state.error.type)
        assertEquals("192.168.1.20", state.error.host)
        assertEquals(5800, state.error.port)
        assertEquals("Address already in use: 5800", state.error.detail)
        assertEquals(1, setup.errors.size)
        setup.sockets.bindFailure = { _, _ -> null }
        advanceTimeBy(5000L)
        runCurrent()
        assertEquals(SenderState.Ready, setup.server.state.value)
    }

    @Test
    fun noAddressGivesFailedUntilTheInterfaceHasAnAddress() = runTest {
        val setup = setup(hosts = emptyList())
        setup.server.start()
        runCurrent()
        assertEquals(DataOutputErrorType.NoAddress, assertIs<SenderState.Failed>(setup.server.state.value).error.type)
        setup.hosts = listOf("192.168.1.20")
        advanceTimeBy(5000L)
        runCurrent()
        assertEquals(SenderState.Ready, setup.server.state.value)
        assertEquals("192.168.1.20", setup.sockets.open.single().host)
    }

    @Test
    fun onlyOneOfTwoAddressesCanBindAndTheServerRuns() = runTest {
        val setup = setup(hosts = listOf("192.168.1.20", "fe80::1%wlan0"))
        setup.sockets.bindFailure = { host, _ -> if (':' in host) IllegalStateException("Cannot assign requested address") else null }
        setup.server.start()
        runCurrent()
        assertEquals(SenderState.Ready, setup.server.state.value)
        assertEquals(listOf("192.168.1.20:5800"), setup.server.listening.value)
        assertEquals(DataOutputErrorType.PortBindFailed, setup.errors.single().type)
    }

    @Test
    fun aClientThatFailsTheHandshakeGetsNoRecord() = runTest {
        val refuse = StreamSecurity { link -> throw HandshakeException("TLS alert decrypt_error from ${link.remoteHost}") }
        val setup = setup(security = refuse)
        setup.server.start()
        runCurrent()
        val client = setup.sockets.open.single().connect("192.168.1.66")
        runCurrent()
        setup.server.publish(record("a\n"))
        runCurrent()
        assertTrue(client.closed)
        assertEquals("", client.receivedText)
        assertTrue(setup.server.clients.value.isEmpty())
        val error = setup.errors.single()
        assertEquals(DataOutputErrorType.HandshakeFailed, error.type)
        assertEquals("192.168.1.66", error.host)
        assertEquals(SenderState.Ready, setup.server.state.value)
    }
}
