package org.superdro.core.functions.drawing

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import org.superdro.core.functions.graphics.AngleMath
import org.superdro.core.functions.graphics.Vec2
import org.superdro.core.model.DrawingShape
import org.superdro.core.model.ShapeType

/** A box of a drawing in integer nanometers. */
data class NmBox(val minX: Long, val minY: Long, val maxX: Long, val maxY: Long) {
    val width: Long get() = maxX - minX
    val height: Long get() = maxY - minY

    /** Gives the box that holds this box and [other]. */
    fun union(other: NmBox): NmBox =
        NmBox(min(minX, other.minX), min(minY, other.minY), max(maxX, other.maxX), max(maxY, other.maxY))
}

/**
 * The geometry of the drawing shapes in integer nanometers. An arc start angle is normalized to [0,
 * 2π) after
 * each change. A transform with a mirror multiplies the arc sweeps and the polyline bulges by −1.
 */
object ShapeGeometry {

    /** Gives [shape] with all points moved by ([dxNm], [dyNm]). */
    fun translate(shape: DrawingShape, dxNm: Long, dyNm: Long): DrawingShape =
        mapPoints(shape) { x, y -> (x + dxNm) to (y + dyNm) }

    /**
     * Gives [shape] turned by [turns] quarter turns counterclockwise about ([pivotX], [pivotY]).
     * The integer math is exact.
     */
    fun quarterTurn(shape: DrawingShape, turns: Int, pivotX: Long, pivotY: Long): DrawingShape {
        val n = ((turns % 4) + 4) % 4
        if (n == 0) return shape
        val moved = mapPoints(shape) { x, y ->
            val dx = x - pivotX
            val dy = y - pivotY
            when (n) {
                1 -> (pivotX - dy) to (pivotY + dx)
                2 -> (pivotX - dx) to (pivotY - dy)
                else -> (pivotX + dy) to (pivotY - dx)
            }
        }
        return turnAngles(moved, n * PI / 2.0)
    }

    /**
     * Gives [shape] turned by [angleRad] counterclockwise about ([pivotX], [pivotY]). Each new
     * coordinate is rounded to the nearest nm. A multiple of π/2 uses [quarterTurn].
     */
    fun rotate(shape: DrawingShape, angleRad: Double, pivotX: Long, pivotY: Long): DrawingShape {
        val (cos, sin) = AngleMath.cosSinRad(angleRad)
        when {
            cos == 1.0 && sin == 0.0 -> return shape
            cos == 0.0 && sin == 1.0 -> return quarterTurn(shape, 1, pivotX, pivotY)
            cos == -1.0 && sin == 0.0 -> return quarterTurn(shape, 2, pivotX, pivotY)
            cos == 0.0 && sin == -1.0 -> return quarterTurn(shape, 3, pivotX, pivotY)
        }
        val moved = mapPoints(shape) { x, y ->
            val dx = (x - pivotX).toDouble()
            val dy = (y - pivotY).toDouble()
            (pivotX + (dx * cos - dy * sin).roundToLong()) to (pivotY + (dx * sin + dy * cos).roundToLong())
        }
        return turnAngles(moved, angleRad)
    }

    /**
     * Gives [shape] mirrored across the vertical line x = [lineXNm]: `x' = 2·line − x`. The arc
     * start becomes `π − a0`. The sweeps and the bulges change sign.
     */
    fun mirrorAcrossVertical(shape: DrawingShape, lineXNm: Long): DrawingShape {
        val moved = mapPoints(shape) { x, y -> (2 * lineXNm - x) to y }
        return mirrorAngles(moved) { a0 -> PI - a0 }
    }

    /**
     * Gives [shape] mirrored across the horizontal line y = [lineYNm]: `y' = 2·line − y`. The arc
     * start becomes `−a0`. The sweeps and the bulges change sign.
     */
    fun mirrorAcrossHorizontal(shape: DrawingShape, lineYNm: Long): DrawingShape {
        val moved = mapPoints(shape) { x, y -> x to (2 * lineYNm - y) }
        return mirrorAngles(moved) { a0 -> -a0 }
    }

    /**
     * Gives [shape] scaled by [factor] about ([pivotX], [pivotY]): `x' = round((x − px)·s) + px`.
     * The radius becomes `round(r·s)`. The angles and the bulges do not change.
     */
    fun scale(shape: DrawingShape, factor: Double, pivotX: Long, pivotY: Long): DrawingShape {
        val moved = mapPoints(shape) { x, y ->
            (pivotX + ((x - pivotX) * factor).roundToLong()) to (pivotY + ((y - pivotY) * factor).roundToLong())
        }
        return moved.copy(radius = moved.radius?.let { radius -> (radius * factor).roundToLong() })
    }

