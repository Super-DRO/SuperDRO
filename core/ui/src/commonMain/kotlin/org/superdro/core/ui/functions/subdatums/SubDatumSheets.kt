package org.superdro.core.ui.functions.subdatums

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.resources.Res
import org.superdro.core.resources.fn_close
import org.superdro.core.resources.fn_delete
import org.superdro.core.resources.fn_save
import org.superdro.core.resources.ic_add_location
import org.superdro.core.resources.ic_circle
import org.superdro.core.resources.ic_delete
import org.superdro.core.resources.ic_edit
import org.superdro.core.resources.ic_label
import org.superdro.core.resources.ic_my_location
import org.superdro.core.resources.ic_radio_unchecked
import org.superdro.core.resources.ic_rotate_right
import org.superdro.core.resources.ic_straighten
import org.superdro.core.resources.sd_edit_diameter
import org.superdro.core.resources.sd_edit_name
import org.superdro.core.resources.sd_edit_name_hint
import org.superdro.core.resources.sd_edit_note
import org.superdro.core.resources.sd_edit_tags
import org.superdro.core.resources.sd_edit_tags_hint
import org.superdro.core.resources.sd_edit_thread
import org.superdro.core.resources.sd_edit_title
import org.superdro.core.resources.sd_new_title
import org.superdro.core.resources.sd_shape_arbitrary
import org.superdro.core.resources.sd_shape_arc
import org.superdro.core.resources.sd_shape_current
import org.superdro.core.resources.sd_shape_custom
import org.superdro.core.resources.sd_shape_equidistant
import org.superdro.core.resources.sd_shape_grid
import org.superdro.core.resources.sd_shape_hole_circle
import org.superdro.core.resources.sd_shape_title
import org.superdro.core.resources.sd_tag_apply
import org.superdro.core.resources.sd_tag_count
import org.superdro.core.resources.sd_tag_field
import org.superdro.core.resources.sd_tag_hint
import org.superdro.core.resources.sd_tag_title
import org.superdro.core.ui.adaptive.AdaptiveSheet
import org.superdro.core.ui.functions.common.ErrorBlock
import org.superdro.core.ui.functions.common.FunctionActions
import org.superdro.core.ui.functions.common.FunctionSheet
import org.superdro.core.ui.functions.common.LengthField
import org.superdro.core.ui.functions.common.NumberField
import org.superdro.core.ui.functions.common.fieldTextTag
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.LocalTouchTargets
import org.superdro.core.ui.theme.accentTextFieldColors
import org.superdro.core.ui.theme.accentAssistChipColors

/** The test tags of the sheets of the sub-datums. */
object SubDatumSheetTags {
    const val NAME = "sd-edit-name"
    const val NOTE = "sd-edit-note"
    const val TAGS = "sd-edit-tags"
    const val X = "sd-edit-x"
    const val Y = "sd-edit-y"
    const val Z = "sd-edit-z"
    const val A = "sd-edit-a"
    const val DELETE = "sd-edit-delete"
    const val TAG_FIELD = "sd-tag-field"
    fun shape(item: ShapeItem): String = "sd-shape-${item.name}"
}

/** The events of the sub-datum edit sheet. */
interface SubDatumEditEvents {
    fun onName(text: String)
    fun onNote(text: String)
    fun onTags(text: String)
    fun onText(field: SubDatumField, text: String)
    fun onUnit(field: SubDatumField, metric: Boolean)
    fun onSave()
    fun onDelete()
    fun onDismiss()
}

/**
 * The sub-datum edit sheet: the name, the note, the tags, X, Y, Z, and A.
 * In the lathe diameter mode the X field
 * shows and takes a diameter. The coordinates of a thread point do not change here.
 */
