package org.superdro.core.drawing.tool

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.time.Instant
import org.superdro.core.drawing.DrawingConversion
import org.superdro.core.drawing.DrawingImportResult
import org.superdro.core.drawing.DrawingImporter
import org.superdro.core.drawing.DrawingProblem
import org.superdro.core.drawing.DrawingProblemCodes
import org.superdro.core.drawing.dxf.DxfImportSettings
import org.superdro.core.drawing.geometry.Vec
import org.superdro.core.drawing.geometry.bulgeArc
import org.superdro.core.model.DrawingShape
import org.superdro.core.model.DrawingSource
import org.superdro.core.model.ShapeType
import org.superdro.core.model.ToolGeometry
import org.superdro.core.util.Clock
import org.superdro.core.util.MonotonicClock

/**
 * The checks of a cutter drawing. The tool geometry screen shows
 * the text of each check that applies. [message] is the English text; the string key is in
 * [stringKey].
 */
enum class ToolGeometryCheck(val stringKey: String, val message: String) {
    TooManyItems(
        "drawing_tool_geometry_too_many_items",
        "This drawing has far too much in it to be a tool. It may be a drawing of the part rather than of the cutter.",
    ),
    UnlikelySize(
        "drawing_tool_geometry_unlikely_size",
        "The size below doesn't look like a cutting tool's. Check which units the drawing was made in.",
    ),
    NotClosed(
        "drawing_tool_geometry_not_closed",
        "The outline isn't closed, so the tool will show as a line drawing instead of a solid shape.",
    ),
    OpenSegments(
        "drawing_tool_geometry_open_segments",
        "Part of this outline doesn't join up. The solid shape is missing that part of the tool.",
    ),
    OriginOffEdge(
        "drawing_tool_geometry_origin_off_edge",
        "The origin doesn't look like it's on the cutting edge. The X and Z offsets are measured from it.",
    ),
    NoUnits(
        "drawing_tool_geometry_no_units",
        "The drawing doesn't say which units it uses — check the size below.",
    ),
}

/** The result of [ToolGeometryImport.check]. Lengths are µm. */
data class ToolGeometryReport(
    val checks: List<ToolGeometryCheck>,
    val widthUm: Double,
    val heightUm: Double,
    val closedOutlines: Int,
    val openChains: Int,
)

/** The result of [ToolGeometryImport.import]. */
sealed interface ToolGeometryImportResult {
    /** The drawing gives a cutter geometry. [report] has the checks for the screen. */
    data class Imported(val geometry: ToolGeometry, val report: ToolGeometryReport, val warnings: List<DrawingProblem>) : ToolGeometryImportResult

    /**
     * The file gives no geometry: [message] is the text for the screen (for example "This drawing
     * couldn't be read. Try importing it again."), and [problems] is the technical list.
     */
    data class Failed(val message: String, val problems: List<DrawingProblem>) : ToolGeometryImportResult
}

/**
 * The import of a cutter geometry from a drawing file. The import uses the same type test
 * and converters as the drawing
 * import. SuperDRO has no Plus gate.
 */
object ToolGeometryImport {

    /** A drawing with more shapes than this gives [ToolGeometryCheck.TooManyItems]. */
    const val MAX_TOOL_SHAPES: Int = 500

    /** The smallest and the largest normal size of a cutter: 1 mm and 400 mm (in µm). */
    const val MIN_SIZE_UM: Double = 1000.0
    const val MAX_SIZE_UM: Double = 400_000.0

    /** Two end points nearer than this (nm) join. */
    const val JOIN_TOLERANCE_NM: Double = 10_000.0

    /** The origin is on the cutting edge when it is this near to the outline: max(1000 µm, 5 % of the size). */
    const val ORIGIN_MIN_DISTANCE_UM: Double = 1000.0
    const val ORIGIN_SIZE_FRACTION: Double = 0.05