    /** Gives the box of all [shapes], or null when there is no shape with a position. */
    fun extents(shapes: List<DrawingShape>): NmBox? =
        shapes.mapNotNull(::extents).reduceOrNull { a, b -> a.union(b) }

    /**
     * Gives the box of [shape], or null when the shape has no position. An arc box holds its two
     * end points and each quadrant point inside the sweep. A Bézier box holds the extreme points
     * of each cubic segment.
     */
    fun extents(shape: DrawingShape): NmBox? {
        val points = ArrayList<Vec2>()
        when (shape.type) {
            ShapeType.Line -> {
                pointOf(shape.x1, shape.y1)?.let(points::add)
                pointOf(shape.x2, shape.y2)?.let(points::add)
            }
            ShapeType.Point -> pointOf(shape.x1, shape.y1)?.let(points::add)
            ShapeType.Circle -> {
                val center = pointOf(shape.x1, shape.y1) ?: return null
                val r = (shape.radius ?: 0L).toDouble()
                points += Vec2(center.x - r, center.y - r)
                points += Vec2(center.x + r, center.y + r)
            }
            ShapeType.Arc -> {
                val center = pointOf(shape.x1, shape.y1) ?: return null
                points += arcExtentPoints(center, (shape.radius ?: 0L).toDouble(), shape.startAngle ?: 0.0, shape.sweepAngle ?: 0.0)
            }
            ShapeType.Polyline -> {
                val vertices = vertices(shape)
                points += vertices
                val segments = if (shape.closed) vertices.size else vertices.size - 1
                for (index in 0 until segments) {
                    val bulge = shape.bulges[index] ?: 0.0
                    if (bulge == 0.0) continue
                    val arc = bulgeArc(vertices[index], vertices[(index + 1) % vertices.size], bulge) ?: continue
                    points += arcExtentPoints(arc.center, arc.radius, arc.startAngle, arc.sweepAngle)
                }
            }
            ShapeType.Bezier -> {
                val vertices = vertices(shape)
                if (vertices.isNotEmpty()) points += vertices[0]
                var index = 0
                while (index + 3 < vertices.size) {
                    points += bezierExtentPoints(vertices[index], vertices[index + 1], vertices[index + 2], vertices[index + 3])
                    index += 3
                }
            }
        }
        if (points.isEmpty()) return null
        return NmBox(
            points.minOf { point -> point.x }.floorToLong(),
            points.minOf { point -> point.y }.floorToLong(),
            points.maxOf { point -> point.x }.ceilToLong(),
            points.maxOf { point -> point.y }.ceilToLong(),
        )
    }

    /** Gives the vertices of a polyline or a Bézier chain. */
    fun vertices(shape: DrawingShape): List<Vec2> =
        shape.points.chunked(2).filter { pair -> pair.size == 2 }.map { pair -> Vec2(pair[0].toDouble(), pair[1].toDouble()) }

    /** The arc of a polyline segment with a bulge. */
    data class BulgeArc(val center: Vec2, val radius: Double, val startAngle: Double, val sweepAngle: Double)

    /**
     * Gives the arc of the polyline segment from [start] to [end] with [bulge] = tan(θ/4). A
     * positive bulge is a counterclockwise arc. Gives null for a zero bulge or a zero chord.
     */
    fun bulgeArc(start: Vec2, end: Vec2, bulge: Double): BulgeArc? {
        val chord = end - start
        val length = chord.length
        if (bulge == 0.0 || length == 0.0) return null
        val sweep = 4.0 * atan(bulge)
        val radius = length * (1.0 + bulge * bulge) / (4.0 * abs(bulge))
        // The center is on the left of the chord for a counterclockwise arc.
        val sagittaToCenter = radius - length * abs(bulge) / 2.0
        val normal = Vec2(-chord.y, chord.x) / length
        val middle = (start + end) / 2.0
        val side = if (bulge > 0.0) 1.0 else -1.0
        val center = middle + normal * (side * sagittaToCenter)
        val startAngle = (start - center).angle
        return BulgeArc(center, radius, startAngle, sweep)
    }

    /** Gives the motion direction at the start and at the end of a segment with [bulge] (the arc tangents). */
    fun bulgeTangents(start: Vec2, end: Vec2, bulge: Double): Pair<Vec2, Vec2> {
        val chord = end - start
        if (bulge == 0.0) return chord to chord
        val theta = 4.0 * atan(bulge)
        return chord.rotated(-theta / 2.0) to chord.rotated(theta / 2.0)
    }

