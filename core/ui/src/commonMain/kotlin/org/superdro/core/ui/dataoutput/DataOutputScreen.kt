package org.superdro.core.ui.dataoutput

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.dataoutput.DataOutputStatus
import org.superdro.core.dataoutput.MqttTopics
import org.superdro.core.model.AngleUnit
import org.superdro.core.model.BindInterface
import org.superdro.core.model.DataOutputActivation
import org.superdro.core.model.DataOutputCadence
import org.superdro.core.model.DataOutputDelimiter
import org.superdro.core.model.DataOutputField
import org.superdro.core.model.DataOutputFieldId
import org.superdro.core.model.DataOutputFormat
import org.superdro.core.model.DataOutputSecurity
import org.superdro.core.model.DataOutputTransport
import org.superdro.core.model.DistanceUnit
import org.superdro.core.model.MqttTopicLayout
import org.superdro.core.model.TimestampFormat
import org.superdro.core.resources.Res
import org.superdro.core.resources.data_output_activation
import org.superdro.core.resources.data_output_activation_always
import org.superdro.core.resources.data_output_activation_on_demand
import org.superdro.core.resources.data_output_angle_unit
import org.superdro.core.resources.data_output_cadence
import org.superdro.core.resources.data_output_cadence_change
import org.superdro.core.resources.data_output_cadence_change_interval
import org.superdro.core.resources.data_output_cadence_interval
import org.superdro.core.resources.data_output_clients
import org.superdro.core.resources.data_output_delimiter
import org.superdro.core.resources.data_output_delimiter_tab
import org.superdro.core.resources.data_output_distance_unit
import org.superdro.core.resources.data_output_enable
import org.superdro.core.resources.data_output_field_label
import org.superdro.core.resources.data_output_fields
import org.superdro.core.resources.data_output_format
import org.superdro.core.resources.data_output_format_delimited
import org.superdro.core.resources.data_output_format_json
import org.superdro.core.resources.data_output_interface
import org.superdro.core.resources.data_output_interval
import org.superdro.core.resources.data_output_labels
import org.superdro.core.resources.data_output_max_rate
import org.superdro.core.resources.data_output_mqtt_client_id
import org.superdro.core.resources.data_output_mqtt_host
import org.superdro.core.resources.data_output_mqtt_layout
import org.superdro.core.resources.data_output_mqtt_layout_fields
import org.superdro.core.resources.data_output_mqtt_layout_json
import org.superdro.core.resources.data_output_mqtt_machine
import org.superdro.core.resources.data_output_mqtt_password
import org.superdro.core.resources.data_output_mqtt_port
import org.superdro.core.resources.data_output_mqtt_tls
import org.superdro.core.resources.data_output_mqtt_username
import org.superdro.core.resources.data_output_port
import org.superdro.core.resources.data_output_precision
import org.superdro.core.resources.data_output_psk_caution
import org.superdro.core.resources.data_output_psk_copy
import org.superdro.core.resources.data_output_psk_key
import org.superdro.core.resources.data_output_psk_new
import org.superdro.core.resources.data_output_security
import org.superdro.core.resources.data_output_security_plain
import org.superdro.core.resources.data_output_security_psk
import org.superdro.core.resources.data_output_start
import org.superdro.core.resources.data_output_stop
import org.superdro.core.resources.data_output_test
import org.superdro.core.resources.data_output_timestamp
import org.superdro.core.resources.data_output_timestamp_epoch
import org.superdro.core.resources.data_output_timestamp_iso
import org.superdro.core.resources.data_output_title
import org.superdro.core.resources.data_output_transport
import org.superdro.core.resources.data_output_transport_mqtt
import org.superdro.core.resources.data_output_transport_tcp
import org.superdro.core.resources.data_output_unit_degrees
import org.superdro.core.resources.data_output_unit_dms
import org.superdro.core.resources.data_output_unit_inch
import org.superdro.core.resources.data_output_unit_meter
import org.superdro.core.resources.data_output_unit_micron
import org.superdro.core.resources.data_output_unit_mil
import org.superdro.core.resources.data_output_unit_millimeter
import org.superdro.core.resources.data_output_unit_radians
import org.superdro.core.resources.data_output_warning_all_interfaces
import org.superdro.core.resources.data_output_warning_mqtt_no_tls
import org.superdro.core.resources.data_output_warning_plain
import org.superdro.core.resources.data_output_warning_trusted
import org.superdro.core.resources.dataoutput_addresses
import org.superdro.core.resources.dataoutput_always_on_needs_switch
import org.superdro.core.resources.dataoutput_clients
import org.superdro.core.resources.dataoutput_details
import org.superdro.core.resources.dataoutput_details_hide
import org.superdro.core.resources.dataoutput_discard
import org.superdro.core.resources.dataoutput_field_send
import org.superdro.core.resources.dataoutput_interface_addresses
import org.superdro.core.resources.dataoutput_interface_no_address
import org.superdro.core.resources.dataoutput_last_error
import org.superdro.core.resources.dataoutput_machine
import org.superdro.core.resources.dataoutput_mqtt_client_id_hint
import org.superdro.core.resources.dataoutput_mqtt_json_only
import org.superdro.core.resources.dataoutput_mqtt_machine_hint
import org.superdro.core.resources.dataoutput_no_address
import org.superdro.core.resources.dataoutput_no_machine
import org.superdro.core.resources.dataoutput_psk_help
import org.superdro.core.resources.dataoutput_psk_none
import org.superdro.core.resources.dataoutput_records
import org.superdro.core.resources.dataoutput_save
import org.superdro.core.resources.dataoutput_section_format
import org.superdro.core.resources.dataoutput_section_mqtt
import org.superdro.core.resources.dataoutput_section_network
import org.superdro.core.resources.dataoutput_section_status
import org.superdro.core.resources.dataoutput_security_no_tls
import org.superdro.core.resources.dataoutput_security_tls
import org.superdro.core.resources.dataoutput_sent_failed
import org.superdro.core.resources.dataoutput_status_line
import org.superdro.core.resources.dataoutput_testing
import org.superdro.core.resources.dataoutput_unsaved
import org.superdro.core.resources.ic_check
import org.superdro.core.resources.ic_dataoutput_copy
import org.superdro.core.resources.ic_dataoutput_lock_open
import org.superdro.core.resources.ic_dataoutput_stop
import org.superdro.core.resources.ic_dataoutput_stream
import org.superdro.core.resources.ic_error
import org.superdro.core.resources.ic_hourglass
import org.superdro.core.resources.ic_lock
import org.superdro.core.resources.ic_pause
import org.superdro.core.resources.ic_play_arrow
import org.superdro.core.resources.ic_warning
import org.superdro.core.ui.components.OpenCloseIcon
import org.superdro.core.ui.components.openState
import org.superdro.core.ui.navigation.ScreenScaffold
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.text.stringResourceForKey
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.LocalTouchTargets
import org.superdro.core.ui.theme.accentRadioColors
import org.superdro.core.ui.theme.accentTextFieldColors
import org.superdro.core.ui.theme.accentSwitchColors