    /** Imports the file content [bytes] as the cutter geometry of a lathe tool. */
    fun import(
        bytes: ByteArray,
        fileName: String,
        drawnIn: Double = ToolGeometryCodec.DRAWN_IN_MM,
        settings: DxfImportSettings = DxfImportSettings(),
        clock: Clock = MonotonicClock(),
        now: Instant = kotlin.time.Clock.System.now(),
    ): ToolGeometryImportResult {
        return when (val result = DrawingImporter.import(bytes, settings, clock, now)) {
            is DrawingImportResult.Imported -> {
                val document = result.document
                val geometry = ToolGeometry(
                    shapes = DrawingConversion.toShapes(document),
                    source = DrawingSource(fileName, result.format),
                    drawnIn = drawnIn,
                    layers = DrawingConversion.toLayers(document),
                )
                val unitsAssumed = result.warnings.any { warning -> warning.code == DrawingProblemCodes.UNITS_ASSUMED }
                ToolGeometryImportResult.Imported(geometry, check(geometry, unitsAssumed), result.warnings)
            }
            is DrawingImportResult.NotADrawing -> ToolGeometryImportResult.Failed(result.message, emptyList())
            is DrawingImportResult.TooLarge -> ToolGeometryImportResult.Failed(result.message, emptyList())
            is DrawingImportResult.NoShapes -> ToolGeometryImportResult.Failed(result.message, result.warnings)
            is DrawingImportResult.NotImported -> ToolGeometryImportResult.Failed(ToolGeometryCodec.UNREADABLE_MESSAGE, result.problems)
        }
    }

    /**
     * Gives the checks of [geometry] with its factor `drawnIn`. [unitsAssumed] is true when the
     * converter had no unit for the drawing (the warning `UNITS_ASSUMED`).
     */
    fun check(geometry: ToolGeometry, unitsAssumed: Boolean = false): ToolGeometryReport {
        val factor = geometry.drawnIn
        val paths = geometry.shapes.flatMap { shape -> flatten(shape) }.map { path ->
            Path(path.points.map { point -> point * factor }, path.closed)
        }
        val checks = ArrayList<ToolGeometryCheck>()
        if (geometry.shapes.size > MAX_TOOL_SHAPES) checks += ToolGeometryCheck.TooManyItems
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (path in paths) for (point in path.points) {
            minX = min(minX, point.x)
            minY = min(minY, point.y)
            maxX = max(maxX, point.x)
            maxY = max(maxY, point.y)
        }
        val widthUm = if (paths.isEmpty()) 0.0 else (maxX - minX) / 1000.0
        val heightUm = if (paths.isEmpty()) 0.0 else (maxY - minY) / 1000.0
        val sizeUm = max(widthUm, heightUm)
        if (sizeUm < MIN_SIZE_UM || sizeUm > MAX_SIZE_UM) checks += ToolGeometryCheck.UnlikelySize
        val (closed, open) = assemble(paths)
        if (closed.isEmpty()) {
            checks += ToolGeometryCheck.NotClosed
        } else if (open.isNotEmpty()) {
            checks += ToolGeometryCheck.OpenSegments
        }
        val origin = Vec(0.0, 0.0)
        val inside = closed.any { loop -> contains(loop, origin) }
        if (!inside && paths.isNotEmpty()) {
            val nearest = min(
                closed.minOfOrNull { loop -> distance(loop, origin, true) } ?: Double.POSITIVE_INFINITY,
                open.minOfOrNull { chain -> distance(chain, origin, false) } ?: Double.POSITIVE_INFINITY,
            )
            val distanceUm = nearest / 1000.0
            if (distanceUm > max(ORIGIN_MIN_DISTANCE_UM, ORIGIN_SIZE_FRACTION * sizeUm)) checks += ToolGeometryCheck.OriginOffEdge
        }
        if (unitsAssumed) checks += ToolGeometryCheck.NoUnits
        return ToolGeometryReport(checks.sortedBy { check -> check.ordinal }, widthUm, heightUm, closed.size, open.size)
    }

    /** A flat path in nm. */
    private class Path(val points: List<Vec>, val closed: Boolean)

