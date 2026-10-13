package org.superdro.core.readout

import org.superdro.core.model.Units

/**
 * A unit converter of a readout. [toDisplay] changes an internal
 * value (µm, arc seconds, or RPM) to the display unit. [toInternal] changes a display value
 * back.
 */
sealed interface UnitConverter {

    /** The display unit. */
    val unit: DisplayUnit

    /** Gives the display value of the internal value [value]. */
    fun toDisplay(value: Double): Double

    /** Gives the internal value of the display value [display]. */
    fun toInternal(display: Double): Double

    /** Linear, inch: `v / 25400` and `d × 25400`. */
    data object Inch : UnitConverter {
        override val unit: DisplayUnit = DisplayUnit.Inch
        override fun toDisplay(value: Double): Double = value / Units.UM_PER_INCH
        override fun toInternal(display: Double): Double = display * Units.UM_PER_INCH
    }

    /** Linear, mm: `v / 1000` and `d × 1000`. */
    data object Millimeter : UnitConverter {
        override val unit: DisplayUnit = DisplayUnit.Millimeter
        override fun toDisplay(value: Double): Double = value / Units.UM_PER_MM
        override fun toInternal(display: Double): Double = display * Units.UM_PER_MM
    }

    /**
     * Angular, degrees: `v / 3600` if `v ≥ 0`, else `v / 3600 + 360`. Back:
     * `(d % 360) × 3600` if `d > 0`, else `((d % 360) + 360) × 3600`.
     */
    data object Degrees : UnitConverter {
        override val unit: DisplayUnit = DisplayUnit.Degrees

        override fun toDisplay(value: Double): Double {
            val degrees = value / Units.ARCSEC_PER_DEGREE
            return if (value >= 0.0) degrees else degrees + 360.0
        }

        override fun toInternal(display: Double): Double =
            if (display > 0.0) {
                (display % 360.0) * Units.ARCSEC_PER_DEGREE
            } else {
                ((display % 360.0) + 360.0) * Units.ARCSEC_PER_DEGREE
            }
    }

    /**
     * Angular, divisions: `N` divisions for each revolution and `q = 360 / N` degrees for each
     * division. `((v / 3600) / q) mod N`, made positive. Back: `((d × q) mod 360, made positive)
     * × 3600`. `N` must be 1 or more.
     */
    data class Divisions(val divisionsPerRev: Int) : UnitConverter {

        init {
            require(divisionsPerRev >= 1) { "The divisions per revolution must be 1 or more, but it is $divisionsPerRev." }
        }

        /** The degrees of one division. */
        val degreesPerDivision: Double = 360.0 / divisionsPerRev

        override val unit: DisplayUnit = DisplayUnit.Divisions

        override fun toDisplay(value: Double): Double {
            val divisions = (value / Units.ARCSEC_PER_DEGREE) / degreesPerDivision
            return positiveModulo(divisions, divisionsPerRev.toDouble())
        }

        override fun toInternal(display: Double): Double =
            positiveModulo(display * degreesPerDivision, 360.0) * Units.ARCSEC_PER_DEGREE
    }

    /** The tachometer: `v` and `v`. */
    data object Rpm : UnitConverter {
        override val unit: DisplayUnit = DisplayUnit.Rpm
        override fun toDisplay(value: Double): Double = value
        override fun toInternal(display: Double): Double = display
    }

    companion object {
        /** Gives the linear converter: mm when [metric] is true, else inch. */
        fun linear(metric: Boolean): UnitConverter = if (metric) Millimeter else Inch

        /** Gives `x mod m` in the range 0 (inclusive) to `m` (exclusive). */
        internal fun positiveModulo(x: Double, m: Double): Double {
            val remainder = x % m
            return if (remainder < 0.0) remainder + m else remainder
        }
    }
}

/** The unit of a shown value. */
enum class DisplayUnit { Inch, Millimeter, Degrees, Divisions, Rpm, SurfaceFeetPerMinute, MetersPerMinute }
