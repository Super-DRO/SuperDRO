package org.superdro.core.ui.functions.workspace

import org.superdro.core.model.AxisId
import org.superdro.core.model.MachineProfile
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.resources.Res
import org.superdro.core.resources.fn_unit_inch
import org.superdro.core.resources.fn_unit_mm
import org.superdro.core.resources.ws_degrees
import org.superdro.core.resources.ws_shift_line
import org.superdro.core.ui.functions.common.LengthTexts
import org.superdro.core.ui.text.UiText

/** The texts of the positions and the shifts of the workspace commands. */
object WorkspaceTexts {

    /** Gives the label of [axis] of [profile], for example "X" or "α". */
    fun label(profile: MachineProfile, axis: AxisId): String = when (axis) {
        AxisId.X -> profile.x.label
        AxisId.Y -> profile.y.label
        AxisId.Z -> profile.z.label
        AxisId.Angular -> profile.angular.label
        else -> axis.name
    }

    /**
     * Gives the value [value] of [axis] as the readout shows it: µm in the unit of the axis, with
     * the diameter on a lathe X in the diameter mode, or arc seconds in degrees. [signed] adds the
     * sign `+` to a positive value.
     */
    fun value(frame: ReadoutFrame, axis: AxisId, value: Double, signed: Boolean = false): UiText {
        if (axis == AxisId.Angular) {
            val text = LengthTexts.degrees(value)
            return UiText.of(Res.string.ws_degrees, if (signed && value >= 0.0) "+$text" else text)
        }
        val metric = frame.metric(axis)
        val shown = if (axis == AxisId.X && frame.profile.machineType.isLathe && frame.state(AxisId.X).diameterMode) value * 2.0 else value
        val text = LengthTexts.format(shown, metric, frame.profile.display.readoutFormat)
        return UiText.Plain(if (signed && !text.startsWith("-")) "+$text" else text)
    }

    /** Gives the unit text of [axis]: "mm", "in", or empty for the angular axis. */
    fun unit(frame: ReadoutFrame, axis: AxisId): UiText =
        if (axis == AxisId.Angular) UiText.Plain("") else UiText.of(if (frame.metric(axis)) Res.string.fn_unit_mm else Res.string.fn_unit_inch)

    /** Gives one line of a shift list: "X +1.2345 in". */
    fun shiftLine(frame: ReadoutFrame, axis: AxisId, shift: Double): UiText =
        UiText.of(Res.string.ws_shift_line, label(frame.profile, axis), value(frame, axis, shift, signed = true), unit(frame, axis))
}
