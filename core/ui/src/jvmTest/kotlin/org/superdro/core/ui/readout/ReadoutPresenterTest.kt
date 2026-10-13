package org.superdro.core.ui.readout

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.LinkFailure
import org.superdro.core.connection.SessionState
import org.superdro.core.model.AngularAxisConfig
import org.superdro.core.model.AxisButtonMode
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.DisplaySettings
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.TachometerConfig
import org.superdro.core.model.TachometerMode
import org.superdro.core.readout.DisplayUnit
import org.superdro.core.readout.ReadoutText
import org.superdro.core.readout.input.AxisValueStatus
import org.superdro.core.resources.Res
import org.superdro.core.resources.readout_no_adapter
import org.superdro.core.resources.readout_undo_all_zero
import org.superdro.core.resources.readout_undo_compensation
import org.superdro.core.resources.readout_undo_zero
import org.superdro.core.resources.readout_waiting_for_adapter
import org.superdro.core.ui.navigation.AxisDetailsRoute
import org.superdro.core.ui.navigation.DivisionsRoute
import org.superdro.core.ui.navigation.EncoderSettingsRoute
import org.superdro.core.ui.navigation.KeypadRoute
import org.superdro.core.ui.navigation.TachometerPresetRoute
import org.superdro.core.ui.text.UiText
import org.superdro.core.model.AuxiliaryConfig
import org.superdro.core.model.AuxiliaryType
import org.superdro.core.model.Channels
import org.superdro.core.model.CompoundProjection
import org.superdro.core.model.HeadTilt
import org.superdro.core.model.InputConfig
import org.superdro.core.resources.fns_undo_compound_clear
import org.superdro.core.resources.fns_undo_head_tilt_clear

/** The readout presenter with a real database in memory, a fake session, and virtual time. */
class ReadoutPresenterTest {

    private val scope = TestScope()
    private val harness = ReadoutHarness(scope)

    @AfterTest
    fun close() = harness.close()

    private fun inch(axis: AxisId = AxisId.X, offset: Double = 0.0) = axis to AxisState(metric = false, absoluteOffset = offset)

    @Test
    fun theWorkedExampleWithAnAbsoluteOffsetShowsTwoInches() = scope.runTest {
        harness.start(states = mapOf(inch(offset = -12_700.0)))
        harness.inputs(AxisId.X to 63_500.0)
        assertEquals("2.0000", harness.text(AxisId.X))
        assertEquals(DisplayUnit.Inch, harness.presenter.axisValue(AxisId.X).value.unit)
    }

    @Test
    fun theCountExampleShowsInchAndMm() = scope.runTest {
        harness.start(states = mapOf(inch(), AxisId.Y to AxisState(metric = true)))
        harness.inputs(AxisId.X to 61_725.0, AxisId.Y to 61_725.0)
        assertEquals("2.4301", harness.text(AxisId.X))
        assertEquals("61.725", harness.text(AxisId.Y))
    }

    @Test
    fun aLatheInDiameterModeShowsTheDiameter() = scope.runTest {
        harness.start(MachineProfile.create(MachineType.Lathe), states = mapOf(AxisId.X to AxisState(metric = false, diameterMode = true)))
        harness.inputs(AxisId.X to 12_700.0)
        assertEquals("1.0000", harness.text(AxisId.X))
        // The radius or diameter button toggles the X display.
        harness.presenter.onFunctionTap(FunctionButton.RadiusDiameter)
        advanceUntilIdle()
        assertEquals("0.5000", harness.text(AxisId.X))
        assertFalse(harness.axisState(AxisId.X).diameterMode)
        assertNull(harness.presenter.uiState.value.undo, "A display toggle has no undo.")
    }

