package org.superdro.core.ui.functions.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.model.AxisId
import org.superdro.core.resources.Res
import org.superdro.core.resources.ic_sync
import org.superdro.core.resources.ws_datum_diameter
import org.superdro.core.resources.ws_datum_name
import org.superdro.core.resources.ws_datum_note
import org.superdro.core.resources.ws_datum_save
import org.superdro.core.resources.ws_datum_sync_cd
import org.superdro.core.resources.ws_datum_title_edit
import org.superdro.core.resources.ws_datum_title_new
import org.superdro.core.resources.ws_position_label
import org.superdro.core.ui.functions.common.ErrorBlock
import org.superdro.core.ui.functions.common.FunctionSheet
import org.superdro.core.ui.functions.common.LengthField
import org.superdro.core.ui.functions.common.NumberField
import org.superdro.core.ui.functions.common.fieldTextTag
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.theme.LocalTouchTargets
import org.superdro.core.ui.theme.accentTextFieldColors

/** The test tags of the machine datum edit sheet. */
object MachineDatumEditTags {
    const val NAME = "ws-datum-name"
    const val NOTE = "ws-datum-note"
    fun axis(axis: AxisId): String = "ws-datum-axis-${axis.name}"
    fun sync(axis: AxisId): String = "ws-datum-sync-${axis.name}"
}

/** The events of the machine datum edit sheet. */
interface MachineDatumEditEvents {
    fun onName(text: String)
    fun onNote(text: String)
    fun onText(axis: AxisId, text: String)
    fun onUnit(axis: AxisId, metric: Boolean)
    fun onSync(axis: AxisId)
    fun onSave()
    fun onDismiss()
}

/**
 * The machine datum edit sheet: the name
 * (1 to 15 characters), the note, and the machine position of each axis with its unit control and
 * a sync button. A lathe X in the diameter mode shows and takes a diameter.
 */
@Composable
fun MachineDatumEditSheet(state: MachineDatumEditUiState, events: MachineDatumEditEvents) {
    FunctionSheet(
        title = stringResource(if (state.newDatum) Res.string.ws_datum_title_new else Res.string.ws_datum_title_edit),
        onDismiss = events::onDismiss,
        primaryLabel = stringResource(Res.string.ws_datum_save),
        onPrimary = events::onSave,
        primaryEnabled = state.loaded,
        errors = state.error?.let { ErrorBlock(null, listOf(it)) },
    ) {
        OutlinedTextField(
            value = state.name,
            onValueChange = events::onName,
            label = { Text(stringResource(Res.string.ws_datum_name)) },
            isError = state.nameError != null,
            supportingText = state.nameError?.let { error -> { Text(error.resolve()) } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            colors = accentTextFieldColors(),
            modifier = Modifier.fillMaxWidth().heightIn(min = LocalTouchTargets.current.button).testTag(fieldTextTag(MachineDatumEditTags.NAME)),
        )
        OutlinedTextField(
            value = state.note,
            onValueChange = events::onNote,
            label = { Text(stringResource(Res.string.ws_datum_note)) },
            singleLine = true,
            colors = accentTextFieldColors(),
            modifier = Modifier.fillMaxWidth().heightIn(min = LocalTouchTargets.current.button).testTag(fieldTextTag(MachineDatumEditTags.NOTE)),
        )
        state.axes.forEach { axis ->
            val name = state.labels[axis] ?: axis.name
            val label = if (axis == AxisId.X && state.xDiameter) {
                stringResource(Res.string.ws_datum_diameter, name)
            } else {
                stringResource(Res.string.ws_position_label, name)
            }
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                when (axis) {
                    AxisId.Angular -> NumberField(
                        label = label,
                        state = state.a,
                        onTextChange = { text -> events.onText(axis, text) },
                        error = state.fieldErrors[axis],
                        suffix = "°",
                        tag = MachineDatumEditTags.axis(axis),
                        modifier = Modifier.weight(1f),
                    )
                    else -> LengthField(
                        label = label,
                        state = when (axis) {
                            AxisId.X -> state.x
                            AxisId.Y -> state.y
                            else -> state.z
                        },
                        onTextChange = { text -> events.onText(axis, text) },
                        onUnitChange = { metric -> events.onUnit(axis, metric) },
                        error = state.fieldErrors[axis],
                        tag = MachineDatumEditTags.axis(axis),
                        modifier = Modifier.weight(1f),
                    )
                }
                IconButton(
                    onClick = { events.onSync(axis) },
                    modifier = Modifier.padding(top = 4.dp).heightIn(min = LocalTouchTargets.current.button).testTag(MachineDatumEditTags.sync(axis)),
                ) {
                    Icon(painterResource(Res.drawable.ic_sync), contentDescription = stringResource(Res.string.ws_datum_sync_cd, name))
                }
            }
        }
    }
}
