package org.superdro.core.ui.functions.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.resources.Res
import org.superdro.core.resources.fn_preview_cd
import org.superdro.core.resources.fn_preview_count
import org.superdro.core.resources.fn_preview_empty
import org.superdro.core.resources.fn_preview_title

/** The test tag of the point preview. */
const val POINT_PREVIEW_TAG: String = "functions-point-preview"

/**
 * One point of the preview, in the two axes of the plane, in µm.
 *
 * @property label A short text next to the point, for example the number of the hole, or null.
 */
@Immutable
data class PreviewPoint(val h: Double, val v: Double, val label: String? = null)

/**
 * The preview of the points before "Create": the points of the pattern in the plane, with
 * the horizontal axis [hAxis] and the vertical axis [vAxis]. The first point has the `tertiary`
 * role (the color of the selected point) and a ring. The other points have `onSurfaceVariant`.
 * The cross at (0, 0) of the plane is the origin of the pattern. The scale is the same on the two
 * axes, so a circle shows as a circle.
 *
 * @param origin The origin of the pattern in the plane, or null for no cross.
 */
@Composable
fun PointPreview(
    points: List<PreviewPoint>,
    hAxis: String,
    vAxis: String,
    modifier: Modifier = Modifier,
    origin: PreviewPoint? = null,
) {
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val description = stringResource(Res.string.fn_preview_cd, points.size)
    val labelStyle = TextStyle(color = colors.onSurfaceVariant, fontSize = 12.sp)
    Surface(
        modifier = modifier.fillMaxSize().testTag(POINT_PREVIEW_TAG).semantics { contentDescription = description },
        color = colors.surfaceContainerLowest,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            Text(
                text = stringResource(Res.string.fn_preview_title) + " · " + stringResource(Res.string.fn_preview_count, points.size),
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceVariant,
            )
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                if (points.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.fn_preview_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                    return@Box
                }
                Canvas(Modifier.fillMaxSize().padding(8.dp)) {
                    val all = points + listOfNotNull(origin)
                    val margin = 28.dp.toPx()
                    val width = size.width - 2 * margin
                    val height = size.height - 2 * margin
                    if (width <= 0f || height <= 0f) return@Canvas
                    val minH = all.minOfOrNull { it.h } ?: 0.0
                    val maxH = all.maxOfOrNull { it.h } ?: 0.0
                    val minV = all.minOfOrNull { it.v } ?: 0.0
                    val maxV = all.maxOfOrNull { it.v } ?: 0.0
                    val spanH = maxH - minH
                    val spanV = maxV - minV
                    val span = max(spanH, spanV).takeIf { it > 0.0 } ?: 1.0
                    val scale = min(width / (if (spanH > 0.0) spanH else span), height / (if (spanV > 0.0) spanV else span))
                    val centerH = (minH + maxH) / 2.0
                    val centerV = (minV + maxV) / 2.0
                    fun toScreen(h: Double, v: Double): Offset = Offset(
                        (size.width / 2 + (h - centerH) * scale).toFloat(),
                        (size.height / 2 - (v - centerV) * scale).toFloat(),
                    )
                    // The axis lines of the plane through the origin of the pattern.
                    val axisColor = colors.onSurfaceVariant.copy(alpha = 0.35f)
                    val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                    val center = origin?.let { toScreen(it.h, it.v) }
                    if (center != null) {
                        drawLine(axisColor, Offset(0f, center.y), Offset(size.width, center.y), strokeWidth = 1.dp.toPx(), pathEffect = dash)
                        drawLine(axisColor, Offset(center.x, 0f), Offset(center.x, size.height), strokeWidth = 1.dp.toPx(), pathEffect = dash)
                        val arm = 10.dp.toPx()
                        drawLine(colors.secondary, center.copy(x = center.x - arm), center.copy(x = center.x + arm), strokeWidth = 2.dp.toPx())
                        drawLine(colors.secondary, center.copy(y = center.y - arm), center.copy(y = center.y + arm), strokeWidth = 2.dp.toPx())
                    }
                    val showLabels = points.size <= MAX_LABELS
                    val radius = if (points.size > 60) 3.dp.toPx() else 5.dp.toPx()
                    points.forEachIndexed { index, point ->
                        val position = toScreen(point.h, point.v)
                        if (index == 0) {
                            drawCircle(colors.tertiary, radius * 1.4f, position)
                            drawCircle(colors.tertiary, radius * 2.4f, position, style = Stroke(width = 2.dp.toPx()))
                        } else {
                            drawCircle(colors.onSurfaceVariant, radius, position)
                        }
                        val label = point.label
                        if (showLabels && label != null) {
                            val layout = measurer.measure(label, labelStyle)
                            // The first point has a ring: its label goes out of the ring.
                            val gap = if (index == 0) radius * 2.8f else radius * 1.6f
                            drawText(layout, topLeft = Offset(position.x + gap, position.y - layout.size.height - gap * 0.4f))
                        }
                    }
                    // The names of the two plane axes at the ends of the area.
                    val hLayout = measurer.measure(hAxis, labelStyle)
                    drawText(hLayout, topLeft = Offset(size.width - hLayout.size.width, size.height - hLayout.size.height))
                    val vLayout = measurer.measure(vAxis, labelStyle)
                    drawText(vLayout, topLeft = Offset(0f, 0f))
                }
            }
        }
    }
}

/** The preview shows the labels of the points only up to this count. */
private const val MAX_LABELS = 36
