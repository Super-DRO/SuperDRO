package org.superdro.core.functions.compensation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.functions.TestValues.mm
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.MachineType
import org.superdro.core.model.Tool
import org.superdro.core.model.ToolKind
import org.superdro.core.model.Workspace

/**
 * The tool compensation and the lathe tool offset.
 */
class ToolCompensationTest {

    private val mill = Workspace.forMachine(MachineType.VerticalMill)
    private val lathe = Workspace.forMachine(MachineType.Lathe)

    private fun Workspace.state(axis: AxisId): AxisState = axisStates.getValue(axis)

    private fun applied(result: CompensationResult): Workspace = assertIs<CompensationResult.Applied>(result).workspace

    /** `abs = R + (T if T.active) + A`. */
    private fun Workspace.abs(axis: AxisId, rawUm: Double): Double {
        val state = state(axis)
        return rawUm + (if (state.toolOffsetActive) state.toolOffset else 0.0) + state.absoluteOffset
    }

    @Test
    fun aDiameterOf10MmBackLeftGivesXPlus5000AndYMinus5000() {
        val workspace = applied(ToolCompensation.radius(mill, ToolPosition.BackLeft, mm(10.0)))
        assertEquals(5000.0, workspace.state(AxisId.X).toolOffset)
        assertTrue(workspace.state(AxisId.X).toolOffsetActive)
        assertEquals(-5000.0, workspace.state(AxisId.Y).toolOffset)
        assertTrue(workspace.state(AxisId.Y).toolOffsetActive)
        assertEquals(10000.0, workspace.toolXUm)
        assertEquals(1, workspace.toothCount)
        assertNull(workspace.activeToolId)
    }

    @Test
    fun theNineDirectionsOfTheTable() {
        val expected = mapOf(
            ToolPosition.BackLeft to (1 to -1), ToolPosition.BackCenter to (0 to -1), ToolPosition.BackRight to (-1 to -1),
            ToolPosition.MiddleLeft to (1 to 0), ToolPosition.Center to (0 to 0), ToolPosition.MiddleRight to (-1 to 0),
            ToolPosition.FrontLeft to (1 to 1), ToolPosition.FrontCenter to (0 to 1), ToolPosition.FrontRight to (-1 to 1),
        )
        expected.forEach { (position, direction) ->
            assertEquals(direction, position.dirX to position.dirY, "$position")
            val workspace = applied(ToolCompensation.radius(mill, position, mm(4.0)))
            assertEquals(direction.first * 2000.0, workspace.state(AxisId.X).toolOffset, "$position X")
            assertEquals(direction.first != 0, workspace.state(AxisId.X).toolOffsetActive, "$position X active")
            assertEquals(direction.second * 2000.0, workspace.state(AxisId.Y).toolOffset, "$position Y")
            assertEquals(direction.second != 0, workspace.state(AxisId.Y).toolOffsetActive, "$position Y active")
        }
    }

    @Test
    fun noDirectionAndNoDiameterChangesNothingAndAnEmptyDiameterIsRefused() {
        assertEquals(CompensationResult.NoChange, ToolCompensation.radius(mill, ToolPosition.Center, 0.0))
        val failure = assertIs<CompensationResult.Failure>(ToolCompensation.radius(mill, ToolPosition.BackLeft, null))
        assertEquals("Please enter a valid tool diameter", failure.message.text)
    }

    @Test
    fun aLibraryToolThatIsNotActiveFirstClearsTheHeightCompensation() {
        val endMill = Tool(id = "t1", kind = ToolKind.Mill, diameterUm = mm(6.0), toothCount = 4, zOffsetUm = mm(-20.0))
        val withHeight = applied(ToolCompensation.height(mill, mm(-12.5)))
        val workspace = applied(ToolCompensation.radius(withHeight, ToolPosition.FrontRight, null, endMill))
        assertEquals(-3000.0, workspace.state(AxisId.X).toolOffset)
        assertEquals(3000.0, workspace.state(AxisId.Y).toolOffset)
        assertFalse(workspace.state(AxisId.Z).toolOffsetActive)
        assertEquals(4, workspace.toothCount)
        assertEquals("t1", workspace.activeToolId)
        // The same tool for the height keeps the radius compensation.
        val both = applied(ToolCompensation.height(workspace, null, endMill))
        assertEquals(-20000.0, both.state(AxisId.Z).toolOffset)
        assertEquals(-3000.0, both.state(AxisId.X).toolOffset)
        // A different tool for the height clears the radius compensation first.
        val other = Tool(id = "t2", zOffsetUm = mm(-5.0))
        val changed = applied(ToolCompensation.height(both, null, other))
        assertFalse(changed.state(AxisId.X).toolOffsetActive)
        assertEquals(0.0, changed.toolXUm)
        assertEquals("t2", changed.activeToolId)
    }

    @Test
    fun aZOffsetOfMinus12500GivesAZToolOffsetOfMinus12500() {
        val workspace = applied(ToolCompensation.height(mill.copy(activeToolId = "x"), mm(-12.5)))
        assertEquals(-12500.0, workspace.state(AxisId.Z).toolOffset)
        assertTrue(workspace.state(AxisId.Z).toolOffsetActive)
        assertEquals(-12500.0, workspace.toolZUm)
        assertNull(workspace.activeToolId)
        assertEquals(mm(100.0) - 12500.0, workspace.abs(AxisId.Z, mm(100.0)))
    }

