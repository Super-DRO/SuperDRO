package org.superdro.core.ui.graphics

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.superdro.core.connection.AdapterInfo
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.DeviceRef
import org.superdro.core.connection.DeviceSession
import org.superdro.core.connection.RestartResult
import org.superdro.core.connection.SessionEvent
import org.superdro.core.connection.SessionState
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.SuperDroJson
import org.superdro.core.model.Workspace
import org.superdro.core.protocol.AdapterCommand
import org.superdro.core.protocol.AdapterMessage
import org.superdro.core.protocol.CommandResult
import org.superdro.core.readout.input.InputConfiguration
import org.superdro.core.readout.input.InputHistory
import org.superdro.core.readout.input.InputSnapshot
import org.superdro.core.readout.input.InputStage
import org.superdro.core.readout.input.ProbeEvent
import org.superdro.core.store.repository.DrawingRepository
import org.superdro.core.store.repository.MeasurementLogRepository
import org.superdro.core.store.repository.ProbeRepository
import org.superdro.core.store.valueOrNull
import org.superdro.core.ui.presenter.PresenterContext
import org.superdro.core.ui.readout.ReadoutPresenter
import org.superdro.core.ui.sound.SoundController
import org.superdro.core.ui.sound.UiSound
import org.superdro.core.ui.sound.UiSounds
import org.superdro.core.ui.session.Connections
import org.superdro.core.ui.testing.UiStoreFixture
import org.superdro.core.util.Clock
import org.superdro.core.util.FilePicker
import org.superdro.core.util.PickedFile

/** The sounds and the proximity levels that a test heard. */
class GraphicsSounds : UiSounds {
    val played = mutableListOf<UiSound>()
    val proximityLevels = mutableListOf<Double>()

    override fun play(sound: UiSound) {
        played += sound
    }

    override fun proximity(level: Double) {
        proximityLevels += level
    }
}

/**
 * A [FilePicker] for the tests: [openDocument] and [capturePhoto] give the next file of [picks],
 * and [createDocument] keeps the files that the UI saved.
 */
class RecordingFilePicker : FilePicker {
    val picks = ArrayDeque<PickedFile?>()
    val photos = ArrayDeque<PickedFile?>()
    val created = mutableListOf<Triple<String, String, ByteArray>>()
    val shared = mutableListOf<Triple<String, String, ByteArray>>()
    val mimeRequests = mutableListOf<List<String>>()

    override suspend fun openDocument(mimeTypes: List<String>): PickedFile? {
        mimeRequests += mimeTypes
        return picks.removeFirstOrNull()
    }

    override suspend fun createDocument(suggestedName: String, mimeType: String, content: ByteArray): Boolean {
        created += Triple(suggestedName, mimeType, content)
        return true
    }

    override suspend fun capturePhoto(): PickedFile? = photos.removeFirstOrNull()

    override suspend fun share(name: String, mimeType: String, content: ByteArray): Boolean {
        shared += Triple(name, mimeType, content)
        return true
    }
}

/**
 * A [DeviceSession] with a real [InputStage]: a test moves an axis with [move] and closes the
 * probe with [probe]. The input history, the inputs, and the probe events then come from the
 * input stage, as in the real session.
 *
 * The default encoder profiles have 25400 counts for each inch, so one count is 1 µm.
 */
class FakeProbeSession(
    override val id: DeviceId,
    configuration: InputConfiguration,
    initialState: SessionState = SessionState.Connected(0L),
) : DeviceSession {
    val stage = InputStage(configuration)
    override val device = MutableStateFlow(DeviceRef(id, id.value, AdapterAddress.Fake("still")))
    override val state = MutableStateFlow(initialState)
    override val inputs = MutableStateFlow(InputSnapshot.EMPTY)
    private val probeFlow = MutableSharedFlow<ProbeEvent>(extraBufferCapacity = 64)
    override val probeEvents: SharedFlow<ProbeEvent> = probeFlow
    override val adapterInfo = MutableStateFlow(AdapterInfo())
    override val history: InputHistory get() = stage.history
    override val messages: SharedFlow<AdapterMessage> = MutableSharedFlow()
    override val events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 16)

    /** The probe events that the session sent. */
    val sentEvents = mutableListOf<ProbeEvent>()

    override suspend fun send(command: AdapterCommand): CommandResult = CommandResult.Cancelled
    override suspend fun restartAdapter(): RestartResult = RestartResult.NoSession
    override fun openAdapterDetails() = Unit
    override fun closeAdapterDetails() = Unit
    override suspend fun <T> readHistory(block: (InputHistory) -> T): T = block(stage.history)

    /** One count sample of the input [channel] (for example 'x') at [timeMs]. */
    fun move(channel: Char, count: Long, timeMs: Long) {
        emit(stage.onAxisCount(channel, count, timeMs))
    }

    /** The probe message `P1;` (or `P0;`) on [port] at [timeMs]. */
    fun probe(active: Boolean, timeMs: Long, port: Char = 'p') {
        emit(stage.onBinaryInput(port, if (active) 1 else 0, timeMs))
    }

    /** A tick of the input stage at [nowMs] (the latch deadline and the watchdog). */
    fun tick(nowMs: Long) {
        emit(stage.onTick(nowMs))
    }

    private fun emit(events: List<ProbeEvent>) {
        inputs.value = stage.snapshot
        events.forEach { event ->
            sentEvents += event
            check(probeFlow.tryEmit(event)) { "The probe event buffer is full." }
        }
    }
}

