package org.superdro.core.ui.adapter.encoder

import androidx.compose.runtime.Immutable
import org.superdro.core.model.BacklashMode
import org.superdro.core.model.CalibrationStatus
import org.superdro.core.model.Channels
import org.superdro.core.model.EncoderKind
import org.superdro.core.model.EncoderProfile
import org.superdro.core.model.EncoderType

/** The backlash model of an encoder type. */
enum class BacklashModel {
    /** Scales and direct encoders: the "hysteresis". */
    Lag,

    /** A leadscrew encoder: the gap of the screw and the nut. */
    DeadZone,
}

/**
 * The fields of the encoder settings screen. The text fields keep the text that
 * the user typed. [EncoderSettingsRules.build] makes the encoder profile from them.
 *
 * @property hysteresis The LAG constant in whole counts. A blank text means no hysteresis.
 * @property backlashMode The DEAD_ZONE mode of a leadscrew encoder.
 * @property backlashConstant The DEAD_ZONE constant in whole counts.
 * @property cyclic The "cyclic" switch of the error map of an angular input.
 */
@Immutable
data class EncoderForm(
    val type: EncoderType = EncoderType.Scale,
    val kind: EncoderKind = EncoderKind.Linear,
    val resolution: String = "",
    val countsPerScrewRev: String = "",
    val reversed: Boolean = false,
    val filter: Boolean = false,
    val hysteresis: String = "",
    val backlashMode: BacklashMode = BacklashMode.None,
    val backlashConstant: String = "",
    val cyclic: Boolean = true,
)

/** The rows that the screen shows (the flags C to H). */
@Immutable
data class EncoderVisibility(
    val resolution: Boolean,
    val calibrate: Boolean,
    val hysteresis: Boolean,
    val backlashMode: Boolean,
    val backlashConstant: Boolean,
    val backlashMap: Boolean,
    val countsPerScrewRev: Boolean,
    val cyclic: Boolean,
)

/** A field error of the encoder settings screen. */
enum class EncoderFieldError {
    /** "The resolution must be more than 0." */
    ResolutionNotPositive,

    /** "Type a whole number of counts. A blank field means no hysteresis." */
    HysteresisNotWhole,

    /** "Type a whole number of counts more than 0." */
    BacklashNotPositive,

    /** "The backlash must be less than the counts of one screw turn." */
    BacklashExceedsPitch,

    /** "Type the counts of one screw turn: a number more than 0." */
    ScrewRevRequired,
}

/** The field errors of one form. An empty object means that the form gives a profile. */
@Immutable
data class EncoderFieldErrors(
    val resolution: EncoderFieldError? = null,
    val hysteresis: EncoderFieldError? = null,
    val backlashConstant: EncoderFieldError? = null,
    val countsPerScrewRev: EncoderFieldError? = null,
) {
    /** True when the form has no error. */
    val isEmpty: Boolean get() = resolution == null && hysteresis == null && backlashConstant == null && countsPerScrewRev == null
}

/** The result of [EncoderSettingsRules.build]. */
sealed interface EncoderBuild {
    /** The form gives [profile]. [deletesBacklashMap] is true when the save removes a stored backlash map. */
    data class Ok(val profile: EncoderProfile, val deletesBacklashMap: Boolean) : EncoderBuild

    /** The form has errors. The screen shows them and writes nothing. */
    data class Invalid(val errors: EncoderFieldErrors) : EncoderBuild
}

/**
 * The rules of the encoder settings screen.
 */
object EncoderSettingsRules {

    /** The backlash model of [type]: a leadscrew encoder gives DEAD_ZONE, each other type gives LAG (save rule 1). */
    fun model(type: EncoderType): BacklashModel = if (type == EncoderType.LeadscrewEncoder) BacklashModel.DeadZone else BacklashModel.Lag

    /** Gives the fields of [profile]. */
    fun formOf(profile: EncoderProfile): EncoderForm {
        val lag = model(profile.type) == BacklashModel.Lag
        return EncoderForm(
            type = profile.type,
            kind = profile.kind,
            resolution = EncoderFormat.number(profile.resolution),
            countsPerScrewRev = profile.countsPerScrewRev?.let { EncoderFormat.number(it) }.orEmpty(),
            reversed = profile.reversed,
            filter = profile.filter,
            hysteresis = if (lag && profile.backlashMode != BacklashMode.None && profile.backlashCounts > 0L) profile.backlashCounts.toString() else "",
            backlashMode = if (lag) BacklashMode.None else profile.backlashMode,
            backlashConstant = if (!lag && profile.backlashCounts > 0L) profile.backlashCounts.toString() else "",
            cyclic = profile.errorMapCyclic,
        )
    }

