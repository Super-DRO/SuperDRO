package org.superdro.core.ui.settings.machine

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.camera.CrosshairColor
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.AuxiliaryType
import org.superdro.core.model.AxisButtonMode
import org.superdro.core.model.AxisId
import org.superdro.core.model.Channels
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.MuteScope
import org.superdro.core.model.PatternAngleConvention
import org.superdro.core.model.ReadoutFont
import org.superdro.core.model.ReadoutFormat
import org.superdro.core.model.SidePanelMode
import org.superdro.core.model.TachometerMode
import org.superdro.core.model.TransportKind
import org.superdro.core.resources.Res
import org.superdro.core.resources.add_device_aux_compound
import org.superdro.core.resources.add_device_aux_other
import org.superdro.core.resources.add_device_aux_tailstock
import org.superdro.core.resources.add_device_auxiliary_type
import org.superdro.core.resources.add_device_conflicts_title
import org.superdro.core.resources.add_device_units_inch
import org.superdro.core.resources.add_device_units_mm
import org.superdro.core.resources.route_machine_settings
import org.superdro.core.resources.settings_machine_3d_sub_datums
import org.superdro.core.resources.settings_machine_3d_sub_datums_summary
import org.superdro.core.resources.settings_machine_activate_sub_datum
import org.superdro.core.resources.settings_machine_activate_sub_datum_summary
import org.superdro.core.resources.settings_machine_angle_convention
import org.superdro.core.resources.settings_machine_angle_convention_lathe
import org.superdro.core.resources.settings_machine_angle_convention_summary
import org.superdro.core.resources.settings_machine_angle_legacy
import org.superdro.core.resources.settings_machine_angle_reference
import org.superdro.core.resources.settings_machine_angle_standard
import org.superdro.core.resources.settings_machine_angular_enabled
import org.superdro.core.resources.settings_machine_at_once
import org.superdro.core.resources.settings_machine_auxiliary_enabled
import org.superdro.core.resources.settings_machine_axis_enabled
import org.superdro.core.resources.settings_machine_baud_rate
import org.superdro.core.resources.settings_machine_baud_rate_summary
import org.superdro.core.resources.settings_machine_bluetooth_address
import org.superdro.core.resources.settings_machine_bluetooth_address_summary
import org.superdro.core.resources.settings_machine_button
import org.superdro.core.resources.settings_machine_button_mode
import org.superdro.core.resources.settings_machine_caliper_enabled
import org.superdro.core.resources.settings_machine_caliper_summary
import org.superdro.core.resources.settings_machine_camera_overlays
import org.superdro.core.resources.settings_machine_camera_overlays_summary
import org.superdro.core.resources.settings_machine_channel
import org.superdro.core.resources.settings_machine_configuration_commands
import org.superdro.core.resources.settings_machine_configuration_commands_summary
import org.superdro.core.resources.settings_machine_crosshair
import org.superdro.core.resources.settings_machine_encoder
import org.superdro.core.resources.settings_machine_encoder_summary
import org.superdro.core.resources.settings_machine_no_adapter_profile
import org.superdro.core.resources.settings_machine_no_tab
import org.superdro.core.resources.settings_machine_profile_file_no_tab
import org.superdro.core.resources.settings_machine_font
import org.superdro.core.resources.settings_machine_font_monospace
import org.superdro.core.resources.settings_machine_font_system
import org.superdro.core.resources.settings_machine_format
import org.superdro.core.resources.settings_machine_format_value
import org.superdro.core.resources.settings_machine_function_angle
import org.superdro.core.resources.settings_machine_function_arbitrary_circle
import org.superdro.core.resources.settings_machine_function_arc_cut
import org.superdro.core.resources.settings_machine_function_compound
import org.superdro.core.resources.settings_machine_function_dimension
import org.superdro.core.resources.settings_machine_function_edge_finder
import org.superdro.core.resources.settings_machine_function_equidistant
import org.superdro.core.resources.settings_machine_function_head_tilt
import org.superdro.core.resources.settings_machine_function_height
import org.superdro.core.resources.settings_machine_function_hole_circle
import org.superdro.core.resources.settings_machine_function_hole_grid
import org.superdro.core.resources.settings_machine_function_radius
import org.superdro.core.resources.settings_machine_function_strip_note
import org.superdro.core.resources.settings_machine_function_z_reference
import org.superdro.core.resources.settings_machine_group_angular
import org.superdro.core.resources.settings_machine_group_auxiliary
import org.superdro.core.resources.settings_machine_group_caliper
import org.superdro.core.resources.settings_machine_group_display
import org.superdro.core.resources.settings_machine_group_functions
import org.superdro.core.resources.settings_machine_group_link
import org.superdro.core.resources.settings_machine_group_machine
import org.superdro.core.resources.settings_machine_group_probe
import org.superdro.core.resources.settings_machine_group_tachometer
import org.superdro.core.resources.settings_machine_group_x
import org.superdro.core.resources.settings_machine_group_y
import org.superdro.core.resources.settings_machine_group_z
import org.superdro.core.resources.settings_machine_invert
import org.superdro.core.resources.settings_machine_label
import org.superdro.core.resources.settings_machine_label_summary
import org.superdro.core.resources.settings_machine_leadscrew_pitch
import org.superdro.core.resources.settings_machine_leadscrew_pitch_summary
import org.superdro.core.resources.settings_machine_link_demo
import org.superdro.core.resources.settings_machine_link_type
import org.superdro.core.resources.settings_machine_metric
import org.superdro.core.resources.settings_machine_metric_summary
import org.superdro.core.resources.settings_machine_mode_abs_inc
import org.superdro.core.resources.settings_machine_mode_center
import org.superdro.core.resources.settings_machine_mode_degrees
import org.superdro.core.resources.settings_machine_mode_divide
import org.superdro.core.resources.settings_machine_mode_hold
import org.superdro.core.resources.settings_machine_mode_units
import org.superdro.core.resources.settings_machine_mode_zero
import org.superdro.core.resources.settings_machine_mute
import org.superdro.core.resources.settings_machine_mute_app_scope
import org.superdro.core.resources.settings_machine_mute_summary
import org.superdro.core.resources.settings_machine_name
import org.superdro.core.resources.settings_machine_no_profile
import org.superdro.core.resources.settings_machine_presets
import org.superdro.core.resources.settings_machine_presets_summary
import org.superdro.core.resources.settings_machine_probe_edge_finder
import org.superdro.core.resources.settings_machine_probe_edge_finder_summary
import org.superdro.core.resources.settings_machine_probe_enabled
import org.superdro.core.resources.settings_machine_probe_input
import org.superdro.core.resources.settings_machine_probe_input_summary
import org.superdro.core.resources.settings_machine_probe_profiles
import org.superdro.core.resources.settings_machine_probe_profiles_summary
import org.superdro.core.resources.settings_machine_probe_summary
import org.superdro.core.resources.settings_machine_probe_tool_setter
import org.superdro.core.resources.settings_machine_probe_tool_setter_summary
import org.superdro.core.resources.settings_machine_pulses
import org.superdro.core.resources.settings_machine_pulses_no_adapter
import org.superdro.core.resources.settings_machine_pulses_summary
import org.superdro.core.resources.settings_machine_secondary
import org.superdro.core.resources.settings_machine_secondary_summary
import org.superdro.core.resources.settings_machine_section_auxiliary
import org.superdro.core.resources.settings_machine_section_axis
import org.superdro.core.resources.settings_machine_section_behavior
import org.superdro.core.resources.settings_machine_section_caliper
import org.superdro.core.resources.settings_machine_section_camera
import org.superdro.core.resources.settings_machine_section_communication
import org.superdro.core.resources.settings_machine_section_function_strip
import org.superdro.core.resources.settings_machine_section_link
import org.superdro.core.resources.settings_machine_section_primary
import org.superdro.core.resources.settings_machine_section_probe
import org.superdro.core.resources.settings_machine_section_profile
import org.superdro.core.resources.settings_machine_section_readout
import org.superdro.core.resources.settings_machine_section_secondary
import org.superdro.core.resources.settings_machine_section_sounds
import org.superdro.core.resources.settings_machine_section_tachometer
import org.superdro.core.resources.settings_machine_section_workspace
import org.superdro.core.resources.settings_machine_shared_tools
import org.superdro.core.resources.settings_machine_shared_tools_summary
import org.superdro.core.resources.settings_machine_short_press_zero
import org.superdro.core.resources.settings_machine_short_press_zero_summary
import org.superdro.core.resources.settings_machine_show_collapsed
import org.superdro.core.resources.settings_machine_show_collapsed_summary
import org.superdro.core.resources.settings_machine_show_expanded
import org.superdro.core.resources.settings_machine_side_panel
import org.superdro.core.resources.settings_machine_side_panel_machine_datums
import org.superdro.core.resources.settings_machine_side_panel_off
import org.superdro.core.resources.settings_machine_side_panel_sub_datums
import org.superdro.core.resources.settings_machine_side_panel_tools
import org.superdro.core.resources.settings_machine_sliders
import org.superdro.core.resources.settings_machine_sliders_summary
import org.superdro.core.resources.settings_machine_tachometer_direct
import org.superdro.core.resources.settings_machine_tachometer_enabled
import org.superdro.core.resources.settings_machine_tachometer_invert_summary
import org.superdro.core.resources.settings_machine_tachometer_mode
import org.superdro.core.resources.settings_machine_tachometer_preset
import org.superdro.core.resources.settings_machine_tachometer_sensor
import org.superdro.core.resources.settings_machine_two_way
import org.superdro.core.resources.settings_machine_two_way_summary
import org.superdro.core.resources.settings_machine_type
import org.superdro.core.resources.settings_machine_type_summary
import org.superdro.core.resources.settings_machine_usb_device
import org.superdro.core.resources.settings_machine_usb_select
import org.superdro.core.resources.settings_machine_vectoring
import org.superdro.core.resources.settings_machine_vectoring_summary
import org.superdro.core.resources.settings_machine_wifi_host
import org.superdro.core.resources.settings_machine_wifi_host_summary
import org.superdro.core.resources.settings_machine_wifi_port
import org.superdro.core.resources.settings_machine_wifi_port_summary
import org.superdro.core.ui.linkName
import org.superdro.core.ui.navigation.ScreenScaffold
import org.superdro.core.ui.settings.components.NoticeKind
import org.superdro.core.ui.settings.components.SettingsDropdown
import org.superdro.core.ui.settings.components.SettingsGroupItem
import org.superdro.core.ui.settings.components.SettingsGroupLayout
import org.superdro.core.ui.settings.components.SettingsLink
import org.superdro.core.ui.settings.components.SettingsNote
import org.superdro.core.ui.settings.components.SettingsNotice
import org.superdro.core.ui.settings.components.SettingsSection
import org.superdro.core.ui.settings.components.SettingsSegmented
import org.superdro.core.ui.settings.components.SettingsSwitch
import org.superdro.core.ui.settings.components.SettingsTextField
import org.superdro.core.ui.settings.help.HelpButton
import org.superdro.core.ui.settings.help.HelpTopic
import org.superdro.core.ui.settings.typechange.MachineTypeChangeDialog
import org.superdro.core.ui.tabs.adddevice.machineTypeLabel
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.text.stringResourceForKey
import org.superdro.core.ui.theme.LocalTouchTargets
import org.superdro.core.ui.theme.accentRadioColors
import org.superdro.core.ui.theme.readoutFontFamily
import org.superdro.core.resources.route_profile_file
import org.superdro.core.resources.integration_settings_profile_file_summary

