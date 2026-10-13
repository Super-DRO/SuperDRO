package org.superdro.core.ui.adapter.list

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.model.DeclaredInput
import org.superdro.core.resources.Res
import org.superdro.core.resources.adapter_action_import_new
import org.superdro.core.resources.adapter_alias
import org.superdro.core.resources.adapter_alias_edit
import org.superdro.core.resources.adapter_field_bluetooth_id
import org.superdro.core.resources.adapter_field_model
import org.superdro.core.resources.adapter_list_create
import org.superdro.core.resources.adapter_list_empty
import org.superdro.core.resources.adapter_offline_inputs
import org.superdro.core.resources.adapter_offline_note
import org.superdro.core.resources.adapter_prompt_inputs
import org.superdro.core.resources.adapter_prompt_name
import org.superdro.core.resources.adapter_list_separator
import org.superdro.core.resources.frame_cancel
import org.superdro.core.resources.frame_save
import org.superdro.core.resources.ic_adapter_import
import org.superdro.core.resources.ic_add
import org.superdro.core.resources.ic_edit
import org.superdro.core.resources.ic_settings
import org.superdro.core.resources.route_adapters
import org.superdro.core.resources.route_offline_adapter
import org.superdro.core.resources.adapter_gear
import org.superdro.core.ui.adapter.common.SectionTitle
import org.superdro.core.ui.adapter.common.StatusKind
import org.superdro.core.ui.adapter.common.StatusLine
import org.superdro.core.ui.adapter.common.TextValueField
import org.superdro.core.ui.adapter.common.ValueRow
import org.superdro.core.ui.adapter.identity.declaredName
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
import org.superdro.core.ui.theme.accentCheckboxColors

/** The test tags of the adapters screens. */
object AdaptersTags {
    const val CREATE: String = "adapters-create"
    const val IMPORT: String = "adapters-import"
    const val CREATE_NAME: String = "adapters-create-name"
    const val CREATE_CONFIRM: String = "adapters-create-confirm"
    const val ALIAS_EDIT: String = "offline-alias-edit"
    const val ALIAS_FIELD: String = "offline-alias-field"
    const val ALIAS_SAVE: String = "offline-alias-save"
    fun row(id: String): String = "adapters-row-$id"
    fun input(key: String): String = "offline-input-$key"
    fun declared(input: DeclaredInput): String = "offline-declared-${input.name}"
}

/** The events of the adapters screen. */
interface AdaptersEvents {
    fun onRow(row: AdapterRowUi)
    fun onCreate()
    fun onCreateName(text: String)
    fun onCreateToggle(input: DeclaredInput)
    fun onCreateConfirm()
    fun onCreateDismiss()
}

/**
 * The adapters screen: one card for each stored adapter profile with the parts of its row
 * text, "Create New Adapter Profile", and the import of a file as a new profile.
 */
@Composable
fun AdaptersScreen(
    state: AdaptersUiState,
    actionsState: ProfileActionsUiState,
    actions: ProfileActionEvents,
    events: AdaptersEvents,
    onBack: () -> Unit,
) {
    val separator = stringResource(Res.string.adapter_list_separator)
    ScreenScaffold(
        title = stringResource(Res.string.route_adapters),
        onBack = onBack,
        actions = {
            IconButton(onClick = actions::importAsNew, enabled = !actionsState.busy, modifier = Modifier.testTag(AdaptersTags.IMPORT)) {
                Icon(painterResource(Res.drawable.ic_adapter_import), contentDescription = stringResource(Res.string.adapter_action_import_new))
            }
        },
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 360.dp),
            modifier = Modifier.fillMaxSize(),
            state = rememberProfileActionsGridState(actionsState),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            profileActionsReport(actionsState)
            if (state.loaded && state.rows.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { StatusLine(StatusKind.Info, stringResource(Res.string.adapter_list_empty)) }
            }
            items(state.rows, key = { row -> row.id }) { row ->
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable { events.onRow(row) }.testTag(AdaptersTags.row(row.id)),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(row.alias, style = MaterialTheme.typography.titleMedium)
                        Text(
                            row.parts.map { part -> part.resolve() }.joinToString(separator),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                OutlinedButton(
                    onClick = events::onCreate,
                    colors = AccentButtons.outlined(),
                    modifier = Modifier.heightIn(min = LocalTouchTargets.current.button).testTag(AdaptersTags.CREATE),
                ) {
                    Icon(painterResource(Res.drawable.ic_add), contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(Res.string.adapter_list_create))
                }
            }
        }
    }
    ProfileActionsHost(actionsState, actions)
    state.create?.let { dialog -> CreateAdapterDialog(dialog, events) }
}

