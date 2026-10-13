package org.superdro.core.ui.testing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.Properties
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.readByteArray
import org.superdro.core.connection.AdapterInfo
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.DeviceRef
import org.superdro.core.connection.DeviceSession
import org.superdro.core.connection.RestartResult
import org.superdro.core.connection.SessionEvent
import org.superdro.core.connection.SessionState
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.AxisId
import org.superdro.core.model.SuperDroJson
import org.superdro.core.protocol.AdapterCommand
import org.superdro.core.protocol.AdapterMessage
import org.superdro.core.protocol.CommandResult
import org.superdro.core.readout.input.AxisValue
import org.superdro.core.readout.input.AxisValueStatus
import org.superdro.core.readout.input.InputHistory
import org.superdro.core.readout.input.InputSnapshot
import org.superdro.core.readout.input.InputStage
import org.superdro.core.readout.input.ProbeEvent
import org.superdro.core.store.StoreIOProvider
import org.superdro.core.store.StoreWriter
import org.superdro.core.store.createSuperDroDatabase
import org.superdro.core.store.db.SuperDroDatabase
import org.superdro.core.store.json.AppSettingsStore
import org.superdro.core.store.json.TabStore
import org.superdro.core.store.repository.AdapterRepository
import org.superdro.core.store.repository.MachineRepository
import org.superdro.core.store.repository.ToolRepository
import org.superdro.core.ui.session.Connections

/**
 * A [DeviceSession] for the UI tests. The test sets [state] and [inputs] directly. [send] gives
 * `Cancelled` with no link, as the real session.
 */
class FakeDeviceSession(
    override val id: DeviceId,
    name: String = id.value,
    address: AdapterAddress = AdapterAddress.Fake("still"),
    initialState: SessionState = SessionState.Connected(0L),
) : DeviceSession {
    override val device = MutableStateFlow(DeviceRef(id, name, address))
    override val state = MutableStateFlow(initialState)
    override val inputs = MutableStateFlow(InputSnapshot.EMPTY)
    override val probeEvents: SharedFlow<ProbeEvent> = MutableSharedFlow()
    override val adapterInfo = MutableStateFlow(AdapterInfo())
    override val history: InputHistory = InputStage().history
    override val messages: SharedFlow<AdapterMessage> = MutableSharedFlow()
    override val events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 16)

    /** The commands that the UI sent. */
    val sent = mutableListOf<AdapterCommand>()

    override suspend fun send(command: AdapterCommand): CommandResult {
        sent += command
        return CommandResult.Cancelled
    }

    override suspend fun restartAdapter(): RestartResult = RestartResult.NoSession
    override fun openAdapterDetails() = Unit
    override fun closeAdapterDetails() = Unit
    override suspend fun <T> readHistory(block: (InputHistory) -> T): T = block(history)

    /** Sets the sum `R` of each axis of [values] (µm, arc seconds, or RPM) with the status Ok. */
    fun setAxes(values: Map<AxisId, Double>, timeMs: Long = 0L) {
        inputs.value = InputSnapshot(axes = values.mapValues { (axis, value) -> AxisValue(axis, value, timeMs, AxisValueStatus.Ok) })
    }

    /** Sets the status of [axis] with no value. */
    fun setAxisStatus(axis: AxisId, status: AxisValueStatus) {
        inputs.value = inputs.value.copy(axes = inputs.value.axes + (axis to AxisValue(axis, null, null, status)))
    }
}

/** A [Connections] for the tests: [connect] makes a [FakeDeviceSession] in [connectState]. */
class FakeConnections(var connectState: SessionState = SessionState.Connecting(1)) : Connections {
    private val mutable = MutableStateFlow<Map<DeviceId, DeviceSession>>(emptyMap())
    override val sessions: StateFlow<Map<DeviceId, DeviceSession>> = mutable

    /** The calls in order, for example `connect mill` or `disconnect mill`. */
    val calls = mutableListOf<String>()

