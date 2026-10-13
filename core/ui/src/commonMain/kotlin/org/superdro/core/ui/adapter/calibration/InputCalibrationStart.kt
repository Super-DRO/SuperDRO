package org.superdro.core.ui.adapter.calibration

import org.superdro.core.model.AxisId
import org.superdro.core.model.Channels
import org.superdro.core.model.EncoderKind
import org.superdro.core.model.EncoderProfile
import org.superdro.core.model.InputConfig
import org.superdro.core.model.MachineProfile
import org.superdro.core.readout.calibration.CalibrationStart
import org.superdro.core.resources.Res
import org.superdro.core.resources.calibration_disabled_input
import org.superdro.core.resources.calibration_tach_input
import org.superdro.core.ui.navigation.InputCalibrationRoute
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.toUiText

/**
 * The start rules of the calibration wizard of an adapter input.
 *
 * The wizard calibrates one adapter input. The "Calibrate" button of the encoder settings opens it
 * with [InputCalibrationRoute]. An area that starts from an axis (for example the setup reminder
 * banner of the settings area) uses [forAxis]: the function selects the input of the axis and
 * applies the checks "Secondary Input Disabled" and "Input Prefix Missing".
 */
object InputCalibrationStart {

    /** The result of a start from an axis. */
    sealed interface AxisStart {
        /** The wizard can start: push [route] on the back stack of the tab. */
        data class Ready(val route: InputCalibrationRoute) : AxisStart

        /** The wizard cannot start. Show [message]. */
        data class Refused(val message: UiText) : AxisStart
    }

    /**
     * Gives the start of the wizard for the primary input, or for the [secondary] input, of [axis]
     * of [profile]. A secondary input needs its switch on ("Secondary Input Disabled"), and each
     * input needs a channel ("Input Prefix Missing"). The tachometer has no calibration.
     */
    fun forAxis(profile: MachineProfile, axis: AxisId, secondary: Boolean = false): AxisStart {
        val input: InputConfig? = when (axis) {
            AxisId.X -> if (secondary) profile.x.secondary else profile.x.primary
            AxisId.Y -> if (secondary) profile.y.secondary else profile.y.primary
            AxisId.Z -> if (secondary) profile.z.secondary else profile.z.primary
            AxisId.Angular -> if (secondary) null else profile.angular.primary
            AxisId.Caliper -> if (secondary) null else profile.caliper.input
            AxisId.Tachometer -> return AxisStart.Refused(UiText.of(Res.string.calibration_tach_input))
        }
        CalibrationStart.check(input, secondary)?.let { message -> return AxisStart.Refused(message.toUiText()) }
        val letter = Channels.normalize(input!!.channel)
        return inputRefusal(letter, null)?.let(AxisStart::Refused) ?: AxisStart.Ready(InputCalibrationRoute(letter))
    }

    /**
     * Gives the text that stops the wizard of the adapter input [input] with its encoder profile
     * [encoder], or null when the wizard can start:
     * - The letter is not an encoder input: "Input Prefix Missing".
     * - The input `t` (the tachometer) has its pulses, not a calibration.
     * - An encoder profile of the kind "disabled" gives no counts to calibrate.
     */
    fun inputRefusal(input: Char, encoder: EncoderProfile?): UiText? {
        val letter = Channels.normalize(input)
        if (letter == Channels.T) return UiText.of(Res.string.calibration_tach_input)
        CalibrationStart.check(InputConfig(letter), secondary = false)?.let { message -> return message.toUiText() }
        if (encoder?.kind == EncoderKind.Disabled) return UiText.of(Res.string.calibration_disabled_input)
        return null
    }
}
