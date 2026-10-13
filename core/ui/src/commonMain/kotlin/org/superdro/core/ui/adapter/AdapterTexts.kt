package org.superdro.core.ui.adapter

import org.jetbrains.compose.resources.StringResource
import org.superdro.core.model.Channels
import org.superdro.core.protocol.InputChannel
import org.superdro.core.protocol.InputMode
import org.superdro.core.resources.Res
import org.superdro.core.resources.adapter_input_aux1
import org.superdro.core.resources.adapter_input_aux2
import org.superdro.core.resources.adapter_input_letter
import org.superdro.core.resources.adapter_input_probe
import org.superdro.core.resources.adapter_input_tach
import org.superdro.core.resources.adapter_input_w
import org.superdro.core.resources.adapter_input_x
import org.superdro.core.resources.adapter_input_y
import org.superdro.core.resources.adapter_input_z
import org.superdro.core.resources.adapter_mode_bcd7
import org.superdro.core.resources.adapter_mode_bin6
import org.superdro.core.resources.adapter_mode_binary_switch
import org.superdro.core.resources.adapter_mode_digital_probe
import org.superdro.core.resources.adapter_mode_disabled
import org.superdro.core.resources.adapter_mode_igaging21
import org.superdro.core.resources.adapter_mode_igaging_spc
import org.superdro.core.resources.adapter_mode_limit_xy
import org.superdro.core.resources.adapter_mode_limit_zw
import org.superdro.core.resources.adapter_mode_mitutoyo_spc
import org.superdro.core.resources.adapter_mode_quadrature
import org.superdro.core.resources.adapter_mode_tachometer
import org.superdro.core.resources.adapter_mode_unknown
import org.superdro.core.ui.text.UiText

/**
 * The shared texts of the adapter area.
 */
object AdapterTexts {

    /** The text of an adapter value that is not valid. */
    const val NOT_VALID: String = "???"

    /** The name of the channel row: "Input X" to "Input W", "Aux 1", "Aux 2". */
    fun channelName(channel: InputChannel): StringResource = when (channel) {
        InputChannel.X -> Res.string.adapter_input_x
        InputChannel.Y -> Res.string.adapter_input_y
        InputChannel.Z -> Res.string.adapter_input_z
        InputChannel.W -> Res.string.adapter_input_w
        InputChannel.U -> Res.string.adapter_input_aux1
        InputChannel.V -> Res.string.adapter_input_aux2
    }

    /**
     * The name of a row of a legacy adapter with declared inputs: "Aux 1" becomes "Probe"
     * and "Aux 2" becomes "Tach".
     */
    fun declaredRowName(channel: InputChannel): StringResource = when (channel) {
        InputChannel.U -> Res.string.adapter_input_probe
        InputChannel.V -> Res.string.adapter_input_tach
        else -> channelName(channel)
    }

    /** The name of an encoder input `x y z w u v t`: "Input X" to "Input W", "Aux 1", "Aux 2", "Tach". */
    fun encoderInputName(input: Char): UiText = when (Channels.normalize(input)) {
        Channels.T -> UiText.of(Res.string.adapter_input_tach)
        else -> InputChannel.forLetter(Channels.normalize(input))?.let { channel -> UiText.of(channelName(channel)) }
            ?: UiText.of(Res.string.adapter_input_letter, input.uppercaseChar().toString())
    }

    /** The name of an input mode. */
    fun modeName(mode: InputMode): StringResource = when (mode) {
        InputMode.Disabled -> Res.string.adapter_mode_disabled
        InputMode.QuadratureAB -> Res.string.adapter_mode_quadrature
        InputMode.Tachometer -> Res.string.adapter_mode_tachometer
        InputMode.DigitalProbe -> Res.string.adapter_mode_digital_probe
        InputMode.LimitXY -> Res.string.adapter_mode_limit_xy
        InputMode.LimitZW -> Res.string.adapter_mode_limit_zw
        InputMode.BinarySwitch -> Res.string.adapter_mode_binary_switch
        InputMode.Bcd7 -> Res.string.adapter_mode_bcd7
        InputMode.Bin6 -> Res.string.adapter_mode_bin6
        InputMode.IGaging21Bit -> Res.string.adapter_mode_igaging21
        InputMode.IGagingSpc -> Res.string.adapter_mode_igaging_spc
        InputMode.MitutoyoSpc -> Res.string.adapter_mode_mitutoyo_spc
        InputMode.Unknown -> Res.string.adapter_mode_unknown
    }
}
