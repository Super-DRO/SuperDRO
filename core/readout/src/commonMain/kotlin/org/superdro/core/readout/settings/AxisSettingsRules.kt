package org.superdro.core.readout.settings

import org.superdro.core.model.AuxiliaryType
import org.superdro.core.model.AxisId
import org.superdro.core.model.Channels
import org.superdro.core.model.InputConfig
import org.superdro.core.model.LinearAxisConfig
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.TachometerMode
import org.superdro.core.model.Workspace
import org.superdro.core.readout.ReadoutMessage
import org.superdro.core.readout.ReadoutText
import org.superdro.core.readout.operations.TachometerActions

/** The result of a settings change. */
sealed interface SettingsResult<out T> {
    /** The change is accepted. [value] is the new value. */
    data class Accepted<T>(val value: T) : SettingsResult<T>

    /** The change is refused with [message]. The old value stays. */
    data class Refused(val message: ReadoutMessage) : SettingsResult<Nothing>
}

/** The fields of the tachometer settings that show for a mode. */
data class TachometerFields(val showPulses: Boolean, val showPresets: Boolean)

/**
 * The validation rules of the axis settings. The settings UI
 * calls these functions and shows the message of a refusal.
 *
 * | Event | Rule |
 * |---|---|
 * | Primary and secondary channel of an axis are the same | Refuse: "Primary and secondary inputs can't use the same prefix". |
 * | The user disables the last of X, Y, Z, and Angular | Refuse: "At least one output axis must be enabled". |
 * | Secondary input on | The secondary channel becomes `w` for X, Y, and Z. If the primary uses `w`, use the default primary channel. Then the input conflict check runs. |
 * | Secondary input off | Secondary channel none, invert false. |
 * | Edge finder sample count not 1 to 10 | Refuse: "Number of edge finder samples must be a number between 1 and 10 (inclusive)". |
 * | Tachometer pulses per revolution changes to a value that is not 0 | The tachometer offsets become 0. |
 * | Tachometer mode | RPM sensor: show the pulses, hide the presets. Preset: show the presets, hide the pulses. Direct entry: hide both. |
 * | Probe enabled | Show or hide the edge finder settings. |
 */
object AxisSettingsRules {

    /** The axes of the "one axis enabled" rule. */
    val OUTPUT_AXES: List<AxisId> = listOf(AxisId.X, AxisId.Y, AxisId.Z, AxisId.Angular)

    /** Sets the primary channel of [config] to [channel]. */
    fun setPrimaryChannel(config: LinearAxisConfig, channel: Char): SettingsResult<LinearAxisConfig> {
        val letter = Channels.normalize(channel)
        val secondary = config.secondary
        if (secondary != null && Channels.normalize(secondary.channel) == letter) return refused(ReadoutText.SamePrefix)
        return SettingsResult.Accepted(config.copy(primary = config.primary.copy(channel = letter)))
    }

    /** Sets the secondary channel of [config] to [channel]. The secondary input must be on. */
    fun setSecondaryChannel(config: LinearAxisConfig, channel: Char): SettingsResult<LinearAxisConfig> {
        val letter = Channels.normalize(channel)
        val secondary = config.secondary ?: InputConfig(letter)
        if (Channels.normalize(config.primary.channel) == letter) return refused(ReadoutText.SamePrefix)
        return SettingsResult.Accepted(config.copy(secondary = secondary.copy(channel = letter)))
    }

    /**
     * Turns the secondary input of [axis] (X, Y, or Z) on or off. On: the secondary channel
     * becomes `w`; when the primary uses `w`, the secondary channel becomes the default primary
     * channel of the axis (`x`, `y`, `z`). Off: no secondary channel, invert false. Run
     * [InputConflicts.check] after the change.
     */
    fun setSecondaryEnabled(axis: AxisId, config: LinearAxisConfig, enabled: Boolean): LinearAxisConfig {
        if (!enabled) return config.copy(secondary = null)
        if (config.secondary != null) return config
        val channel = if (Channels.normalize(config.primary.channel) == Channels.DEFAULT_SECONDARY) {
            Channels.defaultPrimary(axis)
        } else {
            Channels.DEFAULT_SECONDARY
        }
        return config.copy(secondary = InputConfig(channel = channel))
    }

    /** Enables or disables [axis] of [profile]. The last of X, Y, Z, and Angular cannot be disabled. */
    fun setAxisEnabled(profile: MachineProfile, axis: AxisId, enabled: Boolean): SettingsResult<MachineProfile> {
        if (!enabled && axis in OUTPUT_AXES) {
            val othersOn = OUTPUT_AXES.filter { other -> other != axis }.any { other -> enabledIn(profile, other) }
            if (!othersOn) return refused(ReadoutText.OneAxisRequired)
        }
        val changed = when (axis) {
            AxisId.X -> profile.copy(x = profile.x.copy(enabled = enabled))
            AxisId.Y -> profile.copy(y = profile.y.copy(enabled = enabled))
            AxisId.Z -> profile.copy(z = profile.z.copy(enabled = enabled))
            AxisId.Angular -> profile.copy(angular = profile.angular.copy(enabled = enabled))
            AxisId.Caliper -> profile.copy(caliper = profile.caliper.copy(enabled = enabled))
            AxisId.Tachometer -> profile.copy(tachometer = profile.tachometer.copy(enabled = enabled))
        }
        return SettingsResult.Accepted(changed)
    }