/**
 * The stores, a fake probe session, a readout presenter, and the [GraphicsDependencies] of one
 * machine for the presenter tests of the graphics area. All work runs on the test scheduler of
 * [scope] (virtual time).
 *
 * @param fileDispatcher The file dispatcher of the presenters. Null gives the dispatcher of the store.
 */
class GraphicsHarness(private val scope: TestScope, fileDispatcher: CoroutineContext? = null) {
    val store = UiStoreFixture(scope)
    val probes = ProbeRepository(store.writer, SuperDroJson, store.wallClock)
    val drawings = DrawingRepository(store.writer)
    val measurementLog = MeasurementLogRepository(store.writer, SuperDroJson, store.wallClock)
    val connections = GraphicsConnections()
    val sounds = GraphicsSounds()
    val clock = Clock { scope.testScheduler.currentTime }
    val soundController = SoundController(sounds, store.appSettings.settings, MutableStateFlow(emptySet()), clock)
    val filePicker = RecordingFilePicker()
    val context = PresenterContext(store.dispatcher, clock, fileDispatcher ?: store.dispatcher)
    lateinit var profile: MachineProfile
    lateinit var adapter: AdapterProfile
    lateinit var session: FakeProbeSession
    lateinit var readout: ReadoutPresenter
    lateinit var deps: GraphicsDependencies

    val deviceId: DeviceId get() = DeviceId(profile.id)

    /**
     * Stores [machine] with the axis states [states], starts a session in [state], and starts the
     * readout presenter of its tab.
     */
    suspend fun start(
        machine: MachineProfile = MachineProfile.create(MachineType.VerticalMill, "Mill"),
        states: Map<AxisId, AxisState> = emptyMap(),
        state: SessionState = SessionState.Connected(0L),
        withSession: Boolean = true,
    ): ReadoutPresenter {
        adapter = store.adapters.saveAdapterProfile(AdapterProfile(alias = "Test")).valueOrNull() ?: error("No adapter profile.")
        val workspace = machine.workspaces.first().let { base -> base.copy(axisStates = base.axisStates + states) }
        val stored = machine.copy(workspaces = listOf(workspace), activeWorkspaceId = workspace.id, adapterProfileId = adapter.id)
        profile = store.machines.createProfile(stored).valueOrNull() ?: error("The profile was not stored.")
        if (withSession) {
            session = FakeProbeSession(DeviceId(profile.id), InputConfiguration.from(profile, adapter), state)
            connections.add(session)
        }
        readout = ReadoutPresenter(
            deviceId = DeviceId(profile.id),
            connections = connections,
            machines = store.machines,
            adapters = store.adapters,
            tools = store.tools,
            appSettings = store.appSettings,
            sounds = soundController,
            context = context,
            probes = probes,
        )
        deps = GraphicsDependencies(
            connections = connections,
            machines = store.machines,
            adapters = store.adapters,
            tools = store.tools,
            probes = probes,
            drawings = drawings,
            measurementLog = measurementLog,
            storeIO = store.storeIO,
            filePicker = filePicker,
            sounds = soundController,
            appSettings = store.appSettings,
            context = context,
            wallClock = store.wallClock,
            utcOffsetSeconds = { 0 },
        )
        scope.advanceUntilIdle()
        return readout
    }

    /** The stored active workspace. */
    suspend fun workspace(): Workspace = store.machines.loadWorkspace(profile.activeWorkspaceId) ?: error("No workspace.")

    /** The stored profile with its workspaces. */
    suspend fun fullProfile(): MachineProfile = store.machines.loadFullProfile(profile.id) ?: error("No profile.")

    /** Changes the stored profile with [change] and lets the flows run. */
    suspend fun updateProfile(change: (MachineProfile) -> MachineProfile) {
        store.machines.updateProfile(profile.id, change)
        scope.advanceUntilIdle()
    }

    /** Changes the stored active workspace with [change] and lets the flows run. */
    suspend fun updateWorkspace(change: (Workspace) -> Workspace) {
        store.machines.updateWorkspace(profile.activeWorkspaceId, change)
        scope.advanceUntilIdle()
    }

    /** Moves the axes to the counts (1 count = 1 µm) at [timeMs] and lets the flows run. */
    fun moveTo(timeMs: Long, x: Long? = null, y: Long? = null, z: Long? = null) {
        x?.let { count -> session.move('x', count, timeMs) }
        y?.let { count -> session.move('y', count, timeMs) }
        z?.let { count -> session.move('z', count, timeMs) }
        scope.advanceUntilIdle()
    }

    fun close() {
        if (::readout.isInitialized) readout.close()
        store.close()
    }
}

/** A [Connections] for the graphics tests that takes any [DeviceSession]. */
class GraphicsConnections : Connections {
    private val mutable = MutableStateFlow<Map<DeviceId, DeviceSession>>(emptyMap())
    override val sessions: StateFlow<Map<DeviceId, DeviceSession>> = mutable

    /** Adds [session] as if the manager started it. */
    fun add(session: DeviceSession) {
        mutable.value = mutable.value + (session.id to session)
    }

    override fun connect(device: DeviceRef) = Unit
    override fun disconnect(id: DeviceId) = Unit
    override fun disconnectAll() = Unit
    override fun remove(id: DeviceId) {
        mutable.value = mutable.value - id
    }

    override fun setRecordStream(id: DeviceId, on: Boolean) = Unit
}
