package org.superdro.core.dataoutput

import io.moquette.broker.Server
import io.moquette.broker.config.MemoryConfig
import io.moquette.broker.security.IAuthenticator
import io.moquette.interception.AbstractInterceptHandler
import io.moquette.interception.messages.InterceptPublishMessage
import java.io.File
import java.security.KeyStore
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.superdro.core.dataoutput.RealSockets.freePort
import org.superdro.core.dataoutput.RealSockets.waitUntil
import org.superdro.core.model.DataOutputConfig
import org.superdro.core.model.DataOutputTransport
import org.superdro.core.model.MqttTopicLayout
import org.superdro.core.util.InMemorySecretStore

/**
 * The MQTT output with the Paho client and an embedded Moquette broker. The broker
 * has a TLS port with the test certificate `test-broker.p12`, a plain port, and a user name with a
 * password.
 */
class MqttBrokerTest {

    private val broker = Server()
    private val messages = CopyOnWriteArrayList<Pair<String, String>>()
    private var plainPort = 0
    private var tlsPort = 0
    private lateinit var keystore: File

    @BeforeTest
    fun startBroker() {
        plainPort = freePort()
        tlsPort = freePort()
        keystore = File.createTempFile("test-broker", ".p12").apply {
            writeBytes(MqttBrokerTest::class.java.getResourceAsStream("/test-broker.p12")!!.readBytes())
            deleteOnExit()
        }
        val properties = Properties().apply {
            setProperty("host", "127.0.0.1")
            setProperty("port", "$plainPort")
            setProperty("ssl_port", "$tlsPort")
            setProperty("ssl_provider", "JDK")
            setProperty("jks_path", keystore.absolutePath)
            setProperty("key_store_type", "pkcs12")
            setProperty("key_store_password", PASSWORD)
            setProperty("key_manager_password", PASSWORD)
            setProperty("allow_anonymous", "false")
            setProperty("persistence_enabled", "false")
            // The broker must not send data to a remote server in a test.
            setProperty("telemetry_enabled", "false")
        }
        val handler = object : AbstractInterceptHandler() {
            override fun getID(): String = "superdro-test"

            override fun onPublish(message: InterceptPublishMessage) {
                val payload = message.payload
                val bytes = ByteArray(payload.readableBytes())
                payload.getBytes(payload.readerIndex(), bytes)
                messages += message.topicName to bytes.decodeToString()
                super.onPublish(message)
            }

            override fun onSessionLoopError(error: Throwable?) = Unit
        }
        val authenticator = IAuthenticator { _, username, password ->
            username == "dro" && password?.decodeToString() == "secret"
        }
        broker.startServer(MemoryConfig(properties), listOf(handler), null, authenticator, null)
    }

    @AfterTest
    fun stopBroker() {
        broker.stopServer()
    }

