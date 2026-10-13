package org.superdro.core.ui.adapter.inputs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.model.ProbeContactType
import org.superdro.core.resources.Res
import org.superdro.core.resources.adapter_no_profile
import org.superdro.core.resources.adapter_probe_auto
import org.superdro.core.resources.adapter_probe_auto_help
import org.superdro.core.resources.adapter_probe_contact
import org.superdro.core.resources.adapter_probe_debounce
import org.superdro.core.resources.adapter_probe_help
import org.superdro.core.resources.adapter_probe_nc
import org.superdro.core.resources.adapter_probe_nc_help
import org.superdro.core.resources.adapter_probe_no
import org.superdro.core.resources.adapter_probe_no_help
import org.superdro.core.resources.adapter_tach_pulses
import org.superdro.core.resources.adapter_tach_pulses_help
import org.superdro.core.resources.adapter_tach_set
import org.superdro.core.resources.adapter_unit_ms
import org.superdro.core.resources.frame_cancel
import org.superdro.core.resources.frame_save
import org.superdro.core.resources.route_probe_input
import org.superdro.core.resources.route_tach_pulses
import org.superdro.core.ui.adapter.common.NumberField
import org.superdro.core.ui.adapter.common.SectionTitle
import org.superdro.core.ui.adapter.common.StatusKind
import org.superdro.core.ui.adapter.common.StatusLine
import org.superdro.core.ui.adaptive.AdaptiveSheet
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.LocalTouchTargets
import org.superdro.core.ui.theme.accentRadioColors

/** The test tags of the input settings sheets. */
object InputSheetTags {
    const val PULSES: String = "tach-pulses"
    const val SET_PULSES: String = "tach-set"
    const val DEBOUNCE: String = "probe-debounce"
    const val SAVE_PROBE: String = "probe-save"
    fun contact(type: ProbeContactType): String = "probe-contact-${type.name}"
}

/** The tach pulses sheet: "Pulses per revolution" and "Set pulses". */
@Composable
fun TachPulsesSheet(state: TachPulsesUiState, onText: (String) -> Unit, onSet: () -> Unit, onDismiss: () -> Unit) {
    AdaptiveSheet(onDismissRequest = onDismiss, title = stringResource(Res.string.route_tach_pulses)) {
        if (state.loaded && !state.hasProfile) StatusLine(StatusKind.Info, stringResource(Res.string.adapter_no_profile))
        NumberField(
            label = stringResource(Res.string.adapter_tach_pulses),
            value = state.text,
            onValueChange = onText,
            error = state.error,
            supporting = stringResource(Res.string.adapter_tach_pulses_help),
            enabled = state.hasProfile && !state.busy,
            tag = InputSheetTags.PULSES,
        )
        SheetButtons(
            confirm = stringResource(Res.string.adapter_tach_set),
            confirmTag = InputSheetTags.SET_PULSES,
            enabled = state.hasProfile && !state.busy,
            onConfirm = onSet,
            onDismiss = onDismiss,
        )
    }
}

/** The probe input sheet: the contact type and the debounce time of the adapter. */
@Composable
fun ProbeInputSheet(
    state: ProbeInputUiState,
    onContactType: (ProbeContactType) -> Unit,
    onDebounce: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AdaptiveSheet(onDismissRequest = onDismiss, title = stringResource(Res.string.route_probe_input)) {
        if (state.loaded && !state.hasProfile) StatusLine(StatusKind.Info, stringResource(Res.string.adapter_no_profile))
        Text(stringResource(Res.string.adapter_probe_help), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SectionTitle(stringResource(Res.string.adapter_probe_contact))
        ProbeContactType.entries.forEach { type ->
            val (label, help) = when (type) {
                ProbeContactType.Auto -> Res.string.adapter_probe_auto to Res.string.adapter_probe_auto_help
                ProbeContactType.NormallyClosed -> Res.string.adapter_probe_nc to Res.string.adapter_probe_nc_help
                ProbeContactType.NormallyOpen -> Res.string.adapter_probe_no to Res.string.adapter_probe_no_help
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .selectable(selected = state.contactType == type, enabled = state.hasProfile, role = Role.RadioButton, onClick = { onContactType(type) })
                    .testTag(InputSheetTags.contact(type)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = state.contactType == type, onClick = null, enabled = state.hasProfile, colors = accentRadioColors())
                Spacer(Modifier.size(12.dp))
                Column {
                    Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(help), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.size(8.dp))
        NumberField(
            label = stringResource(Res.string.adapter_probe_debounce),
            value = state.debounceText,
            onValueChange = onDebounce,
            error = state.error,
            suffix = stringResource(Res.string.adapter_unit_ms),
            decimal = false,
            enabled = state.hasProfile && !state.busy,
            tag = InputSheetTags.DEBOUNCE,
        )
        SheetButtons(
            confirm = stringResource(Res.string.frame_save),
            confirmTag = InputSheetTags.SAVE_PROBE,
            enabled = state.hasProfile && !state.busy,
            onConfirm = onSave,
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun SheetButtons(confirm: String, confirmTag: String, enabled: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Spacer(Modifier.size(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
        TextButton(onClick = onDismiss, colors = AccentButtons.text(), modifier = Modifier.heightIn(min = LocalTouchTargets.current.button)) {
            Text(stringResource(Res.string.frame_cancel))
        }
        Button(onClick = onConfirm, enabled = enabled, modifier = Modifier.heightIn(min = LocalTouchTargets.current.button).testTag(confirmTag), colors = AccentButtons.filled()) {
            Text(confirm)
        }
    }
}