/** The test tag of the data output screen. */
const val DATA_OUTPUT_SCREEN_TAG: String = "data-output-screen"

/** The test tag of the switch of the output. */
const val DATA_OUTPUT_SWITCH_TAG: String = "data-output-switch"

/** The test tag of the save button. */
const val DATA_OUTPUT_SAVE_TAG: String = "data-output-save"

/** The test tag of the port field. */
const val DATA_OUTPUT_PORT_TAG: String = "data-output-port"

/** Gives the test tag of the label field of [field]. */
fun dataOutputLabelTag(field: DataOutputFieldId): String = "data-output-label-${field.wireId}"

/** Gives the test tag of the segmented button of [mode]. */
fun dataOutputSecurityTag(mode: DataOutputSecurity): String = "data-output-security-${mode.name}"

/** The events of the data output screen. [DataOutputPresenter] implements them. */
interface DataOutputEvents {
    fun onSelectProfile(profileId: String)
    fun onEnabledChange(enabled: Boolean)
    fun onStartStop()
    fun onShowDiagnostics()
    fun onStopFromDiagnostics()
    fun onInterface(bind: BindInterface)
    fun onPortText(text: String)
    fun onSecurity(mode: DataOutputSecurity)
    fun onConfirmWarning()
    fun onDismissDialog()
    fun onTransport(transport: DataOutputTransport)
    fun onActivation(activation: DataOutputActivation)
    fun onBrokerHost(host: String)
    fun onBrokerPortText(text: String)
    fun onTls(tls: Boolean)
    fun onUsername(username: String)
    fun onPassword(password: String)
    fun onClientId(clientId: String)
    fun onMachineName(name: String)
    fun onTopicLayout(layout: MqttTopicLayout)
    fun onFormat(format: DataOutputFormat)
    fun onDelimiter(delimiter: DataOutputDelimiter)
    fun onLabels(labels: Boolean)
    fun onTimestamp(enabled: Boolean)
    fun onTimestampFormat(format: TimestampFormat)
    fun onDistanceUnit(unit: DistanceUnit)
    fun onAngleUnit(unit: AngleUnit)
    fun onPrecision(precision: Int)
    fun onCadence(cadence: DataOutputCadence)
    fun onIntervalText(text: String)
    fun onMaxRateText(text: String)
    fun onField(field: DataOutputFieldId, enabled: Boolean)
    fun onFieldLabel(field: DataOutputFieldId, label: String)
    fun onDiscard()
    fun onSave()
    fun onKeyCopied()
    fun onNewKey()
    fun onConfirmNewKey()
    fun onTest()
    fun onMessageShown(id: Long)
}

