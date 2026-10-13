package org.superdro.core.functions.patterns

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.superdro.core.functions.TestValues.assertMm
import org.superdro.core.functions.TestValues.deg
import org.superdro.core.functions.TestValues.mm
import org.superdro.core.model.MachineType
import org.superdro.core.model.PatternAngleConvention
import org.superdro.core.model.Plane
import org.superdro.core.model.Workspace

/** The hole grid. */
class HoleGridTest {

    private val zero = PatternCommon(origin = AxisPoint(0.0, 0.0), convention = PatternAngleConvention.Legacy)

    private val input30 = HoleGridInput(repeat1 = 3, step1Um = mm(10.0), repeat2 = 2, step2Um = mm(20.0), angleArcSec = deg(30.0))

    private fun points(common: PatternCommon, input: HoleGridInput): List<AxisPoint> =
        assertIs<PatternResult.Success>(HoleGrid.create(common, input)).group.points

    private fun assertTable(expected: List<Pair<Double, Double>>, actual: List<AxisPoint>) {
        assertEquals(expected.size, actual.size)
        expected.forEachIndexed { index, (x, y) ->
            assertMm(x, actual[index].x, "Grid - ${index + 1} X")
            assertMm(y, actual[index].y, "Grid - ${index + 1} Y")
        }
    }

    @Test
    fun theSixPointTableAt30Degrees() {
        val group = assertIs<PatternResult.Success>(HoleGrid.create(zero, input30)).group
        assertTable(
            listOf(0.0 to 0.0, 8.660 to -5.000, 17.321 to -10.000, 10.000 to 17.321, 18.660 to 12.321, 27.321 to 7.321),
            group.points,
        )
        val names = PatternBatch.create(Workspace(), group, 1L).subDatums.map { subDatum -> subDatum.name }
        assertEquals((1..6).map { n -> "Grid - $n" }, names)
    }

    @Test
    fun theTableAt0Degrees() {
        assertTable(
            listOf(0.0 to 0.0, 10.0 to 0.0, 20.0 to 0.0, 0.0 to 20.0, 10.0 to 20.0, 20.0 to 20.0),
            points(zero, input30.copy(angleArcSec = 0.0)),
        )
    }

    @Test
    fun theStandardConventionTurnsTheGridCounterClockwise() {
        val standard = points(zero.copy(convention = PatternAngleConvention.Standard), input30)
        assertMm(8.660, standard[1].x)
        assertMm(5.000, standard[1].y)
        assertMm(-10.000, standard[3].x)
        assertMm(17.321, standard[3].y)
    }

    @Test
    fun twoRepeatValuesOf1GiveTheMessageWithTheAxisNamesOfThePlane() {
        val one = HoleGridInput(repeat1 = 1, repeat2 = 1)
        val expected = mapOf(
            Plane.XY to "Repeat X or Repeat Y value must be larger than 1",
            Plane.XZ to "Repeat X or Repeat Z value must be larger than 1",
            Plane.YZ to "Repeat Y or Repeat Z value must be larger than 1",
        )
        expected.forEach { (plane, text) ->
            val failure = assertIs<PatternResult.Failure>(HoleGrid.create(zero.copy(plane = plane), one))
            assertEquals(listOf(text), failure.errors.map { error -> error.text }, "$plane")
        }
        val lathe = assertIs<PatternResult.Failure>(HoleGrid.create(zero.copy(machineType = MachineType.Lathe), HoleGridInput()))
        assertEquals(listOf("Repeat X or Repeat Z value must be larger than 1"), lathe.errors.map { error -> error.text })
    }

    @Test
    fun aStepIsRequiredWhenItsRepeatIsMoreThan1() {
        val failure = assertIs<PatternResult.Failure>(HoleGrid.create(zero, HoleGridInput(repeat1 = 3, repeat2 = 2)))
        assertEquals(listOf("Step X value is required", "Step Y value is required"), failure.errors.map { error -> error.text })
        val yz = assertIs<PatternResult.Failure>(HoleGrid.create(zero.copy(plane = Plane.YZ), HoleGridInput(repeat1 = 3)))
        assertEquals(listOf("Step Y value is required"), yz.errors.map { error -> error.text })
        val range = assertIs<PatternResult.Failure>(
            HoleGrid.create(zero, HoleGridInput(repeat1 = 101, step1Um = -1.0, repeat2 = 2, step2Um = 5.0, angleArcSec = deg(400.0))),
        )
        assertEquals(
            listOf("Repeat X value is too large", "Step X value is too small", "Angle value is too large"),
            range.errors.map { error -> error.text },
        )
    }

    @Test
    fun aRepeatValueOf0GivesNoPoint() {
        assertEquals(emptyList(), points(zero, HoleGridInput(repeat1 = 0, repeat2 = 5, step2Um = 1000.0)))
    }

    @Test
    fun theTitleAndTheDefaultPrefix() {
        assertEquals("Hole Grid", HoleGrid.title(MachineType.VerticalMill).text)
        assertEquals("Linear Point Array", HoleGrid.title(MachineType.Lathe).text)
        assertEquals("Grid", assertIs<PatternResult.Success>(HoleGrid.create(zero, input30)).group.groupName)
    }
}
