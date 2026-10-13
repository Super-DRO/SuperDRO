package org.superdro.core.readout.input

import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.AuxiliaryType
import org.superdro.core.model.AxisId
import org.superdro.core.model.Channels
import org.superdro.core.model.EncoderProfile
import org.superdro.core.model.InputConfig
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.ProbeContactType
import org.superdro.core.model.TachometerMode
import org.superdro.core.model.Workspace
import org.superdro.core.readout.encoder.EncoderProfileCheck
import org.superdro.core.readout.encoder.EncoderProfileProblem

/** The function of a term in the sum of an axis. */
enum class TermRole {
    /** The primary input of the axis. */
    Primary,

    /** The secondary input of the axis. */
    Secondary,

    /** The compound slide input on X or Z of a lathe. */
    Compound,

    /** The quill input on X or Y of a vertical mill. */
    HeadTilt,

    /** The quill input on Z of a vertical mill. It replaces the secondary term. */
    Quill,
}

/**
 * One term of the weighted sum of an axis: `value × weight × sign`.
 *
 * @property input The lowercase adapter input letter.
 * @property sign +1, or −1 for the axis invert of the machine profile.
 */
data class AxisTerm(
    val input: Char,
    val weight: Double = 1.0,
    val sign: Int = 1,
    val role: TermRole = TermRole.Primary,
) {
    /** `weight × sign`. */
    val factor: Double get() = weight * sign
}

/**
 * The terms of one axis.
 *
 * @property enabled True when the axis shows. The probe latch reads only the enabled axes.
 */
data class AxisTerms(
    val axis: AxisId,
    val terms: List<AxisTerm>,
    val enabled: Boolean = true,
) {
    /** The input letters of the terms. */
    val inputs: Set<Char> get() = terms.mapTo(LinkedHashSet()) { term -> term.input }

    /**
     * True when a projection changes the axis (a compound or a head tilt term, or a quill weight
     * that is not 1). The readout then shows the warning state.
     */
    val projected: Boolean
        get() = terms.any { term ->
            term.role == TermRole.Compound || term.role == TermRole.HeadTilt || (term.role == TermRole.Quill && term.weight != 1.0)
        }
}

/**
 * The probe values of the adapter.
 *
 * @property debounceMs The debounce in ms. 0 accepts each activation.
 */
data class ProbeSettings(
    val contactType: ProbeContactType = ProbeContactType.Auto,
    val debounceMs: Long = AdapterProfile.DEFAULT_PROBE_DEBOUNCE_MS,
) {
    /** The raw value that is active: 0 for a normally closed contact, else 1. */
    val activeValue: Int get() = if (contactType == ProbeContactType.NormallyClosed) 0 else 1

    /** The watchdog period `T = max(5 × debounce, 1000 ms)`. */
    val watchdogPeriodMs: Long get() = maxOf(5L * debounceMs, MIN_WATCHDOG_MS)

    companion object {
        const val MIN_WATCHDOG_MS: Long = 1000L
    }
}

/**
 * The configuration of an [InputStage]: the encoder profile of each adapter input, the terms of
 * each axis, and the probe values.
 *
 * @property encoders The encoder profile of each lowercase input letter. An input with no profile
 *   gives "no value". [from] puts the checked profiles here: a map that fails the check is empty.
 * @property problems The problems of the stored encoder profile of each input with a problem.
 *   The UI shows them in the "Error" state of the axis and in the encoder settings.
 */
