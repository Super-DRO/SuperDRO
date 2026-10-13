package org.superdro.validation.readout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.model.AxisButtonConfig
import org.superdro.core.model.AxisButtonMode
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.SubDatum
import org.superdro.core.model.Workspace
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.readout.operations.ActionButtons
import org.superdro.core.readout.operations.AxisOperations
import org.superdro.core.readout.operations.GlobalButtons
import org.superdro.core.readout.operations.GlobalModeState
import org.superdro.core.readout.operations.GlobalUnitsState
import org.superdro.core.readout.operations.OperationRequest
import org.superdro.core.readout.operations.ZeroButtons

/**
 * The operations table, the examples, the zero table for both
 * values of `short_press_zero_set`, the global buttons, the action buttons, the keypad, and the
 * undo.
 */
class AxisOperationsValidationTest {

    private fun shortPress(profile: MachineProfile, on: Boolean): MachineProfile =
        profile.copy(display = profile.display.copy(shortPressZero = on))

    private fun ReadoutFrame.after(workspace: Workspace): ReadoutFrame = with(workspace)

    private fun Workspace.state(axis: AxisId): AxisState = axisStates.getValue(axis)

    // The operations table.

    @Test
    fun h_sets_A_to_c_V_minus_R_plus_T() {
        val profile = mill()
        val workspace = profile.workspace.axis(AxisId.X) { state -> state.copy(toolOffset = 500.0, toolOffsetActive = true) }
        val result = AxisOperations.setAbsolute(frame(profile, workspace, AxisId.X to 10000.0), AxisId.X, 30000.0)
        assertEquals(19500.0, result.workspace.state(AxisId.X).absoluteOffset)
    }

    @Test
    fun L_sets_the_value_and_absolute_mode() {
        val profile = mill()
        val workspace = profile.workspace.axis(AxisId.X) { state -> state.copy(relative = true) }
        val start = frame(profile, workspace, AxisId.X to 10000.0)
        val result = AxisOperations.setAxis(start, AxisId.X, 0.0)
        assertFalse(result.workspace.state(AxisId.X).relative)
        assertEquals(-10000.0, result.workspace.state(AxisId.X).absoluteOffset)
        assertEquals(0.0, start.after(result.workspace).reading(AxisId.X).shown)
    }

    @Test
    fun L_does_nothing_for_a_disabled_axis_X_included() {
        val base = mill()
        val profile = base.copy(x = base.x.copy(enabled = false), y = base.y.copy(enabled = false))
        val start = frameOf(profile, AxisId.X to 10000.0, AxisId.Y to 5000.0)
        val x = AxisOperations.setAxis(start, AxisId.X, 0.0)
        assertEquals(0.0, x.workspace.state(AxisId.X).absoluteOffset)
        assertFalse(x.changed)
        val y = AxisOperations.setAxis(start, AxisId.Y, 0.0)
        assertEquals(0.0, y.workspace.state(AxisId.Y).absoluteOffset)
        assertFalse(y.changed)
    }

    @Test
    fun incremental_zero_at_abs_40000_gives_I_minus_40000_and_0_0394_after_1000() {
        val profile = mill()
        val start = frameOf(profile, AxisId.X to 40000.0)
        val result = AxisOperations.setIncremental(start, AxisId.X, 0.0)
        val state = result.workspace.state(AxisId.X)
        assertEquals(-40000.0, state.incrementalOffset)
        assertTrue(state.relative)
        assertEquals(0.0, start.after(result.workspace).reading(AxisId.X).shown)
        val moved = ReadoutFrame(profile, result.workspace, mapOf(AxisId.X to 41000.0))
        assertEquals("0.0394", moved.reading(AxisId.X).text)
    }

