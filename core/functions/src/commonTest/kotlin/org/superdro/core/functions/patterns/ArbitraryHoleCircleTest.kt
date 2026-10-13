package org.superdro.core.functions.patterns

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.superdro.core.functions.TestValues.assertDegrees
import org.superdro.core.functions.TestValues.assertMm
import org.superdro.core.functions.TestValues.deg
import org.superdro.core.model.PatternAngleConvention

/**
 * The arbitrary hole circle.
 */
class ArbitraryHoleCircleTest {

    private val zero = PatternCommon(origin = AxisPoint(0.0, 0.0), convention = PatternAngleConvention.Legacy)

    private fun list(vararg degrees: Double): HoleAngleList =
        degrees.fold(HoleAngleList()) { list, angle -> list.add(deg(angle)) }

    @Test
    fun theAngles10And100And200GiveTheWorkedExample() {
        val result = ArbitraryHoleCircle.create(zero, 25400.0, list(10.0, 100.0, 200.0))
        val points = assertIs<PatternResult.Success>(result).group.points
        val expected = listOf(4.411 to 25.014, 25.014 to -4.411, -8.687 to -23.868)
        expected.forEachIndexed { index, (x, y) ->
            assertMm(x, points[index].x, "point ${index + 1} X")
            assertMm(y, points[index].y, "point ${index + 1} Y")
        }
        assertEquals("Circle", assertIs<PatternResult.Success>(result).group.groupName)
    }

    @Test
    fun theStandardConventionSwapsSinAndCos() {
        val common = zero.copy(convention = PatternAngleConvention.Standard)
        val points = assertIs<PatternResult.Success>(ArbitraryHoleCircle.create(common, 25400.0, list(10.0, 100.0))).group.points
        assertMm(25.014, points[0].x)
        assertMm(4.411, points[0].y)
        assertMm(-4.411, points[1].x)
        assertMm(25.014, points[1].y)
    }

    @Test
    fun theListRoundsTo4DecimalsIgnoresADuplicateAndKeepsTheOrder() {
        val angles = list(200.0, 10.123456, 10.12346, -90.0, 450.0, 270.0)
        assertEquals(4, angles.size)
        assertDegrees(200.0, angles.anglesArcSec[0])
        assertEquals(10.1235, angles.anglesArcSec[1] / 3600.0, 1e-9)
        assertDegrees(270.0, angles.anglesArcSec[2])
        assertDegrees(90.0, angles.anglesArcSec[3])
        assertEquals(listOf(200.0, 270.0, 90.0), angles.removeAt(1).anglesArcSec.map { angle -> kotlin.math.round(angle / 3600.0) })
    }

    @Test
    fun theAngleFieldAcceptsMinus360To360() {
        val result = HoleAngleList().addField(deg(361.0))
        assertEquals("Hole Angle value is too large", result.error?.text)
        assertEquals(0, result.list.size)
        val accepted = HoleAngleList().addField(deg(-360.0))
        assertNull(accepted.error)
        assertDegrees(0.0, accepted.list.anglesArcSec.single())
    }

    @Test
    fun oneAngleGivesTheMessageAtLeastTwoHoleAngles() {
        val failure = assertIs<PatternResult.Failure>(ArbitraryHoleCircle.create(zero, 25400.0, list(10.0)))
        assertEquals(listOf("At least two hole angles are required."), failure.errors.map { error -> error.text })
        assertEquals("Arbitrary Hole Circle", ArbitraryHoleCircle.title().text)
    }

    @Test
    fun theFirstHoleReferencePutsTheFirstAngleAtTheOrigin() {
        val common = zero.copy(reference = PositionReference.FirstPoint)
        val points = assertIs<PatternResult.Success>(ArbitraryHoleCircle.create(common, 25400.0, list(10.0, 100.0))).group.points
        assertMm(0.0, points[0].x)
        assertMm(0.0, points[0].y)
        assertMm(25.014 - 4.411, points[1].x)
        assertMm(-4.411 - 25.014, points[1].y)
    }
}
