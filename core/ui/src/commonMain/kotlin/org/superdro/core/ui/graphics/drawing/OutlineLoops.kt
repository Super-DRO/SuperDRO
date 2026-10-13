package org.superdro.core.ui.graphics.drawing

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import org.superdro.core.functions.drawing.ShapeGeometry
import org.superdro.core.functions.graphics.Vec2
import org.superdro.core.model.DrawingShape
import org.superdro.core.model.ShapeType

/**
 * The fill of a cutter outline: the closed loops of the shapes, for the display
 * only. A loop can come from one closed shape, or from open shapes whose end points meet. The
 * checks of the outline (closed, open parts) stay in `core:drawing` (`ToolGeometryImport.check`).
 */
object OutlineLoops {

    /** Two end points nearer than this (µm of the drawing) join. The value of `ToolGeometryImport`. */
    const val JOIN_TOLERANCE_UM: Double = 10.0

    /** The largest angle of one step of a flattened arc: 5°. */
    private const val ARC_STEP_RAD: Double = PI / 36.0

    /** The steps of one Bézier segment. */
    private const val BEZIER_STEPS: Int = 16

    /** Gives the closed loops of [shapes] in model µm. [factor] is the unit factor (`drawnIn`). */
    fun loops(shapes: List<DrawingShape>, factor: Double = 1.0, offsetUm: Vec2 = Vec2.ZERO): List<List<Vec2>> {
        val scale = factor / NM_PER_UM
        val loops = ArrayList<List<Vec2>>()
        val open = ArrayList<MutableList<Vec2>>()
        shapes.forEach { shape ->
            val (points, closed) = flatten(shape) ?: return@forEach
            val model = points.map { point -> point * scale + offsetUm }
            if (closed) loops += model else open += model.toMutableList()
        }
        val tolerance = JOIN_TOLERANCE_UM * factor
        fun near(a: Vec2, b: Vec2) = a.distanceTo(b) <= tolerance
        while (open.isNotEmpty()) {
            val chain = open.removeAt(open.size - 1)
            var grown = true
            while (grown && !(chain.size > 2 && near(chain.first(), chain.last()))) {
                grown = false
                val iterator = open.listIterator()
                while (iterator.hasNext()) {
                    val other = iterator.next()
                    when {
                        near(chain.last(), other.first()) -> chain.addAll(other.drop(1))
                        near(chain.last(), other.last()) -> chain.addAll(other.reversed().drop(1))
                        near(chain.first(), other.last()) -> chain.addAll(0, other.dropLast(1))
                        near(chain.first(), other.first()) -> chain.addAll(0, other.reversed().dropLast(1))
                        else -> continue
                    }
                    iterator.remove()
                    grown = true
                    break
                }
            }
            if (chain.size > 2 && near(chain.first(), chain.last())) loops += chain.dropLast(1)
        }
        return loops
    }

    /** Gives the path of [loops] with the even-odd fill, in the units of the loops. */
    fun fillPath(loops: List<List<Vec2>>): Path = Path().apply {
        fillType = PathFillType.EvenOdd
        loops.filter { loop -> loop.size >= 3 }.forEach { loop ->
            moveTo(loop[0].x.toFloat(), loop[0].y.toFloat())
            for (index in 1 until loop.size) lineTo(loop[index].x.toFloat(), loop[index].y.toFloat())
            close()
        }
    }

    /** Gives the points of [shape] in nm and true for a closed shape, or null for a point. */
    private fun flatten(shape: DrawingShape): Pair<List<Vec2>, Boolean>? {
        val first = Vec2((shape.x1 ?: 0L).toDouble(), (shape.y1 ?: 0L).toDouble())
        return when (shape.type) {
            ShapeType.Point -> null
            ShapeType.Line -> listOf(first, Vec2((shape.x2 ?: 0L).toDouble(), (shape.y2 ?: 0L).toDouble())) to false
            ShapeType.Circle -> arc(first, (shape.radius ?: 0L).toDouble(), 0.0, 2.0 * PI).dropLast(1) to true
            ShapeType.Arc -> arc(first, (shape.radius ?: 0L).toDouble(), shape.startAngle ?: 0.0, shape.sweepAngle ?: 0.0) to false
            ShapeType.Polyline -> {
                val vertices = ShapeGeometry.vertices(shape)
                if (vertices.size < 2) return null
                val out = ArrayList<Vec2>()
                out += vertices[0]
                val count = if (shape.closed) vertices.size else vertices.size - 1
                for (index in 0 until count) {
                    val a = vertices[index]
                    val b = vertices[(index + 1) % vertices.size]
                    val bulge = shape.bulges[index] ?: 0.0
                    val curve = if (bulge == 0.0) null else ShapeGeometry.bulgeArc(a, b, bulge)
                    if (curve == null) out += b else out += arc(curve.center, curve.radius, curve.startAngle, curve.sweepAngle).drop(1)
                }
                if (shape.closed && out.size > 1) out.removeAt(out.size - 1)
                out to shape.closed
            }
            ShapeType.Bezier -> {
                val p = ShapeGeometry.vertices(shape)
                if (p.size < 4) return null
                val out = ArrayList<Vec2>()
                out += p[0]
                var index = 0
                while (index + 3 < p.size) {
                    for (step in 1..BEZIER_STEPS) {
                        val t = step.toDouble() / BEZIER_STEPS
                        val s = 1.0 - t
                        out += p[index] * (s * s * s) + p[index + 1] * (3 * s * s * t) + p[index + 2] * (3 * s * t * t) + p[index + 3] * (t * t * t)
                    }
                    index += 3
                }
                if (shape.closed && out.size > 1) out.removeAt(out.size - 1)
                out to shape.closed
            }
        }
    }

    private fun arc(center: Vec2, radius: Double, start: Double, sweep: Double): List<Vec2> {
        val steps = max(1, ceil(abs(sweep) / ARC_STEP_RAD).toInt())
        return (0..steps).map { step ->
            val angle = start + sweep * step / steps
            Vec2(center.x + radius * cos(angle), center.y + radius * sin(angle))
        }
    }

    private const val NM_PER_UM: Double = 1000.0
}
