package org.superdro.core.ui.functions.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.model.AxisId
import org.superdro.core.resources.Res
import org.superdro.core.resources.fn_save
import org.superdro.core.resources.ic_sync
import org.superdro.core.resources.tools_field_description
import org.superdro.core.resources.tools_field_diameter
import org.superdro.core.resources.tools_field_name
import org.superdro.core.resources.tools_field_teeth
import org.superdro.core.resources.tools_field_type
import org.superdro.core.resources.tools_field_units
import org.superdro.core.resources.tools_field_x_offset
import org.superdro.core.resources.tools_field_z_offset
import org.superdro.core.resources.tools_sync
import org.superdro.core.resources.tools_sync_cd
import org.superdro.core.resources.tools_title_edit
import org.superdro.core.resources.tools_title_new
import org.superdro.core.resources.tools_type_boring
import org.superdro.core.resources.tools_type_od_facing
import org.superdro.core.resources.tools_units_imperial
import org.superdro.core.resources.tools_units_metric
import org.superdro.core.ui.functions.common.ChoiceRow
import org.superdro.core.ui.functions.common.FieldGroupTitle
import org.superdro.core.ui.functions.common.FunctionSheet
import org.superdro.core.ui.functions.common.LengthFieldState
import org.superdro.core.ui.functions.common.LengthTexts
import org.superdro.core.ui.functions.common.NumberField
import org.superdro.core.ui.functions.common.fieldTextTag
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.LocalTouchTargets
import org.superdro.core.ui.theme.accentTextFieldColors
import androidx.compose.material3.MaterialTheme
import org.superdro.core.resources.drawing_tool_geometry_not_defined
import org.superdro.core.resources.drawing_tool_geometry_title
import org.superdro.core.resources.integration_tool_geometry_defined
import org.superdro.core.resources.integration_tool_geometry_open
import org.superdro.core.resources.integration_tool_geometry_save_first

/** The test tags of the fields of the tool edit sheet. */
object ToolEditTags {
    const val NAME = "tools-edit-name"
    const val DESCRIPTION = "tools-edit-description"
    const val DIAMETER = "tools-edit-diameter"
    const val TEETH = "tools-edit-teeth"
    const val Z_OFFSET = "tools-edit-z"
    const val X_OFFSET = "tools-edit-x"
    const val SYNC_X = "tools-edit-sync-x"
    const val SYNC_Z = "tools-edit-sync-z"
    const val GEOMETRY = "tools-edit-geometry"
    const val GEOMETRY_STATE = "tools-edit-geometry-state"
}

/** The events of the tool edit sheet. */
interface ToolEditEvents {
    fun onName(text: String)
    fun onDescription(text: String)
    fun onMetric(metric: Boolean)
    fun onDiameter(text: String)
    fun onToothCount(text: String)
    fun onZOffset(text: String)
    fun onXOffset(text: String)
    fun onInternal(internal: Boolean)
    fun onSync(axis: AxisId)
    fun onSave()
    fun onDismiss()

    /** The cutter geometry button of a lathe tool: opens the cutter geometry screen. */
    fun onGeometry() {}
}

/**
 * The tool edit sheet (`ToolEditDialogLathe`): a mill tool has the name, the description, the
 * units, the flute diameter, the tooth count, and the Z offset. A lathe tool has the name, the
 * description, OD/facing or boring, the X and Z offsets, and the sync buttons.
 */