    @Test
    fun incremental_set_on_an_axis_of_the_active_sub_datum_makes_it_not_active() {
        val profile = mill()
        val point = SubDatum(name = "Point 1", x = 25400.0)
        val workspace = profile.workspace.copy(subDatums = listOf(point), activeSubDatumId = point.id)
        val start = frame(profile, workspace, AxisId.X to 30000.0, AxisId.Y to 0.0)
        // The sub-datum has no Y coordinate: an incremental set on Y keeps it.
        assertEquals(point.id, AxisOperations.setIncremental(start, AxisId.Y, 0.0).workspace.activeSubDatumId)
        assertNull(AxisOperations.setIncremental(start, AxisId.X, 0.0).workspace.activeSubDatumId)
    }

    @Test
    fun clear_absolute_and_clear_incremental() {
        val profile = mill()
        val workspace = profile.workspace.axis(AxisId.X) { state ->
            state.copy(absoluteOffset = 1000.0, incrementalOffset = -500.0, relative = true)
        }
        val start = frame(profile, workspace, AxisId.X to 10000.0)
        assertEquals(0.0, AxisOperations.clearAbsolute(start, AxisId.X).workspace.state(AxisId.X).absoluteOffset)
        val cleared = AxisOperations.clearIncremental(start, AxisId.X).workspace
        assertEquals(0.0, cleared.state(AxisId.X).incrementalOffset)
        // Incremental mode then shows abs.
        assertEquals(11000.0, start.after(cleared).reading(AxisId.X).shown)
    }

    @Test
    fun toggle_mode_keeps_the_old_I() {
        val profile = mill()
        val start = frameOf(profile, AxisId.X to 40000.0)
        val zeroed = AxisOperations.setIncremental(start, AxisId.X, 0.0).workspace
        val absolute = AxisOperations.toggleMode(start.after(zeroed), AxisId.X).workspace
        assertFalse(absolute.state(AxisId.X).relative)
        assertEquals(-40000.0, absolute.state(AxisId.X).incrementalOffset)
        assertEquals(40000.0, start.after(absolute).reading(AxisId.X).shown)
        val back = AxisOperations.toggleMode(start.after(absolute), AxisId.X).workspace
        assertEquals(0.0, start.after(back).reading(AxisId.X).shown)
    }

    @Test
    fun half_sets_the_incremental_value_shown_over_2() {
        val profile = mill()
        val start = frameOf(profile, AxisId.X to 40000.0)
        val result = AxisOperations.half(start, AxisId.X).workspace
        assertTrue(result.state(AxisId.X).relative)
        assertEquals(20000.0, start.after(result).reading(AxisId.X).shown)
        assertEquals(0.0, result.state(AxisId.X).absoluteOffset)
    }

    @Test
    fun half_with_short_press_zero_on_and_absolute_sets_the_absolute_value() {
        val profile = shortPress(mill(), on = true)
        val start = frameOf(profile, AxisId.X to 40000.0)
        val result = AxisOperations.half(start, AxisId.X).workspace
        assertFalse(result.state(AxisId.X).relative)
        assertEquals(-20000.0, result.state(AxisId.X).absoluteOffset)
        assertEquals(20000.0, start.after(result).reading(AxisId.X).shown)
    }

    @Test
    fun center_sets_L_axis_shown_over_2() {
        val profile = mill()
        val workspace = profile.workspace.axis(AxisId.X) { state -> state.copy(relative = true, incrementalOffset = -10000.0) }
        val start = frame(profile, workspace, AxisId.X to 40000.0)
        // shown = 30000 in incremental mode: L(X, 15000).
        val result = AxisOperations.center(start, AxisId.X).workspace
        assertFalse(result.state(AxisId.X).relative)
        assertEquals(15000.0, start.after(result).reading(AxisId.X).shown)
    }

    @Test
    fun hold_25_000_mm_then_move_5_mm_then_set_at_the_new_position() {
        val profile = mill()
        val workspace = profile.workspace.axis(AxisId.X) { state -> state.copy(metric = true) }
        val start = frame(profile, workspace, AxisId.X to 25000.0)
        val first = AxisOperations.holdTap(start, AxisId.X)
        assertEquals(25000.0, first.hold[AxisId.X])
        assertNull(first.undo)
        val moved = ReadoutFrame(profile, first.workspace, mapOf(AxisId.X to 30000.0), hold = first.hold)
        assertEquals("25.000", moved.reading(AxisId.X).text)
        val second = AxisOperations.holdTap(moved, AxisId.X)
        assertNull(second.hold[AxisId.X])
        val after = ReadoutFrame(profile, second.workspace, mapOf(AxisId.X to 30000.0), hold = second.hold)
        assertEquals(25000.0, after.reading(AxisId.X).shown)
        assertEquals("25.000", after.reading(AxisId.X).text)
        assertFalse(second.workspace.state(AxisId.X).relative)
    }

