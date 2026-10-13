package org.superdro.core.ui.functions.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.resources.Res
import org.superdro.core.resources.fn_unit_cd
import org.superdro.core.resources.fn_unit_inch
import org.superdro.core.resources.fn_unit_mm
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.theme.LocalTouchTargets
import org.superdro.core.ui.theme.accentColor
import org.superdro.core.ui.theme.accentTextFieldColors

/** The test tag of the text field of a field with the tag [tag]. */
fun fieldTextTag(tag: String): String = "$tag-text"

/** The test tag of a unit button of a length field with the tag [tag]. */
fun fieldUnitTag(tag: String, metric: Boolean): String = "$tag-unit-${if (metric) "mm" else "in"}"

/**
 * A number field with the expression evaluator. The field shows the error of the evaluator, or
 * [error] (for example "Circle Radius value is too small"), below the text.
 *
 * @param suffix A short text after the value, for example "°".
 */
@Composable
fun NumberField(
    label: String,
    state: NumberFieldState,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    error: UiText? = null,
    suffix: String? = null,
    enabled: Boolean = true,
    tag: String? = null,
) {
    val message = state.error ?: error
    OutlinedTextField(
        value = state.text,
        onValueChange = onTextChange,
        label = { Text(label) },
        suffix = suffix?.let { text -> { Text(text) } },
        isError = message != null,
        supportingText = message?.let { text -> { Text(text.resolve()) } },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
        colors = accentTextFieldColors(),
        modifier = modifier.heightIn(min = LocalTouchTargets.current.button).let { base ->
            if (tag != null) base.testTag(fieldTextTag(tag)) else base
        },
    )
}

/**
 * A length field with its own inch or mm control. The field keeps the
 * value in µm ([LengthFieldState]). A change of the unit shows the same length in the new unit.
 */
@Composable
fun LengthField(
    label: String,
    state: LengthFieldState,
    onTextChange: (String) -> Unit,
    onUnitChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    error: UiText? = null,
    enabled: Boolean = true,
    tag: String? = null,
) {
    val message = state.error ?: error
    Row(modifier = modifier, verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.text,
            onValueChange = onTextChange,
            label = { Text(label) },
            isError = message != null,
            supportingText = message?.let { text -> { Text(text.resolve()) } },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
            colors = accentTextFieldColors(),
            modifier = Modifier.weight(1f).heightIn(min = LocalTouchTargets.current.button).let { base ->
                if (tag != null) base.testTag(fieldTextTag(tag)) else base
            },
        )
        UnitControl(
            metric = state.metric,
            onChange = onUnitChange,
            fieldLabel = label,
            enabled = enabled,
            tag = tag,
            modifier = Modifier.width(UNIT_CONTROL_WIDTH),
        )
    }
}

/** The width of the inch or mm control. */
private val UNIT_CONTROL_WIDTH = 132.dp

/** The inch or mm control of a length field: two segmented buttons. */
@Composable
fun UnitControl(
    metric: Boolean,
    onChange: (Boolean) -> Unit,
    fieldLabel: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tag: String? = null,
) {
    val description = stringResource(Res.string.fn_unit_cd, fieldLabel)
    SingleChoiceSegmentedButtonRow(modifier = modifier.padding(top = 4.dp).semantics { contentDescription = description }) {
        listOf(true to Res.string.fn_unit_mm, false to Res.string.fn_unit_inch).forEachIndexed { index, (value, text) ->
            SegmentedButton(
                selected = metric == value,
                onClick = { onChange(value) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index, 2),
                colors = SegmentedButtonDefaults.colors(activeBorderColor = accentColor()),
                modifier = Modifier.heightIn(min = 48.dp).let { base -> if (tag != null) base.testTag(fieldUnitTag(tag, value)) else base },
                label = { Text(stringResource(text)) },
            )
        }
    }
}

/**
 * A row of segmented buttons for one choice of [options], for example the plane or the
 * position source. Each option has its text.
 */
@Composable
fun <T> ChoiceRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tag: String? = null,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, text) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                colors = SegmentedButtonDefaults.colors(activeBorderColor = accentColor()),
                modifier = Modifier.heightIn(min = LocalTouchTargets.current.button).let { base ->
                    if (tag != null) base.testTag("$tag-$index") else base
                },
                label = { Text(text, maxLines = 1) },
            )
        }
    }
}

/** A title of a group of fields. */
@Composable
fun FieldGroupTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.widthIn(min = 0.dp),
    )
}
