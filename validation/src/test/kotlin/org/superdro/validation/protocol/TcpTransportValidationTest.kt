package org.superdro.validation.protocol

import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.superdro.core.connection.CapabilitySource
import org.superdro.core.connection.CapabilityState
import org.superdro.core.connection.ConnectionManager
import org.superdro.core.connection.DeviceSession
import org.superdro.core.connection.LinkFailure
import org.superdro.core.connection.LinkFailureException
import org.superdro.core.connection.SessionState
import org.superdro.core.connection.WantedLinkSource
import org.superdro.core.connection.tcp.TcpAddressRules
import org.superdro.core.connection.tcp.TcpLinkDefaults
import org.superdro.core.connection.tcp.TcpTransport
import org.superdro.core.connection.tcp.TcpTransportFactory
import org.superdro.core.fakeadapter.FakeAdapter
import org.superdro.core.fakeadapter.FakeScenarios
import org.superdro.core.fakeadapter.FakeTcpServer
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.TransportKind
import org.superdro.core.protocol.AdapterTiming
import org.superdro.validation.protocol.SessionHarness.Companion.ref

/**
 * The Wi-Fi TCP link and the "TCP server" scenario. The tests use
 * real sockets on the loopback address and the real clock.
 */
class TcpTransportValidationTest {

    private val loopback: InetAddress = InetAddress.getLoopbackAddress()

    private fun server(backlog: Int = 50): ServerSocket = ServerSocket(0, backlog, loopback)

    private fun address(server: ServerSocket) = AdapterAddress.Tcp(loopback.hostAddress, server.localPort)

