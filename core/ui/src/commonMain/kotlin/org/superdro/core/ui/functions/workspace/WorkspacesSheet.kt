package org.superdro.core.ui.functions.workspace

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.resources.Res
import org.superdro.core.resources.fn_cancel
import org.superdro.core.resources.fn_close
import org.superdro.core.resources.fn_delete
import org.superdro.core.resources.fn_edit
import org.superdro.core.resources.fn_save
import org.superdro.core.resources.ic_check
import org.superdro.core.resources.ic_close
import org.superdro.core.resources.ic_delete
import org.superdro.core.resources.ic_edit
import org.superdro.core.resources.ic_undo
import org.superdro.core.resources.ws_active
import org.superdro.core.resources.ws_create
import org.superdro.core.resources.ws_delete_selected
import org.superdro.core.resources.ws_delete_selected_text
import org.superdro.core.resources.ws_delete_selected_title
import org.superdro.core.resources.ws_delete_text
import org.superdro.core.resources.ws_delete_title
import org.superdro.core.resources.ws_new_name
import org.superdro.core.resources.ws_rename
import org.superdro.core.resources.ws_selected_count
import org.superdro.core.resources.ws_title
import org.superdro.core.resources.ws_undo_last
import org.superdro.core.ui.adaptive.AdaptiveSheet
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.LocalTouchTargets
import org.superdro.core.ui.theme.accentColor
import org.superdro.core.ui.theme.accentTextFieldColors
import org.superdro.core.ui.theme.accentCheckboxColors

/** The test tags of the workspaces sheet. */
object WorkspacesTags {
    const val NEW_NAME = "ws-new-name"
    const val CREATE = "ws-create"
    const val RENAME_TEXT = "ws-rename-text"
    const val RENAME_SAVE = "ws-rename-save"
    const val DELETE_SELECTED = "ws-delete-selected"
    const val UNDO = "ws-undo-last"
    fun row(id: String): String = "ws-row-$id"
    fun rename(id: String): String = "ws-rename-$id"
    fun delete(id: String): String = "ws-delete-$id"
}

/** The events of the workspaces sheet. */
interface WorkspacesEvents {
    fun onSelect(id: String)
    fun onToggleSelected(id: String)
    fun onClearSelection()
    fun onDeleteSelectedRequest()
    fun onDeleteSelectedConfirm()
    fun onDeleteSelectedCancel()
    fun onDelete(id: String)
    fun onRenameStart(id: String)
    fun onRenameText(text: String)
    fun onRenameConfirm()
    fun onRenameCancel()
    fun onNewName(text: String)
    fun onCreate()
    fun onUndoLastTransform()
    fun onDismiss()
}