@Composable
fun ToolEditSheet(state: ToolEditUiState, events: ToolEditEvents) {
    FunctionSheet(
        title = stringResource(if (state.newTool) Res.string.tools_title_new else Res.string.tools_title_edit),
        onDismiss = events::onDismiss,
        primaryLabel = stringResource(Res.string.fn_save),
        onPrimary = events::onSave,
        primaryEnabled = state.loaded,
    ) {
        TextInput(stringResource(Res.string.tools_field_name), state.name, events::onName, state.errors[ToolField.Name], ToolEditTags.NAME)
        TextInput(
            stringResource(Res.string.tools_field_description),
            state.description,
            events::onDescription,
            state.errors[ToolField.Description],
            ToolEditTags.DESCRIPTION,
        )
        val unit = stringResource(LengthTexts.unit(state.metric))
        if (state.lathe) {
            FieldGroupTitle(stringResource(Res.string.tools_field_type))
            ChoiceRow(
                options = listOf(false to stringResource(Res.string.tools_type_od_facing), true to stringResource(Res.string.tools_type_boring)),
                selected = state.internal,
                onSelect = events::onInternal,
            )
            SyncedLength(
                label = stringResource(Res.string.tools_field_x_offset),
                field = state.xOffset,
                unit = unit,
                error = state.errors[ToolField.XOffset],
                onText = events::onXOffset,
                onSync = { events.onSync(AxisId.X) },
                tag = ToolEditTags.X_OFFSET,
                syncTag = ToolEditTags.SYNC_X,
                axis = "X",
            )
            SyncedLength(
                label = stringResource(Res.string.tools_field_z_offset),
                field = state.zOffset,
                unit = unit,
                error = state.errors[ToolField.ZOffset],
                onText = events::onZOffset,
                onSync = { events.onSync(AxisId.Z) },
                tag = ToolEditTags.Z_OFFSET,
                syncTag = ToolEditTags.SYNC_Z,
                axis = "Z",
            )
            GeometryRow(state, events::onGeometry)
        } else {
            FieldGroupTitle(stringResource(Res.string.tools_field_units))
            ChoiceRow(
                options = listOf(false to stringResource(Res.string.tools_units_imperial), true to stringResource(Res.string.tools_units_metric)),
                selected = state.metric,
                onSelect = events::onMetric,
            )
            LengthText(stringResource(Res.string.tools_field_diameter), state.diameter, unit, state.errors[ToolField.Diameter], events::onDiameter, ToolEditTags.DIAMETER)
            NumberField(
                label = stringResource(Res.string.tools_field_teeth),
                state = state.toothCount,
                onTextChange = events::onToothCount,
                error = state.errors[ToolField.ToothCount],
                tag = ToolEditTags.TEETH,
                modifier = Modifier.fillMaxWidth(),
            )
            LengthText(stringResource(Res.string.tools_field_z_offset), state.zOffset, unit, state.errors[ToolField.ZOffset], events::onZOffset, ToolEditTags.Z_OFFSET)
        }
    }
}

/**
 * The cutter geometry block of a lathe tool: the state
 * "Not defined" or "Defined" and the button that opens the cutter geometry screen. A new tool
 * must be saved first, so the button is disabled and a hint tells it.
 */
@Composable
private fun GeometryRow(state: ToolEditUiState, onGeometry: () -> Unit) {
    val title = stringResource(Res.string.drawing_tool_geometry_title)
    FieldGroupTitle(title)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = when {
                !state.geometryAvailable -> stringResource(Res.string.integration_tool_geometry_save_first)
                state.geometryDefined -> stringResource(Res.string.integration_tool_geometry_defined)
                else -> stringResource(Res.string.drawing_tool_geometry_not_defined)
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).testTag(ToolEditTags.GEOMETRY_STATE),
        )
        OutlinedButton(
            onClick = onGeometry,
            enabled = state.geometryAvailable,
            colors = AccentButtons.outlined(),
            modifier = Modifier.heightIn(min = LocalTouchTargets.current.button).testTag(ToolEditTags.GEOMETRY),
        ) { Text(stringResource(Res.string.integration_tool_geometry_open)) }
    }
}

@Composable
private fun TextInput(label: String, value: String, onChange: (String) -> Unit, error: UiText?, tag: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        isError = error != null,
        supportingText = error?.let { text -> { Text(text.resolve()) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        colors = accentTextFieldColors(),
        modifier = Modifier.fillMaxWidth().heightIn(min = LocalTouchTargets.current.button).testTag(fieldTextTag(tag)),
    )
}

/** A length field in the unit of the tool, with the unit as a suffix (the units choice of the tool sets it). */
@Composable
private fun LengthText(label: String, field: LengthFieldState, unit: String, error: UiText?, onText: (String) -> Unit, tag: String, modifier: Modifier = Modifier.fillMaxWidth()) {
    val message = field.error ?: error
    OutlinedTextField(
        value = field.text,
        onValueChange = onText,
        label = { Text(label) },
        suffix = { Text(unit) },
        isError = message != null,
        supportingText = message?.let { text -> { Text(text.resolve()) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
        colors = accentTextFieldColors(),
        modifier = modifier.heightIn(min = LocalTouchTargets.current.button).testTag(fieldTextTag(tag)),
    )
}

@Composable
private fun SyncedLength(
    label: String,
    field: LengthFieldState,
    unit: String,
    error: UiText?,
    onText: (String) -> Unit,
    onSync: () -> Unit,
    tag: String,
    syncTag: String,
    axis: String,
) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LengthText(label, field, unit, error, onText, tag, Modifier.weight(1f))
        OutlinedButton(
            onClick = onSync,
            colors = AccentButtons.outlined(),
            modifier = Modifier.padding(top = 4.dp).heightIn(min = LocalTouchTargets.current.button).testTag(syncTag),
        ) {
            Icon(painterResource(Res.drawable.ic_sync), contentDescription = stringResource(Res.string.tools_sync_cd, axis))
            Text(" " + stringResource(Res.string.tools_sync))
        }
    }
}
