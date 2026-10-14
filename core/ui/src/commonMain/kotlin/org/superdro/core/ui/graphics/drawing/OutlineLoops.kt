package org.superdro.core.ui.graphics.drawing

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
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
        val open = ArrayList<List<Vec2>>()
        shapes.forEach { shape ->
            val (points, closed) = flatten(shape) ?: return@forEach
            val model = points.map { point -> point * scale + offsetUm }
            if (closed) loops += model else open += model
        }
        loops += join(open, JOIN_TOLERANCE_UM * factor)
        return loops
    }

    /**
     * Joins the open chains [open] into closed loops. Two end points join when their distance is
     * [tolerance] or less.
     *
     * The rules of the join:
     * - A new chain starts with the last chain of [open] that is not used.
     * - The chain grows at its two ends until it closes or no chain touches it. Each step uses the
     *   first chain of [open] (the lowest index) that touches an end of the chain. The four checks of
     *   that chain are in this order: its first point at the last point of the chain, its last point
     *   at the last point, its last point at the first point, its first point at the first point.
     * - A chain with more than two points whose ends meet is a loop, without its last point.
     *
     * A grid of square cells holds the end points. Thus each step looks only at the end points in
     * the cells near an end of the chain (one to four cells), and the join is O(n).
     */
    internal fun join(open: List<List<Vec2>>, tolerance: Double): List<List<Vec2>> {
        val loops = ArrayList<List<Vec2>>()
        fun near(a: Vec2, b: Vec2) = a.distanceTo(b) <= tolerance
        val grid = EndGrid(tolerance, open.size * 2)
        open.forEachIndexed { index, chain ->
            grid.add(chain.first(), index)
            grid.add(chain.last(), index)
        }
        val used = BooleanArray(open.size)
        var top = open.size - 1
        while (true) {
            while (top >= 0 && used[top]) top--
            if (top < 0) break
            used[top] = true
            val chain = ArrayDeque(open[top])
            while (!(chain.size > 2 && near(chain.first(), chain.last()))) {
                val index = grid.lowestNear(chain.last(), chain.first(), used) ?: break
                val other = open[index]
                when {
                    near(chain.last(), other.first()) -> for (i in 1 until other.size) chain.addLast(other[i])
                    near(chain.last(), other.last()) -> for (i in other.size - 2 downTo 0) chain.addLast(other[i])
                    near(chain.first(), other.last()) -> for (i in other.size - 2 downTo 0) chain.addFirst(other[i])
                    else -> for (i in 1 until other.size) chain.addFirst(other[i])
                }
                used[index] = true
            }
            if (chain.size > 2 && near(chain.first(), chain.last())) loops += chain.toList().dropLast(1)
        }
        return loops
    }

    /**
     * The end points of the open chains in square cells. The side of a cell is two times the
     * tolerance, so the square of the tolerance about a point touches one to four cells.
     */
    private class EndGrid(private val tolerance: Double, capacity: Int) {
        private val size = if (tolerance > 0.0) 2.0 * tolerance else 1.0
        private val cells = HashMap<Long, IntArrayList>()
        private val points = arrayOfNulls<Vec2>(capacity)
        private val owners = IntArray(capacity)
        private var count = 0

        fun add(point: Vec2, index: Int) {
            val entry = count++
            points[entry] = point
            owners[entry] = index
            cells.getOrPut(key(cell(point.x), cell(point.y))) { IntArrayList() }.add(entry)
        }

        /** Gives the lowest index of a chain that is not [used] with an end point near [a] or [b], or null. */
        fun lowestNear(a: Vec2, b: Vec2, used: BooleanArray): Int? {
            val lowest = lowestNear(b, used, lowestNear(a, used, Int.MAX_VALUE))
            return lowest.takeIf { value -> value != Int.MAX_VALUE }
        }

        private fun lowestNear(target: Vec2, used: BooleanArray, start: Int): Int {
            var lowest = start
            // The margin of the range is for the rounding of the division.
            val x0 = cell(target.x - tolerance, -MARGIN)
            val x1 = cell(target.x + tolerance, MARGIN)
            val y0 = cell(target.y - tolerance, -MARGIN)
            val y1 = cell(target.y + tolerance, MARGIN)
            var cx = x0
            while (cx <= x1) {
                var cy = y0
                while (cy <= y1) {
                    val entries = cells[key(cx, cy)]
                    if (entries != null) {
                        for (i in 0 until entries.size) {
                            val entry = entries[i]
                            val index = owners[entry]
                            if (index < lowest && !used[index] && target.distanceTo(points[entry]!!) <= tolerance) lowest = index
                        }
                    }
                    cy++
                }
                cx++
            }
            return lowest
        }

        private fun cell(value: Double, margin: Double = 0.0): Long = floor(value / size + margin).toLong()

        /** Two cells can have the same key. This is not an error: the distance check finds the correct points. */
        private fun key(cx: Long, cy: Long): Long = cx * 73_856_093L xor cy * 19_349_663L
    }

    /** A small growable list of Int with no boxing. */
    private class IntArrayList {
        private var values = IntArray(2)
        var size: Int = 0
            private set

        fun add(value: Int) {
            if (size == values.size) values = values.copyOf(size * 2)
            values[size++] = value
        }

        operator fun get(index: Int): Int = values[index]
    }

    /** The margin of a cell range in cells. */
    private const val MARGIN: Double = 1e-6

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
