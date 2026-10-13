package org.superdro.core.dataoutput

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import org.superdro.core.model.AngleUnit
import org.superdro.core.model.DistanceUnit

/**
 * The unit conversions of the data output. The internal units
 * are micrometers (µm) for the lengths and arc seconds for the angles. All math uses [Double].
 */
object OutputUnits {

    /** Micrometers in one inch. */
    const val UM_PER_INCH: Double = 25400.0

    /** Micrometers in one mil (0.001 in). */
    const val UM_PER_MIL: Double = 25.4

    /** Micrometers in one millimeter. */
    const val UM_PER_MM: Double = 1000.0

    /** Micrometers in one meter. */
    const val UM_PER_METER: Double = 1_000_000.0

    /** Micrometers in one foot: the divisor of the surface speed in ft/min. */
    const val UM_PER_FOOT: Double = 304800.0

    /** Arc seconds in one degree. */
    const val ARC_SECONDS_PER_DEGREE: Double = 3600.0

    /** Arc seconds in a half turn (π radians). */
    const val ARC_SECONDS_PER_HALF_TURN: Double = 648000.0

    /** Gives a length [um] (or a length per minute) in [unit]. */
    fun distance(um: Double, unit: DistanceUnit): Double = when (unit) {
        DistanceUnit.Inch -> um / UM_PER_INCH
        DistanceUnit.Mil -> um / UM_PER_MIL
        DistanceUnit.Millimeter -> um / UM_PER_MM
        DistanceUnit.Micron -> um
        DistanceUnit.Meter -> um / UM_PER_METER
    }

    /**
     * Gives a surface speed [umPerMinute] in ft/min for [DistanceUnit.Inch] and [DistanceUnit.Mil],
     * else in m/min.
     */
    fun surfaceSpeed(umPerMinute: Double, unit: DistanceUnit): Double = when (unit) {
        DistanceUnit.Inch, DistanceUnit.Mil -> umPerMinute / UM_PER_FOOT
        DistanceUnit.Millimeter, DistanceUnit.Micron, DistanceUnit.Meter -> umPerMinute / UM_PER_METER
    }

    /** Gives an angle [arcSeconds] in decimal degrees. */
    fun degrees(arcSeconds: Double): Double = arcSeconds / ARC_SECONDS_PER_DEGREE

    /** Gives an angle [arcSeconds] in radians: `a × π / 648000`. */
    fun radians(arcSeconds: Double): Double = arcSeconds * PI / ARC_SECONDS_PER_HALF_TURN

    /**
     * Gives an angle [arcSeconds] as a number in [unit]. [AngleUnit.DegreesMinutesSeconds] has no
     * number form, so it gives decimal degrees.
     */
    fun angle(arcSeconds: Double, unit: AngleUnit): Double = when (unit) {
        AngleUnit.DecimalDegrees, AngleUnit.DegreesMinutesSeconds -> degrees(arcSeconds)
        AngleUnit.Radians -> radians(arcSeconds)
    }

    /**
     * Gives an angle [arcSeconds] as the text `[-]<d>d<m>m<s>s`, with [decimals] digits in the
     * seconds. For example, 163815.5 arc seconds with 4
     * decimals gives `45d30m15.5000s`.
     *
     * The seconds carry into the minutes, and the minutes into the degrees. Thus 3599.999
     * arc seconds with 2 decimals gives `1d0m0.00s`, not `0d59m60.00s`.
     */
    fun dms(arcSeconds: Double, decimals: Int): String {
        require(arcSeconds.isFinite()) { "The angle $arcSeconds is not finite." }
        val negative = arcSeconds < 0.0
        val magnitude = abs(arcSeconds)
        var degrees = floor(magnitude / ARC_SECONDS_PER_DEGREE)
        var rest = magnitude - ARC_SECONDS_PER_DEGREE * degrees
        // A quotient that rounds up to an integer gives a small negative rest. Correct it.
        if (rest < 0.0) {
            degrees -= 1.0
            rest += ARC_SECONDS_PER_DEGREE
        }
        var minutes = floor(rest / 60.0)
        var seconds = rest - 60.0 * minutes
        if (seconds < 0.0) {
            minutes -= 1.0
            seconds += 60.0
        }
        var secondsText = FixedPoint.format(seconds, decimals)
        if (secondsText.toDouble() >= 60.0) {
            secondsText = FixedPoint.format(0.0, decimals)
            minutes += 1.0
        }
        if (minutes >= 60.0) {
            minutes -= 60.0
            degrees += 1.0
        }
        val text = "${FixedPoint.format(degrees, 0)}d${FixedPoint.format(minutes, 0)}m${secondsText}s"
        return if (negative) "-$text" else text
    }
}