    @Test
    fun hold_second_tap_in_incremental_mode_sets_the_incremental_value() {
        val profile = mill()
        val workspace = profile.workspace.axis(AxisId.X) { state -> state.copy(relative = true) }
        val start = frame(profile, workspace, AxisId.X to 25000.0)
        val first = AxisOperations.holdTap(start, AxisId.X)
        val moved = ReadoutFrame(profile, first.workspace, mapOf(AxisId.X to 30000.0), hold = first.hold)
        val second = AxisOperations.holdTap(moved, AxisId.X)
        assertEquals(-5000.0, second.workspace.state(AxisId.X).incrementalOffset)
        assertEquals(0.0, second.workspace.state(AxisId.X).absoluteOffset)
    }

    @Test
    fun hold_long_press_cancels_with_no_offset_change() {
        val profile = mill()
        val start = frameOf(profile, AxisId.X to 25000.0)
        val first = AxisOperations.holdTap(start, AxisId.X)
        val cancelled = AxisOperations.holdLongPress(ReadoutFrame(profile, first.workspace, mapOf(AxisId.X to 30000.0), hold = first.hold), AxisId.X)
        assertNull(cancelled.hold[AxisId.X])
        assertNull(cancelled.undo)
        assertEquals(profile.workspace.state(AxisId.X), cancelled.workspace.state(AxisId.X))
    }

    @Test
    fun keypad_value_is_i_in_incremental_mode_and_h_in_absolute_mode() {
        val profile = mill()
        val absolute = frameOf(profile, AxisId.X to 10000.0)
        val h = AxisOperations.keypadValue(absolute, AxisId.X, 25400.0).workspace
        assertEquals(15400.0, h.state(AxisId.X).absoluteOffset)
        val incrementalWorkspace = profile.workspace.axis(AxisId.X) { state -> state.copy(relative = true) }
        val i = AxisOperations.keypadValue(frame(profile, incrementalWorkspace, AxisId.X to 10000.0), AxisId.X, 25400.0).workspace
        assertEquals(15400.0, i.state(AxisId.X).incrementalOffset)
        assertEquals(0.0, i.state(AxisId.X).absoluteOffset)
    }

    @Test
    fun plus_plus_0_5_at_1_2500_in_absolute_calls_h_44450() {
        val profile = mill()
        val start = frameOf(profile, AxisId.X to 31750.0)
        assertEquals("1.2500", start.reading(AxisId.X).text)
        val result = AxisOperations.keypadText(start, AxisId.X, "++0.5", keypadEvaluate).getOrThrow()
        val reading = start.after(result.workspace).reading(AxisId.X)
        assertEquals(44450.0, reading.absolute, 1e-6)
        assertEquals("1.7500", reading.text)
    }

    @Test
    fun minus_minus_x_gives_current_minus_x() {
        val profile = mill()
        val start = frameOf(profile, AxisId.X to 31750.0)
        val result = AxisOperations.keypadText(start, AxisId.X, "--0.25", keypadEvaluate).getOrThrow()
        assertEquals(25400.0, start.after(result.workspace).reading(AxisId.X).absolute, 1e-6)
    }

    // The zero table, short_press_zero_set = false (the default).

    @Test
    fun short_press_off_axis_tap_is_i_0() {
        val profile = mill()
        assertFalse(profile.display.shortPressZero)
        val result = ZeroButtons.tap(frameOf(profile, AxisId.X to 40000.0), AxisId.X).workspace
        assertTrue(result.state(AxisId.X).relative)
        assertEquals(-40000.0, result.state(AxisId.X).incrementalOffset)
        assertEquals(0.0, result.state(AxisId.X).absoluteOffset)
    }

