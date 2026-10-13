package org.superdro.core.functions.drawing

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sin
import org.superdro.core.functions.graphics.Vec2
import org.superdro.core.model.DrawingShape
import org.superdro.core.model.Plane
import org.superdro.core.model.ShapeType
import org.superdro.core.model.SubDatum

/** The kind of a snap point. */
enum class SnapKind { CircleCenter, ArcCenter, NamedPoint, Corner }

/**
 * A snap point of a drawing, in integer nm.
 *
 * @property name The name of a named point, or null.
 */
data class SnapPoint(val kind: SnapKind, val x: Long, val y: Long, val name: String? = null)

/**
 * A group of snap points with its two controls.
 *
 * @property diameterUm The diameter key of a hole group (the diameter rounded to 1 µm), else null.
 * @property shown "Show on drawing": the preview shows the marks, and a tap can pick them.
 * @property createSubDatums "Create as sub-datums": "Apply" adds the points as sub-datums.
 */
data class SnapGroup(
    val kind: SnapKind,
    val diameterUm: Long?,
    val points: List<SnapPoint>,
    val shown: Boolean = true,
    val createSubDatums: Boolean = false,
)

/**
 * The snap points of a drawing and the sub-datums from snap points. All values are in nm.
 */
object SnapPoints {

    /** The cell size of the hash grid of the end points, and the cluster limit: 1 µm. */
    const val CLUSTER_NM: Long = 1000L

    /** The largest angle between two end directions of a corner: 170° (2.9670597 rad). */
    const val CORNER_ANGLE_RAD: Double = 2.9670597

    /** Corners are not shown by default when there are more than this count. */
    const val MAX_SHOWN_CORNERS: Int = 32

    /** The tap tolerance of a pick on the screen, in dp. */
    const val PICK_TOLERANCE_DP: Double = 24.0

    /**
     * Gives the snap point groups of [shapes] with their default controls. The groups come in
     * this order: arc centers, corners, named points, then one group for each hole diameter
     * from the smallest. A group with no point is not in the list.
     *
     * Defaults: "Named points" has "Create as sub-datums" on. The corners are not shown when
     * there are more than [MAX_SHOWN_CORNERS]. A named point has the name of its shape
     * ([DrawingShape.name]).
     */
    fun extract(shapes: List<DrawingShape>): List<SnapGroup> {
        val holes = HashMap<Long, MutableList<SnapPoint>>()
        val arcs = ArrayList<SnapPoint>()
        val named = ArrayList<SnapPoint>()
        for (shape in shapes) {
            val x = shape.x1
            val y = shape.y1
            when (shape.type) {
                ShapeType.Circle -> if (x != null && y != null) {
                    val key = diameterKeyUm(shape.radius ?: 0L)
                    holes.getOrPut(key) { ArrayList() } += SnapPoint(SnapKind.CircleCenter, x, y)
                }
                ShapeType.Arc -> if (x != null && y != null) arcs += SnapPoint(SnapKind.ArcCenter, x, y)
                ShapeType.Point -> if (x != null && y != null) named += SnapPoint(SnapKind.NamedPoint, x, y, shape.name)
                else -> Unit
            }
        }
        val corners = corners(shapes)
        val groups = ArrayList<SnapGroup>()
        if (arcs.isNotEmpty()) groups += SnapGroup(SnapKind.ArcCenter, null, arcs)
        if (corners.isNotEmpty()) groups += SnapGroup(SnapKind.Corner, null, corners, shown = corners.size <= MAX_SHOWN_CORNERS)
        if (named.isNotEmpty()) groups += SnapGroup(SnapKind.NamedPoint, null, named, createSubDatums = true)
        for (diameter in holes.keys.sorted()) groups += SnapGroup(SnapKind.CircleCenter, diameter, holes.getValue(diameter))
        return groups
    }

