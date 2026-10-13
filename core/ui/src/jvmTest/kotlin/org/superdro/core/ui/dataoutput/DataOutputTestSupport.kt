package org.superdro.core.ui.dataoutput

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.superdro.core.connection.AdapterInfo
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.DeviceRef
import org.superdro.core.connection.DeviceSession
import org.superdro.core.connection.NetworkInterfaceInfo
import org.superdro.core.connection.NetworkInterfaces
import org.superdro.core.connection.RestartResult
import org.superdro.core.connection.SessionEvent
import org.superdro.core.connection.SessionState
import org.superdro.core.dataoutput.ByteLink
import org.superdro.core.dataoutput.DataOutputPlatform
import org.superdro.core.dataoutput.ListenSocket
import org.superdro.core.dataoutput.ListenSocketFactory
import org.superdro.core.dataoutput.WallClock
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.BindInterface
import org.superdro.core.model.Channels
import org.superdro.core.model.EncoderKind
import org.superdro.core.model.EncoderProfile
import org.superdro.core.model.EncoderType
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.protocol.AdapterCommand
import org.superdro.core.protocol.AdapterMessage
import org.superdro.core.protocol.CommandResult
import org.superdro.core.readout.input.InputConfiguration
import org.superdro.core.readout.input.InputHistory
import org.superdro.core.readout.input.InputSnapshot
import org.superdro.core.readout.input.InputStage
import org.superdro.core.readout.input.ProbeEvent
import org.superdro.core.store.valueOrNull
import org.superdro.core.ui.presenter.PresenterContext
import org.superdro.core.ui.tabs.RecordingRequests
import org.superdro.core.ui.session.Connections
import org.superdro.core.ui.testing.UiStoreFixture
import org.superdro.core.util.Clock
import org.superdro.core.util.InMemorySecretStore

/** The sessions of the tests. A test adds and removes them. */
class TestConnections : Connections {
    override val sessions = MutableStateFlow<Map<DeviceId, DeviceSession>>(emptyMap())

    fun add(session: DeviceSession) {
        sessions.value = sessions.value + (session.id to session)
    }

    override fun remove(id: DeviceId) {
        sessions.value = sessions.value - id
    }

    override fun connect(device: DeviceRef) = Unit
    override fun disconnect(id: DeviceId) = Unit
    override fun disconnectAll() = Unit
    override fun setRecordStream(id: DeviceId, on: Boolean) = Unit
}

/** The interfaces of the tests: Wi-Fi, localhost, and a cellular network with no kind. */
class TestInterfaces(
    initial: List<NetworkInterfaceInfo> = listOf(
        NetworkInterfaceInfo("wlan0", BindInterface.WiFi, listOf("192.168.1.20")),
        NetworkInterfaceInfo("lo", BindInterface.Localhost, listOf("127.0.0.1")),
        NetworkInterfaceInfo("rmnet0", null, listOf("100.64.0.9")),
    ),
) : NetworkInterfaces {
    override val interfaces = MutableStateFlow(initial)
}

/** Sockets with no network, for the tests with virtual time. */
class RecordingSockets : ListenSocketFactory {

    /** Each socket that [bind] made, in order. */
    val sockets = mutableListOf<TestListenSocket>()

    /** The sockets that are not closed. */
    val open: List<TestListenSocket> get() = sockets.filter { socket -> !socket.closed }

    override suspend fun bind(host: String, port: Int): ListenSocket = TestListenSocket(host, port).also { socket -> sockets += socket }
}

/** One server socket of [RecordingSockets]. */
class TestListenSocket(override val host: String, override val port: Int) : ListenSocket {
    private val incoming = Channel<ByteLink>(Channel.UNLIMITED)
    var closed = false
        private set

    /** Connects a new client from [remoteHost]. */
    fun connect(remoteHost: String = "192.168.1.30"): TestLink = TestLink(remoteHost).also { link -> incoming.trySend(link) }

    override suspend fun accept(): ByteLink = incoming.receive()

    override fun close() {
        closed = true
        incoming.cancel()
    }
}

/** The server side of one test connection. */
class TestLink(override val remoteHost: String) : ByteLink {
    private val toServer = Channel<ByteArray>(Channel.UNLIMITED)
    private val received = mutableListOf<ByteArray>()
    var closed = false
        private set

    /** The text that the client received. */
    val receivedText: String get() = received.fold(ByteArray(0)) { all, bytes -> all + bytes }.decodeToString()

    override suspend fun read(buffer: ByteArray): Int = toServer.receiveCatching().getOrNull()?.let { chunk ->
        chunk.copyInto(buffer, 0, 0, minOf(chunk.size, buffer.size))
        minOf(chunk.size, buffer.size)
    } ?: -1

    override suspend fun write(bytes: ByteArray) {
        check(!closed) { "The link is closed." }
        received += bytes.copyOf()
    }

