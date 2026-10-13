package org.superdro.core.ui.readout

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.SessionState
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.Workspace
import org.superdro.core.store.valueOrNull
import org.superdro.core.ui.presenter.PresenterContext
import org.superdro.core.ui.sound.SoundController
import org.superdro.core.ui.sound.UiSound
import org.superdro.core.ui.sound.UiSounds
import org.superdro.core.ui.testing.FakeConnections
import org.superdro.core.ui.testing.FakeDeviceSession
import org.superdro.core.ui.testing.UiStoreFixture
import org.superdro.core.util.Clock

/** The sounds that a test heard. */
class RecordingSounds : UiSounds {
    val played = mutableListOf<UiSound>()
    override fun play(sound: UiSound) {
        played += sound
    }

    override fun proximity(level: Double) = Unit
}

/**
 * A readout presenter on a database in memory, with a fake session. All work runs on the test
 * scheduler of [scope] (virtual time).
 */
class ReadoutHarness(private val scope: TestScope) {
    val store = UiStoreFixture(scope)
    val connections = FakeConnections()
    val sounds = RecordingSounds()
    val clock = Clock { scope.testScheduler.currentTime }
    val soundController = SoundController(sounds, store.appSettings.settings, MutableStateFlow(emptySet()), clock)
    lateinit var profile: MachineProfile
    lateinit var session: FakeDeviceSession
    lateinit var presenter: ReadoutPresenter

    /** Stores [machine] with the axis states [states] and starts the presenter of its tab. */
    suspend fun start(
        machine: MachineProfile = MachineProfile.create(MachineType.VerticalMill, "Mill"),
        states: Map<AxisId, AxisState> = emptyMap(),
        state: SessionState = SessionState.Connected(0L),
        withSession: Boolean = true,
    ): ReadoutPresenter {
        val workspace = machine.workspaces.first().let { base -> base.copy(axisStates = base.axisStates + states) }
        val stored = machine.copy(workspaces = listOf(workspace), activeWorkspaceId = workspace.id)
        profile = store.machines.createProfile(stored).valueOrNull() ?: error("The profile was not stored.")
        if (withSession) {
            session = FakeDeviceSession(DeviceId(profile.id), profile.name, initialState = state)
            connections.add(session)
        }
        presenter = ReadoutPresenter(
            deviceId = DeviceId(profile.id),
            connections = connections,
            machines = store.machines,
            adapters = store.adapters,
            tools = store.tools,
            appSettings = store.appSettings,
            sounds = soundController,
            context = PresenterContext(store.dispatcher, clock),
        )
        scope.advanceUntilIdle()
        return presenter
    }

    /** The stored active workspace. */
    suspend fun workspace(): Workspace = store.machines.loadWorkspace(profile.activeWorkspaceId) ?: error("No workspace.")

    /** The stored state of [axis]. */
    suspend fun axisState(axis: AxisId): AxisState = workspace().axisStates.getValue(axis)

    /** Sets the sums `R` and lets the flows run. */
    fun inputs(vararg values: Pair<AxisId, Double>) {
        session.setAxes(values.toMap())
        scope.advanceUntilIdle()
    }

    /** The text of the value of [axis], with the sign. */
    fun text(axis: AxisId): String = presenter.axisValue(axis).value.text

    fun close() {
        if (::presenter.isInitialized) presenter.close()
        store.close()
    }
}
