package org.superdro.core.functions.patterns

import kotlin.math.abs
import org.superdro.core.functions.PointUm
import org.superdro.core.model.MachineType
import org.superdro.core.model.PatternAngleConvention

/**
 * The inputs of the hole circle. Lengths are µm, angles are arc
 * seconds. A null value is an empty field.
 *
 * @property radiusUm The radius `R`, 0.001 µm or more.
 * @property holeCount The hole count `n`, 2 to 100.
 * @property startArcSec The start angle `α`, −360° to 360°, default 0.
 * @property sweepArcSec The sweep `θ`, −360° to 360°, default 360°.
 */
data class HoleCircleInput(
    val radiusUm: Double?,
    val holeCount: Int?,
    val startArcSec: Double? = null,
    val sweepArcSec: Double? = null,
)

/** The hole circle, "Circular Point Array" on a lathe. */
object HoleCircle {
    const val MIN_RADIUS_UM: Double = 0.001
    const val MIN_HOLES: Int = 2
    const val MAX_HOLES: Int = 100
    const val DEFAULT_START_ARCSEC: Double = 0.0
    const val DEFAULT_SWEEP_ARCSEC: Double = PatternAngles.FULL_CIRCLE_ARCSEC

    /** The dialog title: "Hole Circle", or "Circular Point Array" on a lathe. */
    fun title(machineType: MachineType) =
        if (machineType.isLathe) PatternTexts.TITLE_HOLE_CIRCLE_LATHE.message() else PatternTexts.TITLE_HOLE_CIRCLE.message()

    /**
     * Gives the angle of each hole (arc seconds, normalized):
     *
     * ```text
     * start = α normalized
     * sweep = θ; if sweep == 0: sweep = 360
     * full  = (|sweep| == 360)         
     * step  = sweep / (full ? n : n − 1)
     * a_i   = start + i × step, normalized, for i = 0 .. n − 1
     * ```
     */
    fun angles(holeCount: Int, startArcSec: Double, sweepArcSec: Double): List<Double> {
        val start = PatternAngles.normalize(startArcSec)
        val sweep = if (sweepArcSec == 0.0) PatternAngles.FULL_CIRCLE_ARCSEC else sweepArcSec
        val full = abs(sweep) == PatternAngles.FULL_CIRCLE_ARCSEC
        val step = sweep / (if (full) holeCount else holeCount - 1)
        return List(holeCount) { index -> PatternAngles.normalize(start + index * step) }
    }

    /** Gives the 2D points of the holes about the center, before the position reference. */
    fun points(
        radiusUm: Double,
        holeCount: Int,
        startArcSec: Double,
        sweepArcSec: Double,
        convention: PatternAngleConvention,
    ): List<PointUm> = angles(holeCount, startArcSec, sweepArcSec).map { angle ->
        PatternAngles.circlePoint(radiusUm, angle, convention)
    }

    /** Checks the inputs and gives the hole circle group (default prefix "Circle"). */
    fun create(common: PatternCommon, input: HoleCircleInput): PatternResult {
        val checks = FieldChecks()
        val prefix = checks.prefix(common.prefix, PatternPrefix.DEFAULT_CIRCLE)
        val radius = checks.number(PatternField.CircleRadius, input.radiusUm, min = MIN_RADIUS_UM)
        val count = checks.count(PatternField.HoleCount, input.holeCount, MIN_HOLES, MAX_HOLES)
        val start = checks.number(
            PatternField.StartAngle, input.startArcSec,
            PatternAngles.MIN_ANGLE_ARCSEC, PatternAngles.MAX_ANGLE_ARCSEC, DEFAULT_START_ARCSEC,
        )
        val sweep = checks.number(
            PatternField.SweepAngle, input.sweepArcSec,
            PatternAngles.MIN_ANGLE_ARCSEC, PatternAngles.MAX_ANGLE_ARCSEC, DEFAULT_SWEEP_ARCSEC,
        )
        if (checks.errors.isNotEmpty()) return PatternResult.Failure(checks.errors)
        val points = points(radius!!, count!!, start!!, sweep!!, common.effectiveConvention)
        return PatternResult.Success(group(common, prefix!!, points))
    }
}

/**
 * Applies the position reference and the plane map to [points], and gives the group with the
 * plane suffix.
 */
internal fun group(common: PatternCommon, prefix: String, points: List<PointUm>): PatternGroup {
    val plane = common.effectivePlane
    val mapped = points.withReference(common.reference).map { point -> PatternPlanes.map(plane, common.origin, point) }
    return PatternGroup(prefix + PatternPlanes.groupSuffix(common.machineType, plane), mapped)
}
