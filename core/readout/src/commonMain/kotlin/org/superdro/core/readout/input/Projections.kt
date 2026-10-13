package org.superdro.core.readout.input

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import org.superdro.core.model.CompoundProjection
import org.superdro.core.model.HeadTilt
import org.superdro.core.model.Units

/**
 * The weights of the compound slide projection on a lathe.
 *
 * ```text
 * a     = compound slide angle (degrees)
 * ref   = y_angle_reference (degrees)
 * a_eff = a          when ref is 0 (mod 360)
 *         ref − a    for each other reference                
 * kX    = sin(a_eff)   when "Apply to Cross Slide", else no X term
 * kZ    = cos(a_eff)   when "Apply to Carriage", else no Z term
 * ```
 *
 * SuperDRO gives an effect to each reference of −360 to 360.
 */
object CompoundProjectionWeights {

    /** Gives the effective angle in degrees. */
    fun effectiveAngleDegrees(angleDegrees: Double, referenceDegrees: Double): Double =
        if (referenceDegrees % 360.0 == 0.0) angleDegrees else referenceDegrees - angleDegrees

    /** Gives the X weight, or null when the projection gives no X term. */
    fun xWeight(projection: CompoundProjection, referenceDegrees: Double): Double? {
        if (!projection.active || !projection.applyX) return null
        return sin(radians(effectiveAngleDegrees(projection.angleArcSec / Units.ARCSEC_PER_DEGREE, referenceDegrees)))
    }

    /** Gives the Z weight, or null when the projection gives no Z term. */
    fun zWeight(projection: CompoundProjection, referenceDegrees: Double): Double? {
        if (!projection.active || !projection.applyZ) return null
        return cos(radians(effectiveAngleDegrees(projection.angleArcSec / Units.ARCSEC_PER_DEGREE, referenceDegrees)))
    }
}

/**
 * The weights of the head tilt projection on a vertical mill.
 *
 * ```text
 * kX = −sin α                              when "Apply to X", else no X term
 * kY = −sin β                              when "Apply to Y", else no Y term
 * kZ = sqrt(max(0, 1 − sin²α − sin²β))     always (1.0 when the projection is off)
 * ```
 */
object HeadTiltWeights {

    /** Gives the X weight, or null when the projection gives no X term. */
    fun xWeight(tilt: HeadTilt): Double? =
        if (tilt.active && tilt.applyX) -sin(radians(tilt.tiltArcSec / Units.ARCSEC_PER_DEGREE)) else null

    /** Gives the Y weight, or null when the projection gives no Y term. */
    fun yWeight(tilt: HeadTilt): Double? =
        if (tilt.active && tilt.applyY) -sin(radians(tilt.nodArcSec / Units.ARCSEC_PER_DEGREE)) else null

    /** Gives the weight of the quill on Z. */
    fun quillWeight(tilt: HeadTilt): Double {
        if (!tilt.active) return 1.0
        val sinAlpha = sin(radians(tilt.tiltArcSec / Units.ARCSEC_PER_DEGREE))
        val sinBeta = sin(radians(tilt.nodArcSec / Units.ARCSEC_PER_DEGREE))
        return sqrt(max(0.0, 1.0 - sinAlpha * sinAlpha - sinBeta * sinBeta))
    }

    /**
     * True when the head cannot lean this far: `sin²α + sin²β > 1`.
     * The dialog refuses these angles.
     */
    fun tooFar(tiltArcSec: Double, nodArcSec: Double): Boolean {
        val sinAlpha = sin(radians(tiltArcSec / Units.ARCSEC_PER_DEGREE))
        val sinBeta = sin(radians(nodArcSec / Units.ARCSEC_PER_DEGREE))
        return sinAlpha * sinAlpha + sinBeta * sinBeta > 1.0
    }
}

internal fun radians(degrees: Double): Double = degrees * PI / 180.0