/** The test tag of the row or the field [name] of the machine settings. */
fun machineSettingTag(name: String): String = "machine-setting:$name"

/** The routes that the machine settings open. The entry connects them to the back stacks. */
interface MachineSettingsNavigation {
    /** The encoder settings of the adapter input [input] (resolution, hysteresis, filter). */
    fun openEncoder(input: Char) = Unit

    /** The probe profiles of the probe library. */
    fun openProbeLibrary() = Unit

    /** The probe input settings of the adapter. */
    fun openProbeInput() = Unit

    /** The profile file export and import of the profile. */
    fun openProfileFile() = Unit
}

/**
 * The machine settings screen of one profile: one
 * group for each part of the profile. Each row shows the stored value and changes it at once.
 * With no tab ([MachineSettingsUiState.tab] false), a notice shows, and the rows that need the tab
 * or an adapter profile are disabled with a short note.
 * The composable is stateless: [state] gives each value, and [events] gets each change.
 */
@Composable
fun MachineSettingsScreen(
    state: MachineSettingsUiState,
    events: MachineSettingsEvents,
    onBack: () -> Unit,
    navigation: MachineSettingsNavigation = object : MachineSettingsNavigation {},
) {
    ScreenScaffold(
        title = stringResource(Res.string.route_machine_settings),
        onBack = onBack,
        actions = { HelpButton(HelpTopic.MachineSettings) },
    ) {
        val profile = state.profile
        when {
            !state.loaded -> Unit
            profile == null -> SettingsNote(stringResource(Res.string.settings_machine_no_profile), Modifier.padding(24.dp))
            else -> SettingsGroupLayout(
                groups = state.groups.map { group -> SettingsGroupItem(group, groupTitle(group, profile.machineType)) },
                selected = state.group,
                onSelect = events::selectGroup,
                modifier = Modifier.fillMaxSize(),
            ) { group -> GroupContent(group, state, profile, events, navigation) }
        }
    }
    state.typeChange?.let { question ->
        MachineTypeChangeDialog(
            state = question,
            onDisposal = events::setTypeChangeDisposal,
            onConfirm = events::confirmTypeChange,
            onDismiss = events::cancelTypeChange,
        )
    }
}

