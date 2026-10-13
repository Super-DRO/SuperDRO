package org.superdro.validation.readout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.model.AxisAlignment
import org.superdro.core.model.AxisAlignmentSource
import org.superdro.core.model.AxisId
import org.superdro.core.model.MachineMode
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.ProbeType
import org.superdro.core.model.Workspace
import org.superdro.core.readout.PlaneRotation
import org.superdro.core.readout.ReadoutFrame

/**
 * The effects of the measurement mode on the readout. The mode
 * suspends the X, Y, and Z tool offsets, the reference instrument offsets add to X and Y,
 * and the X/Y alignment turns the readouts.
 */
class MeasurementReadoutValidationTest {

    private fun measurement(profile: MachineProfile): MachineProfile = profile.copy(machineMode = MachineMode.Measurement)

    private fun withTools(workspace: Workspace): Workspace {
        var result = workspace
        listOf(AxisId.X, AxisId.Y, AxisId.Z).forEach { axis ->
            result = result.axis(axis) { state -> state.copy(toolOffset = 500.0, toolOffsetActive = true) }
        }
        return result
    }

    private val probe = ProbeProfile(type = ProbeType.TouchProbe, name = "Probe", port = 'p', xOffsetUm = 1000.0, yOffsetUm = -500.0)

    @Test
    fun measurement_mode_suspends_the_X_Y_Z_tool_offsets() {
        val profile = measurement(mill())
        val frame = frame(profile, withTools(profile.workspace), AxisId.X to 10000.0, AxisId.Y to 20000.0, AxisId.Z to 30000.0)
        assertTrue(frame.measurementActive)
        assertEquals(10000.0, frame.reading(AxisId.X).absolute)
        assertEquals(20000.0, frame.reading(AxisId.Y).absolute)
        assertEquals(30000.0, frame.reading(AxisId.Z).absolute)
        // The values stay stored.
        assertEquals(500.0, frame.state(AxisId.X).toolOffset)
    }

    @Test
    fun cutting_mode_uses_the_tool_offsets() {
        val profile = mill()
        val frame = frame(profile, withTools(profile.workspace), AxisId.X to 10000.0, AxisId.Y to 20000.0, AxisId.Z to 30000.0)
        assertFalse(frame.measurementActive)
        assertEquals(10500.0, frame.reading(AxisId.X).absolute)
        assertEquals(30500.0, frame.reading(AxisId.Z).absolute)
    }

    @Test
    fun measurement_mode_is_not_on_a_lathe() {
        val profile = measurement(lathe())
        val frame = frame(profile, withTools(profile.workspace), AxisId.X to 10000.0, AxisId.Z to 30000.0)
        assertFalse(frame.measurementActive)
        assertEquals(30500.0, frame.reading(AxisId.Z).absolute)
    }

    @Test
    fun instrument_X_and_Y_offsets_add_to_the_X_and_Y_readouts() {
        val profile = measurement(mill())
        val frame = ReadoutFrame(
            profile,
            profile.workspace,
            mapOf(AxisId.X to 10000.0, AxisId.Y to 20000.0, AxisId.Z to 30000.0),
            instrument = probe,
        )
        assertEquals(11000.0, frame.reading(AxisId.X).absolute)
        assertEquals(19500.0, frame.reading(AxisId.Y).absolute)
        assertEquals(30000.0, frame.reading(AxisId.Z).absolute)
        assertTrue(frame.reading(AxisId.X).modified)
    }

    @Test
    fun no_instrument_offset_in_the_cutting_mode() {
        val profile = mill()
        val frame = ReadoutFrame(profile, profile.workspace, mapOf(AxisId.X to 10000.0, AxisId.Y to 20000.0), instrument = probe)
        assertNull(frame.instrumentInUse)
        assertEquals(10000.0, frame.reading(AxisId.X).absolute)
    }

    @Test
    fun instrument_is_a_touch_probe_a_2D_edge_finder_or_a_camera() {
        assertTrue(ReadoutFrame.validInstrument(probe))
        assertTrue(ReadoutFrame.validInstrument(probe.copy(type = ProbeType.EdgeFinder2D)))
        assertTrue(ReadoutFrame.validInstrument(probe.copy(type = ProbeType.Camera, port = null)))
        assertFalse(ReadoutFrame.validInstrument(probe.copy(type = ProbeType.ToolHeightSetter)))
        val profile = measurement(mill())
        val setter = ReadoutFrame(profile, profile.workspace, mapOf(AxisId.X to 10000.0), instrument = probe.copy(type = ProbeType.ToolHeightSetter))
        assertEquals(10000.0, setter.reading(AxisId.X).absolute)
    }

    @Test
    fun alignment_2_degrees_absX_100000_gives_99939_and_minus_3490() {
        val rotation = PlaneRotation(2.0 * 3600.0)
        val turned = rotation.forward(100000.0, 0.0)
        assertEquals(99939.0, turned.x, 0.5)
        assertEquals(-3490.0, turned.y, 0.5)
        val back = rotation.inverse(turned.x, turned.y)
        assertEquals(100000.0, back.x, 1e-6)
        assertEquals(0.0, back.y, 1e-6)
    }

    @Test
    fun alignment_turns_the_X_and_Y_readouts_only_in_the_measurement_mode() {
        val alignment = AxisAlignment(active = true, angleArcSec = 2.0 * 3600.0, source = AxisAlignmentSource.Typed)
        val measuring = measurement(mill())
        val on = frame(measuring, measuring.workspace.copy(axisAlignment = alignment), AxisId.X to 100000.0, AxisId.Y to 0.0)
        assertEquals(99939.0, on.reading(AxisId.X).absolute, 0.5)
        assertEquals(-3490.0, on.reading(AxisId.Y).absolute, 0.5)
        val cutting = mill()
        val off = frame(cutting, cutting.workspace.copy(axisAlignment = alignment), AxisId.X to 100000.0, AxisId.Y to 0.0)
        assertEquals(100000.0, off.reading(AxisId.X).absolute)
        assertEquals(0.0, off.reading(AxisId.Y).absolute)
    }
}
