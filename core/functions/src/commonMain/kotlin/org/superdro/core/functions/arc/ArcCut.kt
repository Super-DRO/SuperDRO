package org.superdro.core.functions.arc

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.ceil
import org.superdro.core.functions.PointUm
import org.superdro.core.functions.patterns.FieldChecks
import org.superdro.core.functions.patterns.PatternAngles
import org.superdro.core.functions.patterns.PatternCommon
import org.superdro.core.functions.patterns.PatternError
import org.superdro.core.functions.patterns.PatternField
import org.superdro.core.functions.patterns.PatternPrefix
import org.superdro.core.functions.patterns.PatternResult
import org.superdro.core.functions.patterns.PatternTexts
import org.superdro.core.functions.patterns.group
import org.superdro.core.model.MachineType
import org.superdro.core.model.PatternAngleConvention

/** The cut type of the arc cut. */
enum class CutType {
    /** The cutter is in the radius: the point radius is `R − D/2`. */
    Internal,

    /** The default. The cutter is out of the radius: the point radius is `R + D/2`. */
    External,
}

/** The angle reference of the arc cut. */
enum class AngleReference {
    /** The default: the angles are the angles of the cutter center. */
    Center,

    /** The cutter touches the two end lines of the sweep. */
    Tangent,
}

/**
 * The inputs of the arc cut. Lengths are µm, angles are arc seconds. A
 * null value is an empty field.
 *
 * @property radiusUm The part radius `R`, 0.001 µm or more.
 * @property cutterDiameterUm The cutter diameter `D`, 0.001 µm to 2540000 µm.
 * @property stepoverUm The stepover `s`: the chord on the part surface, 0.001 µm or more.
 * @property startArcSec The start angle `α`, −360° to 360°. It is required.
 * @property sweepArcSec The sweep `θ`, −360° to 360°, default 90°.
 */
data class ArcCutInput(
    val radiusUm: Double?,
    val cutterDiameterUm: Double?,
    val stepoverUm: Double?,
    val startArcSec: Double?,
    val sweepArcSec: Double? = null,
    val cutType: CutType = CutType.External,
    val angleReference: AngleReference = AngleReference.Center,
)

/**
 * The values of an arc cut before the plane map. The angles are arc seconds.
 *
 * @property stepAngleArcSec `c(R, s)`.
 * @property tangentAngleArcSec `fc`.
 * @property effectiveSweepArcSec `sweep_eff`.
 * @property startArcSec The normalized start angle.
 * @property steps `n`. The cut has `n + 1` points.
 * @property stepArcSec `sweep_eff / n`.
 * @property pointRadiusUm `Rc`.
 * @property points The cutter-center points about the radius center.
 */
data class ArcCutGeometry(
    val stepAngleArcSec: Double,
    val tangentAngleArcSec: Double,
    val effectiveSweepArcSec: Double,
    val startArcSec: Double,
    val steps: Int,
    val stepArcSec: Double,
    val pointRadiusUm: Double,
    val points: List<PointUm>,
)

/** The result of [ArcCut.geometry]. */
sealed interface ArcGeometryResult {
    data class Success(val geometry: ArcCutGeometry) : ArcGeometryResult
    data class Failure(val error: PatternError) : ArcGeometryResult
}

/** "Radius/Arc Cutting": cutter-center points along an arc. */
object ArcCut {
    const val MIN_RADIUS_UM: Double = 0.001
    const val MIN_CUTTER_UM: Double = 0.001
    const val MAX_CUTTER_UM: Double = 2_540_000.0
    const val MIN_STEPOVER_UM: Double = 0.001
    const val DEFAULT_SWEEP_ARCSEC: Double = 90.0 * 3600.0

    /** The degrees for each radian in the chord formula. */
    const val DEGREES_PER_RADIAN: Double = 57.29578

    /** The dialog title "Radius/Arc Cutting". */
    fun title() = PatternTexts.TITLE_ARC.message()

    /**
     * The default cutter diameter: on a mill, the active radius compensation diameter when it is
     * more than 0 ([org.superdro.core.model.Workspace.toolXUm]). Else no default.
     */
    fun defaultCutterDiameterUm(machineType: MachineType, workspaceToolXUm: Double): Double? =
        workspaceToolXUm.takeIf { !machineType.isLathe && it > 0.0 }

    /**
     * The chord angle `c(r, d) = 2 × asin(d / (2r)) × 57.29578`, in arc seconds. It gives null
     * (an error) when `r <= 0`, `d <= 0`, or `d > 2r`.
     */
    fun chordAngleArcSec(radiusUm: Double, chordUm: Double): Double? {
        if (radiusUm <= 0.0 || chordUm <= 0.0 || chordUm > 2.0 * radiusUm) return null
        return PatternAngles.arcSec(2.0 * asin(chordUm / (2.0 * radiusUm)) * DEGREES_PER_RADIAN)
    }

