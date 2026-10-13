package org.superdro.core.readout.operations

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.model.AxisButtonConfig
import org.superdro.core.model.AxisButtonMode
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.SubDatum
import org.superdro.core.model.TachometerMode
import org.superdro.core.model.Workspace
import org.superdro.core.readout.Fixtures
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.readout.ReadoutText

/** The zero table, the global buttons, and the action buttons. */
class ButtonsTest {

    private val inputs = mapOf(AxisId.X to 1000.0, AxisId.Y to 2000.0, AxisId.Z to 3000.0, AxisId.Angular to 3600.0, AxisId.Tachometer to 0.0)

    private fun profile(shortPressZero: Boolean = false, angular: Boolean = false): MachineProfile {
        val mill = Fixtures.mill()
        return mill.copy(display = mill.display.copy(shortPressZero = shortPressZero), angular = mill.angular.copy(enabled = angular))
    }

    private fun frame(profile: MachineProfile, states: Map<AxisId, AxisState> = emptyMap(), change: (Workspace) -> Workspace = { it }): ReadoutFrame {
        val base = Fixtures.workspace(profile)
        return ReadoutFrame(profile, change(base.copy(axisStates = base.axisStates + states)), inputs)
    }

    private fun OperationResult.state(axis: AxisId): AxisState = workspace.axisStates.getValue(axis)

    @Test
    fun theAxisZeroButtonWithShortPressZeroOff() {
        val frame = frame(profile())
        val tap = ZeroButtons.tap(frame, AxisId.X)
        assertEquals(-1000.0, tap.state(AxisId.X).incrementalOffset)
        assertTrue(tap.state(AxisId.X).relative)
        val long = ZeroButtons.longPress(frame(profile(), mapOf(AxisId.X to AxisState(relative = true))), AxisId.X)
        assertEquals(-1000.0, long.state(AxisId.X).absoluteOffset, "the absolute origin")
        assertFalse(long.state(AxisId.X).relative)
        assertTrue(long.undo != null, "undo in place of the question")
    }

    @Test
    fun theAxisZeroButtonWithShortPressZeroOn() {
        val incremental = ZeroButtons.tap(frame(profile(shortPressZero = true), mapOf(AxisId.X to AxisState(relative = true))), AxisId.X)
        assertEquals(-1000.0, incremental.state(AxisId.X).incrementalOffset)
        val absolute = ZeroButtons.tap(frame(profile(shortPressZero = true)), AxisId.X)
        assertEquals(-1000.0, absolute.state(AxisId.X).absoluteOffset)
        assertFalse(absolute.state(AxisId.X).relative)
        assertNull(ZeroButtons.longPress(frame(profile(shortPressZero = true)), AxisId.X).undo, "no action")
    }

    @Test
    fun theGlobalZeroSetWithShortPressZeroOff() {
        val point = SubDatum(id = "s1", x = 1.0)
        val mill = profile(angular = true).let { profile -> profile.copy(z = profile.z.copy(enabled = false)) }
        val tap = ZeroButtons.globalTap(frame(mill) { it.copy(subDatums = listOf(point), activeSubDatumId = "s1") })
        assertEquals(-1000.0, tap.state(AxisId.X).incrementalOffset)
        assertEquals(-2000.0, tap.state(AxisId.Y).incrementalOffset)
        assertEquals(0.0, tap.state(AxisId.Z).incrementalOffset, "Z is disabled")
        assertEquals(-3600.0, tap.state(AxisId.Angular).incrementalOffset)
        assertNull(tap.workspace.activeSubDatumId)
        val long = ZeroButtons.globalLongPress(frame(mill))
        assertEquals(-1000.0, long.state(AxisId.X).absoluteOffset)
        assertEquals(-2000.0, long.state(AxisId.Y).absoluteOffset)
        assertEquals(0.0, long.state(AxisId.Z).absoluteOffset, "L does nothing for a disabled axis")
        assertEquals(-3600.0, long.state(AxisId.Angular).absoluteOffset)
    }

