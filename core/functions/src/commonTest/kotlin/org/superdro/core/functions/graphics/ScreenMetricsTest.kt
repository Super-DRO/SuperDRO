package org.superdro.core.functions.graphics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.superdro.core.functions.PointUm

/** The check of the screen density values that the device reports. */
class ScreenMetricsTest {

    /** Gives the screen width and height (px) of a model square with the side [sideUm]. */
    private fun squareOnScreen(metrics: ScreenMetrics, sideUm: Double): Pair<Double, Double> {
        val transform = ViewTransform(originX = 0.0, originY = 0.0, scale = 1.0, metrics = metrics)
        val corner = transform.toScreen(PointUm(0.0, 0.0))
        val opposite = transform.toScreen(PointUm(sideUm, sideUm))
        return (opposite.x - corner.x) to (corner.y - opposite.y)
    }

    @Test
    fun aWrongYdpiDoesNotStretchASquare() {
        // A tablet reported an ydpi of approximately 2.25 times its xdpi. A square part
        // then showed as a tall rectangle, and each circle as a tall ellipse.
        val reported = ScreenMetrics(xdpi = 140.0, ydpi = 315.0, density = 2.0)
        val (badWidth, badHeight) = squareOnScreen(reported, 50_000.0)
        assertTrue(badHeight / badWidth > 2.0)

        val checked = ScreenMetrics.fromReported(xdpi = 140.0, ydpi = 315.0, densityDpi = 320.0, density = 2.0)
        val (width, height) = squareOnScreen(checked, 50_000.0)
        assertEquals(width, height, 1e-9)
        // Both axes use the value that is nearer to the density class.
        assertEquals(ScreenMetrics(315.0, 315.0, 2.0), checked)
    }

    @Test
    fun valuesThatAgreeStayAsTheDeviceReportsThem() {
        assertEquals(
            ScreenMetrics(224.0, 226.0, 1.5),
            ScreenMetrics.fromReported(xdpi = 224.0, ydpi = 226.0, densityDpi = 240.0, density = 1.5),
        )
    }

    @Test
    fun theDensityClassAppliesWhenBothValuesAreFarFromIt() {
        assertEquals(
            ScreenMetrics(320.0, 320.0, 2.0),
            ScreenMetrics.fromReported(xdpi = 100.0, ydpi = 600.0, densityDpi = 320.0, density = 2.0),
        )
    }

    @Test
    fun aValueThatIsNotPositiveIsNotUsed() {
        assertEquals(
            ScreenMetrics(320.0, 320.0, 2.0),
            ScreenMetrics.fromReported(xdpi = 0.0, ydpi = Double.NaN, densityDpi = 320.0, density = 2.0),
        )
        assertEquals(
            ScreenMetrics(300.0, 300.0, 2.0),
            ScreenMetrics.fromReported(xdpi = 0.0, ydpi = 300.0, densityDpi = 320.0, density = 2.0),
        )
        assertEquals(
            ScreenMetrics(160.0, 160.0, 1.0),
            ScreenMetrics.fromReported(xdpi = -1.0, ydpi = 0.0, densityDpi = 0.0, density = 1.0),
        )
    }
}
