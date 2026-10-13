package org.superdro.core.ui.functions.workspace

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.resources.Res
import org.superdro.core.resources.fn_cancel
import org.superdro.core.resources.fn_delete
import org.superdro.core.resources.fn_edit
import org.superdro.core.resources.ic_add_location
import org.superdro.core.resources.ic_delete
import org.superdro.core.resources.ic_edit
import org.superdro.core.resources.ic_place
import org.superdro.core.resources.route_machine_datums
import org.superdro.core.resources.ws_datum_add_cd
import org.superdro.core.resources.ws_datum_apply
import org.superdro.core.resources.ws_datum_apply_text
import org.superdro.core.resources.ws_datum_apply_title
import org.superdro.core.resources.ws_datum_empty
import org.superdro.core.resources.ws_datum_hint
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.LocalTouchTargets

/** The test tags of the machine datums tab. */
object MachineDatumTags {
    const val ADD = "ws-datum-add"
    const val APPLY = "ws-datum-apply"
    fun row(id: String): String = "ws-datum-$id"
    fun edit(id: String): String = "ws-datum-edit-$id"
    fun delete(id: String): String = "ws-datum-delete-$id"
}

/** The events of the machine datums tab. */
interface MachineDatumPanelEvents {
    fun onTap(id: String)
    fun onEdit(id: String)
    fun onDelete(id: String)
    fun onAdd()
    fun onQuickCapture()
    fun onApplyConfirm()
    fun onApplyCancel()
    fun onProblemShown()
}

/**
 * The machine datums tab of the side panel: the datums in
 * the natural order of the names, with the stored position. A tap on a datum sets the absolute
 * origin to it after a confirmation with the shift list. A long press opens the menu: edit and
 * delete. The add button: a tap opens the edit sheet; a long press captures a datum at once.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MachineDatumPanel(state: MachineDatumPanelUiState, events: MachineDatumPanelEvents, modifier: Modifier = Modifier) {
    val targets = LocalTouchTargets.current
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Text(
                    stringResource(Res.string.route_machine_datums),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
                )
                Text(
                    stringResource(Res.string.ws_datum_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                HorizontalDivider()
                if (state.loaded && state.rows.isEmpty()) {
                    Text(
                        stringResource(Res.string.ws_datum_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    items(state.rows, key = { it.id }) { row ->
                        DatumRow(row, events)
                        HorizontalDivider()
                    }
                }
            }
            AddDatumButton(events, Modifier.align(Alignment.BottomEnd).padding(targets.gap))
        }
    }
    state.apply?.let { pending ->
        AlertDialog(
            onDismissRequest = events::onApplyCancel,
            icon = { Icon(painterResource(Res.drawable.ic_place), contentDescription = null) },
            title = { Text(stringResource(Res.string.ws_datum_apply_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(Res.string.ws_datum_apply_text, pending.name))
                    pending.shifts.forEach { line -> Text(line.resolve(), style = MaterialTheme.typography.bodyLarge) }
                }
            },
            confirmButton = {
                TextButton(onClick = events::onApplyConfirm, colors = AccentButtons.text(), modifier = Modifier.testTag(MachineDatumTags.APPLY)) {
                    Text(stringResource(Res.string.ws_datum_apply))
                }
            },
            dismissButton = { TextButton(onClick = events::onApplyCancel, colors = AccentButtons.text()) { Text(stringResource(Res.string.fn_cancel)) } },
        )
    }
    state.problem?.let { (title, text) -> MessageDialog(title, text, events::onProblemShown) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DatumRow(row: MachineDatumRowUiState, events: MachineDatumPanelEvents) {
    var menu by remember { mutableStateOf(false) }
    Box {
        ListItem(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = LocalTouchTargets.current.button)
                .combinedClickable(onClick = { events.onTap(row.id) }, onLongClick = { menu = true })
                .testTag(MachineDatumTags.row(row.id)),
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            headlineContent = { Text(row.name, style = MaterialTheme.typography.titleMedium) },
            supportingContent = {
                val parts = row.position.map { it.resolve() }
                val unit = row.unit?.resolve().orEmpty()
                Text(parts.joinToString(" · ") + if (unit.isEmpty()) "" else " $unit", style = MaterialTheme.typography.bodyMedium)
            },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.fn_edit)) },
                leadingIcon = { Icon(painterResource(Res.drawable.ic_edit), contentDescription = null) },
                onClick = {
                    menu = false
                    events.onEdit(row.id)
                },
                modifier = Modifier.testTag(MachineDatumTags.edit(row.id)),
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.fn_delete)) },
                leadingIcon = { Icon(painterResource(Res.drawable.ic_delete), contentDescription = null) },
                onClick = {
                    menu = false
                    events.onDelete(row.id)
                },
                modifier = Modifier.testTag(MachineDatumTags.delete(row.id)),
            )
        }
    }
}

/** The add button of the tab: a tap opens the edit sheet, a long press captures a datum at once. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AddDatumButton(events: MachineDatumPanelEvents, modifier: Modifier = Modifier) {
    val description = stringResource(Res.string.ws_datum_add_cd)
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .size(LocalTouchTargets.current.button)
            .clip(CircleShape)
            .combinedClickable(role = Role.Button, onClick = events::onAdd, onLongClick = events::onQuickCapture)
            .semantics { contentDescription = description }
            .testTag(MachineDatumTags.ADD),
        shape = CircleShape,
        color = colors.primaryContainer,
        contentColor = colors.onPrimaryContainer,
        shadowElevation = 3.dp,
    ) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(Res.drawable.ic_add_location), contentDescription = null, modifier = Modifier.size(32.dp))
        }
    }
}