@Composable
private fun CreateAdapterDialog(state: CreateAdapterUi, events: AdaptersEvents) {
    AlertDialog(
        onDismissRequest = events::onCreateDismiss,
        title = { Text(stringResource(Res.string.adapter_list_create)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TextValueField(
                    label = stringResource(Res.string.adapter_prompt_name),
                    value = state.name,
                    onValueChange = events::onCreateName,
                    error = state.error,
                    enabled = !state.busy,
                    tag = AdaptersTags.CREATE_NAME,
                )
                SectionTitle(stringResource(Res.string.adapter_prompt_inputs))
                DeclaredInputs(state.declared, events::onCreateToggle) { input -> "adapters-create-${input.name}" }
            }
        },
        confirmButton = {
            TextButton(onClick = events::onCreateConfirm, enabled = !state.busy, colors = AccentButtons.text(), modifier = Modifier.testTag(AdaptersTags.CREATE_CONFIRM)) {
                Text(stringResource(Res.string.frame_save))
            }
        },
        dismissButton = { TextButton(onClick = events::onCreateDismiss, colors = AccentButtons.text()) { Text(stringResource(Res.string.frame_cancel)) } },
    )
}

/** The six check boxes of the declared inputs X, Y, Z, W, Probe, and Tach. */
@Composable
fun DeclaredInputs(declared: Set<DeclaredInput>, onToggle: (DeclaredInput) -> Unit, tag: (DeclaredInput) -> String) {
    DeclaredInput.entries.forEach { input ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(value = input in declared, role = Role.Checkbox, onValueChange = { onToggle(input) })
                .testTag(tag(input)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = input in declared, onCheckedChange = null, colors = accentCheckboxColors())
            Spacer(Modifier.size(8.dp))
            Text(stringResource(declaredName(input)), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** The events of the offline adapter details. */
interface OfflineAdapterEvents {
    fun onEditAlias()
    fun onAliasText(text: String)
    fun onSaveAlias()
    fun onCancelAlias()
    fun onToggleDeclared(input: DeclaredInput)
    fun onRow(target: OfflineRowTarget)
}

/**
 * The offline adapter details (`DialogOfflineAdapterDetails`): the name with edit, the
 * model, the Bluetooth ID, the declared inputs of a legacy adapter, and one row for each input
 * with its settings button. The screen sends no byte to an adapter.
 */
@Composable
fun OfflineAdapterScreen(
    state: OfflineAdapterUiState,
    actionsState: ProfileActionsUiState,
    actions: ProfileActionEvents,
    events: OfflineAdapterEvents,
    onBack: () -> Unit,
) {
    ScreenScaffold(
        title = stringResource(Res.string.route_offline_adapter),
        onBack = onBack,
        actions = { if (state.loaded && !state.gone) ProfileActionButtons(actions, enabled = !actionsState.busy) },
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 360.dp),
            modifier = Modifier.fillMaxSize(),
            state = rememberProfileActionsGridState(actionsState),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            profileActionsReport(actionsState)
            item(span = { GridItemSpan(maxLineSpan) }) { OfflineHeader(state, events) }
            item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle(stringResource(Res.string.adapter_offline_inputs)) }
            items(state.rows, key = { row -> row.key }) { row -> OfflineRow(row, events) }
        }
    }
    ProfileActionsHost(actionsState, actions)
}

@Composable
private fun OfflineHeader(state: OfflineAdapterUiState, events: OfflineAdapterEvents) {
    Column(Modifier.fillMaxWidth()) {
        StatusLine(StatusKind.Info, stringResource(Res.string.adapter_offline_note))
        Spacer(Modifier.size(12.dp))
        val edit = state.aliasEdit
        if (edit == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Res.string.adapter_alias), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(state.alias, style = MaterialTheme.typography.titleLarge)
                }
                IconButton(onClick = events::onEditAlias, modifier = Modifier.testTag(AdaptersTags.ALIAS_EDIT)) {
                    Icon(painterResource(Res.drawable.ic_edit), contentDescription = stringResource(Res.string.adapter_alias_edit))
                }
            }
        } else {
            TextValueField(
                label = stringResource(Res.string.adapter_alias),
                value = edit,
                onValueChange = events::onAliasText,
                error = state.aliasError,
                tag = AdaptersTags.ALIAS_FIELD,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = events::onCancelAlias, colors = AccentButtons.text()) { Text(stringResource(Res.string.frame_cancel)) }
                TextButton(onClick = events::onSaveAlias, colors = AccentButtons.text(), modifier = Modifier.testTag(AdaptersTags.ALIAS_SAVE)) {
                    Text(stringResource(Res.string.frame_save))
                }
            }
        }
        ValueRow(stringResource(Res.string.adapter_field_model), state.model)
        ValueRow(stringResource(Res.string.adapter_field_bluetooth_id), state.bluetoothId)
        if (state.legacy) {
            SectionTitle(stringResource(Res.string.adapter_prompt_inputs))
            DeclaredInputs(state.declared, events::onToggleDeclared, AdaptersTags::declared)
        }
    }
}

@Composable
private fun OfflineRow(row: OfflineInputRowUi, events: OfflineAdapterEvents) {
    val name = row.name.resolve()
    OutlinedCard(Modifier.fillMaxWidth().testTag(AdaptersTags.input(row.key))) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                row.summary.forEach { line -> Text(line.resolve(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            IconButton(onClick = { events.onRow(row.target) }, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(Res.drawable.ic_settings), contentDescription = stringResource(Res.string.adapter_gear, name))
            }
        }
    }
}
