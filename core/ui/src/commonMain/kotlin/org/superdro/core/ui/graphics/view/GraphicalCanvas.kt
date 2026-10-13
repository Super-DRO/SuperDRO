package org.superdro.core.ui.graphics.view

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.MaterialTheme
import org.superdro.core.drawing.DrawingEdits
import org.superdro.core.drawing.DrawingStrokeStyles
import org.superdro.core.drawing.StrokeColorRole
import org.superdro.core.functions.PointUm
import org.superdro.core.functions.graphics.CursorGeometry
import org.superdro.core.functions.graphics.GraphSelection
import org.superdro.core.functions.graphics.GuideOrientation
import org.superdro.core.functions.graphics.Matrix3
import org.superdro.core.functions.graphics.ScreenMetrics
import org.superdro.core.functions.graphics.Vec2
import org.superdro.core.functions.graphics.ViewGuide
import org.superdro.core.functions.graphics.ViewTransform
import org.superdro.core.model.Drawing
import org.superdro.core.ui.graphics.drawing.OutlineLoops
import org.superdro.core.ui.graphics.render.ShapePaths
import org.superdro.core.ui.theme.accentColor

/** The test tag of the canvas of the graphical view. */
const val GRAPHICAL_CANVAS_TAG: String = "graphical-canvas"

/**
 * The reference image layer (layer 1): the bitmap, the stored matrix from image pixels to the
 * machine values of the plane, and the alpha 0, 127, or 255.
 */
@Immutable
data class ReferenceLayer(val bitmap: ImageBitmap, val matrix: Matrix3, val alpha: Int)

/** One measurement point of the measurement points layer, in the view of the plane. */
@Immutable
data class MeasurementMark(val position: PointUm, val suspect: Boolean)

/**
 * The colors of the layers, from the Snapshot color roles.
 */
@Immutable
data class GraphicalColors(
    val background: Color,
    val point: Color,
    val selectedPoint: Color,
    val cursor: Color,
    val cutterFill: Color,
    val cutterLine: Color,
    val guide: Color,
    val shape: Color,
    val construction: Color,
    val accent: Color,
    val origin: Color,
    val originLine: Color,
    val measurement: Color,
    val suspect: Color,
)

/** Gives the colors of the layers of the current theme. */
@Composable
fun graphicalColors(): GraphicalColors {
    val scheme = MaterialTheme.colorScheme
    return GraphicalColors(
        background = scheme.surfaceContainerLowest,
        point = scheme.onSurfaceVariant,
        selectedPoint = scheme.tertiary,
        cursor = scheme.secondary,
        cutterFill = scheme.primaryContainer,
        cutterLine = scheme.onPrimaryContainer,
        guide = scheme.error,
        shape = scheme.onSurface,
        construction = scheme.onSurfaceVariant,
        accent = accentColor(),
        origin = scheme.primary,
        originLine = scheme.onSurface,
        measurement = scheme.tertiary,
        suspect = scheme.error,
    )
}

/** The gestures of the canvas. The view presenter gets them. */
interface GraphicalCanvasEvents {
    fun onSize(widthPx: Double, heightPx: Double)
    fun onTap(position: Vec2)
    fun onDoubleTap()
    fun onLongPress(position: Vec2)
    fun onDrag(dxPx: Double, dyPx: Double)
    fun onPinch(factor: Double, focus: Vec2)
}

/**
 * The canvas of the graphical view. It draws the layers in this order: background, reference image,
 * origin
 * symbol, drawing, ghost points, thread outline, points, measurement points, cursor, tool, ghost
 * guides, guides. It reads only [state], so it draws only when a value changes.
 */
