package org.superdro.validation.functions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.functions.compensation.CompensationResult
import org.superdro.core.functions.compensation.ToolCompensation
import org.superdro.core.functions.compensation.ToolPosition
import org.superdro.core.functions.measure.CaliperMeasure
import org.superdro.core.functions.measure.MeasureDistanceAngle
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.MachineType
import org.superdro.core.model.Tool
import org.superdro.core.model.ToolKind
import org.superdro.core.model.Workspace
import org.superdro.validation.functions.Expect.DEG
import org.superdro.validation.functions.Expect.DEG2
import org.superdro.validation.functions.Expect.MM

/**
 * The measure tools and the tool compensation.
 */
class MeasureAndCompensationValidationTest {

    @Test
    fun distance_and_angle_of_30_40() {
        val m = MeasureDistanceAngle.measure(30 * MM, 40 * MM)
        Expect.mm(50.0, m.distanceUm, "d")
        Expect.near(53.13, m.lineAngleArcSec / DEG, DEG2, "alpha")
        Expect.near(36.87, m.complementArcSec / DEG, DEG2, "theta")
    }

    @Test
    fun line_angle_and_direction_of_minus_30_40() {
        val m = MeasureDistanceAngle.measure(-30 * MM, 40 * MM)
        Expect.near(-53.13, m.lineAngleArcSec / DEG, DEG2, "line angle")
        Expect.near(126.87, m.directionArcSec / DEG, DEG2, "direction")
    }

    @Test
    fun the_special_cases_of_alpha_and_theta() {
        val origin = MeasureDistanceAngle.measure(0.0, 0.0)
        assertEquals(0.0, origin.lineAngleArcSec)
        assertEquals(0.0, origin.complementArcSec)
        val onB = MeasureDistanceAngle.measure(0.0, 5 * MM)
        assertEquals(90.0, onB.lineAngleArcSec / DEG, 1e-9)
        assertEquals(0.0, onB.complementArcSec / DEG, 1e-9)
        val onA = MeasureDistanceAngle.measure(5 * MM, 0.0)
        assertEquals(0.0, onA.lineAngleArcSec / DEG, 1e-9)
        assertEquals(90.0, onA.complementArcSec / DEG, 1e-9)
        // The direction is 0° to 360°: a point below the A axis gives more than 180°.
        Expect.near(306.87, MeasureDistanceAngle.measure(30 * MM, -40 * MM).directionArcSec / DEG, DEG2, "direction")
    }

    @Test
    fun axes_are_x_y_on_a_mill_and_z_x_on_a_lathe() {
        assertEquals(AxisId.X to AxisId.Y, MeasureDistanceAngle.axes(MachineType.VerticalMill))
        assertEquals(AxisId.Z to AxisId.X, MeasureDistanceAngle.axes(MachineType.Lathe))
    }

    @Test
    fun caliper_copy_25_mm_to_12_5_mm_in_radius_mode() {
        val copy = CaliperMeasure.copyToX(25 * MM, xDiameterMode = false, xIncremental = false)
        Expect.mm(12.5, copy.valueUm, "X value")
        assertFalse(copy.incremental, "X absolute: h(value)")
        val diameter = CaliperMeasure.copyToX(25 * MM, xDiameterMode = true, xIncremental = true)
        Expect.mm(25.0, diameter.valueUm, "X value in diameter mode")
        assertTrue(diameter.incremental, "X incremental: i(value)")
        assertTrue(CaliperMeasure.buttonVisible(showDimensionMeasure = true, caliperEnabled = true))
        assertFalse(CaliperMeasure.buttonVisible(showDimensionMeasure = true, caliperEnabled = false))
    }

    private fun applied(result: CompensationResult): Workspace = assertIs<CompensationResult.Applied>(result).workspace

    private fun Workspace.tool(axis: AxisId): AxisState = axisStates.getValue(axis)

    @Test
    fun radius_compensation_D_10_mm_back_left() {
        val w = applied(ToolCompensation.radius(Workspace(), ToolPosition.BackLeft, 10 * MM))
        assertEquals(5000.0, w.tool(AxisId.X).toolOffset)
        assertTrue(w.tool(AxisId.X).toolOffsetActive)
        assertEquals(-5000.0, w.tool(AxisId.Y).toolOffset)
        assertTrue(w.tool(AxisId.Y).toolOffsetActive)
        assertEquals(10 * MM, w.toolXUm, "the workspace stores D")
    }

    @Test
    fun direction_table() {
        val expected = mapOf(
            ToolPosition.BackLeft to (1 to -1), ToolPosition.BackCenter to (0 to -1), ToolPosition.BackRight to (-1 to -1),
            ToolPosition.MiddleLeft to (1 to 0), ToolPosition.Center to (0 to 0), ToolPosition.MiddleRight to (-1 to 0),
            ToolPosition.FrontLeft to (1 to 1), ToolPosition.FrontCenter to (0 to 1), ToolPosition.FrontRight to (-1 to 1),
        )
        expected.forEach { (position, direction) -> assertEquals(direction, position.dirX to position.dirY, position.name) }
        // A direction of 0 clears the offset of that axis.
        val w = applied(ToolCompensation.radius(Workspace(), ToolPosition.FrontCenter, 8 * MM))
        assertFalse(w.tool(AxisId.X).toolOffsetActive)
        assertEquals(4000.0, w.tool(AxisId.Y).toolOffset)
        assertEquals(CompensationResult.NoChange, ToolCompensation.radius(Workspace(), ToolPosition.Center, 0.0))
    }

