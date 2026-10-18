package org.superdro.core.ui.readout

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.readout.operations.GlobalModeState
import org.superdro.core.readout.operations.GlobalUnitsState
import org.superdro.core.resources.Res
import org.superdro.core.resources.ic_info
import org.superdro.core.resources.ic_warning
import org.superdro.core.resources.readout_setup_calibrate
import org.superdro.core.resources.readout_setup_settings
import org.superdro.core.resources.readout_setup_text
import org.superdro.core.resources.readout_setup_title
import org.superdro.core.resources.readout_status_datum
import org.superdro.core.resources.readout_status_measurement
import org.superdro.core.resources.readout_status_no_tool
import org.superdro.core.resources.readout_status_rpm
import org.superdro.core.resources.readout_status_tool
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.theme.LocalTouchTargets

/** The test tag of the status strip. */
const val STATUS_STRIP_TAG: String = "readout-status-strip"

/** The test tag of the hint and message line. */
const val MESSAGE_LINE_TAG: String = "readout-message-line"

/** The RPM text of a readout with no tachometer value. */
val NoRpmText: StateFlow<String?> = MutableStateFlow(null)

/**
 * The status strip: the units, ABS or INC, the active tool, the active sub-datum, the RPM,
 * and the "Measurement" mode chip. Each status has an icon and a text.
 *
 * @param rpmText The RPM text of the tachometer, or null when the tachometer is off. Only the RPM
 *   chip reads it, so a new RPM value does not recompose the other chips.
 * @param trailing The items of the other areas after the frame items, for example the status
 *   chip of the data output (`EntryRegistry.statusStripItem`).
 */
@Composable
fun StatusStrip(
    state: StatusStripUiState,
    modifier: Modifier = Modifier,
    rpmText: StateFlow<String?> = NoRpmText,
    trailing: @Composable () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier.testTag(STATUS_STRIP_TAG).fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val mixedUnits = state.units == GlobalUnitsState.Mixed
        StatusChip(stringResource(ReadoutTexts.unitsText(state.units)), ReadoutTexts.mixedIcon(mixedUnits))
        StatusChip(stringResource(ReadoutTexts.modeText(state.mode)), ReadoutTexts.mixedIcon(state.mode == GlobalModeState.Mixed))
        StatusChip(state.toolName?.let { name -> stringResource(Res.string.readout_status_tool, name) } ?: stringResource(Res.string.readout_status_no_tool))
        state.subDatumName?.let { name -> StatusChip(stringResource(Res.string.readout_status_datum, name)) }
        RpmChip(rpmText)
        if (state.measurementMode) {
            StatusChip(stringResource(Res.string.readout_status_measurement), Res.drawable.ic_info, colors.secondaryContainer, colors.onSecondaryContainer)
        }
        trailing()
    }
}

/** The RPM chip: the only reader of the RPM text. No chip shows when the text is null. */
@Composable
private fun RpmChip(rpmText: StateFlow<String?>) {
    val rpm by rpmText.collectAsState()
    rpm?.let { text -> StatusChip(stringResource(Res.string.readout_status_rpm, text)) }
}

@Composable
private fun StatusChip(
    text: String,
    icon: DrawableResource? = null,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: Color = MaterialTheme.colorScheme.onSurface,
) {
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/**
 * The setup reminder banner: "Calibration required" with
 * the actions "Settings" and "Calibrate". The app setting "Show the setup reminder" turns it off.
 */
@Composable
fun SetupReminderBanner(onSettings: () -> Unit, onCalibrate: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier.fillMaxWidth().testTag("readout-setup-reminder"), color = colors.tertiaryContainer, contentColor = colors.onTertiaryContainer) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(Res.drawable.ic_warning), contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.readout_setup_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(Res.string.readout_setup_text), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.width(12.dp))
            FilledTonalButton(onClick = onSettings, modifier = Modifier.heightIn(min = LocalTouchTargets.current.button)) {
                Text(stringResource(Res.string.readout_setup_settings))
            }
            Spacer(Modifier.width(LocalTouchTargets.current.gap))
            FilledTonalButton(onClick = onCalibrate, modifier = Modifier.heightIn(min = LocalTouchTargets.current.button)) {
                Text(stringResource(Res.string.readout_setup_calibrate))
            }
        }
    }
}

/**
 * The line of a one-time hint or a message. It shows a short text and never
 * blocks an action.
 */
@Composable
fun MessageLine(text: UiText, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth().testTag(MESSAGE_LINE_TAG).semantics { liveRegion = LiveRegionMode.Polite },
        color = colors.secondaryContainer,
        contentColor = colors.onSecondaryContainer,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(Res.drawable.ic_info), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(text.resolve(), style = MaterialTheme.typography.bodyLarge)
        }
    }
}
