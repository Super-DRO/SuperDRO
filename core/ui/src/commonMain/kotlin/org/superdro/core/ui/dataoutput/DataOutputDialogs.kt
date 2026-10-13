package org.superdro.core.ui.dataoutput

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.resources.Res
import org.superdro.core.resources.data_output_broker
import org.superdro.core.resources.data_output_clients
import org.superdro.core.resources.data_output_diagnostics_title
import org.superdro.core.resources.data_output_listening
import org.superdro.core.resources.data_output_not_running
import org.superdro.core.resources.data_output_psk_caution
import org.superdro.core.resources.data_output_psk_new
import org.superdro.core.resources.data_output_psk_new_warning
import org.superdro.core.resources.data_output_warning_cancel
import org.superdro.core.resources.data_output_warning_confirm
import org.superdro.core.resources.data_output_warning_mqtt_no_tls
import org.superdro.core.resources.data_output_warning_plain
import org.superdro.core.resources.data_output_warning_trusted
import org.superdro.core.resources.dataoutput_psk_new_title
import org.superdro.core.resources.dataoutput_status_title
import org.superdro.core.resources.ic_dataoutput_stream
import org.superdro.core.resources.ic_warning
import org.superdro.core.ui.components.InfoPanel
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.theme.AccentButtons

/** The test tag of the warning dialog "Use only on trusted networks". */
const val DATA_OUTPUT_WARNING_TAG: String = "data-output-warning"

/** The test tag of the confirm button of the warning dialog. */
const val DATA_OUTPUT_WARNING_CONFIRM_TAG: String = "data-output-warning-confirm"

/** The test tag of the diagnostics in the status section of the screen (not in a dialog). */
const val DATA_OUTPUT_DIAGNOSTICS_TAG: String = "data-output-diagnostics"

/** The test tag of the button that opens and closes the diagnostics. */
const val DATA_OUTPUT_DETAILS_TAG: String = "data-output-details"

/**
 * The dialogs of the data output screen: the two warnings "Use only on trusted networks" and the
 * question before "Make a new key". The diagnostics show in the status section of the screen
 * ([DiagnosticsSection]; the dialog rule).
 */
@Composable
internal fun DataOutputDialogs(state: DataOutputUiState, events: DataOutputEvents) {
    when (state.dialog) {
        DataOutputDialog.PlainTextWarning -> TrustedNetworkWarning(
            text = stringResource(Res.string.data_output_warning_plain),
            onConfirm = events::onConfirmWarning,
            onDismiss = events::onDismissDialog,
        )
        DataOutputDialog.MqttNoTlsWarning -> TrustedNetworkWarning(
            text = stringResource(Res.string.data_output_warning_mqtt_no_tls),
            onConfirm = events::onConfirmWarning,
            onDismiss = events::onDismissDialog,
        )
        DataOutputDialog.NewKey -> AlertDialog(
            onDismissRequest = events::onDismissDialog,
            title = { Text(stringResource(Res.string.dataoutput_psk_new_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(Res.string.data_output_psk_new_warning))
                    Text(stringResource(Res.string.data_output_psk_caution), color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(colors = AccentButtons.text(), onClick = events::onConfirmNewKey) { Text(stringResource(Res.string.data_output_psk_new)) }
            },
            dismissButton = {
                TextButton(colors = AccentButtons.text(), onClick = events::onDismissDialog) { Text(stringResource(Res.string.data_output_warning_cancel)) }
            },
        )
        DataOutputDialog.Diagnostics, null -> Unit
    }
}

/**
 * WARNING: the plain text mode and a broker link with no TLS have no encryption. The dialog has the
 * title "Use only on trusted networks".
 * Only "Use this mode" selects the mode.
 */
@Composable
private fun TrustedNetworkWarning(text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(DATA_OUTPUT_WARNING_TAG),
        icon = { Icon(painterResource(Res.drawable.ic_warning), contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(Res.string.data_output_warning_trusted)) },
        text = { Text(text) },
        confirmButton = {
            TextButton(colors = AccentButtons.text(), onClick = onConfirm, modifier = Modifier.testTag(DATA_OUTPUT_WARNING_CONFIRM_TAG)) {
                Text(stringResource(Res.string.data_output_warning_confirm))
            }
        },
        dismissButton = {
            TextButton(colors = AccentButtons.text(), onClick = onDismiss) { Text(stringResource(Res.string.data_output_warning_cancel)) }
        },
    )
}

/**
 * The diagnostics in the status section of the screen (the dialog rule): the title "Data output
 * problem" with the warning icon, or "Data output"; the addresses and the clients (or the
 * broker), the last problem and its age, the age of the last delivery, and the counts. The button
 * that opened the section closes it, so the section has no close button. The section has no stop
 * button: the switch and the start and stop button of the same status section stop the output.
 */
@Composable
fun DiagnosticsSection(lines: DiagnosticsUiState, modifier: Modifier = Modifier) {
    InfoPanel(modifier = modifier, tag = DATA_OUTPUT_DIAGNOSTICS_TAG) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(if (lines.warning) Res.drawable.ic_warning else Res.drawable.ic_dataoutput_stream),
                contentDescription = null,
                tint = if (lines.warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(if (lines.warning) Res.string.data_output_diagnostics_title else Res.string.dataoutput_status_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
        }
        val first = when {
            lines.listening != null ->
                stringResource(Res.string.data_output_listening, lines.listening) + " (" +
                    pluralStringResource(Res.plurals.data_output_clients, lines.clients, lines.clients) + ")"
            lines.broker != null -> stringResource(Res.string.data_output_broker, lines.broker)
            else -> stringResource(Res.string.data_output_not_running)
        }
        Text(first, style = MaterialTheme.typography.bodyLarge)
        lines.problem?.let { problem -> Text(problem.resolve(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error) }
        Text(lines.delivery.resolve(), style = MaterialTheme.typography.bodyLarge)
        lines.counts?.let { counts -> Text(counts.resolve(), style = MaterialTheme.typography.bodyLarge) }
    }
}