/**
 * The data output screen of a machine. It is stateless:
 * it gets the [state] of [DataOutputPresenter] and calls the [events]. The status card at the top
 * shows the address, the port, the security mode, and the number of clients at all times
 * (the security rules).
 *
 * @param onCopyKey Copies the share text of the PSK to the clipboard.
 * @param listState The scroll state of the list. A screenshot test gives a start position.
 */
@Composable
fun DataOutputScreen(
    state: DataOutputUiState,
    events: DataOutputEvents,
    onBack: () -> Unit,
    onCopyKey: (String) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    ScreenScaffold(
        title = stringResource(Res.string.data_output_title),
        onBack = onBack,
        modifier = modifier.testTag(DATA_OUTPUT_SCREEN_TAG),
        actions = {
            if (state.dirty) {
                TextButton(colors = AccentButtons.text(), onClick = events::onDiscard) { Text(stringResource(Res.string.dataoutput_discard)) }
            }
            FilledTonalButton(
                onClick = events::onSave,
                enabled = state.canSave,
                modifier = Modifier.padding(horizontal = 8.dp).testTag(DATA_OUTPUT_SAVE_TAG),
            ) { Text(stringResource(Res.string.dataoutput_save)) }
        },
    ) {
        if (!state.loading && state.profileId == null) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(Res.string.dataoutput_no_machine), style = MaterialTheme.typography.titleMedium)
            }
            return@ScreenScaffold
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                state = listState,
                modifier = Modifier.widthIn(max = 920.dp).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "status") { StatusCard(state, events) }
                item(key = "transport") { TransportCard(state, events) }
                if (state.draft.transport == DataOutputTransport.Tcp) {
                    item(key = "network") { NetworkCard(state, events) }
                    item(key = "security") { SecurityCard(state, events, onCopyKey) }
                } else {
                    item(key = "mqtt") { MqttCard(state, events) }
                }
                item(key = "format") { FormatCard(state, events) }
                item(key = "cadence") { CadenceCard(state, events) }
                item(key = "fields") { SectionTitle(Res.string.data_output_fields) }
                items(state.draft.fields, key = { field -> field.id.wireId }) { field -> FieldRow(state, field, events) }
                item(key = "actions") { ActionsCard(state, events) }
            }
        }
    }
    DataOutputDialogs(state, events)
}

// -------------------------------------------------------------------------------------------
// The status (the security rules)

