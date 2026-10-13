package org.superdro.core.ui.adapter.encoder.map

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.superdro.core.ui.adapter.encoder.EncoderFormat

/**
 * One series of a [MapChart]: points in counts (x = position, y = value).
 *
 * @property line True to join the points with a line, false for single dots.
 */
@Immutable
data class ChartSeries(val points: List<Pair<Double, Double>>, val color: Color, val line: Boolean, val alpha: Float = 1f)

/**
 * The scale of a chart: the ranges of the two axes, with a margin of 5 %. A range of one value
 * gets the width 1 count.
 */
@Immutable
data class ChartScale(val minX: Double, val maxX: Double, val minY: Double, val maxY: Double) {

    /** Gives the x pixel of [value] in a plot of [width] pixels. */
    fun x(value: Double, width: Float): Float = ((value - minX) / (maxX - minX) * width).toFloat()

    /** Gives the y pixel of [value] in a plot of [height] pixels. Larger values are higher. */
    fun y(value: Double, height: Float): Float = (height - (value - minY) / (maxY - minY) * height).toFloat()

    companion object {
        /** Gives the scale of all points of [series], with 0 in the y range. */
        fun of(series: List<ChartSeries>): ChartScale {
            val all = series.flatMap { it.points }
            if (all.isEmpty()) return ChartScale(0.0, 1.0, -1.0, 1.0)
            var minX = all.minOf { it.first }
            var maxX = all.maxOf { it.first }
            var minY = minOf(0.0, all.minOf { it.second })
            var maxY = maxOf(0.0, all.maxOf { it.second })
            if (maxX - minX < 1.0) { minX -= 0.5; maxX += 0.5 }
            if (maxY - minY < 1.0) { minY -= 0.5; maxY += 0.5 }
            val mx = (maxX - minX) * MARGIN
            val my = (maxY - minY) * MARGIN
            return ChartScale(minX - mx, maxX + mx, minY - my, maxY + my)
        }

        private const val MARGIN = 0.05
    }
}

/**
 * A chart of a map in counts as a Compose `Canvas`: the zero line, the series,
 * and the labels of the ranges. [description] is the content description.
 */
@Composable
fun MapChart(
    series: List<ChartSeries>,
    axisColor: Color,
    labelColor: Color,
    description: String,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val scale = ChartScale.of(series)
    Canvas(modifier.semantics { contentDescription = description }) {
        val left = LABEL_WIDTH.dp.toPx()
        val bottom = LABEL_HEIGHT.dp.toPx()
        val width = size.width - left
        val height = size.height - bottom
        if (width <= 0f || height <= 0f) return@Canvas
        val origin = Offset(left, 0f)
        drawLine(axisColor, Offset(left, 0f), Offset(left, height), strokeWidth = 1.dp.toPx())
        drawLine(axisColor, Offset(left, height), Offset(size.width, height), strokeWidth = 1.dp.toPx())
        val zero = scale.y(0.0, height)
        drawLine(axisColor.copy(alpha = 0.5f), Offset(left, zero), Offset(size.width, zero), strokeWidth = 1.dp.toPx())
        series.forEach { item -> drawSeries(item, scale, origin, width, height) }
        val style = TextStyle(color = labelColor, fontSize = 11.sp)
        val all = series.flatMap { it.points }
        if (all.isEmpty()) return@Canvas
        val minX = all.minOf { it.first }
        val maxX = all.maxOf { it.first }
        val minY = all.minOf { it.second }
        val maxY = all.maxOf { it.second }
        val yAt = { value: Double -> (scale.y(value, height) - 7.dp.toPx()).coerceIn(0f, height - 14.dp.toPx()) }
        label(measurer, EncoderFormat.number(maxY, 1), Offset(2.dp.toPx(), yAt(maxY)), style)
        label(measurer, EncoderFormat.number(minY, 1), Offset(2.dp.toPx(), yAt(minY)), style)
        val minText = EncoderFormat.number(minX, 0)
        label(measurer, minText, Offset(left + scale.x(minX, width), height + 2.dp.toPx()), style)
        val maxText = EncoderFormat.number(maxX, 0)
        val maxWidth = measurer.measure(maxText, style).size.width
        label(measurer, maxText, Offset((left + scale.x(maxX, width) - maxWidth).coerceAtLeast(left), height + 2.dp.toPx()), style)
    }
}

private fun DrawScope.drawSeries(series: ChartSeries, scale: ChartScale, origin: Offset, width: Float, height: Float) {
    val color = series.color.copy(alpha = series.alpha)
    val points = series.points.map { (x, y) -> Offset(origin.x + scale.x(x, width), scale.y(y, height)) }
    if (series.line && points.size >= 2) {
        val path = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
    }
    if (series.line && points.size > MAX_LINE_DOTS) return
    val radius = if (series.line) 3.dp.toPx() else 1.5.dp.toPx()
    val step = maxOf(1, points.size / MAX_DOTS)
    points.forEachIndexed { index, point -> if (index % step == 0) drawCircle(color, radius, point) }
}

private fun DrawScope.label(measurer: TextMeasurer, text: String, at: Offset, style: TextStyle) {
    drawText(measurer, text, at, style)
}

private const val LABEL_WIDTH = 48
private const val LABEL_HEIGHT = 18
private const val MAX_DOTS = 4000
private const val MAX_LINE_DOTS = 200
