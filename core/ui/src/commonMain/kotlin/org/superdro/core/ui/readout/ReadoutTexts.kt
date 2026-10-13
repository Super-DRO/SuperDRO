package org.superdro.core.ui.readout

import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.superdro.core.connection.LinkFailure
import org.superdro.core.readout.DisplayUnit
import org.superdro.core.readout.operations.GlobalModeState
import org.superdro.core.readout.operations.GlobalUnitsState
import org.superdro.core.resources.Res
import org.superdro.core.resources.app_cause_bluetooth_off
import org.superdro.core.resources.app_cause_connect_failed
import org.superdro.core.resources.app_cause_link_lost
import org.superdro.core.resources.app_cause_no_network
import org.superdro.core.resources.app_cause_permission_missing
import org.superdro.core.resources.app_cause_unplugged
import org.superdro.core.resources.ic_check
import org.superdro.core.resources.ic_circle
import org.superdro.core.resources.ic_error
import org.superdro.core.resources.ic_hourglass
import org.superdro.core.resources.ic_info
import org.superdro.core.resources.ic_lock
import org.superdro.core.resources.ic_pause
import org.superdro.core.resources.ic_sync
import org.superdro.core.resources.ic_warning
import org.superdro.core.resources.readout_core_state_error
import org.superdro.core.resources.readout_core_state_no_input
import org.superdro.core.resources.readout_state_held
import org.superdro.core.resources.readout_state_modified
import org.superdro.core.resources.readout_state_offline
import org.superdro.core.resources.readout_state_waiting
import org.superdro.core.resources.readout_status_awaiting_data
import org.superdro.core.resources.readout_status_connected
import org.superdro.core.resources.readout_status_connecting
import org.superdro.core.resources.readout_status_disconnected
import org.superdro.core.resources.readout_status_failed
import org.superdro.core.resources.readout_status_no_data
import org.superdro.core.resources.readout_status_reconnecting
import org.superdro.core.resources.readout_status_waiting_device
import org.superdro.core.resources.readout_unit_degrees
import org.superdro.core.resources.readout_unit_divisions
import org.superdro.core.resources.readout_unit_inch
import org.superdro.core.resources.readout_unit_m_min
import org.superdro.core.resources.readout_unit_mm
import org.superdro.core.resources.readout_unit_name_degrees
import org.superdro.core.resources.readout_unit_name_divisions
import org.superdro.core.resources.readout_unit_name_inch
import org.superdro.core.resources.readout_unit_name_m_min
import org.superdro.core.resources.readout_unit_name_mm
import org.superdro.core.resources.readout_unit_name_rpm
import org.superdro.core.resources.readout_unit_name_sfm
import org.superdro.core.resources.readout_unit_rpm
import org.superdro.core.resources.readout_unit_sfm
import org.superdro.core.resources.strip_align_axes
import org.superdro.core.resources.strip_angle_measure
import org.superdro.core.resources.strip_arbitrary_hole_circle
import org.superdro.core.resources.strip_arc_cut
import org.superdro.core.resources.strip_compound
import org.superdro.core.resources.strip_diameter
import org.superdro.core.resources.strip_dimension_measure
import org.superdro.core.resources.strip_edge_finder
import org.superdro.core.resources.strip_equidistant_array
import org.superdro.core.resources.strip_graphical_view
import org.superdro.core.resources.strip_head_tilt
import org.superdro.core.resources.strip_height_compensation
import org.superdro.core.resources.strip_hole_circle
import org.superdro.core.resources.strip_hole_grid
import org.superdro.core.resources.strip_instrument
import org.superdro.core.resources.strip_lathe_tool_offset
import org.superdro.core.resources.strip_mixed
import org.superdro.core.resources.strip_mode_absolute
import org.superdro.core.resources.strip_mode_incremental
import org.superdro.core.resources.strip_radius
import org.superdro.core.resources.strip_radius_compensation
import org.superdro.core.resources.strip_rotate_part
import org.superdro.core.resources.strip_thread_setup
import org.superdro.core.resources.strip_units_inch
import org.superdro.core.resources.strip_units_mm
import org.superdro.core.resources.strip_z_reference

/** The string resources and the icons of the readout screen (each status has an icon and a text). */
object ReadoutTexts {

    /** The short symbol of [unit], for example "mm". */
    fun unitSymbol(unit: DisplayUnit): StringResource = when (unit) {
        DisplayUnit.Inch -> Res.string.readout_unit_inch
        DisplayUnit.Millimeter -> Res.string.readout_unit_mm
        DisplayUnit.Degrees -> Res.string.readout_unit_degrees
        DisplayUnit.Divisions -> Res.string.readout_unit_divisions
        DisplayUnit.Rpm -> Res.string.readout_unit_rpm
        DisplayUnit.SurfaceFeetPerMinute -> Res.string.readout_unit_sfm
        DisplayUnit.MetersPerMinute -> Res.string.readout_unit_m_min
    }

    /** The full name of [unit] for the semantic label, for example "millimeters". */
    fun unitName(unit: DisplayUnit): StringResource = when (unit) {
        DisplayUnit.Inch -> Res.string.readout_unit_name_inch
        DisplayUnit.Millimeter -> Res.string.readout_unit_name_mm
        DisplayUnit.Degrees -> Res.string.readout_unit_name_degrees
        DisplayUnit.Divisions -> Res.string.readout_unit_name_divisions
        DisplayUnit.Rpm -> Res.string.readout_unit_name_rpm
        DisplayUnit.SurfaceFeetPerMinute -> Res.string.readout_unit_name_sfm
        DisplayUnit.MetersPerMinute -> Res.string.readout_unit_name_m_min
    }

