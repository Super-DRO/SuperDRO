package org.superdro.core.ui.functions.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.functions.transform.FlipAxis
import org.superdro.core.functions.transform.GuidePolicy
import org.superdro.core.model.AxisId
import org.superdro.core.resources.Res
import org.superdro.core.resources.fn_cancel
import org.superdro.core.resources.ic_warning
import org.superdro.core.resources.ws_axis_error
import org.superdro.core.resources.ws_flip
import org.superdro.core.resources.ws_flip_across_x
import org.superdro.core.resources.ws_flip_across_y
import org.superdro.core.resources.ws_flip_line
import org.superdro.core.resources.ws_flip_title
import org.superdro.core.resources.ws_guides_convert
import org.superdro.core.resources.ws_guides_delete
import org.superdro.core.resources.ws_guides_text
import org.superdro.core.resources.ws_guides_title
import org.superdro.core.resources.ws_pivot
import org.superdro.core.resources.ws_position_label
import org.superdro.core.resources.ws_rotate
import org.superdro.core.resources.ws_rotate_by
import org.superdro.core.resources.ws_rotate_title
import org.superdro.core.resources.ws_scale
import org.superdro.core.resources.ws_scale_by
import org.superdro.core.resources.ws_scale_title
import org.superdro.core.resources.ws_scale_z
import org.superdro.core.resources.ws_source_current
import org.superdro.core.resources.ws_source_custom
import org.superdro.core.resources.ws_source_origin
import org.superdro.core.resources.ws_source_sub_datum
import org.superdro.core.resources.ws_sub_datum_select
import org.superdro.core.ui.functions.common.ChoiceRow
import org.superdro.core.ui.functions.common.ErrorBlock
import org.superdro.core.ui.functions.common.FieldGroupTitle
import org.superdro.core.ui.functions.common.FunctionSheet
import org.superdro.core.ui.functions.common.LengthField
import org.superdro.core.ui.functions.common.NumberField
import org.superdro.core.ui.navigation.WorkspaceTransformKind
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.LocalTouchTargets
import org.superdro.core.ui.theme.accentSwitchColors

/** The test tags of the transform sheets. */
object TransformTags {
    const val VALUE = "ws-transform-value"
    const val SCALE_Z = "ws-transform-scale-z"
    const val SOURCE = "ws-transform-source"
    const val FLIP_AXIS = "ws-transform-flip-axis"
    const val SUB_DATUM = "ws-transform-sub-datum"
    const val GUIDES_DELETE = "ws-guides-delete"
    const val GUIDES_CONVERT = "ws-guides-convert"
    fun axis(axis: AxisId): String = "ws-transform-axis-${axis.name}"
    fun subDatum(id: String): String = "ws-transform-sub-datum-$id"
}

/** The events of a transform sheet. */
interface TransformEvents {
    fun onFlipAxis(axis: FlipAxis)
    fun onAngle(text: String)
    fun onPercent(text: String)
    fun onScaleZ(on: Boolean)
    fun onSource(source: PivotSource)
    fun onSubDatum(id: String)
    fun onText(axis: AxisId, text: String)
    fun onUnit(axis: AxisId, metric: Boolean)
    fun onApply()
    fun onGuideAnswer(policy: GuidePolicy?)
    fun onDismiss()
}

/**
 * The transform sheets: the value of the transform, the source of the pivot or the line, and the
 * action button. A rotation that cannot keep guides on X or Y asks first.
 */
