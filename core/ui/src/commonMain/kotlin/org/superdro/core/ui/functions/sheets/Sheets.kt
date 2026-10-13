package org.superdro.core.ui.functions.sheets

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.functions.compensation.ToolPosition
import org.superdro.core.functions.zreference.ZReferenceTexts
import org.superdro.core.resources.Res
import org.superdro.core.resources.fn_close
import org.superdro.core.resources.fn_unit_inch
import org.superdro.core.resources.fn_unit_mm
import org.superdro.core.resources.fns_absolute
import org.superdro.core.resources.fns_apply_compensation
import org.superdro.core.resources.fns_apply_to_carriage
import org.superdro.core.resources.fns_apply_to_cross_slide
import org.superdro.core.resources.fns_apply_to_x
import org.superdro.core.resources.fns_apply_to_y
import org.superdro.core.resources.fns_auxiliary_type
import org.superdro.core.resources.fns_caliper_value
import org.superdro.core.resources.fns_clear_compensation
import org.superdro.core.resources.fns_clear_offset
import org.superdro.core.resources.fns_compound_angle
import org.superdro.core.resources.fns_compound_reference
import org.superdro.core.resources.fns_copy_to_x
import org.superdro.core.resources.fns_copy_to_x_factor_diameter
import org.superdro.core.resources.fns_copy_to_x_factor_radius
import org.superdro.core.resources.fns_desired_z
import org.superdro.core.resources.fns_direction
import org.superdro.core.resources.fns_distance
import org.superdro.core.resources.fns_head_nod
import org.superdro.core.resources.fns_head_tilt
import org.superdro.core.resources.fns_incremental
import org.superdro.core.resources.fns_line_angle
import org.superdro.core.resources.fns_measure_hint
import org.superdro.core.resources.fns_mode
import org.superdro.core.resources.fns_no_value
import org.superdro.core.resources.fns_position
import org.superdro.core.resources.fns_select_tool
import org.superdro.core.resources.fns_set_height
import org.superdro.core.resources.fns_set_offset
import org.superdro.core.resources.fns_set_radius
import org.superdro.core.resources.fns_set_z
import org.superdro.core.resources.fns_setter_height
import org.superdro.core.resources.fns_title_angle_measure
import org.superdro.core.resources.fns_title_compound
import org.superdro.core.resources.fns_title_dimension_measure
import org.superdro.core.resources.fns_title_head_tilt
import org.superdro.core.resources.fns_title_height
import org.superdro.core.resources.fns_title_lathe_offset
import org.superdro.core.resources.fns_title_radius
import org.superdro.core.resources.fns_tool_diameter
import org.superdro.core.resources.fns_tool_library
import org.superdro.core.resources.fns_weight_x
import org.superdro.core.resources.fns_weight_y
import org.superdro.core.resources.fns_weight_z
import org.superdro.core.resources.fns_x_offset
import org.superdro.core.resources.fns_z_offset
import org.superdro.core.resources.fns_z_reference_hint
import org.superdro.core.resources.fns_zero
import org.superdro.core.resources.fns_units
import org.superdro.core.ui.adaptive.AdaptiveSheet
import org.superdro.core.ui.functions.common.ChoiceRow
import org.superdro.core.ui.functions.common.ErrorBlock
import org.superdro.core.ui.functions.common.FieldGroupTitle
import org.superdro.core.ui.functions.common.FunctionSheet
import org.superdro.core.ui.functions.common.LengthField
import org.superdro.core.ui.functions.common.NumberField
import org.superdro.core.ui.functions.common.PRIMARY_BUTTON_TAG
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.text.toUiText
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.LocalTouchTargets

/** The test tags of the fields of the sheets. */
object SheetFieldTags {
    const val DIAMETER = "sheets-diameter"
    const val Z_OFFSET = "sheets-z-offset"
    const val X_OFFSET = "sheets-x-offset"
    const val SETTER_HEIGHT = "sheets-setter-height"
    const val DESIRED_Z = "sheets-desired-z"
    const val ANGLE = "sheets-angle"
    const val TILT = "sheets-tilt"
    const val NOD = "sheets-nod"
}