    /** The text of a display state, or null for a normal value. */
    fun stateText(state: ValueState): StringResource? = when (state) {
        ValueState.Normal -> null
        ValueState.Modified -> Res.string.readout_state_modified
        ValueState.Pending -> Res.string.readout_state_waiting
        ValueState.Error -> Res.string.readout_core_state_error
        ValueState.NoInput -> Res.string.readout_core_state_no_input
        ValueState.Held -> Res.string.readout_state_held
        ValueState.Offline -> Res.string.readout_state_offline
    }

    /** The icon of a display state, or null for a normal value. */
    fun stateIcon(state: ValueState): DrawableResource? = when (state) {
        ValueState.Normal -> null
        ValueState.Modified -> Res.drawable.ic_warning
        ValueState.Pending -> Res.drawable.ic_hourglass
        ValueState.Error -> Res.drawable.ic_error
        ValueState.NoInput -> Res.drawable.ic_info
        ValueState.Held -> Res.drawable.ic_lock
        ValueState.Offline -> Res.drawable.ic_sync
    }

    /** The text of the link status. */
    fun linkText(status: LinkStatus): StringResource = when (status) {
        LinkStatus.Connected -> Res.string.readout_status_connected
        is LinkStatus.Connecting -> Res.string.readout_status_connecting
        LinkStatus.AwaitingData -> Res.string.readout_status_awaiting_data
        LinkStatus.NoData -> Res.string.readout_status_no_data
        is LinkStatus.Reconnecting -> Res.string.readout_status_reconnecting
        is LinkStatus.WaitingForDevice -> causeText(status.cause) ?: Res.string.readout_status_waiting_device
        is LinkStatus.Failed -> causeText(status.cause) ?: Res.string.readout_status_failed
        LinkStatus.Disconnected -> Res.string.readout_status_disconnected
    }

    /** The icon of the link status. */
    fun linkIcon(status: LinkStatus): DrawableResource = when (status) {
        LinkStatus.Connected -> Res.drawable.ic_circle
        is LinkStatus.Connecting, LinkStatus.AwaitingData, is LinkStatus.Reconnecting -> Res.drawable.ic_sync
        LinkStatus.NoData -> Res.drawable.ic_warning
        is LinkStatus.WaitingForDevice, is LinkStatus.Failed -> Res.drawable.ic_error
        LinkStatus.Disconnected -> Res.drawable.ic_pause
    }

    /** The text of the cause of a link failure, for example "Bluetooth off". */
    fun causeText(cause: LinkFailure): StringResource? = when (cause) {
        LinkFailure.RadioOff -> Res.string.app_cause_bluetooth_off
        LinkFailure.DeviceGone -> Res.string.app_cause_unplugged
        LinkFailure.NetworkOff -> Res.string.app_cause_no_network
        LinkFailure.PermissionMissing -> Res.string.app_cause_permission_missing
        LinkFailure.Refused -> Res.string.app_cause_connect_failed
        LinkFailure.Lost -> Res.string.app_cause_link_lost
    }

    /** The text of the global Mode button and of the mode chip. */
    fun modeText(mode: GlobalModeState): StringResource = when (mode) {
        GlobalModeState.Absolute -> Res.string.strip_mode_absolute
        GlobalModeState.Incremental -> Res.string.strip_mode_incremental
        GlobalModeState.Mixed -> Res.string.strip_mixed
    }

    /** The text of the global Units button and of the units chip. */
    fun unitsText(units: GlobalUnitsState): StringResource = when (units) {
        GlobalUnitsState.Inch -> Res.string.strip_units_inch
        GlobalUnitsState.Metric -> Res.string.strip_units_mm
        GlobalUnitsState.Mixed -> Res.string.strip_mixed
    }

    /** The icon of a mixed state, or null (the mixed state has its own icon). */
    fun mixedIcon(mixed: Boolean): DrawableResource? = if (mixed) Res.drawable.ic_warning else null

    /** The icon of the "active" state of a compensation button. */
    val activeIcon: DrawableResource get() = Res.drawable.ic_check

    /** The label of a function button. */
    fun functionLabel(button: FunctionButtonUiState): StringResource = when (button.button) {
        FunctionButton.RadiusDiameter -> if (button.toggled) Res.string.strip_diameter else Res.string.strip_radius
        FunctionButton.LatheToolOffset -> Res.string.strip_lathe_tool_offset
        FunctionButton.ZReference -> Res.string.strip_z_reference
        FunctionButton.RadiusCompensation -> Res.string.strip_radius_compensation
        FunctionButton.HeightCompensation -> Res.string.strip_height_compensation
        FunctionButton.EdgeFinder -> Res.string.strip_edge_finder
        FunctionButton.HoleCircle -> Res.string.strip_hole_circle
        FunctionButton.ArbitraryHoleCircle -> Res.string.strip_arbitrary_hole_circle
        FunctionButton.ArcCut -> Res.string.strip_arc_cut
        FunctionButton.HoleGrid -> Res.string.strip_hole_grid
        FunctionButton.EquidistantArray -> Res.string.strip_equidistant_array
        FunctionButton.DimensionMeasure -> Res.string.strip_dimension_measure
        FunctionButton.AngleMeasure -> Res.string.strip_angle_measure
        FunctionButton.GraphicalView -> Res.string.strip_graphical_view
        FunctionButton.ThreadSetup -> Res.string.strip_thread_setup
        FunctionButton.CompoundCompensation -> Res.string.strip_compound
        FunctionButton.HeadTiltCompensation -> Res.string.strip_head_tilt
        FunctionButton.RotatePart -> Res.string.strip_rotate_part
        FunctionButton.AlignAxes -> Res.string.strip_align_axes
        FunctionButton.ReferenceInstrument -> Res.string.strip_instrument
    }
}
