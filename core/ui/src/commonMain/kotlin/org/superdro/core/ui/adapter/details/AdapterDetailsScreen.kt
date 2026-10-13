package org.superdro.core.ui.adapter.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.connection.AdapterRestartState
import org.superdro.core.protocol.InputChannel
import org.superdro.core.protocol.InputMode
import org.superdro.core.protocol.ReferenceState
import org.superdro.core.resources.Res
import org.superdro.core.resources.adapter_busy
import org.superdro.core.resources.adapter_channel_mode
import org.superdro.core.resources.adapter_channel_raw
import org.superdro.core.resources.adapter_channel_rate
import org.superdro.core.resources.adapter_channels
import org.superdro.core.resources.adapter_clear
import org.superdro.core.resources.adapter_create_profile
import org.superdro.core.resources.adapter_field_bluetooth_id
import org.superdro.core.resources.adapter_field_build
import org.superdro.core.resources.adapter_field_date
import org.superdro.core.resources.adapter_field_model
import org.superdro.core.resources.adapter_field_name
import org.superdro.core.resources.adapter_field_profile
import org.superdro.core.resources.adapter_field_version
import org.superdro.core.resources.adapter_gear
import org.superdro.core.resources.adapter_information
import org.superdro.core.resources.adapter_no_profile
import org.superdro.core.resources.adapter_rate_na
import org.superdro.core.resources.adapter_reference
import org.superdro.core.resources.adapter_reference_cancelled
import org.superdro.core.resources.adapter_reference_finished
import org.superdro.core.resources.adapter_reference_in_progress
import org.superdro.core.resources.adapter_reference_timed_out
import org.superdro.core.resources.adapter_restart_needed
import org.superdro.core.resources.adapter_restart_now
import org.superdro.core.resources.adapter_restarting
import org.superdro.core.resources.connection_identity_differs
import org.superdro.core.resources.connection_restart_failed
import org.superdro.core.resources.connection_restart_failed_title
import org.superdro.core.resources.frame_cancel
import org.superdro.core.resources.frame_done
import org.superdro.core.resources.frame_save
import org.superdro.core.resources.ic_adapter_restart
import org.superdro.core.resources.ic_settings
import org.superdro.core.resources.route_adapter_details
import org.superdro.core.ui.adapter.AdapterTexts
import org.superdro.core.ui.adapter.common.SectionTitle
import org.superdro.core.ui.adapter.common.StatusKind
import org.superdro.core.ui.adapter.common.StatusLine
import org.superdro.core.ui.adapter.common.ValueRow
import org.superdro.core.ui.adapter.profile.ProfileActionEvents
import org.superdro.core.ui.adapter.profile.ProfileActionButtons
import org.superdro.core.ui.adapter.profile.ProfileActionsHost
import org.superdro.core.ui.adapter.profile.profileActionsReport
import org.superdro.core.ui.adapter.profile.rememberProfileActionsGridState
import org.superdro.core.ui.adapter.profile.ProfileActionsUiState
import org.superdro.core.ui.navigation.ScreenScaffold
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.LocalTouchTargets
import org.superdro.core.ui.theme.accentTextFieldColors
import org.superdro.core.ui.theme.accentColor

/** The events of the Adapter Details screen. */
interface AdapterDetailsEvents {
    fun onSelectMode(channel: InputChannel, mode: InputMode)
    fun onCancel(channel: InputChannel)
    fun onSave(channel: InputChannel)
    fun onReference(channel: InputChannel)
    fun onClear(channel: InputChannel)
    fun onGear(target: GearTarget)
    fun onRestart()
    fun onRestartDialogDismissed()
    fun onCreateProfile()
}

/** The test tags of the Adapter Details screen. */
object AdapterDetailsTags {
    const val RESTART: String = "adapter-restart"
    const val CREATE_PROFILE: String = "adapter-create-profile"
    const val GRID: String = "adapter-details-grid"
    fun row(channel: InputChannel): String = "adapter-row-${channel.letter}"
    fun mode(channel: InputChannel): String = "adapter-mode-${channel.letter}"
    fun modeItem(channel: InputChannel, mode: InputMode): String = "adapter-mode-${channel.letter}-${mode.id}"
    fun save(channel: InputChannel): String = "adapter-save-${channel.letter}"
    fun cancel(channel: InputChannel): String = "adapter-cancel-${channel.letter}"
    fun reference(channel: InputChannel): String = "adapter-reference-${channel.letter}"
    fun clear(channel: InputChannel): String = "adapter-clear-${channel.letter}"
    fun gear(channel: InputChannel): String = "adapter-gear-${channel.letter}"
    fun raw(channel: InputChannel): String = "adapter-raw-${channel.letter}"
    fun rate(channel: InputChannel): String = "adapter-rate-${channel.letter}"
    fun message(channel: InputChannel): String = "adapter-message-${channel.letter}"
}