    /** Gives the diameter key of a circle: `(2r + 500) ÷ 1000` µm with integer division. */
    fun diameterKeyUm(radiusNm: Long): Long = (2 * radiusNm + 500) / 1000

    /**
     * Gives the corners of [shapes]:
     *
     * - Each open end gives a candidate with its direction away from the end point. Candidates
     *   within 1 µm in X and in Y make one cluster. A cluster with 3 or more ends is a corner. A
     *   cluster with 2 ends is a corner only if the angle between the two directions is less
     *   than 170°.
     * - An inner vertex of a polyline or a Bézier chain is a corner when the angle between its
     *   two side directions is less than 170°. Bulge segments use the arc tangents.
     * - A corner closer than 1 µm in X and Y to a previous corner is dropped.
     */
    fun corners(shapes: List<DrawingShape>): List<SnapPoint> {
        val ends = ArrayList<End>()
        val inner = ArrayList<SnapPoint>()
        for (shape in shapes) collect(shape, ends, inner)
        val clusters = cluster(ends)
        val result = ArrayList<SnapPoint>()
        fun addCorner(x: Long, y: Long) {
            if (result.none { corner -> abs(corner.x - x) < CLUSTER_NM && abs(corner.y - y) < CLUSTER_NM }) {
                result += SnapPoint(SnapKind.Corner, x, y)
            }
        }
        for (cluster in clusters) {
            val isCorner = when {
                cluster.size >= 3 -> true
                cluster.size == 2 -> isSharp(cluster[0].direction, cluster[1].direction)
                else -> false
            }
            if (isCorner) addCorner(cluster[0].x, cluster[0].y)
        }
        for (corner in inner) addCorner(corner.x, corner.y)
        return result
    }

    /**
     * Gives the nearest snap point of the shown groups within [toleranceNm] of ([x], [y]), or
     * null. The caller converts the tap tolerance of [PICK_TOLERANCE_DP] to nm.
     */
    fun pick(groups: List<SnapGroup>, x: Double, y: Double, toleranceNm: Double): SnapPoint? {
        var best: SnapPoint? = null
        var bestDistance = Double.POSITIVE_INFINITY
        for (group in groups) {
            if (!group.shown) continue
            for (point in group.points) {
                val distance = Vec2(point.x.toDouble(), point.y.toDouble()).distanceTo(Vec2(x, y))
                if (distance <= toleranceNm && distance < bestDistance) {
                    best = point
                    bestDistance = distance
                }
            }
        }
        return best
    }

    /**
     * Gives the group name, which becomes the tag of the sub-datums: `Hole_<diameter>mm` (3
     * decimals, end zeros removed), `Arc_Center`, `Corner`, `Point`.
     */
    fun groupName(group: SnapGroup): String = when (group.kind) {
        SnapKind.CircleCenter -> "Hole_${millimeters(group.diameterUm ?: 0L)}mm"
        SnapKind.ArcCenter -> "Arc_Center"
        SnapKind.Corner -> "Corner"
        SnapKind.NamedPoint -> "Point"
    }

    /**
     * Gives the name of the sub-datum of the point at the 1-based [index] of [group]. The names
     * are `⌀<diameter> Hole <n>`, `Arc Center <n>`, `Corner <n>`, and the point name or
     * `Point <n>`.
     */
    fun subDatumName(group: SnapGroup, point: SnapPoint, index: Int): String = when (group.kind) {
        SnapKind.CircleCenter -> "⌀${millimeters(group.diameterUm ?: 0L)} Hole $index"
        SnapKind.ArcCenter -> "Arc Center $index"
        SnapKind.Corner -> "Corner $index"
        SnapKind.NamedPoint -> point.name?.takeIf { name -> name.isNotBlank() } ?: "Point $index"
    }