    @Test
    fun custom_diameter_clears_the_tool_and_a_new_library_tool_clears_the_height_compensation() {
        val start = Workspace(activeToolId = "old")
        val custom = applied(ToolCompensation.radius(start, ToolPosition.BackLeft, 10 * MM))
        assertNull(custom.activeToolId, "a custom diameter clears the active tool")
        val withHeight = applied(ToolCompensation.height(Workspace(), -12.5 * MM)).copy(activeToolId = "t1")
        val mill = Tool(id = "t2", kind = ToolKind.Mill, diameterUm = 6 * MM, toothCount = 2)
        val radius = applied(ToolCompensation.radius(withHeight, ToolPosition.MiddleLeft, null, mill))
        assertFalse(radius.tool(AxisId.Z).toolOffsetActive, "the height compensation is cleared first")
        assertEquals(3000.0, radius.tool(AxisId.X).toolOffset)
    }

    @Test
    fun height_compensation_minus_12_5_mm_and_the_zero_error() {
        val w = applied(ToolCompensation.height(Workspace(), -12.5 * MM))
        assertEquals(-12500.0, w.tool(AxisId.Z).toolOffset)
        assertTrue(w.tool(AxisId.Z).toolOffsetActive)
        val zero = assertIs<CompensationResult.Failure>(ToolCompensation.height(Workspace(), 0.0))
        assertEquals("Z offset can't be 0", zero.message.text)
        // A library tool that is not the active tool first clears the radius compensation.
        val withRadius = applied(ToolCompensation.radius(Workspace(), ToolPosition.BackLeft, 10 * MM))
        val tool = Tool(id = "z1", kind = ToolKind.Mill, zOffsetUm = -20 * MM)
        val height = applied(ToolCompensation.height(withRadius, null, tool))
        assertFalse(height.tool(AxisId.X).toolOffsetActive)
        assertFalse(height.tool(AxisId.Y).toolOffsetActive)
        assertEquals(-20000.0, height.tool(AxisId.Z).toolOffset)
    }

    @Test
    fun lathe_tool_offset_x_0_5_mm_z_minus_1_2_mm() {
        val w = applied(ToolCompensation.latheOffset(Workspace.forMachine(MachineType.Lathe), 0.5 * MM, -1.2 * MM))
        assertEquals(500.0, w.tool(AxisId.X).toolOffset)
        assertEquals(-1200.0, w.tool(AxisId.Z).toolOffset)
    }

    @Test
    fun no_offset_on_either_axis_gives_the_message_and_a_sum_of_0_is_valid() {
        val error = assertIs<CompensationResult.Failure>(ToolCompensation.latheOffset(Workspace(), 0.0, 0.0))
        assertEquals("Please set an offset on at least one axis", error.message.text)
        // The lathe tool offset: X = 0.5 mm and Z = −0.5 mm is a valid offset.
        val w = applied(ToolCompensation.latheOffset(Workspace(), 0.5 * MM, -0.5 * MM))
        assertEquals(500.0, w.tool(AxisId.X).toolOffset)
        assertEquals(-500.0, w.tool(AxisId.Z).toolOffset)
    }

    @Test
    fun select_tool_keeps_the_absolute_position() {
        val states = Workspace.forMachine(MachineType.Lathe).axisStates.toMutableMap()
        states[AxisId.X] = states.getValue(AxisId.X).copy(absoluteOffset = 1000.0, toolOffset = 200.0, toolOffsetActive = true)
        states[AxisId.Z] = states.getValue(AxisId.Z).copy(absoluteOffset = -3000.0, toolOffset = -100.0, toolOffsetActive = true)
        val before = Workspace.forMachine(MachineType.Lathe).copy(axisStates = states)
        val tool = Tool(id = "lt", kind = ToolKind.Lathe, xOffsetUm = 700.0, zOffsetUm = -300.0)
        val after = ToolCompensation.selectTool(before, tool)
        // abs = R + (T if active) + A. R is the same before and after.
        fun abs(w: Workspace, axis: AxisId, raw: Double): Double {
            val s = w.axisStates.getValue(axis)
            return raw + (if (s.toolOffsetActive) s.toolOffset else 0.0) + s.absoluteOffset
        }
        for (raw in listOf(0.0, 12345.0, -54321.0)) {
            assertEquals(abs(before, AxisId.X, raw), abs(after, AxisId.X, raw), 1e-9, "X at R = $raw")
            assertEquals(abs(before, AxisId.Z, raw), abs(after, AxisId.Z, raw), 1e-9, "Z at R = $raw")
        }
        assertEquals(700.0, after.tool(AxisId.X).toolOffset)
        assertEquals(-300.0, after.tool(AxisId.Z).toolOffset)
    }

    @Test
    fun active_state_of_the_buttons() {
        assertFalse(ToolCompensation.radiusActive(Workspace()))
        assertTrue(ToolCompensation.radiusActive(Workspace(toolXUm = 1.0)), "a stored diameter more than 0")
        val radius = applied(ToolCompensation.radius(Workspace(), ToolPosition.MiddleRight, 10 * MM))
        assertTrue(ToolCompensation.radiusActive(radius))
        assertFalse(ToolCompensation.heightActive(radius))
        val height = applied(ToolCompensation.height(Workspace(), -1 * MM))
        assertTrue(ToolCompensation.heightActive(height))
        val lathe = applied(ToolCompensation.latheOffset(Workspace(), null, -1 * MM))
        assertTrue(ToolCompensation.latheOffsetActive(lathe))
        assertFalse(ToolCompensation.latheOffsetActive(Workspace()))
        // A long press clears at once (SuperDRO gives an undo).
        assertFalse(ToolCompensation.radiusActive(ToolCompensation.clearRadius(radius)))
        assertFalse(ToolCompensation.heightActive(ToolCompensation.clearHeight(height)))
        assertFalse(ToolCompensation.latheOffsetActive(ToolCompensation.clearLatheOffset(lathe)))
    }
}
