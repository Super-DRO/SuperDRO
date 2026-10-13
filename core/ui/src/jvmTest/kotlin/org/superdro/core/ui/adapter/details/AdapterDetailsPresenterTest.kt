package org.superdro.core.ui.adapter.details

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.AdapterInfo
import org.superdro.core.connection.AdapterRestartState
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.IdentityCheck
import org.superdro.core.connection.AdapterIdentity
import org.superdro.core.connection.RestartResult
import org.superdro.core.connection.SessionEvent
import org.superdro.core.connection.SessionState
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.DeclaredInput
import org.superdro.core.model.IdentitySource
import org.superdro.core.model.MachineProfile
import org.superdro.core.protocol.AdapterCommand
import org.superdro.core.protocol.AdapterValues
import org.superdro.core.protocol.CommandFailure
import org.superdro.core.protocol.CommandResult
import org.superdro.core.protocol.InputChannel
import org.superdro.core.protocol.InputMode
import org.superdro.core.protocol.ReferenceState
import org.superdro.core.resources.Res
import org.superdro.core.resources.adapter_cleared
import org.superdro.core.resources.adapter_commands_off
import org.superdro.core.resources.adapter_failure_clear
import org.superdro.core.resources.adapter_failure_mode
import org.superdro.core.resources.adapter_failure_reference
import org.superdro.core.resources.adapter_two_way_off
import org.superdro.core.resources.connection_restart_failed
import org.superdro.core.resources.readout_no_adapter
import org.superdro.core.ui.adapter.AdapterFixture
import org.superdro.core.ui.adapter.ScriptedSession
import org.superdro.core.ui.sound.UiSound
import org.superdro.core.ui.text.UiText

/** The presenter of the Adapter Details screen. */
class AdapterDetailsPresenterTest {

    private val device = DeviceId("mill")

    /** The values of an adapter `tda-example` with the mode answers of the six channels. */
    private fun values(model: String = "tda-example", modes: List<String> = DEFAULT_MODES): AdapterValues {
        var values = AdapterValues()
            .withInfo(0x04, model, 0L)
            .withInfo(0x05, "246f28a1b2c3", 0L)
            .withInfo(0x03, "TouchDRO Adapter", 0L)
            .withInfo(0x06, "1.2.3", 0L)
            .withInfo(0x07, "456", 0L)
            .withInfo(0x08, "67df4f00", 0L)
        modes.forEach { text -> values = values.withInfo(0x01, text, 0L) }
        return values
    }

    private class Setup(val fixture: AdapterFixture, val session: ScriptedSession, val presenter: AdapterDetailsPresenter) {
        val state get() = presenter.uiState.value
        fun row(channel: InputChannel): ChannelRowUi = state.rows.first { row -> row.channel == channel }
        fun close() {
            presenter.close()
            fixture.close()
        }
    }

    private suspend fun TestScope.setup(
        commands: Boolean = true,
        adapter: AdapterProfile = AdapterProfile(alias = "Shop adapter", model = "tda-example"),
        info: AdapterInfo = AdapterInfo(values = values()),
        configure: (MachineProfile) -> MachineProfile = { it },
    ): Setup {
        val fixture = AdapterFixture(this)
        fixture.machineWithAdapter(device, adapter) { profile -> configure(profile.copy(adapterConfigurationCommands = commands)) }
        val session = fixture.connect(ScriptedSession(device))
        session.adapterInfo.value = info
        val presenter = AdapterDetailsPresenter(device, fixture.deps)
        runCurrent()
        return Setup(fixture, session, presenter)
    }

    /** Runs [body] with a setup and closes the presenter also when an assertion fails. */
    private fun detailsTest(
        commands: Boolean = true,
        adapter: AdapterProfile = AdapterProfile(alias = "Shop adapter", model = "tda-example"),
        info: AdapterInfo = AdapterInfo(values = values()),
        body: suspend TestScope.(Setup) -> Unit,
    ) = runTest {
        val setup = setup(commands, adapter, info)
        try {
            body(setup)
        } finally {
            setup.close()
        }
    }

