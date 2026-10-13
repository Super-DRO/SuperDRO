package org.superdro.core.ui.readout

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.Properties
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.SessionState
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.SuperDroJson
import org.superdro.core.model.Workspace
import org.superdro.core.store.StoreWriter
import org.superdro.core.store.createSuperDroDatabase
import org.superdro.core.store.db.SuperDroDatabase
import org.superdro.core.store.json.AppSettingsStore
import org.superdro.core.store.repository.AdapterRepository
import org.superdro.core.store.repository.MachineRepository
import org.superdro.core.store.repository.ToolRepository
import org.superdro.core.store.valueOrNull
import org.superdro.core.ui.presenter.PresenterContext
import org.superdro.core.ui.sound.SoundController
import org.superdro.core.ui.testing.FakeConnections
import org.superdro.core.ui.testing.FakeDeviceSession
import org.superdro.core.ui.testing.FixedWallClock
import org.superdro.core.ui.testing.MemoryStoreIO
import org.superdro.core.util.Clock

/**
 * A readout presenter for the Compose UI tests. The store and the presenter run on an unconfined
 * test dispatcher: each write and each flow runs at once on the calling thread. The timers (the
 * undo of 10 s) use the virtual time of [scheduler].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveReadout(machine: MachineProfile, states: Map<AxisId, AxisState> = emptyMap(), state: SessionState = SessionState.Connected(0L)) {
    val scheduler = TestCoroutineScheduler()
    private val dispatcher = UnconfinedTestDispatcher(scheduler)
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties().apply { setProperty("foreign_keys", "true") })
        .also { driver -> SuperDroDatabase.Schema.create(driver) }
    private val writer = StoreWriter(createSuperDroDatabase(driver), dispatcher = dispatcher, readDispatcher = dispatcher)
    private val storeIO = MemoryStoreIO()
    private val wallClock = FixedWallClock()
    val machines = MachineRepository(writer, storeIO, SuperDroJson, wallClock)
    private val adapters = AdapterRepository(writer, storeIO, SuperDroJson, wallClock, fileDispatcher = dispatcher)
    private val tools = ToolRepository(writer, SuperDroJson, wallClock)
    val appSettings = AppSettingsStore(storeIO, SuperDroJson, dispatcher, wallClock)
    val connections = FakeConnections()
    val sounds = RecordingSounds()
    private val clock = Clock { scheduler.currentTime }
    val profile: MachineProfile
    val session: FakeDeviceSession
    val presenter: ReadoutPresenter

    init {
        val workspace = machine.workspaces.first().let { base -> base.copy(axisStates = base.axisStates + states) }
        profile = runBlocking { machines.createProfile(machine.copy(workspaces = listOf(workspace), activeWorkspaceId = workspace.id)).valueOrNull() }
            ?: error("The profile was not stored.")
        session = FakeDeviceSession(DeviceId(profile.id), profile.name, initialState = state)
        connections.add(session)
        presenter = ReadoutPresenter(
            deviceId = DeviceId(profile.id),
            connections = connections,
            machines = machines,
            adapters = adapters,
            tools = tools,
            appSettings = appSettings,
            sounds = SoundController(sounds, appSettings.settings, MutableStateFlow(emptySet()), clock),
            context = PresenterContext(dispatcher, clock),
        )
        scheduler.runCurrent()
    }

    /** The stored active workspace. */
    fun workspace(): Workspace = runBlocking { machines.loadWorkspace(profile.activeWorkspaceId) } ?: error("No workspace.")

    /** Sets the sums `R` of the axes. */
    fun inputs(vararg values: Pair<AxisId, Double>) {
        session.setAxes(values.toMap())
        scheduler.runCurrent()
    }

    fun close() {
        presenter.close()
        driver.close()
    }
}
