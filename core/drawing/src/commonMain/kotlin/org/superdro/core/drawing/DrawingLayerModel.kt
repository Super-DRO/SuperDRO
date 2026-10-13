package org.superdro.core.drawing

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import org.superdro.core.drawing.geometry.Vec
import org.superdro.core.drawing.geometry.bulgeArc
import org.superdro.core.drawing.geometry.normalizeAngle
import org.superdro.core.model.Drawing
import org.superdro.core.model.DrawingShape
import org.superdro.core.model.ShapeType

/** The color role of a stroke style. The theme of `core:ui` gives the color of each role. */
enum class StrokeColorRole {
    /** The shape color. */
    Shape,

    /** The construction color. */
    Construction,

    /** The accent color of the theme: the selected shape. */
    Accent,
}

/**
 * The look of one stroke style of the drawing layer. The widths
 * and the dashes are in dp.
 *
 * @property alpha The alpha 0 to 255. The display panel alpha (255, 127, or 0) multiplies it.
 * @property widthDp The stroke width, or null for a line of 1 px (style 0).
 * @property dashOnDp The length of a dash, or null for a solid line.
 * @property dashOffDp The length of a gap, or null for a solid line.
 */
data class StrokeStyle(
    val style: Int,
    val color: StrokeColorRole,
    val alpha: Int,
    val widthDp: Double?,
    val dashOnDp: Double? = null,
    val dashOffDp: Double? = null,
)

/** The six stroke styles 0 to 5 and the style of the selected shape. All strokes have round caps and joins. */
object DrawingStrokeStyles {
    val STYLES: List<StrokeStyle> = listOf(
        StrokeStyle(0, StrokeColorRole.Shape, 230, null),
        StrokeStyle(1, StrokeColorRole.Shape, 230, 1.0),
        StrokeStyle(2, StrokeColorRole.Shape, 230, 2.0),
        StrokeStyle(3, StrokeColorRole.Construction, 120, 0.7),
        StrokeStyle(4, StrokeColorRole.Construction, 230, 1.0, 8.0, 4.0),
        StrokeStyle(5, StrokeColorRole.Shape, 60, 0.8, 4.0, 3.0),
    )

    /** The style of the selected shape. */
    val SELECTED: StrokeStyle = StrokeStyle(-1, StrokeColorRole.Accent, 230, 2.5)

    /** Gives the stroke style [style]. A value out of the range 0 to 5 gives the style 1. */
    fun of(style: Int): StrokeStyle = STYLES.getOrElse(style) { STYLES[1] }
}

/**
 * The edits of the drawing layer: show and hide a layer,
 * delete one shape. "Delete Drawing" removes the [Drawing] of the workspace in `core:store`.
 */
object DrawingEdits {

    /** Gives [drawing] with the layer [layerIndex] hidden or shown. */
    fun withLayerHidden(drawing: Drawing, layerIndex: Int, hidden: Boolean): Drawing =
        drawing.copy(layers = drawing.layers.map { layer -> if (layer.index == layerIndex) layer.copy(hidden = hidden) else layer })

    /** Gives [drawing] with no shape at [shapeIndex]. An index out of the range gives [drawing]. */
    fun withoutShape(drawing: Drawing, shapeIndex: Int): Drawing =
        if (shapeIndex !in drawing.shapes.indices) drawing else drawing.copy(shapes = drawing.shapes.filterIndexed { index, _ -> index != shapeIndex })

    /** True when the layer of [shape] is shown. A shape on a layer that the list does not have is shown. */
    fun isVisible(drawing: Drawing, shape: DrawingShape): Boolean =
        drawing.layers.firstOrNull { layer -> layer.index == shape.layerIndex }?.hidden != true
}

/** The selection of a shape in the graphical view: the nearest shown shape to a point. */
object DrawingHitTest {

    /**
     * Gives the index of the shape of [drawing] that is nearest to ([x], [y]) (nm), or null when
     * no shown shape is within [toleranceNm].
     */
    fun nearestShape(drawing: Drawing, x: Long, y: Long, toleranceNm: Double): Int? {
        val point = Vec(x.toDouble(), y.toDouble())
        var best: Int? = null
        var bestDistance = toleranceNm
        drawing.shapes.forEachIndexed { index, shape ->
            if (!DrawingEdits.isVisible(drawing, shape)) return@forEachIndexed
            val distance = distance(shape, point)
            if (distance <= bestDistance) {
                best = index
                bestDistance = distance
            }
        }
        return best
    }

    /** The distance (nm) from [point] to [shape]. */
    internal fun distance(shape: DrawingShape, point: Vec): Double {
        val points = shape.points.chunked(2).filter { pair -> pair.size == 2 }.map { (px, py) -> Vec(px.toDouble(), py.toDouble()) }
        val first = Vec((shape.x1 ?: 0).toDouble(), (shape.y1 ?: 0).toDouble())
        return when (shape.type) {
            ShapeType.Point -> (point - first).length
            ShapeType.Line -> segment(first, Vec((shape.x2 ?: 0).toDouble(), (shape.y2 ?: 0).toDouble()), point)
            ShapeType.Circle -> abs((point - first).length - (shape.radius ?: 0).toDouble())
            ShapeType.Arc -> arc(first, (shape.radius ?: 0).toDouble(), shape.startAngle ?: 0.0, shape.sweepAngle ?: 0.0, point)
            ShapeType.Polyline -> {
                if (points.size < 2) return Double.POSITIVE_INFINITY
                val count = if (shape.closed) points.size else points.size - 1
                (0 until count).minOf { index ->
                    val a = points[index]
                    val b = points[(index + 1) % points.size]
                    val bulge = shape.bulges[index] ?: 0.0
                    if (bulge == 0.0 || a == b) {
                        segment(a, b, point)
                    } else {
                        val conic = bulgeArc(a, b, bulge)
                        arc(conic.center, conic.u.x, conic.start, conic.sweep, point)
                    }
                }
            }
            ShapeType.Bezier -> {
                if (points.size < 4) return Double.POSITIVE_INFINITY
                var bestDistance = Double.POSITIVE_INFINITY
                var index = 0
                while (index + 3 < points.size) {
                    var previous = points[index]
                    for (step in 1..32) {
                        val t = step / 32.0
                        val s = 1 - t
                        val next = points[index] * (s * s * s) + points[index + 1] * (3 * s * s * t) +
                            points[index + 2] * (3 * s * t * t) + points[index + 3] * (t * t * t)
                        bestDistance = min(bestDistance, segment(previous, next, point))
                        previous = next
                    }
                    index += 3
                }
                bestDistance
            }
        }
    }

    private fun segment(a: Vec, b: Vec, point: Vec): Double {
        val ab = b - a
        val length2 = ab dot ab
        if (length2 == 0.0) return (point - a).length
        val t = (((point - a) dot ab) / length2).coerceIn(0.0, 1.0)
        return (a + ab * t - point).length
    }

    private fun arc(center: Vec, radius: Double, start: Double, sweep: Double, point: Vec): Double {
        val from = if (sweep >= 0) start else start + sweep
        val size = abs(sweep)
        val angle = normalizeAngle(atan2(point.y - center.y, point.x - center.x) - from)
        if (angle <= size || size >= 2 * PI) return abs((point - center).length - radius)
        val a = center + Vec(radius * kotlin.math.cos(from), radius * kotlin.math.sin(from))
        val b = center + Vec(radius * kotlin.math.cos(from + size), radius * kotlin.math.sin(from + size))
        return min(hypot(point.x - a.x, point.y - a.y), hypot(point.x - b.x, point.y - b.y))
    }
}
