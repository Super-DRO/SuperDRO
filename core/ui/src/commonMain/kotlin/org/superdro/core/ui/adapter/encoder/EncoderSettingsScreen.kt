package org.superdro.core.ui.adapter.encoder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.model.BacklashMode
import org.superdro.core.model.CalibrationStatus
import org.superdro.core.model.EncoderKind
import org.superdro.core.model.EncoderType
import org.superdro.core.resources.Res
import org.superdro.core.resources.encoder_adapter
import org.superdro.core.resources.encoder_backlash_constant
import org.superdro.core.resources.encoder_backlash_map
import org.superdro.core.resources.encoder_backlash_mode
import org.superdro.core.resources.encoder_calibrate
import org.superdro.core.resources.encoder_calibrated_on
import org.superdro.core.resources.encoder_calibrated_with
import org.superdro.core.resources.encoder_counts
import org.superdro.core.resources.encoder_cyclic
import org.superdro.core.resources.encoder_cyclic_help
import org.superdro.core.resources.encoder_delete
import org.superdro.core.resources.encoder_delete_backlash_text
import org.superdro.core.resources.encoder_delete_backlash_title
import org.superdro.core.resources.encoder_edit_map
import org.superdro.core.resources.encoder_error_map
import org.superdro.core.resources.encoder_filter
import org.superdro.core.resources.encoder_filter_help
import org.superdro.core.resources.encoder_hysteresis
import org.superdro.core.resources.encoder_hysteresis_help
import org.superdro.core.resources.encoder_kind
import org.superdro.core.resources.encoder_loading
import org.superdro.core.resources.encoder_map_not_set
import org.superdro.core.resources.encoder_map_summary
import org.superdro.core.resources.encoder_no_adapter
import org.superdro.core.resources.encoder_no_adapter_offline
import org.superdro.core.resources.encoder_resolution
import org.superdro.core.resources.encoder_reversed
import org.superdro.core.resources.encoder_reversed_help
import org.superdro.core.resources.encoder_screw_rev
import org.superdro.core.resources.encoder_section_backlash
import org.superdro.core.resources.encoder_section_calibration
import org.superdro.core.resources.encoder_section_encoder
import org.superdro.core.resources.encoder_section_error_map
import org.superdro.core.resources.encoder_section_problems
import org.superdro.core.resources.encoder_title
import org.superdro.core.resources.encoder_type
import org.superdro.core.resources.encoder_unit_per_inch
import org.superdro.core.resources.encoder_unit_per_rev
import org.superdro.core.resources.frame_cancel
import org.superdro.core.ui.adapter.AdapterTexts
import org.superdro.core.ui.adapter.common.ChoiceRow
import org.superdro.core.ui.adapter.common.NumberField
import org.superdro.core.ui.adapter.common.SectionTitle
import org.superdro.core.ui.adapter.common.StatusKind
import org.superdro.core.ui.adapter.common.StatusLine
import org.superdro.core.ui.adapter.common.SwitchRow
import org.superdro.core.ui.navigation.ScreenScaffold
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.LocalTouchTargets

/** The test tags of the encoder settings screen. */
object EncoderTags {
    const val RESOLUTION: String = "encoder-resolution"
    const val SCREW_REV: String = "encoder-screw-rev"
    const val HYSTERESIS: String = "encoder-hysteresis"
    const val BACKLASH_CONSTANT: String = "encoder-backlash-constant"
    const val REVERSED: String = "encoder-reversed"
    const val FILTER: String = "encoder-filter"
    const val CYCLIC: String = "encoder-cyclic"
    const val CALIBRATE: String = "encoder-calibrate"
    const val EDIT_ERROR_MAP: String = "encoder-edit-error-map"
    const val EDIT_BACKLASH_MAP: String = "encoder-edit-backlash-map"
    const val TYPE: String = "encoder-type"
    const val KIND: String = "encoder-kind"
    const val BACKLASH_MODE: String = "encoder-backlash-mode"
}

/** The events of the encoder settings screen. */
interface EncoderSettingsEvents {
    fun onType(type: EncoderType)
    fun onKind(kind: EncoderKind)
    fun onResolution(text: String)
    fun onCountsPerScrewRev(text: String)
    fun onReversed(reversed: Boolean)
    fun onFilter(filter: Boolean)
    fun onHysteresis(text: String)
    fun onBacklashMode(mode: BacklashMode)
    fun onBacklashConstant(text: String)
    fun onCyclic(cyclic: Boolean)
    fun onConfirmDeleteBacklashMap()
    fun onCancelDeleteBacklashMap()
    fun onCalibrate()
    fun onEditErrorMap()
    fun onEditBacklashMap()
}

