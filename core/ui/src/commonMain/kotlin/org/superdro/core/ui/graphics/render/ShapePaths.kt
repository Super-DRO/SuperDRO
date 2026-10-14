package org.superdro.core.ui.graphics.render

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import kotlin.math.PI
import org.superdro.core.functions.drawing.ShapeGeometry
import org.superdro.core.functions.graphics.Matrix3
import org.superdro.core.functions.graphics.Vec2
import org.superdro.core.functions.graphics.ViewTransform
import org.superdro.core.model.DrawingShape
import org.superdro.core.model.ShapeType

/**
 * The paths of the drawing shapes for the Compose canvas.
 *
 * A shape has integer nm in the plane coordinates (h, v) of the workspace. A path of this object
 * is in model µm (h, v), with the y axis up. [toScreen] then gives the matrix from model µm to the
 * screen pixels. A copy of the path through that matrix ([Path.transform]) gives the screen path,
 * so that a stroke width stays in screen pixels.
 *
 * A [ShapeType.Point] shape has no path: the layer draws a mark of a constant screen size at
 * [pointOf].
 */
object ShapePaths {

    /**
     * Gives the path of [shapes] in model µm. Each coordinate is `nm × factor / 1000 + offset`.
     *
     * @param factor The unit factor of a tool geometry (`ToolGeometry.drawnIn`), else 1.
     * @param offsetUm The model position of the drawing origin, for example the tool position.
     */
    fun modelPath(shapes: Iterable<DrawingShape>, factor: Double = 1.0, offsetUm: Vec2 = Vec2.ZERO): Path {
        val path = Path()
        shapes.forEach { shape -> appendShape(path, shape, factor, offsetUm) }
        return path
    }

    /** Adds the outline of [shape] to [path] in model µm. A point adds nothing. */
    fun appendShape(path: Path, shape: DrawingShape, factor: Double = 1.0, offsetUm: Vec2 = Vec2.ZERO) {
        fun x(nm: Long?): Float = ((nm ?: 0L) * factor / NM_PER_UM + offsetUm.x).toFloat()
        fun y(nm: Long?): Float = ((nm ?: 0L) * factor / NM_PER_UM + offsetUm.y).toFloat()
        when (shape.type) {
            ShapeType.Point -> Unit
            ShapeType.Line -> {
                path.moveTo(x(shape.x1), y(shape.y1))
                path.lineTo(x(shape.x2), y(shape.y2))
            }
            ShapeType.Circle -> {
                val r = ((shape.radius ?: 0L) * factor / NM_PER_UM).toFloat()
                if (r > 0f) path.addOval(Rect(x(shape.x1) - r, y(shape.y1) - r, x(shape.x1) + r, y(shape.y1) + r))
            }
            ShapeType.Arc -> {
                val r = ((shape.radius ?: 0L) * factor / NM_PER_UM).toFloat()
                if (r > 0f) {
                    val cx = x(shape.x1)
                    val cy = y(shape.y1)
                    path.arcTo(
                        Rect(cx - r, cy - r, cx + r, cy + r),
                        degrees(shape.startAngle ?: 0.0),
                        degrees(shape.sweepAngle ?: 0.0),
                        forceMoveTo = true,
                    )
                }
            }
            ShapeType.Polyline -> {
                val points = ShapeGeometry.vertices(shape)
                if (points.size < 2) return
                path.moveTo(x(points[0].x.toLong()), y(points[0].y.toLong()))
                val count = if (shape.closed) points.size else points.size - 1
                for (index in 0 until count) {
                    val a = points[index]
                    val b = points[(index + 1) % points.size]
                    val bulge = shape.bulges[index] ?: 0.0
                    val arc = if (bulge == 0.0) null else ShapeGeometry.bulgeArc(a, b, bulge)
                    if (arc == null) {
                        path.lineTo(x(b.x.toLong()), y(b.y.toLong()))
                    } else {
                        val cx = (arc.center.x * factor / NM_PER_UM + offsetUm.x).toFloat()
                        val cy = (arc.center.y * factor / NM_PER_UM + offsetUm.y).toFloat()
                        val r = (arc.radius * factor / NM_PER_UM).toFloat()
                        path.arcTo(Rect(cx - r, cy - r, cx + r, cy + r), degrees(arc.startAngle), degrees(arc.sweepAngle), forceMoveTo = false)
                    }
                }
                if (shape.closed) path.close()
            }
            ShapeType.Bezier -> {
                val points = ShapeGeometry.vertices(shape)
                if (points.size < 4) return
                path.moveTo(x(points[0].x.toLong()), y(points[0].y.toLong()))
                var index = 1
                while (index + 2 < points.size) {
                    path.cubicTo(
                        x(points[index].x.toLong()), y(points[index].y.toLong()),
                        x(points[index + 1].x.toLong()), y(points[index + 1].y.toLong()),
                        x(points[index + 2].x.toLong()), y(points[index + 2].y.toLong()),
                    )
                    index += 3
                }
                if (shape.closed) path.close()
            }
        }
    }

    /** Gives the model position (µm) of a point shape, or null for a different shape. */
    fun pointOf(shape: DrawingShape, factor: Double = 1.0, offsetUm: Vec2 = Vec2.ZERO): Vec2? {
        if (shape.type != ShapeType.Point) return null
        return Vec2((shape.x1 ?: 0L) * factor / NM_PER_UM + offsetUm.x, (shape.y1 ?: 0L) * factor / NM_PER_UM + offsetUm.y)
    }

    /**
     * Gives the matrix from model µm (h, v) to screen pixels for [transform]. The optional
     * [displayFrame] (Rotate Part and Align Axes) applies first, in model µm.
     */
    fun toScreen(transform: ViewTransform, displayFrame: Matrix3 = Matrix3.IDENTITY): Matrix =
        composeMatrix(transform.toMatrix() * displayFrame)

    /**
     * Gives the Compose matrix of the plane matrix [m]. A projective matrix (the reference image)
     * keeps its perspective row.
     */
    fun composeMatrix(m: Matrix3): Matrix {
        val matrix = Matrix()
        val v = matrix.values
        v[Matrix.ScaleX] = m.m00.toFloat()
        v[Matrix.SkewX] = m.m01.toFloat()
        v[Matrix.TranslateX] = m.m02.toFloat()
        v[Matrix.SkewY] = m.m10.toFloat()
        v[Matrix.ScaleY] = m.m11.toFloat()
        v[Matrix.TranslateY] = m.m12.toFloat()
        v[Matrix.Perspective0] = m.m20.toFloat()
        v[Matrix.Perspective1] = m.m21.toFloat()
        v[Matrix.Perspective2] = m.m22.toFloat()
        return matrix
    }

    /**
     * Gives a copy of the model [path] in screen pixels through [matrix]. The copy keeps the fill
     * type of [path], because `addPath` does not copy it.
     */
    fun screenPath(path: Path, matrix: Matrix): Path = Path().apply {
        fillType = path.fillType
        addPath(path)
        transform(matrix)
    }

    private fun degrees(rad: Double): Float = (rad * 180.0 / PI).toFloat()

    private const val NM_PER_UM: Double = 1000.0
}
