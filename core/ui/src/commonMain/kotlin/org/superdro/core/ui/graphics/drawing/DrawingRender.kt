package org.superdro.core.ui.graphics.drawing

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import org.superdro.core.drawing.DrawingStrokeStyles
import org.superdro.core.drawing.StrokeColorRole
import org.superdro.core.drawing.StrokeStyle
import org.superdro.core.functions.graphics.Vec2
import org.superdro.core.model.DrawingShape
import org.superdro.core.ui.graphics.render.ShapePaths
import org.superdro.core.ui.theme.accentColor

/**
 * The colors of the three roles of the stroke styles: the shape
 * color, the construction color, and the accent color of the selected shape.
 */
@Immutable
data class DrawingStrokeColors(val shape: Color, val construction: Color, val accent: Color) {
    /** Gives the color of [role]. */
    fun of(role: StrokeColorRole): Color = when (role) {
        StrokeColorRole.Shape -> shape
        StrokeColorRole.Construction -> construction
        StrokeColorRole.Accent -> accent
    }
}

/** Gives the stroke colors of the theme: `onSurface`, `onSurfaceVariant`, and the accent color. */
@Composable
fun drawingStrokeColors(): DrawingStrokeColors {
    val scheme = MaterialTheme.colorScheme
    return DrawingStrokeColors(shape = scheme.onSurface, construction = scheme.onSurfaceVariant, accent = accentColor())
}

/**
 * The paths of a list of drawing shapes in model µm, one path for each stroke style 0 to 5.
 * The points of the point shapes are a separate list, because a point mark has a
 * constant size on the screen. Make it one time for each change of the shapes.
 *
 * @param factor The unit factor of a cutter geometry (`ToolGeometry.drawnIn`), else 1.
 * @param offsetUm The model position of the drawing origin.
 * @param selected The list position of the selected shape, or null. It gets its own path.
 */
class DrawingPaths(
    shapes: List<DrawingShape>,
    factor: Double = 1.0,
    offsetUm: Vec2 = Vec2.ZERO,
    selected: Int? = null,
    visible: (DrawingShape) -> Boolean = { true },
) {
    /** The path of each stroke style. */
    val byStyle: Map<Int, Path>

    /** The point shapes: the model position and the stroke style. */
    val points: List<Pair<Vec2, Int>>

    /** The path of the selected shape, or null. */
    val selectedPath: Path?

    /** The model position of the selected point shape, or null. */
    val selectedPoint: Vec2?

    init {
        val styles = HashMap<Int, Path>()
        val marks = ArrayList<Pair<Vec2, Int>>()
        var selectedShapePath: Path? = null
        var selectedShapePoint: Vec2? = null
        shapes.forEachIndexed { index, shape ->
            if (!visible(shape)) return@forEachIndexed
            val style = DrawingStrokeStyles.of(shape.style).style
            val point = ShapePaths.pointOf(shape, factor, offsetUm)
            if (index == selected) {
                if (point != null) selectedShapePoint = point else selectedShapePath = ShapePaths.modelPath(listOf(shape), factor, offsetUm)
            }
            if (point != null) {
                marks += point to style
            } else {
                ShapePaths.appendShape(styles.getOrPut(style) { Path() }, shape, factor, offsetUm)
            }
        }
        byStyle = styles
        points = marks
        selectedPath = selectedShapePath
        selectedPoint = selectedShapePoint
    }
}

/** The half size of the mark of a point shape, in dp. */
const val POINT_MARK_DP: Float = 4f

/**
 * Draws [paths] with the six stroke styles. [matrix] maps model
 * µm to the screen pixels. [alpha] (0 to 1) multiplies the alpha of each style: the display pane
 * gives 1, 0.5 (dimmed), or 0.
 */
fun DrawScope.drawDrawing(paths: DrawingPaths, matrix: Matrix, colors: DrawingStrokeColors, alpha: Float = 1f) {
    if (alpha <= 0f) return
    paths.byStyle.forEach { (style, path) ->
        val screen = ShapePaths.screenPath(path, matrix)
        drawPath(screen, styleColor(DrawingStrokeStyles.of(style), colors, alpha), style = stroke(DrawingStrokeStyles.of(style)))
    }
    paths.points.forEach { (point, style) ->
        drawPointMark(matrix.map(Offset(point.x.toFloat(), point.y.toFloat())), styleColor(DrawingStrokeStyles.of(style), colors, alpha), DrawingStrokeStyles.of(style))
    }
    val selectedStyle = DrawingStrokeStyles.SELECTED
    paths.selectedPath?.let { path ->
        drawPath(ShapePaths.screenPath(path, matrix), styleColor(selectedStyle, colors, alpha), style = stroke(selectedStyle))
    }
    paths.selectedPoint?.let { point ->
        drawPointMark(matrix.map(Offset(point.x.toFloat(), point.y.toFloat())), styleColor(selectedStyle, colors, alpha), selectedStyle)
    }
}

private fun styleColor(style: StrokeStyle, colors: DrawingStrokeColors, alpha: Float): Color =
    colors.of(style.color).copy(alpha = (style.alpha / 255f) * alpha)

private fun DrawScope.stroke(style: StrokeStyle): Stroke {
    val width = style.widthDp?.let { dp -> (dp * density).toFloat() } ?: 1f
    val on = style.dashOnDp
    val off = style.dashOffDp
    val effect = if (on != null && off != null) PathEffect.dashPathEffect(floatArrayOf((on * density).toFloat(), (off * density).toFloat())) else null
    return Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect)
}

private fun DrawScope.drawPointMark(center: Offset, color: Color, style: StrokeStyle) {
    val half = POINT_MARK_DP * density
    val width = style.widthDp?.let { dp -> (dp * density).toFloat() } ?: 1f
    drawLine(color, Offset(center.x - half, center.y - half), Offset(center.x + half, center.y + half), strokeWidth = width, cap = StrokeCap.Round)
    drawLine(color, Offset(center.x - half, center.y + half), Offset(center.x + half, center.y - half), strokeWidth = width, cap = StrokeCap.Round)
}
