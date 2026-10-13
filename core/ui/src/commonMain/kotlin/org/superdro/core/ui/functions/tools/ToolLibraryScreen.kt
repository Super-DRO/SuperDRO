package org.superdro.core.ui.functions.tools

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.resources.Res
import org.superdro.core.resources.fn_cancel
import org.superdro.core.resources.fn_delete
import org.superdro.core.resources.fn_edit
import org.superdro.core.resources.ic_add
import org.superdro.core.resources.ic_check
import org.superdro.core.resources.ic_close
import org.superdro.core.resources.ic_delete
import org.superdro.core.resources.ic_edit
import org.superdro.core.resources.ic_file_download
import org.superdro.core.resources.ic_file_upload
import org.superdro.core.resources.ic_sort
import org.superdro.core.resources.tools_active
import org.superdro.core.resources.tools_add
import org.superdro.core.resources.tools_clear_selection
import org.superdro.core.resources.tools_delete_selected
import org.superdro.core.resources.tools_delete_text
import org.superdro.core.resources.tools_delete_title
import org.superdro.core.resources.tools_empty
import org.superdro.core.resources.tools_export
import org.superdro.core.resources.tools_import
import org.superdro.core.resources.tools_library_profile
import org.superdro.core.resources.tools_library_shared
import org.superdro.core.resources.tools_select_all
import org.superdro.core.resources.tools_selected_count
import org.superdro.core.resources.tools_shared_switch
import org.superdro.core.resources.tools_sort
import org.superdro.core.resources.tools_sort_name_ascending
import org.superdro.core.resources.tools_sort_name_descending
import org.superdro.core.resources.tools_sort_newest
import org.superdro.core.resources.tools_sort_oldest
import org.superdro.core.resources.tools_title_lathe
import org.superdro.core.resources.tools_title_mill
import org.superdro.core.ui.navigation.ScreenScaffold
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.LocalTouchTargets
import org.superdro.core.ui.theme.accentColor
import org.superdro.core.ui.theme.accentSwitchColors
import org.superdro.core.ui.theme.accentCheckboxColors

/** The test tag of the row of the tool [id]. */
fun toolRowTag(id: String): String = "tools-row-$id"

/** The test tags of the tool library screen. */
const val TOOL_ADD_TAG: String = "tools-add"
const val TOOL_SHARED_TAG: String = "tools-shared"
const val TOOL_DELETE_SELECTED_TAG: String = "tools-delete-selected"
const val TOOL_EXPORT_TAG: String = "tools-export"
const val TOOL_IMPORT_TAG: String = "tools-import"

/** The events of the tool library screen. */
interface ToolLibraryEvents {
    fun onBack()
    fun onAdd()
    fun onEdit(id: String)
    fun onDelete(id: String)
    fun onToggleSelected(id: String)
    fun onSelectAll()
    fun onClearSelection()
    fun onDeleteSelected()
    fun onSort(sort: ToolSort)
    fun onSharedLibrary(on: Boolean)
    fun onImport()
    fun onExport()
}

