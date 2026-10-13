package org.superdro.core.ui.functions.patterns

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.functions.arc.AngleReference
import org.superdro.core.functions.arc.CutType
import org.superdro.core.functions.patterns.AxisLetter
import org.superdro.core.functions.patterns.PositionReference
import org.superdro.core.functions.patterns.PositionSource
import org.superdro.core.model.Plane
import org.superdro.core.resources.Res
import org.superdro.core.resources.fn_create
import org.superdro.core.resources.fnp_add_angle
import org.superdro.core.resources.fnp_angle_center
import org.superdro.core.resources.fnp_angle_item
import org.superdro.core.resources.fnp_angle_reference
import org.superdro.core.resources.fnp_angle_tangent
import org.superdro.core.resources.fnp_angles_empty
import org.superdro.core.resources.fnp_angles_title
import org.superdro.core.resources.fnp_axis_diameter
import org.superdro.core.resources.fnp_axis_switch_cd
import org.superdro.core.resources.fnp_column_name
import org.superdro.core.resources.fnp_cut_external
import org.superdro.core.resources.fnp_cut_internal
import org.superdro.core.resources.fnp_cut_type
import org.superdro.core.resources.fnp_degree
import org.superdro.core.resources.fnp_end_title
import org.superdro.core.resources.fnp_plane
import org.superdro.core.resources.fnp_points_more
import org.superdro.core.resources.fnp_points_title
import org.superdro.core.resources.fnp_prefix_default
import org.superdro.core.resources.fnp_reference
import org.superdro.core.resources.fnp_remove_angle_cd
import org.superdro.core.resources.fnp_source
import org.superdro.core.resources.fnp_source_current
import org.superdro.core.resources.fnp_source_custom
import org.superdro.core.resources.fnp_source_zero
import org.superdro.core.resources.fnp_start_title
import org.superdro.core.resources.fnp_step_sources
import org.superdro.core.resources.fnp_sub_datum
import org.superdro.core.resources.fnp_sub_datum_select
import org.superdro.core.resources.ic_close
import org.superdro.core.resources.ic_warning
import org.superdro.core.resources.pattern_label_prefix
import org.superdro.core.ui.functions.common.BlockedDialog
import org.superdro.core.ui.functions.common.ChoiceRow
import org.superdro.core.ui.functions.common.FieldGroupTitle
import org.superdro.core.ui.functions.common.FieldMath
import org.superdro.core.ui.functions.common.FunctionScreen
import org.superdro.core.ui.functions.common.LengthField
import org.superdro.core.ui.functions.common.NumberField
import org.superdro.core.ui.functions.common.PointPreview
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.LocalTouchTargets
import org.superdro.core.ui.theme.accentColor
import org.superdro.core.ui.theme.accentTextFieldColors
import org.superdro.core.ui.theme.accentSwitchColors

/** The test tag of the prefix field. */
const val PATTERN_PREFIX_TAG: String = "pattern-prefix"

/** The test tag of the "Add Angle" button of the arbitrary hole circle. */
const val PATTERN_ADD_ANGLE_TAG: String = "pattern-add-angle"

/** The test tag of the point list. */
const val PATTERN_POINTS_TAG: String = "pattern-points"

/** The tag prefix of the choice rows. The buttons have the tags `<tag>-<index>`. */
const val PATTERN_PLANE_TAG: String = "pattern-plane"
const val PATTERN_SOURCE_TAG: String = "pattern-source"
const val PATTERN_REFERENCE_TAG: String = "pattern-reference"
const val PATTERN_CUT_TYPE_TAG: String = "pattern-cut-type"
const val PATTERN_ANGLE_REFERENCE_TAG: String = "pattern-angle-reference"

/** The tag of the field [input] (the text field has the tag of `fieldTextTag`). */
fun patternFieldTag(input: PatternInput): String = "pattern-${input.name}"

/** The tag of the axis switch of [axis]. */
fun patternSwitchTag(axis: AxisLetter): String = "pattern-switch-${axis.name}"

/** The tag of the remove button of the angle [index]. */
fun patternRemoveAngleTag(index: Int): String = "pattern-angle-remove-$index"