    @Test
    fun short_press_off_axis_long_press_is_L_axis_0() {
        val profile = mill()
        val workspace = profile.workspace.axis(AxisId.X) { state -> state.copy(relative = true) }
        val result = ZeroButtons.longPress(frame(profile, workspace, AxisId.X to 40000.0), AxisId.X).workspace
        assertFalse(result.state(AxisId.X).relative)
        assertEquals(-40000.0, result.state(AxisId.X).absoluteOffset)
    }

    @Test
    fun short_press_off_global_tap_is_i_0_on_X_and_each_enabled_axis_then_no_sub_datum() {
        val base = mill()
        val profile = base.copy(z = base.z.copy(enabled = false))
        val point = SubDatum(name = "Point 1", x = 1000.0, y = 1000.0)
        val workspace = profile.workspace.copy(subDatums = listOf(point), activeSubDatumId = point.id)
        val start = frame(profile, workspace, AxisId.X to 40000.0, AxisId.Y to 20000.0, AxisId.Z to 7000.0, AxisId.Angular to 3600.0)
        val result = ZeroButtons.globalTap(start).workspace
        assertNull(result.activeSubDatumId)
        assertEquals(-40000.0, result.state(AxisId.X).incrementalOffset)
        assertEquals(-20000.0, result.state(AxisId.Y).incrementalOffset)
        assertTrue(result.state(AxisId.X).relative && result.state(AxisId.Y).relative)
        // Z and Angular are disabled: no change.
        assertEquals(start.state(AxisId.Z), result.state(AxisId.Z))
        assertEquals(start.state(AxisId.Angular), result.state(AxisId.Angular))
    }

    @Test
    fun short_press_off_global_long_press_is_L_axis_0_on_X_Y_Z_Angular() {
        val base = mill()
        val profile = base.copy(angular = base.angular.copy(enabled = true))
        val start = frameOf(profile, AxisId.X to 40000.0, AxisId.Y to 20000.0, AxisId.Z to 7000.0, AxisId.Angular to 3600.0)
        val result = ZeroButtons.globalLongPress(start).workspace
        val after = start.after(result)
        listOf(AxisId.X, AxisId.Y, AxisId.Z, AxisId.Angular).forEach { axis ->
            assertEquals(0.0, after.reading(axis).absolute, "axis $axis")
            assertFalse(result.state(axis).relative, "axis $axis")
        }
    }

    // The zero table, short_press_zero_set = true.

    @Test
    fun short_press_on_axis_tap_is_i_0_if_rel_else_L_axis_0() {
        val profile = shortPress(mill(), on = true)
        val absolute = ZeroButtons.tap(frameOf(profile, AxisId.X to 40000.0), AxisId.X).workspace
        assertFalse(absolute.state(AxisId.X).relative)
        assertEquals(-40000.0, absolute.state(AxisId.X).absoluteOffset)
        val incrementalWorkspace = profile.workspace.axis(AxisId.X) { state -> state.copy(relative = true) }
        val incremental = ZeroButtons.tap(frame(profile, incrementalWorkspace, AxisId.X to 40000.0), AxisId.X).workspace
        assertTrue(incremental.state(AxisId.X).relative)
        assertEquals(-40000.0, incremental.state(AxisId.X).incrementalOffset)
        assertEquals(0.0, incremental.state(AxisId.X).absoluteOffset)
    }

    @Test
    fun short_press_on_axis_long_press_has_no_action() {
        val profile = shortPress(mill(), on = true)
        val result = ZeroButtons.longPress(frameOf(profile, AxisId.X to 40000.0), AxisId.X)
        assertFalse(result.changed)
        assertEquals(0.0, result.workspace.state(AxisId.X).absoluteOffset)
    }