    @Test
    fun theScreenOpensTheDetailsQueriesAndShowsTheInformation() = runTest {
        val setup = setup()
        try {
        assertEquals(1, setup.session.detailsOpened, "At open the session queues the details queries in the correct order.")
        val state = setup.state
        assertEquals("TouchDRO Adapter", state.name)
        assertEquals("tda-example", state.model)
        assertEquals("24:6f:28:a1:b2:c5", state.bluetoothId)
        assertEquals("1.2.3", state.firmwareVersion)
        assertEquals("456", state.firmwareBuild)
        assertEquals("2025-03-23 00:00", state.firmwareDate)
        assertEquals("Shop adapter", state.adapterAlias)
        assertNull(state.connection)
        } finally {
            setup.close()
        }
        assertEquals(1, setup.session.detailsClosed, "At close the screen ends the details queries.")
    }

    @Test
    fun aValueThatIsNotValidShowsThreeQuestionMarks() = detailsTest(info = AdapterInfo(values = AdapterValues().withInfo(0x05, "12345", 0L))) { setup ->
        val state = setup.state
        assertEquals("???", state.name)
        assertEquals("tda-example", state.model, "The model of the adapter profile shows when the adapter gives none.")
        assertEquals("???", state.bluetoothId)
        assertEquals("???", state.firmwareVersion)
        assertEquals("???", state.firmwareBuild)
        assertEquals("???", state.firmwareDate)
    }

    @Test
    fun theModeListHasOnlyTheSupportedModesAndSaveShowsOnlyForAChange() = detailsTest { setup ->
        val x = setup.row(InputChannel.X)
        assertEquals(listOf(InputMode.Disabled, InputMode.QuadratureAB, InputMode.Tachometer, InputMode.DigitalProbe), x.modes)
        assertEquals(InputMode.QuadratureAB, x.current)
        assertFalse(x.showSave)
        setup.presenter.onSelectMode(InputChannel.X, InputMode.Tachometer)
        runCurrent()
        assertTrue(setup.row(InputChannel.X).showSave, "Save and Cancel show when the selection is different.")
        assertFalse(setup.row(InputChannel.X).showReference, "The mode changed since the screen read it.")
        setup.presenter.onCancel(InputChannel.X)
        runCurrent()
        assertFalse(setup.row(InputChannel.X).showSave)
        assertEquals(InputMode.QuadratureAB, setup.row(InputChannel.X).selection)
        assertTrue(setup.session.sent.isEmpty(), "Cancel sends nothing.")
    }

    @Test
    fun saveSendsTheModeWriteAndTheRestartShows() = detailsTest { setup ->
        setup.presenter.onSelectMode(InputChannel.X, InputMode.Tachometer)
        runCurrent()
        setup.presenter.onSave(InputChannel.X)
        runCurrent()
        assertEquals(listOf<AdapterCommand>(AdapterCommand.SetMode(InputChannel.X, InputMode.Tachometer)), setup.session.sent)
        assertEquals("#set_mode:x,4;", setup.session.sent.single().text)
        // The session marks "restart needed" after `$060;` and the read-back.
        setup.session.adapterInfo.value = setup.session.adapterInfo.value.copy(
            values = setup.session.adapterInfo.value.values.withInfo(0x01, "x000f0004", 1L),
            restartNeeded = setOf(InputChannel.X),
        )
        runCurrent()
        val state = setup.state
        assertTrue(state.restartNeeded)
        assertTrue(state.showRestart)
        assertEquals(InputMode.Tachometer, setup.row(InputChannel.X).current)
        assertFalse(setup.row(InputChannel.X).showSave)
        assertFalse(setup.row(InputChannel.X).showClear, "The mode changed: Clear does not show until the restart.")
    }

