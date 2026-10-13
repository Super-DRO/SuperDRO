package org.superdro.core.ui.functions.subdatums

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.model.SubDatumGroupKind
import org.superdro.core.resources.Res
import org.superdro.core.resources.ic_check_box
import org.superdro.core.resources.ic_check_box_outline
import org.superdro.core.resources.ic_chevron_left
import org.superdro.core.resources.ic_chevron_right
import org.superdro.core.resources.ic_content_copy
import org.superdro.core.resources.ic_delete
import org.superdro.core.resources.ic_filter_list
import org.superdro.core.resources.ic_first_page
import org.superdro.core.resources.ic_label
import org.superdro.core.resources.ic_last_page
import org.superdro.core.resources.ic_layers
import org.superdro.core.resources.ic_more_vert
import org.superdro.core.resources.ic_my_location
import org.superdro.core.resources.ic_place
import org.superdro.core.resources.ic_straighten
import org.superdro.core.resources.sd_3d
import org.superdro.core.resources.sd_3d_cd
import org.superdro.core.resources.sd_active
import org.superdro.core.resources.sd_copy
import org.superdro.core.resources.sd_delete_all
import org.superdro.core.resources.sd_delete_selected
import org.superdro.core.resources.sd_deselect
import org.superdro.core.resources.sd_empty
import org.superdro.core.resources.sd_export
import org.superdro.core.resources.sd_filter
import org.superdro.core.resources.sd_filter_empty
import org.superdro.core.resources.sd_first
import org.superdro.core.resources.sd_guide
import org.superdro.core.resources.sd_import
import org.superdro.core.resources.sd_kind_measured
import org.superdro.core.resources.sd_kind_pattern
import org.superdro.core.resources.sd_kind_thread
import org.superdro.core.resources.sd_last
import org.superdro.core.resources.sd_menu
import org.superdro.core.resources.sd_menu_align_origin
import org.superdro.core.resources.sd_menu_align_spindle
import org.superdro.core.resources.sd_menu_align_tool
import org.superdro.core.resources.sd_menu_delete
import org.superdro.core.resources.sd_menu_edit
import org.superdro.core.resources.sd_menu_pair
import org.superdro.core.resources.sd_menu_select
import org.superdro.core.resources.sd_menu_set_origin
import org.superdro.core.resources.sd_next
import org.superdro.core.resources.sd_previous
import org.superdro.core.resources.sd_select
import org.superdro.core.resources.sd_select_all
import org.superdro.core.resources.sd_selected_count
import org.superdro.core.resources.sd_tag
import org.superdro.core.resources.sd_untagged
import org.superdro.core.resources.sd_undo_transform
import org.superdro.core.resources.sd_workspace
import org.superdro.core.resources.sd_workspace_commands
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.LocalTouchTargets
import org.superdro.core.ui.theme.accentColor
import org.superdro.core.ui.theme.accentCheckboxColors

/** The test tags of the sub-datum panel. */
object SubDatumPanelTags {
    const val PANEL = "sd-panel"
    const val WORKSPACE = "sd-workspace"
    const val MENU = "sd-menu"
    const val SELECT = "sd-select"
    const val TAG = "sd-tag"
    const val COPY = "sd-copy"
    const val FILTER = "sd-filter"
    const val THREE_D = "sd-3d"
    const val DELETE = "sd-delete"
    const val FIRST = "sd-first"
    const val PREVIOUS = "sd-previous"
    const val NEXT = "sd-next"
    const val LAST = "sd-last"
    const val DESELECT = "sd-deselect"
    fun row(id: String): String = "sd-row-$id"
    fun filterRow(tag: String?): String = "sd-filter-${tag ?: "untagged"}"
    const val MENU_EDIT = "sd-menu-edit"
    const val MENU_SELECT = "sd-menu-select"
    const val MENU_DELETE = "sd-menu-delete"
    const val MENU_SET_ORIGIN = "sd-menu-set-origin"
    const val MENU_ALIGN_SPINDLE = "sd-menu-align-spindle"
    const val MENU_ALIGN_ORIGIN = "sd-menu-align-origin"
    const val MENU_PAIR = "sd-menu-pair"
}