    @Test
    fun short_press_on_global_tap_is_i_0_if_rel_else_L_axis_0_then_no_sub_datum() {
        val profile = shortPress(mill(), on = true)
        val point = SubDatum(name = "Point 1", z = 500.0)
        val workspace = profile.workspace
            .axis(AxisId.Y) { state -> state.copy(relative = true) }
            .copy(subDatums = listOf(point), activeSubDatumId = point.id)
        val start = frame(profile, workspace, AxisId.X to 40000.0, AxisId.Y to 20000.0, AxisId.Z to 7000.0)
        val result = ZeroButtons.globalTap(start).workspace
        assertNull(result.activeSubDatumId)
        assertFalse(result.state(AxisId.X).relative)
        assertEquals(-40000.0, result.state(AxisId.X).absoluteOffset)
        assertTrue(result.state(AxisId.Y).relative)
        assertEquals(-20000.0, result.state(AxisId.Y).incrementalOffset)
        assertEquals(0.0, result.state(AxisId.Y).absoluteOffset)
        assertEquals(-7000.0, result.state(AxisId.Z).absoluteOffset)
    }

    @Test
    fun short_press_on_global_long_press_has_no_action() {
        val profile = shortPress(mill(), on = true)
        val result = ZeroButtons.globalLongPress(frameOf(profile, AxisId.X to 40000.0, AxisId.Y to 1.0, AxisId.Z to 2.0))
        assertFalse(result.changed)
    }

    // Undo.

    @Test
    fun undo_restores_all_offsets_that_the_action_changed() {
        val base = mill()
        val profile = base.copy(angular = base.angular.copy(enabled = true))
        val point = SubDatum(name = "Point 1", x = 1000.0)
        val workspace = profile.workspace
            .axis(AxisId.X) { state -> state.copy(absoluteOffset = 123.0, incrementalOffset = -7.0, relative = true) }
            .axis(AxisId.Z) { state -> state.copy(absoluteOffset = -55.0) }
            .copy(subDatums = listOf(point), activeSubDatumId = point.id)
        val start = frame(profile, workspace, AxisId.X to 40000.0, AxisId.Y to 20000.0, AxisId.Z to 7000.0, AxisId.Angular to 3600.0)
        val zeroed = ZeroButtons.globalTap(start)
        val undo = assertNotNull(zeroed.undo)
        val restored = undo.restore(zeroed.workspace)
        AxisId.entries.forEach { axis -> assertEquals(start.state(axis), restored.state(axis), "axis $axis") }
        assertEquals(point.id, restored.activeSubDatumId)
    }

    // Global buttons.

    @Test
    fun mode_tap_all_absolute_sets_rel_on_X_Y_Z_Angular_else_sets_abs() {
        val profile = mill()
        val start = frameOf(profile, AxisId.X to 1.0, AxisId.Y to 2.0, AxisId.Z to 3.0)
        val incremental = GlobalButtons.modeTap(start).workspace
        listOf(AxisId.X, AxisId.Y, AxisId.Z, AxisId.Angular).forEach { axis -> assertTrue(incremental.state(axis).relative, "axis $axis") }
        val mixed = start.after(incremental.axis(AxisId.Y) { state -> state.copy(relative = false) })
        assertEquals(GlobalModeState.Mixed, GlobalButtons.modeState(mixed))
        val absolute = GlobalButtons.modeTap(mixed).workspace
        listOf(AxisId.X, AxisId.Y, AxisId.Z, AxisId.Angular).forEach { axis -> assertFalse(absolute.state(axis).relative, "axis $axis") }
    }

    @Test
    fun mode_long_press_mixed_has_no_action() {
        val profile = mill()
        val workspace = profile.workspace
            .axis(AxisId.X) { state -> state.copy(relative = true, incrementalOffset = -5.0, absoluteOffset = 7.0) }
            .axis(AxisId.Y) { state -> state.copy(absoluteOffset = 9.0) }
        val result = GlobalButtons.modeLongPress(frame(profile, workspace, AxisId.X to 1.0, AxisId.Y to 2.0, AxisId.Z to 3.0))
        assertFalse(result.changed)
    }

