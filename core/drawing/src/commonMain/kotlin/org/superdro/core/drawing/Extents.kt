package org.superdro.core.drawing

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import org.superdro.core.drawing.geometry.Vec
import org.superdro.core.drawing.geometry.bulgeArc
import org.superdro.core.drawing.geometry.normalizeAngle

/** A box of doubles that grows with each point. */
internal class Bounds {
    var minX = Double.POSITIVE_INFINITY
        private set
    var minY = Double.POSITIVE_INFINITY
        private set
    var maxX = Double.NEGATIVE_INFINITY
        private set
    var maxY = Double.NEGATIVE_INFINITY
        private set

    val isEmpty: Boolean get() = minX > maxX

    fun add(x: Double, y: Double) {
        if (x < minX) minX = x
        if (x > maxX) maxX = x
        if (y < minY) minY = y
        if (y > maxY) maxY = y
    }

    fun add(point: NmPoint) = add(point.x.toDouble(), point.y.toDouble())

    /**
     * Adds the points of the circle arc with [center], [radius], [start], and [sweep] (radians):
     * the axis points in the sweep, and the two end points when [ends] is true.
     */
    fun addArc(center: Vec, radius: Double, start: Double, sweep: Double, ends: Boolean = true) {
        val from = if (sweep >= 0) start else start + sweep
        val size = kotlin.math.abs(sweep)
        if (ends) {
            add(center.x + radius * cos(from), center.y + radius * sin(from))
            add(center.x + radius * cos(from + size), center.y + radius * sin(from + size))
        }
        val first = normalizeAngle(from)
        var quarter = floor(first / (PI / 2)) * (PI / 2)
        while (quarter < first) quarter += PI / 2
        while (quarter <= first + size + 1e-12) {
            add(center.x + radius * cos(quarter), center.y + radius * sin(quarter))
            quarter += PI / 2
        }
    }

    fun addEntity(entity: WireframeEntity) {
        when (entity) {
            is WireframeLine -> {
                add(entity.start)
                add(entity.end)
            }
            is WireframePoint -> add(entity.point)
            is WireframeCircle -> {
                val r = entity.radius.toDouble()
                add(entity.center.x - r, entity.center.y - r)
                add(entity.center.x + r, entity.center.y + r)
            }
            is WireframeArc -> addArc(
                Vec(entity.center.x.toDouble(), entity.center.y.toDouble()),
                entity.radius.toDouble(),
                entity.start,
                entity.sweep,
            )
            is WireframePolyline -> {
                entity.points.forEach { point -> add(point) }
                val count = if (entity.closed) entity.points.size else entity.points.size - 1
                for ((index, bulge) in entity.bulges) {
                    if (bulge == 0.0 || index < 0 || index >= count) continue
                    val a = entity.points[index]
                    val b = entity.points[(index + 1) % entity.points.size]
                    if (a == b) continue
                    val arc = bulgeArc(Vec(a.x.toDouble(), a.y.toDouble()), Vec(b.x.toDouble(), b.y.toDouble()), bulge)
                    addArc(arc.center, arc.u.x, arc.start, arc.sweep, ends = false)
                }
            }
            is WireframeBezier -> entity.points.forEach { point -> add(point) }
        }
    }

    /** Gives the extents in nm, or null for an empty box. */
    fun toExtents(): WireframeExtents? {
        if (isEmpty) return null
        return WireframeExtents(NmPoint(low(minX), low(minY)), NmPoint(high(maxX), high(maxY)))
    }

    /** Rounds down; a value within 0.001 nm of an integer gives that integer. */
    private fun low(value: Double): Long {
        val nearest = kotlin.math.round(value)
        return clamp(if (kotlin.math.abs(value - nearest) < 1e-3) nearest else floor(value))
    }

    /** Rounds up; a value within 0.001 nm of an integer gives that integer. */
    private fun high(value: Double): Long {
        val nearest = kotlin.math.round(value)
        return clamp(if (kotlin.math.abs(value - nearest) < 1e-3) nearest else kotlin.math.ceil(value))
    }

    private fun clamp(value: Double): Long = when {
        value >= Long.MAX_VALUE.toDouble() -> Long.MAX_VALUE
        value <= Long.MIN_VALUE.toDouble() -> Long.MIN_VALUE
        else -> value.toLong()
    }
}

/** Gives the extents of [entities], or (0, 0)…(0, 0) when there are none. */
internal fun extentsOf(entities: List<WireframeEntity>): WireframeExtents {
    val bounds = Bounds()
    entities.forEach { entity -> bounds.addEntity(entity) }
    return bounds.toExtents() ?: WireframeExtents(NmPoint(0, 0), NmPoint(0, 0))
}