    @Test
    fun theAngularExamplesShowDegreesAndDivisions() = scope.runTest {
        val mill = MachineProfile.create(MachineType.VerticalMill).copy(angular = AngularAxisConfig(enabled = true))
        harness.start(mill)
        harness.inputs(AxisId.Angular to 225_000.0)
        assertEquals("62.50", harness.text(AxisId.Angular))
        harness.inputs(AxisId.Angular to -90_000.0)
        assertEquals("335.00", harness.text(AxisId.Angular))
        assertTrue(harness.presenter.onDivisionsSet(24))
        advanceUntilIdle()
        assertEquals("22.33", harness.text(AxisId.Angular))
        harness.inputs(AxisId.Angular to 225_000.0)
        assertEquals("4.17", harness.text(AxisId.Angular))
        assertEquals(DisplayUnit.Divisions, harness.presenter.axisValue(AxisId.Angular).value.unit)
        assertFalse(harness.presenter.onDivisionsSet(0), "N must be 1 or more.")
    }

    @Test
    fun theIncrementalZeroGivesMinus40000AndThenTheMove() = scope.runTest {
        harness.start(states = mapOf(inch()))
        harness.inputs(AxisId.X to 40_000.0)
        harness.presenter.onActionTap(AxisId.X, AxisButtonMode.SetZero)
        advanceUntilIdle()
        val state = harness.axisState(AxisId.X)
        assertEquals(-40_000.0, state.incrementalOffset)
        assertTrue(state.relative)
        assertEquals("0.0000", harness.text(AxisId.X))
        harness.inputs(AxisId.X to 41_000.0)
        assertEquals("0.0394", harness.text(AxisId.X))
    }

    @Test
    fun theHoldKeepsTheValueAndSetsItAtTheNewPosition() = scope.runTest {
        harness.start(states = mapOf(AxisId.X to AxisState(metric = true)))
        harness.inputs(AxisId.X to 25_000.0)
        harness.presenter.onActionTap(AxisId.X, AxisButtonMode.Hold)
        advanceUntilIdle()
        assertEquals(ValueState.Held, harness.presenter.axisValue(AxisId.X).value.state)
        harness.inputs(AxisId.X to 30_000.0)
        assertEquals("25.000", harness.text(AxisId.X))
        harness.presenter.onActionTap(AxisId.X, AxisButtonMode.Hold)
        runCurrent()
        assertEquals("25.000", harness.text(AxisId.X))
        assertEquals(ValueState.Normal, harness.presenter.axisValue(AxisId.X).value.state)
        assertNotNull(harness.presenter.uiState.value.undo, "The held value set is an offset change.")
    }

    @Test
    fun aLongPressCancelsTheHold() = scope.runTest {
        harness.start(states = mapOf(AxisId.X to AxisState(metric = true)))
        harness.inputs(AxisId.X to 25_000.0)
        harness.presenter.onActionTap(AxisId.X, AxisButtonMode.Hold)
        advanceUntilIdle()
        harness.presenter.onActionLongPress(AxisId.X, AxisButtonMode.Hold)
        advanceUntilIdle()
        harness.inputs(AxisId.X to 30_000.0)
        assertEquals("30.000", harness.text(AxisId.X))
        assertEquals(0.0, harness.axisState(AxisId.X).absoluteOffset)
    }

    @Test
    fun theKeypadRelativeEntrySetsTheAbsoluteValue() = scope.runTest {
        harness.start(states = mapOf(inch()))
        harness.inputs(AxisId.X to 31_750.0)
        assertEquals("1.2500", harness.text(AxisId.X))
        assertNull(harness.presenter.onKeypadSet(AxisId.X, "++0.5"))
        advanceUntilIdle()
        // h(44450): A = 44450 − R.
        assertEquals(44_450.0 - 31_750.0, harness.axisState(AxisId.X).absoluteOffset, 1e-9)
        assertEquals("1.7500", harness.text(AxisId.X))
    }

    @Test
    fun theKeypadShowsTheErrorOfTheExpression() = scope.runTest {
        harness.start(states = mapOf(inch()))
        harness.inputs(AxisId.X to 0.0)
        val error = harness.presenter.onKeypadSet(AxisId.X, "1/0")
        assertEquals(UiText.Key("expression_error_division_by_zero", "Division by zero"), error)
        val preview = harness.presenter.keypadPreview(AxisId.X, "2*3")
        assertEquals("6", (preview as org.superdro.core.readout.expression.ExpressionResult.Value).text)
    }