/**
 * The tool library screen: the
 * library switch, the sort, the list with the selection, add, edit, delete, and the CSV import
 * and export.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ToolLibraryScreen(state: ToolLibraryUiState, events: ToolLibraryEvents, modifier: Modifier = Modifier) {
    val targets = LocalTouchTargets.current
    val title = stringResource(if (state.lathe) Res.string.tools_title_lathe else Res.string.tools_title_mill)
    ScreenScaffold(
        title = title,
        onBack = events::onBack,
        modifier = modifier,
        actions = {
            IconButton(onClick = events::onImport, modifier = Modifier.testTag(TOOL_IMPORT_TAG)) {
                Icon(painterResource(Res.drawable.ic_file_download), contentDescription = stringResource(Res.string.tools_import))
            }
            IconButton(onClick = events::onExport, modifier = Modifier.testTag(TOOL_EXPORT_TAG)) {
                Icon(painterResource(Res.drawable.ic_file_upload), contentDescription = stringResource(Res.string.tools_export))
            }
            SortMenu(state.sort, events::onSort)
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                LibrarySwitch(state.sharedLibrary, events::onSharedLibrary)
                HorizontalDivider()
                if (state.selecting) SelectionBar(state.selectedIds.size, events)
                if (state.loaded && state.rows.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.tools_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(bottom = targets.button * 2),
                ) {
                    items(state.rows, key = { it.id }) { row ->
                        ToolRow(
                            row = row,
                            selecting = state.selecting,
                            onClick = { if (state.selecting) events.onToggleSelected(row.id) else events.onEdit(row.id) },
                            onLongClick = { events.onToggleSelected(row.id) },
                            onEdit = { events.onEdit(row.id) },
                            onDelete = { events.onDelete(row.id) },
                        )
                        HorizontalDivider()
                    }
                }
            }
            // The Android tree does not always get the text of the button, so the button has
            // its own content description.
            val addLabel = stringResource(Res.string.tools_add)
            ExtendedFloatingActionButton(
                onClick = events::onAdd,
                icon = { Icon(painterResource(Res.drawable.ic_add), contentDescription = null) },
                text = { Text(addLabel) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(targets.gap * 2)
                    .heightIn(min = targets.button)
                    .semantics { contentDescription = addLabel }
                    .testTag(TOOL_ADD_TAG),
            )
        }
    }
}

@Composable
private fun LibrarySwitch(shared: Boolean, onChange: (Boolean) -> Unit) {
    val switchText = stringResource(Res.string.tools_shared_switch)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(switchText, style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(if (shared) Res.string.tools_library_shared else Res.string.tools_library_profile),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = shared,
            onCheckedChange = onChange,
            modifier = Modifier.testTag(TOOL_SHARED_TAG).semantics { contentDescription = switchText },
            colors = accentSwitchColors(),
        )
    }
}

@Composable
private fun SelectionBar(count: Int, events: ToolLibraryEvents) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = events::onClearSelection) {
                Icon(painterResource(Res.drawable.ic_close), contentDescription = stringResource(Res.string.tools_clear_selection))
            }
            Text(stringResource(Res.string.tools_selected_count, count), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = events::onSelectAll, colors = AccentButtons.text(), modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(Res.string.tools_select_all))
            }
            TextButton(
                onClick = events::onDeleteSelected,
                colors = AccentButtons.text(),
                modifier = Modifier.heightIn(min = 48.dp).testTag(TOOL_DELETE_SELECTED_TAG),
            ) {
                Icon(painterResource(Res.drawable.ic_delete), contentDescription = null, modifier = Modifier.size(18.dp))
                Text(" " + stringResource(Res.string.tools_delete_selected))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolRow(
    row: ToolRowUiState,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val activeText = stringResource(Res.string.tools_active)
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LocalTouchTargets.current.button)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics { if (row.active) stateDescription = activeText }
            .testTag(toolRowTag(row.id)),
        colors = if (row.selected) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            ListItemDefaults.colors()
        },
        leadingContent = if (selecting) {
            { Checkbox(checked = row.selected, onCheckedChange = { onClick() }, colors = accentCheckboxColors()) }
        } else {
            null
        },
        headlineContent = { Text(row.name, style = MaterialTheme.typography.titleMedium) },
        supportingContent = {
            Column {
                Text(row.detail.resolve(), style = MaterialTheme.typography.bodyMedium)
                if (row.description.isNotBlank()) {
                    Text(row.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (row.active) ActiveMark(activeText)
            }
        },
        trailingContent = {
            Row {
                IconButton(onClick = onEdit) { Icon(painterResource(Res.drawable.ic_edit), contentDescription = stringResource(Res.string.fn_edit)) }
                IconButton(onClick = onDelete) { Icon(painterResource(Res.drawable.ic_delete), contentDescription = stringResource(Res.string.fn_delete)) }
            }
        },
    )
}

/** The mark of the active tool: an icon and a text. */
@Composable
internal fun ActiveMark(text: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.tertiary) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(painterResource(Res.drawable.ic_check), contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun SortMenu(sort: ToolSort, onSort: (ToolSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(Res.drawable.ic_sort), contentDescription = stringResource(Res.string.tools_sort))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(
                ToolSort.NameAscending to Res.string.tools_sort_name_ascending,
                ToolSort.NameDescending to Res.string.tools_sort_name_descending,
                ToolSort.Newest to Res.string.tools_sort_newest,
                ToolSort.Oldest to Res.string.tools_sort_oldest,
            ).forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(stringResource(text)) },
                    leadingIcon = if (value == sort) {
                        { Icon(painterResource(Res.drawable.ic_check), contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = {
                        open = false
                        onSort(value)
                    },
                )
            }
        }
    }
}

/** The delete question of one tool. */
@Composable
fun DeleteToolDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(Res.drawable.ic_delete), contentDescription = null) },
        title = { Text(stringResource(Res.string.tools_delete_title, name)) },
        text = { Text(stringResource(Res.string.tools_delete_text)) },
        confirmButton = {
            TextButton(onClick = onConfirm, colors = AccentButtons.text()) { Text(stringResource(Res.string.fn_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = AccentButtons.text()) {
                Text(stringResource(Res.string.fn_cancel))
            }
        },
    )
}