/**
 * The Adapter Details screen of one tab (`DialogDroAdapterDetails`): the
 * adapter information, the channels with their modes, raw values, and refresh rates, the commands
 * of each channel, and "Restart Now". The screen is stateless: [AdapterDetailsPresenter] gives the
 * state.
 */
@Composable
fun AdapterDetailsScreen(
    state: AdapterDetailsUiState,
    actionsState: ProfileActionsUiState,
    actions: ProfileActionEvents,
    events: AdapterDetailsEvents,
    onBack: () -> Unit,
) {
    ScreenScaffold(
        title = stringResource(Res.string.route_adapter_details),
        onBack = onBack,
        actions = { if (state.hasProfile) ProfileActionButtons(actions, enabled = !actionsState.busy) },
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 360.dp),
            modifier = Modifier.fillMaxSize().testTag(AdapterDetailsTags.GRID),
            state = rememberProfileActionsGridState(actionsState),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            profileActionsReport(actionsState)
            item(span = { GridItemSpan(maxLineSpan) }) { Header(state, events) }
            item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle(stringResource(Res.string.adapter_channels)) }
            items(state.rows, key = { row -> row.channel.letter }) { row -> ChannelCard(row, state.commandsAllowed, events) }
        }
    }
    ProfileActionsHost(actionsState, actions)
    state.restartDialog?.let { text ->
        AlertDialog(
            onDismissRequest = events::onRestartDialogDismissed,
            title = { Text(stringResource(Res.string.connection_restart_failed_title)) },
            text = { Text(text.resolve()) },
            confirmButton = {
                TextButton(onClick = events::onRestartDialogDismissed, colors = AccentButtons.text()) { Text(stringResource(Res.string.frame_done)) }
            },
        )
    }
}

@Composable
private fun Header(state: AdapterDetailsUiState, events: AdapterDetailsEvents) {
    Column(Modifier.fillMaxWidth()) {
        state.connection?.let { text -> StatusLine(StatusKind.Warning, text.resolve()) }
        if (state.identityDiffers) StatusLine(StatusKind.Warning, stringResource(Res.string.connection_identity_differs))
        SectionTitle(stringResource(Res.string.adapter_information))
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                ValueRow(stringResource(Res.string.adapter_field_name), state.name)
                ValueRow(stringResource(Res.string.adapter_field_model), state.model)
                ValueRow(stringResource(Res.string.adapter_field_bluetooth_id), state.bluetoothId, valueTag = "adapter-bluetooth-id")
                ValueRow(stringResource(Res.string.adapter_field_version), state.firmwareVersion)
                ValueRow(stringResource(Res.string.adapter_field_build), state.firmwareBuild)
                ValueRow(stringResource(Res.string.adapter_field_date), state.firmwareDate, valueTag = "adapter-date")
                ValueRow(stringResource(Res.string.adapter_field_profile), state.adapterAlias ?: AdapterTexts.NOT_VALID)
            }
        }
        if (!state.hasProfile) {
            StatusLine(StatusKind.Info, stringResource(Res.string.adapter_no_profile))
            OutlinedButton(
                onClick = events::onCreateProfile,
                colors = AccentButtons.outlined(),
                modifier = Modifier.heightIn(min = LocalTouchTargets.current.button).testTag(AdapterDetailsTags.CREATE_PROFILE),
            ) { Text(stringResource(Res.string.adapter_create_profile)) }
        }
        state.commandsNote?.let { text -> StatusLine(StatusKind.Info, text.resolve()) }
        if (state.restartNeeded) {
            Spacer(Modifier.size(8.dp))
            StatusLine(StatusKind.Warning, stringResource(Res.string.adapter_restart_needed))
        }
        when (state.restartState) {
            AdapterRestartState.Restarting -> StatusLine(StatusKind.Progress, stringResource(Res.string.adapter_restarting))
            AdapterRestartState.Failed -> StatusLine(StatusKind.Error, stringResource(Res.string.connection_restart_failed))
            AdapterRestartState.Idle -> Unit
        }
        if (state.showRestart) {
            Button(
                onClick = events::onRestart,
                enabled = !state.restartBusy,
                modifier = Modifier.heightIn(min = LocalTouchTargets.current.button).testTag(AdapterDetailsTags.RESTART),
                colors = AccentButtons.filled(),
            ) {
                Icon(painterResource(Res.drawable.ic_adapter_restart), contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(Res.string.adapter_restart_now))
            }
        }
    }
}