/** The events of the sub-datum panel. */
interface SubDatumPanelEvents {
    fun onTap(id: String)
    fun onLongPress(id: String)
    fun onMenuDismiss()
    fun onEdit(id: String)
    fun onSelectPoint(id: String)
    fun onDelete(id: String)
    fun onSetOrigin(id: String)
    fun onAlignSpindle(id: String)
    fun onAlignOrigin(id: String)
    fun onPair(pointA: String, pointB: String)
    fun onWorkspaces()
    fun onSelecting(on: Boolean)
    fun onSelectAll()
    fun onDeselect()
    fun onTagSelected()
    fun onCopy()
    fun onFilterToggle()
    fun onFilterLongPress()
    fun onFilterRow(tag: String?)
    fun onToggle3d()
    fun onDeleteSelectedOrAll()
    fun onDeleteAll()
    fun onFirst()
    fun onPrevious()
    fun onNext()
    fun onLast()
    fun onImport()
    fun onExport()
    fun onWorkspaceCommands()
    fun onUndoTransform()
}

/**
 * The sub-datum panel of the side panel: the workspace name, the commands, the tag filter, the
 * list, and the navigation first, previous, next, and last (no gate, all orientations).
 */
@Composable
fun SubDatumPanel(state: SubDatumPanelUiState, events: SubDatumPanelEvents, modifier: Modifier = Modifier) {
    val surface = MaterialTheme.colorScheme.surfaceContainerLow
    Surface(modifier = modifier.fillMaxSize().testTag(SubDatumPanelTags.PANEL), color = surface) {
        Column(Modifier.fillMaxSize()) {
            Header(state, events)
            Commands(state, events)
            if (state.filterOpen) FilterRows(state, events, surface)
            if (state.selecting) SelectionBar(state, events)
            HorizontalDivider()
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (state.loaded && state.empty) {
                    Text(
                        text = stringResource(Res.string.sd_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.rows, key = { it.id }) { row ->
                        Box {
                            PointRow(row, state.selecting, surface, events)
                            val menu = state.menu
                            if (menu != null && menu.id == row.id) PointMenu(menu, state.lathe, events)
                        }
                        HorizontalDivider()
                    }
                }
            }
            HorizontalDivider()
            Navigation(events)
        }
    }
}

@Composable
private fun Header(state: SubDatumPanelUiState, events: SubDatumPanelEvents) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = events::onWorkspaces,
            colors = AccentButtons.text(),
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(SubDatumPanelTags.WORKSPACE),
        ) {
            Icon(painterResource(Res.drawable.ic_layers), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(Res.string.sd_workspace, state.workspaceName),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag(SubDatumPanelTags.MENU)) {
                Icon(painterResource(Res.drawable.ic_more_vert), contentDescription = stringResource(Res.string.sd_menu))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                MenuItem(Res.string.sd_import) { menuOpen = false; events.onImport() }
                MenuItem(Res.string.sd_export) { menuOpen = false; events.onExport() }
                if (!state.lathe) MenuItem(Res.string.sd_workspace_commands) { menuOpen = false; events.onWorkspaceCommands() }
                if (state.canUndoTransform) MenuItem(Res.string.sd_undo_transform) { menuOpen = false; events.onUndoTransform() }
                if (state.pointCount > 0 || state.rows.isNotEmpty()) MenuItem(Res.string.sd_delete_all) { menuOpen = false; events.onDeleteAll() }
            }
        }
    }
}

@Composable
private fun MenuItem(text: org.jetbrains.compose.resources.StringResource, tag: String? = null, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(text)) },
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp).let { base -> if (tag != null) base.testTag(tag) else base },
    )
}

