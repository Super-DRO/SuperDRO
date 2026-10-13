package org.superdro.validation.readout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.superdro.core.model.AxisId
import org.superdro.core.model.MachineDatum
import org.superdro.core.model.MachineMode
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.ProbeType
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.readout.operations.MachineDatumPlan
import org.superdro.core.readout.operations.MachineDatums

/**
 * The machine position and "Set Absolute Origin to Machine Datum", with a worked example.
 */
class MachineDatumValidationTest {

    /** Machine position (160, 80, 0) mm and absolute position (10, 5, 20) mm. */
    private fun exampleFrame(): ReadoutFrame {
        val profile = mill()
        val workspace = profile.workspace
            .axis(AxisId.X) { state -> state.copy(absoluteOffset = -150000.0) }
            .axis(AxisId.Y) { state -> state.copy(absoluteOffset = -75000.0) }
            .axis(AxisId.Z) { state -> state.copy(absoluteOffset = 20000.0) }
        return frame(profile, workspace, AxisId.X to 160000.0, AxisId.Y to 80000.0, AxisId.Z to 0.0)
    }

    private val datum = MachineDatum(name = "Datum 1", x = 152400.0, y = 76200.0, z = -12700.0)

    @Test
    fun datum_152_4_76_2_minus_12_7_at_160_80_0_gives_absolute_7_6_3_8_12_7() {
        val start = exampleFrame()
        assertEquals(10000.0, start.reading(AxisId.X).absolute)
        val plan = assertIs<MachineDatumPlan.Apply>(MachineDatums.plan(start, datum))
        val after = start.with(plan.result.workspace)
        assertEquals(7600.0, after.reading(AxisId.X).absolute, 1e-9)
        assertEquals(3800.0, after.reading(AxisId.Y).absolute, 1e-9)
        assertEquals(12700.0, after.reading(AxisId.Z).absolute, 1e-9)
        assertFalse(plan.noOp)
        assertNotNull(plan.result.undo)
    }

    @Test
    fun shift_list_is_the_new_minus_the_current_absolute_value() {
        val plan = assertIs<MachineDatumPlan.Apply>(MachineDatums.plan(exampleFrame(), datum))
        assertEquals(-2400.0, plan.shifts.getValue(AxisId.X), 1e-9)
        assertEquals(-1200.0, plan.shifts.getValue(AxisId.Y), 1e-9)
        assertEquals(-7300.0, plan.shifts.getValue(AxisId.Z), 1e-9)
    }

    @Test
    fun after_the_apply_the_datum_position_reads_0() {
        val start = exampleFrame()
        val plan = assertIs<MachineDatumPlan.Apply>(MachineDatums.plan(start, datum))
        val atDatum = ReadoutFrame(start.profile, plan.result.workspace, mapOf(AxisId.X to 152400.0, AxisId.Y to 76200.0, AxisId.Z to -12700.0))
        listOf(AxisId.X, AxisId.Y, AxisId.Z).forEach { axis -> assertEquals(0.0, atDatum.reading(axis).absolute, 1e-9, "axis $axis") }
    }

    @Test
    fun axis_that_the_datum_does_not_store_keeps_its_absolute_value() {
        val start = exampleFrame()
        val plan = assertIs<MachineDatumPlan.Apply>(MachineDatums.plan(start, MachineDatum(name = "X only", x = 152400.0)))
        val after = start.with(plan.result.workspace)
        assertEquals(7600.0, after.reading(AxisId.X).absolute, 1e-9)
        assertEquals(5000.0, after.reading(AxisId.Y).absolute, 1e-9)
        assertEquals(20000.0, after.reading(AxisId.Z).absolute, 1e-9)
        assertEquals(setOf(AxisId.X), plan.shifts.keys)
    }

    @Test
    fun no_op_when_each_linear_shift_is_less_than_0_5_um() {
        val start = exampleFrame()
        val near = MachineDatum(name = "Near", x = 150000.4, y = 74999.6, z = -20000.0)
        assertTrue(assertIs<MachineDatumPlan.Apply>(MachineDatums.plan(start, near)).noOp)
        val far = near.copy(x = 150000.6)
        assertFalse(assertIs<MachineDatumPlan.Apply>(MachineDatums.plan(start, far)).noOp)
    }

    @Test
    fun stored_axis_with_no_position_gives_position_not_available() {
        val profile = mill()
        val start = frameOf(profile, AxisId.X to 1000.0)
        val plan = assertIs<MachineDatumPlan.PositionNotAvailable>(MachineDatums.plan(start, datum))
        assertEquals(setOf(AxisId.Y, AxisId.Z), plan.axes)
    }

    @Test
    fun machine_position_has_no_tool_offset_and_no_instrument_offset() {
        val base = mill().copy(machineMode = MachineMode.Cutting)
        val tool = base.workspace
            .axis(AxisId.X) { state -> state.copy(toolOffset = 500.0, toolOffsetActive = true, absoluteOffset = 3000.0) }
        val cutting = frame(base, tool, AxisId.X to 152400.0, AxisId.Y to 76200.0, AxisId.Z to -12700.0)
        assertEquals(152400.0, MachineDatums.machinePosition(cutting, AxisId.X))
        val measuring = base.copy(machineMode = MachineMode.Measurement)
        val instrument = ProbeProfile(type = ProbeType.TouchProbe, name = "Probe", port = 'p', xOffsetUm = 1000.0, yOffsetUm = 1000.0)
        val withInstrument = ReadoutFrame(
            measuring,
            measuring.workspace,
            mapOf(AxisId.X to 152400.0, AxisId.Y to 76200.0, AxisId.Z to -12700.0),
            instrument = instrument,
        )
        val captured = assertNotNull(MachineDatums.capture(withInstrument, "Datum 1"))
        assertEquals(152400.0, captured.x)
        assertEquals(76200.0, captured.y)
        assertEquals(-12700.0, captured.z)
    }

    @Test
    fun A_is_stored_when_the_angular_axis_is_on() {
        val off = frameOf(mill(), AxisId.X to 1.0, AxisId.Y to 2.0, AxisId.Z to 3.0, AxisId.Angular to 3600.0)
        assertEquals(null, MachineDatums.capture(off, "Datum")?.a)
        val profile = mill().let { base -> base.copy(angular = base.angular.copy(enabled = true)) }
        val on = frameOf(profile, AxisId.X to 1.0, AxisId.Y to 2.0, AxisId.Z to 3.0, AxisId.Angular to 3600.0)
        assertEquals(3600.0, MachineDatums.capture(on, "Datum")?.a)
    }

    @Test
    fun angular_no_op_limit_is_0_5_arcsec() {
        val profile = mill().let { base -> base.copy(angular = base.angular.copy(enabled = true)) }
        val workspace = profile.workspace.axis(AxisId.Angular) { state -> state.copy(absoluteOffset = -3600.0) }
        val start = frame(profile, workspace, AxisId.Angular to 7200.0)
        val near = MachineDatum(name = "A", a = 3600.4)
        assertTrue(assertIs<MachineDatumPlan.Apply>(MachineDatums.plan(start, near)).noOp)
        val far = MachineDatum(name = "A", a = 3600.6)
        assertFalse(assertIs<MachineDatumPlan.Apply>(MachineDatums.plan(start, far)).noOp)
    }
}