    @Test
    fun halfInAbsoluteModeWithShortPressZeroSetSetsTheAbsoluteHalf() = scope.runTest {
        val mill = MachineProfile.create().copy(display = DisplaySettings(shortPressZero = true), metricDefault = true)
        harness.start(mill)
        harness.inputs(AxisId.X to 50_000.0)
        harness.presenter.onActionTap(AxisId.X, AxisButtonMode.SetCenter)
        advanceUntilIdle()
        val state = harness.axisState(AxisId.X)
        assertFalse(state.relative)
        assertEquals(-25_000.0, state.absoluteOffset)
        assertEquals("25.000", harness.text(AxisId.X))
    }

    @Test
    fun halfInTheDefaultModeSetsTheIncrementalHalf() = scope.runTest {
        harness.start(MachineProfile.create().copy(metricDefault = true))
        harness.inputs(AxisId.X to 50_000.0)
        harness.presenter.onActionTap(AxisId.X, AxisButtonMode.SetCenter)
        advanceUntilIdle()
        val state = harness.axisState(AxisId.X)
        assertTrue(state.relative)
        assertEquals(-25_000.0, state.incrementalOffset)
    }

    @Test
    fun theZeroTableWithShortPressZeroSetOff() = scope.runTest {
        harness.start(MachineProfile.create().copy(metricDefault = true))
        harness.inputs(AxisId.X to 10_000.0, AxisId.Y to 20_000.0, AxisId.Z to 30_000.0)
        harness.presenter.onActionLongPress(AxisId.X, AxisButtonMode.SetZero)
        advanceUntilIdle()
        assertEquals(-10_000.0, harness.axisState(AxisId.X).absoluteOffset, "Long press: L(axis, 0).")
        assertFalse(harness.axisState(AxisId.X).relative)
        harness.presenter.onZeroSetTap()
        advanceUntilIdle()
        listOf(AxisId.X, AxisId.Y, AxisId.Z).forEach { axis -> assertTrue(harness.axisState(axis).relative, "$axis is incremental.") }
        assertEquals(-20_000.0, harness.axisState(AxisId.Y).incrementalOffset)
        harness.presenter.onZeroSetLongPress()
        advanceUntilIdle()
        assertEquals(-30_000.0, harness.axisState(AxisId.Z).absoluteOffset)
        assertFalse(harness.axisState(AxisId.Z).relative)
    }

    @Test
    fun theZeroTableWithShortPressZeroSetOn() = scope.runTest {
        harness.start(MachineProfile.create().copy(metricDefault = true, display = DisplaySettings(shortPressZero = true)))
        harness.inputs(AxisId.X to 10_000.0)
        harness.presenter.onActionTap(AxisId.X, AxisButtonMode.SetZero)
        advanceUntilIdle()
        assertEquals(-10_000.0, harness.axisState(AxisId.X).absoluteOffset, "Tap on an absolute axis: L(axis, 0).")
        assertFalse(harness.axisState(AxisId.X).relative)
        harness.inputs(AxisId.X to 15_000.0)
        harness.presenter.onActionLongPress(AxisId.X, AxisButtonMode.SetZero)
        harness.presenter.onZeroSetLongPress()
        advanceUntilIdle()
        assertEquals(-10_000.0, harness.axisState(AxisId.X).absoluteOffset, "No long press action.")
    }