    @Test
    fun mode_long_press_absolute_clears_A_with_undo() {
        val profile = mill()
        val workspace = profile.workspace
            .axis(AxisId.X) { state -> state.copy(absoluteOffset = 7.0, incrementalOffset = -5.0) }
            .axis(AxisId.Y) { state -> state.copy(absoluteOffset = 9.0) }
        val result = GlobalButtons.modeLongPress(frame(profile, workspace, AxisId.X to 1.0, AxisId.Y to 2.0, AxisId.Z to 3.0))
        assertEquals(0.0, result.workspace.state(AxisId.X).absoluteOffset)
        assertEquals(0.0, result.workspace.state(AxisId.Y).absoluteOffset)
        assertEquals(-5.0, result.workspace.state(AxisId.X).incrementalOffset)
        assertNotNull(result.undo)
    }

    @Test
    fun mode_long_press_incremental_clears_the_sub_datum_and_I_also_on_the_tachometer() {
        val base = mill()
        val profile = base.copy(tachometer = base.tachometer.copy(enabled = true))
        val point = SubDatum(name = "Point 1", x = 1.0)
        var workspace = profile.workspace.copy(subDatums = listOf(point), activeSubDatumId = point.id)
        listOf(AxisId.X, AxisId.Y, AxisId.Z, AxisId.Tachometer).forEach { axis ->
            workspace = workspace.axis(axis) { state -> state.copy(relative = true, incrementalOffset = -5.0, absoluteOffset = 3.0) }
        }
        val result = GlobalButtons.modeLongPress(frame(profile, workspace, AxisId.X to 1.0, AxisId.Y to 2.0, AxisId.Z to 3.0, AxisId.Tachometer to 0.0))
        assertNull(result.workspace.activeSubDatumId)
        listOf(AxisId.X, AxisId.Y, AxisId.Z, AxisId.Tachometer).forEach { axis ->
            assertEquals(0.0, result.workspace.state(axis).incrementalOffset, "axis $axis")
            assertEquals(3.0, result.workspace.state(axis).absoluteOffset, "axis $axis")
        }
        assertNotNull(result.undo)
    }

    @Test
    fun units_tap_all_inch_change_to_mm_else_all_change_to_inch() {
        val profile = mill()
        val start = frameOf(profile, AxisId.X to 1.0, AxisId.Y to 2.0, AxisId.Z to 3.0)
        assertEquals(GlobalUnitsState.Inch, GlobalButtons.unitsState(start))
        val metric = GlobalButtons.unitsTap(start).workspace
        listOf(AxisId.X, AxisId.Y, AxisId.Z).forEach { axis -> assertTrue(start.after(metric).metric(axis), "axis $axis") }
        val mixed = start.after(metric.axis(AxisId.Y) { state -> state.copy(metric = false) })
        assertEquals(GlobalUnitsState.Mixed, GlobalButtons.unitsState(mixed))
        val inch = GlobalButtons.unitsTap(mixed).workspace
        listOf(AxisId.X, AxisId.Y, AxisId.Z).forEach { axis -> assertFalse(start.after(inch).metric(axis), "axis $axis") }
    }

    @Test
    fun units_count_stops_at_the_first_disabled_axis_from_the_top() {
        val base = mill()
        val profile = base.copy(y = base.y.copy(enabled = false))
        // X inch, Y disabled, Z mm: the count sees only X, which shows inch. Thus all change to mm.
        val workspace = profile.workspace.axis(AxisId.Z) { state -> state.copy(metric = true) }
        val start = frame(profile, workspace, AxisId.X to 1.0, AxisId.Z to 3.0)
        val result = GlobalButtons.unitsTap(start).workspace
        listOf(AxisId.X, AxisId.Y, AxisId.Z).forEach { axis -> assertTrue(start.after(result).metric(axis), "axis $axis") }
    }

    // Action buttons.

    @Test
    fun default_action_buttons_are_modes_0_1_2() {
        assertEquals(
            listOf(AxisButtonMode.SetZero, AxisButtonMode.ToggleIncremental, AxisButtonMode.ToggleUnits),
            mill().x.buttons.map { button -> button.mode },
        )
        assertEquals(listOf(0, 1, 2, 3, 4, 5), AxisButtonMode.entries.map { mode -> mode.ordinal })
    }