    /**
     * Calculates the cut:
     *
     * ```text
     * sgn       = (θ < 0) ? −1 : 1
     * fc        = tangent ? c(R + D/2, D/2) : 0
     * sweep_eff = θ − sgn × 2 × fc
     * check 1:  θ × sweep_eff < 0                         -> cutter diameter too large
     * check 2:  |sweep_eff| < |c(R, s)|, or c(R, s) fails -> stepover too large
     * start     = sgn × fc + α, normalized
     * n         = ceil(|sweep_eff| / c(R, s))
     * step      = sweep_eff / n
     * a_i       = start + i × step, normalized, for i = 0 .. n
     * Rc        = R + D/2 (external) or R − D/2 (internal)
     * ```
     *
     * The point of each angle uses the angle convention of [convention].
     */
    fun geometry(
        radiusUm: Double,
        cutterDiameterUm: Double,
        stepoverUm: Double,
        startArcSec: Double,
        sweepArcSec: Double,
        cutType: CutType,
        angleReference: AngleReference,
        convention: PatternAngleConvention,
    ): ArcGeometryResult {
        val halfCutter = cutterDiameterUm / 2.0
        if (cutType == CutType.Internal && halfCutter >= radiusUm) {
            return ArcGeometryResult.Failure(
                PatternError(PatternField.CutterDiameter, PatternTexts.CUTTER_TOO_LARGE_FOR_RADIUS.message()),
            )
        }
        val sign = if (sweepArcSec < 0.0) -1.0 else 1.0
        val tangent = if (angleReference == AngleReference.Tangent) {
            chordAngleArcSec(radiusUm + halfCutter, halfCutter) ?: return cutterTooLarge()
        } else {
            0.0
        }
        val effectiveSweep = sweepArcSec - sign * 2.0 * tangent
        if (sweepArcSec * effectiveSweep < 0.0) return cutterTooLarge()
        val stepAngle = chordAngleArcSec(radiusUm, stepoverUm)
        if (stepAngle == null || abs(effectiveSweep) < abs(stepAngle)) {
            return ArcGeometryResult.Failure(PatternError(PatternField.Stepover, PatternTexts.STEPOVER_TOO_LARGE.message()))
        }
        val start = PatternAngles.normalize(sign * tangent + startArcSec)
        val steps = ceil(abs(effectiveSweep) / stepAngle).toInt()
        val step = effectiveSweep / steps
        val pointRadius = if (cutType == CutType.Internal) radiusUm - halfCutter else radiusUm + halfCutter
        val points = List(steps + 1) { index ->
            PatternAngles.circlePoint(pointRadius, PatternAngles.normalize(start + index * step), convention)
        }
        return ArcGeometryResult.Success(
            ArcCutGeometry(stepAngle, tangent, effectiveSweep, start, steps, step, pointRadius, points),
        )
    }

    /**
     * Checks the inputs and gives the cut group (default prefix "Arc"). The position reference
     * "Start of cut" puts the first point at the origin.
     */
    fun create(common: PatternCommon, input: ArcCutInput): PatternResult {
        val checks = FieldChecks()
        val prefix = checks.prefix(common.prefix, PatternPrefix.DEFAULT_ARC)
        val radius = checks.number(PatternField.PartRadius, input.radiusUm, min = MIN_RADIUS_UM)
        val cutter = checks.number(PatternField.CutterDiameter, input.cutterDiameterUm, MIN_CUTTER_UM, MAX_CUTTER_UM)
        val stepover = checks.number(PatternField.Stepover, input.stepoverUm, min = MIN_STEPOVER_UM)
        val start = checks.number(
            PatternField.StartAngle, input.startArcSec,
            PatternAngles.MIN_ANGLE_ARCSEC, PatternAngles.MAX_ANGLE_ARCSEC,
        )
        val sweep = checks.number(
            PatternField.SweepAngle, input.sweepArcSec,
            PatternAngles.MIN_ANGLE_ARCSEC, PatternAngles.MAX_ANGLE_ARCSEC, DEFAULT_SWEEP_ARCSEC,
        )
        if (checks.errors.isNotEmpty()) return PatternResult.Failure(checks.errors)
        val result = geometry(
            radius!!, cutter!!, stepover!!, start!!, sweep!!,
            input.cutType, input.angleReference, common.effectiveConvention,
        )
        return when (result) {
            is ArcGeometryResult.Failure -> PatternResult.Failure(listOf(result.error))
            is ArcGeometryResult.Success -> PatternResult.Success(group(common, prefix!!, result.geometry.points))
        }
    }

    private fun cutterTooLarge(): ArcGeometryResult.Failure =
        ArcGeometryResult.Failure(PatternError(PatternField.CutterDiameter, PatternTexts.CUTTER_TOO_LARGE.message()))
}
