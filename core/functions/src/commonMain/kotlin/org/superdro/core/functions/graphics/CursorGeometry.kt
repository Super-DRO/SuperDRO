package org.superdro.core.functions.graphics

import kotlin.math.roundToLong
import org.superdro.core.functions.PointUm
import org.superdro.core.model.MachineType
import org.superdro.core.model.Plane
import org.superdro.core.model.Units

/** The kind of a ruler tick of the follow-mode cursor. */
enum class TickKind { Major, Minor, Fine }

/**
 * One ruler tick. The tick shows at +[offsetPx] and at −[offsetPx] from the cursor center, on
 * the cursor line.
 *
 * @property lengthPx The length of the tick on the screen.
 */
data class RulerTick(val offsetPx: Double, val kind: TickKind, val lengthPx: Double)

/**
 * The cutter circle: center = tool position − tool offset
 * vector, radius = tool diameter / 2.
 */
data class CutterCircle(val center: PointUm, val radiusUm: Double)

/**
 * The geometry of the position cursor and the cutter.
 *
 * Ruler ticks of the follow-mode cursor:
 *
 * | Tick | Inch mode | Metric mode | Length on the screen |
 * |---|---|---|---|
 * | Major | each 1 in | each 10 mm | `0.3 × xdpi` px |
 * | Minor | each 0.1 in | each 5 mm | `0.12 × xdpi` px |
 * | Fine | each 0.01 in | each 1 mm | `0.06 × xdpi` px |
 *
 * Minor and fine ticks show only when their interval is 3 dp or more.
 */
object CursorGeometry {

    /** The length factor of a major tick: `0.3 × xdpi` px. */
    const val MAJOR_LENGTH_INCH: Double = 0.3

    /** The length factor of a minor tick: `0.12 × xdpi` px. */
    const val MINOR_LENGTH_INCH: Double = 0.12

    /** The length factor of a fine tick: `0.06 × xdpi` px. */
    const val FINE_LENGTH_INCH: Double = 0.06

    /** The smallest interval of the minor and fine ticks on the screen, in dp. */
    const val MIN_TICK_INTERVAL_DP: Double = 3.0

    /** The length of the small cross out of follow mode in metric mode: 1 cm in inches. */
    const val METRIC_CROSS_INCH: Double = 10.0 / 25.4

    /**
     * Gives the tick intervals in µm: (major, minor, fine). Inch: 1 in, 0.1 in, 0.01 in. Metric:
     * 10 mm, 5 mm, 1 mm.
     */
    fun tickIntervalsUm(metric: Boolean): Triple<Double, Double, Double> =
        if (metric) Triple(10_000.0, 5_000.0, 1_000.0) else Triple(25_400.0, 2_540.0, 254.0)

    /**
     * Gives the ruler ticks of one cursor line from the center to [halfLengthPx].
     *
     * @param dpiAlong The pixels for each inch along the line: `xdpi` for the horizontal line,
     *   `ydpi` for the vertical line.
     */
    fun rulerTicks(
        metrics: ScreenMetrics,
        scale: Double,
        metric: Boolean,
        halfLengthPx: Double,
        dpiAlong: Double = metrics.xdpi,
    ): List<RulerTick> {
        val (majorUm, minorUm, fineUm) = tickIntervalsUm(metric)
        val pxPerUm = dpiAlong / Units.UM_PER_INCH * scale
        if (!(pxPerUm > 0.0) || !(halfLengthPx > 0.0)) return emptyList()
        val minIntervalPx = metrics.dpToPx(MIN_TICK_INTERVAL_DP)
        val showMinor = minorUm * pxPerUm >= minIntervalPx
        val showFine = fineUm * pxPerUm >= minIntervalPx
        // The step is the smallest interval that shows. The tick at step k gets the largest kind
        // that its distance is a multiple of.
        val stepUm = when {
            showFine -> fineUm
            showMinor -> minorUm
            else -> majorUm
        }
        val stepsPerMajor = (majorUm / stepUm).roundToLong()
        val stepsPerMinor = (minorUm / stepUm).roundToLong()
        val ticks = ArrayList<RulerTick>()
        var k = 1L
        while (true) {
            val offsetPx = k * stepUm * pxPerUm
            if (offsetPx > halfLengthPx) break
            val kind = when {
                k % stepsPerMajor == 0L -> TickKind.Major
                k % stepsPerMinor == 0L -> TickKind.Minor
                else -> TickKind.Fine
            }
            ticks += RulerTick(offsetPx, kind, lengthPx(kind, metrics))
            k++
        }
        return ticks
    }

    /** Gives the length of a tick of [kind] on the screen. */
    fun lengthPx(kind: TickKind, metrics: ScreenMetrics): Double = when (kind) {
        TickKind.Major -> MAJOR_LENGTH_INCH * metrics.xdpi
        TickKind.Minor -> MINOR_LENGTH_INCH * metrics.xdpi
        TickKind.Fine -> FINE_LENGTH_INCH * metrics.xdpi
    }

    /**
     * Gives the line lengths (horizontal, vertical) in px of the small cross out of follow mode.
     * Each line is 1 in, or 1 cm in metric mode, at the model scale: `xdpi × u × s` and
     * `ydpi × u × s`.
     */
    fun smallCrossPx(metrics: ScreenMetrics, scale: Double, metric: Boolean): Vec2 {
        val unitInch = if (metric) METRIC_CROSS_INCH else 1.0
        return Vec2(metrics.xdpi * unitInch * scale, metrics.ydpi * unitInch * scale)
    }

    /**
     * Gives the cutter circle. On a mill in the plane `XY`, the center is the tool position minus
     * the tool offset vector, and the radius is [toolDiameterUm] / 2. In all other cases the
     * center is the tool position and the radius is 0.
     *
     * @param tool The view position of the tool (the absolute position with the tool offset).
     * @param toolOffsetXUm The X tool offset when the X tool offset is on, else 0.
     * @param toolOffsetYUm The Y tool offset when the Y tool offset is on, else 0.
     */
    fun cutter(
        plane: Plane,
        machineType: MachineType,
        tool: PointUm,
        toolOffsetXUm: Double,
        toolOffsetYUm: Double,
        toolDiameterUm: Double,
    ): CutterCircle {
        if (machineType.isLathe || plane != Plane.XY) return CutterCircle(tool, 0.0)
        val radius = if (toolDiameterUm > 0.0) toolDiameterUm / 2.0 else 0.0
        return CutterCircle(PointUm(tool.h - toolOffsetXUm, tool.v - toolOffsetYUm), radius)
    }
}