/**
 * The workspaces sheet: the list with the
 * active workspace, select, rename, delete one or the selected workspaces, create, and "Undo last
 * transform".
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WorkspacesSheet(state: WorkspacesUiState, fields: WorkspaceFieldsUiState, events: WorkspacesEvents) {
    val targets = LocalTouchTargets.current
    AdaptiveSheet(onDismissRequest = events::onDismiss, title = stringResource(Res.string.ws_title)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.selecting) SelectionBar(state.selectedIds.size, events)
            state.rows.forEach { row ->
                if (fields.renameId == row.id) {
                    RenameRow(fields, events)
                } else {
                    WorkspaceRow(row, state.selecting, events)
                }
                HorizontalDivider()
            }
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = fields.newName,
                    onValueChange = events::onNewName,
                    label = { Text(stringResource(Res.string.ws_new_name)) },
                    isError = fields.newNameError != null,
                    supportingText = fields.newNameError?.let { error -> { Text(error.resolve()) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    colors = accentTextFieldColors(),
                    modifier = Modifier.weight(1f).heightIn(min = targets.button).testTag(WorkspacesTags.NEW_NAME),
                )
                FilledTonalButton(
                    onClick = events::onCreate,
                    modifier = Modifier.padding(top = 4.dp).heightIn(min = targets.button).testTag(WorkspacesTags.CREATE),
                ) { Text(stringResource(Res.string.ws_create)) }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(targets.gap, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val undoLabel = state.undoLabel
                if (undoLabel != null) {
                    OutlinedButton(
                        onClick = events::onUndoLastTransform,
                        colors = AccentButtons.outlined(),
                        modifier = Modifier.heightIn(min = targets.button).testTag(WorkspacesTags.UNDO),
                    ) {
                        Icon(painterResource(Res.drawable.ic_undo), contentDescription = null)
                        Text("  " + stringResource(Res.string.ws_undo_last, undoLabel))
                    }
                }
                TextButton(onClick = events::onDismiss, colors = AccentButtons.text(), modifier = Modifier.heightIn(min = targets.button)) {
                    Text(stringResource(Res.string.fn_close))
                }
            }
        }
    }
    if (fields.confirmDeleteSelected) {
        AlertDialog(
            onDismissRequest = events::onDeleteSelectedCancel,
            icon = { Icon(painterResource(Res.drawable.ic_delete), contentDescription = null) },
            title = { Text(stringResource(Res.string.ws_delete_selected_title, state.selectedIds.size)) },
            text = { Text(stringResource(Res.string.ws_delete_selected_text)) },
            confirmButton = {
                TextButton(onClick = events::onDeleteSelectedConfirm, colors = AccentButtons.text()) { Text(stringResource(Res.string.fn_delete)) }
            },
            dismissButton = {
                TextButton(onClick = events::onDeleteSelectedCancel, colors = AccentButtons.text()) { Text(stringResource(Res.string.fn_cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WorkspaceRow(row: WorkspaceRowUiState, selecting: Boolean, events: WorkspacesEvents) {
    val activeText = stringResource(Res.string.ws_active)
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LocalTouchTargets.current.button)
            .combinedClickable(
                onClick = { if (selecting) events.onToggleSelected(row.id) else events.onSelect(row.id) },
                onLongClick = { events.onToggleSelected(row.id) },
            )
            .semantics { if (row.active) stateDescription = activeText }
            .testTag(WorkspacesTags.row(row.id)),
        colors = when {
            row.selected -> ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            row.active -> ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                headlineColor = MaterialTheme.colorScheme.onTertiaryContainer,
                supportingColor = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            else -> ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        },
        leadingContent = if (selecting) {
            {
                Checkbox(
                    checked = row.selected,
                    onCheckedChange = { events.onToggleSelected(row.id) },
                    colors = accentCheckboxColors(),
                )
            }
        } else {
            null
        },
        headlineContent = { Text(row.name, style = MaterialTheme.typography.titleMedium) },
        supportingContent = if (row.active) {
            {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(painterResource(Res.drawable.ic_check), contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(activeText, style = MaterialTheme.typography.labelMedium)
                }
            }
        } else {
            null
        },
        trailingContent = {
            Row {
                IconButton(onClick = { events.onRenameStart(row.id) }, modifier = Modifier.testTag(WorkspacesTags.rename(row.id))) {
                    Icon(painterResource(Res.drawable.ic_edit), contentDescription = stringResource(Res.string.ws_rename, row.name))
                }
                IconButton(onClick = { events.onDelete(row.id) }, modifier = Modifier.testTag(WorkspacesTags.delete(row.id))) {
                    Icon(painterResource(Res.drawable.ic_delete), contentDescription = stringResource(Res.string.ws_delete_title, row.name))
                }
            }
        },
    )
}

@Composable
private fun RenameRow(fields: WorkspaceFieldsUiState, events: WorkspacesEvents) {
    val targets = LocalTouchTargets.current
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = fields.renameText,
            onValueChange = events::onRenameText,
            label = { Text(stringResource(Res.string.fn_edit)) },
            isError = fields.renameError != null,
            supportingText = fields.renameError?.let { error -> { Text(error.resolve()) } },
            singleLine = true,
            colors = accentTextFieldColors(),
            modifier = Modifier.weight(1f).heightIn(min = targets.button).testTag(WorkspacesTags.RENAME_TEXT),
        )
        TextButton(onClick = events::onRenameCancel, colors = AccentButtons.text(), modifier = Modifier.padding(top = 4.dp).heightIn(min = targets.button)) {
            Text(stringResource(Res.string.fn_cancel))
        }
        FilledTonalButton(
            onClick = events::onRenameConfirm,
            modifier = Modifier.padding(top = 4.dp).heightIn(min = targets.button).testTag(WorkspacesTags.RENAME_SAVE),
        ) { Text(stringResource(Res.string.fn_save)) }
    }
}

@Composable
private fun SelectionBar(count: Int, events: WorkspacesEvents) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = events::onClearSelection) { Icon(painterResource(Res.drawable.ic_close), contentDescription = stringResource(Res.string.fn_cancel)) }
            Text(stringResource(Res.string.ws_selected_count, count), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(
                onClick = events::onDeleteSelectedRequest,
                colors = AccentButtons.text(),
                modifier = Modifier.heightIn(min = 48.dp).testTag(WorkspacesTags.DELETE_SELECTED),
            ) {
                Icon(painterResource(Res.drawable.ic_delete), contentDescription = null, modifier = Modifier.size(18.dp))
                Text(" " + stringResource(Res.string.ws_delete_selected))
            }
        }
    }
}

/** The delete question of one workspace. */
@Composable
fun DeleteWorkspaceDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(Res.drawable.ic_delete), contentDescription = null) },
        title = { Text(stringResource(Res.string.ws_delete_title, name)) },
        text = { Text(stringResource(Res.string.ws_delete_text)) },
        confirmButton = { TextButton(onClick = onConfirm, colors = AccentButtons.text()) { Text(stringResource(Res.string.fn_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss, colors = AccentButtons.text()) { Text(stringResource(Res.string.fn_cancel)) } },
    )
}

/** A dialog with a title, a text, and one confirm button (for example a refusal). */
@Composable
internal fun MessageDialog(title: UiText, text: UiText, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title.resolve()) },
        text = { Text(text.resolve()) },
        confirmButton = { TextButton(onClick = onDismiss, colors = AccentButtons.text()) { Text(stringResource(Res.string.fn_close)) } },
    )
}
