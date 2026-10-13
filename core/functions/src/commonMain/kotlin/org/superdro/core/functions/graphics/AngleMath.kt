package org.superdro.core.functions.graphics

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import org.superdro.core.model.Units

/**
 * The angle rules of the geometry functions. The stored angles are arc seconds. The math uses
 * degrees or radians inside a function.
 */
object AngleMath {

    /** 90° in arc seconds. */
    const val QUARTER_TURN_ARCSEC: Double = 324_000.0

    /** 180° in arc seconds. */
    const val HALF_TURN_ARCSEC: Double = 648_000.0

    /** 2π. */
    const val TWO_PI: Double = 2.0 * PI

    /** Gives degrees from arc seconds. */
    fun arcSecToDegrees(arcSec: Double): Double = arcSec / Units.ARCSEC_PER_DEGREE

    /** Gives arc seconds from degrees. */
    fun degreesToArcSec(degrees: Double): Double = degrees * Units.ARCSEC_PER_DEGREE

    /** Gives radians from degrees. */
    fun degreesToRad(degrees: Double): Double = degrees * PI / 180.0

    /** Gives degrees from radians. */
    fun radToDegrees(rad: Double): Double = rad * 180.0 / PI

    /** Gives radians from arc seconds. */
    fun arcSecToRad(arcSec: Double): Double = degreesToRad(arcSecToDegrees(arcSec))

    /** Gives the angle in the range (−180°, 180°]. */
    fun normalizeDegrees(degrees: Double): Double = degrees - 360.0 * ceil((degrees - 180.0) / 360.0)

    /** Gives the angle in the range (−648000″, 648000″], that is (−180°, 180°]. */
    fun normalizeArcSec(arcSec: Double): Double =
        arcSec - 2.0 * HALF_TURN_ARCSEC * ceil((arcSec - HALF_TURN_ARCSEC) / (2.0 * HALF_TURN_ARCSEC))

    /** Gives the angle in the range (−π, π]. */
    fun normalizeRadSigned(rad: Double): Double = rad - TWO_PI * ceil((rad - PI) / TWO_PI)

    /** Gives the angle in the range [0, 2π). */
    fun normalizeRad(rad: Double): Double {
        val value = rad - TWO_PI * floor(rad / TWO_PI)
        return if (value >= TWO_PI) 0.0 else value
    }

    /**
     * Gives the direction of a line in the range (−90°, 90°]. A line has no sense, so the angles
     * a and a + 180° are the same line.
     */
    fun foldLineDegrees(degrees: Double): Double {
        val value = normalizeDegrees(degrees)
        return when {
            value > 90.0 -> value - 180.0
            value <= -90.0 -> value + 180.0
            else -> value
        }
    }

    /**
     * Gives the number of quarter turns (0, 1, 2, or 3 counterclockwise) when [arcSec] is a
     * multiple of 90°, else null.
     */
    fun quarterTurns(arcSec: Double): Int? {
        if (!arcSec.isFinite()) return null
        val turns = arcSec / QUARTER_TURN_ARCSEC
        val whole = floor(turns)
        if (turns != whole) return null
        return (((whole % 4.0) + 4.0) % 4.0).toInt()
    }

    /**
     * Gives (cos, sin) of an angle in arc seconds. A multiple of 90° gives the exact values 0, 1,
     * and −1.
     */
    fun cosSinArcSec(arcSec: Double): Pair<Double, Double> = when (quarterTurns(arcSec)) {
        0 -> 1.0 to 0.0
        1 -> 0.0 to 1.0
        2 -> -1.0 to 0.0
        3 -> 0.0 to -1.0
        else -> {
            val rad = arcSecToRad(arcSec)
            cos(rad) to sin(rad)
        }
    }

    /**
     * Gives (cos, sin) of an angle in radians. An angle within 1e-15 rad of a multiple of π/2
     * gives the exact values 0, 1, and −1.
     */
    fun cosSinRad(rad: Double): Pair<Double, Double> {
        val turns = rad / (PI / 2.0)
        val whole = floor(turns + 0.5)
        if (abs(rad - whole * (PI / 2.0)) <= 1e-15) {
            return when ((((whole % 4.0) + 4.0) % 4.0).toInt()) {
                0 -> 1.0 to 0.0
                1 -> 0.0 to 1.0
                2 -> -1.0 to 0.0
                else -> 0.0 to -1.0
            }
        }
        return cos(rad) to sin(rad)
    }
}
