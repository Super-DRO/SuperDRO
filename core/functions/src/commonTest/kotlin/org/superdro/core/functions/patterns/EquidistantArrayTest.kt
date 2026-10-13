package org.superdro.core.functions.patterns

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.superdro.core.functions.TestValues.assertMm
import org.superdro.core.functions.TestValues.mm
import org.superdro.core.model.MachineType
import org.superdro.core.model.Workspace

/** The equidistant array. */
class EquidistantArrayTest {

    private val xy = AxisSwitches(x = true, y = true, z = false)
    private val none = AxisRange(null, null)

    @Test
    fun fivePointsFrom0To100MmInXAnd0To50MmInY() {
        val result = EquidistantArray.create(5, xy, AxisRange(0.0, mm(100.0)), AxisRange(0.0, mm(50.0)), none)
        val group = assertIs<PatternResult.Success>(result).group
        val expected = listOf(0.0 to 0.0, 25.0 to 12.5, 50.0 to 25.0, 75.0 to 37.5, 100.0 to 50.0)
        expected.forEachIndexed { index, (x, y) ->
            assertMm(x, group.points[index].x)
            assertMm(y, group.points[index].y)
            assertEquals(null, group.points[index].z)
        }
        assertEquals("Grid", group.groupName)
        val names = PatternBatch.create(Workspace(), group, 1L).subDatums.map { subDatum -> subDatum.name }
        assertEquals((1..5).map { n -> "Grid - $n" }, names)
    }

    @Test
    fun theCountMessages() {
        val empty = assertIs<PatternResult.Failure>(EquidistantArray.create(null, xy, AxisRange(0.0, 1.0), AxisRange(0.0, 1.0), none))
        assertEquals(listOf("Please enter a valid number of holes."), empty.errors.map { error -> error.text })
        val two = assertIs<PatternResult.Failure>(EquidistantArray.create(2, xy, AxisRange(0.0, 1.0), AxisRange(0.0, 1.0), none))
        assertEquals(listOf("Number of holes can't be less than 3."), two.errors.map { error -> error.text })
    }

    @Test
    fun aPositionThatIsNotValidGivesTheAxisMessage() {
        val failure = assertIs<PatternResult.Failure>(
            EquidistantArray.create(3, xy, AxisRange(null, 1.0), AxisRange(0.0, Double.NaN), none),
        )
        assertEquals(
            listOf(
                "Please enter a valid number for the starting X position.",
                "Please enter a valid number for the ending Y position.",
            ),
            failure.errors.map { error -> error.text },
        )
    }

    @Test
    fun theIncludedAxes() {
        assertEquals(AxisSwitches(x = true, y = true, z = true), EquidistantArray.includedAxes(MachineType.VerticalMill, true, true, true, true))
        assertEquals(AxisSwitches(x = true, y = true, z = false), EquidistantArray.includedAxes(MachineType.VerticalMill, true, true, true, false))
        assertEquals(AxisSwitches(x = true, y = false, z = true), EquidistantArray.includedAxes(MachineType.Lathe, true, true, true, true))
        assertEquals(AxisSwitches(x = false, y = true, z = false), EquidistantArray.includedAxes(MachineType.VerticalMill, false, true, false, true))
        assertEquals("Equidistant Hole Array", EquidistantArray.title(MachineType.VerticalMill).text)
        assertEquals("Equidistant Point Array", EquidistantArray.title(MachineType.Lathe).text)
    }
}