private fun errors(error: UiText?): ErrorBlock? = error?.let { ErrorBlock(null, listOf(it)) }

// ---------------------------------------------------------------------------------------------
// The measure sheets

/**
 * The measure sheet: the live
 * values of the two axes, the distance, the line angle (−90° to 90°), and the direction (0° to
 * 360°). The unit control sets the unit of the lengths.
 */
@Composable
fun AngleMeasureSheet(state: AngleMeasureUiState, onUnit: (Boolean) -> Unit, onDismiss: () -> Unit) {
    AdaptiveSheet(onDismissRequest = onDismiss, title = stringResource(Res.string.fns_title_angle_measure)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.fns_measure_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FieldGroupTitle(stringResource(Res.string.fns_units))
            ChoiceRow(
                options = listOf(true to stringResource(Res.string.fn_unit_mm), false to stringResource(Res.string.fn_unit_inch)),
                selected = state.metric,
                onSelect = onUnit,
                tag = SheetTags.UNITS,
            )
            val unit = stringResource(if (state.metric) Res.string.fn_unit_mm else Res.string.fn_unit_inch)
            state.unavailable?.let { Notice(it) }
            ValueRow(state.aLabel, state.aText.ifEmpty { "—" } + " $unit")
            ValueRow(state.bLabel, state.bText.ifEmpty { "—" } + " $unit")
            HorizontalDivider()
            ValueRow(stringResource(Res.string.fns_distance), state.distance.ifEmpty { "—" } + " $unit", large = true)
            ValueRow(stringResource(Res.string.fns_line_angle), state.lineAngle.ifEmpty { "—" } + "°", large = true)
            ValueRow(stringResource(Res.string.fns_direction), state.direction.ifEmpty { "—" } + "°", large = true)
            CloseRow(onDismiss)
        }
    }
}

/** The events of the dimension measure sheet. */
interface DimensionMeasureEvents {
    fun onZero()
    fun onZeroLongPress()
    fun onToggleMode()
    fun onToggleUnits()
    fun onCopyToX()
    fun onDismiss()
}

/**
 * The dimension measure sheet: the caliper value with zero (a long press sets the absolute zero),
 * ABS or INC, the unit, and "Copy to X".
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DimensionMeasureSheet(state: DimensionMeasureUiState, events: DimensionMeasureEvents) {
    AdaptiveSheet(onDismissRequest = events::onDismiss, title = stringResource(Res.string.fns_title_dimension_measure)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val unit = stringResource(if (state.metric) Res.string.fn_unit_mm else Res.string.fn_unit_inch)
            val value = if (state.hasValue) "${state.value} $unit" else stringResource(Res.string.fns_no_value)
            ValueRow(stringResource(Res.string.fns_caliper_value), value, large = true)
            val targets = LocalTouchTargets.current
            Row(horizontalArrangement = Arrangement.spacedBy(targets.gap), modifier = Modifier.fillMaxWidth()) {
                val zero = stringResource(Res.string.fns_zero)
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = targets.button)
                        .combinedClickable(role = Role.Button, onClick = events::onZero, onLongClick = events::onZeroLongPress)
                        .semantics { contentDescription = zero }
                        .testTag(SheetTags.ZERO),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.heightIn(min = targets.button)) {
                        Text(zero, style = MaterialTheme.typography.titleMedium)
                    }
                }
                val mode = stringResource(if (state.incremental) Res.string.fns_incremental else Res.string.fns_absolute)
                val modeName = stringResource(Res.string.fns_mode)
                OutlinedButton(
                    onClick = events::onToggleMode,
                    colors = AccentButtons.outlined(),
                    modifier = Modifier.weight(1f).heightIn(min = targets.button).testTag(SheetTags.MODE)
                        .semantics { contentDescription = "$modeName: $mode" },
                ) { Text(mode) }
                OutlinedButton(
                    onClick = events::onToggleUnits,
                    colors = AccentButtons.outlined(),
                    modifier = Modifier.weight(1f).heightIn(min = targets.button).testTag(SheetTags.UNITS),
                ) { Text(unit) }
            }
            HorizontalDivider()
            Text(
                stringResource(if (state.xDiameter) Res.string.fns_copy_to_x_factor_diameter else Res.string.fns_copy_to_x_factor_radius, state.xLabel),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(targets.gap, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                SheetButton(stringResource(Res.string.fn_close), events::onDismiss, tag = "sheets-close")
                FilledTonalButton(
                    onClick = events::onCopyToX,
                    enabled = state.hasValue,
                    modifier = Modifier.heightIn(min = targets.button).testTag(SheetTags.COPY_TO_X),
                ) { Text(stringResource(Res.string.fns_copy_to_x, state.xLabel)) }
            }
        }
    }
}

@Composable
private fun ColumnScope.CloseRow(onDismiss: () -> Unit) {
    FilledTonalButton(
        onClick = onDismiss,
        modifier = Modifier.align(Alignment.End).heightIn(min = LocalTouchTargets.current.button).testTag(PRIMARY_BUTTON_TAG),
    ) { Text(stringResource(Res.string.fn_close)) }
}

// ---------------------------------------------------------------------------------------------
// The compensation sheets

/** The events of the radius compensation sheet. */
interface RadiusCompensationEvents {
    fun onTool(id: String?)
    fun onDiameter(text: String)
    fun onDiameterUnit(metric: Boolean)
    fun onPosition(position: ToolPosition)
    fun onApply()
    fun onClear()
    fun onToolLibrary()
    fun onDismiss()
}