    @Test
    fun theGlobalZeroSetWithShortPressZeroOn() {
        val profile = profile(shortPressZero = true)
        val tap = ZeroButtons.globalTap(frame(profile, mapOf(AxisId.Y to AxisState(relative = true))))
        assertEquals(-1000.0, tap.state(AxisId.X).absoluteOffset)
        assertEquals(-2000.0, tap.state(AxisId.Y).incrementalOffset)
        assertEquals(0.0, tap.state(AxisId.Y).absoluteOffset)
        assertEquals(-3000.0, tap.state(AxisId.Z).absoluteOffset)
        assertNull(ZeroButtons.globalLongPress(frame(profile)).undo)
    }

    @Test
    fun theGlobalModeButtonCountsTheEnabledAxes() {
        val profile = profile(angular = true)
        assertEquals(GlobalModeState.Absolute, GlobalButtons.modeState(frame(profile)))
        val mixed = frame(profile, mapOf(AxisId.X to AxisState(relative = true)))
        assertEquals(GlobalModeState.Mixed, GlobalButtons.modeState(mixed))
        val all = frame(profile, listOf(AxisId.X, AxisId.Y, AxisId.Z, AxisId.Angular).associateWith { AxisState(relative = true) })
        assertEquals(GlobalModeState.Incremental, GlobalButtons.modeState(all))

        val toIncremental = GlobalButtons.modeTap(frame(profile))
        listOf(AxisId.X, AxisId.Y, AxisId.Z, AxisId.Angular).forEach { axis -> assertTrue(toIncremental.state(axis).relative, "$axis") }
        val toAbsolute = GlobalButtons.modeTap(mixed)
        listOf(AxisId.X, AxisId.Y, AxisId.Z, AxisId.Angular).forEach { axis -> assertFalse(toAbsolute.state(axis).relative, "$axis") }
    }

    @Test
    fun theGlobalModeLongPress() {
        val profile = profile(angular = true)
        val offsets = AxisState(absoluteOffset = 5.0, incrementalOffset = 7.0)
        val states = AxisId.entries.associateWith { offsets }
        val absolute = GlobalButtons.modeLongPress(frame(profile, states))
        listOf(AxisId.X, AxisId.Y, AxisId.Z, AxisId.Angular).forEach { axis -> assertEquals(0.0, absolute.state(axis).absoluteOffset) }
        assertEquals(7.0, absolute.state(AxisId.X).incrementalOffset)

        val incrementalStates = AxisId.entries.associateWith { offsets.copy(relative = true) }
        val point = SubDatum(id = "s1", x = 1.0)
        val incremental = GlobalButtons.modeLongPress(frame(profile, incrementalStates) { it.copy(subDatums = listOf(point), activeSubDatumId = "s1") })
        listOf(AxisId.X, AxisId.Y, AxisId.Z, AxisId.Angular).forEach { axis -> assertEquals(0.0, incremental.state(axis).incrementalOffset) }
        assertEquals(7.0, incremental.state(AxisId.Tachometer).incrementalOffset, "the tachometer is disabled")
        assertNull(incremental.workspace.activeSubDatumId)
        assertTrue(incremental.undo != null)

        val mixed = frame(profile, mapOf(AxisId.X to AxisState(relative = true, incrementalOffset = 7.0)))
        assertNull(GlobalButtons.modeLongPress(mixed).undo, "mixed modes: no action")
    }

    @Test
    fun theGlobalUnitsButton() {
        val profile = profile()
        assertEquals(GlobalUnitsState.Inch, GlobalButtons.unitsState(frame(profile)))
        val toMm = GlobalButtons.unitsTap(frame(profile))
        listOf(AxisId.X, AxisId.Y, AxisId.Z).forEach { axis -> assertEquals(true, toMm.state(axis).metric) }
        val mixed = frame(profile, mapOf(AxisId.Y to AxisState(metric = true)))
        assertEquals(GlobalUnitsState.Mixed, GlobalButtons.unitsState(mixed))
        val toInch = GlobalButtons.unitsTap(mixed)
        listOf(AxisId.X, AxisId.Y, AxisId.Z).forEach { axis -> assertEquals(false, toInch.state(axis).metric) }
    }

