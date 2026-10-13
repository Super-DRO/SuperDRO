package org.superdro.core.ui.adapter.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.superdro.core.resources.Res
import org.superdro.core.resources.ic_check
import org.superdro.core.resources.ic_error
import org.superdro.core.resources.ic_hourglass
import org.superdro.core.resources.ic_info
import org.superdro.core.resources.ic_warning
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.theme.accentTextFieldColors
import org.superdro.core.ui.theme.accentSwitchColors

/** The minimum height of a row with a control (48 dp or more). */
val ROW_MIN_HEIGHT = 56.dp

/** The kind of a status line. Each kind has its own icon and color (an icon and a text for each status). */
enum class StatusKind { Info, Progress, Success, Warning, Error }

/** The title of a section of a screen. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.padding(top = 16.dp, bottom = 8.dp).semantics { heading() },
    )
}

/** One label and one value in a row. */
@Composable
fun ValueRow(label: String, value: String, modifier: Modifier = Modifier, valueTag: String? = null) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 40.dp).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = if (valueTag != null) Modifier.testTag(valueTag) else Modifier,
        )
    }
}

/** A switch with its label. The full row toggles the switch. */
@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    supporting: String? = null,
    tag: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN_HEIGHT)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (supporting != null) {
                Text(supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled, colors = accentSwitchColors())
    }
}

/**
 * A text field for a number. The presenter checks the text and gives [error]. [decimal] selects
 * the decimal keyboard, else the number keyboard.
 */
@Composable
fun NumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    error: UiText? = null,
    suffix: String? = null,
    supporting: String? = null,
    decimal: Boolean = true,
    enabled: Boolean = true,
    tag: String? = null,
) {
    val errorText = error?.resolve()
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = errorText != null,
        suffix = suffix?.let { text -> { Text(text) } },
        supportingText = (errorText ?: supporting)?.let { text -> { Text(text) } },
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        colors = accentTextFieldColors(),
        modifier = modifier.fillMaxWidth().then(if (tag != null) Modifier.testTag(tag) else Modifier),
    )
}

/** A text field for a text, for example a name. */
@Composable
fun TextValueField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    error: UiText? = null,
    enabled: Boolean = true,
    tag: String? = null,
) {
    val errorText = error?.resolve()
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = errorText != null,
        supportingText = errorText?.let { text -> { Text(text) } },
        colors = accentTextFieldColors(),
        modifier = modifier.fillMaxWidth().then(if (tag != null) Modifier.testTag(tag) else Modifier),
    )
}

/** One choice of [options] as a segmented button row, with a label above it. */
@Composable
fun <T> ChoiceRow(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tag: String? = null,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(4.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().then(if (tag != null) Modifier.testTag(tag) else Modifier)) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(optionLabel(option), maxLines = 2) }
            }
        }
    }
}

/** The icon of a [StatusKind]. */
fun statusIcon(kind: StatusKind): DrawableResource = when (kind) {
    StatusKind.Info -> Res.drawable.ic_info
    StatusKind.Progress -> Res.drawable.ic_hourglass
    StatusKind.Success -> Res.drawable.ic_check
    StatusKind.Warning -> Res.drawable.ic_warning
    StatusKind.Error -> Res.drawable.ic_error
}

/** The color of a [StatusKind]. */
@Composable
fun statusColor(kind: StatusKind): Color = when (kind) {
    StatusKind.Error -> MaterialTheme.colorScheme.error
    StatusKind.Warning -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * A status line with an icon and a text. The icon has no content description, because the
 * text tells the status.
 */
@Composable
fun StatusLine(kind: StatusKind, text: String, modifier: Modifier = Modifier, tag: String? = null) {
    val color = statusColor(kind)
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp).then(if (tag != null) Modifier.testTag(tag) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(painterResource(statusIcon(kind)), contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = if (kind == StatusKind.Error) color else MaterialTheme.colorScheme.onSurface)
    }
}
