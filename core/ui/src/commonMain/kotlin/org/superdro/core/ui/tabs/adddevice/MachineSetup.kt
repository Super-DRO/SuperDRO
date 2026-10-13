package org.superdro.core.ui.tabs.adddevice

import androidx.compose.runtime.Immutable
import org.superdro.core.model.AuxiliaryConfig
import org.superdro.core.model.AuxiliaryType
import org.superdro.core.model.AxisId
import org.superdro.core.model.Channels
import org.superdro.core.model.InputConfig
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.readout.ReadoutMessage
import org.superdro.core.readout.ReadoutText
import org.superdro.core.readout.settings.InputConflict
import org.superdro.core.readout.settings.InputConflicts
import org.superdro.core.resources.Res
import org.superdro.core.resources.add_device_conflict_angular
import org.superdro.core.resources.add_device_conflict_compound
import org.superdro.core.resources.add_device_conflict_head_tilt
import org.superdro.core.resources.add_device_conflict_shared
import org.superdro.core.resources.add_device_conflict_twice
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.toUiText

/**
 * One input of the machine setup: a switch and the input letter.
 *
 * @property channel The lowercase wire letter: one of `x y z w u v`. The setup does not
 *   offer `a` to `f`.
 */
@Immutable
data class SetupInput(val enabled: Boolean, val channel: Char)

/**
 * The answers of the machine setup step of the add-device sheet: the machine type, the
 * units, and the inputs of each axis.
 *
 * | Field | Default | Machine |
 * |---|---|---|
 * | X input (lathe: cross slide) | on, `x` | all |
 * | Y input | on, `y` | mill |
 * | Z input (lathe: carriage) | on, `z` | all |
 * | Quill scale (the secondary input of Z) | off, `w` | mill |
 * | Auxiliary readout (the Y output) | off, `y` | lathe |
 * | Auxiliary readout type | tailstock | lathe |
 * | Tachometer | off | all |
 *
 * "Mill" is a vertical mill or a horizontal mill. The setup writes no resolution: the encoder
 * profiles of the adapter hold it, and the setup reminder banner shows until the user sets it.
 */
@Immutable
data class MachineSetup(
    val machineType: MachineType = MachineType.VerticalMill,
    val metric: Boolean = false,
    val x: SetupInput = SetupInput(true, Channels.X),
    val y: SetupInput = SetupInput(true, Channels.Y),
    val z: SetupInput = SetupInput(true, Channels.Z),
    val quill: SetupInput = SetupInput(false, Channels.W),
    val auxiliary: SetupInput = SetupInput(false, Channels.Y),
    val auxiliaryType: AuxiliaryType = AuxiliaryType.Tailstock,
    val tachometer: Boolean = false,
) {
    /** True for a lathe. */
    val lathe: Boolean get() = machineType.isLathe

    /**
     * Gives the profile of the answers with the name [name]: [MachineProfile.create] with the
     * units and the inputs of the answers.
     */
    fun toProfile(name: String): MachineProfile {
        val base = MachineProfile.create(machineType, name)
        val yInput = if (lathe) auxiliary else y
        return base.copy(
            metricDefault = metric,
            x = base.x.copy(enabled = x.enabled, primary = InputConfig(x.channel)),
            y = base.y.copy(enabled = yInput.enabled, primary = InputConfig(yInput.channel)),
            z = base.z.copy(
                enabled = z.enabled,
                primary = InputConfig(z.channel),
                secondary = if (!lathe && quill.enabled) InputConfig(quill.channel) else null,
            ),
            auxiliary = if (lathe) AuxiliaryConfig(type = auxiliaryType) else base.auxiliary,
            tachometer = base.tachometer.copy(enabled = tachometer),
        )
    }

    /**
     * Gives the refusal of the answers, or null. One of X, Y, and Z must be on ("At least one
     * output axis must be enabled"), and the quill input must not be the Z input.
     */
    fun refusal(): UiText? {
        val yOn = if (lathe) auxiliary.enabled else y.enabled
        if (!x.enabled && !yOn && !z.enabled) return ReadoutMessage(ReadoutText.OneAxisRequired).toUiText()
        if (!lathe && quill.enabled && z.enabled && Channels.normalize(quill.channel) == Channels.normalize(z.channel)) {
            return ReadoutMessage(ReadoutText.SamePrefix).toUiText()
        }
        return null
    }

    /** Gives the warnings of the input conflict check of `core:readout`. The setup can continue with them. */
    fun conflicts(): List<UiText> {
        val profile = toProfile("")
        return InputConflicts.check(profile).map { conflict -> conflictText(conflict, profile) }
    }

    companion object {
        /** The letters that the setup offers for an axis input. */
        val LETTERS: List<Char> = Channels.axisChannels
    }
}

/** Gives the SuperDRO text of an input conflict. The letters show in upper case. */
fun conflictText(conflict: InputConflict, profile: MachineProfile): UiText = when (conflict) {
    is InputConflict.SharedWithinType -> {
        val axes = conflict.axes.distinct()
        if (axes.size == 1) {
            UiText.of(Res.string.add_device_conflict_twice, letter(conflict.input), axisLabel(axes.single(), profile))
        } else {
            UiText.of(Res.string.add_device_conflict_shared, letter(conflict.input), axes.joinToString(", ") { axisLabel(it, profile) })
        }
    }
    is InputConflict.SharedAcrossTypes ->
        UiText.of(Res.string.add_device_conflict_angular, letter(conflict.input), conflict.axes.distinct().joinToString(", ") { axisLabel(it, profile) })
    is InputConflict.CompoundProjectionBlocked -> UiText.of(Res.string.add_device_conflict_compound, letter(conflict.input))
    is InputConflict.HeadTiltBlocked -> UiText.of(Res.string.add_device_conflict_head_tilt, letter(conflict.input))
}

private fun letter(input: Char): String = input.uppercaseChar().toString()

private fun axisLabel(axis: AxisId, profile: MachineProfile): String = when (axis) {
    AxisId.X -> profile.x.label
    AxisId.Y -> profile.y.label
    AxisId.Z -> profile.z.label
    AxisId.Angular -> profile.angular.label
    AxisId.Caliper -> "Caliper"
    AxisId.Tachometer -> profile.tachometer.label
}