    private fun InputStream.readExactly(count: Int, timeoutMs: Int, socket: Socket): ByteArray {
        socket.soTimeout = timeoutMs
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = read(buffer, read, count - read)
            if (n < 0) break
            read += n
        }
        return buffer.copyOf(read)
    }

    // The address rules.

    @Test
    fun d126_the_host_can_be_ipv4_ipv6_or_a_host_name() {
        assertEquals(TcpAddressRules.HostKind.Ipv4, TcpAddressRules.hostKind("192.168.4.1"))
        assertEquals(TcpAddressRules.HostKind.Ipv6, TcpAddressRules.hostKind("fe80::1"))
        assertEquals(TcpAddressRules.HostKind.Ipv6, TcpAddressRules.hostKind("2001:db8::8a2e:370:7334"))
        assertEquals(TcpAddressRules.HostKind.HostName, TcpAddressRules.hostKind("dro-adapter.local"))
        assertEquals(TcpAddressRules.HostKind.HostName, TcpAddressRules.hostKind("esp32"))
        assertFalse(TcpAddressRules.isValidHost(""))
        assertFalse(TcpAddressRules.isValidHost("bad host"))
    }

    @Test
    fun port_range_1_to_65535_and_default_port_8080() {
        assertFalse(TcpAddressRules.isValidPort(0))
        assertTrue(TcpAddressRules.isValidPort(1))
        assertTrue(TcpAddressRules.isValidPort(8080))
        assertTrue(TcpAddressRules.isValidPort(65535))
        assertFalse(TcpAddressRules.isValidPort(65536))
        assertEquals(8080, AdapterAddress.Tcp("192.168.4.1").port)
        assertEquals(8080, TcpLinkDefaults.PORT)
        assertEquals(AdapterAddress.Tcp("192.168.4.1", 8080), TcpAddressRules.parse("192.168.4.1"))
        assertEquals(AdapterAddress.Tcp("dro.local", 9000), TcpAddressRules.parse("dro.local:9000"))
        assertEquals(AdapterAddress.Tcp("fe80::1", 8080), TcpAddressRules.parse("[fe80::1]:8080"))
        assertNull(TcpAddressRules.parse("dro.local:0"))
        assertNull(TcpAddressRules.parse("dro.local:65536"))
    }

    @Test
    fun constants_and_the_device_id() {
        assertEquals(5000L, TcpLinkDefaults.CONNECT_TIMEOUT_MS)
        assertEquals(1000L, TcpLinkDefaults.WRITE_TIMEOUT_MS)
        assertEquals(1024, TcpLinkDefaults.READ_BUFFER_BYTES)
        assertEquals("192.168.4.1:8080", TcpAddressRules.deviceId(AdapterAddress.Tcp("192.168.4.1", 8080)))
        TcpTransportFactory().use { factory ->
            val transport = factory.create(AdapterAddress.Tcp("dro.local", 8081)) as TcpTransport
            assertEquals("dro.local:8081", transport.deviceId)
        }
    }

    // The transport with real sockets.

    @Test
    fun open_read_write_and_close_on_a_local_server() = runBlocking {
        server().use { server ->
            TcpTransportFactory().use { factory ->
                val transport = factory.create(address(server))
                withTimeout(5_000L) { transport.open() }
                server.soTimeout = 5_000
                val peer = server.accept()
                peer.getOutputStream().apply {
                    write("X1200;".encodeToByteArray())
                    flush()
                }
                val chunk = withTimeout(5_000L) { transport.incoming.first() }
                assertEquals("X1200;", chunk.decodeToString())
                val command = byteArrayOf(0x22, 0x23, 0x67, 0x65, 0x74, 0x3A, 0x6D, 0x61, 0x63, 0x3B, 0x22)
                transport.write(command)
                assertContentEquals(command, peer.getInputStream().readExactly(11, 5_000, peer))
                transport.write(byteArrayOf(0x07))
                assertContentEquals(byteArrayOf(0x07), peer.getInputStream().readExactly(1, 5_000, peer))
                transport.close()
                peer.soTimeout = 5_000
                assertEquals(-1, peer.getInputStream().read())
                peer.close()
            }
        }
    }

    @Test
    fun a_connect_to_a_port_with_no_server_is_refused() = runBlocking {
        val port = server().use { it.localPort }
        TcpTransportFactory().use { factory ->
            val transport = factory.create(AdapterAddress.Tcp(loopback.hostAddress, port))
            val failure = assertFailsWith<LinkFailureException> { withTimeout(6_000L) { transport.open() } }
            assertEquals(LinkFailure.Refused, failure.failure)
        }
    }

    @Test
    fun an_address_that_is_not_valid_is_refused_at_once() = runBlocking {
        TcpTransportFactory().use { factory ->
            val transport = factory.create(AdapterAddress.Tcp("bad host", 8080))
            val failure = assertFailsWith<LinkFailureException> { withTimeout(1_000L) { transport.open() } }
            assertEquals(LinkFailure.Refused, failure.failure)
        }
    }

    @Test
    fun the_end_of_the_stream_from_the_adapter_is_lost() = runBlocking {
        server().use { server ->
            TcpTransportFactory().use { factory ->
                val transport = factory.create(address(server))
                withTimeout(5_000L) { transport.open() }
                server.soTimeout = 5_000
                server.accept().close()
                val failure = assertFailsWith<LinkFailureException> { withTimeout(5_000L) { transport.incoming.collect { } } }
                assertEquals(LinkFailure.Lost, failure.failure)
            }
        }
    }

    @Test
    fun dc19_one_connect_attempt_with_a_5000_ms_timeout() = runBlocking {
        // A server with a full accept queue does not answer a new connect.
        val listener = server(backlog = 1)
        val fillers = ArrayList<Socket>()
        try {
            repeat(8) {
                val socket = Socket()
                try {
                    socket.connect(InetSocketAddress(loopback, listener.localPort), 300)
                    fillers += socket
                } catch (_: SocketTimeoutException) {
                    socket.close()
                }
            }
            TcpTransportFactory().use { factory ->
                val transport = factory.create(address(listener))
                val started = System.nanoTime()
                val failure = assertFailsWith<LinkFailureException> { withTimeout(15_000L) { transport.open() } }
                val elapsedMs = (System.nanoTime() - started) / 1_000_000L
                assertEquals(LinkFailure.Refused, failure.failure)
                assertTrue(elapsedMs in 4_500L..8_000L, "the connect ended after $elapsedMs ms")
            }
        } finally {
            fillers.forEach { it.close() }
            listener.close()
        }
    }

    @Test
    fun d127_a_write_that_does_not_end_in_1000_ms_is_lost() = runBlocking {
        server().use { server ->
            TcpTransportFactory().use { factory ->
                val transport = factory.create(address(server))
                withTimeout(5_000L) { transport.open() }
                server.soTimeout = 5_000
                val peer = server.accept()
                peer.receiveBufferSize = 4_096
                val started = System.nanoTime()
                val failure = assertFailsWith<LinkFailureException> {
                    withTimeout(20_000L) { repeat(64) { transport.write(ByteArray(1 shl 20)) } }
                }
                val elapsedMs = (System.nanoTime() - started) / 1_000_000L
                assertEquals(LinkFailure.Lost, failure.failure)
                assertTrue(elapsedMs < 10_000L, "the write ended after $elapsedMs ms")
                peer.close()
            }
        }
    }

    // A session on a real TCP link.

    private class RealTime(timing: AdapterTiming = AdapterTiming()) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val factory = TcpTransportFactory()
        val manager = ConnectionManager(
            factories = mapOf(TransportKind.Tcp to factory),
            capabilities = object : CapabilitySource {
                override val state: StateFlow<CapabilityState> = MutableStateFlow(CapabilityState())
            },
            wantedLinks = object : WantedLinkSource {
                override val wantedLinks = emptyFlow<List<org.superdro.core.connection.WantedLink>>()
            },
            scope = scope,
            timing = timing,
        )

        override fun close() {
            manager.close()
            factory.close()
            scope.cancel()
        }
    }

    private suspend fun DeviceSession.awaitState(timeoutMs: Long, predicate: (SessionState) -> Boolean): SessionState =
        withTimeout(timeoutMs) { state.first(predicate) }

    @Test
    fun f1_23_same_bel_byte_and_queue_on_a_tcp_link() = runBlocking {
        server().use { server ->
            RealTime().use { realTime ->
                val session = realTime.manager.connect(ref(address(server)))
                server.soTimeout = 5_000
                val peer = server.accept()
                val output = peer.getOutputStream()
                val sentAt = System.nanoTime()
                output.write("X1;".encodeToByteArray())
                output.flush()
                session.awaitState(5_000L) { it is SessionState.Connected }
                val keepData = realTime.scope.async {
                    repeat(40) {
                        delay(100L)
                        runCatching {
                            output.write("X1;".encodeToByteArray())
                            output.flush()
                        }
                    }
                }
                val input = peer.getInputStream()
                peer.soTimeout = 5_000
                val first = input.read()
                val belAtMs = (System.nanoTime() - sentAt) / 1_000_000L
                assertEquals(0x07, first)
                assertTrue(belAtMs in 350L..1_200L, "the first BEL byte came after $belAtMs ms")
                // The next bytes: more BEL bytes, then "#get:mac;" at about 2000 ms.
                var next = input.read()
                while (next == 0x07) next = input.read()
                val queueAtMs = (System.nanoTime() - sentAt) / 1_000_000L
                val rest = input.readExactly(10, 5_000, peer)
                assertEquals("\"#get:mac;\"", (byteArrayOf(next.toByte()) + rest).decodeToString())
                assertTrue(queueAtMs in 1_800L..3_000L, "the first query came after $queueAtMs ms")
                keepData.cancel()
                peer.close()
            }
        }
    }

    @Test
    fun d127_a_half_open_tcp_link_is_found_by_the_watchdog_and_opened_again() = runBlocking {
        server().use { server ->
            RealTime(AdapterTiming(noDataMs = 1_000L)).use { realTime ->
                val session = realTime.manager.connect(ref(address(server), twoWay = false))
                server.soTimeout = 5_000
                val first = server.accept()
                first.getOutputStream().apply {
                    write("X1;".encodeToByteArray())
                    flush()
                }
                session.awaitState(5_000L) { it is SessionState.Connected }
                // The adapter sends nothing more, but the socket stays open (half open).
                val reconnecting = session.awaitState(5_000L) { it is SessionState.Reconnecting }
                assertIs<SessionState.Reconnecting>(reconnecting)
                first.soTimeout = 5_000
                assertEquals(-1, first.getInputStream().read())
                val second = server.accept()
                assertNotNull(second)
                second.close()
                first.close()
            }
        }
    }

    // The fake adapter behind a local TCP server.

    @Test
    fun fake_adapter_behind_a_local_tcp_server_gives_the_documented_answers() = runBlocking {
        FakeTcpServer(FakeAdapter(FakeScenarios.Commands), host = loopback.hostAddress, port = 0).use { fake ->
            fake.start()
            RealTime().use { realTime ->
                val session = realTime.manager.connect(ref(fake.address))
                session.awaitState(5_000L) { it is SessionState.Connected }
                withTimeout(10_000L) { session.adapterInfo.first { it.values.model.value != null } }
                assertEquals(FakeAdapter.MAC, session.adapterInfo.value.values.mac.value)
                assertEquals("24:6f:28:a1:b2:c5", session.adapterInfo.value.identity?.value)
            }
        }
    }

    @Test
    fun fake_tcp_server_closed_gives_a_refused_connect_and_reconnecting() = runBlocking {
        val fake = FakeTcpServer(FakeAdapter(FakeScenarios.Still), host = loopback.hostAddress, port = 0)
        fake.start()
        val address = fake.address
        fake.close()
        RealTime().use { realTime ->
            val session = realTime.manager.connect(ref(address))
            val state = session.awaitState(6_000L) { it is SessionState.Reconnecting }
            assertEquals(LinkFailure.Refused, (state as SessionState.Reconnecting).cause)
        }
    }
}