    @Test
    fun theGlobalModeAndUnitsButtonsShowTheMixedStates() = scope.runTest {
        harness.start(MachineProfile.create().copy(metricDefault = true))
        harness.inputs(AxisId.X to 1_000.0, AxisId.Y to 2_000.0, AxisId.Z to 3_000.0)
        assertEquals(org.superdro.core.readout.operations.GlobalModeState.Absolute, harness.presenter.uiState.value.globalButtons.mode)
        harness.presenter.onActionTap(AxisId.X, AxisButtonMode.ToggleIncremental)
        advanceUntilIdle()
        assertEquals(org.superdro.core.readout.operations.GlobalModeState.Mixed, harness.presenter.uiState.value.globalButtons.mode)
        assertNull(harness.presenter.uiState.value.undo, "A mode toggle has no undo.")
        // Mixed modes: the long press does nothing.
        harness.presenter.onModeLongPress()
        harness.presenter.onModeTap()
        advanceUntilIdle()
        assertEquals(org.superdro.core.readout.operations.GlobalModeState.Absolute, harness.presenter.uiState.value.globalButtons.mode)
        harness.presenter.onActionTap(AxisId.X, AxisButtonMode.ToggleUnits)
        advanceUntilIdle()
        assertEquals(org.superdro.core.readout.operations.GlobalUnitsState.Mixed, harness.presenter.uiState.value.globalButtons.units)
        harness.presenter.onUnitsTap()
        advanceUntilIdle()
        assertEquals(org.superdro.core.readout.operations.GlobalUnitsState.Inch, harness.presenter.uiState.value.globalButtons.units)
        harness.presenter.onUnitsTap()
        advanceUntilIdle()
        assertEquals(org.superdro.core.readout.operations.GlobalUnitsState.Metric, harness.presenter.uiState.value.status.units)
    }

    @Test
    fun theUndoShowsForExactly10SecondsAndRestoresAllOffsets() = scope.runTest {
        harness.start(MachineProfile.create().copy(metricDefault = true))
        harness.inputs(AxisId.X to 10_000.0, AxisId.Y to 20_000.0, AxisId.Z to 30_000.0)
        harness.presenter.onZeroSetTap()
        runCurrent()
        val undo = assertNotNull(harness.presenter.uiState.value.undo)
        assertEquals(UiText.of(Res.string.readout_undo_all_zero), undo.label)
        advanceTimeBy(ReadoutPresenter.UNDO_MS - 1)
        runCurrent()
        assertNotNull(harness.presenter.uiState.value.undo, "The undo shows until 10 000 ms.")
        advanceTimeBy(1)
        runCurrent()
        assertNull(harness.presenter.uiState.value.undo, "The undo goes away after 10 000 ms.")

        harness.inputs(AxisId.X to 11_000.0, AxisId.Y to 22_000.0, AxisId.Z to 33_000.0)
        harness.presenter.onZeroSetTap()
        advanceTimeBy(5_000)
        runCurrent()
        val second = assertNotNull(harness.presenter.uiState.value.undo)
        harness.presenter.onUndo(second.id)
        advanceUntilIdle()
        assertNull(harness.presenter.uiState.value.undo)
        // The undo restores the offsets of the first zero.
        assertEquals(-10_000.0, harness.axisState(AxisId.X).incrementalOffset)
        assertEquals(-20_000.0, harness.axisState(AxisId.Y).incrementalOffset)
        assertEquals(-30_000.0, harness.axisState(AxisId.Z).incrementalOffset)
        assertEquals("1.000", harness.text(AxisId.X))
    }

    @Test
    fun theUndoRestoresEachOffsetOfTheAction() = scope.runTest {
        harness.start(MachineProfile.create().copy(metricDefault = true))
        harness.inputs(AxisId.X to 10_000.0, AxisId.Y to 20_000.0, AxisId.Z to 30_000.0)
        val before = listOf(AxisId.X, AxisId.Y, AxisId.Z).associateWith { axis -> harness.axisState(axis) }
        harness.presenter.onZeroSetLongPress()
        runCurrent()
        assertEquals(-20_000.0, harness.axisState(AxisId.Y).absoluteOffset)
        harness.presenter.onUndo(assertNotNull(harness.presenter.uiState.value.undo).id)
        advanceUntilIdle()
        before.forEach { (axis, state) -> assertEquals(state, harness.axisState(axis), "$axis") }
        assertEquals("10.000", harness.text(AxisId.X))
    }