@Composable
fun GraphicalCanvas(
    state: GraphicalViewUiState,
    metrics: ScreenMetrics,
    events: GraphicalCanvasEvents,
    modifier: Modifier = Modifier,
    referenceImage: ReferenceLayer? = null,
    measurementMarks: List<MeasurementMark> = emptyList(),
    description: String? = null,
    transparentBackground: Boolean = false,
    cursorColor: Color? = null,
) {
    val base = graphicalColors()
    // The camera video shows through a transparent background, with its crosshair color.
    val colors = base.copy(
        background = if (transparentBackground) Color.Transparent else base.background,
        cursor = cursorColor ?: base.cursor,
    )
    val drawingPaths = rememberDrawingPaths(state.scene?.drawing)
    Canvas(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { size -> events.onSize(size.width.toDouble(), size.height.toDouble()) }
            .pointerInput(events) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    if (zoom != 1f) events.onPinch(zoom.toDouble(), Vec2(centroid.x.toDouble(), centroid.y.toDouble()))
                    if (pan != Offset.Zero) events.onDrag(pan.x.toDouble(), pan.y.toDouble())
                }
            }
            .pointerInput(events) {
                detectTapGestures(
                    onDoubleTap = { events.onDoubleTap() },
                    onLongPress = { offset -> events.onLongPress(Vec2(offset.x.toDouble(), offset.y.toDouble())) },
                    onTap = { offset -> events.onTap(Vec2(offset.x.toDouble(), offset.y.toDouble())) },
                )
            }
            .semantics { if (description != null) contentDescription = description }
            .testTag(GRAPHICAL_CANVAS_TAG),
    ) {
        drawRect(colors.background)
        val scene = state.scene ?: return@Canvas
        val port = state.viewport ?: return@Canvas
        val transform = port.transform
        val dp = metrics.density.toFloat()
        referenceImage?.let { layer -> drawReferenceImage(layer, transform, scene) }
        drawOrigin(transform, metrics, colors)
        if (scene.drawing != null && scene.drawingAlpha > 0.0) drawDrawing(scene, drawingPaths, transform, metrics, colors)
        if (scene.display.subDatumsShown && scene.display.ghostSubDatumsShown) {
            scene.ghostPoints.forEach { point -> drawPoint(transform.toScreen(point), metrics, colors.point.copy(alpha = GHOST_ALPHA), dp) }
        }
        scene.threadOutline?.let { outline -> drawThreadOutline(outline, transform, colors, dp) }
        if (scene.display.subDatumsShown) drawPoints(scene, transform, metrics, colors, dp)
        measurementMarks.forEach { mark ->
            drawPoint(transform.toScreen(mark.position), metrics, if (mark.suspect) colors.suspect else colors.measurement, dp * 1.5f)
        }
        scene.tool?.let { tool ->
            if (scene.display.crosshairsShown) drawCursor(tool, port.followMode || scene.display.viewLocked, transform, metrics, scene, colors, dp)
            if (scene.display.toolShown) drawTool(scene, transform, colors, dp)
        }
        if (scene.display.guidesShown && scene.display.ghostGuidesShown) {
            scene.ghostGuides.forEach { guide -> drawGuide(guide, transform, colors.guide.copy(alpha = GHOST_ALPHA), dp, dashed = true) }
        }
        if (scene.display.guidesShown) {
            scene.guides.forEach { guide ->
                val selected = scene.selection == GraphSelection.Guide(guide.id)
                drawGuide(guide, transform, colors.guide.copy(alpha = LINE_ALPHA), if (selected) 3f * dp else dp, dashed = false)
            }
        }
    }
}

/** The paths of the drawing in model µm for each stroke style, and the point shapes. */
@Immutable
class DrawingPaths(val byStyle: Map<Int, Path>, val points: List<Pair<Int, Vec2>>, val shapePaths: List<Path?>)

