package org.superdro.core.ui.readout.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.model.AxisButtonMode
import org.superdro.core.resources.Res
import org.superdro.core.resources.readout_details_center
import org.superdro.core.resources.readout_details_chip_load
import org.superdro.core.resources.readout_details_feed_rate
import org.superdro.core.resources.readout_details_mode
import org.superdro.core.resources.readout_details_per_minute
import org.superdro.core.resources.readout_details_per_tooth
import org.superdro.core.resources.readout_details_position
import org.superdro.core.resources.readout_details_thread_pitch
import org.superdro.core.resources.readout_details_title
import org.superdro.core.resources.readout_details_tpi
import org.superdro.core.resources.readout_details_tpi_unit
import org.superdro.core.resources.readout_details_unit
import org.superdro.core.resources.readout_unit_inch
import org.superdro.core.resources.readout_unit_mm
import org.superdro.core.resources.keypad_mode_absolute
import org.superdro.core.resources.keypad_mode_incremental
import org.superdro.core.ui.adaptive.AdaptiveSheet
import org.superdro.core.ui.graphics.proximity.ProximityButton
import org.superdro.core.ui.readout.ActionButton
import org.superdro.core.ui.readout.AxisValueUiState
import org.superdro.core.ui.readout.DroButton
import org.superdro.core.ui.readout.DroButtonStyle
import org.superdro.core.ui.readout.ReadoutEvents
import org.superdro.core.ui.readout.ReadoutRowUiState
import org.superdro.core.ui.readout.ReadoutTexts
import org.superdro.core.ui.readout.RowKind
import org.superdro.core.ui.theme.LocalTouchTargets

/** The test tag of the axis details sheet. */
const val AXIS_DETAILS_TAG: String = "axis-details"

/**
 * The axis details sheet (`AxisDetailsRoute`).
 *
 * - Linear axis: the position, the feed rate, the chip load, the thread pitch or the threads per
 *   inch (lathe Z only), and the buttons half, center in ABS, zero, ABS or INC, units, and hold.
 * - Angular axis: the position, the unit, and the buttons divisions, half, and zero.
 *
 * The buttons have the tap and the long press of the action buttons of the row.
 *
 * @param feed The feed values of a linear axis, or null for the angular axis.
 * @param onCenterAbsolute "Center (ABS)": `L(axis, shown / 2)`.
 * @param proximityOn The state of the proximity warning button of a linear axis, or null
 *   for no button.
 * @param onProximity A tap on the proximity warning button: start the warning with the target 0 on
 *   the shown value, or stop it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AxisDetailsSheet(
    row: ReadoutRowUiState,
    value: AxisValueUiState,
    feed: FeedTexts?,
    events: ReadoutEvents,
    onCenterAbsolute: () -> Unit,
    onDismiss: () -> Unit,
    proximityOn: Boolean? = null,
    onProximity: () -> Unit = {},
) {
    AdaptiveSheet(onDismissRequest = onDismiss, title = stringResource(Res.string.readout_details_title, row.label)) {
        Column(Modifier.fillMaxWidth().testTag(AXIS_DETAILS_TAG)) {
            val unit = stringResource(ReadoutTexts.unitSymbol(value.unit))
            DetailLine(stringResource(Res.string.readout_details_position), "${value.text} $unit")
            DetailLine(
                stringResource(Res.string.readout_details_mode),
                stringResource(if (value.incremental) Res.string.keypad_mode_incremental else Res.string.keypad_mode_absolute),
            )
            if (row.kind == RowKind.Angular) {
                DetailLine(stringResource(Res.string.readout_details_unit), stringResource(ReadoutTexts.unitName(value.unit)))
            }
            if (feed != null) {
                val length = stringResource(if (feed.metric) Res.string.readout_unit_mm else Res.string.readout_unit_inch)
                DetailLine(stringResource(Res.string.readout_details_feed_rate), "${feed.rate} ${stringResource(Res.string.readout_details_per_minute, length)}")
                DetailLine(stringResource(Res.string.readout_details_chip_load), "${feed.chipLoad} ${stringResource(Res.string.readout_details_per_tooth, length)}")
                feed.thread?.let { thread ->
                    if (feed.metric) {
                        DetailLine(stringResource(Res.string.readout_details_thread_pitch), "$thread ${stringResource(Res.string.readout_unit_mm)}")
                    } else {
                        DetailLine(stringResource(Res.string.readout_details_tpi), "$thread ${stringResource(Res.string.readout_details_tpi_unit)}")
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            val gap = LocalTouchTargets.current.gap
            FlowRow(horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap)) {
                val modes = if (row.kind == RowKind.Angular) {
                    listOf(AxisButtonMode.DivideCircle, AxisButtonMode.SetCenter, AxisButtonMode.SetZero)
                } else {
                    listOf(AxisButtonMode.SetCenter, AxisButtonMode.SetZero, AxisButtonMode.ToggleIncremental, AxisButtonMode.ToggleUnits, AxisButtonMode.Hold)
                }
                modes.forEach { mode -> ActionButton(row, mode, value, events) }
                if (row.kind == RowKind.Linear) {
                    val center = stringResource(Res.string.readout_details_center)
                    DroButton(onClick = onCenterAbsolute, contentDescription = center, style = DroButtonStyle.Primary, modifier = Modifier.testTag("axis-details-center")) {
                        Text(center, style = MaterialTheme.typography.titleMedium)
                    }
                }
                if (proximityOn != null) ProximityButton(proximityOn, onProximity)
            }
        }
    }
}

@Composable
private fun DetailLine(name: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