    private fun arcExtentPoints(center: Vec2, radius: Double, start: Double, sweep: Double): List<Vec2> {
        val result = ArrayList<Vec2>()
        result += center + Vec2(radius * cos(start), radius * sin(start))
        result += center + Vec2(radius * cos(start + sweep), radius * sin(start + sweep))
        val from = min(start, start + sweep)
        val to = max(start, start + sweep)
        var k = ceil(from / (PI / 2.0))
        while (k * (PI / 2.0) <= to) {
            val angle = k * (PI / 2.0)
            result += center + Vec2(radius * cos(angle), radius * sin(angle))
            k += 1.0
        }
        return result
    }

    private fun bezierExtentPoints(p0: Vec2, p1: Vec2, p2: Vec2, p3: Vec2): List<Vec2> {
        val result = arrayListOf(p0, p3)
        for (axis in 0..1) {
            val a0 = if (axis == 0) p0.x else p0.y
            val a1 = if (axis == 0) p1.x else p1.y
            val a2 = if (axis == 0) p2.x else p2.y
            val a3 = if (axis == 0) p3.x else p3.y
            // The derivative is the quadratic a·t² + b·t + c.
            val a = -a0 + 3 * a1 - 3 * a2 + a3
            val b = 2 * (a0 - 2 * a1 + a2)
            val c = a1 - a0
            val roots = ArrayList<Double>()
            if (abs(a) < 1e-12) {
                if (b != 0.0) roots += -c / b
            } else {
                val discriminant = b * b - 4 * a * c
                if (discriminant >= 0.0) {
                    val root = sqrt(discriminant)
                    roots += (-b + root) / (2 * a)
                    roots += (-b - root) / (2 * a)
                }
            }
            for (t in roots) {
                if (t > 0.0 && t < 1.0) result += bezierPoint(p0, p1, p2, p3, t)
            }
        }
        return result
    }

    private fun bezierPoint(p0: Vec2, p1: Vec2, p2: Vec2, p3: Vec2, t: Double): Vec2 {
        val u = 1.0 - t
        return p0 * (u * u * u) + p1 * (3 * u * u * t) + p2 * (3 * u * t * t) + p3 * (t * t * t)
    }

    private fun pointOf(x: Long?, y: Long?): Vec2? = if (x != null && y != null) Vec2(x.toDouble(), y.toDouble()) else null

    // The trigonometric functions give a small error, for example sin(π) = 1.2e-16. A value
    // within 1e-6 nm of an integer counts as that integer.
    private fun Double.floorToLong(): Long = floor(this + 1e-6).toLong()

    private fun Double.ceilToLong(): Long = ceil(this - 1e-6).toLong()

    /** Gives [shape] with each point (x1, y1), (x2, y2), and each pair of [DrawingShape.points] mapped by [map]. */
    internal fun mapPoints(shape: DrawingShape, map: (Long, Long) -> Pair<Long, Long>): DrawingShape {
        val x1 = shape.x1
        val y1 = shape.y1
        val x2 = shape.x2
        val y2 = shape.y2
        val p1 = if (x1 != null && y1 != null) map(x1, y1) else null
        val p2 = if (x2 != null && y2 != null) map(x2, y2) else null
        val points = if (shape.points.isEmpty()) shape.points else {
            val result = ArrayList<Long>(shape.points.size)
            var index = 0
            while (index + 1 < shape.points.size) {
                val (x, y) = map(shape.points[index], shape.points[index + 1])
                result += x
                result += y
                index += 2
            }
            if (index < shape.points.size) result += shape.points[index]
            result
        }
        return shape.copy(
            x1 = p1?.first ?: shape.x1,
            y1 = p1?.second ?: shape.y1,
            x2 = p2?.first ?: shape.x2,
            y2 = p2?.second ?: shape.y2,
            points = points,
        )
    }

    private fun turnAngles(shape: DrawingShape, angleRad: Double): DrawingShape =
        shape.copy(startAngle = shape.startAngle?.let { start -> AngleMath.normalizeRad(start + angleRad) })

    private fun mirrorAngles(shape: DrawingShape, start: (Double) -> Double): DrawingShape = shape.copy(
        startAngle = shape.startAngle?.let { a0 -> AngleMath.normalizeRad(start(a0)) },
        sweepAngle = shape.sweepAngle?.let { sweep -> -sweep },
        bulges = shape.bulges.mapValues { (_, bulge) -> -bulge },
    )
}
