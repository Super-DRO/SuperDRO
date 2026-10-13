package org.superdro.core.drawing.geometry

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeometryTest {

    @Test
    fun theOcsOfPlusZIsTheIdentityAndMinusZMirrorsX() {
        assertEquals(Affine.IDENTITY, Affine.ocs(0.0, 0.0, 1.0))
        assertEquals(Affine(-1.0, 0.0, 0.0, 1.0, 0.0, 0.0), Affine.ocs(0.0, 0.0, -1.0))
        // A tilted plane: (1, 0, 0) gives Ax = (0, 1, 0) and Ay = (0, 0, 1): the OCS x goes to WCS y.
        val tilted = Affine.ocs(1.0, 0.0, 0.0)
        assertEquals(Vec(0.0, 1.0), tilted.linear(Vec(1.0, 0.0)))
        assertEquals(Vec(0.0, 0.0), tilted.linear(Vec(0.0, 1.0)))
    }

    @Test
    fun theMultiplesOf90DegreesAreExact() {
        assertEquals(Affine(0.0, -1.0, 1.0, 0.0, 0.0, 0.0), Affine.rotate(PI / 2))
        assertEquals(Affine(-1.0, -0.0, 0.0, -1.0, 0.0, 0.0), Affine.rotate(PI))
        assertEquals(0.0 to -1.0, exactCosSin(-PI / 2))
    }

    @Test
    fun theCompositionAppliesTheInnerMapFirst() {
        val map = Affine.translate(10.0, 0.0) * Affine.scale(2.0, 3.0)
        assertEquals(Vec(12.0, 3.0), map.apply(1.0, 1.0))
        assertTrue(Affine.scale(2.0, 2.0).isSimilarity)
        assertTrue(Affine.scale(-1.0, 1.0).isSimilarity)
        assertFalse(Affine.scale(2.0, 1.0).isSimilarity)
        assertEquals(2.0, Affine.scale(2.0, 1.0).maxStretch, 1e-12)
    }

    @Test
    fun normalizeAngleGivesZeroToTwoPi() {
        assertEquals(0.0, normalizeAngle(2 * PI))
        assertEquals(3 * PI / 2, normalizeAngle(-PI / 2), 1e-15)
        assertEquals(PI / 4, normalizeAngle(PI / 4 + 4 * PI), 1e-12)
    }

    @Test
    fun theConicSegmentsAreInTheTolerance() {
        for ((radius, sweep) in listOf(1.0e6 to 2 * PI, 2.0e7 to 2 * PI, 1.0e9 to PI, 5.0e10 to 0.3)) {
            val conic = Conic(Vec(0.0, 0.0), Vec(radius, 0.0), Vec(0.0, radius / 2), 0.25, sweep)
            val count = conic.segmentCount(1000.0)
            var worst = 0.0
            for (cubic in conic.cubics(count)) {
                for (step in 0..40) {
                    val point = cubic.at(step / 40.0)
                    // The distance to the ellipse along the ray from the center, for an axis-parallel ellipse.
                    val angle = kotlin.math.atan2(point.y / (radius / 2), point.x / radius)
                    val onCurve = Vec(radius * cos(angle), radius / 2 * sin(angle))
                    worst = max(worst, (point - onCurve).length)
                }
            }
            assertTrue(worst <= 1000.0, "radius $radius: error $worst nm with $count segments")
        }
    }

    @Test
    fun aQuarterCircleNeedsOneSegmentForASmallRadius() {
        val conic = Conic(Vec(0.0, 0.0), Vec(1.0e6, 0.0), Vec(0.0, 1.0e6), 0.0, PI / 2)
        assertEquals(1L, conic.segmentCount(1000.0))
        assertTrue(Conic.unitCircleError(PI / 2) in 2.7e-4..3.0e-4)
    }

    @Test
    fun theBulgeArcHasTheRadiusAndTheSweep() {
        // A half circle: bulge 1 from (0, 0) to (10, 0), counterclockwise, center (5, 0).
        val arc = bulgeArc(Vec(0.0, 0.0), Vec(10.0, 0.0), 1.0)
        assertEquals(5.0, arc.center.x, 1e-12)
        assertEquals(0.0, arc.center.y, 1e-12)
        assertEquals(5.0, arc.u.x, 1e-12)
        assertEquals(PI, arc.sweep, 1e-12)
        val end = arc.at(arc.start + arc.sweep)
        assertEquals(10.0, end.x, 1e-9)
        assertEquals(0.0, end.y, 1e-9)
        // bulge = tan(angle / 4): 0.41421356 is a quarter circle.
        assertEquals(PI / 2, bulgeArc(Vec(0.0, 0.0), Vec(1.0, 1.0), sqrt(2.0) - 1).sweep, 1e-12)
    }

    /** The B-spline point of [degree] at [u] by the Cox-de Boor recursion (a second method). */
    private fun reference(points: List<Vec>, knots: DoubleArray, degree: Int, u: Double): Vec {
        fun basis(i: Int, p: Int): Double {
            if (p == 0) {
                val last = u == knots[points.size] && knots[i + 1] == knots[points.size] && knots[i] < knots[i + 1]
                return if ((u >= knots[i] && u < knots[i + 1]) || last) 1.0 else 0.0
            }
            val left = if (knots[i + p] == knots[i]) 0.0 else (u - knots[i]) / (knots[i + p] - knots[i]) * basis(i, p - 1)
            val right = if (knots[i + p + 1] == knots[i + 1]) 0.0 else (knots[i + p + 1] - u) / (knots[i + p + 1] - knots[i + 1]) * basis(i + 1, p - 1)
            return left + right
        }
        var x = 0.0
        var y = 0.0
        points.forEachIndexed { index, point ->
            val n = basis(index, degree)
            x += n * point.x
            y += n * point.y
        }
        return Vec(x, y)
    }

    @Test
    fun aCubicBSplineGivesExactBezierSegments() {
        val points = listOf(Vec(0.0, 0.0), Vec(10.0, 20.0), Vec(30.0, -5.0), Vec(50.0, 25.0), Vec(70.0, 0.0), Vec(80.0, 10.0))
        val knots = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 1.0, 2.5, 4.0, 4.0, 4.0, 4.0)
        val cubics = Nurbs(3, knots, points, null).toCubics(1.0, 100)
        assertTrue(cubics.toleranceMet)
        assertEquals(3, cubics.cubics.size)
        val spans = listOf(0.0 to 1.0, 1.0 to 2.5, 2.5 to 4.0)
        cubics.cubics.forEachIndexed { index, cubic ->
            val (a, b) = spans[index]
            for (step in 0..10) {
                val t = step / 10.0
                val expected = reference(points, knots, 3, a + (b - a) * t)
                assertTrue((cubic.at(t) - expected).length < 1e-9, "span $index, t $t")
            }
        }
    }

    @Test
    fun aRationalQuarterCircleIsInTheTolerance() {
        val w = sqrt(2.0) / 2
        val r = 1.0e7
        val nurbs = Nurbs(2, doubleArrayOf(0.0, 0.0, 0.0, 1.0, 1.0, 1.0), listOf(Vec(r, 0.0), Vec(r, r), Vec(0.0, r)), doubleArrayOf(1.0, w, 1.0))
        assertTrue(nurbs.rational)
        val approximation = nurbs.toCubics(1000.0, 1000)
        assertTrue(approximation.toleranceMet)
        for (cubic in approximation.cubics) {
            for (step in 0..20) assertTrue(abs(cubic.at(step / 20.0).length - r) <= 1000.0)
        }
    }

    @Test
    fun theFitPointSplinePassesThroughThePoints() {
        val points = listOf(Vec(0.0, 0.0), Vec(10.0, 15.0), Vec(25.0, 5.0), Vec(40.0, 20.0))
        val cubics = interpolateFitPoints(points, null, null)
        assertEquals(3, cubics.size)
        assertEquals(points, listOf(cubics[0].p0) + cubics.map { cubic -> cubic.p3 })
        // The joints are smooth: the tangents on the two sides are parallel.
        for (index in 0 until cubics.size - 1) {
            val before = cubics[index].p3 - cubics[index].p2
            val after = cubics[index + 1].p1 - cubics[index + 1].p0
            assertEquals(0.0, before cross after, 1e-9)
        }
        assertEquals(1, interpolateFitPoints(listOf(Vec(0.0, 0.0), Vec(1.0, 0.0)), null, null).size)
    }
}
