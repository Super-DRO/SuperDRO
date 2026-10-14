package org.superdro.core.ui.tabs.adddevice

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.connection.PairedListState
import org.superdro.core.model.AuxiliaryType
import org.superdro.core.model.MachineType
import org.superdro.core.model.TransportKind
import org.superdro.core.resources.Res
import org.superdro.core.resources.add_device_aux_compound
import org.superdro.core.resources.add_device_aux_other
import org.superdro.core.resources.add_device_aux_tailstock
import org.superdro.core.resources.add_device_auxiliary_type
import org.superdro.core.resources.add_device_bluetooth_off
import org.superdro.core.resources.add_device_bluetooth_permission
import org.superdro.core.resources.add_device_conflicts_title
import org.superdro.core.resources.add_device_connect
import org.superdro.core.resources.add_device_grant_permission
import org.superdro.core.resources.add_device_input_auxiliary
import org.superdro.core.resources.add_device_input_carriage
import org.superdro.core.resources.add_device_input_cross_slide
import org.superdro.core.resources.add_device_input_quill
import org.superdro.core.resources.add_device_input_x
import org.superdro.core.resources.add_device_input_y
import org.superdro.core.resources.add_device_input_z
import org.superdro.core.resources.add_device_inputs
import org.superdro.core.resources.add_device_machine_type
import org.superdro.core.resources.add_device_name
import org.superdro.core.resources.add_device_no_paired
import org.superdro.core.resources.add_device_no_profiles
import org.superdro.core.resources.add_device_no_usb
import org.superdro.core.resources.add_device_pair_new
import org.superdro.core.resources.add_device_setup_note
import org.superdro.core.resources.add_device_source_copy
import org.superdro.core.resources.add_device_source_new
import org.superdro.core.resources.add_device_source_stored
import org.superdro.core.resources.add_device_step_link
import org.superdro.core.resources.add_device_step_profile
import org.superdro.core.resources.add_device_tachometer
import org.superdro.core.resources.add_device_turn_on_bluetooth
import org.superdro.core.resources.add_device_type_horizontal_mill
import org.superdro.core.resources.add_device_type_lathe
import org.superdro.core.resources.add_device_type_vertical_mill
import org.superdro.core.resources.add_device_units
import org.superdro.core.resources.add_device_units_inch
import org.superdro.core.resources.add_device_units_mm
import org.superdro.core.resources.add_device_usb_permission
import org.superdro.core.resources.add_device_wifi_host
import org.superdro.core.resources.add_device_wifi_port
import org.superdro.core.resources.add_device_wifi_use
import org.superdro.core.resources.frame_back
import org.superdro.core.resources.ic_warning
import org.superdro.core.resources.route_add_device
import org.superdro.core.ui.adaptive.AdaptiveSheet
import org.superdro.core.ui.linkName
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.theme.LocalTouchTargets
import org.superdro.core.ui.transportIcon
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.accentTextFieldColors
import org.superdro.core.ui.theme.accentRadioColors
import org.superdro.core.ui.theme.accentSwitchColors
import org.superdro.core.resources.integration_add_device_rfcomm_hint

/** The test tag of the link option [option]. */
fun linkOptionTag(option: LinkOption): String = "link:${option.name}"

/** The test tag of the "Connect" button of the sheet. */
const val ADD_DEVICE_CONNECT_TAG: String = "add-device-connect"

/** The test tag of the "Pair new adapter" button. */
const val ADD_DEVICE_PAIR_NEW_TAG: String = "add-device-pair-new"

/** The test tag of the sheet. */
const val ADD_DEVICE_SHEET_TAG: String = "add-device-sheet"

/**
 * The add-device sheet: a `ModalBottomSheet` in portrait and a dialog in
 * landscape. The composable is stateless: [AddDevicePresenter] holds the state.
 */
@Composable
fun AddDeviceSheet(state: AddDeviceUiState, presenter: AddDevicePresenter, onDismiss: () -> Unit) {
    AdaptiveSheet(onDismissRequest = onDismiss, title = stringResource(Res.string.route_add_device), modifier = Modifier.testTag(ADD_DEVICE_SHEET_TAG)) {
        when (state.step) {
            AddDeviceStep.Link -> LinkStep(state, presenter)
            AddDeviceStep.Profile -> ProfileStep(state, presenter)
        }
    }
}

