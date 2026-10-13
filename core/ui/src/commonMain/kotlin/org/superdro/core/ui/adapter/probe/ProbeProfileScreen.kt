package org.superdro.core.ui.adapter.probe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.functions.tipcal.CalibrationRecord
import org.superdro.core.functions.tipcal.SpreadLimitSource
import org.superdro.core.model.AxisId
import org.superdro.core.model.ProbeType
import org.superdro.core.resources.Res
import org.superdro.core.resources.frame_cancel
import org.superdro.core.resources.frame_done
import org.superdro.core.resources.frame_save
import org.superdro.core.resources.ic_check
import org.superdro.core.resources.ic_info
import org.superdro.core.resources.ic_science
import org.superdro.core.resources.ic_sync
import org.superdro.core.resources.probelib_calibrate
import org.superdro.core.resources.probelib_calibrated_mark
import org.superdro.core.resources.probelib_explain_camera
import org.superdro.core.resources.probelib_explain_offsets
import org.superdro.core.resources.probelib_explain_setter
import org.superdro.core.resources.probelib_field_diameter
import org.superdro.core.resources.probelib_field_max_spread
import org.superdro.core.resources.probelib_field_name
import org.superdro.core.resources.probelib_field_port
import org.superdro.core.resources.probelib_field_sample_count
import org.superdro.core.resources.probelib_field_setter_height
import org.superdro.core.resources.probelib_field_type
import org.superdro.core.resources.probelib_field_units
import org.superdro.core.resources.probelib_field_x_offset
import org.superdro.core.resources.probelib_field_y_offset
import org.superdro.core.resources.probelib_field_z_offset
import org.superdro.core.resources.probelib_hint_sample_count
import org.superdro.core.resources.probelib_hint_spread_default
import org.superdro.core.resources.probelib_hint_spread_measured
import org.superdro.core.resources.probelib_name_auto
import org.superdro.core.resources.probelib_not_found
import org.superdro.core.resources.probelib_port_none
import org.superdro.core.resources.probelib_port_value
import org.superdro.core.resources.probelib_record
import org.superdro.core.resources.probelib_record_across
import org.superdro.core.resources.probelib_record_completed
import org.superdro.core.resources.probelib_record_gauge
import org.superdro.core.resources.probelib_record_incomplete
import org.superdro.core.resources.probelib_record_limit
import org.superdro.core.resources.probelib_record_line
import org.superdro.core.resources.probelib_record_none
import org.superdro.core.resources.probelib_record_repeatability
import org.superdro.core.resources.probelib_record_tip
import org.superdro.core.resources.probelib_record_touches
import org.superdro.core.resources.probelib_refused_title
import org.superdro.core.resources.probelib_section_offsets
import org.superdro.core.resources.probelib_section_probe
import org.superdro.core.resources.probelib_section_touches
import org.superdro.core.resources.probelib_standard_tip_read_only
import org.superdro.core.resources.probelib_sync
import org.superdro.core.resources.probelib_title_new
import org.superdro.core.resources.route_probe_profile
import org.superdro.core.ui.adapter.common.ChoiceRow
import org.superdro.core.ui.adapter.common.NumberField
import org.superdro.core.ui.adapter.common.SectionTitle
import org.superdro.core.ui.adapter.common.StatusKind
import org.superdro.core.ui.adapter.common.StatusLine
import org.superdro.core.ui.adapter.common.ValueRow
import org.superdro.core.ui.components.InfoPanel
import org.superdro.core.ui.components.OpenCloseIcon
import org.superdro.core.ui.components.openState
import org.superdro.core.ui.navigation.ScreenScaffold
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.LocalTouchTargets
import org.superdro.core.ui.theme.accentTextFieldColors

/** The events of the probe profile screen. */
interface ProbeProfileEvents {
    fun onBack()
    fun onType(type: ProbeType)
    fun onName(text: String)
    fun onMetric(metric: Boolean)
    fun onPort(port: Char?)
    fun onDiameter(text: String)
    fun onSampleCount(text: String)
    fun onMaxSpread(text: String)
    fun onXOffset(text: String)
    fun onYOffset(text: String)
    fun onZOffset(text: String)
    fun onSync(axis: AxisId)

    /** [autoName] is the automatic name in the language of the user. */
    fun onSave(autoName: String)
    fun onCalibrate(autoName: String)
    fun onShowRecord()
    fun onDismissRecord()
    fun onDismissRefusal()
}

