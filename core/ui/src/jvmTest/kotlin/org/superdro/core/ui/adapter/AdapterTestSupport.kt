package org.superdro.core.ui.adapter

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.datetime.TimeZone
import org.superdro.core.connection.AdapterInfo
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.DeviceRef
import org.superdro.core.connection.DeviceSession
import org.superdro.core.connection.RestartResult
import org.superdro.core.connection.SessionEvent
import org.superdro.core.connection.SessionState
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.AppSettings
import org.superdro.core.model.Channels
import org.superdro.core.model.EncoderProfile
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.SuperDroJson
import org.superdro.core.protocol.AdapterCommand
import org.superdro.core.protocol.AdapterMessage
import org.superdro.core.protocol.CommandResult
import org.superdro.core.readout.input.InputHistory
import org.superdro.core.readout.input.InputSnapshot
import org.superdro.core.readout.input.InputConfiguration
import org.superdro.core.readout.input.InputStage
import org.superdro.core.readout.input.ProbeEvent
import org.superdro.core.store.repository.ProbeRepository
import org.superdro.core.store.valueOrNull
import org.superdro.core.ui.sound.SoundController
import org.superdro.core.ui.sound.UiSound
import org.superdro.core.ui.sound.UiSounds
import org.superdro.core.ui.tabs.testPresenterContext
import org.superdro.core.ui.session.Connections
import org.superdro.core.ui.testing.UiStoreFixture
import org.superdro.core.util.FilePicker
import org.superdro.core.util.PickedFile
import org.superdro.core.readout.input.ChannelValue
import kotlinx.coroutines.flow.SharedFlow

/**
 * A [DeviceSession] for the tests of the adapter area. The test sets the flows directly and
 * selects the result of each command.
 *
 * - [send] records the command, waits [sendDelayMs] of virtual time, and gives [result] of the
 *   command.
 * - [stage] is a real input stage with the default encoder profile of each input: a test feeds
 *   it to fill [history] (the refresh rate, the calibration samples, the probe positions).
 */
class ScriptedSession(
    override val id: DeviceId,
    initialState: SessionState = SessionState.Connected(0L),
    address: AdapterAddress = AdapterAddress.Fake("still"),
) : DeviceSession {
    val stage = InputStage(InputConfiguration(encoders = Channels.encoderInputs.associateWith { input -> EncoderProfile.defaultFor(input) }))
    override val device = MutableStateFlow(DeviceRef(id, id.value, address))
    override val state = MutableStateFlow(initialState)
    override val inputs = MutableStateFlow(InputSnapshot.EMPTY)
    private val sampleFlow = MutableSharedFlow<ChannelValue>(extraBufferCapacity = DeviceSession.SAMPLE_BUFFER)
    override val samples: SharedFlow<ChannelValue> = sampleFlow
    override val probeEvents = MutableSharedFlow<ProbeEvent>(extraBufferCapacity = 64)
    override val adapterInfo = MutableStateFlow(AdapterInfo())
    override val history: InputHistory get() = stage.history
    override val messages = MutableSharedFlow<AdapterMessage>(extraBufferCapacity = 64)
    override val events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 64)

    /** The commands in the order of [send]. */
    val sent = mutableListOf<AdapterCommand>()

    /** The virtual time of each [send] before its result. */
    var sendDelayMs: Long = 0L

    /** The result of each command. */
    var result: (AdapterCommand) -> CommandResult = { CommandResult.Completed }

    /** The result of [restartAdapter]. */
    var restartResult: RestartResult = RestartResult.Accepted

    /** The number of calls of [openAdapterDetails] and [closeAdapterDetails]. */
    var detailsOpened: Int = 0
    var detailsClosed: Int = 0

    override suspend fun send(command: AdapterCommand): CommandResult {
        sent += command
        if (sendDelayMs > 0L) delay(sendDelayMs)
        return result(command)
    }

    override suspend fun restartAdapter(): RestartResult = restartResult

    override fun openAdapterDetails() {
        detailsOpened++
    }

    override fun closeAdapterDetails() {
        detailsClosed++
    }

    override suspend fun <T> readHistory(block: (InputHistory) -> T): T = block(history)

    /**
     * Sets [snapshot] as the inputs and gives each of its channel values as one sample, as the
     * input stage of a real session does. The inputs conflate; the samples do not.
     */
    fun push(snapshot: InputSnapshot) {
        inputs.value = snapshot
        snapshot.channels.values.forEach { value -> check(sampleFlow.tryEmit(value)) { "The sample buffer is full." } }
    }
}

