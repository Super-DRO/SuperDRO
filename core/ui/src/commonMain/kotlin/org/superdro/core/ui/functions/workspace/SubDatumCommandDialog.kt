package org.superdro.core.ui.functions.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.resources.Res
import org.superdro.core.resources.fn_cancel
import org.superdro.core.resources.fn_close
import org.superdro.core.resources.ic_my_location
import org.superdro.core.resources.ws_align_origin
import org.superdro.core.resources.ws_align_origin_text
import org.superdro.core.resources.ws_align_spindle
import org.superdro.core.resources.ws_align_spindle_text
import org.superdro.core.resources.ws_align_spindle_text_lathe
import org.superdro.core.resources.ws_align_tool
import org.superdro.core.resources.ws_pair_yes
import org.superdro.core.ui.navigation.SubDatumCommand
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.theme.AccentButtons

/** The test tag of the confirm button of the origin command dialog. */
const val SUB_DATUM_COMMAND_CONFIRM_TAG: String = "ws-sub-datum-command-confirm"

/**
 * The confirmation of an origin command of a sub-datum: the question, and the
 * shift of each axis for "Align Sub-Datum to Spindle". A refusal shows its text.
 */
@Composable
fun SubDatumCommandDialog(state: SubDatumCommandUiState, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val title = when (state.command) {
        SubDatumCommand.AlignToSpindle -> if (state.lathe) Res.string.ws_align_tool else Res.string.ws_align_spindle
        SubDatumCommand.AlignOriginToSubDatum -> Res.string.ws_align_origin
    }
    val error = state.error
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(Res.drawable.ic_my_location), contentDescription = null) },
        title = { Text(stringResource(title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (error != null) {
                    Text(error.resolve())
                } else {
                    val question = when (state.command) {
                        SubDatumCommand.AlignToSpindle ->
                            if (state.lathe) Res.string.ws_align_spindle_text_lathe else Res.string.ws_align_spindle_text
                        SubDatumCommand.AlignOriginToSubDatum -> Res.string.ws_align_origin_text
                    }
                    Text(stringResource(question, state.name))
                    state.shifts.forEach { line -> Text(line.resolve(), style = MaterialTheme.typography.bodyLarge) }
                }
            }
        },
        confirmButton = {
            if (error == null) {
                TextButton(
                    onClick = onConfirm,
                    enabled = state.loaded && !state.busy,
                    colors = AccentButtons.text(),
                    modifier = Modifier.testTag(SUB_DATUM_COMMAND_CONFIRM_TAG),
                ) { Text(stringResource(Res.string.ws_pair_yes)) }
            } else {
                TextButton(onClick = onDismiss, colors = AccentButtons.text()) { Text(stringResource(Res.string.fn_close)) }
            }
        },
        dismissButton = if (error == null) {
            { TextButton(onClick = onDismiss, colors = AccentButtons.text()) { Text(stringResource(Res.string.fn_cancel)) } }
        } else {
            null
        },
    )
}
