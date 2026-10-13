package org.superdro.core.functions.drawing

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.superdro.core.functions.graphics.Vec2
import org.superdro.core.model.DrawingShape
import org.superdro.core.model.ShapeType

/** The shape map in integer nm. */
class ShapeGeometryTest {

    private val line = DrawingShape(type = ShapeType.Line, x1 = 10_000_000, y1 = 0, x2 = 20_000_000, y2 = 5_000_000)

    @Test
    fun aQuarterTurnIsExact() {
        val turned = ShapeGeometry.quarterTurn(line, 1, 0, 0)
        assertEquals(listOf(0L, 10_000_000L, -5_000_000L, 20_000_000L), listOf(turned.x1, turned.y1, turned.x2, turned.y2))
        val back = ShapeGeometry.quarterTurn(turned, -1, 0, 0)
        assertEquals(line, back)
        val half = ShapeGeometry.quarterTurn(line, 2, 1_000_000, 1_000_000)
        assertEquals(listOf(-8_000_000L, 2_000_000L), listOf(half.x1, half.y1))
        // The function rotate() uses the quarter turn for a multiple of π/2.
        assertEquals(turned, ShapeGeometry.rotate(line, PI / 2.0, 0, 0))
    }

    @Test
    fun anArcStartTurnsWithTheShapeAndStaysIn0To2Pi() {
        val arc = DrawingShape(type = ShapeType.Arc, x1 = 0, y1 = 0, radius = 1_000_000, startAngle = 1.75 * PI, sweepAngle = 1.0)
        val turned = ShapeGeometry.quarterTurn(arc, 1, 0, 0)
        assertEquals(0.25 * PI, assertNotNull(turned.startAngle), 1e-12)
        assertEquals(1.0, turned.sweepAngle)
        val rotated = ShapeGeometry.rotate(arc, 0.25, 0, 0)
        assertEquals(1.75 * PI + 0.25, assertNotNull(rotated.startAngle), 1e-12)
    }

    @Test
    fun aMirrorChangesTheArcStartTheSweepAndTheBulges() {
        val arc = DrawingShape(type = ShapeType.Arc, x1 = 3_000_000, y1 = 4_000_000, radius = 1_000_000, startAngle = 0.5, sweepAngle = 1.0)
        val vertical = ShapeGeometry.mirrorAcrossVertical(arc, 0)
        assertEquals(-3_000_000L, vertical.x1)
        assertEquals(PI - 0.5, assertNotNull(vertical.startAngle), 1e-12)
        assertEquals(-1.0, vertical.sweepAngle)
        val horizontal = ShapeGeometry.mirrorAcrossHorizontal(arc, 0)
        assertEquals(-4_000_000L, horizontal.y1)
        assertEquals(2 * PI - 0.5, assertNotNull(horizontal.startAngle), 1e-12)
        val polyline = DrawingShape(type = ShapeType.Polyline, points = listOf(0, 0, 10, 0, 10, 10), bulges = mapOf(1 to 0.5))
        val mirrored = ShapeGeometry.mirrorAcrossVertical(polyline, 5)
        assertEquals(listOf(10L, 0L, 0L, 0L, 0L, 10L), mirrored.points)
        assertEquals(mapOf(1 to -0.5), mirrored.bulges)
    }

    @Test
    fun aScaleChangesThePointsAndTheRadiusAboutThePivot() {
        val circle = DrawingShape(type = ShapeType.Circle, x1 = 20_000_000, y1 = 10_000_000, radius = 3_175_000)
        val scaled = ShapeGeometry.scale(circle, 1.5, 10_000_000, 10_000_000)
        assertEquals(listOf(25_000_000L, 10_000_000L, 4_762_500L), listOf(scaled.x1, scaled.y1, scaled.radius))
    }

    @Test
    fun theExtentsOfLinesCirclesArcsAndBeziers() {
        assertEquals(NmBox(10_000_000, 0, 20_000_000, 5_000_000), ShapeGeometry.extents(line))
        val circle = DrawingShape(type = ShapeType.Circle, x1 = 0, y1 = 0, radius = 1000)
        assertEquals(NmBox(-1000, -1000, 1000, 1000), ShapeGeometry.extents(circle))
        // A quarter arc from 0 to π/2.
        val quarter = DrawingShape(type = ShapeType.Arc, x1 = 0, y1 = 0, radius = 1000, startAngle = 0.0, sweepAngle = PI / 2.0)
        assertEquals(NmBox(0, 0, 1000, 1000), ShapeGeometry.extents(quarter))
        // An arc from π/4 through π/2 and π to 5π/4 holds the points at 90° and 180°.
        val wide = DrawingShape(type = ShapeType.Arc, x1 = 0, y1 = 0, radius = 1000, startAngle = PI / 4.0, sweepAngle = PI)
        val wideBox = assertNotNull(ShapeGeometry.extents(wide))
        assertEquals(-1000L, wideBox.minX)
        assertEquals(1000L, wideBox.maxY)
        // A Bézier segment bulges past its end points.
        val bezier = DrawingShape(type = ShapeType.Bezier, points = listOf(0, 0, 0, 1000, 1000, 1000, 1000, 0))
        assertEquals(NmBox(0, 0, 1000, 750), ShapeGeometry.extents(bezier))
        assertNull(ShapeGeometry.extents(emptyList()))
    }

    @Test
    fun aPolylineBulgeArcHasItsCenterOnTheCorrectSide() {
        val semicircle = assertNotNull(ShapeGeometry.bulgeArc(Vec2(-1000.0, 0.0), Vec2(1000.0, 0.0), 1.0))
        assertEquals(1000.0, semicircle.radius, 1e-9)
        assertEquals(0.0, semicircle.center.x, 1e-9)
        assertEquals(0.0, semicircle.center.y, 1e-9)
        // A counterclockwise half circle from (−1000, 0) to (1000, 0) goes through (0, −1000).
        val box = assertNotNull(
            ShapeGeometry.extents(DrawingShape(type = ShapeType.Polyline, points = listOf(-1000, 0, 1000, 0), bulges = mapOf(0 to 1.0))),
        )
        assertEquals(NmBox(-1000, -1000, 1000, 0), box)
    }
}