    override fun close() {
        closed = true
        toServer.close()
    }
}

/**
 * A device session with a real [InputStage], so that the input history has the samples. The test
 * calls [sample] and [probe].
 */
class StageSession(
    override val id: DeviceId,
    configuration: InputConfiguration,
    initialState: SessionState = SessionState.Connected(0L),
) : DeviceSession {
    private val stage = InputStage(configuration)
    override val device = MutableStateFlow(DeviceRef(id, id.value, AdapterAddress.Fake("still")))
    override val state = MutableStateFlow(initialState)
    override val inputs = MutableStateFlow(InputSnapshot.EMPTY)
    override val probeEvents = MutableSharedFlow<ProbeEvent>(extraBufferCapacity = 16)
    override val adapterInfo = MutableStateFlow(AdapterInfo())
    override val history: InputHistory get() = stage.history
    override val messages: SharedFlow<AdapterMessage> = MutableSharedFlow()
    override val events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 16)

    /** One count of [channel] at [timeMs] (1 µm for each count with the test encoders). */
    fun sample(channel: Char, count: Long, timeMs: Long) {
        stage.onAxisCount(channel, count, timeMs)
        inputs.value = stage.snapshot
    }

    /** Sends one probe event. */
    fun probe(event: ProbeEvent) {
        probeEvents.tryEmit(event)
    }

    override suspend fun send(command: AdapterCommand): CommandResult = CommandResult.Cancelled
    override suspend fun restartAdapter(): RestartResult = RestartResult.NoSession
    override fun openAdapterDetails() = Unit
    override fun closeAdapterDetails() = Unit
    override suspend fun <T> readHistory(block: (InputHistory) -> T): T = block(stage.history)

    companion object {
        /** A linear encoder with 25400 counts for each inch: 1 µm for each count. */
        fun linear(input: Char): EncoderProfile =
            EncoderProfile(input = input, type = EncoderType.Scale, kind = EncoderKind.Linear, resolution = 25400.0)

        /** The configuration of [profile] with X on `x`, Y on `y`, and Z on `z`. */
        fun configuration(profile: MachineProfile): InputConfiguration = InputConfiguration(
            mapOf(Channels.X to linear(Channels.X), Channels.Y to linear(Channels.Y), Channels.Z to linear(Channels.Z)),
            InputConfiguration.axisTerms(profile),
        )
    }
}

/**
 * The data output on a database in memory, with test sockets and virtual time. All work runs on
 * the test scheduler of [scope].
 */
class DataOutputHarness(val scope: TestScope) {
    val store = UiStoreFixture(scope)
    val connections = TestConnections()
    val interfaces = TestInterfaces()
    val sockets = RecordingSockets()
    val secrets = InMemorySecretStore()
    val requests = RecordingRequests()
    val clock = Clock { scope.testScheduler.currentTime }
    val dispatcher = StandardTestDispatcher(scope.testScheduler)
    val platform = DataOutputPlatform(
        networkInterfaces = interfaces,
        secretStore = secrets,
        listenSockets = sockets,
        clock = clock,
        wallClock = WallClock { WALL_START_MS + scope.testScheduler.currentTime },
        secretsDispatcher = dispatcher,
    )
    val runner = DataOutputRunner(store.machines, connections, platform, dispatcher)

    /** Stores a new profile and gives it. */
    suspend fun profile(type: MachineType = MachineType.VerticalMill, name: String = "Mill", change: (MachineProfile) -> MachineProfile = { it }): MachineProfile =
        store.machines.createProfile(change(MachineProfile.create(type, name))).valueOrNull() ?: error("The profile was not stored.")

    /** Adds an online session with a real input stage for [profile]. */
    fun session(profile: MachineProfile, state: SessionState = SessionState.Connected(0L)): StageSession =
        StageSession(DeviceId(profile.id), StageSession.configuration(profile), state).also { session -> connections.add(session) }

    /** Starts the runner in the background scope of the test. */
    fun start() {
        runner.start(scope.backgroundScope)
        settle()
    }

    /** Runs the work that is due now, and a short time more. */
    fun settle(ms: Long = 10L) {
        scope.runCurrent()
        scope.advanceTimeBy(ms)
        scope.runCurrent()
    }

    /** The presenter context of the virtual time. */
    fun presenterContext(): PresenterContext = PresenterContext(dispatcher, clock)

    /** Gives the presenter of the data output screen. */
    fun presenter(): DataOutputPresenter = DataOutputPresenter(runner, store.machines, interfaces, requests, presenterContext())

    fun close() = store.close()

    companion object {
        /** The wall clock at the virtual time 0: 2026-10-11T14:03:22.517Z. */
        const val WALL_START_MS: Long = 1791727402517L
    }
}