    @Test
    fun anOperationNeedsAConnectedSession() = scope.runTest {
        harness.start(state = SessionState.AwaitingData)
        harness.inputs(AxisId.X to 10_000.0)
        assertNull(harness.presenter.onActionTap(AxisId.X, AxisButtonMode.SetZero))
        runCurrent()
        assertEquals(UiText.of(Res.string.readout_waiting_for_adapter), harness.presenter.uiState.value.message?.text)
        assertEquals(0.0, harness.axisState(AxisId.X).incrementalOffset)
        harness.session.state.value = SessionState.Reconnecting(2, 0L, LinkFailure.Lost)
        advanceUntilIdle()
        harness.presenter.onZeroSetTap()
        runCurrent()
        assertEquals(UiText.of(Res.string.readout_no_adapter), harness.presenter.uiState.value.message?.text)
        assertEquals(UiText.of(Res.string.readout_no_adapter), harness.presenter.onKeypadSet(AxisId.X, "1"))
        assertTrue(harness.sounds.played.isNotEmpty(), "A refusal plays the error sound.")
        advanceTimeBy(ReadoutPresenter.MESSAGE_MS + 1)
        runCurrent()
        assertNull(harness.presenter.uiState.value.message, "The message goes away after 4 s.")
    }

    @Test
    fun aTabWithNoLinkKeepsTheLastValues() = scope.runTest {
        harness.start(MachineProfile.create().copy(metricDefault = true))
        harness.inputs(AxisId.X to 12_345.0)
        harness.session.state.value = SessionState.Reconnecting(1, 0L, LinkFailure.Lost)
        harness.session.setAxes(emptyMap())
        advanceUntilIdle()
        assertEquals("12.345", harness.text(AxisId.X))
        assertEquals(ValueState.Offline, harness.presenter.axisValue(AxisId.X).value.state)
        assertEquals(OfflineLabel.Reconnecting, harness.presenter.uiState.value.status.offline)
        harness.session.state.value = SessionState.WaitingForDevice(LinkFailure.RadioOff)
        advanceUntilIdle()
        assertEquals(OfflineLabel.Offline, harness.presenter.uiState.value.status.offline)
        assertFalse(harness.presenter.uiState.value.keepScreenOn)
    }

    @Test
    fun theDisplayStatesOfTheValue() = scope.runTest {
        harness.start(MachineProfile.create().copy(metricDefault = true), state = SessionState.AwaitingData)
        assertEquals("----.---", harness.text(AxisId.X), "No value gives the dash pattern.")
        assertEquals(ValueState.Pending, harness.presenter.axisValue(AxisId.X).value.state)
        harness.session.state.value = SessionState.Connected(0L)
        harness.session.setAxisStatus(AxisId.X, AxisValueStatus.Error)
        harness.session.setAxisStatus(AxisId.Y, AxisValueStatus.NoInput)
        advanceUntilIdle()
        val x = harness.presenter.axisValue(AxisId.X).value
        assertEquals(ValueState.Error, x.state)
        assertEquals('x', x.errorInput)
        assertEquals(EncoderSettingsRoute('x'), harness.presenter.onValueTap(AxisId.X), "A tap on Error opens the encoder settings.")
        assertEquals(ValueState.NoInput, harness.presenter.axisValue(AxisId.Y).value.state)
        harness.inputs(AxisId.Z to 1_000.0)
        assertEquals(KeypadRoute(AxisId.Z), harness.presenter.onValueTap(AxisId.Z))
        assertEquals(AxisDetailsRoute(AxisId.Z), harness.presenter.onLabelTap(AxisId.Z))
        assertTrue(harness.presenter.uiState.value.keepScreenOn, "Keep the screen on while Connected.")
    }

