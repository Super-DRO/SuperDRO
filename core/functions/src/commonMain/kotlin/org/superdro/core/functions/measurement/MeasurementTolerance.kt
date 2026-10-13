package org.superdro.core.functions.measurement

import org.superdro.core.model.Units

/**
 * The fit tolerance of the measurement functions.
 *
 * `tol = 4 × res`. `res` is the largest step `25400 / resolution` (µm for each count) of the
 * axes of the plane. The resolution is the encoder value in counts for each inch. An axis with no
 * resolution (null, 0, or less than 0) gives 1.0 µm. With no plane axes, `res` is 1.0 µm.
 *
 * Example: a scale with 5080 counts for each inch gives `res` = 5 µm and `tol` = 20 µm.
 */
object MeasurementTolerance {

    /** The factor of the step. */
    const val FACTOR: Double = 4.0

    /** The step of an axis with no resolution, and the step with no axes, in µm. */
    const val DEFAULT_STEP_UM: Double = 1.0

    /** Gives the step of one axis in µm, from its [resolution] in counts for each inch. */
    fun stepUm(resolution: Double?): Double =
        if (resolution == null || !(resolution > 0.0)) DEFAULT_STEP_UM else Units.UM_PER_INCH / resolution

    /** Gives `res`: the largest step of the plane axes in µm. */
    fun resolutionUm(planeAxisResolutions: List<Double?>): Double =
        planeAxisResolutions.maxOfOrNull { resolution -> stepUm(resolution) } ?: DEFAULT_STEP_UM

    /** Gives `tol = 4 × res` in µm. */
    fun toleranceUm(planeAxisResolutions: List<Double?>): Double = FACTOR * resolutionUm(planeAxisResolutions)
}