/** The [EncoderSettingsEvents] of a presenter. [open] gets the navigation requests. */
class PresenterEncoderEvents(
    private val presenter: EncoderSettingsPresenter,
    private val openCalibration: () -> Unit,
    private val openErrorMap: () -> Unit,
    private val openBacklashMap: () -> Unit,
) : EncoderSettingsEvents {
    override fun onType(type: EncoderType) = presenter.onType(type)
    override fun onKind(kind: EncoderKind) = presenter.onKind(kind)
    override fun onResolution(text: String) = presenter.onResolution(text)
    override fun onCountsPerScrewRev(text: String) = presenter.onCountsPerScrewRev(text)
    override fun onReversed(reversed: Boolean) = presenter.onReversed(reversed)
    override fun onFilter(filter: Boolean) = presenter.onFilter(filter)
    override fun onHysteresis(text: String) = presenter.onHysteresis(text)
    override fun onBacklashMode(mode: BacklashMode) = presenter.onBacklashMode(mode)
    override fun onBacklashConstant(text: String) = presenter.onBacklashConstant(text)
    override fun onCyclic(cyclic: Boolean) = presenter.onCyclic(cyclic)
    override fun onConfirmDeleteBacklashMap() = presenter.onConfirmDeleteBacklashMap()
    override fun onCancelDeleteBacklashMap() = presenter.onCancelDeleteBacklashMap()
    override fun onCalibrate() {
        if (presenter.onCalibrate()) openCalibration()
    }
    override fun onEditErrorMap() {
        if (presenter.onEditMap()) openErrorMap()
    }
    override fun onEditBacklashMap() {
        if (presenter.onEditMap()) openBacklashMap()
    }
}

/**
 * The encoder settings screen of one input. The composable is stateless:
 * [state] gives the values and [events] gets each change.
 */
@Composable
fun EncoderSettingsScreen(state: EncoderSettingsUiState, events: EncoderSettingsEvents, onBack: () -> Unit) {
    val title = stringResource(Res.string.encoder_title, AdapterTexts.encoderInputName(state.input).resolve())
    ScreenScaffold(title = title, onBack = onBack) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 760.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 8.dp),
            ) {
                when {
                    !state.loaded -> StatusLine(StatusKind.Progress, stringResource(Res.string.encoder_loading))
                    !state.available -> StatusLine(
                        StatusKind.Warning,
                        stringResource(if (state.online) Res.string.encoder_no_adapter else Res.string.encoder_no_adapter_offline),
                    )
                    else -> EncoderForm(state, events)
                }
                Spacer(Modifier.size(24.dp))
            }
        }
    }
    if (state.confirmDeleteBacklashMap) {
        AlertDialog(
            onDismissRequest = events::onCancelDeleteBacklashMap,
            title = { Text(stringResource(Res.string.encoder_delete_backlash_title)) },
            text = { Text(stringResource(Res.string.encoder_delete_backlash_text)) },
            confirmButton = {
                TextButton(onClick = events::onConfirmDeleteBacklashMap, colors = AccentButtons.text()) { Text(stringResource(Res.string.encoder_delete)) }
            },
            dismissButton = {
                TextButton(onClick = events::onCancelDeleteBacklashMap, colors = AccentButtons.text()) { Text(stringResource(Res.string.frame_cancel)) }
            },
        )
    }
}