    @Test
    fun aToolOffsetGivesTheModifiedState() = scope.runTest {
        harness.start(states = mapOf(AxisId.X to AxisState(metric = true, toolOffset = 5_000.0, toolOffsetActive = true)))
        harness.inputs(AxisId.X to 1_000.0)
        assertEquals(ValueState.Modified, harness.presenter.axisValue(AxisId.X).value.state)
        assertEquals("6.000", harness.text(AxisId.X))
    }

    @Test
    fun theTachometerModes() = scope.runTest {
        val sensor = MachineProfile.create().copy(tachometer = TachometerConfig(enabled = true, mode = TachometerMode.Sensor))
        harness.start(sensor)
        assertNull(harness.presenter.onValueTap(AxisId.Tachometer))
        runCurrent()
        assertEquals(UiText.Key(ReadoutText.CantSetRpm.key, ReadoutText.CantSetRpm.english), harness.presenter.uiState.value.message?.text)
    }

    @Test
    fun thePresetModeOpensThePresetSheetAndSetsTheRpm() = scope.runTest {
        harness.start(MachineProfile.create().copy(tachometer = TachometerConfig(enabled = true, mode = TachometerMode.Preset)), state = SessionState.AwaitingData)
        assertEquals(TachometerPresetRoute, harness.presenter.onValueTap(AxisId.Tachometer))
        assertEquals(listOf(0.0, 105.0, 160.0, 250.0, 585.0, 898.0, 1400.0), harness.presenter.presets())
        harness.presenter.onPresetSelected(1400.0)
        advanceUntilIdle()
        assertEquals("1400", harness.text(AxisId.Tachometer))
        assertEquals("1400", harness.presenter.uiState.value.status.rpmText)
    }

    @Test
    fun theDirectEntryModeOpensTheKeypad() = scope.runTest {
        harness.start(MachineProfile.create().copy(tachometer = TachometerConfig(enabled = true, mode = TachometerMode.Direct)), state = SessionState.AwaitingData)
        assertEquals(KeypadRoute(AxisId.Tachometer), harness.presenter.onValueTap(AxisId.Tachometer))
        assertNull(harness.presenter.onKeypadSet(AxisId.Tachometer, "600*2"), "No live position is necessary with no input.")
        advanceUntilIdle()
        assertEquals("1200", harness.text(AxisId.Tachometer))
    }

    @Test
    fun aLongPressOnAnActiveCompensationClearsItWithUndo() = scope.runTest {
        val states = mapOf(
            AxisId.X to AxisState(metric = true, toolOffset = 5_000.0, toolOffsetActive = true),
            AxisId.Y to AxisState(metric = true, toolOffset = -5_000.0, toolOffsetActive = true),
        )
        harness.start(states = states)
        harness.inputs(AxisId.X to 0.0, AxisId.Y to 0.0)
        val radius = harness.presenter.uiState.value.functions.first { item -> item.button == FunctionButton.RadiusCompensation }
        assertTrue(radius.active)
        assertNull(harness.presenter.onFunctionLongPress(FunctionButton.RadiusCompensation))
        runCurrent()
        assertFalse(harness.axisState(AxisId.X).toolOffsetActive)
        assertFalse(harness.presenter.uiState.value.functions.first { item -> item.button == FunctionButton.RadiusCompensation }.active)
        val undo = assertNotNull(harness.presenter.uiState.value.undo)
        assertEquals(UiText.of(Res.string.readout_undo_compensation), undo.label)
        harness.presenter.onUndo(undo.id)
        advanceUntilIdle()
        assertEquals(5_000.0, harness.axisState(AxisId.X).toolOffset)
        assertTrue(harness.axisState(AxisId.Y).toolOffsetActive)
    }

