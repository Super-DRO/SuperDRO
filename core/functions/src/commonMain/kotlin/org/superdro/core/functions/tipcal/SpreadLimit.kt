package org.superdro.core.functions.tipcal

import kotlin.math.max
import org.superdro.core.model.ProbeProfile

/** The source of a spread limit. */
enum class SpreadLimitSource {
    /** The spread limit of the profile. */
    Profile,

    /** `max(10 µm, 3 × calibration residual)` from the last tip calibration. */
    Calibration,

    /** The default 20 µm. */
    Default,
}

/**
 * A spread limit and its source.
 *
 * @property valueUm The limit in µm.
 */
data class SpreadLimit(val valueUm: Double, val source: SpreadLimitSource)

/**
 * The sample technique of a probe profile.
 *
 * @property sampleCount The number of samples for each edge, 1 or more.
 */
data class ProbeTechnique(val sampleCount: Int, val spreadLimit: SpreadLimit)

/** The spread limit rule. */
object SpreadLimits {

    /** The default limit: 20 µm. */
    const val DEFAULT_UM: Double = 20.0

    /** The lowest limit that a calibration residual can give: 10 µm. */
    const val CALIBRATION_FLOOR_UM: Double = 10.0

    /** The factor of the calibration residual: 3. */
    const val RESIDUAL_FACTOR: Double = 3.0

    /**
     * Gives the spread limit of [profile]:
     *
     * 1. The profile value, if it is finite and more than 0.
     * 2. Else `max(10 µm, 3 × calibration residual)`, if the residual is finite and more than 0.
     * 3. Else 20 µm. This also applies when there is no profile.
     *
     * The default value of [ProbeProfile.spreadLimitUm] is 20 µm. Thus rule 2 applies only when
     * the user clears the profile value.
     */
    fun of(profile: ProbeProfile?): SpreadLimit {
        val profileValue = profile?.spreadLimitUm
        if (profileValue != null && profileValue.isFinite() && profileValue > 0.0) {
            return SpreadLimit(profileValue, SpreadLimitSource.Profile)
        }
        val residual = profile?.calibrationResidualUm
        if (residual != null && residual.isFinite() && residual > 0.0) {
            return SpreadLimit(max(CALIBRATION_FLOOR_UM, RESIDUAL_FACTOR * residual), SpreadLimitSource.Calibration)
        }
        return SpreadLimit(DEFAULT_UM, SpreadLimitSource.Default)
    }

    /**
     * Gives the technique of [profile]: the sample count `max(1, profile.sampleCount)` (1 with
     * no profile) and the spread limit of [of].
     */
    fun techniqueOf(profile: ProbeProfile?): ProbeTechnique =
        ProbeTechnique(sampleCount = max(1, profile?.sampleCount ?: 1), spreadLimit = of(profile))

    /**
     * Gives the spread of the samples of one edge: max − min. An empty list gives 0.
     */
    fun sampleSpreadUm(samples: List<Double>): Double =
        if (samples.isEmpty()) 0.0 else samples.max() - samples.min()
}