@Composable
private fun EncoderForm(state: EncoderSettingsUiState, events: EncoderSettingsEvents) {
    val form = state.form
    val visible = state.visibility
    val errors = state.errors
    Text(
        stringResource(Res.string.encoder_adapter, state.adapterName),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (state.problems.isNotEmpty()) {
        SectionTitle(stringResource(Res.string.encoder_section_problems))
        state.problems.forEach { problem -> StatusLine(StatusKind.Error, problem.resolve()) }
    }

    SectionTitle(stringResource(Res.string.encoder_section_encoder))
    ChoiceRow(
        label = stringResource(Res.string.encoder_type),
        options = EncoderType.entries,
        selected = form.type,
        optionLabel = { stringResource(EncoderTexts.type(it)) },
        onSelect = events::onType,
        tag = EncoderTags.TYPE,
    )
    ChoiceRow(
        label = stringResource(Res.string.encoder_kind),
        options = EncoderKind.entries,
        selected = form.kind,
        optionLabel = { stringResource(EncoderTexts.kind(it)) },
        onSelect = events::onKind,
        tag = EncoderTags.KIND,
    )
    if (visible.resolution) {
        NumberField(
            label = stringResource(Res.string.encoder_resolution),
            value = form.resolution,
            onValueChange = events::onResolution,
            error = errors.resolution?.let { UiText.of(EncoderTexts.fieldError(it)) },
            suffix = stringResource(if (form.kind == EncoderKind.Angular) Res.string.encoder_unit_per_rev else Res.string.encoder_unit_per_inch),
            tag = EncoderTags.RESOLUTION,
        )
    }
    if (visible.countsPerScrewRev) {
        NumberField(
            label = stringResource(Res.string.encoder_screw_rev),
            value = form.countsPerScrewRev,
            onValueChange = events::onCountsPerScrewRev,
            error = errors.countsPerScrewRev?.let { UiText.of(EncoderTexts.fieldError(it)) },
            suffix = stringResource(Res.string.encoder_counts),
            tag = EncoderTags.SCREW_REV,
        )
    }
    SwitchRow(
        label = stringResource(Res.string.encoder_reversed),
        checked = form.reversed,
        onCheckedChange = events::onReversed,
        supporting = stringResource(Res.string.encoder_reversed_help),
        tag = EncoderTags.REVERSED,
    )
    SwitchRow(
        label = stringResource(Res.string.encoder_filter),
        checked = form.filter,
        onCheckedChange = events::onFilter,
        supporting = stringResource(Res.string.encoder_filter_help),
        tag = EncoderTags.FILTER,
    )

    if (visible.hysteresis || visible.backlashMode) {
        SectionTitle(stringResource(Res.string.encoder_section_backlash))
    }
    if (visible.hysteresis) {
        NumberField(
            label = stringResource(Res.string.encoder_hysteresis),
            value = form.hysteresis,
            onValueChange = events::onHysteresis,
            error = errors.hysteresis?.let { UiText.of(EncoderTexts.fieldError(it)) },
            supporting = stringResource(Res.string.encoder_hysteresis_help),
            suffix = stringResource(Res.string.encoder_counts),
            decimal = false,
            tag = EncoderTags.HYSTERESIS,
        )
    }
    if (visible.backlashMode) {
        ChoiceRow(
            label = stringResource(Res.string.encoder_backlash_mode),
            options = BacklashMode.entries,
            selected = form.backlashMode,
            optionLabel = { stringResource(EncoderTexts.backlash(it)) },
            onSelect = events::onBacklashMode,
            tag = EncoderTags.BACKLASH_MODE,
        )
    }
    if (visible.backlashConstant) {
        NumberField(
            label = stringResource(Res.string.encoder_backlash_constant),
            value = form.backlashConstant,
            onValueChange = events::onBacklashConstant,
            error = errors.backlashConstant?.let { UiText.of(EncoderTexts.fieldError(it)) },
            suffix = stringResource(Res.string.encoder_counts),
            decimal = false,
            tag = EncoderTags.BACKLASH_CONSTANT,
        )
    }
    if (visible.backlashMap) {
        MapRow(stringResource(Res.string.encoder_backlash_map), state.backlashMap, EncoderTags.EDIT_BACKLASH_MAP, events::onEditBacklashMap)
    }

    if (visible.resolution) {
        SectionTitle(stringResource(Res.string.encoder_section_calibration))
        CalibrationRow(state.calibration)
        if (visible.calibrate) {
            Spacer(Modifier.size(8.dp))
            FilledTonalButton(
                onClick = events::onCalibrate,
                modifier = Modifier.heightIn(min = LocalTouchTargets.current.button).testTag(EncoderTags.CALIBRATE),
            ) { Text(stringResource(Res.string.encoder_calibrate)) }
        }
    }

    SectionTitle(stringResource(Res.string.encoder_section_error_map))
    MapRow(stringResource(Res.string.encoder_error_map), state.errorMap, EncoderTags.EDIT_ERROR_MAP, events::onEditErrorMap)
    if (visible.cyclic) {
        SwitchRow(
            label = stringResource(Res.string.encoder_cyclic),
            checked = form.cyclic,
            onCheckedChange = events::onCyclic,
            supporting = stringResource(Res.string.encoder_cyclic_help),
            tag = EncoderTags.CYCLIC,
        )
    }
}

@Composable
private fun CalibrationRow(calibration: CalibrationUi) {
    val kind = if (calibration.status == CalibrationStatus.NotCalibrated) StatusKind.Warning else StatusKind.Success
    StatusLine(kind, stringResource(EncoderTexts.status(calibration.status)))
    calibration.date?.let { date ->
        Text(stringResource(Res.string.encoder_calibrated_on, date), style = MaterialTheme.typography.bodyMedium)
    }
    val machine = calibration.machineType
    val axis = calibration.axis
    if (machine != null && axis != null) {
        Text(
            stringResource(Res.string.encoder_calibrated_with, stringResource(EncoderTexts.machine(machine)), stringResource(EncoderTexts.axis(axis))),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun MapRow(label: String, summary: MapSummaryLine?, tag: String, onEdit: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = if (summary == null) {
                    stringResource(Res.string.encoder_map_not_set)
                } else {
                    stringResource(Res.string.encoder_map_summary, summary.intervals, summary.first, summary.last)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(
            onClick = onEdit,
            colors = AccentButtons.outlined(),
            modifier = Modifier.heightIn(min = 48.dp).testTag(tag),
        ) { Text(stringResource(Res.string.encoder_edit_map)) }
    }
    HorizontalDivider()
}
