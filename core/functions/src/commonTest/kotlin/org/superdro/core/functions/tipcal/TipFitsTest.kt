package org.superdro.core.functions.tipcal

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The circle fit and the ellipse fit of the tip calibration. */
class TipFitsTest {

    /** The mean points of the four clock positions on a circle: 9, 3, 12, and 6 o'clock. */
    private fun clockPoints(cx: Double, cy: Double, rx: Double, ry: Double = rx): Pair<List<Double>, List<Double>> =
        listOf(cx - rx, cx + rx, cx, cx) to listOf(cy, cy, cy + ry, cy - ry)

    @Test
    fun fourClockPositionsGiveTheCircleOfTheWorkedExample() {
        val (xs, ys) = clockPoints(0.4, 0.1, 11506.3)
        val fit = assertNotNull(TipFits.fitCircle(xs, ys))
        assertEquals(0.4, fit.centerXUm, 1e-9)
        assertEquals(0.1, fit.centerYUm, 1e-9)
        assertEquals(11506.3, fit.radiusUm, 1e-9)
        assertEquals(0.0, fit.residualRmsUm, 1e-9)
        // tip = gauge − 2R: 25000.0 − 2 × 11506.3 = 1987.4 µm.
        assertEquals(1987.4, 25000.0 - 2.0 * fit.radiusUm, 1e-9)
    }

    @Test
    fun aCircleWithACenterOffsetAndUnevenAnglesIsFoundExactly() {
        val angles = listOf(175.0, 8.0, 93.0, 268.0).map { degrees -> degrees * PI / 180.0 }
        val xs = angles.map { angle -> 1234.5 + 6000.0 * cos(angle) }
        val ys = angles.map { angle -> -678.9 + 6000.0 * sin(angle) }
        val fit = assertNotNull(TipFits.fitCircle(xs, ys))
        assertEquals(1234.5, fit.centerXUm, 1e-6)
        assertEquals(-678.9, fit.centerYUm, 1e-6)
        assertEquals(6000.0, fit.radiusUm, 1e-6)
        assertEquals(0.0, fit.residualRmsUm, 1e-6)
    }

    @Test
    fun theResidualIsTheRmsOfTheRadialErrors() {
        // An ellipse with semi axes 11500 and 11510 at the four clock positions.
        val (xs, ys) = clockPoints(5.0, -3.0, 11500.0, 11510.0)
        val fit = assertNotNull(TipFits.fitCircle(xs, ys))
        val radius = sqrt((11500.0 * 11500.0 + 11510.0 * 11510.0) / 2.0)
        assertEquals(5.0, fit.centerXUm, 1e-9)
        assertEquals(-3.0, fit.centerYUm, 1e-9)
        assertEquals(radius, fit.radiusUm, 1e-9)
        val e1 = 11500.0 - radius
        val e2 = 11510.0 - radius
        assertEquals(sqrt((2.0 * e1 * e1 + 2.0 * e2 * e2) / 4.0), fit.residualRmsUm, 1e-9)
    }

    @Test
    fun pointsOnALineOrFewerThanThreePointsGiveNoCircle() {
        assertNull(TipFits.fitCircle(listOf(0.0, 10.0, 20.0, 30.0), listOf(0.0, 10.0, 20.0, 30.0)))
        assertNull(TipFits.fitCircle(listOf(0.0, 10.0), listOf(0.0, 5.0)))
        assertNull(TipFits.fitCircle(listOf(1.0, 1.0, 1.0, 1.0), listOf(2.0, 2.0, 2.0, 2.0)))
    }

    @Test
    fun theEllipseFitGivesTheTwoSemiAxes() {
        val (xs, ys) = clockPoints(5.0, -3.0, 11500.0, 11510.0)
        val fit = assertNotNull(TipFits.fitEllipse(xs, ys))
        assertEquals(11500.0, fit.semiAxisXUm, 1e-6)
        assertEquals(11510.0, fit.semiAxisYUm, 1e-6)
        // diameterX = G − 2·semiAxisX, diameterY = G − 2·semiAxisY.
        assertEquals(2000.0, 25000.0 - 2.0 * fit.semiAxisXUm, 1e-6)
        assertEquals(1980.0, 25000.0 - 2.0 * fit.semiAxisYUm, 1e-6)
    }

    @Test
    fun theEllipseFitNeedsExactlyFourPointsThatAreNotDegenerate() {
        val (xs, ys) = clockPoints(0.0, 0.0, 1000.0)
        assertNull(TipFits.fitEllipse(xs.take(3), ys.take(3)))
        assertNull(TipFits.fitEllipse(listOf(0.0, 0.0, 0.0, 0.0), listOf(0.0, 0.0, 0.0, 0.0)))
        // Four points on a line give a singular system.
        assertNull(TipFits.fitEllipse(listOf(0.0, 1.0, 2.0, 3.0), listOf(0.0, 1.0, 2.0, 3.0)))
    }

    @Test
    fun gaussJordanSolvesWithPartialPivot() {
        // The first pivot is 0, so the step must swap the rows.
        val solution = assertNotNull(
            TipFits.solveGaussJordan(
                arrayOf(
                    doubleArrayOf(0.0, 1.0, 3.0),
                    doubleArrayOf(2.0, 1.0, 5.0),
                ),
            ),
        )
        assertEquals(1.0, solution[0], 1e-12)
        assertEquals(3.0, solution[1], 1e-12)
        assertNull(TipFits.solveGaussJordan(arrayOf(doubleArrayOf(1.0, 2.0, 3.0), doubleArrayOf(2.0, 4.0, 6.0))))
    }
}