/**
 * The radius compensation sheet:
 * the tool (a library tool or a custom diameter), the 3 × 3 direction control, "Set Radius
 * Compensation", and "Clear Compensation" when the compensation is active.
 */
@Composable
fun RadiusCompensationSheet(state: RadiusCompensationUiState, events: RadiusCompensationEvents) {
    FunctionSheet(
        title = stringResource(Res.string.fns_title_radius),
        onDismiss = events::onDismiss,
        primaryLabel = stringResource(Res.string.fns_set_radius),
        onPrimary = events::onApply,
        primaryEnabled = state.loaded,
        errors = errors(state.error),
    ) {
        ToolPicker(state.tools, state.toolId, events::onTool)
        LengthField(
            label = stringResource(Res.string.fns_tool_diameter),
            state = state.diameter,
            onTextChange = events::onDiameter,
            onUnitChange = events::onDiameterUnit,
            enabled = state.toolId == null,
            tag = SheetFieldTags.DIAMETER,
        )
        FieldGroupTitle(stringResource(Res.string.fns_position))
        DirectionGrid(state.position, events::onPosition)
        Row(horizontalArrangement = Arrangement.spacedBy(LocalTouchTargets.current.gap)) {
            SheetButton(stringResource(Res.string.fns_tool_library), events::onToolLibrary, SheetTags.TOOL_LIBRARY)
            SheetButton(stringResource(Res.string.fns_clear_compensation), events::onClear, SheetTags.CLEAR, enabled = state.active)
        }
    }
}

/** The events of the height compensation sheet. */
interface HeightCompensationEvents {
    fun onTool(id: String?)
    fun onZOffset(text: String)
    fun onZOffsetUnit(metric: Boolean)
    fun onApply()
    fun onClear()
    fun onToolLibrary()
    fun onDismiss()
}

/**
 * The height compensation sheet:
 * the tool or a custom Z offset, "Set Height Compensation", and "Clear Compensation".
 */
@Composable
fun HeightCompensationSheet(state: HeightCompensationUiState, events: HeightCompensationEvents) {
    FunctionSheet(
        title = stringResource(Res.string.fns_title_height),
        onDismiss = events::onDismiss,
        primaryLabel = stringResource(Res.string.fns_set_height),
        onPrimary = events::onApply,
        primaryEnabled = state.loaded,
        errors = errors(state.error),
    ) {
        ToolPicker(state.tools, state.toolId, events::onTool)
        LengthField(
            label = stringResource(Res.string.fns_z_offset),
            state = state.zOffset,
            onTextChange = events::onZOffset,
            onUnitChange = events::onZOffsetUnit,
            enabled = state.toolId == null,
            tag = SheetFieldTags.Z_OFFSET,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(LocalTouchTargets.current.gap)) {
            SheetButton(stringResource(Res.string.fns_tool_library), events::onToolLibrary, SheetTags.TOOL_LIBRARY)
            SheetButton(stringResource(Res.string.fns_clear_compensation), events::onClear, SheetTags.CLEAR, enabled = state.active)
        }
    }
}

