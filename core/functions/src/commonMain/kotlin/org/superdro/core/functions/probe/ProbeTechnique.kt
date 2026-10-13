package org.superdro.core.functions.probe

import org.superdro.core.functions.tipcal.SpreadLimits
import org.superdro.core.model.AxisId
import org.superdro.core.model.ProbeConfig
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.ProbeType

/**
 * The values that the edge finder takes from the probe profile. The spread limit has one rule:
 * [SpreadLimits.of].
 */
object ProbeTechnique {

    /** The sample count `N`: the profile value in the range 1 to 10, else 1. */
    fun sampleCount(profile: ProbeProfile?): Int =
        (profile?.sampleCount ?: ProbeProfile.DEFAULT_SAMPLE_COUNT)
            .coerceIn(ProbeProfile.MIN_SAMPLE_COUNT, ProbeProfile.MAX_SAMPLE_COUNT)

    /**
     * The stylus diameter of the profile: the calibrated (effective) diameter, else the nominal
     * diameter, else 0.
     */
    fun stylusDiameterUm(profile: ProbeProfile?): Double {
        val effective = profile?.effectiveDiameterUm
        if (effective != null && effective.isFinite() && effective > 0.0) return effective
        return profile?.stylusDiameterUm?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
    }

    /**
     * The offset that the edge finder adds to a probe sample of [axis] (one rule for the
     * single-edge sampler and for AUTO):
     *
     * ```text
     * offset = profile offset of the axis − (H of the axis, if active)
     * ```
     *
     * `H` is the measurement instrument offset that the readout adds in measurement mode. The
     * latched absolute position includes it, so the edge finder removes it and uses its own
     * profile offset. The Z sampler of the edge finder uses the offset 0.
     *
     * @param instrumentOffsetUm The `H` of the axis, or null when it is not active.
     */
    fun sampleOffsetUm(axis: AxisId, profile: ProbeProfile?, instrumentOffsetUm: Double? = null): Double {
        val profileOffset = when (axis) {
            AxisId.X -> profile?.xOffsetUm ?: 0.0
            AxisId.Y -> profile?.yOffsetUm ?: 0.0
            else -> 0.0
        }
        return profileOffset - (instrumentOffsetUm ?: 0.0)
    }

    /** The profile types that the edge finder accepts: touch probe, 2D edge finder, camera. */
    fun edgeFinderAccepts(type: ProbeType): Boolean =
        type == ProbeType.TouchProbe || type == ProbeType.EdgeFinder2D || type == ProbeType.Camera

    /** The profile types that can capture a Z position in the edge finder: only the touch probe. */
    fun zCaptureAccepts(type: ProbeType): Boolean = type == ProbeType.TouchProbe

    /**
     * The probe edge finder is "on" when the probe and the probe edge finder are on, and the
     * selected profile (if any) has a trigger port. Else a tap on an edge captures the current
     * position.
     */
    fun probeEdgeFinderOn(probe: ProbeConfig, profile: ProbeProfile?): Boolean =
        probe.enabled && probe.edgeFinderEnabled && (profile == null || profile.port != null)
}

/**
 * The samples of one edge. The spread is `max − min` of the samples.
 */
data class SampleSet(val values: List<Double> = emptyList()) {

    /** The number of samples. */
    val count: Int get() = values.size

    /** Adds one sample. */
    fun add(valueUm: Double): SampleSet = SampleSet(values + valueUm)

    /** The mean of the samples, or null with no sample. */
    val meanUm: Double? get() = if (values.isEmpty()) null else values.sum() / values.size

    /** The spread `max − min` of the samples, or 0 with no sample ([SpreadLimits.sampleSpreadUm]). */
    val spreadUm: Double get() = SpreadLimits.sampleSpreadUm(values)
}
