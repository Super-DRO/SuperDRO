package org.superdro.core.connection.tcp

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readByteArray
import io.ktor.utils.io.writeFully
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.superdro.core.connection.LinkFailure
import org.superdro.core.connection.LinkFailureException
import org.superdro.core.model.AdapterAddress

// StandardTestDispatcher gives the transport the virtual time of the test.
@OptIn(ExperimentalCoroutinesApi::class)
class TcpTransportTest {

    @Test
    fun theTransportConnectsReadsAndWrites() = realTime {
        LocalTcpServer.start().use { server ->
            val selector = SelectorManager(Dispatchers.IO)
            val transport = TcpTransport(AdapterAddress.Tcp("127.0.0.1", server.port), selector)
            val accepted = async { server.accept() }
            transport.open()
            val peer = accepted.await()
            val peerOut = peer.openWriteChannel(autoFlush = true)
            val peerIn = peer.openReadChannel()

            peerOut.writeFully("X1234;Y-5;".encodeToByteArray())
            val received = withTimeout(5000) {
                transport.incoming
                    .runningFold("") { text, bytes -> text + bytes.decodeToString() }
                    .first { text -> text.length >= 10 }
            }
            assertEquals("X1234;Y-5;", received)

            transport.write("#get:mac;".encodeToByteArray())
            transport.write(byteArrayOf(0x07))
            assertEquals("#get:mac;", peerIn.readByteArray(9).decodeToString())
            assertEquals(listOf<Byte>(0x07), peerIn.readByteArray(1).toList())

            transport.close()
            peer.close()
            selector.close()
        }
    }

    @Test
    fun theDeviceIdIsHostColonPort() {
        val selector = SelectorManager(Dispatchers.IO)
        assertEquals("192.168.4.1:8080", TcpTransport(AdapterAddress.Tcp("192.168.4.1"), selector).deviceId)
        selector.close()
    }

    @Test
    fun theTransportConnectsToAHostName() = realTime {
        LocalTcpServer.start("127.0.0.1").use { server ->
            val selector = SelectorManager(Dispatchers.IO)
            val transport = TcpTransport(AdapterAddress.Tcp("localhost", server.port), selector)
            val accepted = async { server.accept() }
            transport.open()
            accepted.await().close()
            transport.close()
            selector.close()
        }
    }

    @Test
    fun theTransportConnectsToAnIpv6Address() = realTime {
        val server = runCatching { LocalTcpServer.start("::1") }.getOrNull()
        if (server == null) {
            println("SKIPPED: this computer has no IPv6 loopback address.")
            return@realTime
        }
        server.use {
            val selector = SelectorManager(Dispatchers.IO)
            val transport = TcpTransport(AdapterAddress.Tcp("::1", server.port), selector)
            val accepted = async { server.accept() }
            transport.open()
            val peer = accepted.await()
            peer.openWriteChannel(autoFlush = true).writeFully("$1;".encodeToByteArray())
            assertEquals("$1;", withTimeout(5000) { transport.incoming.first() }.decodeToString())
            transport.close()
            peer.close()
            selector.close()
        }
    }

    @Test
    fun aRefusedConnectIsRefused() = realTime {
        val port = LocalTcpServer.start().use { server -> server.port }
        val selector = SelectorManager(Dispatchers.IO)
        val transport = TcpTransport(AdapterAddress.Tcp("127.0.0.1", port), selector)
        val failure = assertFailsWith<LinkFailureException> { transport.open() }
        assertEquals(LinkFailure.Refused, failure.failure)
        selector.close()
    }

