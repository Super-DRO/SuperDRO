package org.superdro.core.functions.graphics

import kotlin.math.abs
import org.superdro.core.model.AxisId
import org.superdro.core.model.Guide
import org.superdro.core.model.MachineMode
import org.superdro.core.model.MachineType
import org.superdro.core.model.Plane
import org.superdro.core.model.ProximityTarget
import org.superdro.core.model.SubDatum

/** A new tool offset of one axis. */
data class ToolOffsetChange(val axis: AxisId, val offsetUm: Double)

/**
 * The automatic functions at a selected guide.
 *
 * In SuperDRO, the selected item is also the active sub-datum. Thus the
 * incremental value of the guide axis is the distance to the guide.
 */
object AutoGuideActions {

    /**
     * Gives the automatic tool offset when [guide] becomes the selected item (mill, plane `XY`):
     * axis X if the guide has X, else Y. The sign is −1 if the absolute position is more than
     * the guide value, else +1. The tool offset is `sign × D / 2`. Gives null in other planes, on
     * a lathe, and for a guide on Z.
     *
     * @param absolutePositionUm The absolute position of the guide axis.
     */
    fun toolOffset(
        guide: Guide,
        plane: Plane,
        machineType: MachineType,
        absolutePositionUm: Double,
        toolDiameterUm: Double,
    ): ToolOffsetChange? {
        if (machineType.isLathe || plane != Plane.XY) return null
        val axis = if (guide.axis == AxisId.X) AxisId.X else if (guide.axis == AxisId.Y) AxisId.Y else return null
        val sign = if (absolutePositionUm > guide.valueUm) -1.0 else 1.0
        return ToolOffsetChange(axis, sign * toolDiameterUm / 2.0)
    }

    /**
     * Gives the automatic proximity target when [guide] becomes the selected item: the axis of
     * the guide, the target 0, in incremental mode.
     */
    fun proximityTarget(guide: Guide): ProximityTarget =
        ProximityTarget(axis = guide.axis, targetUm = 0.0, absolute = false)
}

/** The automatic functions at a selected point. The selected point is the active sub-datum. */
object AutoPointActions {

    /**
     * Gives the automatic proximity target when the point [pointId] becomes the selected item:
     * the two axes of [plane], each with the target 0 (the distance to the point on that axis).
     */
    fun proximityTarget(plane: Plane, pointId: String): ProximityTarget =
        ProximityTarget(
            axis = PlaneAxes.horizontal(plane),
            targetUm = 0.0,
            absolute = false,
            secondAxis = PlaneAxes.vertical(plane),
            pointId = pointId,
        )
}

/** An edge of the Z plunge detector. */
sealed interface PlungeEdge {
    /** Z went down by the hysteresis below its highest value [maxUm]. */
    data class Down(val maxUm: Double) : PlungeEdge

    /** Z came up by the hysteresis above its lowest value [minUm]. */
    data class Up(val minUm: Double) : PlungeEdge
}

/**
 * The Z plunge detector with a hysteresis. The input is the
 * absolute Z position. At the start, the detector looks for a
 * down edge.
 */
class PlungeDetector(private val hysteresisUm: Double = HYSTERESIS_UM) {

    private var started = false
    private var lookForDown = true
    private var max = 0.0
    private var min = 0.0

    /** Adds the absolute Z position [zUm]. Gives the edge that this sample makes, or null. */
    fun update(zUm: Double): PlungeEdge? {
        if (!zUm.isFinite()) return null
        if (!started) {
            started = true
            max = zUm
            min = zUm
            return null
        }
        if (lookForDown) {
            if (zUm > max) max = zUm
            if (max - zUm >= hysteresisUm) {
                lookForDown = false
                min = zUm
                return PlungeEdge.Down(max)
            }
        } else {
            if (zUm < min) min = zUm
            if (zUm - min >= hysteresisUm) {
                lookForDown = true
                max = zUm
                return PlungeEdge.Up(min)
            }
        }
        return null
    }

    /** Puts the detector back to the start state. */
    fun reset() {
        started = false
        lookForDown = true
    }

    companion object {
        /** The hysteresis of the detector: 10000 µm (10 mm). */
        const val HYSTERESIS_UM: Double = 10_000.0
    }
}

/**
 * A plunge that the [PlungeWatcher] accepted.
 *
 * @property bottomZUm The lowest absolute Z position of the plunge: the bottom of the hole.
 */
data class Plunge(val bottomZUm: Double)

/**
 * Watches the plunges for the automatic zero and the automatic sub-datum. A down edge
 * keeps the X and Y position and arms the watcher. An up edge disarms it. The plunge is valid
 * only if X and Y each moved [XY_LIMIT_UM] or less since the down edge.
 *
 * The watcher uses the absolute Z position, not the raw count.
 * The absolute position has the axis sign of the machine profile (`InputConfig.sign`), so a
 * plunge is always a move to a lower Z. The raw count direction of the scale does not change the
 * result.
 */
