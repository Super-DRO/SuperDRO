package org.superdro.core.camera

import kotlin.math.ceil
import kotlin.math.floor

/** The level of a crosshair tick. */
enum class TickLevel(
    /** The tick length on the screen as a fraction of `xdpi`. */
    val lengthOfXdpi: Double,
) {
    Major(0.3),
    Minor(0.12),
    Fine(0.06),
}

/**
 * One tick on each crosshair line, at [offsetPx] from the center (a negative value is to the left
 * or up). [valueUm] is the distance from the center on the work, for the label of a major tick.
 */
data class CrosshairTick(val offsetPx: Double, val level: TickLevel, val valueUm: Double)

/**
 * The crosshair layout in the view.
 *
 * @property halfWidthPx The half length of the horizontal line.
 * @property halfHeightPx The half length of the vertical line.
 * @property ticks The ticks, or an empty list when the scale is not known.
 */
data class CrosshairLayout(
    val centerX: Double,
    val centerY: Double,
    val halfWidthPx: Double,
    val halfHeightPx: Double,
    val ticks: List<CrosshairTick>,
)

/**
 * The crosshairs at the view center. With the view lock on,
 * the lines cross the full view. With the lock off, the cross has the size
 * `0.25 × min(view width, view height)`.
 *
 * The tick intervals are 1 in, 0.1 in, and 0.01 in in inch mode, and 10 mm, 5 mm, and 1 mm in
 * metric mode. A level shows only when its interval is 3 dp or more on the screen (also the major
 * level, so that a small scale does not give thousands of ticks). While the video
 * plays, the ticks use the image scale of the calibration. With no calibration, the
 * video has no ticks, so that the ticks do not show a wrong scale.
 */
object Crosshairs {

    /** The cross size with the lock off, as a fraction of the shorter view side. */
    const val UNLOCKED_SIZE_FRACTION: Double = 0.25

    /** The minimum tick interval on the screen, in dp. */
    const val MIN_TICK_SPACING_DP: Double = 3.0

    /** The tick intervals in µm in metric mode: major, minor, fine. */
    val METRIC_INTERVALS_UM: List<Double> = listOf(10_000.0, 5_000.0, 1_000.0)

    /** The tick intervals in µm in inch mode: major, minor, fine. */
    val INCH_INTERVALS_UM: List<Double> = listOf(25_400.0, 2_540.0, 254.0)

    /**
     * Gives the crosshair layout.
     *
     * @param screenPixelsPerMm The screen pixels for each mm on the work, or null when the scale is
     *   not known (then there are no ticks).
     * @param density The screen pixels for each dp.
     */
    fun layout(
        viewWidth: Double,
        viewHeight: Double,
        locked: Boolean,
        screenPixelsPerMm: Double?,
        metric: Boolean,
        density: Double,
    ): CrosshairLayout {
        val halfWidth: Double
        val halfHeight: Double
        if (locked) {
            halfWidth = viewWidth / 2.0
            halfHeight = viewHeight / 2.0
        } else {
            val half = UNLOCKED_SIZE_FRACTION * minOf(viewWidth, viewHeight) / 2.0
            halfWidth = half
            halfHeight = half
        }
        val ticks = if (screenPixelsPerMm != null && screenPixelsPerMm > 0.0) {
            ticks(maxOf(halfWidth, halfHeight), screenPixelsPerMm, metric, density)
        } else {
            emptyList()
        }
        return CrosshairLayout(viewWidth / 2.0, viewHeight / 2.0, halfWidth, halfHeight, ticks)
    }

    /**
     * Gives the ticks out to [extentPx] on each side of the center. A tick position that a higher
     * level has already does not repeat. The center has no tick.
     */
    fun ticks(extentPx: Double, screenPixelsPerMm: Double, metric: Boolean, density: Double): List<CrosshairTick> {
        val intervals = if (metric) METRIC_INTERVALS_UM else INCH_INTERVALS_UM
        val minSpacingPx = MIN_TICK_SPACING_DP * density
        val result = mutableListOf<CrosshairTick>()
        val taken = mutableSetOf<Long>()
        TickLevel.entries.forEachIndexed { index, level ->
            val intervalUm = intervals[index]
            val intervalPx = intervalUm / 1000.0 * screenPixelsPerMm
            if (intervalPx <= 0.0 || intervalPx < minSpacingPx) return@forEachIndexed
            val count = floor(extentPx / intervalPx).toLong()
            for (step in 1..count) {
                listOf(step, -step).forEach { signed ->
                    val valueUm = signed * intervalUm
                    val id = kotlin.math.round(valueUm * 1000.0).toLong()
                    if (taken.add(id)) result += CrosshairTick(signed * intervalPx, level, valueUm)
                }
            }
        }
        return result.sortedBy { tick -> tick.offsetPx }
    }

    /**
     * The number of major ticks on each side: `ceil(0.9 × width / 2 ÷ interval_px) − 1`.
     */
    fun majorCount(viewWidth: Double, intervalPx: Double): Int =
        if (intervalPx <= 0.0) 0 else (ceil(0.9 * viewWidth / 2.0 / intervalPx) - 1).toInt().coerceAtLeast(0)
}