    /** A TLS socket factory that trusts the test certificate. */
    private fun testTrust(): javax.net.ssl.SSLSocketFactory {
        val store = KeyStore.getInstance("PKCS12").apply { keystore.inputStream().use { input -> load(input, PASSWORD.toCharArray()) } }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        return SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }.socketFactory
    }

    private fun output(config: DataOutputConfig, connector: MqttConnector, password: String = "secret"): Pair<DataOutputController, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = InMemorySecretStore()
        val controller = DataOutputController(
            profileId = "p1",
            parentScope = scope,
            values = MutableStateFlow(RealSockets.testValues),
            platform = DataOutputPlatform(
                networkInterfaces = FakeNetworkInterfaces(),
                secretStore = store,
                listenSockets = FakeSocketFactory(),
                mqttConnector = connector,
                retryDelayMs = 300L,
            ),
            config = config,
            profileName = "Mill",
        )
        controller.secrets.setMqttCredentials("dro", password.encodeToByteArray())
        controller.setMachineOnline(true)
        controller.requestStart()
        return controller to scope
    }

    private val tlsConfig: DataOutputConfig
        get() = DataOutputConfig(
            enabled = true, transport = DataOutputTransport.Mqtt, mqttBrokerHost = "localhost", mqttBrokerPort = tlsPort,
            mqttMachineName = "mill-1",
        )

    @Test
    fun theJsonLayoutGoesToTheBrokerThroughTls() = runBlocking {
        val (controller, scope) = output(tlsConfig, PahoMqttConnector(testTrust()))
        try {
            waitUntil(10_000L) { messages.size >= 2 }
            val (topic, payload) = messages.first()
            assertEquals("superdro/mill-1", topic)
            assertTrue(payload.startsWith("{\"timestamp\":\"2026-10-11T14:03:22.517Z\",\"sequence\":"), payload)
            assertTrue(payload.endsWith(",\"x_absolute\":12.7000,\"y_absolute\":-3.1750,\"z_absolute\":0.0000}"), payload)
            assertEquals(SenderState.Active, controller.state.value.sender)
            assertTrue(controller.state.value.brokerTls)
        } finally {
            controller.close()
            scope.cancel()
        }
    }

    @Test
    fun theFieldLayoutGivesOneTopicForEachField() = runBlocking {
        val config = tlsConfig.copy(mqttTopicLayout = MqttTopicLayout.TopicPerField)
        val (controller, scope) = output(config, PahoMqttConnector(testTrust()))
        try {
            waitUntil(10_000L) { messages.size >= 4 }
            assertEquals(
                listOf(
                    "superdro/mill-1/timestamp" to "2026-10-11T14:03:22.517Z",
                    "superdro/mill-1/x_absolute" to "12.7000",
                    "superdro/mill-1/y_absolute" to "-3.1750",
                    "superdro/mill-1/z_absolute" to "0.0000",
                ),
                messages.take(4),
            )
        } finally {
            controller.close()
            scope.cancel()
        }
    }

    @Test
    fun theTlsLinkChecksTheBrokerCertificate() = runBlocking {
        // The default trust store of the system does not trust the test certificate.
        val (controller, scope) = output(tlsConfig, PahoMqttConnector())
        try {
            waitUntil(10_000L) { controller.state.value.sender is SenderState.Failed }
            val failed = assertIs<SenderState.Failed>(controller.state.value.sender)
            assertEquals(DataOutputErrorType.TlsFailure, failed.error.type)
            assertTrue(messages.isEmpty())
        } finally {
            controller.close()
            scope.cancel()
        }
    }

    @Test
    fun aWrongPasswordIsRefused() = runBlocking {
        val (controller, scope) = output(tlsConfig, PahoMqttConnector(testTrust()), password = "wrong")
        try {
            waitUntil(10_000L) { controller.state.value.sender is SenderState.Failed }
            assertEquals(DataOutputErrorType.AuthRejected, assertIs<SenderState.Failed>(controller.state.value.sender).error.type)
            assertTrue(messages.isEmpty())
        } finally {
            controller.close()
            scope.cancel()
        }
    }

    @Test
    fun aLinkWithNoTlsStartsAfterTheConfirmation() = runBlocking {
        val config = tlsConfig.copy(mqttBrokerPort = plainPort, mqttTls = false, mqttNoTlsConfirmed = true, mqttBrokerHost = "127.0.0.1")
        val (controller, scope) = output(config, PahoMqttConnector())
        try {
            waitUntil(10_000L) { messages.isNotEmpty() }
            assertEquals("superdro/mill-1", messages.first().first)
        } finally {
            controller.close()
            scope.cancel()
        }
    }

    @Test
    fun testConnectionReachesTheBroker() = runBlocking {
        val (controller, scope) = output(tlsConfig.copy(enabled = false), PahoMqttConnector(testTrust()))
        try {
            assertEquals(TestConnectionResult.BrokerReachable("localhost", tlsPort, true), controller.testConnection(tlsConfig))
        } finally {
            controller.close()
            scope.cancel()
        }
    }

    private companion object {
        const val PASSWORD = "superdro-test"
    }
}
