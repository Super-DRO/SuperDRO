package org.superdro.core.dataoutput

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.NetworkInterfaceInfo
import org.superdro.core.model.BindInterface
import org.superdro.core.model.DataOutputActivation
import org.superdro.core.model.DataOutputConfig
import org.superdro.core.model.DataOutputFormat
import org.superdro.core.model.DataOutputSecurity
import org.superdro.core.model.DataOutputTransport
import org.superdro.core.model.MqttTopicLayout
import org.superdro.core.util.Clock
import org.superdro.core.util.InMemorySecretStore

/** The output of one machine profile with fake sockets and virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class DataOutputControllerTest {

    private val updateTime = 1791727402517L
    private val defaultValues = OutputValues(xAbsolute = 12700.0, yAbsolute = -3175.0, zAbsolute = 0.0, updatedAtEpochMs = updateTime)

    /** The plain text mode after the confirmation, so that a fake client can read the records. */
    private val plain = DataOutputConfig(enabled = true, security = DataOutputSecurity.PlainText, plainTextConfirmed = true)

    private class Setup(
        val controller: DataOutputController,
        val sockets: FakeSocketFactory,
        val interfaces: FakeNetworkInterfaces,
        val values: MutableStateFlow<OutputValues>,
        val keys: MutableList<PskKey>,
        val mqtt: FakeMqttConnector,
        val store: InMemorySecretStore,
        val ports: PortRegistry,
    )

    private fun TestScope.setup(
        config: DataOutputConfig = DataOutputConfig(),
        values: OutputValues = defaultValues,
        ports: PortRegistry = PortRegistry(),
        profileId: String = "p1",
    ): Setup {
        val sockets = FakeSocketFactory()
        val interfaces = FakeNetworkInterfaces()
        val flow = MutableStateFlow(values)
        val keys = mutableListOf<PskKey>()
        val mqtt = FakeMqttConnector()
        val store = InMemorySecretStore()
        val platform = DataOutputPlatform(
            networkInterfaces = interfaces,
            secretStore = store,
            listenSockets = sockets,
            mqttConnector = mqtt,
            pskSecurity = { key -> keys += key; PlainTextSecurity },
            ports = ports,
            clock = Clock { testScheduler.currentTime },
            wallClock = WallClock { updateTime + testScheduler.currentTime },
            secretsDispatcher = StandardTestDispatcher(testScheduler),
        )
        val controller = DataOutputController(profileId, backgroundScope, flow, platform, config, profileName = "Mill")
        return Setup(controller, sockets, interfaces, flow, keys, mqtt, store, ports)
    }

    private fun Setup.online() {
        controller.setMachineOnline(true)
    }

    @Test
    fun noSocketIsOpenWhileTheOutputIsOff() = runTest {
        val setup = setup()
        setup.online()
        setup.controller.requestStart()
        advanceTimeBy(10_000L)
        runCurrent()
        assertTrue(setup.sockets.sockets.isEmpty())
        assertEquals(StartBlock.NotEnabled, setup.controller.state.value.block)
        assertFalse(setup.controller.state.value.running)
        assertEquals(DataOutputStatus.Stopped, setup.controller.state.value.status)
    }

    @Test
    fun theDefaultOutputUsesThePskModeOnTheWifiAddressAfterTheRequest() = runTest {
        val setup = setup(DataOutputConfig(enabled = true))
        setup.online()
        runCurrent()
        assertEquals(StartBlock.NotRequested, setup.controller.state.value.block)
        assertTrue(setup.sockets.sockets.isEmpty())
        setup.controller.requestStart()
        runCurrent()
        val socket = setup.sockets.open.single()
        assertEquals("192.168.1.20", socket.host)
        assertEquals(5800, socket.port)
        // The PSK mode got the key of the SecretStore. The output made the key at the first start.
        assertEquals(setup.controller.secrets.psk(), setup.keys.single())
        val state = setup.controller.state.value
        assertTrue(state.running)
        assertEquals(DataOutputSecurity.Psk, state.security)
        assertEquals(DataOutputStatus.Ready, state.status)
        assertEquals(listOf("192.168.1.20:5800"), state.listening)
        assertEquals(0, state.clients.size)
    }

    @Test
    fun aClientGetsTheDefaultRecordEach500MsWithTheSameTimeStamp() = runTest {
        val setup = setup(plain)
        setup.online()
        setup.controller.requestStart()
        runCurrent()
        val client = setup.sockets.open.single().connect()
        runCurrent()
        assertEquals("", client.receivedText)
        advanceTimeBy(500L)
        runCurrent()
        assertEquals(listOf("2026-10-11T14:03:22.517Z;12.7000;-3.1750;0.0000\n"), client.writes.map { bytes -> bytes.decodeToString() })
        assertEquals(48, client.writes.single().size)
        advanceTimeBy(500L)
        runCurrent()
        assertEquals(2, client.writes.size)
        assertEquals(client.writes[0].decodeToString(), client.writes[1].decodeToString())
        assertEquals(DataOutputStatus.Sending, setup.controller.state.value.status)
        assertEquals(2L, setup.controller.state.value.statistics?.sent)
    }

    @Test
    fun aChangeSendsARecordAtOnceWithTheTimeOfTheUpdate() = runTest {
        val setup = setup(plain)
        setup.online()
        setup.controller.requestStart()
        runCurrent()
        val client = setup.sockets.open.single().connect()
        runCurrent()
        advanceTimeBy(100L)
        setup.values.value = defaultValues.copy(xAbsolute = 25400.0, updatedAtEpochMs = updateTime + 100)
        runCurrent()
        assertEquals("2026-10-11T14:03:22.617Z;25.4000;-3.1750;0.0000\n", client.receivedText)
    }

    @Test
    fun beforeTheFirstUpdateTheTimeStampIsTheCurrentTime() = runTest {
        val setup = setup(plain, values = OutputValues(xAbsolute = 0.0, yAbsolute = 0.0, zAbsolute = 0.0))
        setup.online()
        setup.controller.requestStart()
        runCurrent()
        val client = setup.sockets.open.single().connect()
        runCurrent()
        advanceTimeBy(500L)
        runCurrent()
        // The fake wall clock is updateTime + the virtual time (500 ms).
        assertEquals("2026-10-11T14:03:23.017Z;0.0000;0.0000;0.0000\n", client.receivedText)
    }

    @Test
    fun theSequenceStartsAt0AtEachStartAndCountsTheRecordsWithNoClient() = runTest {
        val setup = setup(plain.copy(format = DataOutputFormat.Json))
        setup.online()
        setup.controller.requestStart()
        runCurrent()
        advanceTimeBy(1500L)
        runCurrent()
        assertEquals(3L, setup.controller.state.value.statistics?.records)
        val client = setup.sockets.open.single().connect()
        runCurrent()
        advanceTimeBy(500L)
        runCurrent()
        assertTrue(client.receivedText.startsWith("{\"timestamp\":\"2026-10-11T14:03:22.517Z\",\"sequence\":3,"))
        setup.controller.requestStop()
        setup.controller.requestStart()
        runCurrent()
        val again = setup.sockets.open.single().connect()
        runCurrent()
        advanceTimeBy(500L)
        runCurrent()
        assertTrue(again.receivedText.startsWith("{\"timestamp\":\"2026-10-11T14:03:22.517Z\",\"sequence\":0,"))
    }

    @Test
    fun theSwitchOffClosesTheServerAndAllClientsAtOnce() = runTest {
        val setup = setup(plain.copy(activation = DataOutputActivation.AlwaysOn))
        setup.online()
        runCurrent()
        val socket = setup.sockets.open.single()
        val clients = listOf(socket.connect("192.168.1.30"), socket.connect("192.168.1.31"))
        runCurrent()
        assertEquals(2, setup.controller.state.value.clients.size)
        setup.controller.setConfig(setup.controller.currentConfig.copy(enabled = false))
        // No runCurrent: the sockets are closed when setConfig() returns.
        assertTrue(socket.closed)
        assertTrue(clients.all { client -> client.closed })
        assertFalse(setup.controller.state.value.running)
        assertEquals(StartBlock.NotEnabled, setup.controller.state.value.block)
        advanceTimeBy(10_000L)
        runCurrent()
        assertTrue(setup.sockets.open.isEmpty())
    }

    @Test
    fun alwaysOnRunsWhileTheMachineIsOnline() = runTest {
        val setup = setup(plain.copy(activation = DataOutputActivation.AlwaysOn))
        runCurrent()
        assertEquals(StartBlock.MachineOffline, setup.controller.state.value.block)
        assertTrue(setup.sockets.sockets.isEmpty())
        setup.online()
        runCurrent()
        assertEquals(1, setup.sockets.open.size)
        setup.controller.setMachineOnline(false)
        assertTrue(setup.sockets.open.isEmpty())
        assertEquals(StartBlock.MachineOffline, setup.controller.state.value.block)
    }

    @Test
    fun theToolbarButtonStartsAndStopsTheOutputOnDemand() = runTest {
        val setup = setup(plain)
        setup.online()
        setup.controller.toggle()
        runCurrent()
        assertEquals(1, setup.sockets.open.size)
        setup.controller.toggle()
        assertTrue(setup.sockets.open.isEmpty())
        assertEquals(StartBlock.NotRequested, setup.controller.state.value.block)
    }

    @Test
    fun thePlainTextModeStartsOnlyAfterTheConfirmation() = runTest {
        val setup = setup(DataOutputConfig(enabled = true, security = DataOutputSecurity.PlainText))
        setup.online()
        setup.controller.requestStart()
        runCurrent()
        assertTrue(setup.sockets.sockets.isEmpty())
        assertEquals(StartBlock.PlainTextNotConfirmed, setup.controller.state.value.block)
        // The screen shows the mode at all times.
        assertEquals(DataOutputSecurity.PlainText, setup.controller.state.value.security)
        setup.controller.setConfig(setup.controller.currentConfig.copy(plainTextConfirmed = true))
        runCurrent()
        assertEquals(1, setup.sockets.open.size)
        assertEquals(DataOutputSecurity.PlainText, setup.controller.state.value.security)
        assertTrue(setup.keys.isEmpty())
    }

    @Test
    fun aChangeOfTheConfigurationStartsTheOutputAgain() = runTest {
        val setup = setup(plain)
        setup.online()
        setup.controller.requestStart()
        runCurrent()
        val first = setup.sockets.open.single()
        setup.controller.setConfig(setup.controller.currentConfig.copy(port = 5900, bindInterface = BindInterface.Localhost))
        runCurrent()
        assertTrue(first.closed)
        val second = setup.sockets.open.single()
        assertEquals("127.0.0.1", second.host)
        assertEquals(5900, second.port)
    }

    @Test
    fun aNewAddressOfTheInterfaceGivesANewServer() = runTest {
        val setup = setup(plain)
        setup.online()
        setup.controller.requestStart()
        runCurrent()
        val first = setup.sockets.open.single()
        setup.interfaces.interfaces.value = listOf(NetworkInterfaceInfo("wlan0", BindInterface.WiFi, listOf("192.168.1.77")))
        runCurrent()
        assertTrue(first.closed)
        assertEquals("192.168.1.77", setup.sockets.open.single().host)
    }

    @Test
    fun twoOutputsCannotUseTheSamePort() = runTest {
        val ports = PortRegistry()
        val mill = setup(plain, ports = ports, profileId = "mill")
        val lathe = setup(plain, ports = ports, profileId = "lathe")
        mill.online()
        mill.controller.requestStart()
        lathe.online()
        lathe.controller.requestStart()
        runCurrent()
        assertEquals(1, mill.sockets.open.size)
        assertTrue(lathe.sockets.sockets.isEmpty())
        assertEquals(StartBlock.PortTaken(5800, "mill"), lathe.controller.state.value.block)
        assertEquals(TestConnectionResult.PortTaken(5800, "mill"), lathe.controller.testConnection())
        mill.controller.requestStop()
        assertNull(ports.owners.value[5800])
    }

    @Test
    fun theStateShowsTheFailureAndTheNewServer() = runTest {
        val setup = setup(plain)
        setup.online()
        setup.controller.requestStart()
        runCurrent()
        setup.sockets.open.single().failAccept(IllegalStateException("Socket closed"))
        runCurrent()
        val state = setup.controller.state.value
        assertEquals(DataOutputStatus.Warning, state.status)
        assertEquals(DataOutputErrorType.AcceptFailed, assertIs<SenderState.Failed>(state.sender).error.type)
        assertEquals(DataOutputErrorType.AcceptFailed, state.statistics?.lastError?.type)
        val diagnostics = Diagnostics.of(state, testScheduler.currentTime)
        assertEquals(DataOutputErrorType.AcceptFailed, diagnostics.problem?.type)
        advanceTimeBy(5000L)
        runCurrent()
        assertEquals(1, setup.sockets.open.size)
        assertEquals(DataOutputStatus.Ready, setup.controller.state.value.status)
        assertEquals(SenderState.Ready, setup.controller.state.value.sender)
    }

    @Test
    fun testConnectionReportsThePort() = runTest {
        val setup = setup(plain)
        assertEquals(TestConnectionResult.PortFree(5800, listOf("192.168.1.20:5800")), setup.controller.testConnection())
        // The test binds and closes the socket.
        assertTrue(setup.sockets.sockets.single().closed)
        setup.sockets.bindFailure = { _, _ -> IllegalStateException("Address already in use") }
        assertEquals(
            TestConnectionResult.PortNotFree(5800, "192.168.1.20", "Address already in use"),
            setup.controller.testConnection(),
        )
        setup.sockets.bindFailure = { _, _ -> null }
        setup.interfaces.interfaces.value = emptyList()
        assertEquals(TestConnectionResult.NoAddress(BindInterface.WiFi), setup.controller.testConnection())
        assertEquals(
            TestConnectionResult.InvalidConfig(listOf(ConfigProblem.Port(22))),
            setup.controller.testConnection(plain.copy(port = 22)),
        )
    }

    @Test
    fun testConnectionOfARunningOutputReportsItsClients() = runTest {
        val setup = setup(plain)
        setup.online()
        setup.controller.requestStart()
        runCurrent()
        setup.sockets.open.single().connect()
        runCurrent()
        assertEquals(TestConnectionResult.Running(5800, listOf("192.168.1.20:5800"), 1), setup.controller.testConnection())
    }

    @Test
    fun theMqttOutputPublishesTheJsonRecordWithTheStoredCredentials() = runTest {
        val config = DataOutputConfig(enabled = true, transport = DataOutputTransport.Mqtt, mqttBrokerHost = "broker.local")
        val setup = setup(config)
        setup.controller.secrets.setMqttCredentials("dro", "secret".encodeToByteArray())
        setup.online()
        setup.controller.requestStart()
        runCurrent()
        val params = setup.mqtt.params.single()
        assertEquals("ssl://broker.local:8883", params.serverUri)
        assertEquals("dro", params.credentials?.username)
        assertEquals("secret", params.credentials?.password?.decodeToString())
        assertEquals("superdro-p1", params.clientId)
        assertTrue(setup.sockets.sockets.isEmpty())
        assertEquals(SenderState.Active, setup.controller.state.value.sender)
        advanceTimeBy(500L)
        runCurrent()
        assertEquals(
            listOf(
                "superdro/Mill" to
                    "{\"timestamp\":\"2026-10-11T14:03:22.517Z\",\"sequence\":0,\"x_absolute\":12.7000,\"y_absolute\":-3.1750,\"z_absolute\":0.0000}",
            ),
            setup.mqtt.connections.single().messages,
        )
    }

    @Test
    fun theMqttFieldLayoutPublishesEachField() = runTest {
        val config = DataOutputConfig(
            enabled = true, transport = DataOutputTransport.Mqtt, mqttBrokerHost = "broker.local",
            mqttMachineName = "mill-1", mqttTopicLayout = MqttTopicLayout.TopicPerField, timestampEnabled = false,
        )
        val setup = setup(config)
        setup.online()
        setup.controller.requestStart()
        runCurrent()
        advanceTimeBy(500L)
        runCurrent()
        assertEquals(
            listOf("superdro/mill-1/x_absolute" to "12.7000", "superdro/mill-1/y_absolute" to "-3.1750", "superdro/mill-1/z_absolute" to "0.0000"),
            setup.mqtt.connections.single().messages,
        )
    }

    @Test
    fun aBrokerFailureGivesFailedAndANewLinkAfter5Seconds() = runTest {
        val config = DataOutputConfig(enabled = true, transport = DataOutputTransport.Mqtt, mqttBrokerHost = "broker.local")
        val setup = setup(config)
        setup.mqtt.failure = MqttFailure(DataOutputErrorType.AuthRejected, "Not authorized")
        setup.online()
        setup.controller.requestStart()
        runCurrent()
        assertEquals(DataOutputErrorType.AuthRejected, assertIs<SenderState.Failed>(setup.controller.state.value.sender).error.type)
        setup.mqtt.failure = null
        advanceTimeBy(5000L)
        runCurrent()
        assertEquals(SenderState.Active, setup.controller.state.value.sender)
        setup.mqtt.connections.single().lose(IllegalStateException("Connection reset"))
        runCurrent()
        val failed = assertIs<SenderState.Failed>(setup.controller.state.value.sender)
        assertEquals(DataOutputErrorType.ConnectionLost, failed.error.type)
        assertEquals("Connection reset", failed.error.detail)
        assertTrue(setup.mqtt.connections.single().closed)
        advanceTimeBy(5000L)
        runCurrent()
        assertEquals(2, setup.mqtt.connections.size)
    }

    @Test
    fun aBrokerLinkWithNoTlsStartsOnlyAfterTheConfirmation() = runTest {
        val config = DataOutputConfig(
            enabled = true, transport = DataOutputTransport.Mqtt, mqttBrokerHost = "broker.local", mqttBrokerPort = 1883, mqttTls = false,
        )
        val setup = setup(config)
        setup.online()
        setup.controller.requestStart()
        runCurrent()
        assertEquals(StartBlock.MqttWithoutTlsNotConfirmed, setup.controller.state.value.block)
        assertTrue(setup.mqtt.params.isEmpty())
        setup.controller.setConfig(config.copy(mqttNoTlsConfirmed = true))
        runCurrent()
        assertEquals("tcp://broker.local:1883", setup.mqtt.params.single().serverUri)
    }

    @Test
    fun testConnectionReportsTheBroker() = runTest {
        val config = DataOutputConfig(transport = DataOutputTransport.Mqtt, mqttBrokerHost = "broker.local")
        val setup = setup(config)
        assertEquals(TestConnectionResult.BrokerReachable("broker.local", 8883, true), setup.controller.testConnection())
        assertTrue(setup.mqtt.connections.single().closed)
        setup.mqtt.failure = MqttFailure(DataOutputErrorType.TlsFailure, "PKIX path building failed")
        val failed = assertIs<TestConnectionResult.BrokerFailed>(setup.controller.testConnection())
        assertEquals(DataOutputErrorType.TlsFailure, failed.error.type)
        assertEquals("broker.local", failed.error.host)
    }
}
