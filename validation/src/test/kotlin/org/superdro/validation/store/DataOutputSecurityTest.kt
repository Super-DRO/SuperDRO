package org.superdro.validation.store

import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Vector
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.bouncycastle.tls.AbstractTlsClient
import org.bouncycastle.tls.BasicTlsPSKExternal
import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.PRFAlgorithm
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.PskKeyExchangeMode
import org.bouncycastle.tls.TlsAuthentication
import org.bouncycastle.tls.TlsClientProtocol
import org.bouncycastle.tls.TlsCredentials
import org.bouncycastle.tls.TlsServerCertificate
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import org.superdro.core.connection.NetworkInterfaceInfo
import org.superdro.core.connection.NetworkInterfaces
import org.superdro.core.dataoutput.BindAddresses
import org.superdro.core.dataoutput.ByteLink
import org.superdro.core.dataoutput.DataOutputController
import org.superdro.core.dataoutput.DataOutputErrorType
import org.superdro.core.dataoutput.DataOutputPlatform
import org.superdro.core.dataoutput.DataOutputSecrets
import org.superdro.core.dataoutput.ListenSocket
import org.superdro.core.dataoutput.ListenSocketFactory
import org.superdro.core.dataoutput.OutputRunRule
import org.superdro.core.dataoutput.OutputValues
import org.superdro.core.dataoutput.PlainTextSecurity
import org.superdro.core.dataoutput.PskKey
import org.superdro.core.dataoutput.SenderState
import org.superdro.core.dataoutput.StartBlock
import org.superdro.core.dataoutput.TcpOutputServer
import org.superdro.core.dataoutput.systemListenSockets
import org.superdro.core.model.BindInterface
import org.superdro.core.model.DataOutputActivation
import org.superdro.core.model.DataOutputCadence
import org.superdro.core.model.DataOutputConfig
import org.superdro.core.model.DataOutputSecurity
import org.superdro.core.model.DataOutputTransport
import org.superdro.core.model.SuperDroJson
import org.superdro.core.util.Clock
import org.superdro.core.util.InMemorySecretStore

