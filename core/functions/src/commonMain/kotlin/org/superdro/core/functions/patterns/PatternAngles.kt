package org.superdro.core.functions.patterns

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin
import org.superdro.core.functions.PointUm
import org.superdro.core.model.MachineType
import org.superdro.core.model.PatternAngleConvention
import org.superdro.core.model.Units

/**
 * The angle rules of the patterns and of the arc cut. Angles are arc seconds (″). The limits
 * of the angle fields are −360° to 360° (−1296000″ to 1296000″).
 */
object PatternAngles {

    /** 360° in arc seconds. */
    const val FULL_CIRCLE_ARCSEC: Double = Units.ARCSEC_PER_REVOLUTION

    /** The smallest value of an angle field: −360°. */
    const val MIN_ANGLE_ARCSEC: Double = -FULL_CIRCLE_ARCSEC

    /** The largest value of an angle field: 360°. */
    const val MAX_ANGLE_ARCSEC: Double = FULL_CIRCLE_ARCSEC

    /**
     * The angle normalization:
     * `if |a| > 360: a = a mod 360; if a < 0: a = a + 360`. Thus 360 stays 360 and −360 gives 0.
     */
    fun normalize(arcSec: Double): Double {
        var angle = arcSec
        if (abs(angle) > FULL_CIRCLE_ARCSEC) angle %= FULL_CIRCLE_ARCSEC
        if (angle < 0.0) angle += FULL_CIRCLE_ARCSEC
        return angle
    }

    /**
     * The arbitrary hole circle round-off: `round(a × 10000) / 10000` in degrees (4 decimals of a
     * degree). The round-off goes half up, as Java `Math.round`.
     */
    fun roundToFourDecimals(arcSec: Double): Double {
        val degrees = arcSec / Units.ARCSEC_PER_DEGREE
        val rounded = kotlin.math.floor(degrees * 10_000.0 + 0.5) / 10_000.0
        return rounded * Units.ARCSEC_PER_DEGREE
    }

    /** Gives the convention that applies: a lathe always uses LEGACY. */
    fun convention(machineType: MachineType, profile: PatternAngleConvention): PatternAngleConvention =
        if (machineType.isLathe) PatternAngleConvention.Legacy else profile

    /** Converts arc seconds to radians. */
    fun radians(arcSec: Double): Double = arcSec / Units.ARCSEC_PER_DEGREE * PI / 180.0

    /** Converts arc seconds to degrees. */
    fun degrees(arcSec: Double): Double = arcSec / Units.ARCSEC_PER_DEGREE

    /** Converts degrees to arc seconds. */
    fun arcSec(degrees: Double): Double = degrees * Units.ARCSEC_PER_DEGREE

    /**
     * The point at the angle [arcSec] on a circle of [radiusUm] about the origin.
     *
     * - LEGACY: `px = R × sin(a)`, `py = R × cos(a)`. The angle 0 is on +py (12 o'clock). A
     *   positive angle turns clockwise.
     * - STANDARD: `px = R × cos(a)`, `py = R × sin(a)`. The angle 0 is on +px (3 o'clock). A
     *   positive angle turns counter-clockwise.
     */
    fun circlePoint(radiusUm: Double, arcSec: Double, convention: PatternAngleConvention): PointUm {
        val angle = radians(arcSec)
        return when (convention) {
            PatternAngleConvention.Legacy -> PointUm(radiusUm * sin(angle), radiusUm * cos(angle))
            PatternAngleConvention.Standard -> PointUm(radiusUm * cos(angle), radiusUm * sin(angle))
        }
    }

    /**
     * Turns the vector [point] about the origin by [arcSec], counter-clockwise for a positive
     * angle: `x1 = cos t × x − sin t × y`, `y1 = sin t × x + cos t × y`.
     */
    fun rotate(point: PointUm, arcSec: Double): PointUm {
        if (arcSec == 0.0) return point
        val angle = radians(arcSec)
        val c = cos(angle)
        val s = sin(angle)
        return PointUm(c * point.h - s * point.v, s * point.h + c * point.v)
    }

    /** Gives true when [arcSec] is a whole number of 0.0001°, to compare the list angles. */
    internal fun sameListAngle(a: Double, b: Double): Boolean =
        round(degrees(a) * 10_000.0) == round(degrees(b) * 10_000.0)
}