    @Test
    fun mode_4_is_offered_only_on_the_angular_axis() {
        assertFalse(AxisButtonMode.DivideCircle in ActionButtons.offeredModes(AxisId.X))
        assertTrue(AxisButtonMode.DivideCircle in ActionButtons.offeredModes(AxisId.Angular))
        val start = frameOf(mill(), AxisId.Angular to 0.0)
        assertEquals(OperationRequest.OpenDivisionsSheet, ActionButtons.tap(start, AxisId.Angular, AxisButtonMode.DivideCircle).request)
    }

    @Test
    fun show_expanded_and_show_collapsed_control_the_visibility() {
        val buttons = listOf(
            AxisButtonConfig(AxisButtonMode.SetZero, showExpanded = true, showCollapsed = true),
            AxisButtonConfig(AxisButtonMode.ToggleIncremental, showExpanded = false, showCollapsed = false),
            AxisButtonConfig(AxisButtonMode.ToggleUnits, showExpanded = true, showCollapsed = false),
        )
        assertEquals(
            listOf(AxisButtonMode.SetZero, AxisButtonMode.ToggleUnits),
            ActionButtons.visibleButtons(AxisId.X, buttons, collapsed = false).map { button -> button.mode },
        )
        assertEquals(listOf(AxisButtonMode.SetZero), ActionButtons.visibleButtons(AxisId.X, buttons, collapsed = true).map { button -> button.mode })
    }

    @Test
    fun mode_1_tap_toggles_and_long_press_clears_A_in_abs_and_I_in_inc() {
        val profile = mill()
        val workspace = profile.workspace.axis(AxisId.X) { state -> state.copy(absoluteOffset = 7.0, incrementalOffset = -5.0) }
        val absolute = frame(profile, workspace, AxisId.X to 1.0)
        assertTrue(ActionButtons.tap(absolute, AxisId.X, AxisButtonMode.ToggleIncremental).workspace.state(AxisId.X).relative)
        val clearA = ActionButtons.longPress(absolute, AxisId.X, AxisButtonMode.ToggleIncremental).workspace.state(AxisId.X)
        assertEquals(0.0, clearA.absoluteOffset)
        assertEquals(-5.0, clearA.incrementalOffset)
        val incremental = absolute.after(workspace.axis(AxisId.X) { state -> state.copy(relative = true) })
        val clearI = ActionButtons.longPress(incremental, AxisId.X, AxisButtonMode.ToggleIncremental).workspace.state(AxisId.X)
        assertEquals(7.0, clearI.absoluteOffset)
        assertEquals(0.0, clearI.incrementalOffset)
    }

    @Test
    fun mode_2_toggles_inch_and_mm_and_degrees_and_divisions() {
        val profile = mill()
        val start = frameOf(profile, AxisId.X to 1.0)
        assertTrue(start.after(ActionButtons.tap(start, AxisId.X, AxisButtonMode.ToggleUnits).workspace).metric(AxisId.X))
        val angularWorkspace = profile.workspace.axis(AxisId.Angular) { state -> state.copy(divisionsPerRev = 24) }
        val angular = frame(profile, angularWorkspace, AxisId.Angular to 0.0)
        assertTrue(ActionButtons.tap(angular, AxisId.Angular, AxisButtonMode.ToggleUnits).workspace.state(AxisId.Angular).divisionUnits)
    }

    @Test
    fun mode_3_is_half_and_mode_5_is_hold() {
        val profile = mill()
        val start = frameOf(profile, AxisId.X to 40000.0)
        assertEquals(20000.0, start.after(ActionButtons.tap(start, AxisId.X, AxisButtonMode.SetCenter).workspace).reading(AxisId.X).shown)
        assertEquals(40000.0, ActionButtons.tap(start, AxisId.X, AxisButtonMode.Hold).hold[AxisId.X])
    }
}
