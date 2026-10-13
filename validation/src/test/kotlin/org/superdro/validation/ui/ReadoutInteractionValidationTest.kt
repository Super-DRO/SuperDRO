package org.superdro.validation.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.SessionState
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.AxisButtonConfig
import org.superdro.core.model.AxisButtonMode
import org.superdro.core.model.AxisId
import org.superdro.core.model.Calibration
import org.superdro.core.model.CalibrationStatus
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.SidePanelMode
import org.superdro.core.model.Tool
import org.superdro.core.store.valueOrNull
import org.superdro.core.ui.navigation.CalibrationWizardRoute
import org.superdro.core.ui.navigation.KeypadRoute
import org.superdro.core.ui.navigation.MachineSettingsRoute
import org.superdro.core.ui.navigation.TabNavigator
import org.superdro.core.ui.navigation.TabRoute
import org.superdro.core.ui.readout.AddPointKind
import org.superdro.core.ui.readout.PresenterReadoutEvents
import org.superdro.core.ui.readout.ReadoutPresenter
import org.superdro.core.ui.readout.ValueState
import org.superdro.core.ui.readout.keypad.KeypadKey
import org.superdro.core.ui.readout.keypad.KeypadPresenter
import org.superdro.core.ui.text.UiText

/**
 * The undo of each offset change for 10 s, the keypad, the worked examples, the "+"
 * button, a hint that never blocks the action, and the setup reminder. The presenters
 * run on the real repositories in virtual time with a fake session.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadoutInteractionValidationTest {

    private fun TestScope.rig(): PresenterRig = PresenterRig(this, StandardTestDispatcher(testScheduler))

    /** The adapter profile with each encoder calibrated by the user: no setup reminder. */
    private fun calibratedAdapter(): AdapterProfile = AdapterProfile(
        alias = "Test adapter",
        encoders = AdapterProfile().encoders.mapValues { (_, encoder) -> encoder.copy(calibration = Calibration(status = CalibrationStatus.UserSet)) },
    )

    /** Stores the profile "mill" and gives its connected session. */
    private suspend fun PresenterRig.mill(
        metric: Boolean,
        adapter: AdapterProfile? = calibratedAdapter(),
        configure: (MachineProfile) -> MachineProfile = { it },
    ): FakeSession {
        val adapterId = adapter?.let { profile -> assertNotNull(adapters.saveAdapterProfile(profile).valueOrNull()).id }
        val profile = MachineProfile.create(MachineType.VerticalMill, "Mill").copy(id = "mill", metricDefault = metric, adapterProfileId = adapterId)
        assertNotNull(machines.createProfile(configure(profile)).valueOrNull())
        return FakeSession("mill", "Mill").also { session ->
            session.state.value = SessionState.Connected(0L)
            connections.add(session)
        }
    }

    private fun ReadoutPresenter.shown(axis: AxisId = AxisId.X): String = axisValue(axis).value.text

    // ---------------------------------------------------------------------------------------
    // The worked examples of the readout

    @Test
    fun example_incremental_zero_at_40000_um_then_a_move_of_1000_um_shows_0_0394() = runTest {
        rig().use { rig ->
            val session = rig.mill(metric = false)
            session.setAxes(mapOf(AxisId.X to 40_000.0))
            val readout = rig.readout("mill")
            runCurrent()
            assertEquals("1.5748", readout.shown())
            readout.onActionTap(AxisId.X, AxisButtonMode.SetZero)
            runCurrent()
            assertEquals(0.0, readout.shown().toDouble())
            assertTrue(readout.axisValue(AxisId.X).value.incremental)
            assertNotNull(readout.uiState.value.undo, "the zero shows the undo")
            session.setAxes(mapOf(AxisId.X to 41_000.0))
            runCurrent()
            assertEquals("0.0394", readout.shown())
        }
    }

    @Test
    fun undo_shows_for_10_s_and_restores_the_old_offsets() = runTest {
        rig().use { rig ->
            val session = rig.mill(metric = false)
            session.setAxes(mapOf(AxisId.X to 40_000.0))
            val readout = rig.readout("mill")
            runCurrent()
            readout.onActionTap(AxisId.X, AxisButtonMode.SetZero)
            runCurrent()
            val undo = assertNotNull(readout.uiState.value.undo)
            advanceTimeBy(9_999L)
            runCurrent()
            assertEquals(undo.id, readout.uiState.value.undo?.id, "the undo stays for 10 s")
            readout.onUndo(undo.id)
            runCurrent()
            assertEquals("1.5748", readout.shown(), "the undo restores the old offsets")
            assertFalse(readout.axisValue(AxisId.X).value.incremental)
            assertNull(readout.uiState.value.undo)
        }
    }

    @Test
    fun undo_goes_away_after_10_s() = runTest {
        rig().use { rig ->
            val session = rig.mill(metric = true)
            session.setAxes(mapOf(AxisId.X to 25_000.0))
            val readout = rig.readout("mill")
            runCurrent()
            readout.onActionLongPress(AxisId.X, AxisButtonMode.SetZero)
            runCurrent()
            assertNotNull(readout.uiState.value.undo, "the absolute zero shows the undo")
            advanceTimeBy(10_001L)
            runCurrent()
            assertNull(readout.uiState.value.undo, "no undo after 10 s")
        }
    }

    @Test
    fun each_offset_change_has_undo() = runTest {
        rig().use { rig ->
            val session = rig.mill(metric = true) { profile ->
                profile.copy(x = profile.x.copy(buttons = listOf(AxisButtonConfig(AxisButtonMode.SetCenter), AxisButtonConfig(AxisButtonMode.ToggleIncremental), AxisButtonConfig(AxisButtonMode.SetZero))))
            }
            session.setAxes(mapOf(AxisId.X to 10_000.0, AxisId.Y to 20_000.0, AxisId.Z to 30_000.0))
            val readout = rig.readout("mill")
            runCurrent()
            val actions: List<Pair<String, () -> Unit>> = listOf(
                "zero tap" to { readout.onActionTap(AxisId.X, AxisButtonMode.SetZero) },
                "absolute zero" to { readout.onActionLongPress(AxisId.X, AxisButtonMode.SetZero) },
                "half" to { readout.onActionTap(AxisId.X, AxisButtonMode.SetCenter) },
                "global zero set" to { readout.onZeroSetTap() },
                "global absolute zero" to { readout.onZeroSetLongPress() },
                "clear offset" to { readout.onActionLongPress(AxisId.X, AxisButtonMode.ToggleIncremental) },
                "keypad set" to { readout.onKeypadSet(AxisId.X, "5") },
            )
            actions.forEach { (name, action) ->
                val before = readout.uiState.value.undo?.id
                // A move, so that each action changes an offset.
                session.setAxes(mapOf(AxisId.X to 10_000.0 + (before ?: 0L) * 1_000.0, AxisId.Y to 20_000.0, AxisId.Z to 30_000.0))
                runCurrent()
                action()
                runCurrent()
                val after = readout.uiState.value.undo
                assertNotNull(after, "$name shows the undo")
                assertTrue(after.id != before, "$name gives a new undo")
            }
        }
    }

    @Test
    fun example_hold_25_000_mm_then_move_5_mm_then_set_at_the_new_position() = runTest {
        rig().use { rig ->
            val session = rig.mill(metric = true) { profile ->
                profile.copy(x = profile.x.copy(buttons = listOf(AxisButtonConfig(AxisButtonMode.Hold), AxisButtonConfig(AxisButtonMode.ToggleIncremental), AxisButtonConfig(AxisButtonMode.ToggleUnits))))
            }
            session.setAxes(mapOf(AxisId.X to 25_000.0))
            val readout = rig.readout("mill")
            runCurrent()
            assertEquals("25.000", readout.shown())
            readout.onActionTap(AxisId.X, AxisButtonMode.Hold)
            runCurrent()
            session.setAxes(mapOf(AxisId.X to 30_000.0))
            runCurrent()
            assertEquals("25.000", readout.shown(), "the display keeps the held value")
            assertEquals(ValueState.Held, readout.axisValue(AxisId.X).value.state)
            readout.onActionTap(AxisId.X, AxisButtonMode.Hold)
            runCurrent()
            assertEquals("25.000", readout.shown(), "the axis shows 25.000 mm at the new position")
            assertTrue(readout.axisValue(AxisId.X).value.state != ValueState.Held)
            session.setAxes(mapOf(AxisId.X to 31_000.0))
            runCurrent()
            assertEquals("26.000", readout.shown())
        }
    }

    @Test
    fun example_keypad_plus_plus_0_5_on_1_2500_in_gives_1_7500_in_absolute() = runTest {
        rig().use { rig ->
            val session = rig.mill(metric = false)
            session.setAxes(mapOf(AxisId.X to 31_750.0))
            val readout = rig.readout("mill")
            runCurrent()
            assertEquals("1.2500", readout.shown())
            val keypad = KeypadPresenter(AxisId.X, readout)
            listOf(KeypadKey.RelativeAdd, KeypadKey.Digit0, KeypadKey.Dot, KeypadKey.Digit5).forEach(keypad::onKey)
            assertEquals("++0.5", keypad.uiState.value.text)
            // The keypad shows the expression and the result before "Set".
            assertEquals("1.7500", keypad.uiState.value.result)
            assertEquals("1.2500", readout.shown(), "no change before Set")
            assertTrue(keypad.onSet())
            runCurrent()
            assertEquals("1.7500", readout.shown())
            assertFalse(readout.axisValue(AxisId.X).value.incremental, "h(V) in absolute mode")
            // h(44450): A = 44450 − (R + T) = 44450 − 31750 = 12700 µm.
            val workspace = assertNotNull(rig.machines.loadWorkspace(assertNotNull(rig.machines.loadProfile("mill")).activeWorkspaceId))
            assertEquals(12_700.0, workspace.axisStates.getValue(AxisId.X).absoluteOffset, 1e-6)
            assertNotNull(readout.uiState.value.undo, "the keypad set has undo")
        }
    }

    @Test
    fun keypad_minus_minus_and_the_clear_key() = runTest {
        rig().use { rig ->
            val session = rig.mill(metric = true)
            session.setAxes(mapOf(AxisId.X to 10_000.0))
            val readout = rig.readout("mill")
            runCurrent()
            val keypad = KeypadPresenter(AxisId.X, readout)
            listOf(KeypadKey.RelativeSubtract, KeypadKey.Digit2, KeypadKey.Dot, KeypadKey.Digit5).forEach(keypad::onKey)
            assertEquals("--2.5", keypad.uiState.value.text)
            assertEquals("7.500", keypad.uiState.value.result, "--x gives current − x")
            keypad.onKey(KeypadKey.Clear)
            assertEquals("", keypad.uiState.value.text)
            // The operator keys + − × ÷ and the parentheses go to the expression evaluator.
            listOf(KeypadKey.OpenParenthesis, KeypadKey.Digit2, KeypadKey.Plus, KeypadKey.Digit4, KeypadKey.CloseParenthesis, KeypadKey.Times, KeypadKey.Digit3, KeypadKey.Divide, KeypadKey.Digit2, KeypadKey.Minus, KeypadKey.Digit1)
                .forEach(keypad::onKey)
            assertEquals("8.000", keypad.uiState.value.result, "(2+4)*3/2-1")
        }
    }

    @Test
    fun a_tap_on_a_value_opens_the_keypad_and_does_not_change_a_value() = runTest {
        rig().use { rig ->
            val session = rig.mill(metric = true)
            session.setAxes(mapOf(AxisId.X to 12_345.0))
            val readout = rig.readout("mill")
            runCurrent()
            val before = readout.shown()
            assertEquals(KeypadRoute(AxisId.X), readout.onValueTap(AxisId.X))
            runCurrent()
            assertEquals(before, readout.shown())
            assertNull(readout.uiState.value.undo, "no offset change")
        }
    }

    @Test
    fun an_operation_needs_a_connected_session() {
        val texts = ArrayList<UiText?>()
        runTest {
            rig().use { rig ->
                val session = rig.mill(metric = true)
                session.setAxes(mapOf(AxisId.X to 12_345.0))
                val readout = rig.readout("mill")
                runCurrent()
                session.state.value = SessionState.AwaitingData
                runCurrent()
                readout.onActionTap(AxisId.X, AxisButtonMode.SetZero)
                runCurrent()
                texts += readout.uiState.value.message?.text
                assertNull(readout.uiState.value.undo)
                session.state.value = SessionState.Disconnected
                runCurrent()
                readout.onZeroSetTap()
                runCurrent()
                texts += readout.uiState.value.message?.text
            }
        }
        assertEquals(listOf("Waiting for the adapter", "No adapter connected"), resolveTexts(texts))
    }

    // ---------------------------------------------------------------------------------------
    // The hints

    @Test
    fun zero_hint_shows_after_the_action_and_never_blocks_it() {
        val texts = ArrayList<UiText?>()
        runTest {
            rig().use { rig ->
                val session = rig.mill(metric = true)
                session.setAxes(mapOf(AxisId.X to 12_000.0))
                val readout = rig.readout("mill")
                runCurrent()
                readout.onActionTap(AxisId.X, AxisButtonMode.SetZero)
                runCurrent()
                // The action ran at once.
                assertEquals(0.0, readout.shown().toDouble())
                texts += readout.uiState.value.hint
            }
        }
        assertEquals(listOf("Press and hold to set the absolute zero."), resolveTexts(texts))
    }

    @Test
    fun each_hint_shows_one_time() = runTest {
        rig().use { rig ->
            val session = rig.mill(metric = true)
            session.setAxes(mapOf(AxisId.X to 12_000.0))
            val readout = rig.readout("mill")
            runCurrent()
            readout.onActionTap(AxisId.X, AxisButtonMode.SetZero)
            runCurrent()
            assertNotNull(readout.uiState.value.hint)
            advanceTimeBy(60_000L)
            runCurrent()
            assertNull(readout.uiState.value.hint)
            session.setAxes(mapOf(AxisId.X to 13_000.0))
            runCurrent()
            readout.onActionTap(AxisId.X, AxisButtonMode.SetZero)
            runCurrent()
            assertEquals(0.0, readout.shown().toDouble(), "the second zero runs")
            assertNull(readout.uiState.value.hint, "the hint shows only one time")
        }
    }

    @Test
    fun the_setting_Show_hints_turns_them_off() = runTest {
        rig().use { rig ->
            rig.appSettings.awaitLoaded()
            rig.appSettings.update { settings -> settings.copy(showHints = false) }
            val session = rig.mill(metric = true)
            session.setAxes(mapOf(AxisId.X to 12_000.0))
            val readout = rig.readout("mill")
            runCurrent()
            readout.onActionTap(AxisId.X, AxisButtonMode.SetZero)
            readout.onModeTap()
            runCurrent()
            assertNull(readout.uiState.value.hint)
        }
    }

    @Test
    fun hint_texts_of_the_clear_the_hold_and_the_plus_button() {
        val texts = ArrayList<UiText?>()
        runTest {
            rig().use { rig ->
                val session = rig.mill(metric = true) { profile ->
                    profile.copy(x = profile.x.copy(buttons = listOf(AxisButtonConfig(AxisButtonMode.Hold), AxisButtonConfig(AxisButtonMode.ToggleIncremental), AxisButtonConfig(AxisButtonMode.SetZero))))
                }
                session.setAxes(mapOf(AxisId.X to 12_000.0))
                val readout = rig.readout("mill")
                runCurrent()
                readout.onModeTap()
                runCurrent()
                texts += readout.uiState.value.hint
                readout.onActionTap(AxisId.X, AxisButtonMode.Hold)
                runCurrent()
                texts += readout.uiState.value.hint
                assertEquals(ValueState.Held, readout.axisValue(AxisId.X).value.state, "the hold ran at once")
                readout.onAddTap()
                advanceTimeBy(301L)
                runCurrent()
                texts += readout.uiState.value.hint
            }
        }
        assertEquals(
            listOf("Press and hold to clear the offsets.", "Press and hold to cancel the hold.", "Press and hold the '+' button for more shapes."),
            resolveTexts(texts),
        )
    }

    // ---------------------------------------------------------------------------------------
    // The setup reminder

    @Test
    fun reminder_shows_when_an_enabled_axis_input_has_no_calibration() = runTest {
        rig().use { rig ->
            rig.mill(metric = true, adapter = null)
            val readout = rig.readout("mill")
            runCurrent()
            assertEquals(AxisId.X, readout.uiState.value.setupReminder?.axis, "no adapter profile: no encoder profile for the inputs")
        }
    }

    @Test
    fun no_reminder_when_each_enabled_input_is_calibrated() = runTest {
        rig().use { rig ->
            rig.mill(metric = true)
            val readout = rig.readout("mill")
            runCurrent()
            assertNull(readout.uiState.value.setupReminder)
        }
    }

    @Test
    fun reminder_checks_only_the_enabled_axes() = runTest {
        rig().use { rig ->
            val notCalibratedZ = calibratedAdapter().let { adapter ->
                adapter.copy(encoders = adapter.encoders + ('z' to adapter.encoders.getValue('z').copy(calibration = Calibration(status = CalibrationStatus.NotCalibrated))))
            }
            rig.mill(metric = true, adapter = notCalibratedZ) { profile -> profile.copy(z = profile.z.copy(enabled = false)) }
            val readout = rig.readout("mill")
            runCurrent()
            assertNull(readout.uiState.value.setupReminder, "Z is disabled")
            rig.machines.updateProfile("mill") { profile -> profile.copy(z = profile.z.copy(enabled = true)) }
            runCurrent()
            assertEquals(AxisId.Z, readout.uiState.value.setupReminder?.axis)
        }
    }

    @Test
    fun the_setting_Show_the_setup_reminder_turns_it_off() = runTest {
        rig().use { rig ->
            rig.appSettings.awaitLoaded()
            rig.appSettings.update { settings -> settings.copy(showSetupReminder = false) }
            rig.mill(metric = true, adapter = null)
            val readout = rig.readout("mill")
            runCurrent()
            assertNull(readout.uiState.value.setupReminder)
        }
    }

    private class Navigator : TabNavigator {
        val pushed = ArrayList<TabRoute>()
        override val deviceId = org.superdro.core.connection.DeviceId("mill")
        override fun push(route: TabRoute) { pushed += route }
        override fun pop() = Unit
        override fun pop(route: TabRoute) = Unit
        override fun replaceTop(route: TabRoute) = Unit
        override fun popToRoot() = Unit
    }

    @Test
    fun Settings_opens_the_machine_settings_and_Calibrate_the_calibration() = runTest {
        rig().use { rig ->
            rig.mill(metric = true, adapter = null)
            val readout = rig.readout("mill")
            runCurrent()
            val navigator = Navigator()
            val events = PresenterReadoutEvents(readout, navigator)
            events.onSetupSettings()
            events.onSetupCalibrate(assertNotNull(readout.uiState.value.setupReminder).axis)
            assertEquals(listOf(MachineSettingsRoute, CalibrationWizardRoute(AxisId.X)), navigator.pushed)
        }
    }

    // ---------------------------------------------------------------------------------------
    // The "+" button and the side panel

    @Test
    fun plus_tap_double_tap_and_long_press() = runTest {
        rig().use { rig ->
            rig.mill(metric = true)
            val readout = rig.readout("mill")
            runCurrent()
            readout.onAddTap()
            advanceTimeBy(299L)
            runCurrent()
            assertNull(readout.uiState.value.addPointRequest, "no single tap before 300 ms")
            advanceTimeBy(2L)
            runCurrent()
            val single = assertNotNull(readout.uiState.value.addPointRequest)
            assertEquals(AddPointKind.Single, single.kind)
            readout.onAddRequestHandled(single.id)
            readout.onAddTap()
            advanceTimeBy(299L)
            readout.onAddTap()
            runCurrent()
            val double = assertNotNull(readout.uiState.value.addPointRequest)
            assertEquals(AddPointKind.Double, double.kind, "a second tap in 300 ms")
            readout.onAddRequestHandled(double.id)
            advanceTimeBy(1_000L)
            runCurrent()
            assertNull(readout.uiState.value.addPointRequest, "the double tap gives no single tap")
            readout.onAddLongPress()
            runCurrent()
            assertEquals(AddPointKind.Long, readout.uiState.value.addPointRequest?.kind)
        }
    }

    @Test
    fun tool_panel_needs_a_tool_and_hides_the_plus_button() = runTest {
        rig().use { rig ->
            rig.mill(metric = true) { profile -> profile.copy(sidePanel = SidePanelMode.Tools) }
            val readout = rig.readout("mill")
            runCurrent()
            // The tool tab is enabled only when the library is not empty: no tool gives OFF.
            assertEquals(SidePanelMode.Off, readout.uiState.value.sidePanel.mode)
            assertFalse(readout.uiState.value.sidePanel.toolsEnabled)
            assertTrue(readout.uiState.value.sidePanel.addVisible)
            assertNotNull(rig.tools.addTool("mill", Tool(name = "T1")).valueOrNull())
            runCurrent()
            assertEquals(SidePanelMode.Tools, readout.uiState.value.sidePanel.mode)
            assertTrue(readout.uiState.value.sidePanel.toolsEnabled)
            assertFalse(readout.uiState.value.sidePanel.addVisible, "no \"+\" in the tool mode")
            readout.onSidePanelTab(SidePanelMode.SubDatums)
            runCurrent()
            assertEquals(SidePanelMode.SubDatums, readout.uiState.value.sidePanel.mode)
            assertTrue(readout.uiState.value.sidePanel.addVisible)
        }
    }

    // ---------------------------------------------------------------------------------------
    // The offline tab, the compensation buttons, the screen on

    @Test
    fun offline_tab_keeps_the_last_values_with_the_label_Reconnecting() = runTest {
        rig().use { rig ->
            val session = rig.mill(metric = true)
            session.setAxes(mapOf(AxisId.X to 12_345.0))
            val readout = rig.readout("mill")
            runCurrent()
            assertEquals("12.345", readout.shown())
            session.state.value = SessionState.Reconnecting(1, 1_000L, org.superdro.core.connection.LinkFailure.Lost)
            session.setAxes(emptyMap())
            runCurrent()
            assertEquals("12.345", readout.shown(), "the last value stays")
            assertEquals(ValueState.Offline, readout.axisValue(AxisId.X).value.state)
            assertEquals(org.superdro.core.ui.readout.OfflineLabel.Reconnecting, readout.uiState.value.status.offline)
            session.state.value = SessionState.Disconnected
            runCurrent()
            assertEquals(org.superdro.core.ui.readout.OfflineLabel.Offline, readout.uiState.value.status.offline)
            assertEquals("12.345", readout.shown())
        }
    }

    @Test
    fun long_press_on_an_active_compensation_button_clears_it_with_undo() = runTest {
        rig().use { rig ->
            val session = rig.mill(metric = true)
            session.setAxes(mapOf(AxisId.X to 0.0, AxisId.Y to 0.0, AxisId.Z to 0.0))
            val readout = rig.readout("mill")
            runCurrent()
            val height = org.superdro.core.ui.readout.FunctionButton.HeightCompensation
            fun active() = readout.uiState.value.functions.first { it.button == height }.active
            assertFalse(active())
            readout.onFunctionLongPress(height)
            runCurrent()
            assertNull(readout.uiState.value.undo, "an inactive button does nothing on a long press")
            val workspaceId = assertNotNull(rig.machines.loadProfile("mill")).activeWorkspaceId
            rig.machines.updateWorkspace(workspaceId) { workspace ->
                val z = workspace.axisStates[AxisId.Z] ?: org.superdro.core.model.AxisState()
                workspace.copy(axisStates = workspace.axisStates + (AxisId.Z to z.copy(toolOffset = 5_000.0, toolOffsetActive = true)))
            }
            runCurrent()
            assertTrue(active(), "the button shows the active state")
            readout.onFunctionLongPress(height)
            runCurrent()
            assertFalse(active(), "the long press clears the compensation")
            val undo = assertNotNull(readout.uiState.value.undo, "with undo")
            readout.onUndo(undo.id)
            runCurrent()
            assertTrue(active(), "the undo restores the compensation")
        }
    }

    @Test
    fun keep_the_screen_on_while_the_session_is_connected() = runTest {
        rig().use { rig ->
            val session = rig.mill(metric = true)
            val readout = rig.readout("mill")
            runCurrent()
            assertTrue(readout.uiState.value.keepScreenOn)
            listOf(SessionState.NoData, SessionState.AwaitingData, SessionState.Disconnected).forEach { state ->
                session.state.value = state
                runCurrent()
                assertFalse(readout.uiState.value.keepScreenOn, "$state")
            }
        }
    }

    @Test
    fun the_app_keeps_the_set_of_shown_hints() = runTest {
        rig().use { rig ->
            val session = rig.mill(metric = true)
            session.setAxes(mapOf(AxisId.X to 12_000.0))
            val first = rig.readout("mill")
            runCurrent()
            first.onActionTap(AxisId.X, AxisButtonMode.SetZero)
            runCurrent()
            assertNotNull(first.uiState.value.hint)
            assertTrue(rig.appSettings.settings.value.shownHints.isNotEmpty(), "the shown hint is stored")
            first.close()
            // A new readout (for example after a restart) does not show the hint again.
            val second = rig.readout("mill")
            session.setAxes(mapOf(AxisId.X to 13_000.0))
            runCurrent()
            second.onActionTap(AxisId.X, AxisButtonMode.SetZero)
            runCurrent()
            assertEquals(0.0, second.shown().toDouble())
            assertNull(second.uiState.value.hint)
        }
    }

    @Test
    fun tachometer_value_tap_follows_the_mode() {
        val texts = ArrayList<UiText?>()
        runTest {
            rig().use { rig ->
                val adapterId = assertNotNull(rig.adapters.saveAdapterProfile(calibratedAdapter()).valueOrNull()).id
                val base = MachineProfile.create(MachineType.Lathe, "Lathe")
                val profile = base.copy(id = "lathe", metricDefault = true, adapterProfileId = adapterId, tachometer = base.tachometer.copy(enabled = true))
                assertNotNull(rig.machines.createProfile(profile).valueOrNull())
                val session = FakeSession("lathe", "Lathe").apply { state.value = SessionState.Connected(0L) }
                rig.connections.add(session)
                session.setAxes(mapOf(AxisId.X to 0.0, AxisId.Z to 0.0, AxisId.Tachometer to 0.0))
                val readout = rig.readout("lathe")
                runCurrent()
                // RPM sensor (the default): no route, the message "Can't set RPM".
                assertNull(readout.onValueTap(AxisId.Tachometer))
                runCurrent()
                texts += readout.uiState.value.message?.text
                rig.machines.updateProfile("lathe") { current -> current.copy(tachometer = current.tachometer.copy(mode = org.superdro.core.model.TachometerMode.Preset)) }
                runCurrent()
                assertEquals(org.superdro.core.ui.navigation.TachometerPresetRoute, readout.onValueTap(AxisId.Tachometer))
                rig.machines.updateProfile("lathe") { current -> current.copy(tachometer = current.tachometer.copy(mode = org.superdro.core.model.TachometerMode.Direct)) }
                runCurrent()
                assertEquals(KeypadRoute(AxisId.Tachometer), readout.onValueTap(AxisId.Tachometer))
            }
        }
        assertEquals(listOf("Can't set RPM"), resolveTexts(texts))
    }
}