    @Test
    fun aLongPressOnTheActiveHeadTiltButtonClearsTheProjectionWithUndo() = scope.runTest {
        val base = MachineProfile.create(MachineType.VerticalMill, "Mill")
        val quill = base.copy(z = base.z.copy(secondary = InputConfig(Channels.W)))
        val tilted = quill.copy(
            workspaces = quill.workspaces.map { workspace -> workspace.copy(headTilt = HeadTilt(active = true, tiltArcSec = 10.0 * 3600.0, nodArcSec = 5.0 * 3600.0)) },
        )
        harness.start(tilted)
        val button = harness.presenter.uiState.value.functions.first { item -> item.button == FunctionButton.HeadTiltCompensation }
        assertTrue(button.active)
        assertNull(harness.presenter.onFunctionLongPress(FunctionButton.HeadTiltCompensation))
        runCurrent()
        // The projection goes off; the angles and the switches stay. The checks read the
        // frame, not the store: a store read lets the virtual time run past the undo time.
        assertEquals(HeadTilt(active = false, tiltArcSec = 36_000.0, nodArcSec = 18_000.0), harness.presenter.frame.value!!.workspace.headTilt)
        assertFalse(harness.presenter.uiState.value.functions.first { item -> item.button == FunctionButton.HeadTiltCompensation }.active)
        val undo = assertNotNull(harness.presenter.uiState.value.undo)
        assertEquals(UiText.of(Res.string.fns_undo_head_tilt_clear), undo.label)
        harness.presenter.onUndo(undo.id)
        runCurrent()
        assertTrue(harness.presenter.frame.value!!.workspace.headTilt.active)
        assertTrue(harness.workspace().headTilt.active)
    }

    @Test
    fun aLongPressOnTheActiveCompoundButtonClearsTheProjectionWithUndo() = scope.runTest {
        val base = MachineProfile.create(MachineType.Lathe, "Lathe")
        val compound = base.copy(auxiliary = AuxiliaryConfig(type = AuxiliaryType.CompoundSlide, vectoring = true))
        val active = compound.copy(
            workspaces = compound.workspaces.map { workspace -> workspace.copy(compoundProjection = CompoundProjection(active = true, angleArcSec = 30.0 * 3600.0)) },
        )
        harness.start(active)
        // The compound button shows the active state.
        assertTrue(harness.presenter.uiState.value.functions.first { item -> item.button == FunctionButton.CompoundCompensation }.active)
        assertNull(harness.presenter.onFunctionLongPress(FunctionButton.CompoundCompensation))
        runCurrent()
        assertEquals(CompoundProjection(active = false, angleArcSec = 108_000.0), harness.presenter.frame.value!!.workspace.compoundProjection)
        val undo = assertNotNull(harness.presenter.uiState.value.undo)
        assertEquals(UiText.of(Res.string.fns_undo_compound_clear), undo.label)
        // A second long press on the button that is not active now opens nothing and changes nothing.
        assertNull(harness.presenter.onFunctionLongPress(FunctionButton.CompoundCompensation))
        runCurrent()
        assertEquals(undo.id, harness.presenter.uiState.value.undo?.id)
        assertFalse(harness.workspace().compoundProjection.active)
    }

    @Test
    fun theSetupReminderShowsForAnInputWithNoCalibration() = scope.runTest {
        harness.start()
        assertEquals(SetupReminderUiState(AxisId.X), harness.presenter.uiState.value.setupReminder, "No adapter profile: no encoder profile.")
        harness.store.appSettings.update { settings -> settings.copy(showSetupReminder = false) }
        advanceUntilIdle()
        assertNull(harness.presenter.uiState.value.setupReminder, "The app setting turns it off.")
    }

    @Test
    fun theSetupReminderGoesAwayAfterAUserResolution() = scope.runTest {
        val calibrated = org.superdro.core.model.AdapterProfile(alias = "Adapter").let { adapter ->
            adapter.copy(encoders = adapter.encoders.mapValues { (_, encoder) ->
                encoder.copy(calibration = org.superdro.core.model.Calibration(org.superdro.core.model.CalibrationStatus.UserSet))
            })
        }
        harness.store.adapters.saveAdapterProfile(calibrated)
        harness.start(MachineProfile.create().copy(adapterProfileId = calibrated.id))
        assertNull(harness.presenter.uiState.value.setupReminder)
    }