    @Test
    fun anErrorOfTheModeWriteShowsTheCommandFailedText() = detailsTest { setup ->
        setup.session.result = { CommandResult.Failed(CommandFailure.of(6, 3)) }
        setup.presenter.onSelectMode(InputChannel.Y, InputMode.Disabled)
        runCurrent()
        setup.presenter.onSave(InputChannel.Y)
        runCurrent()
        assertEquals(UiText.Plain("Command failed: Invalid argument (6:3)"), setup.row(InputChannel.Y).message)
        setup.session.result = { CommandResult.NotConfirmed }
        setup.presenter.onSave(InputChannel.Y)
        runCurrent()
        assertEquals(UiText.of(Res.string.adapter_failure_mode), setup.row(InputChannel.Y).message)
    }

    @Test
    fun withTheSwitchOffNoCommandButtonShows() = detailsTest(commands = false) { setup ->
        val x = setup.row(InputChannel.X)
        assertFalse(x.showReference)
        assertFalse(x.showClear)
        setup.presenter.onSelectMode(InputChannel.X, InputMode.Tachometer)
        runCurrent()
        assertFalse(setup.row(InputChannel.X).showSave)
        setup.presenter.onSave(InputChannel.X)
        runCurrent()
        assertTrue(setup.session.sent.isEmpty())
        assertEquals(UiText.of(Res.string.adapter_commands_off), setup.state.commandsNote)
    }

    @Test
    fun withTwoWayCommunicationOffNoCommandButtonShows() = detailsTest(info = AdapterInfo(values = values(), twoWayCommunication = false)) { setup ->
        assertFalse(setup.state.commandsAllowed)
        assertEquals(UiText.of(Res.string.adapter_two_way_off), setup.state.commandsNote)
    }

    @Test
    fun referenceAndClearFollowTheRulesOfTheModelTheChannelAndTheMode() = detailsTest { setup ->
        assertTrue(setup.row(InputChannel.X).showReference, "tda- model, channel x, Quadrature A/B, mode not changed.")
        assertTrue(setup.row(InputChannel.X).showClear)
        assertFalse(setup.row(InputChannel.U).showReference, "Aux 1 has no reference search.")
        assertTrue(setup.row(InputChannel.U).showClear, "Aux 1 in Quadrature A/B can clear its counter.")
        assertFalse(setup.row(InputChannel.Z).showReference, "Input Z is disabled.")
        assertFalse(setup.row(InputChannel.Z).showClear)
        setup.session.adapterInfo.value = AdapterInfo(values = values(model = "esp-diy"))
        runCurrent()
        assertFalse(setup.row(InputChannel.X).showReference, "The model does not start with tda-.")
        assertTrue(setup.row(InputChannel.X).showClear)
        setup.session.adapterInfo.value = AdapterInfo(values = values(model = "TDA-Pro"))
        runCurrent()
        assertTrue(setup.row(InputChannel.X).showReference, "The case of tda- does not matter.")
    }

    @Test
    fun referenceAndClearSendTheirCommands() = detailsTest { setup ->
        setup.presenter.onReference(InputChannel.X)
        runCurrent()
        setup.presenter.onClear(InputChannel.Y)
        runCurrent()
        assertEquals(listOf("#ref_axis:x,15;", "#clear_counter:y;"), setup.session.sent.map { command -> command.text })
        assertEquals(UiText.of(Res.string.adapter_cleared), setup.row(InputChannel.Y).notice)
    }

    @Test
    fun theButtonsOfAChannelAreDisabledWhileItsCommandRunsAndEnabledAgainAtTheLimit() = detailsTest { setup ->
        setup.session.sendDelayMs = 10_000L
        setup.session.result = { CommandResult.NotConfirmed }
        setup.presenter.onClear(InputChannel.X)
        runCurrent()
        assertTrue(setup.row(InputChannel.X).busy)
        assertFalse(setup.row(InputChannel.Y).busy, "Only the channel of the command is busy.")
        setup.presenter.onClear(InputChannel.X)
        runCurrent()
        assertEquals(1, setup.session.sent.size, "A busy channel sends no second command.")
        advanceTimeBy(10_001L)
        runCurrent()
        assertFalse(setup.row(InputChannel.X).busy, "The result limit enables the buttons again.")
        assertEquals(UiText.of(Res.string.adapter_failure_clear), setup.row(InputChannel.X).message)
        setup.session.result = { CommandResult.NotConfirmed }
        setup.presenter.onReference(InputChannel.X)
        advanceTimeBy(10_001L)
        runCurrent()
        assertEquals(UiText.of(Res.string.adapter_failure_reference), setup.row(InputChannel.X).message)
    }