    @Test
    fun theUnitsCountStopsAtTheFirstDisabledAxisFromTheTop() {
        val mill = profile()
        val yOff = mill.copy(y = mill.y.copy(enabled = false))
        // Z shows mm, but the count stops at Y: X shows inch, so all change to mm.
        val frame = frame(yOff, mapOf(AxisId.Z to AxisState(metric = true)))
        val result = GlobalButtons.unitsTap(frame)
        listOf(AxisId.X, AxisId.Y, AxisId.Z).forEach { axis -> assertEquals(true, result.state(axis).metric) }
        assertEquals(listOf(AxisId.X, AxisId.Z, AxisId.Y), GlobalButtons.rowOrder(ReadoutFrame(Fixtures.lathe(), Fixtures.workspace(Fixtures.lathe()))))
    }

    @Test
    fun theDivideCircleModeIsNotOfferedForALinearAxis() {
        assertFalse(AxisButtonMode.DivideCircle in ActionButtons.offeredModes(AxisId.X))
        assertEquals(AxisButtonMode.entries, ActionButtons.offeredModes(AxisId.Angular))
        val tap = ActionButtons.tap(frame(profile()), AxisId.X, AxisButtonMode.DivideCircle)
        assertNull(tap.undo)
        assertNull(tap.request)
        assertEquals(OperationRequest.OpenDivisionsSheet, ActionButtons.tap(frame(profile(angular = true)), AxisId.Angular, AxisButtonMode.DivideCircle).request)
    }

    @Test
    fun theShowFlagsControlTheVisibility() {
        val buttons = AxisButtonConfig.defaults() + AxisButtonConfig(AxisButtonMode.DivideCircle)
        assertEquals(buttons.take(3), ActionButtons.visibleButtons(AxisId.X, buttons, collapsed = false))
        assertEquals(listOf(buttons[0]), ActionButtons.visibleButtons(AxisId.X, buttons, collapsed = true))
        assertEquals(buttons, ActionButtons.visibleButtons(AxisId.Angular, buttons, collapsed = false))
        val hidden = listOf(AxisButtonConfig(AxisButtonMode.Hold, showExpanded = false))
        assertEquals(emptyList(), ActionButtons.visibleButtons(AxisId.Y, hidden, collapsed = false))
    }

    @Test
    fun theActionButtonModes() {
        val frame = frame(profile(), mapOf(AxisId.X to AxisState(absoluteOffset = 5.0, incrementalOffset = 7.0)))
        assertEquals(-1005.0, ActionButtons.tap(frame, AxisId.X, AxisButtonMode.SetZero).state(AxisId.X).incrementalOffset)
        assertTrue(ActionButtons.tap(frame, AxisId.X, AxisButtonMode.ToggleIncremental).state(AxisId.X).relative)
        assertEquals(0.0, ActionButtons.longPress(frame, AxisId.X, AxisButtonMode.ToggleIncremental).state(AxisId.X).absoluteOffset)
        val incremental = frame(profile(), mapOf(AxisId.X to AxisState(absoluteOffset = 5.0, incrementalOffset = 7.0, relative = true)))
        assertEquals(0.0, ActionButtons.longPress(incremental, AxisId.X, AxisButtonMode.ToggleIncremental).state(AxisId.X).incrementalOffset)
        assertEquals(true, ActionButtons.tap(frame, AxisId.X, AxisButtonMode.ToggleUnits).state(AxisId.X).metric)
        assertTrue(ActionButtons.tap(frame, AxisId.X, AxisButtonMode.SetCenter).state(AxisId.X).relative)
        assertEquals(mapOf(AxisId.X to 1005.0), ActionButtons.tap(frame, AxisId.X, AxisButtonMode.Hold).hold)
    }