    @Test
    fun aHintShowsOneTimeAndDoesNotBlockTheAction() = scope.runTest {
        harness.start(MachineProfile.create().copy(metricDefault = true))
        harness.inputs(AxisId.X to 10_000.0)
        harness.presenter.onActionTap(AxisId.X, AxisButtonMode.SetZero)
        runCurrent()
        assertEquals(ReadoutPresenter.Hint.ZeroLongPress.text(), harness.presenter.uiState.value.hint)
        assertEquals(-10_000.0, harness.presenter.frame.value?.let { _ -> harness.axisState(AxisId.X).incrementalOffset }, "The action ran.")
        assertTrue("hint_zero_long_press" in harness.store.appSettings.settings.value.shownHints)
        advanceTimeBy(ReadoutPresenter.HINT_MS + 1)
        runCurrent()
        assertNull(harness.presenter.uiState.value.hint)
        harness.presenter.onActionTap(AxisId.X, AxisButtonMode.SetZero)
        runCurrent()
        assertNull(harness.presenter.uiState.value.hint, "A hint shows one time.")
    }

    @Test
    fun noHintWithTheSettingOff() = scope.runTest {
        harness.store.appSettings.update { settings -> settings.copy(showHints = false) }
        harness.start()
        harness.inputs(AxisId.X to 10_000.0)
        harness.presenter.onModeTap()
        runCurrent()
        assertNull(harness.presenter.uiState.value.hint)
    }

    @Test
    fun thePlusButtonTellsTheSingleTheDoubleAndTheLongPress() = scope.runTest {
        harness.start()
        harness.presenter.onAddTap()
        advanceTimeBy(ReadoutPresenter.DOUBLE_TAP_MS - 1)
        runCurrent()
        assertNull(harness.presenter.uiState.value.addPointRequest)
        harness.presenter.onAddTap()
        runCurrent()
        val double = assertNotNull(harness.presenter.uiState.value.addPointRequest)
        assertEquals(AddPointKind.Double, double.kind)
        harness.presenter.onAddRequestHandled(double.id)
        harness.presenter.onAddTap()
        advanceTimeBy(ReadoutPresenter.DOUBLE_TAP_MS + 1)
        runCurrent()
        assertEquals(AddPointKind.Single, harness.presenter.uiState.value.addPointRequest?.kind)
        harness.presenter.onAddLongPress()
        runCurrent()
        assertEquals(AddPointKind.Long, harness.presenter.uiState.value.addPointRequest?.kind)
    }

    @Test
    fun theUnitsButtonOfTheAngularAxisAsksForTheDivisions() = scope.runTest {
        harness.start(MachineProfile.create().copy(angular = AngularAxisConfig(enabled = true)))
        assertEquals(DivisionsRoute, harness.presenter.onActionTap(AxisId.Angular, AxisButtonMode.ToggleUnits))
        assertEquals(DivisionsRoute, harness.presenter.onActionTap(AxisId.Angular, AxisButtonMode.DivideCircle))
        assertNull(harness.presenter.divisionsValue())
    }

    @Test
    fun theCenterInAbsoluteModeSetsTheAbsoluteHalf() = scope.runTest {
        harness.start(MachineProfile.create().copy(metricDefault = true), states = mapOf(AxisId.X to AxisState(relative = true, metric = true)))
        harness.inputs(AxisId.X to 40_000.0)
        harness.presenter.onCenterAbsolute(AxisId.X)
        advanceUntilIdle()
        assertFalse(harness.axisState(AxisId.X).relative)
        assertEquals("20.000", harness.text(AxisId.X))
    }

    @Test
    fun aZeroLabelNamesTheAxis() = scope.runTest {
        harness.start()
        harness.inputs(AxisId.X to 10_000.0)
        harness.presenter.onActionTap(AxisId.X, AxisButtonMode.SetZero)
        runCurrent()
        assertEquals(UiText.of(Res.string.readout_undo_zero, "X"), harness.presenter.uiState.value.undo?.label)
    }
}