    @Test
    fun theBusyChannelsOfTheSessionDisableTheButtons() = detailsTest { setup ->
        setup.session.adapterInfo.value = setup.session.adapterInfo.value.copy(busyChannels = setOf(InputChannel.W))
        runCurrent()
        assertTrue(setup.row(InputChannel.W).busy)
        setup.session.adapterInfo.value = setup.session.adapterInfo.value.copy(busyChannels = emptySet())
        runCurrent()
        assertFalse(setup.row(InputChannel.W).busy)
    }

    @Test
    fun theReferenceMarkStatesPlayTheShortSoundAndTheErrorSound() = detailsTest { setup ->
        setup.session.events.emit(SessionEvent.Reference(InputChannel.X, ReferenceState.InProgress))
        runCurrent()
        assertEquals(ReferenceState.InProgress, setup.row(InputChannel.X).reference)
        assertTrue(setup.fixture.uiSounds.played.isEmpty())
        setup.session.events.emit(SessionEvent.Reference(InputChannel.X, ReferenceState.Finished))
        runCurrent()
        assertEquals(ReferenceState.Finished, setup.row(InputChannel.X).reference)
        assertEquals(listOf(UiSound.Short), setup.fixture.uiSounds.played)
        setup.session.events.emit(SessionEvent.Reference(InputChannel.Y, ReferenceState.TimedOut))
        runCurrent()
        assertEquals(listOf(UiSound.Short, UiSound.Error), setup.fixture.uiSounds.played)
        setup.session.events.emit(SessionEvent.Reference(InputChannel.Z, ReferenceState.Cancelled))
        runCurrent()
        assertEquals(2, setup.fixture.uiSounds.played.size, "A cancel plays no sound.")
        assertEquals(ReferenceState.Cancelled, setup.row(InputChannel.Z).reference)
    }

    @Test
    fun aCommandFailedEventShowsInTheRowOfItsChannel() = detailsTest { setup ->
        setup.session.events.emit(SessionEvent.CommandFailed(AdapterCommand.RefAxis(InputChannel.W), CommandFailure.of(0x77, 164)))
        runCurrent()
        assertEquals(UiText.Plain("Command failed: Status code 164 (119:164)"), setup.row(InputChannel.W).message)
    }

    @Test
    fun restartNowSendsTheRebootAndShowsTheFailures() = detailsTest(info = AdapterInfo(values = values(), restartNeeded = setOf(InputChannel.X))) { setup ->
        assertTrue(setup.state.showRestart)
        setup.presenter.onRestart()
        runCurrent()
        assertNull(setup.state.restartDialog)
        setup.session.adapterInfo.value = setup.session.adapterInfo.value.copy(restart = AdapterRestartState.Restarting)
        runCurrent()
        assertTrue(setup.state.restartBusy)
        setup.session.adapterInfo.value = setup.session.adapterInfo.value.copy(restart = AdapterRestartState.Failed)
        runCurrent()
        assertEquals(AdapterRestartState.Failed, setup.state.restartState, "The screen shows: Could not restart the adapter.")
        setup.session.restartResult = RestartResult.NoSession
        setup.presenter.onRestart()
        runCurrent()
        assertEquals(UiText.of(Res.string.connection_restart_failed), setup.state.restartDialog, "No session: the Restart failed dialog.")
        setup.presenter.onRestartDialogDismissed()
        runCurrent()
        assertNull(setup.state.restartDialog)
        setup.session.restartResult = RestartResult.NotAccepted(CommandResult.Failed(CommandFailure.TIMEOUT))
        setup.presenter.onRestart()
        runCurrent()
        assertEquals(UiText.Plain("Command failed: Timeout (-1:256)"), setup.state.restartDialog)
    }