    /**
     * Gives a tag for [name] that is not in [existingTags]. The test ignores case. The function
     * adds `_2`, `_3`, … when the name is already a tag.
     */
    fun uniqueTag(name: String, existingTags: Collection<String>): String {
        val taken = existingTags.map { tag -> tag.lowercase() }.toSet()
        if (name.lowercase() !in taken) return name
        var n = 2
        while ("${name}_$n".lowercase() in taken) n++
        return "${name}_$n"
    }

    /**
     * Gives the sub-datums of the groups with "Create as sub-datums" on. The group name becomes the
     * tag of each point. The coordinates are µm (nm ÷ 1000).
     *
     * | Plane | Sub-datum coordinates |
     * |---|---|
     * | `XY` | X = h, Y = v |
     * | `XZ` | X = h, Z = v |
     * | `YZ` | Y = h, Z = v |
     * | `ZX` (lathe) | X = −v, Z = h |
     *
     * @param existingTags The tags of the workspace.
     */
    fun toSubDatums(groups: List<SnapGroup>, plane: Plane, existingTags: Collection<String>): List<SubDatum> {
        val tags = existingTags.toMutableList()
        val result = ArrayList<SubDatum>()
        for (group in groups) {
            if (!group.createSubDatums || group.points.isEmpty()) continue
            val tag = uniqueTag(groupName(group), tags)
            tags += tag
            group.points.forEachIndexed { index, point ->
                val h = point.x / 1000.0
                val v = point.y / 1000.0
                val name = subDatumName(group, point, index + 1)
                result += when (plane) {
                    Plane.XY -> SubDatum(name = name, tags = listOf(tag), x = h, y = v)
                    Plane.XZ -> SubDatum(name = name, tags = listOf(tag), x = h, z = v)
                    Plane.YZ -> SubDatum(name = name, tags = listOf(tag), y = h, z = v)
                    Plane.ZX -> SubDatum(name = name, tags = listOf(tag), x = zeroFix(-v), z = h)
                }
            }
        }
        return result
    }

    /** Gives µm as mm with 3 decimals and no end zeros, for example 6350 → "6.35". */
    fun millimeters(um: Long): String {
        val negative = um < 0
        val value = abs(um)
        val whole = value / 1000
        val fraction = (value % 1000).toString().padStart(3, '0').trimEnd('0')
        val text = if (fraction.isEmpty()) "$whole" else "$whole.$fraction"
        return if (negative) "-$text" else text
    }

    private fun zeroFix(value: Double): Double = if (value == 0.0) 0.0 else value

    /** An open end: the point and the direction away from it into the shape. */
    private data class End(val x: Long, val y: Long, val direction: Vec2)

    private fun isSharp(a: Vec2, b: Vec2): Boolean {
        if (a.length == 0.0 || b.length == 0.0) return false
        val angle = atan2(abs(a.cross(b)), a.dot(b))
        return angle < CORNER_ANGLE_RAD
    }

    private fun collect(shape: DrawingShape, ends: MutableList<End>, inner: MutableList<SnapPoint>) {
        when (shape.type) {
            ShapeType.Line -> {
                val x1 = shape.x1 ?: return
                val y1 = shape.y1 ?: return
                val x2 = shape.x2 ?: return
                val y2 = shape.y2 ?: return
                val d = Vec2((x2 - x1).toDouble(), (y2 - y1).toDouble())
                ends += End(x1, y1, d)
                ends += End(x2, y2, -d)
            }
            ShapeType.Arc -> {
                val cx = shape.x1 ?: return
                val cy = shape.y1 ?: return
                val r = (shape.radius ?: return).toDouble()
                val a0 = shape.startAngle ?: return
                val sweep = shape.sweepAngle ?: return
                val a1 = a0 + sweep
                val sense = if (sweep >= 0.0) 1.0 else -1.0
                val startTangent = Vec2(-sin(a0), cos(a0)) * sense
                val endTangent = Vec2(-sin(a1), cos(a1)) * sense
                ends += End(cx + (r * cos(a0)).roundToLong(), cy + (r * sin(a0)).roundToLong(), startTangent)
                ends += End(cx + (r * cos(a1)).roundToLong(), cy + (r * sin(a1)).roundToLong(), -endTangent)
            }
            ShapeType.Polyline -> collectChain(polylineSegments(shape), shape.closed, ends, inner)
            ShapeType.Bezier -> collectChain(bezierSegments(shape), shape.closed, ends, inner)
            else -> Unit
        }
    }