    /** Gives the fake session of [id]. */
    fun session(id: DeviceId): FakeDeviceSession = mutable.value.getValue(id) as FakeDeviceSession

    /** Adds [session] as if the manager started it. */
    fun add(session: FakeDeviceSession) {
        mutable.value = mutable.value + (session.id to session)
    }

    override fun connect(device: DeviceRef) {
        calls += "connect ${device.id}"
        val existing = mutable.value[device.id] as FakeDeviceSession?
        if (existing == null) {
            add(FakeDeviceSession(device.id, device.name, device.address, connectState))
        } else {
            existing.device.value = device
            if (existing.state.value == SessionState.Disconnected) existing.state.value = connectState
        }
    }

    override fun disconnect(id: DeviceId) {
        calls += "disconnect $id"
        (mutable.value[id] as FakeDeviceSession?)?.state?.value = SessionState.Disconnected
    }

    override fun disconnectAll() {
        calls += "disconnectAll"
        mutable.value.values.forEach { session -> (session as FakeDeviceSession).state.value = SessionState.Disconnected }
    }

    override fun remove(id: DeviceId) {
        calls += "remove $id"
        mutable.value = mutable.value - id
    }

    /** As the manager: a new link with the flag, and the session stays in its state. */
    override fun setRecordStream(id: DeviceId, on: Boolean) {
        calls += "record $id $on"
        val existing = mutable.value[id] as FakeDeviceSession? ?: return
        existing.device.value = existing.device.value.copy(recordStream = on)
    }
}

/** A [StoreIOProvider] in memory. */
class MemoryStoreIO : StoreIOProvider {
    val files: MutableMap<String, ByteArray> = linkedMapOf()

    override fun exists(storeName: String): Boolean = storeName in files

    override fun sinkFor(storeName: String): RawSink = object : RawSink {
        private val buffer = Buffer()
        override fun write(source: Buffer, byteCount: Long) = buffer.write(source, byteCount)
        override fun flush() = Unit
        override fun close() {
            files[storeName] = buffer.readByteArray()
        }
    }

    override fun sourceFor(storeName: String): RawSource {
        val bytes = files[storeName] ?: throw IOException("No file $storeName.")
        return Buffer().apply { write(bytes) }
    }

    override fun sourceForUri(uri: String): RawSource = sourceFor(uri)
    override fun pathFor(storeName: String): String = "/memory/$storeName"
    override fun delete(storeName: String) {
        files.remove(storeName)
    }
}

/** A wall clock for the tests. */
class FixedWallClock(var current: Instant = Instant.parse("2026-10-12T08:00:00Z")) : Clock {
    override fun now(): Instant = current
}

/**
 * The stores and the repositories on one database in memory. All work runs on one test
 * dispatcher of [scope], so a test uses virtual time and `advanceUntilIdle`.
 */
class UiStoreFixture(scope: TestScope) {
    val dispatcher = StandardTestDispatcher(scope.testScheduler)
    val driver: JdbcSqliteDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties().apply { setProperty("foreign_keys", "true") })
        .also { driver -> SuperDroDatabase.Schema.create(driver) }
    val database: SuperDroDatabase = createSuperDroDatabase(driver)
    val writer = StoreWriter(database, dispatcher = dispatcher, readDispatcher = dispatcher)
    val storeIO = MemoryStoreIO()
    val wallClock = FixedWallClock()
    val machines = MachineRepository(writer, storeIO, SuperDroJson, wallClock)
    val adapters = AdapterRepository(writer, storeIO, SuperDroJson, wallClock, fileDispatcher = dispatcher)
    val tools = ToolRepository(writer, SuperDroJson, wallClock)
    val tabs = TabStore(storeIO, SuperDroJson, dispatcher, wallClock)
    val appSettings = AppSettingsStore(storeIO, SuperDroJson, dispatcher, wallClock)

    fun close() = driver.close()
}