@Composable
private fun rememberDrawingPaths(drawing: Drawing?): DrawingPaths? = remember(drawing) {
    if (drawing == null) return@remember null
    val byStyle = LinkedHashMap<Int, Path>()
    val points = ArrayList<Pair<Int, Vec2>>()
    val shapePaths = ArrayList<Path?>()
    drawing.shapes.forEach { shape ->
        if (!DrawingEdits.isVisible(drawing, shape)) {
            shapePaths += null
            return@forEach
        }
        val point = ShapePaths.pointOf(shape)
        if (point != null) {
            points += shape.style to point
            shapePaths += null
        } else {
            val single = Path()
            ShapePaths.appendShape(single, shape)
            shapePaths += single
            ShapePaths.appendShape(byStyle.getOrPut(shape.style.coerceIn(0, 5)) { Path() }, shape)
        }
    }
    DrawingPaths(byStyle, points, shapePaths)
}

private fun DrawScope.drawReferenceImage(layer: ReferenceLayer, transform: ViewTransform, scene: GraphicalScene) {
    if (layer.alpha <= 0) return
    val matrix = ShapePaths.composeMatrix(transform.imageToScreen(layer.matrix, scene.plane, scene.frameMatrix))
    withTransform({ transform(matrix) }) {
        drawImage(layer.bitmap, alpha = layer.alpha / 255f)
    }
}

private fun DrawScope.drawOrigin(transform: ViewTransform, metrics: ScreenMetrics, colors: GraphicalColors) {
    val center = transform.toScreen(PointUm(0.0, 0.0))
    val radius = ((metrics.xdpi + metrics.ydpi) / 2.0 * ORIGIN_RADIUS_INCH).toFloat()
    val offset = Offset(center.x.toFloat(), center.y.toFloat())
    val box = Rect(offset.x - radius, offset.y - radius, offset.x + radius, offset.y + radius)
    drawArc(colors.origin, startAngle = 0f, sweepAngle = 90f, useCenter = true, topLeft = box.topLeft, size = box.size, style = Fill)
    drawArc(colors.origin, startAngle = 180f, sweepAngle = 90f, useCenter = true, topLeft = box.topLeft, size = box.size, style = Fill)
    drawCircle(colors.originLine, radius = radius, center = offset, style = Stroke(width = 1.5f * metrics.density.toFloat()))
}