data class InputConfiguration(
    val encoders: Map<Char, EncoderProfile> = emptyMap(),
    val axes: Map<AxisId, AxisTerms> = emptyMap(),
    val probe: ProbeSettings = ProbeSettings(),
    val problems: Map<Char, List<EncoderProfileProblem>> = emptyMap(),
) {
    /** Gives the problems of the input [input], or an empty list. */
    fun problemsOf(input: Char): List<EncoderProfileProblem> = problems[Channels.normalize(input)].orEmpty()

    companion object {
        /**
         * Gives the configuration of [profile] with its adapter [adapter] and the projection state
         * of [workspace].
         *
         * | Readout | Terms (weight) |
         * |---|---|
         * | X | Primary (1). Secondary (1). Lathe compound term. Mill head tilt X term. |
         * | Y | Primary (1). Secondary (1), not with a lathe compound slide. Mill head tilt Y term. |
         * | Z, lathe or horizontal mill | Primary (1). Secondary (1). Lathe compound Z term. |
         * | Z, vertical mill | Primary (1). The quill term on the secondary input (1, or the head tilt weight). |
         * | Caliper | Primary (1). |
         * | Angular | Primary (1). |
         * | Tachometer | One term on `t` with the tachometer invert, only in the mode RPM sensor. |
         *
         * Each encoder profile goes through [EncoderProfileCheck.checked]: a map
         * that fails the check gives no correction, and [problems] keeps the problem list.
         */
        fun from(
            profile: MachineProfile,
            adapter: AdapterProfile?,
            workspace: Workspace? = profile.activeWorkspace,
        ): InputConfiguration {
            val checked = adapter?.encoders.orEmpty().entries.associate { (letter, encoder) ->
                Channels.normalize(letter) to EncoderProfileCheck.checked(encoder)
            }
            val encoders = checked.mapValues { (_, result) -> result.profile }
            val problems = checked.filterValues { result -> result.problems.isNotEmpty() }.mapValues { (_, result) -> result.problems }
            val probe = adapter?.let { ProbeSettings(it.probeContactType, it.probeDebounceMs) } ?: ProbeSettings()
            return InputConfiguration(encoders, axisTerms(profile, workspace), probe, problems)
        }

        /** Gives the terms of each axis of [profile] (see [from]). */
        fun axisTerms(profile: MachineProfile, workspace: Workspace? = profile.activeWorkspace): Map<AxisId, AxisTerms> {
            val type = profile.machineType
            val x = profile.x
            val y = profile.y
            val z = profile.z
            val compound = workspace?.compoundProjection
            val tilt = workspace?.headTilt

            val xTerms = ArrayList<AxisTerm>()
            val yTerms = ArrayList<AxisTerm>()
            val zTerms = ArrayList<AxisTerm>()
            xTerms.add(term(x.primary, TermRole.Primary))
            x.secondary?.let { secondary -> xTerms.add(term(secondary, TermRole.Secondary)) }
            yTerms.add(term(y.primary, TermRole.Primary))
            val compoundSlide = type == MachineType.Lathe && profile.auxiliary.type == AuxiliaryType.CompoundSlide
            if (!compoundSlide) y.secondary?.let { secondary -> yTerms.add(term(secondary, TermRole.Secondary)) }
            zTerms.add(term(z.primary, TermRole.Primary))

            when (type) {
                MachineType.VerticalMill -> {
                    val quill = z.secondary
                    if (quill != null) {
                        val quillLetter = Channels.normalize(quill.channel)
                        val xyInputs = listOfNotNull(x.primary, x.secondary, y.primary, y.secondary)
                            .map { input -> Channels.normalize(input.channel) }
                        val allowed = quillLetter !in xyInputs && quillLetter != Channels.normalize(z.primary.channel)
                        val tiltState = tilt?.takeIf { allowed }
                        val quillWeight = tiltState?.let { HeadTiltWeights.quillWeight(it) } ?: 1.0
                        zTerms.add(term(quill, TermRole.Quill, quillWeight))
                        tiltState?.let { state ->
                            HeadTiltWeights.xWeight(state)?.let { weight -> xTerms.add(term(quill, TermRole.HeadTilt, weight)) }
                            HeadTiltWeights.yWeight(state)?.let { weight -> yTerms.add(term(quill, TermRole.HeadTilt, weight)) }
                        }
                    }
                }
                MachineType.Lathe, MachineType.HorizontalMill -> {
                    z.secondary?.let { secondary -> zTerms.add(term(secondary, TermRole.Secondary)) }
                    if (compoundSlide && profile.auxiliary.vectoring && compound != null) {
                        val compoundLetter = Channels.normalize(y.primary.channel)
                        val xzInputs = listOfNotNull(x.primary, x.secondary, z.primary, z.secondary)
                            .map { input -> Channels.normalize(input.channel) }
                        if (compoundLetter !in xzInputs) {
                            val reference = profile.auxiliary.angleReferenceDegrees.toDouble()
                            CompoundProjectionWeights.xWeight(compound, reference)
                                ?.let { weight -> xTerms.add(term(y.primary, TermRole.Compound, weight)) }
                            CompoundProjectionWeights.zWeight(compound, reference)
                                ?.let { weight -> zTerms.add(term(y.primary, TermRole.Compound, weight)) }
                        }
                    }
                }
            }

            val tachTerms = if (profile.tachometer.mode == TachometerMode.Sensor) {
                listOf(AxisTerm(Channels.T, 1.0, if (profile.tachometer.invert) -1 else 1, TermRole.Primary))
            } else {
                emptyList()
            }
            return linkedMapOf(
                AxisId.X to AxisTerms(AxisId.X, xTerms, x.enabled),
                AxisId.Y to AxisTerms(AxisId.Y, yTerms, y.enabled),
                AxisId.Z to AxisTerms(AxisId.Z, zTerms, z.enabled),
                AxisId.Angular to AxisTerms(AxisId.Angular, listOf(term(profile.angular.primary, TermRole.Primary)), profile.angular.enabled),
                AxisId.Caliper to AxisTerms(AxisId.Caliper, listOf(term(profile.caliper.input, TermRole.Primary)), profile.caliper.enabled),
                AxisId.Tachometer to AxisTerms(AxisId.Tachometer, tachTerms, profile.tachometer.enabled),
            )
        }

        private fun term(input: InputConfig, role: TermRole, weight: Double = 1.0): AxisTerm =
            AxisTerm(Channels.normalize(input.channel), input.weight * weight, if (input.sign < 0) -1 else 1, role)
    }
}
