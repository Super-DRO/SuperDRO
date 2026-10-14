package org.superdro.core.ui.graphics.measure

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.connection.DeviceId
import org.superdro.core.model.AxisId
import org.superdro.core.resources.Res
import org.superdro.core.resources.measure_done
import org.superdro.core.resources.measure_instrument_clear
import org.superdro.core.resources.measure_instrument_hint
import org.superdro.core.resources.measure_instrument_label
import org.superdro.core.resources.measure_instrument_title
import org.superdro.core.resources.measure_instrument_x_offset
import org.superdro.core.resources.measure_instrument_y_offset
import org.superdro.core.ui.adaptive.AdaptiveSheet
import org.superdro.core.ui.graphics.GraphicsDependencies
import org.superdro.core.ui.graphics.common.LengthTexts
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.readout.TabReadout
import org.superdro.core.ui.readout.structure
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.LocalTouchTargets
import org.superdro.core.ui.theme.accentTextFieldColors
import org.superdro.core.ui.adapter.probe.ProbeFunction
import org.superdro.core.ui.adapter.probe.ProbeOption
import org.superdro.core.ui.adapter.probe.ProbeSelector
import org.superdro.core.ui.adapter.probe.ProbeSelectorDelegate
import org.superdro.core.ui.adapter.probe.ProbeSelectorUiState

/** The test tag of "Clear Reference". */
const val INSTRUMENT_CLEAR_TAG: String = "measure-instrument-clear"

/**
 * The state of the reference instrument sheet.
 *
 * @property selector The probe selector of the measurement instrument: the valid
 *   instruments and the selection.
 * @property xOffsetText The X offset of the selected instrument in the display unit, with no unit, or "".
 * @property metric The display unit of the offsets.
 */
@Immutable
data class InstrumentUiState(
    val selector: ProbeSelectorUiState = ProbeSelectorUiState(ProbeFunction.MeasurementInstrument),
    val xOffsetText: String = "",
    val yOffsetText: String = "",
    val metric: Boolean = true,
) {
    /** The valid instruments: a profile with X and Y offsets that is a camera or has a trigger port. */
    val options: List<ProbeOption> get() = selector.options

    /** The selected valid instrument, or null for "No probe selected". */
    val selected: ProbeOption? get() = selector.selected
}

/**
 * The presenter of the reference instrument sheet (on both mill types). The probe
 * selector of the adapter area ([ProbeSelectorDelegate]) writes
 * `ProbeRoles.measurementInstrumentProfileId` and the "last used" time of the profile. A new
 * profile from "New profile…" becomes the selection. The readout then adds the X and Y offsets of
 * the instrument to the X and Y readouts in the measurement mode.
 */
class InstrumentPresenter(
    deviceId: DeviceId,
    private val readout: TabReadout,
    private val deps: GraphicsDependencies,
) : ScopedPresenter(deps.context) {

    private val profileId = deviceId.value
    private val selector = ProbeSelectorDelegate(deps.machines, deps.probes, deps.wallClock, deviceId, ProbeFunction.MeasurementInstrument, scope)

    /** The state of the sheet. */
    val uiState: StateFlow<InstrumentUiState> = combine(
        deps.probes.library(profileId),
        deps.machines.profile(profileId),
        readout.frame.structure(),
        selector.state,
    ) { library, profile, frame, probeSelector ->
        val probe = library.firstOrNull { item -> item.id == probeSelector.selectedId }
        val metric = frame?.metric(AxisId.X) ?: profile?.metricDefault ?: true
        val format = (frame?.profile ?: profile)?.display?.readoutFormat
        InstrumentUiState(
            selector = probeSelector,
            xOffsetText = if (probe == null || format == null) "" else LengthTexts.format(probe.xOffsetUm, metric, format),
            yOffsetText = if (probe == null || format == null) "" else LengthTexts.format(probe.yOffsetUm, metric, format),
            metric = metric,
        )
    }.stateIn(scope, SharingStarted.Eagerly, InstrumentUiState())

    /** Selects the instrument [id]. Null selects no instrument. */
    fun onSelect(id: String?) = selector.select(id)

    /**
     * "New profile…" of the selector: the caller opens the new probe profile screen. The next new
     * valid instrument becomes the selection.
     */
    fun onNew() = selector.requestNewProfile()

    /** "Clear Reference": no instrument. The readouts refer to the spindle again. */
    fun onClear() = selector.select(null)
}

/**
 * The reference instrument sheet: the probe selector of the
 * measurement instrument, the read only offsets, "Clear Reference", and "Done". "New
 * profile…" opens a new probe profile, and the edit button opens the selected profile.
 */
@Composable
fun InstrumentSheet(
    state: InstrumentUiState,
    onSelect: (String?) -> Unit,
    onNew: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    onEdit: (String) -> Unit = {},
) {
    val targets = LocalTouchTargets.current
    AdaptiveSheet(onDismissRequest = onDismiss, title = stringResource(Res.string.measure_instrument_title)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.measure_instrument_label), style = MaterialTheme.typography.titleMedium)
            ProbeSelector(state = state.selector, onSelect = onSelect, onNewProfile = onNew, onEdit = onEdit)
            val unit = stringResource(LengthTexts.unitSymbol(state.metric))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ReadOnlyField(stringResource(Res.string.measure_instrument_x_offset), state.xOffsetText, unit, Modifier.weight(1f))
                ReadOnlyField(stringResource(Res.string.measure_instrument_y_offset), state.yOffsetText, unit, Modifier.weight(1f))
            }
            Text(stringResource(Res.string.measure_instrument_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = onClear,
                    enabled = state.selected != null,
                    colors = AccentButtons.outlined(),
                    modifier = Modifier.heightIn(min = targets.button).testTag(INSTRUMENT_CLEAR_TAG),
                ) { Text(stringResource(Res.string.measure_instrument_clear)) }
                Spacer(Modifier.weight(1f).width(8.dp))
                FilledTonalButton(onClick = onDismiss, modifier = Modifier.heightIn(min = targets.button)) {
                    Text(stringResource(Res.string.measure_done))
                }
            }
        }
    }
}

@Composable
private fun ReadOnlyField(label: String, value: String, unit: String, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        suffix = { Text(unit) },
        singleLine = true,
        modifier = modifier.padding(vertical = 2.dp),
        colors = accentTextFieldColors(),
    )
}