@Composable
private fun groupTitle(group: MachineSettingsGroup, type: MachineType): String = stringResource(
    when (group) {
        MachineSettingsGroup.Machine -> Res.string.settings_machine_group_machine
        MachineSettingsGroup.Display -> Res.string.settings_machine_group_display
        MachineSettingsGroup.Functions -> Res.string.settings_machine_group_functions
        MachineSettingsGroup.AxisX -> Res.string.settings_machine_group_x
        MachineSettingsGroup.AxisY -> if (type.isLathe) Res.string.settings_machine_group_auxiliary else Res.string.settings_machine_group_y
        MachineSettingsGroup.AxisZ -> Res.string.settings_machine_group_z
        MachineSettingsGroup.Angular -> Res.string.settings_machine_group_angular
        MachineSettingsGroup.Caliper -> Res.string.settings_machine_group_caliper
        MachineSettingsGroup.Tachometer -> Res.string.settings_machine_group_tachometer
        MachineSettingsGroup.Probe -> Res.string.settings_machine_group_probe
        MachineSettingsGroup.Link -> Res.string.settings_machine_group_link
    },
)

@Composable
private fun ColumnScope.GroupContent(
    group: MachineSettingsGroup,
    state: MachineSettingsUiState,
    profile: MachineProfile,
    events: MachineSettingsEvents,
    navigation: MachineSettingsNavigation,
) {
    when (group) {
        MachineSettingsGroup.Machine -> MachineGroup(state, profile, events, navigation)
        MachineSettingsGroup.Display -> DisplayGroup(profile, events)
        MachineSettingsGroup.Functions -> FunctionsGroup(profile, events)
        MachineSettingsGroup.AxisX, MachineSettingsGroup.AxisY, MachineSettingsGroup.AxisZ ->
            LinearAxisGroup(group.axis!!, state, profile, events, navigation)
        MachineSettingsGroup.Angular -> AngularGroup(state, profile, events, navigation)
        MachineSettingsGroup.Caliper -> CaliperGroup(state, profile, events, navigation)
        MachineSettingsGroup.Tachometer -> TachometerGroup(state, profile, events, navigation)
        MachineSettingsGroup.Probe -> ProbeGroup(state, profile, events, navigation)
        MachineSettingsGroup.Link -> LinkGroup(state, profile, events)
    }
}

// ---------------------------------------------------------------------------------------------
// Machine