@Composable
fun SubDatumEditSheet(state: SubDatumEditUiState, events: SubDatumEditEvents) {
    val errors = state.errors[SubDatumField.Coordinates]?.let { ErrorBlock(null, listOf(it)) }
    FunctionSheet(
        title = stringResource(if (state.newPoint) Res.string.sd_new_title else Res.string.sd_edit_title),
        onDismiss = events::onDismiss,
        primaryLabel = stringResource(Res.string.fn_save),
        onPrimary = events::onSave,
        primaryEnabled = state.loaded,
        errors = errors,
    ) {
        TextInput(
            label = stringResource(Res.string.sd_edit_name),
            value = state.name,
            onChange = events::onName,
            error = state.errors[SubDatumField.Name],
            tag = SubDatumSheetTags.NAME,
            placeholder = if (state.newPoint) stringResource(Res.string.sd_edit_name_hint) else null,
        )
        TextInput(stringResource(Res.string.sd_edit_note), state.note, events::onNote, null, SubDatumSheetTags.NOTE)
        TextInput(
            label = stringResource(Res.string.sd_edit_tags),
            value = state.tags,
            onChange = events::onTags,
            error = null,
            tag = SubDatumSheetTags.TAGS,
            placeholder = stringResource(Res.string.sd_edit_tags_hint),
        )
        if (state.thread) {
            Text(stringResource(Res.string.sd_edit_thread), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val coordinatesEnabled = !state.thread
        LengthField(
            label = if (state.xDiameter) stringResource(Res.string.sd_edit_diameter, state.xLabel) else state.xLabel,
            state = state.x,
            onTextChange = { events.onText(SubDatumField.X, it) },
            onUnitChange = { events.onUnit(SubDatumField.X, it) },
            error = state.errors[SubDatumField.X],
            enabled = coordinatesEnabled,
            tag = SubDatumSheetTags.X,
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.showY) {
            LengthField(
                label = state.yLabel,
                state = state.y,
                onTextChange = { events.onText(SubDatumField.Y, it) },
                onUnitChange = { events.onUnit(SubDatumField.Y, it) },
                error = state.errors[SubDatumField.Y],
                enabled = coordinatesEnabled,
                tag = SubDatumSheetTags.Y,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (state.showZ) {
            LengthField(
                label = state.zLabel,
                state = state.z,
                onTextChange = { events.onText(SubDatumField.Z, it) },
                onUnitChange = { events.onUnit(SubDatumField.Z, it) },
                error = state.errors[SubDatumField.Z],
                enabled = coordinatesEnabled,
                tag = SubDatumSheetTags.Z,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (state.showA) {
            NumberField(
                label = state.aLabel,
                state = state.a,
                onTextChange = { events.onText(SubDatumField.A, it) },
                error = state.errors[SubDatumField.A],
                suffix = "°",
                enabled = coordinatesEnabled,
                tag = SubDatumSheetTags.A,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (!state.newPoint) {
            OutlinedButton(
                onClick = events::onDelete,
                colors = AccentButtons.outlined(),
                modifier = Modifier.heightIn(min = LocalTouchTargets.current.button).testTag(SubDatumSheetTags.DELETE),
            ) {
                Icon(painterResource(Res.drawable.ic_delete), contentDescription = null)
                Text("  " + stringResource(Res.string.fn_delete))
            }
        }
    }
}

@Composable
private fun TextInput(label: String, value: String, onChange: (String) -> Unit, error: UiText?, tag: String, placeholder: String? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { text -> { Text(text) } },
        isError = error != null,
        supportingText = error?.let { text -> { Text(text.resolve()) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        colors = accentTextFieldColors(),
        modifier = Modifier.fillMaxWidth().heightIn(min = LocalTouchTargets.current.button).testTag(fieldTextTag(tag)),
    )
}

/** The events of the bulk tag sheet. */
interface TagSheetEvents {
    fun onText(text: String)
    fun onSuggestion(tag: String)
    fun onApply()
    fun onDismiss()
}

/**
 * The bulk tag sheet "Set Sub-Datum Tag":
 * the tag field with the tags of the workspace as suggestions. An empty field removes the tags.
 */
@Composable
fun TagSheet(state: TagSheetUiState, events: TagSheetEvents) {
    FunctionSheet(
        title = stringResource(Res.string.sd_tag_title),
        onDismiss = events::onDismiss,
        primaryLabel = stringResource(Res.string.sd_tag_apply),
        onPrimary = events::onApply,
        primaryEnabled = state.count > 0,
    ) {
        Text(stringResource(Res.string.sd_tag_count, state.count), style = MaterialTheme.typography.bodyLarge)
        TextInput(
            label = stringResource(Res.string.sd_tag_field),
            value = state.text,
            onChange = events::onText,
            error = null,
            tag = SubDatumSheetTags.TAG_FIELD,
            placeholder = stringResource(Res.string.sd_tag_hint),
        )
        if (state.suggestions.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.suggestions.forEach { tag ->
                    AssistChip(
                        onClick = { events.onSuggestion(tag) },
                        label = { Text(tag) },
                        leadingIcon = { Icon(painterResource(Res.drawable.ic_label), contentDescription = null) },
                        colors = accentAssistChipColors(),
                        modifier = Modifier.heightIn(min = 40.dp),
                    )
                }
            }
        }
    }
}

/** The items of the shape menu. */
enum class ShapeItem { CurrentPoint, CustomPoint, Arc, HoleCircle, ArbitraryHoleCircle, HoleGrid, Equidistant }

/** The items of the shape menu of a machine type: the arbitrary hole circle is not on a lathe. */
fun shapeItems(lathe: Boolean): List<ShapeItem> = ShapeItem.entries.filter { item -> !(lathe && item == ShapeItem.ArbitraryHoleCircle) }

/**
 * The shape menu: current coordinate,
 * custom coordinate, arc, hole circle, arbitrary hole circle, hole grid, and equidistant holes.
 */
@Composable
fun ShapeMenuSheet(lathe: Boolean, onSelect: (ShapeItem) -> Unit, onDismiss: () -> Unit) {
    AdaptiveSheet(onDismissRequest = onDismiss, title = stringResource(Res.string.sd_shape_title)) {
        Column {
            shapeItems(lathe).forEach { item ->
                val (icon, text) = shapeLabel(item)
                ListItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = LocalTouchTargets.current.button)
                        .clickable(role = Role.Button) { onSelect(item) }
                        .testTag(SubDatumSheetTags.shape(item)),
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    leadingContent = { Icon(painterResource(icon), contentDescription = null) },
                    headlineContent = { Text(stringResource(text), style = MaterialTheme.typography.titleMedium) },
                )
                HorizontalDivider()
            }
            FunctionActions(
                primaryLabel = stringResource(Res.string.fn_close),
                onPrimary = onDismiss,
                onCancel = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun shapeLabel(item: ShapeItem): Pair<DrawableResource, StringResource> = when (item) {
    ShapeItem.CurrentPoint -> Res.drawable.ic_my_location to Res.string.sd_shape_current
    ShapeItem.CustomPoint -> Res.drawable.ic_edit to Res.string.sd_shape_custom
    ShapeItem.Arc -> Res.drawable.ic_rotate_right to Res.string.sd_shape_arc
    ShapeItem.HoleCircle -> Res.drawable.ic_circle to Res.string.sd_shape_hole_circle
    ShapeItem.ArbitraryHoleCircle -> Res.drawable.ic_radio_unchecked to Res.string.sd_shape_arbitrary
    ShapeItem.HoleGrid -> Res.drawable.ic_add_location to Res.string.sd_shape_grid
    ShapeItem.Equidistant -> Res.drawable.ic_straighten to Res.string.sd_shape_equidistant
}