    /**
     * The rows of the form: `C` = the kind is not disabled; `D` = `C` and LAG; `E` = `C` and a
     * leadscrew encoder; `F` = `E` and Constant; `G` = `E` and Map; `H` = `E` and the kind Linear.
     * The "cyclic" switch shows for the kind Angular. "Calibrate" shows for `C`, except for
     * the input `t`: the calibration wizard refuses the tachometer input.
     */
    fun visibility(form: EncoderForm, input: Char = Channels.X): EncoderVisibility {
        val enabled = form.kind != EncoderKind.Disabled
        val lag = enabled && model(form.type) == BacklashModel.Lag
        val leadscrew = enabled && model(form.type) == BacklashModel.DeadZone
        return EncoderVisibility(
            resolution = enabled,
            calibrate = enabled && Channels.normalize(input) != Channels.T,
            hysteresis = lag,
            backlashMode = leadscrew,
            backlashConstant = leadscrew && form.backlashMode == BacklashMode.Constant,
            backlashMap = leadscrew && form.backlashMode == BacklashMode.Map,
            countsPerScrewRev = leadscrew && form.kind == EncoderKind.Linear,
            cyclic = form.kind == EncoderKind.Angular,
        )
    }

    /** Gives the field errors of [form]. Only a visible field can have an error. */
    fun errors(form: EncoderForm): EncoderFieldErrors {
        val visible = visibility(form)
        val resolution = form.resolution.trim().toDoubleOrNull()
        val resolutionError = if (form.kind != EncoderKind.Disabled && (resolution == null || !resolution.isFinite() || resolution <= 0.0)) {
            EncoderFieldError.ResolutionNotPositive
        } else {
            null
        }
        val hysteresisError = if (visible.hysteresis && form.hysteresis.isNotBlank() && wholeCounts(form.hysteresis)?.takeIf { it >= 0L } == null) {
            EncoderFieldError.HysteresisNotWhole
        } else {
            null
        }
        val constant = wholeCounts(form.backlashConstant)
        val screw = form.countsPerScrewRev.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
        val constantError = when {
            !visible.backlashConstant -> null
            constant == null || constant <= 0L -> EncoderFieldError.BacklashNotPositive
            visible.countsPerScrewRev && screw != null && constant >= screw -> EncoderFieldError.BacklashExceedsPitch
            else -> null
        }
        val screwError = if (visible.countsPerScrewRev && form.backlashMode != BacklashMode.None && screw == null) {
            EncoderFieldError.ScrewRevRequired
        } else {
            null
        }
        return EncoderFieldErrors(resolutionError, hysteresisError, constantError, screwError)
    }

    /**
     * Makes the encoder profile of [form] on [base], the stored profile. The save rules:
     *
     * 1. A leadscrew encoder uses DEAD_ZONE; each other type uses LAG.
     * 2. LAG: the mode is Constant when the hysteresis field has a count more than 0, else None.
     *    LAG never gets Map.
     * 3. DEAD_ZONE: the mode is the selected mode.
     * 4. Constant on a linear leadscrew encoder: the constant must be less than the counts of one
     *    screw turn.
     * 5. A resolution change of a profile with the status NotCalibrated gives the status UserSet.
     * 6. The counts of one screw turn stay only for a linear leadscrew encoder. Else they are null.
     * 7. A mode that is not Map removes the backlash map ([EncoderBuild.Ok.deletesBacklashMap]).
     *
     * A disabled input keeps its stored resolution.
     */
    fun build(base: EncoderProfile, form: EncoderForm): EncoderBuild {
        val errors = errors(form)
        if (!errors.isEmpty) return EncoderBuild.Invalid(errors)
        val lag = model(form.type) == BacklashModel.Lag
        val resolution = if (form.kind == EncoderKind.Disabled) {
            form.resolution.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: base.resolution
        } else {
            form.resolution.trim().toDouble()
        }
        val hysteresis = wholeCounts(form.hysteresis) ?: 0L
        val mode = when {
            lag -> if (hysteresis > 0L) BacklashMode.Constant else BacklashMode.None
            else -> form.backlashMode
        }
        val counts = when {
            lag -> if (mode == BacklashMode.Constant) hysteresis else 0L
            mode == BacklashMode.Constant -> wholeCounts(form.backlashConstant) ?: 0L
            else -> 0L
        }
        val keepsScrew = form.type == EncoderType.LeadscrewEncoder && form.kind == EncoderKind.Linear
        val screw = if (keepsScrew) form.countsPerScrewRev.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } else null
        val status = if (resolution != base.resolution && base.calibration.status == CalibrationStatus.NotCalibrated) {
            CalibrationStatus.UserSet
        } else {
            base.calibration.status
        }
        val deletesMap = mode != BacklashMode.Map && base.backlashMap.isNotEmpty()
        val profile = base.copy(
            type = form.type,
            kind = form.kind,
            resolution = resolution,
            countsPerScrewRev = screw,
            reversed = form.reversed,
            filter = form.filter,
            backlashMode = mode,
            backlashCounts = counts,
            backlashMap = if (mode == BacklashMode.Map) base.backlashMap else emptyList(),
            errorMapCyclic = form.cyclic,
            calibration = base.calibration.copy(status = status),
        )
        return EncoderBuild.Ok(profile, deletesMap)
    }

    /** Gives the whole number of counts of [text], or null. */
    fun wholeCounts(text: String): Long? = text.trim().toLongOrNull()
}