    @Test
    fun withNoSessionRestartNowSendsNothingAndShowsRestartFailed() = runTest {
        val fixture = AdapterFixture(this)
        fixture.machineWithAdapter(device) { profile -> profile.copy(adapterConfigurationCommands = true) }
        val presenter = AdapterDetailsPresenter(device, fixture.deps)
        try {
            runCurrent()
            assertEquals(UiText.of(Res.string.readout_no_adapter), presenter.uiState.value.connection)
            presenter.onRestart()
            runCurrent()
            assertEquals(UiText.of(Res.string.connection_restart_failed), presenter.uiState.value.restartDialog)
        } finally {
            presenter.close()
            fixture.close()
        }
    }

    @Test
    fun theRefreshRateComesFromTheTimesOfTheLastTwoValues() = detailsTest { setup ->
        setup.session.stage.onAxisCount('x', 100L, 1_000L)
        setup.session.stage.onAxisCount('x', 101L, 1_020L)
        advanceTimeBy(AdapterDetailsPresenter.RATE_UPDATE_MS + 1L)
        runCurrent()
        assertEquals("50.00 Hz", setup.row(InputChannel.X).refreshRate)
        assertEquals("0 Hz", setup.row(InputChannel.Y).refreshRate, "Input Y has no values.")
        assertNull(setup.row(InputChannel.Z).refreshRate, "A disabled channel shows the probe form and N/A.")
    }

    @Test
    fun aLegacyAdapterShowsItsDeclaredInputs() = runTest {
        val legacy = AdapterProfile(alias = "Old adapter", legacy = true, declaredInputs = setOf(DeclaredInput.X, DeclaredInput.Y, DeclaredInput.Probe, DeclaredInput.Tach))
        val setup = setup(adapter = legacy, info = AdapterInfo())
        try {
        val rows = setup.state.rows
        assertEquals(listOf(InputChannel.X, InputChannel.Y, InputChannel.U, InputChannel.V), rows.map { row -> row.channel })
        assertTrue(rows.all { row -> row.declared })
        assertEquals(GearTarget.Probe, setup.row(InputChannel.U).gear)
        assertEquals(GearTarget.Tach, setup.row(InputChannel.V).gear)
        assertTrue(setup.row(InputChannel.X).showClear, "The X to W rows show Clear without a mode check.")
        assertFalse(setup.row(InputChannel.X).showReference, "No tda- model: no Reference, because no command can go out.")
        assertNull(setup.row(InputChannel.U).refreshRate)
        val stored = setup.fixture.store.adapters.adapterProfiles().first().single()
        setup.fixture.store.adapters.saveAdapterProfile(stored.copy(model = "tda-old"))
        runCurrent()
        assertTrue(setup.row(InputChannel.X).showReference)
        assertFalse(setup.row(InputChannel.U).showReference)
        } finally {
            setup.close()
        }
    }

    @Test
    fun anIdentityThatDiffersShowsTheWarning() = detailsTest(
        info = AdapterInfo(
            values = values(),
            identityCheck = IdentityCheck.Differs("aa:bb:cc:dd:ee:ff", AdapterIdentity("24:6f:28:a1:b2:c5", IdentitySource.BtMac)),
        ),
    ) { setup ->
        assertTrue(setup.state.identityDiffers)
    }

    @Test
    fun theDirtyStatusDoesNotShow() = detailsTest { setup ->
        val before = setup.state
        setup.session.adapterInfo.value = setup.session.adapterInfo.value.copy(values = setup.session.adapterInfo.value.values.withDirty(0x05, 1L))
        runCurrent()
        assertEquals(before, setup.state, "The dirty flags of `$3205;` change nothing on the screen.")
    }

    @Test
    fun aSessionThatIsNotConnectedHidesTheCommands() = detailsTest { setup ->
        setup.session.state.value = SessionState.NoData
        runCurrent()
        assertFalse(setup.state.commandsAllowed)
        assertFalse(setup.row(InputChannel.X).showClear)
    }

    private companion object {
        val DEFAULT_MODES = listOf("x000f0002", "y00030002", "z00030001", "w00070002", "u08030002", "v00090008")
    }
}