/** The test tag of the hint for `rfcomm bind` of the Linux desktop. */
const val ADD_DEVICE_RFCOMM_HINT_TAG: String = "add-device-rfcomm-hint"

@Composable
private fun ColumnScope.LinkStep(state: AddDeviceUiState, presenter: AddDevicePresenter) {
    StepTitle(Res.string.add_device_step_link)
    state.sections.forEach { section ->
        SectionHeader(section.kind)
        when (section.kind) {
            TransportKind.BluetoothSpp -> {
                when (section.paired) {
                    PairedListState.PermissionMissing ->
                        Notice(UiText.of(Res.string.add_device_bluetooth_permission), Res.string.add_device_grant_permission, presenter::grantBluetooth)
                    PairedListState.RadioOff ->
                        Notice(UiText.of(Res.string.add_device_bluetooth_off), Res.string.add_device_turn_on_bluetooth, presenter::enableBluetooth)
                    PairedListState.NoPaired -> EmptyText(Res.string.add_device_no_paired)
                    PairedListState.Paired, null -> Unit
                }
                if (state.rfcommBindHint) {
                    Text(
                        stringResource(Res.string.integration_add_device_rfcomm_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp).testTag(ADD_DEVICE_RFCOMM_HINT_TAG),
                    )
                }
                section.options.forEach { option -> LinkRow(option, onClick = { presenter.selectLink(option) }) }
                if (section.paired?.readable != false) {
                    OutlinedButton(colors = AccentButtons.outlined(),
                        onClick = presenter::pairNewAdapter,
                        modifier = Modifier.heightIn(min = LocalTouchTargets.current.button).padding(vertical = 4.dp).testTag(ADD_DEVICE_PAIR_NEW_TAG),
                    ) { Text(stringResource(Res.string.add_device_pair_new)) }
                }
            }
            else -> {
                if (section.options.isEmpty()) EmptyText(Res.string.add_device_no_usb)
                section.options.forEach { option -> LinkRow(option, onClick = { presenter.selectLink(option) }) }
            }
        }
    }
    if (state.wifiShown) {
        SectionHeader(TransportKind.Tcp)
        // The host and the port side by side, with a gap; the button below them with its own space,
        // so that no control touches a different control.
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            OutlinedTextField(
                colors = accentTextFieldColors(),
                value = state.wifiHost,
                onValueChange = presenter::setWifiHost,
                label = { Text(stringResource(Res.string.add_device_wifi_host)) },
                singleLine = true,
                isError = state.wifiHostError != null,
                supportingText = state.wifiHostError?.let { error -> { Text(error.resolve()) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.weight(1f).testTag("wifi-host"),
            )
            OutlinedTextField(
                colors = accentTextFieldColors(),
                value = state.wifiPort,
                onValueChange = presenter::setWifiPort,
                label = { Text(stringResource(Res.string.add_device_wifi_port)) },
                singleLine = true,
                isError = state.wifiPortError != null,
                supportingText = state.wifiPortError?.let { error -> { Text(error.resolve()) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(WIFI_PORT_WIDTH).testTag("wifi-port"),
            )
        }
        FilledTonalButton(
            onClick = presenter::useWifi,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp).heightIn(min = LocalTouchTargets.current.button).testTag("wifi-use"),
        ) { Text(stringResource(Res.string.add_device_wifi_use)) }
    }
    state.demo?.let { demo ->
        SectionHeader(TransportKind.Fake)
        demo.options.forEach { option -> LinkRow(option, onClick = { presenter.selectLink(option) }) }
    }
}

/** The width of the port field of the Wi-Fi link: enough for "65535" and its label. */
private val WIFI_PORT_WIDTH = 160.dp

@Composable
private fun ColumnScope.ProfileStep(state: AddDeviceUiState, presenter: AddDevicePresenter) {
    StepTitle(Res.string.add_device_step_profile)
    state.selected?.let { option ->
        ListItem(
            headlineContent = { Text(option.name) },
            supportingContent = option.detail?.let { detail -> { Text(detail) } },
            leadingContent = { Icon(painterResource(transportIcon(option.address.kind)), contentDescription = stringResource(linkName(option.address.kind))) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
    Choice(
        options = ProfileSource.entries,
        selected = state.source,
        label = { source ->
            when (source) {
                ProfileSource.New -> Res.string.add_device_source_new
                ProfileSource.Copy -> Res.string.add_device_source_copy
                ProfileSource.Stored -> Res.string.add_device_source_stored
            }
        },
        onSelect = presenter::setSource,
    )
    Spacer(Modifier.size(12.dp))
    if (state.source != ProfileSource.New) {
        if (state.profiles.isEmpty()) EmptyText(Res.string.add_device_no_profiles)
        state.profiles.forEach { profile ->
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = LocalTouchTargets.current.button)
                    .clickable(role = Role.RadioButton) { presenter.selectProfile(profile.id) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = profile.id == state.sourceProfileId, onClick = null, colors = accentRadioColors())
                Spacer(Modifier.width(12.dp))
                Text(profile.name, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(machineTypeLabel(profile.machineType)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    OutlinedTextField(
        colors = accentTextFieldColors(),
        value = state.name,
        onValueChange = presenter::setName,
        label = { Text(stringResource(Res.string.add_device_name)) },
        singleLine = true,
        isError = state.nameError != null,
        supportingText = state.nameError?.let { error -> { Text(error.resolve()) } },
        modifier = Modifier.fillMaxWidth().testTag("machine-name"),
    )
    if (state.source == ProfileSource.New) MachineSetupForm(state, presenter)
    Spacer(Modifier.size(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LocalTouchTargets.current.gap, Alignment.End)) {
        TextButton(colors = AccentButtons.text(), onClick = presenter::back, modifier = Modifier.heightIn(min = LocalTouchTargets.current.button)) {
            Text(stringResource(Res.string.frame_back))
        }
        FilledTonalButton(
            onClick = presenter::connect,
            enabled = !state.busy,
            modifier = Modifier.heightIn(min = LocalTouchTargets.current.button).testTag(ADD_DEVICE_CONNECT_TAG),
        ) { Text(stringResource(Res.string.add_device_connect)) }
    }
}

/** The machine setup: the machine type, the units, and the inputs of each axis. */
@Composable
private fun MachineSetupForm(state: AddDeviceUiState, presenter: AddDevicePresenter) {
    MachineSetupForm(setup = state.setup, setupError = state.setupError, conflicts = state.conflicts, onUpdate = presenter::updateSetup)
}

/**
 * The machine setup with its answers as values: the machine type, the units, and the inputs of
 * each axis. The add-device sheet and the machine profiles screen use it.
 *
 * @param onUpdate Changes the answers with the transform that it gets.
 */
@Composable
fun MachineSetupForm(
    setup: MachineSetup,
    setupError: UiText?,
    conflicts: List<UiText>,
    onUpdate: ((MachineSetup) -> MachineSetup) -> Unit,
) {
    Label(Res.string.add_device_machine_type)
    Choice(
        options = listOf(MachineType.VerticalMill, MachineType.Lathe, MachineType.HorizontalMill),
        selected = setup.machineType,
        label = ::machineTypeLabel,
        onSelect = { type -> onUpdate { old -> old.copy(machineType = type) } },
    )
    Label(Res.string.add_device_units)
    Choice(
        options = listOf(false, true),
        selected = setup.metric,
        label = { metric -> if (metric) Res.string.add_device_units_mm else Res.string.add_device_units_inch },
        onSelect = { metric -> onUpdate { old -> old.copy(metric = metric) } },
    )
    Label(Res.string.add_device_inputs)
    if (setup.lathe) {
        InputRow(Res.string.add_device_input_cross_slide, setup.x) { input -> onUpdate { it.copy(x = input) } }
        InputRow(Res.string.add_device_input_carriage, setup.z) { input -> onUpdate { it.copy(z = input) } }
        InputRow(Res.string.add_device_input_auxiliary, setup.auxiliary) { input -> onUpdate { it.copy(auxiliary = input) } }
        if (setup.auxiliary.enabled) {
            Label(Res.string.add_device_auxiliary_type)
            Choice(
                options = AuxiliaryType.entries,
                selected = setup.auxiliaryType,
                label = { type ->
                    when (type) {
                        AuxiliaryType.Tailstock -> Res.string.add_device_aux_tailstock
                        AuxiliaryType.CompoundSlide -> Res.string.add_device_aux_compound
                        AuxiliaryType.Other -> Res.string.add_device_aux_other
                    }
                },
                onSelect = { type -> onUpdate { it.copy(auxiliaryType = type) } },
            )
        }
    } else {
        InputRow(Res.string.add_device_input_x, setup.x) { input -> onUpdate { it.copy(x = input) } }
        InputRow(Res.string.add_device_input_y, setup.y) { input -> onUpdate { it.copy(y = input) } }
        InputRow(Res.string.add_device_input_z, setup.z) { input -> onUpdate { it.copy(z = input) } }
        InputRow(Res.string.add_device_input_quill, setup.quill) { input -> onUpdate { it.copy(quill = input) } }
    }
    SwitchRow(Res.string.add_device_tachometer, setup.tachometer) { on -> onUpdate { it.copy(tachometer = on) } }
    setupError?.let { error ->
        Text(error.resolve(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
    }
    if (conflicts.isNotEmpty()) {
        Surface(color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(12.dp).testTag("input-conflicts")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(Res.drawable.ic_warning), contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.add_device_conflicts_title), style = MaterialTheme.typography.titleSmall)
                }
                conflicts.forEach { conflict -> Text(conflict.resolve(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
            }
        }
    }
    Text(
        text = stringResource(Res.string.add_device_setup_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** One input: the switch and, when on, the letter of the input (`x y z w u v`). */
@Composable
private fun InputRow(label: StringResource, input: SetupInput, onChange: (SetupInput) -> Unit) {
    SwitchRow(label, input.enabled) { on -> onChange(input.copy(enabled = on)) }
    if (input.enabled) {
        Choice(
            options = MachineSetup.LETTERS,
            selected = input.channel,
            text = { letter -> letter.uppercaseChar().toString() },
            onSelect = { letter -> onChange(input.copy(channel = letter)) },
        )
    }
}

@Composable
private fun SwitchRow(label: StringResource, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = LocalTouchTargets.current.button)
            .clickable(role = Role.Switch) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, colors = accentSwitchColors())
    }
}

/** A `SingleChoiceSegmentedButtonRow` of [options]. */
@Composable
private fun <T> Choice(options: List<T>, selected: T, label: (T) -> StringResource, onSelect: (T) -> Unit) {
    Choice(options = options, selected = selected, text = { option -> stringResource(label(option)) }, onSelect = onSelect)
}

@Composable
private fun <T> Choice(options: List<T>, selected: T, text: @Composable (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(text(option), maxLines = 1) }
        }
    }
}

@Composable
private fun LinkRow(option: LinkOption, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(option.name) },
        supportingContent = {
            val detail = listOfNotNull(option.detail, if (option.needsPermission) stringResource(Res.string.add_device_usb_permission) else null)
            if (detail.isNotEmpty()) Text(detail.joinToString(" · "))
        },
        leadingContent = { Icon(painterResource(transportIcon(option.address.kind)), contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().heightIn(min = LocalTouchTargets.current.button).testTag(linkOptionTag(option)).clickable(onClick = onClick),
    )
}

@Composable
private fun SectionHeader(kind: TransportKind) {
    HorizontalDivider(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(transportIcon(kind)), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(linkName(kind)), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    }
}

@Composable
private fun Notice(text: UiText, action: StringResource, onAction: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text.resolve(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(colors = AccentButtons.text(), onClick = onAction, modifier = Modifier.heightIn(min = LocalTouchTargets.current.button)) { Text(stringResource(action)) }
        }
    }
}

@Composable
private fun StepTitle(title: StringResource) {
    Text(stringResource(title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp).semantics { heading() })
}

@Composable
private fun Label(text: StringResource) {
    Text(stringResource(text), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun EmptyText(text: StringResource) {
    Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
}

/** Gives the name of a machine type. */
fun machineTypeLabel(type: MachineType): StringResource = when (type) {
    MachineType.VerticalMill -> Res.string.add_device_type_vertical_mill
    MachineType.Lathe -> Res.string.add_device_type_lathe
    MachineType.HorizontalMill -> Res.string.add_device_type_horizontal_mill
}
