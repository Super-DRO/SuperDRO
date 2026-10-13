package org.superdro.core.ui.adapter.probe

import androidx.compose.runtime.Immutable
import kotlin.time.Instant
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.ProbeType
import org.superdro.core.resources.Res
import org.superdro.core.resources.probelib_error_count
import org.superdro.core.resources.probelib_error_diameter_required
import org.superdro.core.resources.probelib_error_not_negative
import org.superdro.core.resources.probelib_error_number
import org.superdro.core.ui.text.UiText

/** The fields of the probe profile screen that can have an error. */
enum class ProbeField { Diameter, SampleCount, MaxSpread, XOffset, YOffset, ZOffset }

/**
 * The texts of the probe profile screen. The lengths are in the
 * units of [metric].
 *
 * @property name The typed name. An empty name gives the automatic name.
 * @property port The trigger port `p` or `q`, or null for "None".
 */
@Immutable
data class ProbeProfileForm(
    val type: ProbeType = ProbeType.TouchProbe,
    val name: String = "",
    val metric: Boolean = true,
    val port: Char? = ProbeTypeRules.defaultPort(ProbeType.TouchProbe),
    val diameter: String = "",
    val sampleCount: String = "",
    val maxSpread: String = "",
    val xOffset: String = "",
    val yOffset: String = "",
    val zOffset: String = "",
) {
    companion object {
        /** The longest name: 50 characters. */
        const val MAX_NAME_LENGTH: Int = 50

        /** Gives the form of [profile] in the units of the profile. The default sample count gives an empty field. */
        fun of(profile: ProbeProfile): ProbeProfileForm {
            val metric = profile.metric
            fun length(um: Double): String = ProbeLengths.text(um, metric)
            return ProbeProfileForm(
                type = profile.type,
                name = profile.name,
                metric = metric,
                port = ProbeTypeRules.fixPort(profile.type, profile.port),
                diameter = if (ProbeTypeRules.usesDiameter(profile.type)) length(profile.stylusDiameterUm) else "",
                sampleCount = if (profile.sampleCount == ProbeProfile.DEFAULT_SAMPLE_COUNT) "" else profile.sampleCount.toString(),
                maxSpread = profile.spreadLimitUm?.let(::length).orEmpty(),
                xOffset = length(profile.xOffsetUm),
                yOffset = length(profile.yOffsetUm),
                zOffset = length(profile.zOffsetUm),
            )
        }
    }
}

/** The result of [ProbeProfileSave.build]. */
sealed interface ProbeSaveResult {
    /** The form has errors. The profile does not change. */
    data class Invalid(val errors: Map<ProbeField, UiText>) : ProbeSaveResult

    /** The profile to write. */
    data class Valid(val profile: ProbeProfile) : ProbeSaveResult
}

/**
 * The save rules of the probe profile screen:
 *
 * 1. Check each visible field.
 * 2. Clear the values that the type does not use: the diameter, the X and Y offsets, the Z offset.
 * 3. Without a trigger port, clear the sample count (the default 1) and the spread limit.
 * 4. Keep the calibration values: the effective diameter, the residual, the date, and the session.
 *    Keep the session digest only when a session exists.
 * 5. A new profile gets the machine profile ID [machineProfileId] (null: for all machine profiles).
 */
object ProbeProfileSave {

