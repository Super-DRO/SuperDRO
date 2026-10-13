package org.superdro.core.functions.transform

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt
import org.superdro.core.functions.graphics.AngleMath
import org.superdro.core.functions.graphics.Vec2
import org.superdro.core.model.MachineType
import org.superdro.core.model.Plane

/**
 * The values that the pair dialog shows for the points A and B.
 * (u, v) are the two plane axes.
 *
 * @property lineAngleDegrees `atan2(dv, du)` in degrees, folded to (−90°, 90°] (a line direction).
 * @property toleranceDegrees The tolerance angle of the distance, or NaN when the distance is
 *   less than 1 µm.
 * @property degenerate The distance is less than 1 µm: "Align B to Spindle" is disabled.
 * @property shortBaseline The tolerance angle is more than 0.1°: the dialog shows "too close".
 */
data class PairGeometry(
    val du: Double,
    val dv: Double,
    val distanceUm: Double,
    val lineAngleDegrees: Double,
    val toleranceDegrees: Double,
    val degenerate: Boolean,
    val shortBaseline: Boolean,
)

/** The result of "Align A to B". */
sealed interface AlignAToBResult {
    /** The shift is below the no-op limit. */
    data object NoOp : AlignAToBResult

    /**
     * The new absolute values of the two plane axes and the shift `(−du, −dv)` of the readout.
     * The stored rows do not change. Then the dialog asks to delete B.
     */
    data class Apply(val newU: Double, val newV: Double, val shiftU: Double, val shiftV: Double) : AlignAToBResult
}

/** The result of "Align B to Spindle (Pivot A)". */
sealed interface AlignSpindleResult {
    /** The spindle vector from A is shorter than 1 µm. */
    data object NoDirection : AlignSpindleResult

    /** The angle θ is 0 after the snap. */
    data object NoOp : AlignSpindleResult

    /**
     * Rotate the workspace by [thetaDegrees] about A.
     *
     * @property toleranceDegrees The tolerance angle t of the snap.
     * @property landUm `|spindle vector| − distance`: where B lands from the spindle along the line.
     * @property uncertain True when t is more than 0.1°. Then the confirmation shows a caution with t.
     * @property transform The rotation to run through the transform pipeline (the pipeline checks it again).
     */
    data class Rotate(
        val thetaDegrees: Double,
        val toleranceDegrees: Double,
        val landUm: Double,
        val uncertain: Boolean,
        val transform: RotateTransform,
    ) : AlignSpindleResult
}

/**
 * The sub-datum pair commands. All positions are
 * in the workspace frame.
 */
object PairCommands {

    /** The distance below which the pair is degenerate. */
    const val DEGENERATE_UM: Double = 1.0

    /** The tolerance angle above which the baseline is short. */
    const val SHORT_BASELINE_DEGREES: Double = 0.1

    /** The base length of the tolerance angle: `√2 · 10 µm`. */
    val TOLERANCE_LENGTH_UM: Double = sqrt(2.0) * 10.0

    /** Gives the tolerance angle of the distance [distanceUm]: `toDegrees(√2 · 10 µm / d)`, NaN when d < 1 µm. */
    fun toleranceDegrees(distanceUm: Double): Double =
        if (distanceUm < DEGENERATE_UM) Double.NaN else AngleMath.radToDegrees(TOLERANCE_LENGTH_UM / distanceUm)

    /** Gives the values of the pair dialog for the points [a] and [b]. */
    fun geometry(a: Vec2, b: Vec2): PairGeometry {
        val du = b.x - a.x
        val dv = b.y - a.y
        val distance = hypot(du, dv)
        val tolerance = toleranceDegrees(distance)
        return PairGeometry(
            du = du,
            dv = dv,
            distanceUm = distance,
            lineAngleDegrees = AngleMath.foldLineDegrees(AngleMath.radToDegrees(atan2(dv, du))),
            toleranceDegrees = tolerance,
            degenerate = distance < DEGENERATE_UM,
            shortBaseline = tolerance > SHORT_BASELINE_DEGREES,
        )
    }

    /** True when "Align B to Spindle" is visible: not a lathe, and the plane is `XY`. */
    fun alignSpindleVisible(machineType: MachineType, plane: Plane): Boolean = !machineType.isLathe && plane == Plane.XY

    /**
     * Gives "Align A to B": `Abs_c_new = Abs_c − d_c` for each plane axis. A now marks the
     * physical place where B was.
     *
     * Example: A = (10, 20), B = (15, 20) mm and the position (50, 30) mm give the new position
     * (45, 30) mm.
     *
     * @param absolute The current absolute values of the two plane axes (u, v).
     */
    fun alignAToB(a: Vec2, b: Vec2, absolute: Vec2): AlignAToBResult {
        val du = b.x - a.x
        val dv = b.y - a.y
        val shiftU = positiveZero(-du)
        val shiftV = positiveZero(-dv)
        if (abs(shiftU) < NoOpLimits.LINEAR_UM && abs(shiftV) < NoOpLimits.LINEAR_UM) return AlignAToBResult.NoOp
        return AlignAToBResult.Apply(positiveZero(absolute.x - du), positiveZero(absolute.y - dv), shiftU, shiftV)
    }

    /**
     * Gives "Align B to Spindle (Pivot A)":
     *
     * 1. Spindle vector: `S − A`. A length below 1 µm gives [AlignSpindleResult.NoDirection].
     * 2. Raw angle: `θ = norm(atan2(Sv − Av, Su − Au) − atan2(dv, du))` in (−180°, 180°].
     * 3. Snap: t = the tolerance angle of `min(distance, spindle length)`. If
     *    `|θ − round(θ/90)·90| < t`, θ becomes `round(θ/90)·90`.
     * 4. θ = 0 gives [AlignSpindleResult.NoOp].
     *
     * @param spindle The current absolute position (the spindle) in the plane.
     */
    fun alignBToSpindle(a: Vec2, b: Vec2, spindle: Vec2): AlignSpindleResult {
        val spindleVector = spindle - a
        val spindleLength = spindleVector.length
        if (spindleLength < DEGENERATE_UM) return AlignSpindleResult.NoDirection
        val pair = b - a
        val distance = pair.length
        if (distance < DEGENERATE_UM) return AlignSpindleResult.NoDirection
        var theta = AngleMath.normalizeDegrees(AngleMath.radToDegrees(spindleVector.angle - pair.angle))
        val tolerance = toleranceDegrees(min(distance, spindleLength))
        val snapped = (theta / 90.0).roundToLong() * 90.0
        if (abs(theta - snapped) < tolerance) theta = AngleMath.normalizeDegrees(snapped)
        theta = positiveZero(theta)
        if (theta == 0.0) return AlignSpindleResult.NoOp
        return AlignSpindleResult.Rotate(
            thetaDegrees = theta,
            toleranceDegrees = tolerance,
            landUm = spindleLength - distance,
            uncertain = tolerance > SHORT_BASELINE_DEGREES,
            transform = RotateTransform.ofDegrees(theta, a.x, a.y),
        )
    }
}