private fun DrawScope.drawDrawing(scene: GraphicalScene, paths: DrawingPaths?, transform: ViewTransform, metrics: ScreenMetrics, colors: GraphicalColors) {
    if (paths == null) return
    val matrix = ShapePaths.toScreen(transform, scene.frameMatrix)
    val dp = metrics.density.toFloat()
    val layerAlpha = scene.drawingAlpha.toFloat()
    paths.byStyle.forEach { (style, path) ->
        val look = DrawingStrokeStyles.of(style)
        val color = colorOf(look.color, colors).copy(alpha = look.alpha / 255f * layerAlpha)
        val width = look.widthDp?.let { value -> value.toFloat() * dp } ?: 1f
        val effect = if (look.dashOnDp != null && look.dashOffDp != null) {
            PathEffect.dashPathEffect(floatArrayOf(look.dashOnDp!!.toFloat() * dp, look.dashOffDp!!.toFloat() * dp))
        } else {
            null
        }
        drawPath(ShapePaths.screenPath(path, matrix), color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect))
    }
    paths.points.forEach { (style, point) ->
        val look = DrawingStrokeStyles.of(style)
        val shown = if (scene.displayFrame.isIdentity) point else scene.displayFrame.apply(point)
        val screen = transform.toScreen(shown.toPointUm())
        val color = colorOf(look.color, colors).copy(alpha = look.alpha / 255f * layerAlpha)
        val half = SHAPE_POINT_HALF_DP * dp
        val x = screen.x.toFloat()
        val y = screen.y.toFloat()
        drawLine(color, Offset(x - half, y - half), Offset(x + half, y + half), strokeWidth = dp)
        drawLine(color, Offset(x - half, y + half), Offset(x + half, y - half), strokeWidth = dp)
    }
    val selected = scene.selectedShape?.let { index -> paths.shapePaths.getOrNull(index) }
    if (selected != null) {
        val look = DrawingStrokeStyles.SELECTED
        drawPath(
            ShapePaths.screenPath(selected, matrix),
            colors.accent.copy(alpha = look.alpha / 255f * layerAlpha.coerceAtLeast(0.5f)),
            style = Stroke(width = (look.widthDp ?: 2.5).toFloat() * dp, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

private fun colorOf(role: StrokeColorRole, colors: GraphicalColors): Color = when (role) {
    StrokeColorRole.Shape -> colors.shape
    StrokeColorRole.Construction -> colors.construction
    StrokeColorRole.Accent -> colors.accent
}

private fun DrawScope.drawThreadOutline(outline: ThreadOutline, transform: ViewTransform, colors: GraphicalColors, dp: Float) {
    outline.polylines.forEach { line ->
        if (line.size < 2) return@forEach
        val path = Path()
        line.forEachIndexed { index, point ->
            val screen = transform.toScreen(point)
            if (index == 0) path.moveTo(screen.x.toFloat(), screen.y.toFloat()) else path.lineTo(screen.x.toFloat(), screen.y.toFloat())
        }
        drawPath(path, colors.shape.copy(alpha = SHAPE_ALPHA), style = Stroke(width = dp, join = StrokeJoin.Round))
    }
}

private fun DrawScope.drawPoints(scene: GraphicalScene, transform: ViewTransform, metrics: ScreenMetrics, colors: GraphicalColors, dp: Float) {
    val selected = (scene.selection as? GraphSelection.Point)?.id
    scene.points.filter { point -> point.id != selected }.forEach { point ->
        drawPoint(transform.toScreen(point.position), metrics, colors.point.copy(alpha = LINE_ALPHA), dp)
    }
    scene.points.firstOrNull { point -> point.id == selected }?.let { point ->
        drawPoint(transform.toScreen(point.position), metrics, colors.selectedPoint, 1.5f * dp)
    }
}

/** The point symbol: a cross and a circle of a constant size on the screen. */
private fun DrawScope.drawPoint(screen: Vec2, metrics: ScreenMetrics, color: Color, width: Float) {
    val w = (POINT_SIZE_INCH * metrics.xdpi).toFloat()
    val h = (POINT_SIZE_INCH * metrics.ydpi).toFloat()
    val x = screen.x.toFloat()
    val y = screen.y.toFloat()
    drawLine(color, Offset(x - w / 2f, y), Offset(x + w / 2f, y), strokeWidth = width)
    drawLine(color, Offset(x, y - h / 2f), Offset(x, y + h / 2f), strokeWidth = width)
    drawCircle(color, radius = (w + h) / 8f, center = Offset(x, y), style = Stroke(width = width))
}

private fun DrawScope.drawCursor(
    tool: PointUm,
    follow: Boolean,
    transform: ViewTransform,
    metrics: ScreenMetrics,
    scene: GraphicalScene,
    colors: GraphicalColors,
    dp: Float,
) {
    val alpha = if (scene.display.crosshairsDimmed) LINE_ALPHA / 2f else LINE_ALPHA
    val color = colors.cursor.copy(alpha = alpha)
    val center = transform.toScreen(tool)
    val x = center.x.toFloat()
    val y = center.y.toFloat()
    if (follow) {
        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = dp)
        drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = dp)
        val horizontal = CursorGeometry.rulerTicks(metrics, transform.scale, scene.metric, maxOf(x, size.width - x).toDouble(), metrics.xdpi)
        horizontal.forEach { tick ->
            val half = (tick.lengthPx / 2.0).toFloat()
            listOf(x + tick.offsetPx.toFloat(), x - tick.offsetPx.toFloat()).forEach { tx ->
                drawLine(color, Offset(tx, y - half), Offset(tx, y + half), strokeWidth = dp)
            }
        }
        val vertical = CursorGeometry.rulerTicks(metrics, transform.scale, scene.metric, maxOf(y, size.height - y).toDouble(), metrics.ydpi)
        vertical.forEach { tick ->
            val half = (tick.lengthPx / 2.0).toFloat()
            listOf(y + tick.offsetPx.toFloat(), y - tick.offsetPx.toFloat()).forEach { ty ->
                drawLine(color, Offset(x - half, ty), Offset(x + half, ty), strokeWidth = dp)
            }
        }
    } else {
        val cross = CursorGeometry.smallCrossPx(metrics, transform.scale, scene.metric)
        val hx = (cross.x / 2.0).toFloat()
        val hy = (cross.y / 2.0).toFloat()
        drawLine(color, Offset(x - hx, y), Offset(x + hx, y), strokeWidth = dp)
        drawLine(color, Offset(x, y - hy), Offset(x, y + hy), strokeWidth = dp)
    }
}

private fun DrawScope.drawTool(scene: GraphicalScene, transform: ViewTransform, colors: GraphicalColors, dp: Float) {
    val alpha = if (scene.display.toolDimmed) TOOL_DIMMED_ALPHA else TOOL_ALPHA
    val outline = scene.toolOutline
    if (outline != null) {
        val matrix = ShapePaths.toScreen(transform)
        val anchor = Vec2.of(outline.anchor)
        // Only the closed outlines get the fill; the other shapes are lines.
        val loops = OutlineLoops.loops(outline.geometry.shapes, outline.geometry.drawnIn, anchor)
        if (loops.isNotEmpty()) drawPath(ShapePaths.screenPath(OutlineLoops.fillPath(loops), matrix), colors.cutterFill.copy(alpha = alpha))
        val path = ShapePaths.modelPath(outline.geometry.shapes, outline.geometry.drawnIn, anchor)
        drawPath(ShapePaths.screenPath(path, matrix), colors.cutterLine.copy(alpha = alpha), style = Stroke(width = dp, join = StrokeJoin.Round))
        return
    }
    val cutter = scene.cutter ?: return
    val center = transform.toScreen(cutter.center)
    val radius = transform.umToPxX(cutter.radiusUm).toFloat()
    if (radius <= 0f) return
    val offset = Offset(center.x.toFloat(), center.y.toFloat())
    drawCircle(colors.cutterFill.copy(alpha = alpha), radius = radius, center = offset)
    drawCircle(colors.cutterLine.copy(alpha = alpha), radius = radius, center = offset, style = Stroke(width = dp))
}

private fun DrawScope.drawGuide(guide: ViewGuide, transform: ViewTransform, color: Color, width: Float, dashed: Boolean) {
    val effect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6f * width, 4f * width)) else null
    when (guide.orientation) {
        GuideOrientation.Vertical -> {
            val x = transform.screenX(guide.position).toFloat()
            drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = width, pathEffect = effect)
        }
        GuideOrientation.Horizontal -> {
            val y = transform.screenY(guide.position).toFloat()
            drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = width, pathEffect = effect)
        }
    }
}

/** The alpha of the points, the cursor, and the guides: 200 of 255. */
private const val LINE_ALPHA: Float = 200f / 255f

/** The alpha of the ghost layer: 100 of 255. */
private const val GHOST_ALPHA: Float = 100f / 255f

/** The alpha of the thread outline: 230 of 255, as the shape style 1. */
private const val SHAPE_ALPHA: Float = 230f / 255f

/** The alpha of the tool: 191 normal, 127 dimmed. */
private const val TOOL_ALPHA: Float = 191f / 255f
private const val TOOL_DIMMED_ALPHA: Float = 127f / 255f

/** The side of the point symbol, in inches on the screen. */
private const val POINT_SIZE_INCH: Double = 0.25

/** The radius of the origin symbol, in inches on the screen. */
private const val ORIGIN_RADIUS_INCH: Double = 0.25 / 4.0

/** The half size of the mark of a drawing point, in dp. */
private const val SHAPE_POINT_HALF_DP: Float = 4f
