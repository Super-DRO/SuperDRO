package org.superdro.core.ui.settings.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.superdro.core.resources.Res
import org.superdro.core.resources.ic_arrow_drop_down
import org.superdro.core.resources.ic_chevron_right
import org.superdro.core.resources.ic_error
import org.superdro.core.resources.ic_info
import org.superdro.core.resources.ic_warning
import org.superdro.core.ui.theme.LocalTouchTargets
import org.superdro.core.ui.theme.accentColor
import org.superdro.core.ui.theme.accentTextFieldColors
import org.superdro.core.ui.theme.accentSwitchColors

/**
 * The rows of the settings screens. Each row has the touch
 * target ([LocalTouchTargets]), a title, and an optional summary. The rows are stateless:
 * the presenter of the screen holds each value and each text of a text field.
 *
 * In light mode, `primary` is not a text color. The section titles use [accentColor] and the
 * switches use the default Material 3 colors, whose thumb and track are not text.
 */

/** The largest width of the content of a settings screen. A wider window centers the column. */
val SETTINGS_MAX_WIDTH: Dp = 840.dp

/**
 * The scroll column of a settings screen: the full height, the content centered with
 * [SETTINGS_MAX_WIDTH] as its largest width.
 */
@Composable
fun SettingsColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = SETTINGS_MAX_WIDTH)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            content = content,
        )
    }
}

/** A group of rows with a title (a heading for the screen readers). */
@Composable
fun SettingsSection(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = accentColor(),
            modifier = Modifier.padding(bottom = 4.dp).semantics { heading() },
        )
        content()
        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** The title and the summary of a row. */
@Composable
private fun RowScope.RowText(title: String, summary: String?, enabled: Boolean) {
    val color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = color)
        if (summary != null) {
            Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else color,
            )
        }
    }
}

/**
 * A switch row. A tap on the full row changes the value. The row has the role `Switch`, so a
 * screen reader reads the title and the state.
 */
@Composable
fun SettingsSwitch(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    tag: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = LocalTouchTargets.current.button)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowText(title, summary, enabled)
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled, colors = accentSwitchColors())
    }
}

/**
 * A choice of a few values in a `SingleChoiceSegmentedButtonRow`, below the title. Use it
 * for two to four short values. [SettingsDropdown] is for more values.
 */
@Composable
fun <T> SettingsSegmented(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    tag: String? = null,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp).then(if (tag != null) Modifier.testTag(tag) else Modifier)) {
        Row(verticalAlignment = Alignment.CenterVertically) { RowText(title, summary, enabled) }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(label(option), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

/**
 * A choice of one value from a list: the row shows the title and the selected value, and a tap
 * opens a `DropdownMenu` with each value. The open state of the menu is only a state of the view.
 */
@Composable
fun <T> SettingsDropdown(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    tag: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = LocalTouchTargets.current.button)
                .then(if (tag != null) Modifier.testTag(tag) else Modifier)
                .clickable(enabled = enabled, role = Role.DropdownList) { open = true },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RowText(title, summary, enabled)
            Spacer(Modifier.width(16.dp))
            Text(
                label(selected),
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
            Icon(painterResource(Res.drawable.ic_arrow_drop_down), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(label(option)) },
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                    modifier = Modifier.heightIn(min = LocalTouchTargets.current.button).then(
                        if (tag != null) Modifier.testTag("$tag:${options.indexOf(option)}") else Modifier,
                    ),
                )
            }
        }
    }
}

/**
 * A text field row: an `OutlinedTextField` with the title as its label. The presenter holds the
 * text: each change goes to [onValueChange], and the presenter writes a valid value at once.
 * [error] shows below the field.
 *
 * While the field has the focus, the field shows the text of the user at once, so that no
 * character goes away when the state of the presenter comes a little later. Without the focus,
 * the field shows [value].
 */
@Composable
fun SettingsTextField(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    summary: String? = null,
    suffix: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    enabled: Boolean = true,
    tag: String? = null,
) {
    var focused by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(value) }
    LaunchedEffect(value, focused) { if (!focused) text = value }
    OutlinedTextField(
        value = text,
        onValueChange = { changed ->
            text = changed
            onValueChange(changed)
        },
        label = { Text(title) },
        singleLine = true,
        enabled = enabled,
        isError = error != null,
        suffix = suffix?.let { unit -> { Text(unit) } },
        supportingText = (error ?: summary)?.let { line -> { Text(line) } },
        keyboardOptions = keyboardOptions,
        colors = accentTextFieldColors(),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .heightIn(min = LocalTouchTargets.current.button)
            .onFocusChanged { state -> focused = state.isFocused }
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
    )
}

/** A row that opens a different screen or a dialog: the title, the summary, and a chevron. */
@Composable
fun SettingsLink(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    icon: DrawableResource? = null,
    tag: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = LocalTouchTargets.current.button)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
        }
        RowText(title, summary, enabled)
        Icon(painterResource(Res.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The kind of a [SettingsNotice]: the color roles of a notice, and an icon. */
enum class NoticeKind { Info, Warning, Error }

/**
 * A notice in a settings screen: an icon and a text on a container. A warning uses
 * `tertiaryContainer`, an error uses `errorContainer`, and an info uses
 * `surfaceContainerHigh`. [action] is an optional button at the end, for example a link to the
 * system setting.
 */
@Composable
fun SettingsNotice(
    text: String,
    modifier: Modifier = Modifier,
    kind: NoticeKind = NoticeKind.Warning,
    tag: String? = null,
    action: (@Composable RowScope.() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (kind) {
        NoticeKind.Info -> scheme.surfaceContainerHigh to scheme.onSurface
        NoticeKind.Warning -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        NoticeKind.Error -> scheme.errorContainer to scheme.onErrorContainer
    }
    val icon = when (kind) {
        NoticeKind.Info -> Res.drawable.ic_info
        NoticeKind.Warning -> Res.drawable.ic_warning
        NoticeKind.Error -> Res.drawable.ic_error
    }
    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp).then(if (tag != null) Modifier.testTag(tag) else Modifier),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (action != null) {
                Spacer(Modifier.width(8.dp))
                action()
            }
        }
    }
}

/** A row of buttons at the end of a section, with the touch target gap. */
@Composable
fun SettingsButtons(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(LocalTouchTargets.current.gap, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** A plain text row of a settings screen, for example a short note below a group. */
@Composable
fun SettingsNote(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color, modifier = modifier.padding(vertical = 4.dp))
}
