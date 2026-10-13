package org.superdro.core.functions.patterns

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.superdro.core.functions.TestValues.assertMm
import org.superdro.core.functions.TestValues.deg
import org.superdro.core.functions.TestValues.mm
import org.superdro.core.model.MachineType
import org.superdro.core.model.PatternAngleConvention
import org.superdro.core.model.SubDatum

/**
 * Step and repeat.
 */
class StepAndRepeatTest {

    private val mill = PatternCommon(origin = AxisPoint(0.0, 0.0, 0.0), convention = PatternAngleConvention.Legacy)
    private val lathe = PatternCommon(origin = AxisPoint(0.0, null, 0.0), machineType = MachineType.Lathe)

    private val oneRow = HoleGridInput(repeat1 = 3, step1Um = mm(10.0), repeat2 = 1)

    private fun points(common: PatternCommon, sources: List<SubDatum>, rotation: Double = 0.0, input: HoleGridInput = oneRow) =
        assertIs<PatternResult.Success>(StepAndRepeat.create(common, input, sources, rotation)).group.points

    @Test
    fun aMillCopyKeepsTheZOfTheSubDatumAndDropsTheFirstGridPoint() {
        val source = SubDatum(x = mm(5.0), y = mm(6.0), z = mm(-3.0))
        val copies = points(mill, listOf(source))
        assertEquals(2, copies.size)
        assertMm(15.0, copies[0].x)
        assertMm(6.0, copies[0].y)
        assertMm(-3.0, copies[0].z)
        assertMm(25.0, copies[1].x)
        assertMm(6.0, copies[1].y)
        assertMm(-3.0, copies[1].z)
    }

    @Test
    fun a2dSourceGivesA2dCopy() {
        val copies = points(mill, listOf(SubDatum(x = mm(5.0), y = mm(6.0))))
        assertEquals(null, copies[0].z)
    }

    @Test
    fun aLatheCopyKeepsXAsXAndZAsZ() {
        val source = SubDatum(x = mm(10.0), z = mm(-40.0))
        val input = HoleGridInput(repeat1 = 1, repeat2 = 3, step2Um = mm(15.0))
        val copies = points(lathe, listOf(source), input = input)
        assertEquals(2, copies.size)
        assertMm(10.0, copies[0].x)
        assertMm(-25.0, copies[0].z)
        assertMm(10.0, copies[1].x)
        assertMm(-10.0, copies[1].z)
        assertEquals(null, copies[0].y)
    }

    @Test
    fun theGridPointIsTheOuterLoopAndTheSourcesAreTheInnerLoop() {
        val a = SubDatum(x = 0.0, y = 0.0)
        val b = SubDatum(x = 1000.0, y = 0.0)
        val copies = points(mill, listOf(a, b))
        assertEquals(listOf(10000.0, 11000.0, 20000.0, 21000.0), copies.map { point -> point.x })
    }

    @Test
    fun theOriginAndANullCoordinateApply() {
        val common = mill.copy(origin = AxisPoint(mm(100.0), mm(200.0), mm(7.0)))
        val copies = points(common, listOf(SubDatum(x = null, y = mm(1.0), z = null)))
        assertMm(110.0, copies[0].x)
        assertMm(201.0, copies[0].y)
        assertEquals(null, copies[0].z)
    }

    @Test
    fun aRotationTurnsTheSourceVectorBeforeTheGridPointIsAdded() {
        val copies = points(mill, listOf(SubDatum(x = mm(10.0), y = 0.0)), rotation = deg(90.0))
        // The source (10, 0) turns to (0, 10); the grid point (10, 0) then adds.
        assertMm(10.0, copies[0].x)
        assertMm(10.0, copies[0].y)
    }

    @Test
    fun theTitles() {
        assertEquals("Step and Repeat 3 Sub-Datums", StepAndRepeat.title(3).text)
        assertEquals("Step and Repeat Workspace", StepAndRepeat.title(null).text)
    }

    @Test
    fun twoRepeatValuesOf1AreRefused() {
        val failure = assertIs<PatternResult.Failure>(StepAndRepeat.create(mill, HoleGridInput(), listOf(SubDatum(x = 0.0))))
        assertEquals(listOf("Repeat X or Repeat Y value must be larger than 1"), failure.errors.map { error -> error.text })
    }
}
