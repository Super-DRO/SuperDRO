package org.superdro.core.ui.readout

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import org.superdro.core.functions.graphics.PositionSlider
import org.superdro.core.functions.graphics.Segment
import org.superdro.core.functions.graphics.Triangle

/**
 * The position slider below a linear axis. The geometry
 * comes from [PositionSlider.geometry] of `core:functions`. The positions are µm `Double`. Only the
 * pixel values of the canvas are `Float`.
 *
 * Colors: the base line and the ticks `onSurfaceVariant`, the marker `onSurface`,
 * and the tool bar `tertiary`. Never `outline`: it has only 1.9 : 1 on `surface` in the dark
 * scheme. The slider is a picture of the value, so it has no semantics of its own.
 *
 * @param positionUm The shown value of the axis (ABS or INC).
 * @param toolSizeUm The tool size: tool diameter / 2 on mill X and Y, else 0.
 * @param toolOffsetUm The tool offset of the axis.
 * @param metric True for the tick interval of 10 mm, false for 0.5 in.
 */
@Composable
fun PositionIndicator(
    positionUm: Double,
    toolSizeUm: Double,
    toolOffsetUm: Double,
    metric: Boolean,
    modifier: Modifier = Modifier,
) {
    val lineColor = MaterialTheme.colorScheme.onSurfaceVariant
    val markerColor = MaterialTheme.colorScheme.onSurface
    val toolColor = MaterialTheme.colorScheme.tertiary
    Canvas(modifier.clearAndSetSemantics {}) {
        val geometry = PositionSlider.geometry(
            widthPx = size.width.toDouble(),
            heightPx = size.height.toDouble(),
            positionUm = positionUm,
            toolSizeUm = toolSizeUm,
            toolOffsetUm = toolOffsetUm,
            metric = metric,
        )
        val stroke = 2.dp.toPx()
        drawSegment(geometry.baseLine, lineColor, stroke)
        drawSegment(geometry.centerTick, lineColor, stroke)
        geometry.ticks.forEach { tick -> drawSegment(tick, lineColor, stroke) }
        geometry.toolBar?.let { bar ->
            drawRect(
                color = toolColor,
                topLeft = Offset(bar.left.toFloat(), bar.top.toFloat()),
                size = Size(bar.width.toFloat(), (bar.bottom - bar.top).toFloat()),
            )
        }
        drawTriangle(geometry.markerLeft, markerColor)
        drawTriangle(geometry.markerRight, markerColor)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSegment(segment: Segment, color: Color, stroke: Float) {
    drawLine(
        color = color,
        start = Offset(segment.x1.toFloat(), segment.y1.toFloat()),
        end = Offset(segment.x2.toFloat(), segment.y2.toFloat()),
        strokeWidth = stroke,
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTriangle(triangle: Triangle, color: Color) {
    val path = Path().apply {
        moveTo(triangle.a.x.toFloat(), triangle.a.y.toFloat())
        lineTo(triangle.b.x.toFloat(), triangle.b.y.toFloat())
        lineTo(triangle.c.x.toFloat(), triangle.c.y.toFloat())
        close()
    }
    drawPath(path, color)
}