@Composable
private fun Commands(state: SubDatumPanelUiState, events: SubDatumPanelEvents) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        CommandButton(
            icon = if (state.selecting) Res.drawable.ic_check_box else Res.drawable.ic_check_box_outline,
            description = stringResource(Res.string.sd_select),
            tag = SubDatumPanelTags.SELECT,
            selected = state.selecting,
            onClick = { events.onSelecting(!state.selecting) },
        )
        CommandButton(
            icon = Res.drawable.ic_label,
            description = stringResource(Res.string.sd_tag),
            tag = SubDatumPanelTags.TAG,
            enabled = state.selectedIds.isNotEmpty(),
            onClick = events::onTagSelected,
        )
        CommandButton(Res.drawable.ic_content_copy, stringResource(Res.string.sd_copy), SubDatumPanelTags.COPY, onClick = events::onCopy)
        FilterButton(state, events)
        val threeD = stringResource(Res.string.sd_3d_cd)
        if (!state.lathe) {
            FilterChip(
                selected = state.use3d,
                onClick = events::onToggle3d,
                label = { Text(stringResource(Res.string.sd_3d)) },
                modifier = Modifier.padding(horizontal = 4.dp).heightIn(min = 40.dp).testTag(SubDatumPanelTags.THREE_D)
                    .semantics { contentDescription = threeD },
            )
        }
        Spacer(Modifier.weight(1f))
        CommandButton(
            icon = Res.drawable.ic_delete,
            description = stringResource(if (state.selectedIds.isEmpty()) Res.string.sd_delete_all else Res.string.sd_delete_selected),
            tag = SubDatumPanelTags.DELETE,
            enabled = !state.empty,
            onClick = events::onDeleteSelectedOrAll,
        )
    }
}

@Composable
private fun CommandButton(
    icon: DrawableResource,
    description: String,
    tag: String,
    enabled: Boolean = true,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.testTag(tag)) {
        Icon(
            painterResource(icon),
            contentDescription = description,
            tint = if (selected) accentColor() else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FilterButton(state: SubDatumPanelUiState, events: SubDatumPanelEvents) {
    val description = stringResource(Res.string.sd_filter)
    Box(
        modifier = Modifier
            .size(48.dp)
            .combinedClickable(role = Role.Button, onClick = events::onFilterToggle, onLongClick = events::onFilterLongPress)
            .semantics { contentDescription = description }
            .testTag(SubDatumPanelTags.FILTER),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(Res.drawable.ic_filter_list),
            contentDescription = null,
            tint = if (state.filterOn || state.filterOpen) accentColor() else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FilterRows(state: SubDatumPanelUiState, events: SubDatumPanelEvents, surface: Color) {
    if (state.filterRows.isEmpty()) {
        Text(
            stringResource(Res.string.sd_filter_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        return
    }
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.filterRows.forEach { row ->
            FilterChip(
                selected = !row.hidden,
                onClick = { events.onFilterRow(row.tag) },
                label = { Text(row.tag ?: stringResource(Res.string.sd_untagged)) },
                leadingIcon = {
                    Icon(
                        painterResource(Res.drawable.ic_label),
                        contentDescription = null,
                        tint = if (row.tag == null) MaterialTheme.colorScheme.onSurfaceVariant else TagColors.color(row.index, surface),
                        modifier = Modifier.size(18.dp),
                    )
                },
                modifier = Modifier.heightIn(min = 40.dp).testTag(SubDatumPanelTags.filterRow(row.tag)),
            )
        }
    }
}

@Composable
private fun SelectionBar(state: SubDatumPanelUiState, events: SubDatumPanelEvents) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(Res.string.sd_selected_count, state.selectedIds.size),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = events::onSelectAll, colors = AccentButtons.text(), modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(Res.string.sd_select_all))
            }
            TextButton(
                onClick = events::onDeselect,
                colors = AccentButtons.text(),
                modifier = Modifier.heightIn(min = 48.dp).testTag(SubDatumPanelTags.DESELECT),
            ) { Text(stringResource(Res.string.sd_deselect)) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PointRow(row: SubDatumRowUi, selecting: Boolean, surface: Color, events: SubDatumPanelEvents) {
    val activeText = stringResource(Res.string.sd_active)
    val colors = MaterialTheme.colorScheme
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LocalTouchTargets.current.button)
            .combinedClickable(role = Role.Button, onClick = { events.onTap(row.id) }, onLongClick = { events.onLongPress(row.id) })
            .semantics { if (row.active) stateDescription = activeText }
            .testTag(SubDatumPanelTags.row(row.id)),
        colors = when {
            row.active -> ListItemDefaults.colors(
                containerColor = colors.tertiaryContainer,
                headlineColor = colors.onTertiaryContainer,
                supportingColor = colors.onTertiaryContainer,
                leadingIconColor = colors.onTertiaryContainer,
            )
            row.selected -> ListItemDefaults.colors(containerColor = colors.secondaryContainer)
            else -> ListItemDefaults.colors(containerColor = surface)
        },
        leadingContent = {
            when {
                selecting -> Checkbox(
                    checked = row.selected,
                    onCheckedChange = { events.onTap(row.id) },
                    colors = accentCheckboxColors(),
                )
                row.guide -> Icon(painterResource(Res.drawable.ic_straighten), contentDescription = stringResource(Res.string.sd_guide))
                row.active -> Icon(painterResource(Res.drawable.ic_my_location), contentDescription = activeText)
                else -> Icon(painterResource(Res.drawable.ic_place), contentDescription = null)
            }
        },
        headlineContent = { Text(row.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(row.coordinates, style = MaterialTheme.typography.bodySmall)
                if (row.note.isNotBlank()) Text(row.note, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (row.tags.isNotEmpty() || row.kind != null) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        row.kind?.let { kind -> KindMark(kind) }
                        row.tags.forEach { mark -> TagMark(mark, surface = if (row.active) colors.tertiaryContainer else surface) }
                    }
                }
            }
        },
    )
}

/** The mark of a tag: the colored dot, the icon, and the text. */
@Composable
private fun TagMark(mark: TagMarkUi, surface: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(Modifier.size(10.dp).background(TagColors.color(mark.index, surface), CircleShape))
        Text(mark.tag, style = MaterialTheme.typography.labelSmall)
    }
}

