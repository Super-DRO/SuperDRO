package org.superdro.core.ui.functions.sheets

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.functions.compensation.ToolPosition
import org.superdro.core.resources.Res
import org.superdro.core.resources.fns_position_back_center
import org.superdro.core.resources.fns_position_back_left
import org.superdro.core.resources.fns_position_back_right
import org.superdro.core.resources.fns_position_center
import org.superdro.core.resources.fns_position_front_center
import org.superdro.core.resources.fns_position_front_left
import org.superdro.core.resources.fns_position_front_right
import org.superdro.core.resources.fns_position_middle_left
import org.superdro.core.resources.fns_position_middle_right
import org.superdro.core.resources.fns_tool_choice
import org.superdro.core.resources.fns_tool_custom
import org.superdro.core.resources.ic_check
import org.superdro.core.resources.ic_info
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.LocalTouchTargets
import org.superdro.core.ui.theme.accentColor
import org.superdro.core.ui.theme.accentSwitchColors

/** The test tags of the parts of the sheets. */
object SheetTags {
    const val TOOL_PICKER = "sheets-tool-picker"
    const val TOOL_LIBRARY = "sheets-tool-library"
    const val CLEAR = "sheets-clear"
    const val SELECT_TOOL = "sheets-select-tool"
    const val COPY_TO_X = "sheets-copy-to-x"
    const val ZERO = "sheets-zero"
    const val MODE = "sheets-mode"
    const val UNITS = "sheets-units"
    const val PROBLEM = "sheets-problem"
    fun toolChoice(id: String?): String = "sheets-tool-${id ?: "custom"}"
    fun position(position: ToolPosition): String = "sheets-position-${position.name}"
    fun switch(name: String): String = "sheets-switch-$name"
}

/** One value of a sheet: the label at the start and the value at the end (live values). */
@Composable
internal fun ValueRow(label: String, value: String, modifier: Modifier = Modifier, large: Boolean = false) {
    Row(modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(
            value,
            style = if (large) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
        )
    }
}

/** A switch with its text. The whole row is the touch target. */
@Composable
internal fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit, tag: String, enabled: Boolean = true) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LocalTouchTargets.current.button)
            .selectable(selected = checked, enabled = enabled, role = Role.Switch) { onChange(!checked) }
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = accentSwitchColors(),
        )
    }
}

/** A notice of a sheet with an icon and a text, for example the reason that "Apply" is disabled. */
@Composable
internal fun Notice(text: UiText, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().testTag(SheetTags.PROBLEM),
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(Res.drawable.ic_info), contentDescription = null, modifier = Modifier.size(24.dp))
            Text(text.resolve(), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** A secondary button of a sheet, for example "Clear Compensation" or "Tool Library". */
@Composable
internal fun SheetButton(text: String, onClick: () -> Unit, tag: String, enabled: Boolean = true, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        colors = AccentButtons.outlined(),
        modifier = modifier.heightIn(min = LocalTouchTargets.current.button).testTag(tag),
    ) { Text(text) }
}

/**
 * The tool choice of a compensation sheet: "Custom" and the tools of the library. A tap opens the
 * list. A library tool fills the fields and disables them.
 */
@Composable
internal fun ToolPicker(tools: List<ToolChoice>, selectedId: String?, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val custom = stringResource(Res.string.fns_tool_custom)
    val selected = tools.firstOrNull { it.id == selectedId }
    val label = stringResource(Res.string.fns_tool_choice)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(
                onClick = { open = true },
                colors = AccentButtons.outlined(),
                modifier = Modifier.fillMaxWidth().heightIn(min = LocalTouchTargets.current.button).testTag(SheetTags.TOOL_PICKER)
                    .semantics { contentDescription = "$label: ${selected?.name ?: custom}" },
            ) {
                Column(Modifier.weight(1f)) {
                    Text(selected?.name ?: custom, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    selected?.let { Text(it.detail.resolve(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Text(custom) },
                    leadingIcon = check(selectedId == null),
                    onClick = {
                        open = false
                        onSelect(null)
                    },
                    modifier = Modifier.testTag(SheetTags.toolChoice(null)),
                )
                tools.forEach { tool ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(tool.name)
                                Text(tool.detail.resolve(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        leadingIcon = check(tool.id == selectedId),
                        onClick = {
                            open = false
                            onSelect(tool.id)
                        },
                        modifier = Modifier.testTag(SheetTags.toolChoice(tool.id)),
                    )
                }
            }
        }
    }
}

private fun check(selected: Boolean): (@Composable () -> Unit)? =
    if (selected) {
        { Icon(painterResource(Res.drawable.ic_check), contentDescription = null) }
    } else {
        null
    }

/**
 * The 3 × 3 direction control of the radius compensation: back at the top, front at the
 * bottom, left and right. The selected cell has the `secondaryContainer` role, a check icon, and
 * the selected state for a screen reader.
 */
@Composable
internal fun DirectionGrid(selected: ToolPosition, onSelect: (ToolPosition) -> Unit) {
    val rows = listOf(
        listOf(ToolPosition.BackLeft, ToolPosition.BackCenter, ToolPosition.BackRight),
        listOf(ToolPosition.MiddleLeft, ToolPosition.Center, ToolPosition.MiddleRight),
        listOf(ToolPosition.FrontLeft, ToolPosition.FrontCenter, ToolPosition.FrontRight),
    )
    val size = LocalTouchTargets.current.button
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { position ->
                    val chosen = position == selected
                    val description = stringResource(positionText(position))
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = if (chosen) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = if (chosen) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                        border = if (chosen) BorderStroke(2.dp, accentColor()) else null,
                        modifier = Modifier
                            .size(size)
                            .widthIn(min = size)
                            .selectable(selected = chosen, role = Role.RadioButton) { onSelect(position) }
                            .semantics { contentDescription = description }
                            .testTag(SheetTags.position(position)),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(arrow(position), style = MaterialTheme.typography.headlineSmall)
                            if (chosen) {
                                Icon(
                                    painterResource(Res.drawable.ic_check),
                                    contentDescription = null,
                                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The text of a cell of the direction control. */
internal fun positionText(position: ToolPosition): StringResource = when (position) {
    ToolPosition.BackLeft -> Res.string.fns_position_back_left
    ToolPosition.BackCenter -> Res.string.fns_position_back_center
    ToolPosition.BackRight -> Res.string.fns_position_back_right
    ToolPosition.MiddleLeft -> Res.string.fns_position_middle_left
    ToolPosition.Center -> Res.string.fns_position_center
    ToolPosition.MiddleRight -> Res.string.fns_position_middle_right
    ToolPosition.FrontLeft -> Res.string.fns_position_front_left
    ToolPosition.FrontCenter -> Res.string.fns_position_front_center
    ToolPosition.FrontRight -> Res.string.fns_position_front_right
}

private fun arrow(position: ToolPosition): String = when (position) {
    ToolPosition.BackLeft -> "↖"
    ToolPosition.BackCenter -> "↑"
    ToolPosition.BackRight -> "↗"
    ToolPosition.MiddleLeft -> "←"
    ToolPosition.Center -> "•"
    ToolPosition.MiddleRight -> "→"
    ToolPosition.FrontLeft -> "↙"
    ToolPosition.FrontCenter -> "↓"
    ToolPosition.FrontRight -> "↘"
}