/** The test tag of the button that opens and closes the calibration record. */
const val PROBE_RECORD_BUTTON_TAG: String = "probe-record"

/** The test tag of the calibration record in the screen (not in a dialog). */
const val PROBE_RECORD_TAG: String = "probe-record-section"

/** The test tag of the save button of the probe profile screen. */
const val PROBE_SAVE_TAG: String = "probe-save"

/** The test tag of the calibrate button of the probe profile screen. */
const val PROBE_CALIBRATE_TAG: String = "probe-calibrate"

/** The test tag of the field of [field]. */
fun probeFieldTag(field: ProbeField): String = "probe-field-${field.name}"

/** The test tag of the name field. */
const val PROBE_NAME_TAG: String = "probe-field-name"

/** The automatic name of the form in the language of the user. */
@Composable
fun probeAutoName(form: ProbeProfileForm): String =
    ProbeProfileSave.autoName(form, stringResource(ProbeTypeRules.label(form.type)), stringResource(ProbeLengths.unit(form.metric)))

/**
 * The probe profile screen: the type (only
 * for a new profile), the name, the units, the trigger port, the tip diameter with "Calibrate", the
 * touches and the spread limit (only with a port), and the offsets with the explainer text.
 */
@Composable
fun ProbeProfileScreen(state: ProbeProfileUiState, events: ProbeProfileEvents) {
    val title = if (state.isNew) stringResource(Res.string.probelib_title_new) else stringResource(Res.string.route_probe_profile)
    val autoName = probeAutoName(state.form)
    ScreenScaffold(title = title, onBack = events::onBack) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 8.dp),
            ) {
                if (state.notFound) {
                    StatusLine(StatusKind.Error, stringResource(Res.string.probelib_not_found))
                    return@Column
                }
                if (!state.loaded) return@Column
                if (state.standardTip) StatusLine(StatusKind.Info, stringResource(Res.string.probelib_standard_tip_read_only))
                ProfileFields(state, autoName, events)
                Spacer(Modifier.size(24.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                    val target = LocalTouchTargets.current
                    TextButton(onClick = events::onBack, colors = AccentButtons.text(), modifier = Modifier.heightIn(min = target.button)) {
                        Text(stringResource(Res.string.frame_cancel))
                    }
                    Button(
                        onClick = { events.onSave(autoName) },
                        enabled = !state.standardTip,
                        modifier = Modifier.heightIn(min = target.button).testTag(PROBE_SAVE_TAG),
                        colors = AccentButtons.filled(),
                    ) { Text(stringResource(Res.string.frame_save)) }
                }
                Spacer(Modifier.size(24.dp))
            }
        }
    }
    state.refusal?.let { text ->
        AlertDialog(
            onDismissRequest = events::onDismissRefusal,
            title = { Text(stringResource(Res.string.probelib_refused_title)) },
            text = { Text(text.resolve()) },
            confirmButton = { TextButton(colors = AccentButtons.text(), onClick = events::onDismissRefusal) { Text(stringResource(Res.string.frame_done)) } },
        )
    }
}

