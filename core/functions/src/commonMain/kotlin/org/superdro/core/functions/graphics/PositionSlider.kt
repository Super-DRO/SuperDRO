package org.superdro.core.functions.graphics

import org.superdro.core.model.AxisId
import org.superdro.core.model.MachineType

/** A line from ([x1], [y1]) to ([x2], [y2]) in pixels. */
data class Segment(val x1: Double, val y1: Double, val x2: Double, val y2: Double)

/** A filled triangle in pixels. */
data class Triangle(val a: Vec2, val b: Vec2, val c: Vec2)

/** A filled rectangle in pixels. */
data class Rect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width: Double get() = right - left
    val centerX: Double get() = (left + right) / 2.0
}

/**
 * The geometry of one position slider.
 *
 * @property markerX The center of the position marker.
 * @property toolBar The tool bar, or null when the tool size is 0.
 */
data class SliderGeometry(
    val baseLine: Segment,
    val centerTick: Segment,
    val ticks: List<Segment>,
    val markerX: Double,
    val markerLeft: Triangle,
    val markerRight: Triangle,
    val toolBar: Rect?,
)

/**
 * The position slider below each linear axis. `W` and `H` are the size of the slider in pixels.
 *
 * | Item | Value |
 * |---|---|
 * | Range | ±50000 µm (±50 mm) |
 * | Tick interval | 10000 µm (metric) or 12700 µm (inch) |
 * | Position | the shown value of the axis (ABS or INC), clamped to ±range |
 * | Tool size | mill X and Y: tool diameter / 2; Z and lathe: 0 |
 * | Base line | from `0.025 W` to `0.975 W` at `y = H/2` |
 * | Center tick | `x = W/2`, from `H/2` to `H` |
 * | Ticks | at `x = W/2 ± (W/2) × (d / range)`, length `0.25 H`, only inside the base line |
 * | Marker | `x = W/2 + (W/2) × clamp(pos / range, −1, 1)`, two triangles below the base line |
 * | Tool bar | width `(toolSize / range) × W`, center `x = W/2 + (W/2) × clamp((pos − toolOffset) / range, −1, 1)`, from `y = H/2 − 8` to `H/2 − 2` |
 */
object PositionSlider {

    const val RANGE_UM: Double = 50_000.0
    const val METRIC_TICK_UM: Double = 10_000.0
    const val INCH_TICK_UM: Double = 12_700.0

    /** The half gap between the two marker triangles, in pixels (a gap of 10 px). */
    const val MARKER_HALF_GAP_PX: Double = 5.0

    /** The top of the tool bar above the base line, in pixels. */
    const val TOOL_BAR_TOP_PX: Double = 8.0

    /** The bottom of the tool bar above the base line, in pixels. */
    const val TOOL_BAR_BOTTOM_PX: Double = 2.0

    /** Gives the tool size of [axis]: tool diameter / 2 on mill X and Y, else 0. */
    fun toolSizeUm(axis: AxisId, machineType: MachineType, toolDiameterUm: Double): Double =
        if (!machineType.isLathe && (axis == AxisId.X || axis == AxisId.Y)) toolDiameterUm / 2.0 else 0.0

    /** Gives the tick interval: 10000 µm in metric mode, 12700 µm in inch mode. */
    fun tickIntervalUm(metric: Boolean): Double = if (metric) METRIC_TICK_UM else INCH_TICK_UM

    /**
     * Gives the geometry of the slider.
     *
     * @param positionUm The shown value of the axis (ABS or INC).
     * @param toolSizeUm The tool size of the axis. The function [PositionSlider.toolSizeUm] gives it.
     * @param toolOffsetUm The tool offset of the axis.
     */
    fun geometry(
        widthPx: Double,
        heightPx: Double,
        positionUm: Double,
        toolSizeUm: Double,
        toolOffsetUm: Double,
        metric: Boolean,
    ): SliderGeometry {
        val w = widthPx
        val h = heightPx
        val centerX = w / 2.0
        val baseY = h / 2.0
        val baseLeft = 0.025 * w
        val baseRight = 0.975 * w
        val interval = tickIntervalUm(metric)
        val ticks = ArrayList<Segment>()
        var step = 1
        while (step * interval <= RANGE_UM) {
            val dx = centerX * (step * interval / RANGE_UM)
            for (x in listOf(centerX - dx, centerX + dx)) {
                if (x >= baseLeft && x <= baseRight) ticks += Segment(x, baseY, x, baseY + 0.25 * h)
            }
            step++
        }
        val position = clampPosition(positionUm)
        val markerX = centerX + centerX * (position / RANGE_UM).coerceIn(-1.0, 1.0)
        val markerTop = baseY + MARKER_HALF_GAP_PX
        val markerBottom = 0.875 * h
        val markerLeft = Triangle(
            Vec2(markerX - h / 2.0, markerTop),
            Vec2(markerX - MARKER_HALF_GAP_PX, markerTop),
            Vec2(markerX - MARKER_HALF_GAP_PX, markerBottom),
        )
        val markerRight = Triangle(
            Vec2(markerX + h / 2.0, markerTop),
            Vec2(markerX + MARKER_HALF_GAP_PX, markerTop),
            Vec2(markerX + MARKER_HALF_GAP_PX, markerBottom),
        )
        val toolBar = if (toolSizeUm > 0.0) {
            val barWidth = toolSizeUm / RANGE_UM * w
            val barCenter = centerX + centerX * ((position - toolOffsetUm) / RANGE_UM).coerceIn(-1.0, 1.0)
            Rect(barCenter - barWidth / 2.0, baseY - TOOL_BAR_TOP_PX, barCenter + barWidth / 2.0, baseY - TOOL_BAR_BOTTOM_PX)
        } else {
            null
        }
        return SliderGeometry(
            baseLine = Segment(baseLeft, baseY, baseRight, baseY),
            centerTick = Segment(centerX, baseY, centerX, h),
            ticks = ticks,
            markerX = markerX,
            markerLeft = markerLeft,
            markerRight = markerRight,
            toolBar = toolBar,
        )
    }

    /** Gives the position clamped to ±[RANGE_UM]. NaN gives 0. */
    fun clampPosition(positionUm: Double): Double = if (positionUm.isNaN()) 0.0 else positionUm.coerceIn(-RANGE_UM, RANGE_UM)
}