    @Test
    fun aCustomZOffsetOf0IsRefused() {
        assertEquals("Z offset can't be 0", assertIs<CompensationResult.Failure>(ToolCompensation.height(mill, 0.0)).message.text)
        assertEquals("Z offset can't be 0", assertIs<CompensationResult.Failure>(ToolCompensation.height(mill, null)).message.text)
        assertEquals(
            "Please enter a valid Z offset",
            assertIs<CompensationResult.Failure>(ToolCompensation.height(mill, Double.NaN)).message.text,
        )
    }

    @Test
    fun aLatheOffsetOfX05AndZMinus12() {
        val workspace = applied(ToolCompensation.latheOffset(lathe, mm(0.5), mm(-1.2)))
        assertEquals(500.0, workspace.state(AxisId.X).toolOffset)
        assertEquals(-1200.0, workspace.state(AxisId.Z).toolOffset)
        assertTrue(workspace.state(AxisId.X).toolOffsetActive)
        assertTrue(workspace.state(AxisId.Z).toolOffsetActive)
        val onlyX = applied(ToolCompensation.latheOffset(workspace, mm(0.5), 0.0))
        assertFalse(onlyX.state(AxisId.Z).toolOffsetActive)
    }

    @Test
    fun noOffsetOnBothAxesIsRefusedWithNoLibraryTool() {
        val failure = assertIs<CompensationResult.Failure>(ToolCompensation.latheOffset(lathe, 0.0, null))
        assertEquals("Please set an offset on at least one axis", failure.message.text)
        val tool = Tool(id = "lt", kind = ToolKind.Lathe)
        assertIs<CompensationResult.Applied>(ToolCompensation.latheOffset(lathe, null, null, tool))
    }

    @Test
    fun selectToolKeepsTheAbsolutePosition() {
        val start = applied(ToolCompensation.latheOffset(lathe, mm(0.5), mm(-1.2)))
            .let { workspace ->
                workspace.copy(
                    axisStates = workspace.axisStates +
                        (AxisId.X to workspace.state(AxisId.X).copy(absoluteOffset = 700.0)) +
                        (AxisId.Z to workspace.state(AxisId.Z).copy(absoluteOffset = -300.0)),
                )
            }
        val tool = Tool(id = "boring", kind = ToolKind.Lathe, xOffsetUm = mm(2.0), zOffsetUm = mm(3.5))
        val rawX = mm(12.0)
        val rawZ = mm(-48.0)
        val selected = ToolCompensation.selectTool(start, tool)
        assertEquals(start.abs(AxisId.X, rawX), selected.abs(AxisId.X, rawX))
        assertEquals(start.abs(AxisId.Z, rawZ), selected.abs(AxisId.Z, rawZ))
        assertEquals(2000.0, selected.state(AxisId.X).toolOffset)
        assertEquals(3500.0, selected.state(AxisId.Z).toolOffset)
        assertEquals("boring", selected.activeToolId)
    }

    @Test
    fun theActiveStateOfTheButtons() {
        assertFalse(ToolCompensation.radiusActive(mill))
        assertFalse(ToolCompensation.heightActive(mill))
        assertFalse(ToolCompensation.latheOffsetActive(lathe))
        val radius = applied(ToolCompensation.radius(mill, ToolPosition.MiddleLeft, mm(10.0)))
        assertTrue(ToolCompensation.radiusActive(radius))
        assertFalse(ToolCompensation.heightActive(radius))
        val onlyDiameter = applied(ToolCompensation.radius(mill, ToolPosition.Center, mm(10.0)))
        assertTrue(ToolCompensation.radiusActive(onlyDiameter))
        val height = applied(ToolCompensation.height(mill, mm(-1.0)))
        assertTrue(ToolCompensation.heightActive(height))
        assertFalse(ToolCompensation.radiusActive(height))
        val latheZ = applied(ToolCompensation.latheOffset(lathe, 0.0, mm(1.0)))
        assertTrue(ToolCompensation.latheOffsetActive(latheZ))
    }

    @Test
    fun aLongPressClearsTheOffsetOfTheButton() {
        val radius = applied(ToolCompensation.radius(mill, ToolPosition.BackLeft, mm(10.0)))
        val both = applied(ToolCompensation.height(radius, mm(-2.0)))
        val noRadius = ToolCompensation.clearRadius(both)
        assertFalse(ToolCompensation.radiusActive(noRadius))
        assertTrue(ToolCompensation.heightActive(noRadius))
        val noHeight = ToolCompensation.clearHeight(both)
        assertFalse(ToolCompensation.heightActive(noHeight))
        assertTrue(ToolCompensation.radiusActive(noHeight))
        val latheOffset = applied(ToolCompensation.latheOffset(lathe, mm(1.0), mm(1.0)))
        val cleared = ToolCompensation.clearLatheOffset(latheOffset)
        assertFalse(ToolCompensation.latheOffsetActive(cleared))
        assertNull(cleared.activeToolId)
    }
}
