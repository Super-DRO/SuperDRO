package org.superdro.core.functions.measure

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.sqrt
import org.superdro.core.model.AxisId
import org.superdro.core.model.MachineType
import org.superdro.core.model.Units

/**
 * The values of "Measure Distance/Angle". Lengths are µm,
 * angles are arc seconds.
 *
 * @property distanceUm `d = sqrt(a² + b²)`.
 * @property lineAngleArcSec The line angle `alpha`, −90° to 90° (`atan`).
 * @property complementArcSec `theta = 90 − alpha` (0 when a and b are 0).
 * @property directionArcSec The direction from the first point (the zero) to the second point
 *   (the current position): `atan2(b, a)`, 0° to 360°.
 */
data class DistanceAngle(
    val distanceUm: Double,
    val lineAngleArcSec: Double,
    val complementArcSec: Double,
    val directionArcSec: Double,
)

/** "Measure Distance/Angle". The dialog shows live values. */
object MeasureDistanceAngle {

    /**
     * The two axes `(a, b)` of the measure: (X, Y) on a mill, (Z, X) on a lathe. The values are
     * the shown positions, in the ABS or INC mode of each axis.
     */
    fun axes(machineType: MachineType): Pair<AxisId, AxisId> =
        if (machineType.isLathe) AxisId.Z to AxisId.X else AxisId.X to AxisId.Y

    /**
     * Calculates the values for the shown positions [aUm] and [bUm]:
     *
     * ```text
     * d         = sqrt(a² + b²)
     * alpha     = 0 if a == 0 and b == 0;  90 if a == 0;  0 if b == 0;  else degrees(atan(b / a))
     * theta     = 90 − alpha               (0 if a == 0 and b == 0)
     * direction = degrees(atan2(b, a)), plus 360 when it is less than 0
     * ```
     */
    fun measure(aUm: Double, bUm: Double): DistanceAngle {
        val distance = sqrt(aUm * aUm + bUm * bUm)
        val origin = aUm == 0.0 && bUm == 0.0
        val alphaDegrees = when {
            origin -> 0.0
            aUm == 0.0 -> 90.0
            bUm == 0.0 -> 0.0
            else -> atan(bUm / aUm) * 180.0 / PI
        }
        val thetaDegrees = if (origin) 0.0 else 90.0 - alphaDegrees
        var directionDegrees = if (origin) 0.0 else atan2(bUm, aUm) * 180.0 / PI
        if (directionDegrees < 0.0) directionDegrees += 360.0
        return DistanceAngle(
            distanceUm = distance,
            lineAngleArcSec = alphaDegrees * Units.ARCSEC_PER_DEGREE,
            complementArcSec = thetaDegrees * Units.ARCSEC_PER_DEGREE,
            directionArcSec = directionDegrees * Units.ARCSEC_PER_DEGREE,
        )
    }
}

/**
 * The command of the button "Copy to X" of the dimension measure.
 *
 * @property valueUm The value for X, as a shown value of X.
 * @property incremental True: set the incremental value of X (`X.i(value)`). False: set the
 *   absolute value of X (`X.h(value)`).
 */
data class CopyToX(val valueUm: Double, val incremental: Boolean)

/** The dimension measure with the caliper input. */
object CaliperMeasure {

    /**
     * Gives the "Copy to X" command:
     *
     * ```text
     * factor = X diameter mode ? 1.0 : 0.5
     * value  = caliper shown value (µm) × factor
     * X.i(value) if X is incremental, else X.h(value)
     * ```
     *
     * The caliper has its own state, so the copy reads only the caliper shown value.
     */
    fun copyToX(caliperShownUm: Double, xDiameterMode: Boolean, xIncremental: Boolean): CopyToX {
        val factor = if (xDiameterMode) 1.0 else 0.5
        return CopyToX(caliperShownUm * factor, xIncremental)
    }

    /**
     * The function button shows when `show_dimension_measure` is on and the caliper is enabled, on
     * all machine types.
     */
    fun buttonVisible(showDimensionMeasure: Boolean, caliperEnabled: Boolean): Boolean =
        showDimensionMeasure && caliperEnabled
}