class PlungeWatcher(hysteresisUm: Double = PlungeDetector.HYSTERESIS_UM) {

    private val detector = PlungeDetector(hysteresisUm)
    private var armed = false
    private var startX = 0.0
    private var startY = 0.0

    /**
     * Adds one sample of the absolute positions [zUm], [xUm], and [yUm]. Gives the plunge when
     * this sample completes a valid plunge, else null.
     *
     * [lowestZUm] is the lowest absolute Z since the last sample. The display shows only some
     * samples, so a fast move can have a lower Z between two shown samples. When [lowestZUm] is
     * lower than [zUm], the watcher first adds it as a sample at the same X and Y. Thus the
     * bottom of the plunge is the true bottom. NaN (or the default) adds no sample.
     */
    fun update(zUm: Double, xUm: Double, yUm: Double, lowestZUm: Double = zUm): Plunge? {
        // The two samples give one plunge at most: after an up edge, the higher zUm gives no edge.
        val lower = if (lowestZUm < zUm) step(lowestZUm, xUm, yUm) else null
        return step(zUm, xUm, yUm) ?: lower
    }

    private fun step(zUm: Double, xUm: Double, yUm: Double): Plunge? {
        when (val edge = detector.update(zUm)) {
            is PlungeEdge.Down -> {
                startX = xUm
                startY = yUm
                armed = true
            }
            is PlungeEdge.Up -> {
                if (!armed) return null
                armed = false
                if (abs(xUm - startX) <= XY_LIMIT_UM && abs(yUm - startY) <= XY_LIMIT_UM) return Plunge(edge.minUm)
            }
            null -> Unit
        }
        return null
    }

    /** Puts the watcher back to the start state. */
    fun reset() {
        detector.reset()
        armed = false
    }

    companion object {
        /** The largest X or Y motion during a valid plunge. */
        const val XY_LIMIT_UM: Double = 250.0

        /**
         * True when the plunge functions run: a mill, not the measurement mode, and the automatic
         * zero or the automatic sub-datum is on.
         */
        fun isActive(machineType: MachineType, machineMode: MachineMode, automaticZero: Boolean, automaticSubDatum: Boolean): Boolean =
            !machineType.isLathe && machineMode != MachineMode.Measurement && (automaticZero || automaticSubDatum)
    }
}

/** The position of an automatic sub-datum. [zUm] is null when Z does not apply. */
data class AutoSubDatumPoint(val xUm: Double, val yUm: Double, val zUm: Double?) {

    /** Gives the new sub-datum with the name `Point <n>` for the next ordinal [ordinal]. */
    fun toSubDatum(ordinal: Int): SubDatum = SubDatum(name = "Point $ordinal", x = xUm, y = yUm, z = zUm)
}

/**
 * The actions after a valid plunge.
 *
 * - Automatic zero: `i(0)` on X and Y ([AUTO_ZERO_AXES]).
 * - Automatic sub-datum: a sub-datum at (X, Y). When Z applies, Z is the lowest absolute Z of the
 *   plunge, that is the bottom of the hole. No point when a
 *   sub-datum is already within 250 µm.
 */
object AutoPlungeActions {

    /** The axes of the automatic zero. */
    val AUTO_ZERO_AXES: List<AxisId> = listOf(AxisId.X, AxisId.Y)

    /** The distance on each axis that makes an old sub-datum the same point. */
    const val SAME_POINT_LIMIT_UM: Double = 250.0

    /** True when Z applies to a new sub-datum: Z is on, and the machine is a lathe or 3D sub-datums are on. */
    fun zApplies(zEnabled: Boolean, machineType: MachineType, use3dSubDatums: Boolean): Boolean =
        zEnabled && (machineType.isLathe || use3dSubDatums)

    /**
     * Gives the automatic sub-datum of [plunge]. Gives null when a sub-datum of [existing] is
     * within [SAME_POINT_LIMIT_UM] on X and Y. When Z applies and the old sub-datum has Z, the
     * test also uses Z.
     *
     * @param xUm The absolute X position now.
     * @param yUm The absolute Y position now.
     */
    fun autoSubDatum(
        plunge: Plunge,
        xUm: Double,
        yUm: Double,
        zApplies: Boolean,
        existing: List<SubDatum>,
    ): AutoSubDatumPoint? {
        val z = if (zApplies) plunge.bottomZUm else null
        val duplicate = existing.any { subDatum ->
            val sx = subDatum.x
            val sy = subDatum.y
            if (sx == null || sy == null) return@any false
            if (abs(sx - xUm) > SAME_POINT_LIMIT_UM || abs(sy - yUm) > SAME_POINT_LIMIT_UM) return@any false
            val sz = subDatum.z
            z == null || sz == null || abs(sz - z) <= SAME_POINT_LIMIT_UM
        }
        return if (duplicate) null else AutoSubDatumPoint(xUm, yUm, z)
    }
}
