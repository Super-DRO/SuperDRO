package org.superdro.core.readout

import org.superdro.core.model.Units

/**
 * The conversions between the internal units and the display units.
 * Lengths are µm and angles are arc seconds. The functions use `Double`, never `Float`.
 */
object UnitConversion {

    /** Gives millimeters from micrometers. */
    fun umToMm(um: Double): Double = um / Units.UM_PER_MM

    /** Gives inches from micrometers. */
    fun umToInch(um: Double): Double = um / Units.UM_PER_INCH

    /** Gives micrometers from millimeters. */
    fun mmToUm(mm: Double): Double = mm * Units.UM_PER_MM

    /** Gives micrometers from inches. */
    fun inchToUm(inch: Double): Double = inch * Units.UM_PER_INCH

    /** Gives degrees from arc seconds. */
    fun arcSecToDegrees(arcSec: Double): Double = arcSec / Units.ARCSEC_PER_DEGREE

    /** Gives arc seconds from degrees. */
    fun degreesToArcSec(degrees: Double): Double = degrees * Units.ARCSEC_PER_DEGREE
}
