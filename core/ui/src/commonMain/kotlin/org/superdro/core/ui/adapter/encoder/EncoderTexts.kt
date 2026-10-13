package org.superdro.core.ui.adapter.encoder

import org.jetbrains.compose.resources.StringResource
import org.superdro.core.model.AxisId
import org.superdro.core.model.BacklashMode
import org.superdro.core.model.CalibrationStatus
import org.superdro.core.model.EncoderKind
import org.superdro.core.model.EncoderType
import org.superdro.core.model.MachineType
import org.superdro.core.resources.Res
import org.superdro.core.resources.encoder_axis_angular
import org.superdro.core.resources.encoder_axis_caliper
import org.superdro.core.resources.encoder_axis_tachometer
import org.superdro.core.resources.encoder_axis_x
import org.superdro.core.resources.encoder_axis_y
import org.superdro.core.resources.encoder_axis_z
import org.superdro.core.resources.encoder_backlash_constant_mode
import org.superdro.core.resources.encoder_backlash_map_mode
import org.superdro.core.resources.encoder_backlash_none
import org.superdro.core.resources.encoder_error_backlash
import org.superdro.core.resources.encoder_error_backlash_pitch
import org.superdro.core.resources.encoder_error_hysteresis
import org.superdro.core.resources.encoder_error_resolution
import org.superdro.core.resources.encoder_error_screw_rev
import org.superdro.core.resources.encoder_kind_angular
import org.superdro.core.resources.encoder_kind_disabled
import org.superdro.core.resources.encoder_kind_linear
import org.superdro.core.resources.encoder_machine_horizontal_mill
import org.superdro.core.resources.encoder_machine_lathe
import org.superdro.core.resources.encoder_machine_vertical_mill
import org.superdro.core.resources.encoder_status_calibrated
import org.superdro.core.resources.encoder_status_ignored
import org.superdro.core.resources.encoder_status_not_calibrated
import org.superdro.core.resources.encoder_status_user_set
import org.superdro.core.resources.encoder_type_leadscrew
import org.superdro.core.resources.encoder_type_rack
import org.superdro.core.resources.encoder_type_rotary
import org.superdro.core.resources.encoder_type_scale

/** The texts of the values of the encoder area. */
object EncoderTexts {

    fun type(type: EncoderType): StringResource = when (type) {
        EncoderType.Scale -> Res.string.encoder_type_scale
        EncoderType.LeadscrewEncoder -> Res.string.encoder_type_leadscrew
        EncoderType.RotaryEncoder -> Res.string.encoder_type_rotary
        EncoderType.RackPinion -> Res.string.encoder_type_rack
    }

    fun kind(kind: EncoderKind): StringResource = when (kind) {
        EncoderKind.Disabled -> Res.string.encoder_kind_disabled
        EncoderKind.Linear -> Res.string.encoder_kind_linear
        EncoderKind.Angular -> Res.string.encoder_kind_angular
    }

    fun backlash(mode: BacklashMode): StringResource = when (mode) {
        BacklashMode.None -> Res.string.encoder_backlash_none
        BacklashMode.Constant -> Res.string.encoder_backlash_constant_mode
        BacklashMode.Map -> Res.string.encoder_backlash_map_mode
    }

    fun status(status: CalibrationStatus): StringResource = when (status) {
        CalibrationStatus.NotCalibrated -> Res.string.encoder_status_not_calibrated
        CalibrationStatus.Calibrated -> Res.string.encoder_status_calibrated
        CalibrationStatus.UserSet -> Res.string.encoder_status_user_set
        CalibrationStatus.Ignored -> Res.string.encoder_status_ignored
    }

    fun machine(type: MachineType): StringResource = when (type) {
        MachineType.VerticalMill -> Res.string.encoder_machine_vertical_mill
        MachineType.HorizontalMill -> Res.string.encoder_machine_horizontal_mill
        MachineType.Lathe -> Res.string.encoder_machine_lathe
    }

    fun axis(axis: AxisId): StringResource = when (axis) {
        AxisId.X -> Res.string.encoder_axis_x
        AxisId.Y -> Res.string.encoder_axis_y
        AxisId.Z -> Res.string.encoder_axis_z
        AxisId.Angular -> Res.string.encoder_axis_angular
        AxisId.Caliper -> Res.string.encoder_axis_caliper
        AxisId.Tachometer -> Res.string.encoder_axis_tachometer
    }

    fun fieldError(error: EncoderFieldError): StringResource = when (error) {
        EncoderFieldError.ResolutionNotPositive -> Res.string.encoder_error_resolution
        EncoderFieldError.HysteresisNotWhole -> Res.string.encoder_error_hysteresis
        EncoderFieldError.BacklashNotPositive -> Res.string.encoder_error_backlash
        EncoderFieldError.BacklashExceedsPitch -> Res.string.encoder_error_backlash_pitch
        EncoderFieldError.ScrewRevRequired -> Res.string.encoder_error_screw_rev
    }
}