@Composable
fun TransformSheet(state: TransformUiState, events: TransformEvents) {
    val (title, action) = when (state.kind) {
        WorkspaceTransformKind.Flip -> Res.string.ws_flip_title to Res.string.ws_flip
        WorkspaceTransformKind.Rotate -> Res.string.ws_rotate_title to Res.string.ws_rotate
        else -> Res.string.ws_scale_title to Res.string.ws_scale
    }
    val lines = buildList {
        state.valueError?.let(::add)
        state.fieldErrors.forEach { (axis, error) -> add(UiText.of(Res.string.ws_axis_error, state.labels[axis] ?: axis.name, error)) }
        state.error?.let(::add)
    }
    FunctionSheet(
        title = stringResource(title),
        onDismiss = events::onDismiss,
        primaryLabel = stringResource(action),
        onPrimary = events::onApply,
        primaryEnabled = state.loaded && !state.busy,
        errors = if (lines.isEmpty()) null else ErrorBlock(null, lines),
    ) {
        when (state.kind) {
            WorkspaceTransformKind.Flip -> ChoiceRow(
                options = listOf(FlipAxis.AcrossX to stringResource(Res.string.ws_flip_across_x), FlipAxis.AcrossY to stringResource(Res.string.ws_flip_across_y)),
                selected = state.flipAxis,
                onSelect = events::onFlipAxis,
                tag = TransformTags.FLIP_AXIS,
            )
            WorkspaceTransformKind.Rotate -> NumberField(
                label = stringResource(Res.string.ws_rotate_by),
                state = state.angle,
                onTextChange = events::onAngle,
                suffix = "°",
                tag = TransformTags.VALUE,
                modifier = Modifier.fillMaxWidth(),
            )
            else -> {
                NumberField(
                    label = stringResource(Res.string.ws_scale_by),
                    state = state.percent,
                    onTextChange = events::onPercent,
                    suffix = "%",
                    tag = TransformTags.VALUE,
                    modifier = Modifier.fillMaxWidth(),
                )
                val scaleZ = stringResource(Res.string.ws_scale_z)
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(scaleZ, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(
                        checked = state.scaleZ,
                        onCheckedChange = events::onScaleZ,
                        modifier = Modifier.testTag(TransformTags.SCALE_Z).semantics { contentDescription = scaleZ },
                        colors = accentSwitchColors(),
                    )
                }
            }
        }
        FieldGroupTitle(stringResource(if (state.kind == WorkspaceTransformKind.Flip) Res.string.ws_flip_line else Res.string.ws_pivot))
        ChoiceRow(
            options = listOf(
                PivotSource.Current to stringResource(Res.string.ws_source_current),
                PivotSource.Origin to stringResource(Res.string.ws_source_origin),
                PivotSource.SubDatum to stringResource(Res.string.ws_source_sub_datum),
                PivotSource.Custom to stringResource(Res.string.ws_source_custom),
            ),
            selected = state.source,
            onSelect = events::onSource,
            tag = TransformTags.SOURCE,
        )
        when (state.source) {
            PivotSource.SubDatum -> SubDatumPicker(state, events)
            PivotSource.Custom -> state.axes.forEach { axis ->
                val field = state.fields[axis] ?: return@forEach
                LengthField(
                    label = stringResource(Res.string.ws_position_label, state.labels[axis] ?: axis.name),
                    state = field,
                    onTextChange = { text -> events.onText(axis, text) },
                    onUnitChange = { metric -> events.onUnit(axis, metric) },
                    tag = TransformTags.axis(axis),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            else -> Unit
        }
    }
    state.guideQuestion?.let { count -> GuideQuestion(count, events::onGuideAnswer) }
}

@Composable
private fun SubDatumPicker(state: TransformUiState, events: TransformEvents) {
    var open by remember { mutableStateOf(false) }
    val selected = state.subDatums.firstOrNull { it.id == state.subDatumId }
    Box {
        OutlinedButton(
            onClick = { open = true },
            colors = AccentButtons.outlined(),
            modifier = Modifier.fillMaxWidth().heightIn(min = LocalTouchTargets.current.button).testTag(TransformTags.SUB_DATUM),
        ) {
            Text(selected?.let { choice -> choice.name + "   " + choice.coordinates.map { it.resolve() }.joinToString(" · ") }
                ?: stringResource(Res.string.ws_sub_datum_select))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.subDatums.forEach { choice ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(choice.name, style = MaterialTheme.typography.bodyLarge)
                            Text(choice.coordinates.map { it.resolve() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    onClick = {
                        open = false
                        events.onSubDatum(choice.id)
                    },
                    modifier = Modifier.testTag(TransformTags.subDatum(choice.id)),
                )
            }
        }
    }
}

/** "Guides Will Be Deleted": cancel, convert the guides to points, or delete them. */
@Composable
internal fun GuideQuestion(count: Int, onAnswer: (GuidePolicy?) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAnswer(null) },
        icon = { Icon(painterResource(Res.drawable.ic_warning), contentDescription = null) },
        title = { Text(stringResource(Res.string.ws_guides_title)) },
        text = { Text(stringResource(Res.string.ws_guides_text, count)) },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onAnswer(GuidePolicy.Convert) }, colors = AccentButtons.text(), modifier = Modifier.testTag(TransformTags.GUIDES_CONVERT)) {
                    Text(stringResource(Res.string.ws_guides_convert))
                }
                TextButton(onClick = { onAnswer(GuidePolicy.Delete) }, colors = AccentButtons.text(), modifier = Modifier.testTag(TransformTags.GUIDES_DELETE)) {
                    Text(stringResource(Res.string.ws_guides_delete))
                }
            }
        },
        dismissButton = { TextButton(onClick = { onAnswer(null) }, colors = AccentButtons.text()) { Text(stringResource(Res.string.fn_cancel)) } },
    )
}