@Composable
private fun MachineGroup(state: MachineSettingsUiState, profile: MachineProfile, events: MachineSettingsEvents, navigation: MachineSettingsNavigation) {
    if (!state.tab) {
        SettingsNotice(stringResource(Res.string.settings_machine_no_tab), kind = NoticeKind.Info, tag = machineSettingTag("no-tab"))
    }
    SettingsSection(stringResource(Res.string.settings_machine_section_profile)) {
        TextRow(state, SettingsTextField.Name, Res.string.settings_machine_name, events::setName)
        SettingsDropdown(
            title = stringResource(Res.string.settings_machine_type),
            summary = stringResource(Res.string.settings_machine_type_summary),
            options = listOf(MachineType.VerticalMill, MachineType.Lathe, MachineType.HorizontalMill),
            selected = profile.machineType,
            label = { type -> stringResource(machineTypeLabel(type)) },
            onSelect = events::setMachineType,
            tag = machineSettingTag("machine-type"),
        )
        SettingsSegmented(
            title = stringResource(Res.string.settings_machine_metric),
            summary = stringResource(Res.string.settings_machine_metric_summary),
            options = listOf(false, true),
            selected = profile.metricDefault,
            label = { metric -> stringResource(if (metric) Res.string.add_device_units_mm else Res.string.add_device_units_inch) },
            onSelect = events::setMetricDefault,
            tag = machineSettingTag("metric"),
        )
        if (profile.machineType.isLathe) {
            val formattedPitch = profile.leadscrewPitchUm?.let {
                org.superdro.core.ui.settings.components.LengthSettingFormat.format(it, profile.metricDefault)
            }.orEmpty()
            val textState = androidx.compose.runtime.remember(profile.id, profile.metricDefault) {
                androidx.compose.runtime.mutableStateOf(formattedPitch)
            }
            val parsedCurrent = org.superdro.core.ui.settings.components.LengthSettingFormat.parse(textState.value, profile.metricDefault)
            if (parsedCurrent != profile.leadscrewPitchUm && !(textState.value.isBlank() && profile.leadscrewPitchUm == null)) {
                textState.value = formattedPitch
            }
            SettingsTextField(
                title = stringResource(Res.string.settings_machine_leadscrew_pitch),
                value = textState.value,
                onValueChange = { text ->
                    textState.value = text
                    (events as? MachineSettingsPresenter)?.setLeadscrewPitch(text)
                },
                summary = stringResource(Res.string.settings_machine_leadscrew_pitch_summary),
                suffix = stringResource(if (profile.metricDefault) Res.string.add_device_units_mm else Res.string.add_device_units_inch),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                tag = machineSettingTag("leadscrew-pitch"),
            )
        }
        SettingsLink(
            title = stringResource(Res.string.route_profile_file),
            summary = stringResource(
                if (state.profileFileAvailable) Res.string.integration_settings_profile_file_summary else Res.string.settings_machine_profile_file_no_tab,
            ),
            onClick = navigation::openProfileFile,
            enabled = state.profileFileAvailable,
            tag = machineSettingTag("profile-file"),
        )
    }
    SettingsSection(stringResource(Res.string.settings_machine_section_sounds)) {
        val perMachine = state.muteScope == MuteScope.EachMachine
        SettingsSwitch(
            title = stringResource(Res.string.settings_machine_mute),
            summary = stringResource(if (perMachine) Res.string.settings_machine_mute_summary else Res.string.settings_machine_mute_app_scope),
            checked = profile.muteSounds,
            onCheckedChange = events::setMuteSounds,
            enabled = perMachine,
            tag = machineSettingTag("mute"),
        )
    }
    SettingsSection(stringResource(Res.string.settings_machine_section_workspace)) {
        SettingsSwitch(
            title = stringResource(Res.string.settings_machine_shared_tools),
            summary = stringResource(Res.string.settings_machine_shared_tools_summary),
            checked = profile.useSharedToolLibrary,
            onCheckedChange = events::setUseSharedToolLibrary,
            tag = machineSettingTag("shared-tools"),
        )
        SettingsDropdown(
            title = stringResource(Res.string.settings_machine_side_panel),
            options = SidePanelMode.entries,
            selected = profile.sidePanel,
            label = { mode -> stringResource(sidePanelLabel(mode)) },
            onSelect = events::setSidePanel,
            tag = machineSettingTag("side-panel"),
        )
        SettingsSwitch(
            title = stringResource(Res.string.settings_machine_3d_sub_datums),
            summary = stringResource(Res.string.settings_machine_3d_sub_datums_summary),
            checked = profile.use3dSubDatums,
            onCheckedChange = events::setUse3dSubDatums,
            tag = machineSettingTag("3d-sub-datums"),
        )
    }
}

private fun sidePanelLabel(mode: SidePanelMode): StringResource = when (mode) {
    SidePanelMode.Off -> Res.string.settings_machine_side_panel_off
    SidePanelMode.SubDatums -> Res.string.settings_machine_side_panel_sub_datums
    SidePanelMode.Tools -> Res.string.settings_machine_side_panel_tools
    SidePanelMode.MachineDatums -> Res.string.settings_machine_side_panel_machine_datums
}

// ---------------------------------------------------------------------------------------------
// Display

/** The sample value of the font preview. */
private const val FONT_SAMPLE: String = "-0123.456"

@Composable
private fun DisplayGroup(profile: MachineProfile, events: MachineSettingsEvents) {
    val display = profile.display
    SettingsSection(stringResource(Res.string.settings_machine_section_readout)) {
        SettingsDropdown(
            title = stringResource(Res.string.settings_machine_format),
            options = ReadoutFormat.entries,
            selected = display.readoutFormat,
            label = { format -> formatLabel(format) },
            onSelect = events::setReadoutFormat,
            tag = machineSettingTag("format"),
        )
        Text(
            stringResource(Res.string.settings_machine_font),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 8.dp),
        )
        ReadoutFont.entries.forEach { font ->
            FontRow(font = font, selected = font == display.readoutFont, onClick = { events.setReadoutFont(font) })
        }
    }
    SettingsSection(stringResource(Res.string.settings_machine_section_behavior)) {
        SettingsSwitch(
            title = stringResource(Res.string.settings_machine_sliders),
            summary = stringResource(Res.string.settings_machine_sliders_summary),
            checked = display.showSliders,
            onCheckedChange = events::setShowSliders,
            tag = machineSettingTag("sliders"),
        )
        SettingsSwitch(
            title = stringResource(Res.string.settings_machine_short_press_zero),
            summary = stringResource(Res.string.settings_machine_short_press_zero_summary),
            checked = display.shortPressZero,
            onCheckedChange = events::setShortPressZero,
            tag = machineSettingTag("short-press-zero"),
        )
        SettingsSwitch(
            title = stringResource(Res.string.settings_machine_activate_sub_datum),
            summary = stringResource(Res.string.settings_machine_activate_sub_datum_summary),
            checked = display.activateNewSubDatum,
            onCheckedChange = events::setActivateNewSubDatum,
            tag = machineSettingTag("activate-sub-datum"),
        )
        val lathe = profile.machineType.isLathe
        SettingsSegmented(
            title = stringResource(Res.string.settings_machine_angle_convention),
            summary = stringResource(if (lathe) Res.string.settings_machine_angle_convention_lathe else Res.string.settings_machine_angle_convention_summary),
            options = PatternAngleConvention.entries,
            selected = if (lathe) PatternAngleConvention.Legacy else profile.patternAngleConvention,
            label = { convention ->
                stringResource(if (convention == PatternAngleConvention.Standard) Res.string.settings_machine_angle_standard else Res.string.settings_machine_angle_legacy)
            },
            onSelect = events::setPatternAngleConvention,
            enabled = !lathe,
            tag = machineSettingTag("angle-convention"),
        )
    }
    SettingsSection(stringResource(Res.string.settings_machine_section_camera)) {
        SettingsSwitch(
            title = stringResource(Res.string.settings_machine_camera_overlays),
            summary = stringResource(Res.string.settings_machine_camera_overlays_summary),
            checked = display.cameraKeepOverlays,
            onCheckedChange = events::setCameraKeepOverlays,
            tag = machineSettingTag("camera-overlays"),
        )
        SettingsDropdown(
            title = stringResource(Res.string.settings_machine_crosshair),
            options = CrosshairColor.entries,
            selected = CrosshairColor.fromKey(display.cameraCrosshairColor),
            label = { color -> stringResourceForKey(color.labelKey)?.let { stringResource(it) } ?: color.key },
            onSelect = { color -> events.setCameraCrosshairColor(color.key) },
            tag = machineSettingTag("crosshair"),
        )
    }
}