    /** Flattens one shape to paths of points: arcs in steps of 5° or less, Bézier segments in 16 steps. */
    private fun flatten(shape: DrawingShape): List<Path> {
        val points = shape.points.chunked(2).filter { pair -> pair.size == 2 }.map { (x, y) -> Vec(x.toDouble(), y.toDouble()) }
        return when (shape.type) {
            ShapeType.Point -> emptyList()
            ShapeType.Line -> {
                if (shape.x1 == null || shape.y1 == null || shape.x2 == null || shape.y2 == null) return emptyList()
                listOf(Path(listOf(Vec(shape.x1!!.toDouble(), shape.y1!!.toDouble()), Vec(shape.x2!!.toDouble(), shape.y2!!.toDouble())), false))
            }
            ShapeType.Circle -> {
                val r = shape.radius?.toDouble() ?: return emptyList()
                val center = Vec(shape.x1?.toDouble() ?: 0.0, shape.y1?.toDouble() ?: 0.0)
                listOf(Path(arcPoints(center, r, 0.0, 2 * PI).dropLast(1), true))
            }
            ShapeType.Arc -> {
                val r = shape.radius?.toDouble() ?: return emptyList()
                val center = Vec(shape.x1?.toDouble() ?: 0.0, shape.y1?.toDouble() ?: 0.0)
                listOf(Path(arcPoints(center, r, shape.startAngle ?: 0.0, shape.sweepAngle ?: 0.0), false))
            }
            ShapeType.Polyline -> {
                if (points.size < 2) return emptyList()
                val out = ArrayList<Vec>()
                val segments = if (shape.closed) points.size else points.size - 1
                for (index in 0 until segments) {
                    val a = points[index]
                    val b = points[(index + 1) % points.size]
                    val bulge = shape.bulges[index] ?: 0.0
                    if (out.isEmpty()) out += a
                    if (bulge == 0.0 || a == b) {
                        out += b
                    } else {
                        val arc = bulgeArc(a, b, bulge)
                        out += arcPoints(arc.center, arc.u.x, arc.start, arc.sweep).drop(1)
                    }
                }
                if (shape.closed && out.size > 1) out.removeAt(out.size - 1)
                listOf(Path(out, shape.closed))
            }
            ShapeType.Bezier -> {
                if (points.size < 4) return emptyList()
                val out = ArrayList<Vec>()
                out += points[0]
                var index = 0
                while (index + 3 < points.size) {
                    val p0 = points[index]
                    val p1 = points[index + 1]
                    val p2 = points[index + 2]
                    val p3 = points[index + 3]
                    for (step in 1..16) {
                        val t = step / 16.0
                        val s = 1 - t
                        out += p0 * (s * s * s) + p1 * (3 * s * s * t) + p2 * (3 * s * t * t) + p3 * (t * t * t)
                    }
                    index += 3
                }
                val closed = shape.closed || (out.size > 2 && (out.first() - out.last()).length <= JOIN_TOLERANCE_NM)
                if (closed && out.size > 1) out.removeAt(out.size - 1)
                listOf(Path(out, closed))
            }
        }
    }

    private fun arcPoints(center: Vec, radius: Double, start: Double, sweep: Double): List<Vec> {
        val steps = max(1, ceil(abs(sweep) / (PI / 36)).toInt())
        return (0..steps).map { step ->
            val angle = start + sweep * step / steps
            Vec(center.x + radius * cos(angle), center.y + radius * sin(angle))
        }
    }

    /** Joins the open paths at their end points. Gives the closed loops and the open chains. */
    private fun assemble(paths: List<Path>): Pair<List<List<Vec>>, List<List<Vec>>> {
        val closed = paths.filter { path -> path.closed && path.points.size >= 3 }.map { path -> path.points }.toMutableList()
        val pending = paths.filter { path -> !path.closed && path.points.size >= 2 }.map { path -> path.points.toMutableList() }.toMutableList()
        val open = ArrayList<List<Vec>>()
        fun near(a: Vec, b: Vec) = (a - b).length <= JOIN_TOLERANCE_NM
        while (pending.isNotEmpty()) {
            val chain = pending.removeAt(pending.size - 1)
            var grown = true
            while (grown && !near(chain.first(), chain.last())) {
                grown = false
                val iterator = pending.listIterator()
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
            if (chain.size >= 3 && near(chain.first(), chain.last())) closed += chain.dropLast(1) else open += chain
        }
        return closed to open
    }

    /** The ray test of the point [point] in the polygon [loop]. */
    private fun contains(loop: List<Vec>, point: Vec): Boolean {
        var inside = false
        var j = loop.size - 1
        for (i in loop.indices) {
            val a = loop[i]
            val b = loop[j]
            if ((a.y > point.y) != (b.y > point.y) && point.x < (b.x - a.x) * (point.y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = i
        }
        return inside
    }

    /** The distance (nm) from [point] to the nearest segment of [chain]. A [loop] also has the segment from the last point to the first. */
    private fun distance(chain: List<Vec>, point: Vec, loop: Boolean): Double {
        if (chain.size == 1) return (chain[0] - point).length
        var best = Double.POSITIVE_INFINITY
        for (index in 0 until chain.size - 1) best = min(best, segmentDistance(chain[index], chain[index + 1], point))
        if (loop) best = min(best, segmentDistance(chain.last(), chain.first(), point))
        return best
    }

    private fun segmentDistance(a: Vec, b: Vec, point: Vec): Double {
        val ab = b - a
        val length2 = ab dot ab
        if (length2 == 0.0) return (point - a).length
        val t = (((point - a) dot ab) / length2).coerceIn(0.0, 1.0)
        return (a + ab * t - point).length
    }
}