/** The group kind of a point: an icon and a text. */
@Composable
private fun KindMark(kind: SubDatumGroupKind) {
    val text = stringResource(
        when (kind) {
            SubDatumGroupKind.Pattern -> Res.string.sd_kind_pattern
            SubDatumGroupKind.Thread -> Res.string.sd_kind_thread
            SubDatumGroupKind.Measured -> Res.string.sd_kind_measured
        },
    )
    Text("[$text]", style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun PointMenu(menu: PointMenuUi, lathe: Boolean, events: SubDatumPanelEvents) {
    DropdownMenu(expanded = true, onDismissRequest = events::onMenuDismiss) {
        MenuItem(Res.string.sd_menu_edit, SubDatumPanelTags.MENU_EDIT) { events.onEdit(menu.id) }
        MenuItem(Res.string.sd_menu_select, SubDatumPanelTags.MENU_SELECT) { events.onSelectPoint(menu.id) }
        MenuItem(Res.string.sd_menu_delete, SubDatumPanelTags.MENU_DELETE) { events.onDelete(menu.id) }
        MenuItem(Res.string.sd_menu_set_origin, SubDatumPanelTags.MENU_SET_ORIGIN) { events.onSetOrigin(menu.id) }
        MenuItem(if (lathe) Res.string.sd_menu_align_tool else Res.string.sd_menu_align_spindle, SubDatumPanelTags.MENU_ALIGN_SPINDLE) {
            events.onAlignSpindle(menu.id)
        }
        MenuItem(Res.string.sd_menu_align_origin, SubDatumPanelTags.MENU_ALIGN_ORIGIN) { events.onAlignOrigin(menu.id) }
        val partnerId = menu.pairWithId
        if (partnerId != null) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.sd_menu_pair, menu.pairWith.orEmpty(), menu.name)) },
                onClick = { events.onPair(partnerId, menu.id) },
                modifier = Modifier.heightIn(min = 48.dp).testTag(SubDatumPanelTags.MENU_PAIR),
            )
        }
    }
}

@Composable
private fun Navigation(events: SubDatumPanelEvents) {
    // The "+" button of the frame is at the bottom end of the panel: the row leaves its place free.
    val targets = LocalTouchTargets.current
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = targets.button + targets.gap * 2), horizontalArrangement = Arrangement.SpaceEvenly) {
        CommandButton(Res.drawable.ic_first_page, stringResource(Res.string.sd_first), SubDatumPanelTags.FIRST, onClick = events::onFirst)
        CommandButton(Res.drawable.ic_chevron_left, stringResource(Res.string.sd_previous), SubDatumPanelTags.PREVIOUS, onClick = events::onPrevious)
        CommandButton(Res.drawable.ic_chevron_right, stringResource(Res.string.sd_next), SubDatumPanelTags.NEXT, onClick = events::onNext)
        CommandButton(Res.drawable.ic_last_page, stringResource(Res.string.sd_last), SubDatumPanelTags.LAST, onClick = events::onLast)
    }
}