    /**
     * Checks [form] and gives the profile to write.
     *
     * @param existing The stored profile, or null for a new profile.
     * @param autoName The automatic name, for an empty name field.
     * @param newId The ID of a new profile.
     */
    fun build(
        form: ProbeProfileForm,
        existing: ProbeProfile?,
        machineProfileId: String?,
        autoName: String,
        newId: String,
    ): ProbeSaveResult {
        val type = existing?.type ?: form.type
        val metric = form.metric
        val errors = LinkedHashMap<ProbeField, UiText>()
        val port = ProbeTypeRules.fixPort(type, form.port)

        val usesDiameter = ProbeTypeRules.usesDiameter(type)
        val diameter = if (usesDiameter) {
            requiredLength(form.diameter, metric, ProbeField.Diameter, errors)
        } else {
            0.0
        }
        val sampleCount = if (port != null) count(form.sampleCount, errors) else ProbeProfile.DEFAULT_SAMPLE_COUNT
        val spread = if (port != null) optionalLength(form.maxSpread, metric, ProbeField.MaxSpread, errors, notNegative = true) else null
        val usesXy = ProbeTypeRules.usesXyOffsets(type)
        val x = if (usesXy) optionalLength(form.xOffset, metric, ProbeField.XOffset, errors, notNegative = false) ?: 0.0 else 0.0
        val y = if (usesXy) optionalLength(form.yOffset, metric, ProbeField.YOffset, errors, notNegative = false) ?: 0.0 else 0.0
        val usesZ = ProbeTypeRules.usesZOffset(type)
        val z = if (usesZ) optionalLength(form.zOffset, metric, ProbeField.ZOffset, errors, notNegative = false) ?: 0.0 else 0.0
        if (errors.isNotEmpty()) return ProbeSaveResult.Invalid(errors)

        val name = form.name.trim().ifEmpty { autoName }.take(ProbeProfileForm.MAX_NAME_LENGTH)
        val base = existing ?: ProbeProfile(id = newId, profileId = machineProfileId, type = type)
        val session = base.calibrationSession
        return ProbeSaveResult.Valid(
            base.copy(
                type = type,
                name = name,
                port = port,
                stylusDiameterUm = diameter ?: 0.0,
                effectiveDiameterUm = if (usesDiameter) base.effectiveDiameterUm else null,
                xOffsetUm = x,
                yOffsetUm = y,
                zOffsetUm = z,
                metric = metric,
                sampleCount = sampleCount ?: ProbeProfile.DEFAULT_SAMPLE_COUNT,
                spreadLimitUm = spread,
                sessionDigest = if (session != null) base.sessionDigest else null,
                standardTip = false,
            ),
        )
    }

    /**
     * The automatic name of a new profile: the type name, and the diameter with its unit
     * when the type uses a diameter and the diameter field has a value more than 0.
     *
     * @param typeLabel The name of the type in the language of the user.
     * @param unitLabel The unit name: "mm" or "in".
     */
    fun autoName(form: ProbeProfileForm, typeLabel: String, unitLabel: String): String {
        val diameterUm = ProbeLengths.parseUm(form.diameter, form.metric)
        return if (ProbeTypeRules.usesDiameter(form.type) && diameterUm != null && diameterUm > 0.0) {
            "$typeLabel ${ProbeLengths.text(diameterUm, form.metric)} $unitLabel"
        } else {
            typeLabel
        }
    }

    /** True when the calibrated diameter [effectiveUm] is the diameter of the field (the green mark of 13.5.2). */
    fun matchesCalibration(form: ProbeProfileForm, effectiveUm: Double?, calibratedOn: Instant?): Boolean {
        if (effectiveUm == null || calibratedOn == null) return false
        return form.diameter.trim() == ProbeLengths.text(effectiveUm, form.metric)
    }

    private fun requiredLength(text: String, metric: Boolean, field: ProbeField, errors: MutableMap<ProbeField, UiText>): Double? {
        if (text.isBlank()) {
            errors[field] = UiText.of(Res.string.probelib_error_diameter_required)
            return null
        }
        return optionalLength(text, metric, field, errors, notNegative = true)
    }

    private fun optionalLength(
        text: String,
        metric: Boolean,
        field: ProbeField,
        errors: MutableMap<ProbeField, UiText>,
        notNegative: Boolean,
    ): Double? {
        if (text.isBlank()) return null
        val um = ProbeLengths.parseUm(text, metric)
        when {
            um == null -> errors[field] = UiText.of(if (notNegative) Res.string.probelib_error_not_negative else Res.string.probelib_error_number)
            notNegative && um < 0.0 -> errors[field] = UiText.of(Res.string.probelib_error_not_negative)
        }
        return um
    }

    private fun count(text: String, errors: MutableMap<ProbeField, UiText>): Int? {
        if (text.isBlank()) return ProbeProfile.DEFAULT_SAMPLE_COUNT
        val value = text.trim().toIntOrNull()
        if (value == null || value < ProbeProfile.MIN_SAMPLE_COUNT || value > ProbeProfile.MAX_SAMPLE_COUNT) {
            errors[ProbeField.SampleCount] = UiText.of(Res.string.probelib_error_count, ProbeProfile.MIN_SAMPLE_COUNT, ProbeProfile.MAX_SAMPLE_COUNT)
            return null
        }
        return value
    }
}