/** The text of a readout format: the inch and the mm pattern, for example "00.0000 in / 000.000 mm". */
@Composable
private fun formatLabel(format: ReadoutFormat): String =
    stringResource(Res.string.settings_machine_format_value, "00." + "0".repeat(format.inchDecimals), "000." + "0".repeat(format.mmDecimals))

/** The name of a readout font. The brand names are not translated. */
@Composable
fun fontName(font: ReadoutFont): String = when (font) {
    ReadoutFont.Dseg7 -> "DSEG7"
    ReadoutFont.PlatformMonospace -> stringResource(Res.string.settings_machine_font_monospace)
    ReadoutFont.JetBrainsMono -> "JetBrains Mono"
    ReadoutFont.System -> stringResource(Res.string.settings_machine_font_system)
    ReadoutFont.RobotoMono -> "Roboto Mono"
    ReadoutFont.DroidSansMono -> "Droid Sans Mono"
    ReadoutFont.Cousine -> "Cousine"
}

/** One font of the font choice: the name and a sample value in the font (the font preview). */
@Composable
private fun FontRow(font: ReadoutFont, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LocalTouchTargets.current.button)
            .testTag(machineSettingTag("font:${font.name}"))
            .clickable(role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, colors = accentRadioColors())
        Spacer(Modifier.width(12.dp))
        Text(fontName(font), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            FONT_SAMPLE,
            style = TextStyle(fontFamily = readoutFontFamily(font), fontSize = 28.sp),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Function buttons

@Composable
private fun FunctionsGroup(profile: MachineProfile, events: MachineSettingsEvents) {
    SettingsSection(stringResource(Res.string.settings_machine_section_function_strip)) {
        SettingsNote(stringResource(Res.string.settings_machine_function_strip_note))
        FunctionSwitch.entries.filter { button -> button.appliesTo(profile.machineType) }.forEach { button ->
            SettingsSwitch(
                title = stringResource(functionTitle(button)),
                checked = button.valueIn(profile.functionButtons),
                onCheckedChange = { on -> events.setFunctionButton(button, on) },
                tag = machineSettingTag("function:${button.name}"),
            )
        }
    }
}

/**
 * The title of a function strip switch. Each switch has the title of its own button, also
 * `show_edge_finder` and `show_height_setter`.
 */
fun functionTitle(button: FunctionSwitch): StringResource = when (button) {
    FunctionSwitch.EdgeFinder -> Res.string.settings_machine_function_edge_finder
    FunctionSwitch.ZReference -> Res.string.settings_machine_function_z_reference
    FunctionSwitch.RadiusCompensation -> Res.string.settings_machine_function_radius
    FunctionSwitch.HeightCompensation -> Res.string.settings_machine_function_height
    FunctionSwitch.HoleCircle -> Res.string.settings_machine_function_hole_circle
    FunctionSwitch.ArbitraryHoleCircle -> Res.string.settings_machine_function_arbitrary_circle
    FunctionSwitch.HoleGrid -> Res.string.settings_machine_function_hole_grid
    FunctionSwitch.EquidistantArray -> Res.string.settings_machine_function_equidistant
    FunctionSwitch.DimensionMeasure -> Res.string.settings_machine_function_dimension
    FunctionSwitch.AngleMeasure -> Res.string.settings_machine_function_angle
    FunctionSwitch.ArcCut -> Res.string.settings_machine_function_arc_cut
    FunctionSwitch.HeadTilt -> Res.string.settings_machine_function_head_tilt
    FunctionSwitch.CompoundAngle -> Res.string.settings_machine_function_compound
}

// ---------------------------------------------------------------------------------------------
// Axes

@Composable
private fun ConflictNotices(state: MachineSettingsUiState) {
    if (state.conflicts.isEmpty()) return
    SettingsNotice(
        text = (listOf(stringResource(Res.string.add_device_conflicts_title)) + state.conflicts.map { conflict -> conflict.resolve() }).joinToString("\n"),
        kind = NoticeKind.Warning,
        tag = machineSettingTag("conflicts"),
    )
}

@Composable
private fun LinearAxisGroup(
    axis: AxisId,
    state: MachineSettingsUiState,
    profile: MachineProfile,
    events: MachineSettingsEvents,
    navigation: MachineSettingsNavigation,
) {
    val config = profile.linear(axis)
    val auxiliary = axis == AxisId.Y && profile.machineType.isLathe
    ConflictNotices(state)
    SettingsSection(stringResource(Res.string.settings_machine_section_axis)) {
        SettingsSwitch(
            title = stringResource(if (auxiliary) Res.string.settings_machine_auxiliary_enabled else Res.string.settings_machine_axis_enabled, axis.name),
            checked = config.enabled,
            onCheckedChange = { on -> events.setAxisEnabled(axis, on) },
            tag = machineSettingTag("enabled:${axis.name}"),
        )
        TextRow(state, SettingsTextField.labelOf(axis)!!, Res.string.settings_machine_label, { text -> events.setLabel(axis, text) }, Res.string.settings_machine_label_summary)
    }
    if (auxiliary) {
        SettingsSection(stringResource(Res.string.settings_machine_section_auxiliary)) {
            SettingsDropdown(
                title = stringResource(Res.string.add_device_auxiliary_type),
                options = AuxiliaryType.entries,
                selected = profile.auxiliary.type,
                label = { type -> stringResource(auxiliaryLabel(type)) },
                onSelect = events::setAuxiliaryType,
                tag = machineSettingTag("auxiliary-type"),
            )
            SettingsSwitch(
                title = stringResource(Res.string.settings_machine_vectoring),
                summary = stringResource(Res.string.settings_machine_vectoring_summary),
                checked = profile.auxiliary.vectoring,
                onCheckedChange = events::setAuxiliaryVectoring,
                tag = machineSettingTag("vectoring"),
            )
            SettingsSegmented(
                title = stringResource(Res.string.settings_machine_angle_reference),
                options = ANGLE_REFERENCES,
                selected = profile.auxiliary.angleReferenceDegrees,
                label = { degrees -> "$degrees°" },
                onSelect = events::setAuxiliaryAngleReference,
                tag = machineSettingTag("angle-reference"),
            )
        }
    }
    SettingsSection(stringResource(Res.string.settings_machine_section_primary)) {
        ChannelChoice(config.primary.channel) { letter -> events.setPrimaryChannel(axis, letter) }
        SettingsSwitch(
            title = stringResource(Res.string.settings_machine_invert),
            checked = config.primary.sign < 0,
            onCheckedChange = { on -> events.setInvert(axis, on) },
            tag = machineSettingTag("invert:${axis.name}"),
        )
        EncoderLink(config.primary.channel, state, navigation)
    }
    SettingsSection(stringResource(Res.string.settings_machine_section_secondary)) {
        val secondary = config.secondary
        SettingsSwitch(
            title = stringResource(Res.string.settings_machine_secondary),
            summary = stringResource(Res.string.settings_machine_secondary_summary),
            checked = secondary != null,
            onCheckedChange = { on -> events.setSecondaryEnabled(axis, on) },
            tag = machineSettingTag("secondary:${axis.name}"),
        )
        if (secondary != null) {
            ChannelChoice(secondary.channel, tag = "secondary-channel:${axis.name}") { letter -> events.setSecondaryChannel(axis, letter) }
            SettingsSwitch(
                title = stringResource(Res.string.settings_machine_invert),
                checked = secondary.sign < 0,
                onCheckedChange = { on -> events.setSecondaryInvert(axis, on) },
                tag = machineSettingTag("secondary-invert:${axis.name}"),
            )
            EncoderLink(secondary.channel, state, navigation)
        }
    }
    ActionButtons(axis, profile, events)
}

private fun auxiliaryLabel(type: AuxiliaryType): StringResource = when (type) {
    AuxiliaryType.Tailstock -> Res.string.add_device_aux_tailstock
    AuxiliaryType.CompoundSlide -> Res.string.add_device_aux_compound
    AuxiliaryType.Other -> Res.string.add_device_aux_other
}

/** The input letter of an axis: `x y z w u v`. The setup does not offer `a` to `f`. */
@Composable
private fun ChannelChoice(channel: Char, tag: String = "channel", onSelect: (Char) -> Unit) {
    SettingsSegmented(
        title = stringResource(Res.string.settings_machine_channel),
        options = Channels.axisChannels,
        selected = Channels.normalize(channel),
        label = { letter -> letter.uppercaseChar().toString() },
        onSelect = onSelect,
        tag = machineSettingTag(tag),
    )
}

/**
 * The row that opens the encoder settings of the adapter input: the resolution, the hysteresis, and
 * the filter. With no tab and no adapter profile, the row is disabled and shows a note.
 */
@Composable
private fun EncoderLink(channel: Char, state: MachineSettingsUiState, navigation: MachineSettingsNavigation) {
    val letter = Channels.normalize(channel)
    val available = state.adapterInputsAvailable
    SettingsLink(
        title = stringResource(Res.string.settings_machine_encoder),
        summary = if (available) {
            stringResource(Res.string.settings_machine_encoder_summary, letter.uppercaseChar().toString())
        } else {
            stringResource(Res.string.settings_machine_no_adapter_profile)
        },
        onClick = { navigation.openEncoder(letter) },
        enabled = available,
        tag = machineSettingTag("encoder:$letter"),
    )
}

/** The three action buttons of an axis row. */
@Composable
private fun ActionButtons(axis: AxisId, profile: MachineProfile, events: MachineSettingsEvents) {
    val buttons = profile.buttonsOf(axis) ?: return
    buttons.forEachIndexed { index, button ->
        SettingsSection(stringResource(Res.string.settings_machine_button, index + 1)) {
            SettingsDropdown(
                title = stringResource(Res.string.settings_machine_button_mode),
                options = buttonModesFor(axis),
                selected = button.mode,
                label = { mode -> stringResource(buttonModeLabel(mode, axis)) },
                onSelect = { mode -> events.setButtonMode(axis, index, mode) },
                tag = machineSettingTag("button-mode:${axis.name}:$index"),
            )
            SettingsSwitch(
                title = stringResource(Res.string.settings_machine_show_expanded),
                checked = button.showExpanded,
                onCheckedChange = { on -> events.setButtonShowExpanded(axis, index, on) },
                tag = machineSettingTag("expanded:${axis.name}:$index"),
            )
            SettingsSwitch(
                title = stringResource(Res.string.settings_machine_show_collapsed),
                summary = stringResource(Res.string.settings_machine_show_collapsed_summary),
                checked = button.showCollapsed,
                onCheckedChange = { on -> events.setButtonShowCollapsed(axis, index, on) },
                tag = machineSettingTag("collapsed:${axis.name}:$index"),
            )
        }
    }
}

/** The name of an action button mode. Mode 2 is the degrees or divisions on the angular axis. */
fun buttonModeLabel(mode: AxisButtonMode, axis: AxisId): StringResource = when (mode) {
    AxisButtonMode.SetZero -> Res.string.settings_machine_mode_zero
    AxisButtonMode.ToggleIncremental -> Res.string.settings_machine_mode_abs_inc
    AxisButtonMode.ToggleUnits -> if (axis == AxisId.Angular) Res.string.settings_machine_mode_degrees else Res.string.settings_machine_mode_units
    AxisButtonMode.SetCenter -> Res.string.settings_machine_mode_center
    AxisButtonMode.DivideCircle -> Res.string.settings_machine_mode_divide
    AxisButtonMode.Hold -> Res.string.settings_machine_mode_hold
}

@Composable
private fun AngularGroup(state: MachineSettingsUiState, profile: MachineProfile, events: MachineSettingsEvents, navigation: MachineSettingsNavigation) {
    val angular = profile.angular
    ConflictNotices(state)
    SettingsSection(stringResource(Res.string.settings_machine_section_axis)) {
        SettingsSwitch(
            title = stringResource(Res.string.settings_machine_angular_enabled),
            checked = angular.enabled,
            onCheckedChange = { on -> events.setAxisEnabled(AxisId.Angular, on) },
            tag = machineSettingTag("enabled:Angular"),
        )
        TextRow(state, SettingsTextField.LabelAngular, Res.string.settings_machine_label, { text -> events.setLabel(AxisId.Angular, text) }, Res.string.settings_machine_label_summary)
    }
    SettingsSection(stringResource(Res.string.settings_machine_section_primary)) {
        ChannelChoice(angular.primary.channel) { letter -> events.setPrimaryChannel(AxisId.Angular, letter) }
        SettingsSwitch(
            title = stringResource(Res.string.settings_machine_invert),
            checked = angular.primary.sign < 0,
            onCheckedChange = { on -> events.setInvert(AxisId.Angular, on) },
            tag = machineSettingTag("invert:Angular"),
        )
        EncoderLink(angular.primary.channel, state, navigation)
    }
    ActionButtons(AxisId.Angular, profile, events)
}

/** The caliper or micrometer input: only on a lathe (the switch stays on). */
@Composable
private fun CaliperGroup(state: MachineSettingsUiState, profile: MachineProfile, events: MachineSettingsEvents, navigation: MachineSettingsNavigation) {
    val caliper = profile.caliper
    SettingsSection(stringResource(Res.string.settings_machine_section_caliper)) {
        SettingsSwitch(
            title = stringResource(Res.string.settings_machine_caliper_enabled),
            summary = stringResource(Res.string.settings_machine_caliper_summary),
            checked = caliper.enabled,
            onCheckedChange = { on -> events.setAxisEnabled(AxisId.Caliper, on) },
            tag = machineSettingTag("enabled:Caliper"),
        )
        ChannelChoice(caliper.input.channel) { letter -> events.setPrimaryChannel(AxisId.Caliper, letter) }
        SettingsSwitch(
            title = stringResource(Res.string.settings_machine_invert),
            checked = caliper.input.sign < 0,
            onCheckedChange = { on -> events.setInvert(AxisId.Caliper, on) },
            tag = machineSettingTag("invert:Caliper"),
        )
        EncoderLink(caliper.input.channel, state, navigation)
    }
}

// ---------------------------------------------------------------------------------------------
// Tachometer

@Composable
private fun TachometerGroup(state: MachineSettingsUiState, profile: MachineProfile, events: MachineSettingsEvents, navigation: MachineSettingsNavigation) {
    val tachometer = profile.tachometer
    val fields = state.tachometerFields
    SettingsSection(stringResource(Res.string.settings_machine_section_tachometer)) {
        SettingsSwitch(
            title = stringResource(Res.string.settings_machine_tachometer_enabled),
            checked = tachometer.enabled,
            onCheckedChange = { on -> events.setAxisEnabled(AxisId.Tachometer, on) },
            tag = machineSettingTag("enabled:Tachometer"),
        )
        TextRow(state, SettingsTextField.LabelTachometer, Res.string.settings_machine_label, { text -> events.setLabel(AxisId.Tachometer, text) })
        SettingsSegmented(
            title = stringResource(Res.string.settings_machine_tachometer_mode),
            options = TachometerMode.entries,
            selected = tachometer.mode,
            label = { mode -> stringResource(tachometerModeLabel(mode)) },
            onSelect = events::setTachometerMode,
            tag = machineSettingTag("tachometer-mode"),
        )
        if (fields.showPresets) {
            TextRow(
                state, SettingsTextField.Presets, Res.string.settings_machine_presets, events::setPresets, Res.string.settings_machine_presets_summary,
            )
        }
        if (fields.showPulses) {
            if (state.tachometerPulses != null) {
                TextRow(
                    state, SettingsTextField.Pulses, Res.string.settings_machine_pulses, events::setPulses, Res.string.settings_machine_pulses_summary,
                    keyboard = KeyboardType.Decimal,
                )
            } else {
                SettingsNotice(stringResource(Res.string.settings_machine_pulses_no_adapter), kind = NoticeKind.Info, tag = machineSettingTag("pulses-no-adapter"))
            }
            SettingsSwitch(
                title = stringResource(Res.string.settings_machine_invert),
                summary = stringResource(Res.string.settings_machine_tachometer_invert_summary),
                checked = tachometer.invert,
                onCheckedChange = { on -> events.setInvert(AxisId.Tachometer, on) },
                tag = machineSettingTag("invert:Tachometer"),
            )
            EncoderLink(Channels.T, state, navigation)
        }
    }
}

fun tachometerModeLabel(mode: TachometerMode): StringResource = when (mode) {
    TachometerMode.Sensor -> Res.string.settings_machine_tachometer_sensor
    TachometerMode.Preset -> Res.string.settings_machine_tachometer_preset
    TachometerMode.Direct -> Res.string.settings_machine_tachometer_direct
}

// ---------------------------------------------------------------------------------------------
// Probe

@Composable
private fun ProbeGroup(state: MachineSettingsUiState, profile: MachineProfile, events: MachineSettingsEvents, navigation: MachineSettingsNavigation) {
    SettingsSection(stringResource(Res.string.settings_machine_section_probe)) {
        SettingsSwitch(
            title = stringResource(Res.string.settings_machine_probe_enabled),
            summary = stringResource(Res.string.settings_machine_probe_summary),
            checked = profile.probe.enabled,
            onCheckedChange = events::setProbeEnabled,
            tag = machineSettingTag("probe"),
        )
        if (state.edgeFinderVisible) {
            SettingsSwitch(
                title = stringResource(Res.string.settings_machine_probe_edge_finder),
                summary = stringResource(Res.string.settings_machine_probe_edge_finder_summary),
                checked = profile.probe.edgeFinderEnabled,
                onCheckedChange = events::setEdgeFinderEnabled,
                tag = machineSettingTag("probe-edge-finder"),
            )
            SettingsSwitch(
                title = stringResource(Res.string.settings_machine_probe_tool_setter),
                summary = stringResource(Res.string.settings_machine_probe_tool_setter_summary),
                checked = profile.probe.toolSetterEnabled,
                onCheckedChange = events::setToolSetterEnabled,
                tag = machineSettingTag("probe-tool-setter"),
            )
        }
        SettingsLink(
            title = stringResource(Res.string.settings_machine_probe_profiles),
            summary = stringResource(Res.string.settings_machine_probe_profiles_summary),
            onClick = navigation::openProbeLibrary,
            tag = machineSettingTag("probe-library"),
        )
        SettingsLink(
            title = stringResource(Res.string.settings_machine_probe_input),
            summary = stringResource(
                if (state.adapterInputsAvailable) Res.string.settings_machine_probe_input_summary else Res.string.settings_machine_no_adapter_profile,
            ),
            onClick = navigation::openProbeInput,
            enabled = state.adapterInputsAvailable,
            tag = machineSettingTag("probe-input"),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Adapter link

@Composable
private fun LinkGroup(state: MachineSettingsUiState, profile: MachineProfile, events: MachineSettingsEvents) {
    val address = profile.adapter
    SettingsSection(stringResource(Res.string.settings_machine_section_link)) {
        if (address is AdapterAddress.Fake) {
            SettingsNote(stringResource(Res.string.settings_machine_link_demo))
        } else {
            SettingsSegmented(
                title = stringResource(Res.string.settings_machine_link_type),
                options = SETTINGS_LINK_TYPES,
                selected = state.linkType ?: TransportKind.BluetoothSpp,
                label = { kind -> stringResource(linkName(kind)) },
                onSelect = events::setLinkType,
                tag = machineSettingTag("link-type"),
            )
            when (state.linkType ?: TransportKind.BluetoothSpp) {
                TransportKind.BluetoothSpp -> TextRow(
                    state, SettingsTextField.BluetoothAddress, Res.string.settings_machine_bluetooth_address, events::setBluetoothAddress,
                    Res.string.settings_machine_bluetooth_address_summary,
                )
                TransportKind.UsbSerial -> {
                    val usb = address as? AdapterAddress.UsbSerial
                    if (usb == null) {
                        SettingsNotice(stringResource(Res.string.settings_machine_usb_select), kind = NoticeKind.Info, tag = machineSettingTag("usb-select"))
                    } else {
                        SettingsNote(stringResource(Res.string.settings_machine_usb_device, usbText(usb)))
                    }
                }
                TransportKind.Tcp -> {
                    TextRow(state, SettingsTextField.WifiHost, Res.string.settings_machine_wifi_host, events::setWifiHost, Res.string.settings_machine_wifi_host_summary)
                    TextRow(state, SettingsTextField.WifiPort, Res.string.settings_machine_wifi_port, events::setWifiPort, Res.string.settings_machine_wifi_port_summary, keyboard = KeyboardType.Number)
                }
                else -> Unit
            }
        }
        SettingsDropdown(
            title = stringResource(Res.string.settings_machine_baud_rate),
            summary = stringResource(Res.string.settings_machine_baud_rate_summary),
            options = AdapterAddress.UsbSerial.BAUD_RATES,
            selected = profile.usbBaudRate,
            label = { rate -> rate.toString() },
            onSelect = events::setUsbBaudRate,
            tag = machineSettingTag("baud-rate"),
        )
    }
    SettingsSection(stringResource(Res.string.settings_machine_section_communication)) {
        SettingsSwitch(
            title = stringResource(Res.string.settings_machine_two_way),
            summary = stringResource(Res.string.settings_machine_two_way_summary),
            checked = profile.twoWayCommunication,
            onCheckedChange = events::setTwoWayCommunication,
            tag = machineSettingTag("two-way"),
        )
        SettingsSwitch(
            title = stringResource(Res.string.settings_machine_configuration_commands),
            summary = stringResource(Res.string.settings_machine_configuration_commands_summary),
            checked = profile.adapterConfigurationCommands,
            onCheckedChange = events::setAdapterConfigurationCommands,
            tag = machineSettingTag("configuration-commands"),
        )
        SettingsNote(stringResource(Res.string.settings_machine_at_once))
    }
}

/** The text of a USB device: the vendor and the product ID in hex, and the serial number. */
fun usbText(usb: AdapterAddress.UsbSerial): String {
    val ids = usb.vendorId.toString(16).padStart(4, '0') + ":" + usb.productId.toString(16).padStart(4, '0')
    return listOfNotNull(ids, usb.serial).joinToString(" ")
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun TextRow(
    state: MachineSettingsUiState,
    field: SettingsTextField,
    title: StringResource,
    onChange: (String) -> Unit,
    summary: StringResource? = null,
    keyboard: KeyboardType = KeyboardType.Text,
) {
    SettingsTextField(
        title = stringResource(title),
        value = state.textOf(field),
        onValueChange = onChange,
        error = state.errors[field]?.resolve(),
        summary = summary?.let { stringResource(it) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        tag = machineSettingTag("field:${field.name}"),
    )
}