/** A [Connections] for the tests of the adapter area, with [ScriptedSession] objects. */
class ScriptedConnections : Connections {
    private val mutable = MutableStateFlow<Map<DeviceId, DeviceSession>>(emptyMap())
    override val sessions: StateFlow<Map<DeviceId, DeviceSession>> = mutable

    /** The calls in order, for example `disconnect mill`. */
    val calls = mutableListOf<String>()

    /** Adds [session] as if the manager started it. */
    fun add(session: DeviceSession) {
        mutable.value = mutable.value + (session.id to session)
    }

    /** Removes the session of [id], as if the manager stopped it. */
    fun drop(id: DeviceId) {
        mutable.value = mutable.value - id
    }

    override fun connect(device: DeviceRef) {
        calls += "connect ${device.id}"
    }

    override fun disconnect(id: DeviceId) {
        calls += "disconnect $id"
        (mutable.value[id] as? ScriptedSession)?.state?.value = SessionState.Disconnected
    }

    override fun disconnectAll() {
        calls += "disconnectAll"
    }

    override fun remove(id: DeviceId) {
        calls += "remove $id"
        mutable.value = mutable.value - id
    }

    override fun setRecordStream(id: DeviceId, on: Boolean) {
        calls += "record $id $on"
    }
}

/** A [FilePicker] that records each call. The test sets [nextOpen]. */
class RecordingFilePicker : FilePicker {
    /** The file of the next [openDocument], or null for a cancel. */
    var nextOpen: PickedFile? = null

    /** The MIME types of each [openDocument]. */
    val opened = mutableListOf<List<String>>()

    /** Each written file: the name, the MIME type, and the text. */
    val created = mutableListOf<Triple<String, String, String>>()

    /** The result of [createDocument]. */
    var createResult: Boolean = true

    override suspend fun openDocument(mimeTypes: List<String>): PickedFile? {
        opened += mimeTypes
        return nextOpen
    }

    override suspend fun createDocument(suggestedName: String, mimeType: String, content: ByteArray): Boolean {
        created += Triple(suggestedName, mimeType, content.decodeToString())
        return createResult
    }

    override suspend fun capturePhoto(): PickedFile? = null

    override suspend fun share(name: String, mimeType: String, content: ByteArray): Boolean = false
}

/** A [UiSounds] that records each sound. */
class RecordingSounds : UiSounds {
    val played = mutableListOf<UiSound>()
    override fun play(sound: UiSound) {
        played += sound
    }

    override fun proximity(level: Double) = Unit
}

/**
 * The stores, the fake connections, the file picker, and the sounds of a test of the adapter area,
 * on one database in memory. All work runs on the test dispatcher, so a test uses virtual time.
 */
class AdapterFixture(scope: TestScope) {
    val store = UiStoreFixture(scope)
    val probes = ProbeRepository(store.writer, SuperDroJson, store.wallClock)
    val connections = ScriptedConnections()
    val files = RecordingFilePicker()
    val uiSounds = RecordingSounds()
    val sounds = SoundController(uiSounds, MutableStateFlow(AppSettings()), MutableStateFlow(emptySet()), { scope.testScheduler.currentTime })
    val deps = AdapterUiDeps(
        connections = connections,
        machines = store.machines,
        adapters = store.adapters,
        probes = probes,
        filePicker = files,
        sounds = sounds,
        context = scope.testPresenterContext(),
        wallClock = store.wallClock,
        timeZone = TimeZone.UTC,
        appVersion = "1.0.0",
    )

    /** Adds [session] to the fake connections and gives it. */
    fun connect(session: ScriptedSession): ScriptedSession = session.also { connections.add(it) }

    /**
     * Writes an adapter profile [adapter] and a machine profile with the ID [deviceId] that
     * selects it. Gives the stored adapter profile.
     */
    suspend fun machineWithAdapter(
        deviceId: DeviceId,
        adapter: AdapterProfile = AdapterProfile(alias = "Test adapter", model = "tda-test"),
        type: MachineType = MachineType.VerticalMill,
        configure: (MachineProfile) -> MachineProfile = { it },
    ): AdapterProfile {
        val stored = store.adapters.saveAdapterProfile(adapter).valueOrNull()!!
        val base = MachineProfile.create(type, "Mill ${deviceId.value}")
        store.machines.createProfile(configure(base.copy(id = deviceId.value, adapterProfileId = stored.id)))
        return stored
    }

    /** Writes a machine profile with the ID [deviceId] and no adapter profile. */
    suspend fun machineWithoutAdapter(deviceId: DeviceId, type: MachineType = MachineType.VerticalMill, configure: (MachineProfile) -> MachineProfile = { it }) {
        store.machines.createProfile(configure(MachineProfile.create(type, "Mill ${deviceId.value}").copy(id = deviceId.value)))
    }

    fun close() = store.close()
}
