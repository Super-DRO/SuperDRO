package org.superdro.core.ui.camera

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToLong
import org.superdro.core.camera.CrosshairLayout
import org.superdro.core.camera.CrosshairTick
import org.superdro.core.camera.Crosshairs
import org.superdro.core.camera.TickLevel

/** The test tag of the crosshairs. */
const val CROSSHAIRS_TAG: String = "camera-crosshairs"

/** The screen pixels for each inch for each dp: the `xdpi` of the tick lengths is 160 dp. */
private const val DP_PER_INCH: Float = 160f

/**
 * The crosshairs at the view center: with the view lock on,
 * the lines cross the full view; with the lock off, the cross has a quarter of the shorter view
 * side. The ticks show only with [screenPixelsPerMm]: the image scale of the calibration times the
 * video scale. The major ticks have a label in mm or in inches.
 *
 * @param color The crosshair color ([CameraVideoUiState.crosshairArgb]).
 * @param screenPixelsPerMm The screen pixels for each mm on the work, or null for no ticks.
 * @param metric True for the metric intervals (10, 5, 1 mm), false for the inch intervals.
 */
@Composable
fun CrosshairOverlay(
    locked: Boolean,
    color: Color,
    screenPixelsPerMm: Double?,
    metric: Boolean,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier.testTag(CROSSHAIRS_TAG)) {
        val layout: CrosshairLayout = Crosshairs.layout(
            viewWidth = size.width.toDouble(),
            viewHeight = size.height.toDouble(),
            locked = locked,
            screenPixelsPerMm = screenPixelsPerMm,
            metric = metric,
            density = density.toDouble(),
        )
        val center = Offset(layout.centerX.toFloat(), layout.centerY.toFloat())
        val stroke = 1.5.dp.toPx()
        val halfWidth = layout.halfWidthPx.toFloat()
        val halfHeight = layout.halfHeightPx.toFloat()
        drawLine(color, Offset(center.x - halfWidth, center.y), Offset(center.x + halfWidth, center.y), stroke)
        drawLine(color, Offset(center.x, center.y - halfHeight), Offset(center.x, center.y + halfHeight), stroke)
        val inch = DP_PER_INCH * density
        val labelStyle = TextStyle(color = color, fontSize = 11.sp)
        layout.ticks.forEach { tick: CrosshairTick ->
            val half = (tick.level.lengthOfXdpi * inch / 2.0).toFloat()
            val offset = tick.offsetPx.toFloat()
            if (abs(offset) <= halfWidth) {
                drawLine(color, Offset(center.x + offset, center.y - half), Offset(center.x + offset, center.y + half), stroke)
            }
            if (abs(offset) <= halfHeight) {
                // Up on the screen is plus on the work.
                drawLine(color, Offset(center.x - half, center.y - offset), Offset(center.x + half, center.y - offset), stroke)
            }
            if (tick.level == TickLevel.Major) {
                val text = tickLabel(tick.valueUm, metric)
                if (abs(offset) <= halfWidth) {
                    drawText(measurer, text, Offset(center.x + offset + 2f, center.y + half), labelStyle)
                }
                if (abs(offset) <= halfHeight) {
                    drawText(measurer, text, Offset(center.x + half + 2f, center.y - offset), labelStyle)
                }
            }
        }
    }
}

/** The label of a major tick: the distance from the center in mm (metric) or inches, as an integer. */
fun tickLabel(valueUm: Double, metric: Boolean): String {
    val unitUm = if (metric) 1_000.0 else 25_400.0
    return (valueUm / unitUm).roundToLong().toString()
}