@Composable
private fun StatusCard(state: DataOutputUiState, events: DataOutputEvents) {
    val status = state.status
    Section(Res.string.dataoutput_section_status) {
        if (state.profiles.size > 1) {
            val current = state.profiles.firstOrNull { profile -> profile.id == state.profileId }
            Dropdown(
                label = stringResource(Res.string.dataoutput_machine),
                selected = current,
                options = state.profiles,
                text = { profile -> profile?.name.orEmpty() },
                onSelect = { profile -> profile?.let { value -> events.onSelectProfile(value.id) } },
            )
        } else {
            state.profiles.firstOrNull()?.let { profile -> LabelValue(stringResource(Res.string.dataoutput_machine), profile.name) }
        }
        SwitchRow(
            text = stringResource(Res.string.data_output_enable),
            checked = state.draft.enabled,
            onChange = events::onEnabledChange,
            modifier = Modifier.testTag(DATA_OUTPUT_SWITCH_TAG),
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            val (icon, tint) = statusIcon(status.status)
            Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(Res.string.dataoutput_status_line, stringResource(DataOutputTexts.status(status.status))),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
        }
        status.blockText?.let { text -> Text(text.resolve(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        LabelValue(stringResource(Res.string.dataoutput_addresses), status.addresses.joinToString(", ").ifEmpty { stringResource(Res.string.dataoutput_no_address) })
        if (status.transport == DataOutputTransport.Tcp) {
            LabelValue(stringResource(Res.string.data_output_port), status.port.toString())
            SecurityLine(status.security)
            LabelValue(stringResource(Res.string.dataoutput_clients), pluralStringResource(Res.plurals.data_output_clients, status.clients, status.clients))
        } else {
            TlsLine(status.brokerTls)
        }
        status.records?.let { records ->
            Text(
                stringResource(Res.string.dataoutput_records, records.toInt()) + "  " +
                    stringResource(Res.string.dataoutput_sent_failed, (status.sent ?: 0L).toInt(), (status.failed ?: 0L).toInt()),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        status.lastError?.let { error ->
            Text(stringResource(Res.string.dataoutput_last_error, error.resolve()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(LocalTouchTargets.current.gap), modifier = Modifier.padding(top = 8.dp)) {
            if (status.canStartStop) {
                FilledTonalButton(onClick = events::onStartStop, modifier = Modifier.heightIn(min = LocalTouchTargets.current.button)) {
                    Icon(painterResource(if (status.running) Res.drawable.ic_dataoutput_stop else Res.drawable.ic_play_arrow), contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (status.running) Res.string.data_output_stop else Res.string.data_output_start))
                }
            }
            // The dialog rule: the diagnostics show in this section, not in a dialog.
            // The button opens and closes them.
            val open = state.dialog == DataOutputDialog.Diagnostics
            if (status.running || status.lastError != null || open) {
                OutlinedButton(
                    colors = AccentButtons.outlined(),
                    onClick = if (open) events::onDismissDialog else events::onShowDiagnostics,
                    modifier = Modifier.heightIn(min = LocalTouchTargets.current.button).openState(open).testTag(DATA_OUTPUT_DETAILS_TAG),
                ) {
                    Text(stringResource(if (open) Res.string.dataoutput_details_hide else Res.string.dataoutput_details))
                    Spacer(Modifier.width(8.dp))
                    OpenCloseIcon(open)
                }
            }
        }
        val lines = state.diagnostics
        if (state.dialog == DataOutputDialog.Diagnostics && lines != null) {
            DiagnosticsSection(lines, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** The icon and its color of a status (each status has an icon and a text). */
@Composable
internal fun statusIcon(status: DataOutputStatus): Pair<DrawableResource, Color> {
    val colors = MaterialTheme.colorScheme
    return when (status) {
        DataOutputStatus.Stopped -> Res.drawable.ic_pause to colors.onSurfaceVariant
        DataOutputStatus.Starting -> Res.drawable.ic_hourglass to colors.onSurfaceVariant
        DataOutputStatus.Ready -> Res.drawable.ic_dataoutput_stream to colors.onSurfaceVariant
        DataOutputStatus.Sending -> Res.drawable.ic_check to colors.onSurface
        DataOutputStatus.Warning -> Res.drawable.ic_warning to colors.error
    }
}

@Composable
private fun SecurityLine(security: DataOutputSecurity) {
    val plain = security == DataOutputSecurity.PlainText
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Icon(
            painterResource(if (plain) Res.drawable.ic_dataoutput_lock_open else Res.drawable.ic_lock),
            contentDescription = null,
            tint = if (plain) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(Res.string.data_output_security) + ": " +
                stringResource(if (plain) Res.string.data_output_security_plain else Res.string.data_output_security_psk),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun TlsLine(tls: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Icon(
            painterResource(if (tls) Res.drawable.ic_lock else Res.drawable.ic_dataoutput_lock_open),
            contentDescription = null,
            tint = if (tls) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(stringResource(if (tls) Res.string.dataoutput_security_tls else Res.string.dataoutput_security_no_tls), style = MaterialTheme.typography.bodyLarge)
    }
}

// -------------------------------------------------------------------------------------------
// The transport and the activation

@Composable
private fun TransportCard(state: DataOutputUiState, events: DataOutputEvents) {
    Section(Res.string.data_output_transport) {
        Choice(
            options = DataOutputTransport.entries,
            selected = state.draft.transport,
            text = { transport -> stringResource(if (transport == DataOutputTransport.Tcp) Res.string.data_output_transport_tcp else Res.string.data_output_transport_mqtt) },
            onSelect = events::onTransport,
        )
        SubTitle(Res.string.data_output_activation)
        Choice(
            options = listOf(DataOutputActivation.OnDemand, DataOutputActivation.AlwaysOn),
            selected = state.draft.activation,
            text = { activation ->
                stringResource(if (activation == DataOutputActivation.OnDemand) Res.string.data_output_activation_on_demand else Res.string.data_output_activation_always)
            },
            enabled = { activation -> activation == DataOutputActivation.OnDemand || state.draft.enabled },
            onSelect = events::onActivation,
        )
        if (!state.draft.enabled) Hint(stringResource(Res.string.dataoutput_always_on_needs_switch))
    }
}

// -------------------------------------------------------------------------------------------
// The network

@Composable
private fun NetworkCard(state: DataOutputUiState, events: DataOutputEvents) {
    Section(Res.string.dataoutput_section_network) {
        SubTitle(Res.string.data_output_interface)
        state.interfaces.forEach { choice ->
            val name = stringResource(DataOutputTexts.bindInterface(choice.bind))
            val text = if (choice.addresses.isEmpty()) {
                stringResource(Res.string.dataoutput_interface_no_address, name)
            } else {
                stringResource(Res.string.dataoutput_interface_addresses, name, choice.addresses.joinToString(", "))
            }
            RadioRow(text = text, selected = state.draft.bindInterface == choice.bind, onClick = { events.onInterface(choice.bind) })
        }
        if (state.draft.bindInterface == BindInterface.All) {
            Warning(UiText.of(Res.string.data_output_warning_all_interfaces))
        }
        NumberField(
            label = stringResource(Res.string.data_output_port),
            value = state.portText,
            onChange = events::onPortText,
            problems = state.problemsOf(ProblemTarget.Port),
            modifier = Modifier.testTag(DATA_OUTPUT_PORT_TAG),
        )
    }
}

// -------------------------------------------------------------------------------------------
// The security mode and the PSK

@Composable
private fun SecurityCard(state: DataOutputUiState, events: DataOutputEvents, onCopyKey: (String) -> Unit) {
    Section(Res.string.data_output_security) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            DataOutputSecurity.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = state.draft.security == mode,
                    onClick = { events.onSecurity(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = DataOutputSecurity.entries.size),
                    modifier = Modifier.heightIn(min = 48.dp).testTag(dataOutputSecurityTag(mode)),
                    icon = {
                        Icon(
                            painterResource(if (mode == DataOutputSecurity.Psk) Res.drawable.ic_lock else Res.drawable.ic_dataoutput_lock_open),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                ) {
                    Text(stringResource(if (mode == DataOutputSecurity.Psk) Res.string.data_output_security_psk else Res.string.data_output_security_plain), maxLines = 1)
                }
            }
        }
        if (state.draft.security == DataOutputSecurity.PlainText) {
            Warning(UiText.of(Res.string.data_output_warning_plain), title = UiText.of(Res.string.data_output_warning_trusted))
        } else {
            SubTitle(Res.string.data_output_psk_key)
            val key = state.psk
            if (key == null) {
                Hint(stringResource(Res.string.dataoutput_psk_none))
            } else {
                Text(key, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp).testTag("data-output-psk"))
                Hint(stringResource(Res.string.dataoutput_psk_help))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(LocalTouchTargets.current.gap), modifier = Modifier.padding(top = 4.dp)) {
                OutlinedButton(
                    colors = AccentButtons.outlined(),
                    enabled = key != null,
                    onClick = {
                        if (key != null) {
                            onCopyKey(key)
                            events.onKeyCopied()
                        }
                    },
                    modifier = Modifier.heightIn(min = LocalTouchTargets.current.button),
                ) {
                    Icon(painterResource(Res.drawable.ic_dataoutput_copy), contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.data_output_psk_copy))
                }
                OutlinedButton(colors = AccentButtons.outlined(), onClick = events::onNewKey, modifier = Modifier.heightIn(min = LocalTouchTargets.current.button)) {
                    Text(stringResource(Res.string.data_output_psk_new))
                }
            }
            Warning(UiText.of(Res.string.data_output_psk_caution))
        }
    }
}

// -------------------------------------------------------------------------------------------
// MQTT

@Composable
private fun MqttCard(state: DataOutputUiState, events: DataOutputEvents) {
    val draft = state.draft
    Section(Res.string.dataoutput_section_mqtt) {
        TextField(stringResource(Res.string.data_output_mqtt_host), draft.mqttBrokerHost, events::onBrokerHost, state.problemsOf(ProblemTarget.BrokerHost), KeyboardType.Uri)
        NumberField(stringResource(Res.string.data_output_mqtt_port), state.brokerPortText, events::onBrokerPortText, state.problemsOf(ProblemTarget.BrokerPort))
        SwitchRow(stringResource(Res.string.data_output_mqtt_tls), draft.mqttTls, events::onTls)
        if (!draft.mqttTls) Warning(UiText.of(Res.string.data_output_warning_mqtt_no_tls), title = UiText.of(Res.string.data_output_warning_trusted))
        TextField(stringResource(Res.string.data_output_mqtt_username), state.mqttUsername, events::onUsername, emptyList())
        OutlinedTextField(
            colors = accentTextFieldColors(),
            value = state.mqttPassword,
            onValueChange = events::onPassword,
            label = { Text(stringResource(Res.string.data_output_mqtt_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        TextField(
            stringResource(Res.string.data_output_mqtt_client_id),
            draft.mqttClientId,
            events::onClientId,
            state.problemsOf(ProblemTarget.ClientId),
            hint = stringResource(Res.string.dataoutput_mqtt_client_id_hint),
        )
        TextField(
            stringResource(Res.string.data_output_mqtt_machine),
            draft.mqttMachineName.orEmpty(),
            events::onMachineName,
            state.problemsOf(ProblemTarget.MachineName),
            hint = stringResource(Res.string.dataoutput_mqtt_machine_hint),
        )
        SubTitle(Res.string.data_output_mqtt_layout)
        val machine = draft.mqttMachineName ?: state.profiles.firstOrNull { profile -> profile.id == state.profileId }?.name.orEmpty()
        val level = MqttTopics.base(machine).removePrefix(MqttTopics.ROOT + "/")
        MqttTopicLayout.entries.forEach { layout ->
            RadioRow(
                text = stringResource(if (layout == MqttTopicLayout.JsonRecord) Res.string.data_output_mqtt_layout_json else Res.string.data_output_mqtt_layout_fields, level),
                selected = draft.mqttTopicLayout == layout,
                onClick = { events.onTopicLayout(layout) },
            )
        }
    }
}

// -------------------------------------------------------------------------------------------
// The record format

@Composable
private fun FormatCard(state: DataOutputUiState, events: DataOutputEvents) {
    val draft = state.draft
    Section(Res.string.dataoutput_section_format) {
        if (draft.transport == DataOutputTransport.Tcp) {
            SubTitle(Res.string.data_output_format)
            Choice(
                options = DataOutputFormat.entries,
                selected = draft.format,
                text = { format -> stringResource(if (format == DataOutputFormat.Delimited) Res.string.data_output_format_delimited else Res.string.data_output_format_json) },
                onSelect = events::onFormat,
            )
            if (draft.format == DataOutputFormat.Delimited) {
                SubTitle(Res.string.data_output_delimiter)
                Choice(
                    options = DataOutputDelimiter.entries,
                    selected = draft.delimiter,
                    text = { delimiter -> if (delimiter == DataOutputDelimiter.Tab) stringResource(Res.string.data_output_delimiter_tab) else delimiter.text },
                    onSelect = events::onDelimiter,
                )
                SwitchRow(stringResource(Res.string.data_output_labels), draft.labels, events::onLabels)
            }
        } else {
            Hint(stringResource(Res.string.dataoutput_mqtt_json_only))
        }
        SwitchRow(stringResource(Res.string.data_output_timestamp), draft.timestampEnabled, events::onTimestamp)
        if (draft.timestampEnabled) {
            Choice(
                options = TimestampFormat.entries,
                selected = draft.timestampFormat,
                text = { format -> stringResource(if (format == TimestampFormat.Iso8601) Res.string.data_output_timestamp_iso else Res.string.data_output_timestamp_epoch) },
                onSelect = events::onTimestampFormat,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(LocalTouchTargets.current.gap), modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f)) {
                Dropdown(
                    label = stringResource(Res.string.data_output_distance_unit),
                    selected = draft.distanceUnit,
                    options = DistanceUnit.entries,
                    text = { unit -> stringResource(distanceUnitName(unit)) },
                    onSelect = events::onDistanceUnit,
                )
            }
            Box(Modifier.weight(1f)) {
                Dropdown(
                    label = stringResource(Res.string.data_output_angle_unit),
                    selected = draft.angleUnit,
                    options = AngleUnit.entries,
                    text = { unit -> stringResource(angleUnitName(unit)) },
                    onSelect = events::onAngleUnit,
                )
            }
        }
        SubTitle(Res.string.data_output_precision)
        Choice(options = (0..6).toList(), selected = draft.precision, text = { value -> value.toString() }, onSelect = events::onPrecision)
    }
}

private fun distanceUnitName(unit: DistanceUnit): StringResource = when (unit) {
    DistanceUnit.Inch -> Res.string.data_output_unit_inch
    DistanceUnit.Mil -> Res.string.data_output_unit_mil
    DistanceUnit.Millimeter -> Res.string.data_output_unit_millimeter
    DistanceUnit.Micron -> Res.string.data_output_unit_micron
    DistanceUnit.Meter -> Res.string.data_output_unit_meter
}

private fun angleUnitName(unit: AngleUnit): StringResource = when (unit) {
    AngleUnit.DecimalDegrees -> Res.string.data_output_unit_degrees
    AngleUnit.DegreesMinutesSeconds -> Res.string.data_output_unit_dms
    AngleUnit.Radians -> Res.string.data_output_unit_radians
}

// -------------------------------------------------------------------------------------------
// The cadence

@Composable
private fun CadenceCard(state: DataOutputUiState, events: DataOutputEvents) {
    val draft = state.draft
    Section(Res.string.data_output_cadence) {
        DataOutputCadence.entries.forEach { cadence ->
            RadioRow(
                text = stringResource(
                    when (cadence) {
                        DataOutputCadence.FixedInterval -> Res.string.data_output_cadence_interval
                        DataOutputCadence.OnChange -> Res.string.data_output_cadence_change
                        DataOutputCadence.OnChangeAndInterval -> Res.string.data_output_cadence_change_interval
                    },
                ),
                selected = draft.cadence == cadence,
                onClick = { events.onCadence(cadence) },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(LocalTouchTargets.current.gap), modifier = Modifier.fillMaxWidth()) {
            if (draft.cadence != DataOutputCadence.OnChange) {
                Box(Modifier.weight(1f)) {
                    NumberField(stringResource(Res.string.data_output_interval), state.intervalText, events::onIntervalText, state.problemsOf(ProblemTarget.Interval))
                }
            }
            if (draft.cadence != DataOutputCadence.FixedInterval) {
                Box(Modifier.weight(1f)) {
                    NumberField(stringResource(Res.string.data_output_max_rate), state.maxRateText, events::onMaxRateText, state.problemsOf(ProblemTarget.MaxRate))
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------
// The 25 fields and the labels

@Composable
private fun FieldRow(state: DataOutputUiState, field: DataOutputField, events: DataOutputEvents) {
    val name = stringResourceForKey(DataOutputTexts.fieldNameKey(field.id))?.let { resource -> stringResource(resource) } ?: field.id.wireId
    val problems = state.problemsOf(ProblemTarget.Label(field.id)) + listOfNotNull(state.labelRefusals[field.id])
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(0.4f))
            Box(Modifier.weight(0.45f).padding(horizontal = 8.dp)) {
                OutlinedTextField(
                    colors = accentTextFieldColors(),
                    value = field.label,
                    onValueChange = { label -> events.onFieldLabel(field.id, label) },
                    label = { Text(stringResource(Res.string.data_output_field_label)) },
                    singleLine = true,
                    isError = problems.isNotEmpty(),
                    supportingText = problems.firstOrNull()?.let { text -> { Text(text.resolve()) } },
                    modifier = Modifier.fillMaxWidth().testTag(dataOutputLabelTag(field.id)),
                )
            }
            val description = stringResource(Res.string.dataoutput_field_send, name)
            Switch(
                checked = field.enabled,
                onCheckedChange = { enabled -> events.onField(field.id, enabled) },
                modifier = Modifier.semantics { contentDescription = description }.testTag("data-output-field-${field.id.wireId}"),
                colors = accentSwitchColors(),
            )
        }
    }
}

// -------------------------------------------------------------------------------------------
// "Test connection" and the problems

@Composable
private fun ActionsCard(state: DataOutputUiState, events: DataOutputEvents) {
    Section(Res.string.data_output_test) {
        if (state.problems.isNotEmpty()) {
            state.problems.distinctBy { problem -> problem.text }.forEach { problem ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(Res.drawable.ic_error), contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(problem.text.resolve(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (state.dirty) Hint(stringResource(Res.string.dataoutput_unsaved))
        Row(horizontalArrangement = Arrangement.spacedBy(LocalTouchTargets.current.gap), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                colors = AccentButtons.outlined(),
                onClick = events::onTest,
                enabled = !state.testing,
                modifier = Modifier.heightIn(min = LocalTouchTargets.current.button).testTag("data-output-test"),
            ) { Text(stringResource(Res.string.data_output_test)) }
            FilledTonalButton(onClick = events::onSave, enabled = state.canSave, modifier = Modifier.heightIn(min = LocalTouchTargets.current.button)) {
                Text(stringResource(Res.string.dataoutput_save))
            }
        }
        if (state.testing) Hint(stringResource(Res.string.dataoutput_testing))
        state.testResult?.let { result ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("data-output-test-result")) {
                Icon(
                    painterResource(if (result.passed) Res.drawable.ic_check else Res.drawable.ic_warning),
                    contentDescription = null,
                    tint = if (result.passed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(result.text.resolve(), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// -------------------------------------------------------------------------------------------
// The parts

@Composable
private fun Section(title: StringResource, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionTitle(title)
            content()
        }
    }
}

@Composable
private fun SectionTitle(title: StringResource) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(bottom = 4.dp).semantics { heading() },
    )
}

@Composable
private fun SubTitle(title: StringResource) {
    Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun LabelValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(min = 120.dp).padding(end = 12.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

/** A warning text with an icon. */
@Composable
private fun Warning(text: UiText, title: UiText? = null) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(painterResource(Res.drawable.ic_warning), contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                title?.let { value -> Text(value.resolve(), style = MaterialTheme.typography.titleSmall) }
                Text(text.resolve(), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = LocalTouchTargets.current.button).clickable(role = Role.Switch) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, colors = accentSwitchColors())
    }
}

@Composable
private fun RadioRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, colors = accentRadioColors())
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun <T> Choice(
    options: List<T>,
    selected: T,
    text: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    enabled: (T) -> Boolean = { true },
) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                enabled = enabled(option),
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(text(option), maxLines = 1) }
        }
    }
}

@Composable
private fun <T> Dropdown(label: String, selected: T, options: List<T>, text: @Composable (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(
                colors = AccentButtons.outlined(),
                onClick = { open = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(text(selected), modifier = Modifier.weight(1f), maxLines = 1) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(text(option)) },
                        onClick = {
                            open = false
                            onSelect(option)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TextField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    problems: List<UiText>,
    keyboardType: KeyboardType = KeyboardType.Text,
    hint: String? = null,
) {
    OutlinedTextField(
        colors = accentTextFieldColors(),
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = hint?.let { text -> { Text(text) } },
        singleLine = true,
        isError = problems.isNotEmpty(),
        supportingText = problems.firstOrNull()?.let { text -> { Text(text.resolve()) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit, problems: List<UiText>, modifier: Modifier = Modifier) {
    OutlinedTextField(
        colors = accentTextFieldColors(),
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = problems.isNotEmpty(),
        supportingText = problems.firstOrNull()?.let { text -> { Text(text.resolve()) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.fillMaxWidth(),
    )
}