/** The events of a pattern screen. [PatternPresenter] has a function for each event. */
interface PatternEvents {
    fun onPrefixChange(text: String)
    fun onPlaneChange(plane: Plane)
    fun onSourceChange(source: PositionSource)
    fun onAxisSwitch(axis: AxisLetter, on: Boolean)
    fun onReferenceChange(reference: PositionReference)
    fun onSubDatumSelected(id: String?)
    fun onText(input: PatternInput, text: String)
    fun onUnit(input: PatternInput, metric: Boolean)
    fun onAddAngle()
    fun onRemoveAngle(index: Int)
    fun onCutType(cutType: CutType)
    fun onAngleReference(reference: AngleReference)
    fun onCreate()
    fun onBlockedDismissed()
    fun onBack()
}

/** The [PatternEvents] of a presenter. [onClose] closes the screen. */
class PresenterPatternEvents(private val presenter: PatternPresenter, private val onClose: () -> Unit) : PatternEvents {
    override fun onPrefixChange(text: String) = presenter.onPrefixChange(text)
    override fun onPlaneChange(plane: Plane) = presenter.onPlaneChange(plane)
    override fun onSourceChange(source: PositionSource) = presenter.onSourceChange(source)
    override fun onAxisSwitch(axis: AxisLetter, on: Boolean) = presenter.onAxisSwitch(axis, on)
    override fun onReferenceChange(reference: PositionReference) = presenter.onReferenceChange(reference)
    override fun onSubDatumSelected(id: String?) = presenter.onSubDatumSelected(id)
    override fun onText(input: PatternInput, text: String) = presenter.onText(input, text)
    override fun onUnit(input: PatternInput, metric: Boolean) = presenter.onUnit(input, metric)
    override fun onAddAngle() = presenter.onAddAngle()
    override fun onRemoveAngle(index: Int) = presenter.onRemoveAngle(index)
    override fun onCutType(cutType: CutType) = presenter.onCutType(cutType)
    override fun onAngleReference(reference: AngleReference) = presenter.onAngleReference(reference)
    override fun onCreate() = presenter.onCreate()
    override fun onBlockedDismissed() = presenter.onBlockedDismissed()
    override fun onBack() = onClose()
}

/**
 * A function screen with a point preview: the inputs, the
 * preview of the points, the point list, the error box, and "Create". The composable is
 * stateless: [state] and [events].
 */
@Composable
fun PatternScreen(state: PatternUiState, events: PatternEvents, modifier: Modifier = Modifier) {
    FunctionScreen(
        title = state.title.resolve(),
        onBack = events::onBack,
        primaryLabel = stringResource(Res.string.fn_create),
        onPrimary = events::onCreate,
        modifier = modifier,
        primaryEnabled = state.loaded,
        errors = state.errors,
        preview = {
            PointPreview(points = state.preview, hAxis = state.hAxis, vAxis = state.vAxis, origin = state.previewOrigin)
        },
    ) {
        if (state.kind.hasPrefix) PrefixField(state, events)
        if (state.kind.hasPosition) {
            FieldGroupTitle(stringResource(Res.string.fnp_plane))
            ChoiceRow(
                options = state.planes.map { plane -> plane to plane.name },
                selected = state.plane,
                onSelect = events::onPlaneChange,
                enabled = state.planeEnabled,
                tag = PATTERN_PLANE_TAG,
            )
        }
        FunctionFields(state, events)
        if (state.kind.hasPosition) PositionBlock(state, events)
        state.referenceLabels?.let { (center, first) ->
            FieldGroupTitle(stringResource(Res.string.fnp_reference))
            ChoiceRow(
                options = listOf(PositionReference.Center to center.resolve(), PositionReference.FirstPoint to first.resolve()),
                selected = state.reference,
                onSelect = events::onReferenceChange,
                tag = PATTERN_REFERENCE_TAG,
            )
        }
        state.sourceCount?.let { count ->
            Text(stringResource(Res.string.fnp_step_sources, count), style = MaterialTheme.typography.bodyMedium)
        }
        PointList(state)
    }
    val blocked = state.blocked
    if (blocked != null) {
        BlockedDialog(blocked) {
            events.onBlockedDismissed()
            if (state.closeAfterBlocked) events.onBack()
        }
    }
}