@Composable
private fun ChannelCard(row: ChannelRowUi, commandsAllowed: Boolean, events: AdapterDetailsEvents) {
    val name = stringResource(row.name)
    OutlinedCard(Modifier.fillMaxWidth().testTag(AdapterDetailsTags.row(row.channel))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (row.busy) {
                    val busy = stringResource(Res.string.adapter_busy)
                    CircularProgressIndicator(Modifier.size(24.dp).semantics { contentDescription = busy }, color = accentColor())
                }
                row.gear?.let { target ->
                    IconButton(onClick = { events.onGear(target) }, modifier = Modifier.size(48.dp).testTag(AdapterDetailsTags.gear(row.channel))) {
                        Icon(painterResource(Res.drawable.ic_settings), contentDescription = stringResource(Res.string.adapter_gear, name))
                    }
                }
            }
            if (!row.declared) ModeSelector(row, commandsAllowed, events)
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Res.string.adapter_channel_raw), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(row.rawValue, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(AdapterDetailsTags.raw(row.channel)))
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Res.string.adapter_channel_rate), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        row.refreshRate ?: stringResource(Res.string.adapter_rate_na),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.testTag(AdapterDetailsTags.rate(row.channel)),
                    )
                }
            }
            if (row.showSave) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { events.onSave(row.channel) },
                        enabled = !row.busy,
                        modifier = Modifier.heightIn(min = LocalTouchTargets.current.button).testTag(AdapterDetailsTags.save(row.channel)),
                        colors = AccentButtons.filled(),
                    ) { Text(stringResource(Res.string.frame_save)) }
                    OutlinedButton(
                        onClick = { events.onCancel(row.channel) },
                        enabled = !row.busy,
                        colors = AccentButtons.outlined(),
                        modifier = Modifier.heightIn(min = LocalTouchTargets.current.button).testTag(AdapterDetailsTags.cancel(row.channel)),
                    ) { Text(stringResource(Res.string.frame_cancel)) }
                }
            }
            if (row.showReference || row.showClear) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (row.showReference) {
                        FilledTonalButton(
                            onClick = { events.onReference(row.channel) },
                            enabled = !row.busy,
                            modifier = Modifier.heightIn(min = LocalTouchTargets.current.button).testTag(AdapterDetailsTags.reference(row.channel)),
                        ) { Text(stringResource(Res.string.adapter_reference)) }
                    }
                    if (row.showClear) {
                        FilledTonalButton(
                            onClick = { events.onClear(row.channel) },
                            enabled = !row.busy,
                            modifier = Modifier.heightIn(min = LocalTouchTargets.current.button).testTag(AdapterDetailsTags.clear(row.channel)),
                        ) { Text(stringResource(Res.string.adapter_clear)) }
                    }
                }
            }
            row.reference?.let { reference -> ReferenceLine(reference) }
            row.notice?.let { text -> StatusLine(StatusKind.Success, text.resolve()) }
            row.message?.let { text -> StatusLine(StatusKind.Error, text.resolve(), tag = AdapterDetailsTags.message(row.channel)) }
        }
    }
}

@Composable
private fun ModeSelector(row: ChannelRowUi, commandsAllowed: Boolean, events: AdapterDetailsEvents) {
    val label = stringResource(Res.string.adapter_channel_mode)
    val selected = stringResource(AdapterTexts.modeName(row.selection))
    val canSelect = commandsAllowed && row.modes.size > 1 && !row.busy
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded && canSelect, onExpandedChange = { open -> expanded = open && canSelect }) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            enabled = canSelect,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { if (canSelect) ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = accentTextFieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = canSelect)
                .testTag(AdapterDetailsTags.mode(row.channel)),
        )
        ExposedDropdownMenu(expanded = expanded && canSelect, onDismissRequest = { expanded = false }) {
            row.modes.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(stringResource(AdapterTexts.modeName(mode))) },
                    onClick = {
                        expanded = false
                        events.onSelectMode(row.channel, mode)
                    },
                    modifier = Modifier.testTag(AdapterDetailsTags.modeItem(row.channel, mode)),
                )
            }
        }
    }
}

@Composable
private fun ReferenceLine(reference: ReferenceState) {
    when (reference) {
        ReferenceState.InProgress -> StatusLine(StatusKind.Progress, stringResource(Res.string.adapter_reference_in_progress))
        ReferenceState.Finished -> StatusLine(StatusKind.Success, stringResource(Res.string.adapter_reference_finished))
        ReferenceState.Cancelled -> StatusLine(StatusKind.Info, stringResource(Res.string.adapter_reference_cancelled))
        ReferenceState.TimedOut -> StatusLine(StatusKind.Error, stringResource(Res.string.adapter_reference_timed_out))
    }
}