@Composable
private fun ProfileFields(state: ProbeProfileUiState, autoName: String, events: ProbeProfileEvents) {
    val form = state.form
    val enabled = !state.standardTip
    val unit = stringResource(ProbeLengths.unit(form.metric))
    SectionTitle(stringResource(Res.string.probelib_section_probe))
    if (state.isNew) {
        TypeChips(form.type, events::onType)
    } else {
        ValueRow(stringResource(Res.string.probelib_field_type), stringResource(ProbeTypeRules.label(form.type)))
    }
    OutlinedTextField(
        value = form.name,
        onValueChange = events::onName,
        label = { Text(stringResource(Res.string.probelib_field_name)) },
        placeholder = { Text(autoName) },
        singleLine = true,
        enabled = enabled,
        colors = accentTextFieldColors(),
        supportingText = {
            if (form.name.isEmpty()) {
                Text(stringResource(Res.string.probelib_name_auto, autoName))
            } else {
                Text("${form.name.length} / ${ProbeProfileForm.MAX_NAME_LENGTH}")
            }
        },
        modifier = Modifier.fillMaxWidth().testTag(PROBE_NAME_TAG),
    )
    ChoiceRow(
        label = stringResource(Res.string.probelib_field_units),
        options = listOf(true, false),
        selected = form.metric,
        optionLabel = { metric -> stringResource(ProbeLengths.unit(metric)) },
        onSelect = events::onMetric,
        enabled = enabled,
        tag = "probe-units",
    )
    val ports = ProbeTypeRules.ports(form.type)
    if (ports.isNotEmpty()) {
        ChoiceRow(
            label = stringResource(Res.string.probelib_field_port),
            options = ports,
            selected = form.port,
            optionLabel = { port ->
                if (port == null) stringResource(Res.string.probelib_port_none) else stringResource(Res.string.probelib_port_value, port.uppercaseChar().toString())
            },
            onSelect = events::onPort,
            enabled = enabled,
            tag = "probe-port",
        )
    }
    if (ProbeTypeRules.usesDiameter(form.type)) {
        NumberField(
            label = stringResource(Res.string.probelib_field_diameter),
            value = form.diameter,
            onValueChange = events::onDiameter,
            error = state.errors[ProbeField.Diameter],
            suffix = unit,
            enabled = enabled,
            tag = probeFieldTag(ProbeField.Diameter),
        )
        if (state.diameterCalibrated) StatusLine(StatusKind.Success, stringResource(Res.string.probelib_calibrated_mark))
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val target = LocalTouchTargets.current
            if (state.canCalibrate && enabled) {
                OutlinedButton(
                    onClick = { events.onCalibrate(autoName) },
                    colors = AccentButtons.outlined(),
                    modifier = Modifier.heightIn(min = target.button).testTag(PROBE_CALIBRATE_TAG),
                ) {
                    Icon(painterResource(Res.drawable.ic_science), contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(Res.string.probelib_calibrate))
                }
            }
            if (!state.isNew) {
                val open = state.record != null
                OutlinedButton(
                    onClick = if (open) events::onDismissRecord else events::onShowRecord,
                    colors = AccentButtons.outlined(),
                    modifier = Modifier.heightIn(min = target.button).openState(open).testTag(PROBE_RECORD_BUTTON_TAG),
                ) {
                    Icon(painterResource(Res.drawable.ic_info), contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(Res.string.probelib_record))
                    Spacer(Modifier.size(8.dp))
                    OpenCloseIcon(open)
                }
            }
        }
        // The dialog rule: the calibration record shows in the screen, below its button.
        // The button closes it.
        state.record?.let { record -> RecordSection(record, form.metric) }
    }
    if (form.port != null) {
        SectionTitle(stringResource(Res.string.probelib_section_touches))
        NumberField(
            label = stringResource(Res.string.probelib_field_sample_count),
            value = form.sampleCount,
            onValueChange = events::onSampleCount,
            error = state.errors[ProbeField.SampleCount],
            supporting = stringResource(Res.string.probelib_hint_sample_count),
            decimal = false,
            enabled = enabled,
            tag = probeFieldTag(ProbeField.SampleCount),
        )
        val hint = state.spreadHint
        val hintValue = ProbeLengths.fineText(hint.valueUm, form.metric)
        NumberField(
            label = stringResource(Res.string.probelib_field_max_spread),
            value = form.maxSpread,
            onValueChange = events::onMaxSpread,
            error = state.errors[ProbeField.MaxSpread],
            suffix = unit,
            supporting = if (hint.source == SpreadLimitSource.Calibration) {
                stringResource(Res.string.probelib_hint_spread_measured, hintValue, unit)
            } else {
                stringResource(Res.string.probelib_hint_spread_default, hintValue, unit)
            },
            enabled = enabled,
            tag = probeFieldTag(ProbeField.MaxSpread),
        )
    }
    val usesXy = ProbeTypeRules.usesXyOffsets(form.type)
    val usesZ = ProbeTypeRules.usesZOffset(form.type)
    if (usesXy || usesZ) {
        SectionTitle(stringResource(Res.string.probelib_section_offsets))
        Text(
            text = stringResource(
                when (form.type) {
                    ProbeType.Camera -> Res.string.probelib_explain_camera
                    ProbeType.ToolHeightSetter -> Res.string.probelib_explain_setter
                    else -> Res.string.probelib_explain_offsets
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (usesXy) {
        OffsetField(Res.string.probelib_field_x_offset, form.xOffset, events::onXOffset, ProbeField.XOffset, AxisId.X, state, unit, events)
        OffsetField(Res.string.probelib_field_y_offset, form.yOffset, events::onYOffset, ProbeField.YOffset, AxisId.Y, state, unit, events)
    }
    if (usesZ) {
        val label = if (form.type == ProbeType.ToolHeightSetter) Res.string.probelib_field_setter_height else Res.string.probelib_field_z_offset
        OffsetField(label, form.zOffset, events::onZOffset, ProbeField.ZOffset, AxisId.Z, state, unit, events)
    }
}

/** The type of a new profile: one chip for each type. A chip can have a long name in some languages. */
@Composable
private fun TypeChips(selected: ProbeType, onSelect: (ProbeType) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(stringResource(Res.string.probelib_field_type), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().testTag("probe-type")) {
            ProbeType.entries.forEach { type ->
                FilterChip(
                    selected = type == selected,
                    onClick = { onSelect(type) },
                    label = { Text(stringResource(ProbeTypeRules.label(type))) },
                    leadingIcon = if (type == selected) {
                        { Icon(painterResource(Res.drawable.ic_check), contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else {
                        null
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("probe-type-${type.name}"),
                )
            }
        }
    }
}

@Composable
private fun OffsetField(
    label: org.jetbrains.compose.resources.StringResource,
    value: String,
    onChange: (String) -> Unit,
    field: ProbeField,
    axis: AxisId,
    state: ProbeProfileUiState,
    unit: String,
    events: ProbeProfileEvents,
) {
    val labelText = stringResource(label)
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        NumberField(
            label = labelText,
            value = value,
            onValueChange = onChange,
            error = state.errors[field],
            suffix = unit,
            enabled = !state.standardTip,
            tag = probeFieldTag(field),
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = { events.onSync(axis) },
            enabled = state.canSync && !state.standardTip,
            modifier = Modifier.size(56.dp).testTag("probe-sync-${axis.name}"),
        ) { Icon(painterResource(Res.drawable.ic_sync), contentDescription = stringResource(Res.string.probelib_sync, axis.name)) }
    }
}

/**
 * The calibration record of the stored profile in a section of the screen: the tip diameter, the
 * repeatability, the gauge, the date, the diameters across X and Y, the touches of each position,
 * and the spread limit.
 */
@Composable
private fun RecordSection(record: CalibrationRecord, metric: Boolean) {
    val unit = stringResource(ProbeLengths.unit(metric))
    fun length(um: Double): String = ProbeLengths.text(um, metric)
    InfoPanel(modifier = Modifier.padding(vertical = 4.dp), tag = PROBE_RECORD_TAG) {
        Text(stringResource(Res.string.probelib_record), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        Column {
            when (record) {
                CalibrationRecord.NoCalibration, CalibrationRecord.Unreadable -> Text(stringResource(Res.string.probelib_record_none))
                is CalibrationRecord.Incomplete -> {
                    StatusLine(StatusKind.Warning, stringResource(Res.string.probelib_record_incomplete))
                    RecordLines(record.positions, metric, unit)
                }
                is CalibrationRecord.Complete -> {
                    Text(stringResource(Res.string.probelib_record_tip, length(record.tipDiameterUm), unit))
                    record.repeatabilityUm?.let { Text(stringResource(Res.string.probelib_record_repeatability, ProbeLengths.fineText(it, metric), unit)) }
                    record.gaugeUm?.let { Text(stringResource(Res.string.probelib_record_gauge, length(it), unit)) }
                    record.completed?.let { Text(stringResource(Res.string.probelib_record_completed, it)) }
                    val acrossX = record.diameterXUm
                    val acrossY = record.diameterYUm
                    if (acrossX != null && acrossY != null) {
                        Text(stringResource(Res.string.probelib_record_across, length(acrossX), length(acrossY), unit))
                    }
                    RecordLines(record.positions, metric, unit)
                    record.spreadLimitFromRunUm?.let { Text(stringResource(Res.string.probelib_record_limit, ProbeLengths.fineText(it, metric), unit)) }
                }
            }
        }
    }
}

@Composable
private fun RecordLines(lines: List<org.superdro.core.functions.tipcal.RecordPositionLine>, metric: Boolean, unit: String) {
    if (lines.isEmpty()) return
    SectionTitle(stringResource(Res.string.probelib_record_touches))
    lines.forEach { line ->
        val spread = line.spreadUm?.let { ProbeLengths.fineText(it, metric) } ?: "?"
        Text(stringResource(Res.string.probelib_record_line, line.clock.hour, line.touchCount, spread, unit))
    }
}