    @Test
    fun theAngularUnitsButtonNeedsTheDivisions() {
        val profile = profile(angular = true)
        assertEquals(OperationRequest.OpenDivisionsSheet, ActionButtons.tap(frame(profile), AxisId.Angular, AxisButtonMode.ToggleUnits).request)
        val withN = frame(profile, mapOf(AxisId.Angular to AxisState(divisionsPerRev = 24)))
        assertTrue(ActionButtons.tap(withN, AxisId.Angular, AxisButtonMode.ToggleUnits).state(AxisId.Angular).divisionUnits)
        val set = Divisions.set(frame(profile), 24)
        assertEquals(24, set.state(AxisId.Angular).divisionsPerRev)
        assertTrue(set.state(AxisId.Angular).divisionUnits)
        assertNull(Divisions.set(frame(profile), 0).undo, "N must be 1 or more")
        assertEquals(24, Divisions.sheetValue(withN))
        assertNull(Divisions.sheetValue(frame(profile)))
    }

    @Test
    fun theTachometerTapDependsOnTheMode() {
        val mill = profile()
        val sensor = TachometerActions.tap(frame(mill))
        assertEquals(OperationRequest.ShowMessage(org.superdro.core.readout.ReadoutMessage(ReadoutText.CantSetRpm)), sensor.request)
        assertEquals("Can't set RPM", (sensor.request as OperationRequest.ShowMessage).message.english)
        val preset = TachometerActions.tap(frame(mill.copy(tachometer = mill.tachometer.copy(mode = TachometerMode.Preset))))
        assertEquals(OperationRequest.OpenPresetSheet(listOf(0.0, 105.0, 160.0, 250.0, 585.0, 898.0, 1400.0)), preset.request)
        val direct = TachometerActions.tap(frame(mill.copy(tachometer = mill.tachometer.copy(mode = TachometerMode.Direct))))
        assertEquals(OperationRequest.OpenKeypad(AxisId.Tachometer), direct.request)
    }

    @Test
    fun thePresetListAndTheSelection() {
        assertEquals(listOf(0.0, 105.0, 160.0, 250.0, 585.0, 898.0, 1400.0), TachometerActions.presetList(TachometerActions.parsePresets("105 160 250 585 898 1400")))
        assertEquals(listOf(0.0, 250.0), TachometerActions.presetList(listOf(250.0, 0.0)))
        val frame = frame(profile().let { mill -> mill.copy(tachometer = mill.tachometer.copy(mode = TachometerMode.Preset)) })
        val selected = TachometerActions.selectPreset(frame, 585.0)
        assertEquals(585.0, selected.state(AxisId.Tachometer).absoluteOffset)
        assertEquals(
            "585",
            ReadoutFrame(frame.profile, selected.workspace, frame.inputs).reading(AxisId.Tachometer).text,
        )
    }

    @Test
    fun thePresetListAlwaysStartsWithOneZero() {
        assertEquals(listOf(0.0, 105.0, 160.0), TachometerActions.presetList(listOf(105.0, 0.0, 160.0)))
        assertEquals(listOf(0.0, 105.0), TachometerActions.presetList(listOf(0.0, 105.0)))
        assertEquals(listOf(0.0, 105.0), TachometerActions.presetList(listOf(0.0, 105.0, 0.0)))
        assertEquals(listOf(0.0), TachometerActions.presetList(emptyList()))
        assertEquals(listOf(0.0, 105.0, 160.0), TachometerActions.presetList(TachometerActions.parsePresets("105 0 160")))
    }

    @Test
    fun aChangeOfThePulsesSetsTheTachometerOffsetsToZero() {
        val workspace = Fixtures.workspace(Fixtures.mill()).let { ws ->
            Fixtures.withState(ws, AxisId.Tachometer, AxisState(absoluteOffset = 585.0, incrementalOffset = 10.0))
        }
        val changed = TachometerActions.onPulsesChanged(workspace, old = 1.0, new = 4.0)
        assertEquals(AxisState(), changed.axisStates.getValue(AxisId.Tachometer))
        assertEquals(workspace, TachometerActions.onPulsesChanged(workspace, old = 1.0, new = 0.0))
        assertEquals(workspace, TachometerActions.onPulsesChanged(workspace, old = 4.0, new = 4.0))
    }
}