    /** One segment of a chain: the end points and the motion directions at the two ends. */
    private data class Segment(val start: Vec2, val end: Vec2, val startTangent: Vec2, val endTangent: Vec2)

    private fun polylineSegments(shape: DrawingShape): List<Segment> {
        val vertices = ShapeGeometry.vertices(shape)
        if (vertices.size < 2) return emptyList()
        val count = if (shape.closed) vertices.size else vertices.size - 1
        return (0 until count).map { index ->
            val start = vertices[index]
            val end = vertices[(index + 1) % vertices.size]
            val (startTangent, endTangent) = ShapeGeometry.bulgeTangents(start, end, shape.bulges[index] ?: 0.0)
            Segment(start, end, startTangent, endTangent)
        }
    }

    private fun bezierSegments(shape: DrawingShape): List<Segment> {
        val p = ShapeGeometry.vertices(shape)
        val result = ArrayList<Segment>()
        var index = 0
        while (index + 3 < p.size) {
            val p0 = p[index]
            val p3 = p[index + 3]
            val start = listOf(p[index + 1] - p0, p[index + 2] - p0, p3 - p0).firstOrNull { d -> d.length > 0.0 } ?: Vec2.ZERO
            val end = listOf(p3 - p[index + 2], p3 - p[index + 1], p3 - p0).firstOrNull { d -> d.length > 0.0 } ?: Vec2.ZERO
            result += Segment(p0, p3, start, end)
            index += 3
        }
        return result
    }

    private fun collectChain(segments: List<Segment>, closed: Boolean, ends: MutableList<End>, inner: MutableList<SnapPoint>) {
        if (segments.isEmpty()) return
        val joined = closed && segments.last().end == segments.first().start
        if (!joined) {
            val first = segments.first()
            val last = segments.last()
            ends += End(first.start.x.roundToLong(), first.start.y.roundToLong(), first.startTangent)
            ends += End(last.end.x.roundToLong(), last.end.y.roundToLong(), -last.endTangent)
        }
        val joints = if (joined) segments.indices else 1 until segments.size
        for (index in joints) {
            val previous = segments[(index - 1 + segments.size) % segments.size]
            val next = segments[index]
            if (isSharp(-previous.endTangent, next.startTangent)) {
                inner += SnapPoint(SnapKind.Corner, next.start.x.roundToLong(), next.start.y.roundToLong())
            }
        }
    }

    private fun cluster(ends: List<End>): List<List<End>> {
        val clusters = ArrayList<MutableList<End>>()
        val grid = HashMap<Pair<Long, Long>, MutableList<Int>>()
        for (end in ends) {
            val cellX = floor(end.x.toDouble() / CLUSTER_NM).toLong()
            val cellY = floor(end.y.toDouble() / CLUSTER_NM).toLong()
            var found: Int? = null
            search@ for (dx in -1L..1L) {
                for (dy in -1L..1L) {
                    for (index in grid[(cellX + dx) to (cellY + dy)].orEmpty()) {
                        val seed = clusters[index][0]
                        if (abs(seed.x - end.x) <= CLUSTER_NM && abs(seed.y - end.y) <= CLUSTER_NM) {
                            found = index
                            break@search
                        }
                    }
                }
            }
            if (found != null) {
                clusters[found] += end
            } else {
                clusters += mutableListOf(end)
                grid.getOrPut(cellX to cellY) { ArrayList() } += clusters.size - 1
            }
        }
        return clusters
    }
}