    @Test
    fun aConnectThatDoesNotEndIsRefusedAfter5000Ms() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = TcpTransport(
            AdapterAddress.Tcp("192.168.4.1"),
            TcpConnector { _, _ -> awaitCancellation() },
            dispatcher,
        )
        val failure = assertFailsWith<LinkFailureException> { transport.open() }
        assertEquals(LinkFailure.Refused, failure.failure)
        assertEquals(5000L, testScheduler.currentTime)
    }

    @Test
    fun aConnectToANonRoutableAddressIsRefusedInVirtualTime() = runTest {
        val selector = SelectorManager(Dispatchers.IO)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = TcpTransport(AdapterAddress.Tcp("10.255.255.1", 8080), selector, dispatcher)
        val failure = assertFailsWith<LinkFailureException> { transport.open() }
        assertEquals(LinkFailure.Refused, failure.failure)
        assertTrue(testScheduler.currentTime <= 5000L)
        selector.close()
    }

    @Test
    fun eachOpenMakesOneConnectAttempt() = runTest {
        var attempts = 0
        val transport = TcpTransport(
            AdapterAddress.Tcp("192.168.4.1"),
            TcpConnector { _, _ ->
                attempts++
                throw IllegalStateException("connection refused")
            },
            StandardTestDispatcher(testScheduler),
        )
        val failure = assertFailsWith<LinkFailureException> { transport.open() }
        assertEquals(LinkFailure.Refused, failure.failure)
        assertEquals(1, attempts)
        assertEquals(0L, testScheduler.currentTime)
        assertFailsWith<IllegalStateException> { transport.open() }
        assertEquals(1, attempts)
    }

    @Test
    fun anAddressThatIsNotValidIsRefusedWithNoConnect() = runTest {
        var attempts = 0
        val transport = TcpTransport(
            AdapterAddress.Tcp("192.168.1.300"),
            TcpConnector { _, _ ->
                attempts++
                awaitCancellation()
            },
            StandardTestDispatcher(testScheduler),
        )
        val failure = assertFailsWith<LinkFailureException> { transport.open() }
        assertEquals(LinkFailure.Refused, failure.failure)
        assertEquals(0, attempts)
    }

    @Test
    fun theEndOfTheStreamFromTheAdapterIsLost() = realTime {
        LocalTcpServer.start().use { server ->
            val selector = SelectorManager(Dispatchers.IO)
            val transport = TcpTransport(AdapterAddress.Tcp("127.0.0.1", server.port), selector)
            val accepted = async { server.accept() }
            transport.open()
            val peer = accepted.await()
            peer.openWriteChannel(autoFlush = true).writeFully("X1;".encodeToByteArray())
            delay(200)
            peer.close()

            val chunks = mutableListOf<ByteArray>()
            val failure = assertFailsWith<LinkFailureException> {
                withTimeout(5000) { transport.incoming.collect { bytes -> chunks += bytes } }
            }
            assertEquals(LinkFailure.Lost, failure.failure)
            assertEquals("X1;", chunks.joinToString("") { bytes -> bytes.decodeToString() })
            transport.close()
            selector.close()
        }
    }

    @Test
    fun closeStopsAReadThatWaits() = realTime {
        LocalTcpServer.start().use { server ->
            val selector = SelectorManager(Dispatchers.IO)
            val transport = TcpTransport(AdapterAddress.Tcp("127.0.0.1", server.port), selector)
            val accepted = async { server.accept() }
            transport.open()
            val peer = accepted.await()
            val reader = async { transport.incoming.toList() }
            delay(300)
            assertTrue(reader.isActive)

            transport.close()
            transport.close()
            val chunks = withTimeout(3000) { reader.await() }
            assertTrue(chunks.isEmpty())
            assertEquals(emptyList<ByteArray>(), transport.incoming.toList())
            peer.close()
            selector.close()
        }
    }

    @Test
    fun aHalfOpenLinkHasNoReadTimeoutAndItsWritesStopAfter1000Ms() = realTime {
        LocalTcpServer.start().use { server ->
            val selector = SelectorManager(Dispatchers.IO)
            val transport = TcpTransport(AdapterAddress.Tcp("127.0.0.1", server.port), selector)
            val accepted = async { server.accept() }
            transport.open()
            // The peer stays connected, but it never sends and never reads.
            val peer: Socket = accepted.await()

            val reader = async { runCatching { transport.incoming.toList() } }
            delay(1500)
            assertTrue(reader.isActive, "The socket has no read timeout. Only the no-data watchdog finds this link.")

            val block = ByteArray(1 shl 20)
            var failure: LinkFailureException? = null
            var failedWriteMs = 0L
            withTimeout(60_000) {
                while (failure == null) {
                    val start = TimeSource.Monotonic.markNow()
                    try {
                        transport.write(block)
                    } catch (writeFailure: LinkFailureException) {
                        failure = writeFailure
                        failedWriteMs = start.elapsedNow().inWholeMilliseconds
                    }
                }
            }
            assertEquals(LinkFailure.Lost, failure?.failure)
            assertTrue(failedWriteMs >= 1000L, "The write stopped after $failedWriteMs ms.")
            assertTrue(failedWriteMs < 3000L, "The write stopped after $failedWriteMs ms.")

            val next = assertFailsWith<LinkFailureException> { transport.write(byteArrayOf(0x07)) }
            assertEquals(LinkFailure.Lost, next.failure)

            transport.close()
            val result = withTimeout(3000) { reader.await() }
            assertTrue(result.isSuccess)
            peer.close()
            selector.close()
        }
    }

    @Test
    fun aWriteBeforeTheOpenOrAfterTheCloseIsLost() = realTime {
        val selector = SelectorManager(Dispatchers.IO)
        val transport = TcpTransport(AdapterAddress.Tcp("127.0.0.1", 9), selector)
        assertEquals(LinkFailure.Lost, assertFailsWith<LinkFailureException> { transport.write(byteArrayOf(1)) }.failure)
        transport.close()
        assertEquals(LinkFailure.Lost, assertFailsWith<LinkFailureException> { transport.write(byteArrayOf(1)) }.failure)
        assertEquals(LinkFailure.Refused, assertFailsWith<LinkFailureException> { transport.open() }.failure)
        selector.close()
    }

    @Test
    fun theFactoryMakesTcpTransportsOnly() {
        TcpTransportFactory().use { factory ->
            val transport = factory.create(AdapterAddress.Tcp("dro.local", 23))
            assertIs<TcpTransport>(transport)
            assertEquals(AdapterAddress.Tcp("dro.local", 23), transport.address)
            assertFailsWith<IllegalArgumentException> { factory.create(AdapterAddress.BluetoothSpp("24:6F:28:A1:B2:C3")) }
        }
    }

    /** Runs [block] with real time: the sockets need real time, not the virtual time of the test. */
    private fun realTime(block: suspend CoroutineScope.() -> Unit): TestResult = runTest(timeout = 90.seconds) {
        withContext(Dispatchers.Default) { block() }
    }
}
