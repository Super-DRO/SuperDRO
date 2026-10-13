package org.superdro.core.functions.zreference

import org.superdro.core.functions.FunctionMessage
import org.superdro.core.functions.FunctionSound
import org.superdro.core.functions.MessageTemplate
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.ProbeConfig
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.ProbeType
import org.superdro.core.model.Units

/** The texts of "Set Z Reference". File `strings_z_reference.xml`. */
object ZReferenceTexts {
    val SETTER_HEIGHT_NOT_POSITIVE = MessageTemplate(
        "z_reference_error_setter_height",
        "Tool setter height must be a positive number",
    )
    val Z_NOT_ENABLED = MessageTemplate("z_reference_error_z_not_enabled", "Z readout must be enabled")
    val NOT_CONNECTED = MessageTemplate("z_reference_error_not_connected", "The adapter must be connected")
    val Z_POSITION_UNKNOWN = MessageTemplate(
        "z_reference_error_z_position_unknown",
        "Could not read the Z position at the moment of contact. Try again.",
    )
    val TITLE = MessageTemplate("z_reference_title", "Set Z Reference")

    /** All templates of this object, for the check against the string file. */
    val all: List<MessageTemplate> by lazy { listOf(SETTER_HEIGHT_NOT_POSITIVE, Z_NOT_ENABLED, NOT_CONNECTED, Z_POSITION_UNKNOWN, TITLE) }
}

/**
 * The result of "Set Z Reference".
 */
sealed interface ZReferenceResult {

    /**
     * The set is correct.
     *
     * @property zAbsoluteUm The new absolute value of Z: `A_µm + B_µm`. The readout calls
     *   `h(zAbsoluteUm)` on Z, and Z changes to the absolute mode.
     * @property offsetDeltaUm Only in the automatic mode: the change of the Z absolute offset, so
     *   that Z reads [zAbsoluteUm] at the moment of the contact: `(A + B) − Z_abs(t)`.
     * @property setterHeightUm The value A to store in the machine profile, or null when A comes
     *   from a probe profile.
     */
    data class Applied(
        val zAbsoluteUm: Double,
        val offsetDeltaUm: Double? = null,
        val setterHeightUm: Double? = null,
    ) : ZReferenceResult {
        /** The automatic mode plays the short beep. Then the sheet closes. */
        val sound: FunctionSound get() = FunctionSound.Short
    }

    /** The set is not possible. The automatic mode plays the error sound. */
    data class Failure(val message: FunctionMessage) : ZReferenceResult {
        val sound: FunctionSound get() = FunctionSound.Error
    }
}

/**
 * "Set Z Reference" and the tool height setter.
 *
 * Usage: put the setter on the reference surface, then touch the top of the setter with the
 * tool tip. The reference surface then reads B.
 */
object ZReference {

    /** Converts a typed value to µm: `× 1000` for mm, `× 25400` for inch. */
    fun toMicrometers(value: Double, metric: Boolean): Double =
        value * (if (metric) Units.UM_PER_MM else Units.UM_PER_INCH)

    /**
     * The checks of the manual and of the automatic mode: "Z readout must be enabled" when
     * Z is not enabled, "The adapter must be connected" when the session is not `Connected`. Null
     * when "Apply" is enabled.
     */
    fun block(zEnabled: Boolean, connected: Boolean): FunctionMessage? = when {
        !zEnabled -> ZReferenceTexts.Z_NOT_ENABLED.message()
        !connected -> ZReferenceTexts.NOT_CONNECTED.message()
        else -> null
    }

    /** The profile types that the Z reference accepts: touch probe and tool height setter. */
    fun accepts(type: ProbeType): Boolean = type == ProbeType.TouchProbe || type == ProbeType.ToolHeightSetter

    /**
     * The setter height A from the `ZOffset` of a probe profile. Null when the profile type
     * is not a touch probe or a tool height setter. The calculation refuses a value that is not
     * more than 0.
     */
    fun setterHeightFromProfile(profile: ProbeProfile?): Double? =
        profile?.takeIf { accepts(it.type) }?.zOffsetUm

    /**
     * The button "Auto" shows when the probe and `probe_tool_setter_enable` are on.
     */
    fun autoVisible(probe: ProbeConfig): Boolean = probe.enabled && probe.toolSetterEnabled

    /**
     * The manual mode "Apply":
     *
     * 1. A is required and must be more than 0 ("Tool setter height must be a positive number").
     * 2. B is 0 when empty.
     * 3. `h(A_µm + B_µm)` on Z, and Z changes to the absolute mode.
     *
     * @param setterHeightUm A in µm, or null when the field is empty.
     * @param desiredZUm B in µm, or null when the field is empty.
     * @param fromProfile True when A comes from a probe profile. Then the result has no value to
     *   store in the machine profile.
     */
    fun apply(
        setterHeightUm: Double?,
        desiredZUm: Double?,
        zEnabled: Boolean,
        connected: Boolean,
        fromProfile: Boolean = false,
    ): ZReferenceResult {
        block(zEnabled, connected)?.let { message -> return ZReferenceResult.Failure(message) }
        val a = setterHeightUm
        if (a == null || a.isNaN() || a <= 0.0) {
            return ZReferenceResult.Failure(ZReferenceTexts.SETTER_HEIGHT_NOT_POSITIVE.message())
        }
        val b = desiredZUm?.takeIf { !it.isNaN() } ?: 0.0
        return ZReferenceResult.Applied(zAbsoluteUm = a + b, setterHeightUm = if (fromProfile) null else a)
    }

    /**
     * The automatic mode: at the probe closure, the app runs the calculation of [apply]
     * with the same checks. The Z position comes from the latch at the trigger time, so Z
     * reads `A + B` at the moment of the contact.
     *
     * @param latchedAbsoluteZUm The absolute Z at the trigger time `t`, or null when it is not known.
     */
    fun applyAtTrigger(
        setterHeightUm: Double?,
        desiredZUm: Double?,
        zEnabled: Boolean,
        connected: Boolean,
        latchedAbsoluteZUm: Double?,
        fromProfile: Boolean = false,
    ): ZReferenceResult {
        val result = apply(setterHeightUm, desiredZUm, zEnabled, connected, fromProfile)
        if (result !is ZReferenceResult.Applied) return result
        if (latchedAbsoluteZUm == null || latchedAbsoluteZUm.isNaN()) {
            return ZReferenceResult.Failure(ZReferenceTexts.Z_POSITION_UNKNOWN.message())
        }
        return result.copy(offsetDeltaUm = result.zAbsoluteUm - latchedAbsoluteZUm)
    }

    /**
     * Stores A (µm) and its unit in the machine profile.
     */
    fun storeSetterHeight(profile: MachineProfile, setterHeightUm: Double, metric: Boolean): MachineProfile =
        profile.copy(zSetterHeightUm = setterHeightUm, zSetterMetric = metric)
}