@Composable
private fun PrefixField(state: PatternUiState, events: PatternEvents) {
    OutlinedTextField(
        value = state.prefix,
        onValueChange = events::onPrefixChange,
        label = { Text(stringResource(Res.string.pattern_label_prefix)) },
        placeholder = { Text(state.prefixDefault) },
        supportingText = { Text(stringResource(Res.string.fnp_prefix_default, state.prefixDefault)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        colors = accentTextFieldColors(),
        modifier = Modifier.fillMaxWidth().heightIn(min = LocalTouchTargets.current.button).testTag(PATTERN_PREFIX_TAG),
    )
}

/** The fields of the function: the radius, the counts, the angles, the steps, or the ranges. */
@Composable
private fun FunctionFields(state: PatternUiState, events: PatternEvents) {
    val degree = stringResource(Res.string.fnp_degree)
    @Composable
    fun Input(field: InputUiState, modifier: Modifier = Modifier.fillMaxWidth()) {
        val label = field.label.resolve()
        val tag = patternFieldTag(field.input)
        val length = field.length
        if (length != null) {
            LengthField(
                label = label,
                state = length,
                onTextChange = { text -> events.onText(field.input, text) },
                onUnitChange = { metric -> events.onUnit(field.input, metric) },
                modifier = modifier,
                tag = tag,
            )
        } else {
            val angle = field.input in ANGLE_INPUTS
            NumberField(
                label = label,
                state = field.number ?: org.superdro.core.ui.functions.common.NumberFieldState(),
                onTextChange = { text -> events.onText(field.input, text) },
                modifier = modifier,
                suffix = if (angle) degree else null,
                tag = tag,
            )
        }
    }
    when (state.kind) {
        PatternKind.EquidistantArray -> {
            state.field(PatternInput.Count)?.let { field -> Input(field) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FieldGroupTitle(stringResource(Res.string.fnp_start_title), Modifier.weight(1f))
                FieldGroupTitle(stringResource(Res.string.fnp_end_title), Modifier.weight(1f))
            }
            AxisLetter.entries.forEach { axis ->
                val start = state.field(PatternInput.start(axis))
                val end = state.field(PatternInput.end(axis))
                if (start != null && end != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Input(start, Modifier.weight(1f))
                        Input(end, Modifier.weight(1f))
                    }
                }
            }
        }
        PatternKind.ArbitraryHoleCircle -> {
            state.field(PatternInput.Radius)?.let { field -> Input(field) }
            AngleList(state, events) { field -> Input(field, Modifier.weight(1f)) }
        }
        else -> state.fields.forEach { field -> Input(field) }
    }
    if (state.kind == PatternKind.ArcCut) {
        FieldGroupTitle(stringResource(Res.string.fnp_cut_type))
        ChoiceRow(
            options = listOf(CutType.Internal to stringResource(Res.string.fnp_cut_internal), CutType.External to stringResource(Res.string.fnp_cut_external)),
            selected = state.cutType,
            onSelect = events::onCutType,
            tag = PATTERN_CUT_TYPE_TAG,
        )
        FieldGroupTitle(stringResource(Res.string.fnp_angle_reference))
        ChoiceRow(
            options = listOf(
                AngleReference.Center to stringResource(Res.string.fnp_angle_center),
                AngleReference.Tangent to stringResource(Res.string.fnp_angle_tangent),
            ),
            selected = state.angleReference,
            onSelect = events::onAngleReference,
            tag = PATTERN_ANGLE_REFERENCE_TAG,
        )
    }
}

/** The angle entry and the angle list of the arbitrary hole circle. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AngleList(state: PatternUiState, events: PatternEvents, entry: @Composable androidx.compose.foundation.layout.RowScope.(InputUiState) -> Unit) {
    val targets = LocalTouchTargets.current
    state.field(PatternInput.AngleEntry)?.let { field ->
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            entry(field)
            FilledTonalButton(
                onClick = events::onAddAngle,
                modifier = Modifier.heightIn(min = targets.button).testTag(PATTERN_ADD_ANGLE_TAG),
            ) { Text(stringResource(Res.string.fnp_add_angle)) }
        }
    }
    state.angleError?.let { error ->
        Text(error.resolve(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    FieldGroupTitle(stringResource(Res.string.fnp_angles_title))
    if (state.angles.isEmpty()) {
        Text(stringResource(Res.string.fnp_angles_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.angles.forEachIndexed { index, degrees ->
                val text = FieldMath.format(degrees, 4)
                val description = stringResource(Res.string.fnp_remove_angle_cd, text)
                InputChip(
                    selected = index == 0,
                    onClick = { events.onRemoveAngle(index) },
                    label = { Text(stringResource(Res.string.fnp_angle_item, index + 1, text)) },
                    trailingIcon = { Icon(painterResource(Res.drawable.ic_close), contentDescription = null, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = description }.testTag(patternRemoveAngleTag(index)),
                )
            }
        }
    }
}

/** The position block: the source, the sub-datum list, and the axis rows. */
@Composable
private fun PositionBlock(state: PatternUiState, events: PatternEvents) {
    state.positionTitle?.let { title ->
        HorizontalDivider()
        Text(title.resolve(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
    }
    FieldGroupTitle(stringResource(Res.string.fnp_source))
    ChoiceRow(
        options = listOf(
            PositionSource.Current to stringResource(Res.string.fnp_source_current),
            PositionSource.Zero to stringResource(Res.string.fnp_source_zero),
            PositionSource.Custom to stringResource(Res.string.fnp_source_custom),
        ),
        selected = state.source,
        onSelect = events::onSourceChange,
        tag = PATTERN_SOURCE_TAG,
    )
    state.sourceError?.let { error ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(Res.drawable.ic_warning), contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
            Text(error.resolve(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (state.subDatums.isNotEmpty()) SubDatumMenu(state, events)
    state.positionRows.forEach { row -> PositionRow(row, events) }
}

@Composable
private fun SubDatumMenu(state: PatternUiState, events: PatternEvents) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.subDatums.firstOrNull { choice -> choice.id == state.subDatumId }?.name ?: stringResource(Res.string.fnp_sub_datum_select)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(Res.string.fnp_sub_datum), style = MaterialTheme.typography.bodyLarge)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                colors = AccentButtons.outlined(),
                modifier = Modifier.heightIn(min = LocalTouchTargets.current.button).testTag("pattern-sub-datum"),
            ) { Text(selected, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                state.subDatums.forEach { choice ->
                    DropdownMenuItem(
                        text = { Text(choice.name) },
                        onClick = {
                            expanded = false
                            events.onSubDatumSelected(choice.id)
                        },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("pattern-sub-datum-${choice.id}"),
                    )
                }
            }
        }
    }
}

@Composable
private fun PositionRow(row: PositionRowUiState, events: PatternEvents) {
    val label = if (row.diameter) stringResource(Res.string.fnp_axis_diameter, row.label) else row.label
    val description = stringResource(Res.string.fnp_axis_switch_cd, row.label)
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Switch(
            checked = row.on,
            onCheckedChange = { on -> events.onAxisSwitch(row.axis, on) },
            enabled = row.machineEnabled,
            colors = accentSwitchColors(),
            modifier = Modifier.padding(top = 8.dp).semantics { contentDescription = description }.testTag(patternSwitchTag(row.axis)),
        )
        LengthField(
            label = label,
            state = row.field,
            onTextChange = { text -> events.onText(PatternInput.custom(row.axis), text) },
            onUnitChange = { metric -> events.onUnit(PatternInput.custom(row.axis), metric) },
            enabled = row.editable && row.on,
            modifier = Modifier.weight(1f),
            tag = patternFieldTag(PatternInput.custom(row.axis)),
        )
    }
}

/** The names and the coordinates of the points that "Create" makes. */
@Composable
private fun PointList(state: PatternUiState) {
    if (state.pointRows.isEmpty()) return
    HorizontalDivider()
    FieldGroupTitle(stringResource(Res.string.fnp_points_title))
    Column(Modifier.fillMaxWidth().testTag(PATTERN_POINTS_TAG), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
        val header = MaterialTheme.typography.labelMedium
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.fnp_column_name), style = header, modifier = Modifier.weight(1.4f))
            listOf("X", "Y", "Z").forEach { axis -> Text(axis, style = header, modifier = Modifier.weight(1f)) }
        }
        state.pointRows.forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                Text(row.name, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1.4f))
                Text(row.x, style = style, modifier = Modifier.weight(1f))
                Text(row.y, style = style, modifier = Modifier.weight(1f))
                Text(row.z, style = style, modifier = Modifier.weight(1f))
            }
        }
        val more = state.pointCount - state.pointRows.size
        if (more > 0) {
            Spacer(Modifier.width(4.dp))
            Text(stringResource(Res.string.fnp_points_more, more), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** The fields in degrees. */
private val ANGLE_INPUTS = setOf(PatternInput.StartAngle, PatternInput.SweepAngle, PatternInput.AngleEntry, PatternInput.GridAngle)