    /** Checks the edge finder sample count: an integer from 1 to 10. Null is a text that is not a number. */
    fun setEdgeFinderSampleCount(count: Int?): SettingsResult<Int> =
        if (count == null || count < ProbeProfile.MIN_SAMPLE_COUNT || count > ProbeProfile.MAX_SAMPLE_COUNT) {
            refused(ReadoutText.SampleCountRange)
        } else {
            SettingsResult.Accepted(count)
        }

    /** The tachometer pulses per revolution change from [old] to [new]: a value that is not 0 sets the offsets to 0. */
    fun onTachometerPulsesChanged(workspace: Workspace, old: Double, new: Double): Workspace =
        TachometerActions.onPulsesChanged(workspace, old, new)

    /** Gives the tachometer fields that show for [mode]. */
    fun tachometerFields(mode: TachometerMode): TachometerFields = when (mode) {
        TachometerMode.Sensor -> TachometerFields(showPulses = true, showPresets = false)
        TachometerMode.Preset -> TachometerFields(showPulses = false, showPresets = true)
        TachometerMode.Direct -> TachometerFields(showPulses = false, showPresets = false)
    }

    /** True when the edge finder settings show: the probe is enabled. */
    fun edgeFinderSettingsVisible(probeEnabled: Boolean): Boolean = probeEnabled

    private fun enabledIn(profile: MachineProfile, axis: AxisId): Boolean = when (axis) {
        AxisId.X -> profile.x.enabled
        AxisId.Y -> profile.y.enabled
        AxisId.Z -> profile.z.enabled
        AxisId.Angular -> profile.angular.enabled
        AxisId.Caliper -> profile.caliper.enabled
        AxisId.Tachometer -> profile.tachometer.enabled
    }

    private fun refused(text: ReadoutText): SettingsResult.Refused = SettingsResult.Refused(ReadoutMessage(text))
}

/** One input conflict of the machine setup. */
sealed interface InputConflict {
    /** Two linear axes, or one axis two times, use the input [input]. */
    data class SharedWithinType(val input: Char, val axes: List<AxisId>) : InputConflict

    /** A linear axis and the angular axis use the input [input]. */
    data class SharedAcrossTypes(val input: Char, val axes: List<AxisId>) : InputConflict

    /** The compound slide input [input] is also on X or Z: the projection does not apply. */
    data class CompoundProjectionBlocked(val input: Char) : InputConflict

    /** The quill input [input] is also on X or Y, or is the Z primary input: the head tilt does not apply. */
    data class HeadTiltBlocked(val input: Char) : InputConflict
}

/**
 * The input conflict check of the settings: a letter on two axes, and the
 * compound projection blocked. Two outputs can use the same channel; then both get the value.
 * The check only warns. The setup does not offer A to F.
 */
object InputConflicts {

    /** Gives the conflicts of the enabled axes of [profile]. */
    fun check(profile: MachineProfile): List<InputConflict> {
        val conflicts = ArrayList<InputConflict>()
        val uses = ArrayList<Pair<Char, AxisId>>()
        listOf(AxisId.X to profile.x, AxisId.Y to profile.y, AxisId.Z to profile.z).forEach { (axis, config) ->
            if (!config.enabled) return@forEach
            uses.add(Channels.normalize(config.primary.channel) to axis)
            config.secondary?.let { secondary -> uses.add(Channels.normalize(secondary.channel) to axis) }
        }
        val linearByInput = uses.groupBy({ (input, _) -> input }, { (_, axis) -> axis })
        linearByInput.forEach { (input, axes) -> if (axes.size > 1) conflicts.add(InputConflict.SharedWithinType(input, axes)) }
        if (profile.angular.enabled) {
            val angularInput = Channels.normalize(profile.angular.primary.channel)
            linearByInput[angularInput]?.let { axes -> conflicts.add(InputConflict.SharedAcrossTypes(angularInput, axes + AxisId.Angular)) }
        }
        if (profile.machineType == MachineType.Lathe && profile.auxiliary.type == AuxiliaryType.CompoundSlide && profile.auxiliary.vectoring) {
            val compound = Channels.normalize(profile.y.primary.channel)
            val xz = listOfNotNull(profile.x.primary, profile.x.secondary, profile.z.primary, profile.z.secondary)
                .map { input -> Channels.normalize(input.channel) }
            if (compound in xz) conflicts.add(InputConflict.CompoundProjectionBlocked(compound))
        }
        val quill = profile.z.secondary
        if (profile.machineType == MachineType.VerticalMill && quill != null) {
            val letter = Channels.normalize(quill.channel)
            val xy = listOfNotNull(profile.x.primary, profile.x.secondary, profile.y.primary, profile.y.secondary)
                .map { input -> Channels.normalize(input.channel) }
            if (letter in xy || letter == Channels.normalize(profile.z.primary.channel)) conflicts.add(InputConflict.HeadTiltBlocked(letter))
        }
        return conflicts
    }
}