/** The events of the lathe tool offset sheet. */
interface LatheToolOffsetEvents {
    fun onTool(id: String?)
    fun onXOffset(text: String)
    fun onZOffset(text: String)
    fun onXUnit(metric: Boolean)
    fun onZUnit(metric: Boolean)
    fun onApply()
    fun onSelectTool()
    fun onClear()
    fun onToolLibrary()
    fun onDismiss()
}

/**
 * The lathe tool offset sheet: the tool or
 * the X and Z offsets, "Set Tool Offset", "Select Tool" for a library tool, and "Clear Offset".
 */
@Composable
fun LatheToolOffsetSheet(state: LatheToolOffsetUiState, events: LatheToolOffsetEvents) {
    FunctionSheet(
        title = stringResource(Res.string.fns_title_lathe_offset),
        onDismiss = events::onDismiss,
        primaryLabel = stringResource(Res.string.fns_set_offset),
        onPrimary = events::onApply,
        primaryEnabled = state.loaded,
        errors = errors(state.error),
    ) {
        ToolPicker(state.tools, state.toolId, events::onTool)
        LengthField(
            label = stringResource(Res.string.fns_x_offset),
            state = state.xOffset,
            onTextChange = events::onXOffset,
            onUnitChange = events::onXUnit,
            enabled = state.toolId == null,
            tag = SheetFieldTags.X_OFFSET,
        )
        LengthField(
            label = stringResource(Res.string.fns_z_offset),
            state = state.zOffset,
            onTextChange = events::onZOffset,
            onUnitChange = events::onZUnit,
            enabled = state.toolId == null,
            tag = SheetFieldTags.Z_OFFSET,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(LocalTouchTargets.current.gap)) {
            SheetButton(stringResource(Res.string.fns_tool_library), events::onToolLibrary, SheetTags.TOOL_LIBRARY)
            if (state.canSelect) SheetButton(stringResource(Res.string.fns_select_tool), events::onSelectTool, SheetTags.SELECT_TOOL)
            SheetButton(stringResource(Res.string.fns_clear_offset), events::onClear, SheetTags.CLEAR, enabled = state.active)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The Z reference

/** The events of the Z reference sheet. */
interface ZReferenceEvents {
    fun onSetterHeight(text: String)
    fun onSetterHeightUnit(metric: Boolean)
    fun onDesiredZ(text: String)
    fun onDesiredZUnit(metric: Boolean)
    fun onApply()
    fun onDismiss()
}

/**
 * The Z reference sheet in the manual mode:
 * the setter height A and the desired Z value B, each with its unit, and "Set Z". "Set Z" is
 * disabled with the reason when Z is not enabled or the adapter is not connected.
 *
 * @param automatic The place of the automatic mode (the "Auto" button of the graphics
 *   area), or null.
 */
@Composable
fun ZReferenceSheet(state: ZReferenceUiState, events: ZReferenceEvents, automatic: (@Composable () -> Unit)? = null) {
    FunctionSheet(
        title = ZReferenceTexts.TITLE.message().toUiText().resolve(),
        onDismiss = events::onDismiss,
        primaryLabel = stringResource(Res.string.fns_set_z, state.zLabel),
        onPrimary = events::onApply,
        primaryEnabled = state.loaded && state.block == null,
        errors = errors(state.error),
    ) {
        Text(stringResource(Res.string.fns_z_reference_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.block?.let { Notice(it) }
        LengthField(
            label = stringResource(Res.string.fns_setter_height),
            state = state.setterHeight,
            onTextChange = events::onSetterHeight,
            onUnitChange = events::onSetterHeightUnit,
            tag = SheetFieldTags.SETTER_HEIGHT,
        )
        LengthField(
            label = stringResource(Res.string.fns_desired_z, state.zLabel),
            state = state.desiredZ,
            onTextChange = events::onDesiredZ,
            onUnitChange = events::onDesiredZUnit,
            tag = SheetFieldTags.DESIRED_Z,
        )
        automatic?.invoke()
    }
}

// ---------------------------------------------------------------------------------------------
// The projection sheets

/** The events of the compound slide sheet. */
interface CompoundEvents {
    fun onAngle(text: String)
    fun onApplyX(on: Boolean)
    fun onApplyZ(on: Boolean)
    fun onApply()
    fun onClear()
    fun onDismiss()
}

/**
 * The compound slide sheet:
 * the type of the auxiliary readout, the zero position, the compound angle, "Apply to Cross
 * Slide", "Apply to Carriage", the live weights, and "Clear Compensation".
 */
@Composable
fun CompoundSheet(state: CompoundUiState, events: CompoundEvents) {
    FunctionSheet(
        title = stringResource(Res.string.fns_title_compound),
        onDismiss = events::onDismiss,
        primaryLabel = stringResource(Res.string.fns_apply_compensation),
        onPrimary = events::onApply,
        primaryEnabled = state.loaded && state.problem == null,
        errors = errors(state.error),
    ) {
        state.auxiliaryType?.let { ValueRow(stringResource(Res.string.fns_auxiliary_type), it.resolve()) }
        state.reference?.let { reference ->
            Text(
                stringResource(Res.string.fns_compound_reference, reference.resolve()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.problem?.let { Notice(it) }
        NumberField(
            label = stringResource(Res.string.fns_compound_angle),
            state = state.angle,
            onTextChange = events::onAngle,
            suffix = "°",
            tag = SheetFieldTags.ANGLE,
            modifier = Modifier.fillMaxWidth(),
        )
        SwitchRow(stringResource(Res.string.fns_apply_to_cross_slide), state.applyX, events::onApplyX, SheetTags.switch("x"))
        SwitchRow(stringResource(Res.string.fns_apply_to_carriage), state.applyZ, events::onApplyZ, SheetTags.switch("z"))
        ValueRow(stringResource(Res.string.fns_weight_x), state.xWeight)
        ValueRow(stringResource(Res.string.fns_weight_z), state.zWeight)
        SheetButton(stringResource(Res.string.fns_clear_compensation), events::onClear, SheetTags.CLEAR, enabled = state.active)
    }
}

/** The events of the head tilt sheet. */
interface HeadTiltEvents {
    fun onTilt(text: String)
    fun onNod(text: String)
    fun onApplyX(on: Boolean)
    fun onApplyY(on: Boolean)
    fun onApply()
    fun onClear()
    fun onDismiss()
}

/**
 * The head tilt sheet: the tilt
 * angle α and the nod angle β, "Apply to X", "Apply to Y", the live weights, and "Clear
 * Compensation".
 */
@Composable
fun HeadTiltSheet(state: HeadTiltUiState, events: HeadTiltEvents) {
    FunctionSheet(
        title = stringResource(Res.string.fns_title_head_tilt),
        onDismiss = events::onDismiss,
        primaryLabel = stringResource(Res.string.fns_apply_compensation),
        onPrimary = events::onApply,
        primaryEnabled = state.loaded && state.problem == null,
        errors = errors(state.error),
    ) {
        state.problem?.let { Notice(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(LocalTouchTargets.current.gap)) {
            NumberField(stringResource(Res.string.fns_head_tilt), state.tilt, events::onTilt, suffix = "°", tag = SheetFieldTags.TILT, modifier = Modifier.weight(1f))
            NumberField(stringResource(Res.string.fns_head_nod), state.nod, events::onNod, suffix = "°", tag = SheetFieldTags.NOD, modifier = Modifier.weight(1f))
        }
        SwitchRow(stringResource(Res.string.fns_apply_to_x), state.applyX, events::onApplyX, SheetTags.switch("x"))
        SwitchRow(stringResource(Res.string.fns_apply_to_y), state.applyY, events::onApplyY, SheetTags.switch("y"))
        ValueRow(stringResource(Res.string.fns_weight_x), state.xWeight)
        ValueRow(stringResource(Res.string.fns_weight_y), state.yWeight)
        ValueRow(stringResource(Res.string.fns_weight_z), state.zWeight)
        SheetButton(stringResource(Res.string.fns_clear_compensation), events::onClear, SheetTags.CLEAR, enabled = state.active)
    }
}
