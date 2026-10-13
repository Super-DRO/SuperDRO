package org.superdro.core.ui.readout.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.readout.ReadoutPatterns
import org.superdro.core.resources.Res
import org.superdro.core.resources.keypad_presets_title
import org.superdro.core.ui.adaptive.AdaptiveSheet
import org.superdro.core.ui.readout.DroButton
import org.superdro.core.ui.readout.DroButtonStyle
import org.superdro.core.ui.theme.LocalTouchTargets

/** The test tag of the chip of the preset [rpm]. */
fun presetTag(rpm: Double): String = "preset-${ReadoutPatterns.RPM.format(rpm)}"

/**
 * The preset sheet of the tachometer with large chips (`TachometerPresetRoute`). The list
 * always starts with 0. A tap selects the preset and closes the sheet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PresetSheet(presets: List<Double>, onSelect: (Double) -> Unit, onDismiss: () -> Unit) {
    AdaptiveSheet(onDismissRequest = onDismiss, title = stringResource(Res.string.keypad_presets_title)) {
        val targets = LocalTouchTargets.current
        FlowRow(
            modifier = Modifier.padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(targets.gap),
            verticalArrangement = Arrangement.spacedBy(targets.gap),
        ) {
            presets.forEach { rpm ->
                val text = ReadoutPatterns.RPM.format(rpm)
                DroButton(
                    onClick = { onSelect(rpm) },
                    contentDescription = text,
                    style = DroButtonStyle.Primary,
                    minWidth = targets.keypadKey * 1.5f,
                    minHeight = targets.keypadKey,
                    modifier = Modifier.testTag(presetTag(rpm)),
                ) {
                    Text(text, style = MaterialTheme.typography.headlineSmall)
                }
            }
        }
    }
}
