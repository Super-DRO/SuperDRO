package org.superdro.core.readout

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.superdro.core.model.PartRotation
import org.superdro.core.model.Units

/** A point of the X-Y plane in µm. */
data class PlanePoint(val x: Double, val y: Double)

/**
 * The axis alignment rotation of the X and Y readouts:
 *
 * ```text
 * X' = cos θ · absX + sin θ · absY
 * Y' = −sin θ · absX + cos θ · absY
 * ```
 *
 * Example: θ = 2°, absX = 100000 µm, absY = 0 gives X' = 99939 µm and Y' = −3490 µm.
 */
data class PlaneRotation(val angleArcSec: Double) {

    private val radians: Double = angleArcSec / Units.ARCSEC_PER_DEGREE * PI / 180.0
    private val cosine: Double = cos(radians)
    private val sine: Double = sin(radians)

    /** Gives the display frame point of the machine frame point ([x], [y]). */
    fun forward(x: Double, y: Double): PlanePoint = PlanePoint(cosine * x + sine * y, -sine * x + cosine * y)

    /** Gives the machine frame point of the display frame point ([x], [y]). */
    fun inverse(x: Double, y: Double): PlanePoint = PlanePoint(cosine * x - sine * y, sine * x + cosine * y)
}

/**
 * The rotate part display transform. It turns the
 * drawing, the reference image, and the sub-datums about a pivot for the display only. The
 * readouts and the stored data do not change.
 *
 * ```text
 * φ = −α                     typed source
 * φ = −(A − offset)          linked source, A = angular axis position
 * x1 = px + cos φ·(x − px) − sin φ·(y − py)
 * y1 = py + sin φ·(x − px) + cos φ·(y − py)
 * ```
 *
 * Example: pivot (0, 0), α = 90°. The stored point (10, 0) shows at (0, −10).
 *
 * @property phiArcSec The angle φ.
 */
data class PartRotationTransform(val pivotXUm: Double, val pivotYUm: Double, val phiArcSec: Double) {

    private val radians: Double = phiArcSec / Units.ARCSEC_PER_DEGREE * PI / 180.0
    private val cosine: Double = cos(radians)
    private val sine: Double = sin(radians)

    /** Gives the shown position of the stored point ([x], [y]). */
    fun toDisplay(x: Double, y: Double): PlanePoint {
        val dx = x - pivotXUm
        val dy = y - pivotYUm
        return PlanePoint(pivotXUm + cosine * dx - sine * dy, pivotYUm + sine * dx + cosine * dy)
    }

    /** Gives the stored point of the shown position ([x], [y]): the inverse of [toDisplay]. */
    fun toStored(x: Double, y: Double): PlanePoint {
        val dx = x - pivotXUm
        val dy = y - pivotYUm
        return PlanePoint(pivotXUm + cosine * dx + sine * dy, pivotYUm - sine * dx + cosine * dy)
    }

    companion object {
        /**
         * Gives the transform of [rotation], or null when the rotation is not active.
         * [angularArcSec] is the angular axis position for the linked source.
         */
        fun of(rotation: PartRotation, angularArcSec: Double): PartRotationTransform? {
            if (!rotation.active) return null
            val phi = if (rotation.linkedToAngularEncoder) {
                -(angularArcSec - rotation.encoderOffsetArcSec)
            } else {
                -rotation.angleArcSec
            }
            return PartRotationTransform(rotation.pivotXUm, rotation.pivotYUm, phi)
        }
    }
}