/**
 * The security rules of the data output with real TCP sockets on 127.0.0.1.
 *
 * The PSK client is the BouncyCastle TLS 1.3 client with an external PSK.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DataOutputSecurityTest {

    /** The bytes of the default record (48 bytes). */
    private val expectedRecord = "2026-10-11T14:03:22.517Z;12.7000;-3.1750;0.0000\n".encodeToByteArray()
    private val values = MutableStateFlow(OutputValues(xAbsolute = 12_700.0, yAbsolute = -3_175.0, zAbsolute = 0.0, updatedAtEpochMs = 1_791_727_402_517L))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sockets = systemListenSockets(Dispatchers.IO)
    private val binds = AtomicInteger(0)
    private val countingSockets = ListenSocketFactory { host, port -> binds.incrementAndGet(); sockets.bind(host, port) }
    private val secretStore = InMemorySecretStore()
    private val interfaces = object : NetworkInterfaces {
        override val interfaces: StateFlow<List<NetworkInterfaceInfo>> = MutableStateFlow(
            listOf(NetworkInterfaceInfo("wlan0", BindInterface.WiFi, listOf("127.0.0.1"))),
        )
    }
    private val platform = DataOutputPlatform(networkInterfaces = interfaces, secretStore = secretStore, listenSockets = countingSockets, retryDelayMs = 200)
    private val controllers = mutableListOf<DataOutputController>()

    @AfterTest
    fun close() {
        controllers.forEach(DataOutputController::close)
        scope.cancel()
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun controller(config: DataOutputConfig, profileId: String = "profile-1"): DataOutputController =
        DataOutputController(profileId, scope, values, platform, config, "Mill").also(controllers::add)

    /** The output on, "Always on", with a short interval so that a client gets a record soon. */
    private fun running(port: Int, security: DataOutputSecurity, confirmed: Boolean = false) = DataOutputConfig(
        enabled = true,
        port = port,
        security = security,
        plainTextConfirmed = confirmed,
        activation = DataOutputActivation.AlwaysOn,
        cadence = DataOutputCadence.OnChangeAndInterval,
        intervalMs = 100,
    )

    private fun awaitListening(controller: DataOutputController) = runBlocking {
        withTimeout(10_000) { controller.state.first { state -> state.listening.isNotEmpty() } }
    }

    private fun readLine(input: InputStream): ByteArray {
        val bytes = mutableListOf<Byte>()
        while (true) {
            val value = input.read()
            if (value < 0) throw IOException("end of stream after ${bytes.size} bytes")
            bytes += value.toByte()
            if (value == 0x0A) return bytes.toByteArray()
        }
    }

    private fun connectRefused(port: Int): Boolean = try {
        Socket().use { socket -> socket.connect(InetSocketAddress("127.0.0.1", port), 1000) }
        false
    } catch (_: ConnectException) {
        true
    }

    /** A TLS 1.3 client with an external PSK and `psk_dhe_ke`. */
    private fun pskClient(socket: Socket, identity: ByteArray, key: ByteArray): TlsClientProtocol {
        val crypto = BcTlsCrypto(SecureRandom())
        val client = object : AbstractTlsClient(crypto) {
            override fun getSupportedVersions(): Array<ProtocolVersion> = ProtocolVersion.TLSv13.only()
            override fun getSupportedCipherSuites(): IntArray = intArrayOf(CipherSuite.TLS_AES_128_GCM_SHA256)
            override fun getExternalPSKs(): Vector<*> =
                Vector(listOf(BasicTlsPSKExternal(identity, crypto.createSecret(key), PRFAlgorithm.tls13_hkdf_sha256)))
            override fun getPskKeyExchangeModes(): ShortArray = shortArrayOf(PskKeyExchangeMode.psk_dhe_ke)
            override fun getAuthentication(): TlsAuthentication = object : TlsAuthentication {
                override fun notifyServerCertificate(serverCertificate: TlsServerCertificate) = throw IOException("no certificate expected")
                override fun getClientCredentials(certificateRequest: org.bouncycastle.tls.CertificateRequest): TlsCredentials? = null
            }
        }
        val protocol = TlsClientProtocol(socket.getInputStream(), socket.getOutputStream())
        protocol.connect(client)
        return protocol
    }

    @Test
    fun no_server_socket_is_open_while_the_output_is_off() {
        val port = freePort()
        val output = controller(DataOutputConfig(port = port, activation = DataOutputActivation.AlwaysOn))
        output.setMachineOnline(true)
        output.requestStart()
        assertEquals(StartBlock.NotEnabled, output.state.value.block)
        assertEquals(false, output.state.value.running)
        Thread.sleep(300)
        assertEquals(0, binds.get())
        assertTrue(connectRefused(port))
    }

    @Test
    fun the_plain_text_mode_starts_only_after_the_confirmation_of_the_warning() {
        val port = freePort()
        val output = controller(running(port, DataOutputSecurity.PlainText, confirmed = false))
        output.setMachineOnline(true)
        assertEquals(StartBlock.PlainTextNotConfirmed, output.state.value.block)
        Thread.sleep(300)
        assertEquals(0, binds.get())
        assertTrue(connectRefused(port))
        output.setConfig(running(port, DataOutputSecurity.PlainText, confirmed = true))
        awaitListening(output)
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            // The plain text records are the TouchDRO compatible records.
            assertContentEquals(expectedRecord, readLine(socket.getInputStream()))
        }
    }

    @Test
    fun the_output_discards_the_inbound_bytes_and_two_outputs_cannot_use_one_port() {
        val port = freePort()
        val first = controller(running(port, DataOutputSecurity.PlainText, confirmed = true), profileId = "profile-a")
        first.setMachineOnline(true)
        awaitListening(first)
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            assertContentEquals(expectedRecord, readLine(socket.getInputStream()))
            // The output receives no command: the bytes of a client go away, and the records continue.
            socket.getOutputStream().write("#zero x\n#get_sig\n".encodeToByteArray())
            socket.getOutputStream().flush()
            assertContentEquals(expectedRecord, readLine(socket.getInputStream()))
        }
        val second = controller(running(port, DataOutputSecurity.PlainText, confirmed = true), profileId = "profile-b")
        second.setMachineOnline(true)
        assertEquals(StartBlock.PortTaken(port, "profile-a"), second.state.value.block)
    }

    @Test
    fun the_switch_off_closes_the_server_and_all_clients_at_once() {
        val port = freePort()
        val output = controller(running(port, DataOutputSecurity.PlainText, confirmed = true))
        output.setMachineOnline(true)
        awaitListening(output)
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            assertContentEquals(expectedRecord, readLine(socket.getInputStream()))
            output.setConfig(running(port, DataOutputSecurity.PlainText, confirmed = true).copy(enabled = false))
            assertEquals(StartBlock.NotEnabled, output.state.value.block)
            val ended = try {
                while (socket.getInputStream().read() >= 0) Unit
                true
            } catch (_: IOException) {
                true
            }
            assertTrue(ended, "the client connection ends")
        }
        assertTrue(connectRefused(port), "no server socket after the switch off")
    }

    @Test
    fun a_client_with_the_key_receives_the_records_through_tls_1_3_psk() {
        val port = freePort()
        // The default security mode is "Encrypted (PSK)".
        val config = running(port, DataOutputConfig().security)
        assertEquals(DataOutputSecurity.Psk, config.security)
        val output = controller(config)
        output.setMachineOnline(true)
        awaitListening(output)
        // The PSK key: the key is in the SecretStore as the share text `<identity>:<64 hex digits>`.
        val stored = assertNotNull(secretStore.get("data_output.profile-1.psk")).decodeToString()
        assertTrue(Regex("^superdro:[0-9a-f]{64}$").matches(stored), stored)
        val key = PskKey.parse(stored)
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            val tls = pskClient(socket, key.identityBytes, key.secret)
            // The records in the encrypted stream are the same bytes as in the plain text mode.
            assertContentEquals(expectedRecord, readLine(tls.inputStream))
            tls.close()
        }
        // The key is not in the configuration.
        assertTrue(key.keyHex !in SuperDroJson.encodeToString(DataOutputConfig.serializer(), output.currentConfig))
    }

    @Test
    fun a_client_with_a_wrong_key_receives_no_record() {
        val port = freePort()
        val output = controller(running(port, DataOutputSecurity.Psk))
        output.setMachineOnline(true)
        awaitListening(output)
        val wrong = ByteArray(32) { index -> (index * 7 + 1).toByte() }
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            val failed = try {
                val tls = pskClient(socket, "superdro".encodeToByteArray(), wrong)
                readLine(tls.inputStream)
                false
            } catch (_: IOException) {
                true
            }
            assertTrue(failed, "the handshake fails and no record arrives")
        }
        // A client with no TLS gets no record either.
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 3000
            val got = try {
                readLine(socket.getInputStream())
            } catch (_: IOException) {
                null
            }
            assertNull(got)
        }
    }

    @Test
    fun a_client_without_the_dhe_exchange_or_with_tls_1_2_receives_no_record() {
        val port = freePort()
        val output = controller(running(port, DataOutputSecurity.Psk))
        output.setMachineOnline(true)
        awaitListening(output)
        val key = PskKey.parse(assertNotNull(secretStore.get("data_output.profile-1.psk")).decodeToString())
        fun attempt(versions: Array<ProtocolVersion>, modes: ShortArray): Boolean = Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            val crypto = BcTlsCrypto(SecureRandom())
            val client = object : AbstractTlsClient(crypto) {
                override fun getSupportedVersions(): Array<ProtocolVersion> = versions
                override fun getSupportedCipherSuites(): IntArray =
                    intArrayOf(CipherSuite.TLS_AES_128_GCM_SHA256, CipherSuite.TLS_ECDHE_PSK_WITH_AES_128_CBC_SHA256, CipherSuite.TLS_PSK_WITH_AES_128_GCM_SHA256)
                override fun getExternalPSKs(): Vector<*> =
                    Vector(listOf(BasicTlsPSKExternal(key.identityBytes, crypto.createSecret(key.secret), PRFAlgorithm.tls13_hkdf_sha256)))
                override fun getPskKeyExchangeModes(): ShortArray = modes
                override fun getAuthentication(): TlsAuthentication = object : TlsAuthentication {
                    override fun notifyServerCertificate(serverCertificate: TlsServerCertificate) = throw IOException("no certificate expected")
                    override fun getClientCredentials(certificateRequest: org.bouncycastle.tls.CertificateRequest): TlsCredentials? = null
                }
            }
            try {
                val protocol = TlsClientProtocol(socket.getInputStream(), socket.getOutputStream())
                protocol.connect(client)
                readLine(protocol.inputStream)
                true
            } catch (_: Exception) {
                false
            }
        }
        // The PSK mode: the key exchange mode is psk_dhe_ke only.
        assertEquals(false, attempt(ProtocolVersion.TLSv13.only(), shortArrayOf(PskKeyExchangeMode.psk_ke)), "psk_ke only")
        assertEquals(false, attempt(ProtocolVersion.TLSv12.only(), shortArrayOf(PskKeyExchangeMode.psk_dhe_ke)), "TLS 1.2 only")
        assertEquals(true, attempt(ProtocolVersion.TLSv13.only(), shortArrayOf(PskKeyExchangeMode.psk_dhe_ke)), "the correct client")
    }

    @Test
    fun a_broker_link_with_no_tls_starts_only_after_the_same_warning() {
        val mqtt = DataOutputConfig(enabled = true, transport = DataOutputTransport.Mqtt, mqttBrokerHost = "broker.local", mqttTls = false)
        assertEquals(StartBlock.MqttWithoutTlsNotConfirmed, OutputRunRule.securityBlock(mqtt))
        assertNull(OutputRunRule.securityBlock(mqtt.copy(mqttNoTlsConfirmed = true)))
        assertNull(OutputRunRule.securityBlock(mqtt.copy(mqttTls = true)))
        assertEquals(StartBlock.PlainTextNotConfirmed, OutputRunRule.securityBlock(DataOutputConfig(security = DataOutputSecurity.PlainText)))
        assertNull(OutputRunRule.securityBlock(DataOutputConfig()))
    }

    @Test
    fun the_mode_names_the_warning_and_the_state_of_the_output_screen() {
        val strings = StoreFixtures.root.resolve("core/resources/src/commonMain/composeResources/values/strings_data_output.xml").readText()
        assertTrue(">Encrypted (PSK)<" in strings)
        assertTrue(">Plain text (TouchDRO compatible)<" in strings)
        assertTrue(">Use only on trusted networks<" in strings)
        val port = freePort()
        val output = controller(running(port, DataOutputSecurity.PlainText, confirmed = true))
        output.setMachineOnline(true)
        awaitListening(output)
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            readLine(socket.getInputStream())
            val state = runBlocking { withTimeout(5000) { output.state.first { it.clients.isNotEmpty() } } }
            // The status shows the address, the port, the security mode, and the number of clients.
            assertEquals(listOf("127.0.0.1:$port"), state.listening)
            assertEquals(port, state.port)
            assertEquals(DataOutputSecurity.PlainText, state.security)
            assertEquals(1, state.clients.size)
        }
    }

    @Test
    fun the_server_binds_to_the_wifi_interface_by_default_and_never_to_the_cellular_network() {
        val list = listOf(
            NetworkInterfaceInfo("wlan0", BindInterface.WiFi, listOf("192.168.1.20")),
            NetworkInterfaceInfo("rmnet0", null, listOf("10.64.0.7")),
            NetworkInterfaceInfo("tun0", BindInterface.Vpn, listOf("10.8.0.2")),
            NetworkInterfaceInfo("lo", BindInterface.Localhost, listOf("127.0.0.1")),
        )
        assertEquals(BindInterface.WiFi, DataOutputConfig().bindInterface)
        assertEquals(listOf("192.168.1.20"), BindAddresses.hosts(list, BindInterface.WiFi))
        assertEquals(listOf("10.8.0.2"), BindAddresses.hosts(list, BindInterface.Vpn))
        assertEquals(listOf("192.168.1.20", "10.8.0.2", "127.0.0.1"), BindAddresses.hosts(list, BindInterface.All))
        // "All interfaces" is a choice with a warning text.
        assertTrue(BindAddresses.needsWarning(BindInterface.All))
        BindInterface.entries.filter { it != BindInterface.All }.forEach { assertEquals(false, BindAddresses.needsWarning(it)) }
    }

    @Test
    fun the_mqtt_credentials_and_the_psk_stay_in_the_secret_store() {
        val store = InMemorySecretStore()
        val secrets = DataOutputSecrets(store, "p9")
        secrets.setMqttCredentials("operator", "s3cret".encodeToByteArray())
        assertEquals("operator", secrets.mqttCredentials()?.username)
        assertContentEquals("s3cret".encodeToByteArray(), secrets.mqttCredentials()?.password)
        val key = secrets.pskOrCreate()
        assertEquals(key, secrets.psk())
        val replaced = secrets.replacePsk()
        assertTrue(replaced != key, "a new key stops the old key")
        val text = SuperDroJson.encodeToString(DataOutputConfig.serializer(), DataOutputConfig(transport = org.superdro.core.model.DataOutputTransport.Mqtt))
        assertTrue("s3cret" !in text && "operator" !in text)
    }

    @Test
    fun the_r8_rules_keep_the_mqtt_client_classes() {
        val rules = StoreFixtures.root.resolve("core/data-output/consumer-rules.pro")
        assertTrue(rules.isFile)
        assertTrue("org.eclipse.paho" in rules.readText())
    }

    @Test
    fun an_accept_error_gives_the_state_failed_and_a_new_server_after_5_s() = runTest {
        assertEquals(5000L, TcpOutputServer.RETRY_DELAY_MS)
        val bindCount = AtomicInteger(0)
        val fake = ListenSocketFactory { host, port ->
            val number = bindCount.incrementAndGet()
            object : ListenSocket {
                override val host: String = host
                override val port: Int = port
                override suspend fun accept(): ByteLink = if (number == 1) throw IOException("accept failed") else awaitCancellation()
                override fun close() = Unit
            }
        }
        val errors = mutableListOf<DataOutputErrorType>()
        val server = TcpOutputServer(
            scope = backgroundScope,
            sockets = fake,
            security = PlainTextSecurity,
            bindHosts = { listOf("192.168.1.20") },
            port = 5800,
            clock = Clock { testScheduler.currentTime },
            onError = { error -> errors += error.type },
        )
        server.start()
        runCurrent()
        val failed = assertIs<SenderState.Failed>(server.state.value)
        assertEquals(DataOutputErrorType.AcceptFailed, failed.error.type)
        assertEquals(listOf(DataOutputErrorType.AcceptFailed), errors)
        assertEquals(1, bindCount.get())
        advanceTimeBy(4_999)
        runCurrent()
        assertEquals(1, bindCount.get(), "no new server before 5 s")
        advanceTimeBy(2)
        runCurrent()
        assertEquals(2, bindCount.get(), "a new server after 5 s")
        assertEquals(SenderState.Ready, server.state.value)
        server.stop()
        assertEquals(SenderState.Stopped, server.state.value)
    }

    @Test
    fun on_demand_starts_only_after_the_toolbar_button() {
        val port = freePort()
        val config = running(port, DataOutputSecurity.PlainText, confirmed = true).copy(activation = DataOutputActivation.OnDemand)
        val output = controller(config)
        output.setMachineOnline(true)
        assertEquals(StartBlock.NotRequested, output.state.value.block)
        assertEquals(0, binds.get())
        output.requestStart()
        awaitListening(output)
        assertTrue(binds.get() >= 1)
        output.setMachineOnline(false)
        assertEquals(StartBlock.MachineOffline, output.state.value.block)
        // The stop releases the port before it returns.
        assertTrue(connectRefused(port), "the port is still open after the machine went offline")
    }
}
