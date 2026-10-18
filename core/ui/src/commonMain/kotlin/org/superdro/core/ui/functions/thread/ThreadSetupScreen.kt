package org.superdro.core.ui.functions.thread

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.functions.thread.DrillSeries
import org.superdro.core.functions.thread.PassKind
import org.superdro.core.functions.thread.ThreadDialRule
import org.superdro.core.functions.thread.ThreadUnit
import org.superdro.core.functions.thread.label
import org.superdro.core.model.PartialInsertPreset
import org.superdro.core.model.ThreadClass
import org.superdro.core.model.ThreadCutter
import org.superdro.core.model.ThreadForm
import org.superdro.core.model.ThreadInfeedMode
import org.superdro.core.resources.Res
import org.superdro.core.resources.fn_create
import org.superdro.core.resources.fn_unit_inch
import org.superdro.core.resources.fn_unit_mm
import org.superdro.core.resources.ic_check
import org.superdro.core.resources.ic_info
import org.superdro.core.resources.ic_sync
import org.superdro.core.resources.thrs_actual_pitch_diameter
import org.superdro.core.resources.thrs_anvil_tilt_warning
import org.superdro.core.resources.thrs_bar_deflection_warning
import org.superdro.core.resources.thrs_bar_holder
import org.superdro.core.resources.thrs_bar_max_depth
import org.superdro.core.resources.thrs_bar_min_bore
import org.superdro.core.resources.thrs_bar_too_small
import org.superdro.core.resources.thrs_blind_bottoming
import org.superdro.core.resources.thrs_blind_hole_title
import org.superdro.core.resources.thrs_blind_plug
import org.superdro.core.resources.thrs_blind_taper
import org.superdro.core.resources.thrs_boring_bar_title
import org.superdro.core.resources.thrs_class
import org.superdro.core.resources.thrs_clearance_close
import org.superdro.core.resources.thrs_clearance_loose
import org.superdro.core.resources.thrs_clearance_normal
import org.superdro.core.resources.thrs_clearance_title
import org.superdro.core.resources.thrs_countersink_90
import org.superdro.core.resources.thrs_custom_wire_diameter
import org.superdro.core.resources.thrs_cut_tap_drill
import org.superdro.core.resources.thrs_cutter
import org.superdro.core.resources.thrs_cutter_full_form
import org.superdro.core.resources.thrs_cutter_partial
import org.superdro.core.resources.thrs_cutter_sharp_v
import org.superdro.core.resources.thrs_dial_any_line
import org.superdro.core.resources.thrs_dial_any_numbered
import org.superdro.core.resources.thrs_dial_any_point
import org.superdro.core.resources.thrs_dial_keep_closed
import org.superdro.core.resources.thrs_dial_opposite_numbered
import org.superdro.core.resources.thrs_dial_rule
import org.superdro.core.resources.thrs_dial_same_line
import org.superdro.core.resources.thrs_drill_actual_engagement
import org.superdro.core.resources.thrs_drill_outside_limits
import org.superdro.core.resources.thrs_drill_series_fractional
import org.superdro.core.resources.thrs_drill_series_letter
import org.superdro.core.resources.thrs_drill_series_metric
import org.superdro.core.resources.thrs_drill_series_number
import org.superdro.core.resources.thrs_drill_within_limits
import org.superdro.core.resources.thrs_entry_chamfer_diameter
import org.superdro.core.resources.thrs_external
import org.superdro.core.resources.thrs_form
import org.superdro.core.resources.thrs_form_acme
import org.superdro.core.resources.thrs_form_bsf
import org.superdro.core.resources.thrs_form_bspp
import org.superdro.core.resources.thrs_form_bspt
import org.superdro.core.resources.thrs_form_bsw
import org.superdro.core.resources.thrs_form_custom_imperial
import org.superdro.core.resources.thrs_form_custom_metric
import org.superdro.core.resources.thrs_form_iso_coarse
import org.superdro.core.resources.thrs_form_iso_fine
import org.superdro.core.resources.thrs_form_iso_trapezoidal
import org.superdro.core.resources.thrs_form_npt
import org.superdro.core.resources.thrs_form_tap_drill
import org.superdro.core.resources.thrs_form_unc
import org.superdro.core.resources.thrs_form_unef
import org.superdro.core.resources.thrs_form_unf
import org.superdro.core.resources.thrs_full_thread_depth
import org.superdro.core.resources.thrs_hand
import org.superdro.core.resources.thrs_hand_left
import org.superdro.core.resources.thrs_hand_right
import org.superdro.core.resources.thrs_height
import org.superdro.core.resources.thrs_infeed_flank
import org.superdro.core.resources.thrs_infeed_mode
import org.superdro.core.resources.thrs_infeed_modified_flank
import org.superdro.core.resources.thrs_infeed_radial
import org.superdro.core.resources.thrs_insert_preset
import org.superdro.core.resources.thrs_internal
import org.superdro.core.resources.thrs_internal_gauges_title
import org.superdro.core.resources.thrs_lead
import org.superdro.core.resources.thrs_lead_angle
import org.superdro.core.resources.thrs_length
import org.superdro.core.resources.thrs_major
import org.superdro.core.resources.thrs_measured_over_wires
import org.superdro.core.resources.thrs_minor
import org.superdro.core.resources.thrs_minor_go_nogo
import org.superdro.core.resources.thrs_mode
import org.superdro.core.resources.thrs_mode_single_point
import org.superdro.core.resources.thrs_mode_tap_drill
import org.superdro.core.resources.thrs_nearest_drills
import org.superdro.core.resources.thrs_new
import org.superdro.core.resources.thrs_no_tolerance
import org.superdro.core.resources.thrs_pass_col_compound
import org.superdro.core.resources.thrs_pass_col_kind
import org.superdro.core.resources.thrs_pass_col_pass
import org.superdro.core.resources.thrs_pass_col_step
import org.superdro.core.resources.thrs_pass_col_target
import org.superdro.core.resources.thrs_pass_col_total
import org.superdro.core.resources.thrs_pass_col_z_shift
import org.superdro.core.resources.thrs_pass_kind_finish
import org.superdro.core.resources.thrs_pass_kind_rough
import org.superdro.core.resources.thrs_pass_kind_spring
import org.superdro.core.resources.thrs_pitch
import org.superdro.core.resources.thrs_pitch_diameter
import org.superdro.core.resources.thrs_pitch_go_nogo
import org.superdro.core.resources.thrs_preset_a60
import org.superdro.core.resources.thrs_preset_ag60
import org.superdro.core.resources.thrs_preset_custom
import org.superdro.core.resources.thrs_preset_g60
import org.superdro.core.resources.thrs_preset_n60
import org.superdro.core.resources.thrs_preset_pitch_warning
import org.superdro.core.resources.thrs_relief_diameter
import org.superdro.core.resources.thrs_remaining_radial_depth
import org.superdro.core.resources.thrs_restore
import org.superdro.core.resources.thrs_restore_item
import org.superdro.core.resources.thrs_results
import org.superdro.core.resources.thrs_results_missing
import org.superdro.core.resources.thrs_roughing_passes
import org.superdro.core.resources.thrs_safe_max_rpm
import org.superdro.core.resources.thrs_schedule_summary
import org.superdro.core.resources.thrs_schedule_title
import org.superdro.core.resources.thrs_size
import org.superdro.core.resources.thrs_start_z
import org.superdro.core.resources.thrs_starts
import org.superdro.core.resources.thrs_sync_title
import org.superdro.core.resources.thrs_sync_z
import org.superdro.core.resources.thrs_tag
import org.superdro.core.resources.thrs_tag_default
import org.superdro.core.resources.thrs_tap_engagement
import org.superdro.core.resources.thrs_tap_results_title
import org.superdro.core.resources.thrs_theoretical_drill
import org.superdro.core.resources.thrs_tip_radius
import org.superdro.core.resources.thrs_title
import org.superdro.core.resources.thrs_tpi
import org.superdro.core.resources.thrs_type
import org.superdro.core.resources.thrs_wire_diameter
import org.superdro.core.resources.thrs_wire_measurement
import org.superdro.core.resources.thrs_wire_out_of_range
import org.superdro.core.resources.thrs_wire_title
import org.superdro.core.ui.functions.common.ChoiceRow
import org.superdro.core.ui.functions.common.ErrorBlock
import org.superdro.core.ui.functions.common.FieldGroupTitle
import org.superdro.core.ui.functions.common.FunctionScreen
import org.superdro.core.ui.functions.common.LengthFieldState
import org.superdro.core.ui.functions.common.NumberFieldState
import org.superdro.core.ui.functions.common.fieldTextTag
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.LocalTouchTargets
import org.superdro.core.ui.theme.accentTextFieldColors

/** The test tags of the thread setup screen. */
object ThreadTags {
    const val MODE = "thread-mode"
    const val FORM = "thread-form"
    const val SIZE = "thread-size"
    const val HAND = "thread-hand"
    const val STARTS = "thread-starts"
    const val INSERT_PRESET = "thread-insert-preset"
    const val INFEED_MODE = "thread-infeed-mode"
    const val RESTORE = "thread-restore"
    const val SYNC_Z = "thread-sync-z"
    const val RESULTS = "thread-results"
    const val TAP_RESULTS = "thread-tap-results"
    const val SCHEDULE = "thread-schedule"
    const val PRE_OP = "thread-pre-op"
    const val CUT_TO = "thread-cut-to"
    fun field(field: ThreadField): String = "thread-field-${field.name}"
    fun item(menu: String, index: Int): String = "$menu-item-$index"
}

/** The events of the thread setup screen. */
interface ThreadSetupEvents {
    fun onRestore(id: String)
    fun onMode(mode: ThreadToolMode) {}
    fun onInternal(internal: Boolean)
    fun onLeftHand(leftHand: Boolean) {}
    fun onStarts(starts: Int) {}
    fun onForm(form: ThreadForm)
    fun onSize(size: String)
    fun onClass(threadClass: ThreadClass)
    fun onCutter(cutter: ThreadCutter)
    fun onInsertPreset(preset: PartialInsertPreset) {}
    fun onInfeedMode(mode: ThreadInfeedMode) {}
    fun onText(field: ThreadField, text: String)
    fun onSyncStartZ()
    fun onCreate()
    fun onBack()
}

/** The text of a thread form. */
fun formText(form: ThreadForm): StringResource = when (form) {
    ThreadForm.IsoMetricCoarse -> Res.string.thrs_form_iso_coarse
    ThreadForm.IsoMetricFine -> Res.string.thrs_form_iso_fine
    ThreadForm.Unc -> Res.string.thrs_form_unc
    ThreadForm.Unf -> Res.string.thrs_form_unf
    ThreadForm.Unef -> Res.string.thrs_form_unef
    ThreadForm.Bsw -> Res.string.thrs_form_bsw
    ThreadForm.Bsf -> Res.string.thrs_form_bsf
    ThreadForm.Bspp -> Res.string.thrs_form_bspp
    ThreadForm.IsoTrapezoidal -> Res.string.thrs_form_iso_trapezoidal
    ThreadForm.Acme -> Res.string.thrs_form_acme
    ThreadForm.Npt -> Res.string.thrs_form_npt
    ThreadForm.Bspt -> Res.string.thrs_form_bspt
    ThreadForm.CustomMetric -> Res.string.thrs_form_custom_metric
    ThreadForm.CustomImperial -> Res.string.thrs_form_custom_imperial
}

/** Gives the string resource of this [ThreadForm]. */
fun ThreadForm.labelRes(): StringResource = formText(this)

private fun cutterText(cutter: ThreadCutter): StringResource = when (cutter) {
    ThreadCutter.SharpV -> Res.string.thrs_cutter_sharp_v
    ThreadCutter.FullForm -> Res.string.thrs_cutter_full_form
    ThreadCutter.Partial -> Res.string.thrs_cutter_partial
}

private fun presetText(preset: PartialInsertPreset): StringResource = when (preset) {
    PartialInsertPreset.Custom -> Res.string.thrs_preset_custom
    PartialInsertPreset.A60 -> Res.string.thrs_preset_a60
    PartialInsertPreset.G60 -> Res.string.thrs_preset_g60
    PartialInsertPreset.AG60 -> Res.string.thrs_preset_ag60
    PartialInsertPreset.N60 -> Res.string.thrs_preset_n60
}

private fun infeedText(mode: ThreadInfeedMode): StringResource = when (mode) {
    ThreadInfeedMode.Radial -> Res.string.thrs_infeed_radial
    ThreadInfeedMode.ModifiedFlank -> Res.string.thrs_infeed_modified_flank
    ThreadInfeedMode.Flank -> Res.string.thrs_infeed_flank
}

private fun dialRuleText(rule: ThreadDialRule): StringResource = when (rule) {
    ThreadDialRule.KeepHalfNutClosed -> Res.string.thrs_dial_keep_closed
    ThreadDialRule.AnyPoint -> Res.string.thrs_dial_any_point
    ThreadDialRule.AnyLine -> Res.string.thrs_dial_any_line
    ThreadDialRule.AnyNumberedLine -> Res.string.thrs_dial_any_numbered
    ThreadDialRule.OppositeNumberedLines -> Res.string.thrs_dial_opposite_numbered
    ThreadDialRule.SameLineOnly -> Res.string.thrs_dial_same_line
}

private fun passKindText(kind: PassKind): StringResource = when (kind) {
    PassKind.Roughing -> Res.string.thrs_pass_kind_rough
    PassKind.Finish -> Res.string.thrs_pass_kind_finish
    PassKind.Spring -> Res.string.thrs_pass_kind_spring
}

private fun drillSeriesText(series: DrillSeries): StringResource = when (series) {
    DrillSeries.Metric -> Res.string.thrs_drill_series_metric
    DrillSeries.Fractional -> Res.string.thrs_drill_series_fractional
    DrillSeries.Number -> Res.string.thrs_drill_series_number
    DrillSeries.Letter -> Res.string.thrs_drill_series_letter
}

/**
 * The thread setup screen: the inputs at the
 * start, and the live results (the pre-op and cut-to diameters, the class range, the three-wire
 * values, the pass schedule, or the tap & drill calculator) at the end in landscape or at the top
 * in portrait. In single-point mode, "Create" stores the thread and its points.
 */
@Composable
fun ThreadSetupScreen(state: ThreadSetupUiState, events: ThreadSetupEvents) {
    FunctionScreen(
        title = stringResource(Res.string.thrs_title),
        onBack = events::onBack,
        primaryLabel = if (state.mode == ThreadToolMode.SinglePoint) stringResource(Res.string.fn_create) else null,
        onPrimary = events::onCreate,
        primaryEnabled = state.loaded,
        errors = state.error?.let { ErrorBlock(null, listOf(it)) },
        preview = {
            if (state.mode == ThreadToolMode.TapAndDrill) {
                TapAndDrillResults(state)
            } else {
                Results(state)
            }
        },
    ) {
        val unit = stringResource(if (state.unit == ThreadUnit.Millimeter) Res.string.fn_unit_mm else Res.string.fn_unit_inch)
        if (state.isLathe) {
            FieldGroupTitle(stringResource(Res.string.thrs_mode))
            ChoiceRow(
                options = listOf(
                    ThreadToolMode.SinglePoint to stringResource(Res.string.thrs_mode_single_point),
                    ThreadToolMode.TapAndDrill to stringResource(Res.string.thrs_mode_tap_drill),
                ),
                selected = state.mode,
                onSelect = events::onMode,
                tag = ThreadTags.MODE,
            )
        }
        if (state.mode == ThreadToolMode.SinglePoint) {
            if (state.threads.isNotEmpty()) {
                val selected = state.threads.firstOrNull { it.id == state.restoredId }
                Menu(
                    label = stringResource(Res.string.thrs_restore),
                    value = selected?.let { stringResource(Res.string.thrs_restore_item, it.tag, it.size) } ?: stringResource(Res.string.thrs_new),
                    items = state.threads.map { choice -> stringResource(Res.string.thrs_restore_item, choice.tag, choice.size) },
                    selectedIndex = state.threads.indexOfFirst { it.id == state.restoredId },
                    onSelect = { index -> events.onRestore(state.threads[index].id) },
                    tag = ThreadTags.RESTORE,
                )
            }
            TextInput(stringResource(Res.string.thrs_tag), state.tag, { events.onText(ThreadField.Tag, it) }, ThreadTags.field(ThreadField.Tag), placeholder = state.defaultTag)
            FieldGroupTitle(stringResource(Res.string.thrs_type))
            ChoiceRow(
                options = listOf(true to stringResource(Res.string.thrs_internal), false to stringResource(Res.string.thrs_external)),
                selected = state.internal,
                onSelect = events::onInternal,
                tag = "thread-type",
            )
            FieldGroupTitle(stringResource(Res.string.thrs_hand))
            ChoiceRow(
                options = listOf(false to stringResource(Res.string.thrs_hand_right), true to stringResource(Res.string.thrs_hand_left)),
                selected = state.leftHand,
                onSelect = events::onLeftHand,
                tag = ThreadTags.HAND,
            )
            FieldGroupTitle(stringResource(Res.string.thrs_starts))
            ChoiceRow(
                options = (1..4).map { it to it.toString() },
                selected = state.starts.coerceIn(1, 4),
                onSelect = events::onStarts,
                tag = ThreadTags.STARTS,
            )
        }
        Menu(
            label = stringResource(Res.string.thrs_form),
            value = stringResource(formText(state.form)),
            items = ThreadForm.entries.map { form -> stringResource(formText(form)) },
            selectedIndex = state.form.ordinal,
            onSelect = { index -> events.onForm(ThreadForm.entries[index]) },
            tag = ThreadTags.FORM,
        )
        if (state.custom) {
            if (state.form == ThreadForm.CustomImperial) {
                NumberText(stringResource(Res.string.thrs_tpi), state.tpi, ThreadField.Tpi) { events.onText(ThreadField.Tpi, it) }
            } else {
                LengthText(stringResource(Res.string.thrs_pitch), state.pitch, unit, ThreadField.Pitch, events)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LengthText(stringResource(Res.string.thrs_minor), state.minor, unit, ThreadField.Minor, events, Modifier.weight(1f))
                LengthText(stringResource(Res.string.thrs_major), state.major, unit, ThreadField.Major, events, Modifier.weight(1f))
            }
        } else {
            Menu(
                label = stringResource(Res.string.thrs_size),
                value = state.size.orEmpty(),
                items = state.sizes,
                selectedIndex = state.sizes.indexOf(state.size),
                onSelect = { index -> events.onSize(state.sizes[index]) },
                tag = ThreadTags.SIZE,
            )
            if (state.classes.isNotEmpty() && (state.mode == ThreadToolMode.SinglePoint || state.internal)) {
                FieldGroupTitle(stringResource(Res.string.thrs_class))
                ChoiceRow(
                    options = state.classes.map { it to it.label },
                    selected = state.threadClass ?: state.classes.first(),
                    onSelect = events::onClass,
                    tag = "thread-class",
                )
            }
        }
        if (state.mode == ThreadToolMode.SinglePoint) {
            FieldGroupTitle(stringResource(Res.string.thrs_cutter))
            ChoiceRow(
                options = ThreadCutter.entries.map { cutter -> cutter to stringResource(cutterText(cutter)) },
                selected = state.cutter,
                onSelect = events::onCutter,
                tag = "thread-cutter",
            )
            if (state.cutter == ThreadCutter.Partial) {
                Menu(
                    label = stringResource(Res.string.thrs_insert_preset),
                    value = stringResource(presetText(state.insertPreset)),
                    items = PartialInsertPreset.entries.map { preset -> stringResource(presetText(preset)) },
                    selectedIndex = state.insertPreset.ordinal,
                    onSelect = { index -> events.onInsertPreset(PartialInsertPreset.entries[index]) },
                    tag = ThreadTags.INSERT_PRESET,
                )
                LengthText(stringResource(Res.string.thrs_tip_radius), state.tipRadius, unit, ThreadField.TipRadius, events)
            }
            FieldGroupTitle(stringResource(Res.string.thrs_infeed_mode))
            ChoiceRow(
                options = ThreadInfeedMode.entries.map { mode -> mode to stringResource(infeedText(mode)) },
                selected = state.infeedMode,
                onSelect = events::onInfeedMode,
                tag = ThreadTags.INFEED_MODE,
            )
            NumberText(stringResource(Res.string.thrs_roughing_passes), state.roughingPasses, ThreadField.RoughingPasses) {
                events.onText(ThreadField.RoughingPasses, it)
            }
            if (!state.internal) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LengthText(stringResource(Res.string.thrs_custom_wire_diameter), state.wireDiameter, unit, ThreadField.CustomWireDiameter, events, Modifier.weight(1f))
                    LengthText(stringResource(Res.string.thrs_measured_over_wires), state.measuredOverWires, unit, ThreadField.MeasuredOverWires, events, Modifier.weight(1f))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                LengthText(stringResource(Res.string.thrs_start_z), state.startZ, unit, ThreadField.StartZ, events, Modifier.weight(1f))
                OutlinedButton(
                    onClick = events::onSyncStartZ,
                    colors = AccentButtons.outlined(),
                    modifier = Modifier.padding(top = 4.dp).heightIn(min = LocalTouchTargets.current.button).testTag(ThreadTags.SYNC_Z),
                ) {
                    Icon(painterResource(Res.drawable.ic_sync), contentDescription = stringResource(Res.string.thrs_sync_z))
                }
            }
            LengthText(stringResource(Res.string.thrs_length), state.length, unit, ThreadField.Length, events)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LengthText(state.leftReliefLabel.resolve(), state.leftRelief, unit, ThreadField.LeftRelief, events, Modifier.weight(1f))
                LengthText(state.rightReliefLabel.resolve(), state.rightRelief, unit, ThreadField.RightRelief, events, Modifier.weight(1f))
            }
        } else {
            NumberText(stringResource(Res.string.thrs_tap_engagement), state.tapEngagement, ThreadField.TapEngagement) {
                events.onText(ThreadField.TapEngagement, it)
            }
            LengthText(stringResource(Res.string.thrs_full_thread_depth), state.blindHoleDepth, unit, ThreadField.BlindHoleDepth, events)
        }
    }
}

/** The live results of the single-point thread setup. */
@Composable
private fun Results(state: ThreadSetupUiState) {
    val unit = stringResource(if (state.unit == ThreadUnit.Millimeter) Res.string.fn_unit_mm else Res.string.fn_unit_inch)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxSize().testTag(ThreadTags.RESULTS),
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(Res.string.thrs_results), style = MaterialTheme.typography.titleMedium)
            val results = state.results
            if (results == null) {
                Text(stringResource(Res.string.thrs_results_missing), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            Result(results.preOpLabel.resolve(), "${results.preOp} $unit", results.preOpRange.resolve(), ThreadTags.PRE_OP, large = true)
            Result(results.cutToLabel.resolve(), "${results.cutTo} $unit", null, ThreadTags.CUT_TO, large = true)
            if (results.noTolerance) {
                NoticeRow(stringResource(Res.string.thrs_no_tolerance))
            }
            if (results.insertPresetPitchWarning) {
                NoticeRow(stringResource(Res.string.thrs_preset_pitch_warning))
            }
            if (results.needsAnvilTilt) {
                NoticeRow(stringResource(Res.string.thrs_anvil_tilt_warning))
            }
            HorizontalDivider()
            Result(stringResource(Res.string.thrs_minor), "${results.minor} $unit", null, "thread-minor")
            Result(stringResource(Res.string.thrs_major), "${results.major} $unit", null, "thread-major")
            Result(stringResource(Res.string.thrs_pitch_diameter), "${results.pitchDiameter} $unit", null, "thread-pitch-diameter")
            Result(stringResource(Res.string.thrs_height), "${results.height} $unit", null, "thread-height")
            if (results.starts > 1 && results.lead.isNotEmpty()) {
                Result(stringResource(Res.string.thrs_lead), "${results.lead} $unit", null, "thread-lead")
            }
            if (results.leadAngleDeg.isNotEmpty()) {
                Result(stringResource(Res.string.thrs_lead_angle), results.leadAngleDeg, null, "thread-lead-angle")
            }
            if (results.reliefDiameter.isNotEmpty()) {
                Result(stringResource(Res.string.thrs_relief_diameter), "${results.reliefDiameter} $unit", null, "thread-relief-diameter")
            }
            if (results.entryChamferDiameter.isNotEmpty()) {
                Result(stringResource(Res.string.thrs_entry_chamfer_diameter), "${results.entryChamferDiameter} $unit", null, "thread-entry-chamfer")
            }
            val wireDiameter = results.wireDiameter
            val wireMeasurement = results.wireMeasurement
            if (wireDiameter != null && wireMeasurement != null) {
                HorizontalDivider()
                Text(stringResource(Res.string.thrs_wire_title), style = MaterialTheme.typography.titleSmall)
                Result(stringResource(Res.string.thrs_wire_diameter), "$wireDiameter $unit", null, "thread-wire-diameter")
                if (!results.wireValid) {
                    NoticeRow(stringResource(Res.string.thrs_wire_out_of_range))
                }
                Result(stringResource(Res.string.thrs_wire_measurement), "$wireMeasurement $unit", results.wireRange?.resolve(), "thread-wire-measurement")
                results.actualPitchDiameter?.let { actual ->
                    Result(stringResource(Res.string.thrs_actual_pitch_diameter), "$actual $unit", null, "thread-actual-pitch-diameter")
                }
                results.remainingRadialDepth?.let { remaining ->
                    Result(stringResource(Res.string.thrs_remaining_radial_depth), "$remaining $unit", null, "thread-remaining-radial-depth")
                }
            }
            if (results.minorGo != null && results.minorNoGo != null && results.pitchGo != null && results.pitchNoGo != null) {
                HorizontalDivider()
                Text(stringResource(Res.string.thrs_internal_gauges_title), style = MaterialTheme.typography.titleSmall)
                Result(stringResource(Res.string.thrs_minor_go_nogo), "${results.minorGo} / ${results.minorNoGo} $unit", null, "thread-minor-go-nogo")
                Result(stringResource(Res.string.thrs_pitch_go_nogo), "${results.pitchGo} / ${results.pitchNoGo} $unit", null, "thread-pitch-go-nogo")
            }
            if (state.internal && (results.internalBarHolder != null || results.internalBarTooSmall)) {
                HorizontalDivider()
                Text(stringResource(Res.string.thrs_boring_bar_title), style = MaterialTheme.typography.titleSmall)
                if (results.internalBarTooSmall) {
                    NoticeRow(stringResource(Res.string.thrs_bar_too_small))
                } else if (results.internalBarHolder != null) {
                    Result(stringResource(Res.string.thrs_bar_holder), results.internalBarHolder, null, "thread-bar-holder")
                    results.internalBarMinBore?.let { minBore ->
                        Result(stringResource(Res.string.thrs_bar_min_bore), "$minBore $unit", null, "thread-bar-min-bore")
                    }
                    results.internalBarMaxDepth?.let { maxDepth ->
                        Result(stringResource(Res.string.thrs_bar_max_depth), "$maxDepth $unit", null, "thread-bar-max-depth")
                    }
                    if (results.internalBarDeflectionRisk) {
                        NoticeRow(stringResource(Res.string.thrs_bar_deflection_warning))
                    }
                }
            }
            HorizontalDivider()
            Text(stringResource(Res.string.thrs_sync_title), style = MaterialTheme.typography.titleSmall)
            Result(stringResource(Res.string.thrs_dial_rule), stringResource(dialRuleText(results.dialRule)), null, "thread-dial-rule")
            Result(stringResource(Res.string.thrs_safe_max_rpm), "${results.safeMaxRpm} RPM", null, "thread-safe-max-rpm")
            if (results.schedulePasses.isNotEmpty()) {
                HorizontalDivider()
                Column(Modifier.fillMaxWidth().testTag(ThreadTags.SCHEDULE), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(Res.string.thrs_schedule_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(
                            Res.string.thrs_schedule_summary,
                            results.schedulePasses.size,
                            results.scheduleInfeedAngleDeg,
                            results.scheduleTotalRadialDepth,
                            unit,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.fillMaxWidth()) {
                        Text(stringResource(Res.string.thrs_pass_col_pass), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(0.7f))
                        Text(stringResource(Res.string.thrs_pass_col_kind), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                        Text(stringResource(Res.string.thrs_pass_col_step), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                        Text(stringResource(Res.string.thrs_pass_col_total), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                        Text(stringResource(Res.string.thrs_pass_col_target), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1.2f))
                        Text(stringResource(Res.string.thrs_pass_col_compound), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                        Text(stringResource(Res.string.thrs_pass_col_z_shift), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    }
                    results.schedulePasses.forEach { pass ->
                        Row(Modifier.fillMaxWidth().testTag("thread-pass-${pass.number}")) {
                            Text(pass.number.toString(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.7f))
                            Text(stringResource(passKindText(pass.kind)), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Text(pass.stepRadial, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Text(pass.cumulativeRadial, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Text(pass.targetDiameter, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.2f))
                            Text(pass.compoundAdvance, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Text(pass.zShift, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

/** The live results of the Tap, Drill & Clearance mode. */
@Composable
private fun TapAndDrillResults(state: ThreadSetupUiState) {
    val unit = stringResource(if (state.unit == ThreadUnit.Millimeter) Res.string.fn_unit_mm else Res.string.fn_unit_inch)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxSize().testTag(ThreadTags.TAP_RESULTS),
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(Res.string.thrs_tap_results_title), style = MaterialTheme.typography.titleMedium)
            val tap = state.tapResults
            if (tap == null) {
                Text(stringResource(Res.string.thrs_results_missing), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            Text(stringResource(Res.string.thrs_cut_tap_drill), style = MaterialTheme.typography.titleSmall)
            Result(
                stringResource(Res.string.thrs_theoretical_drill),
                "${tap.cutTheoreticalDiameter} $unit",
                null,
                "thread-cut-tap-theoretical",
                large = true,
            )
            Result(
                stringResource(Res.string.thrs_nearest_drills),
                stringResource(
                    Res.string.thrs_drill_actual_engagement,
                    tap.cutClosestDrill.label,
                    tap.cutClosestDrill.diameter,
                    unit,
                    tap.cutClosestDrill.actualEngagementPercent,
                ),
                null,
                "thread-cut-tap-closest",
            )
            tap.cutWithinMinorLimits?.let { within ->
                NoticeRow(stringResource(if (within) Res.string.thrs_drill_within_limits else Res.string.thrs_drill_outside_limits))
            }
            tap.cutSeriesDrills.forEach { row ->
                Result(
                    stringResource(drillSeriesText(row.series)),
                    stringResource(
                        Res.string.thrs_drill_actual_engagement,
                        row.label,
                        row.diameter,
                        unit,
                        row.actualEngagementPercent,
                    ),
                    null,
                    "thread-drill-series-${row.series.name}",
                )
            }
            if (tap.formTheoreticalDiameter != null && tap.formClosestDrill != null) {
                HorizontalDivider()
                Text(stringResource(Res.string.thrs_form_tap_drill), style = MaterialTheme.typography.titleSmall)
                Result(
                    stringResource(Res.string.thrs_theoretical_drill),
                    "${tap.formTheoreticalDiameter} $unit",
                    null,
                    "thread-form-tap-theoretical",
                )
                Result(
                    stringResource(Res.string.thrs_nearest_drills),
                    stringResource(
                        Res.string.thrs_drill_actual_engagement,
                        tap.formClosestDrill.label,
                        tap.formClosestDrill.diameter,
                        unit,
                        tap.formClosestDrill.actualEngagementPercent,
                    ),
                    null,
                    "thread-form-tap-closest",
                )
            }
            HorizontalDivider()
            Text(stringResource(Res.string.thrs_clearance_title), style = MaterialTheme.typography.titleSmall)
            Result(stringResource(Res.string.thrs_clearance_close), "${tap.closeClearance} $unit", null, "thread-clearance-close")
            Result(stringResource(Res.string.thrs_clearance_normal), "${tap.normalClearance} $unit", null, "thread-clearance-normal")
            Result(stringResource(Res.string.thrs_clearance_loose), "${tap.looseClearance} $unit", null, "thread-clearance-loose")
            Result(stringResource(Res.string.thrs_countersink_90), "${tap.countersink90} $unit", null, "thread-countersink-90")
            if (tap.blindBottomingDepth != null && tap.blindPlugDepth != null && tap.blindTaperDepth != null) {
                HorizontalDivider()
                Text(stringResource(Res.string.thrs_blind_hole_title), style = MaterialTheme.typography.titleSmall)
                Result(stringResource(Res.string.thrs_blind_bottoming), "${tap.blindBottomingDepth} $unit", null, "thread-blind-bottoming")
                Result(stringResource(Res.string.thrs_blind_plug), "${tap.blindPlugDepth} $unit", null, "thread-blind-plug")
                Result(stringResource(Res.string.thrs_blind_taper), "${tap.blindTaperDepth} $unit", null, "thread-blind-taper")
            }
        }
    }
}

@Composable
private fun NoticeRow(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(Res.drawable.ic_info), contentDescription = null, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Result(label: String, value: String, range: String?, tag: String, large: Boolean = false) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag(tag)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(value, style = if (large) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium)
        }
        range?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.End)) }
    }
}

@Composable
private fun LengthText(label: String, state: LengthFieldState, unit: String, field: ThreadField, events: ThreadSetupEvents, modifier: Modifier = Modifier.fillMaxWidth()) {
    OutlinedTextField(
        value = state.text,
        onValueChange = { events.onText(field, it) },
        label = { Text(label) },
        suffix = { Text(unit) },
        isError = state.error != null,
        supportingText = state.error?.let { error -> { Text(error.resolve()) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
        colors = accentTextFieldColors(),
        modifier = modifier.heightIn(min = LocalTouchTargets.current.button).testTag(fieldTextTag(ThreadTags.field(field))),
    )
}

@Composable
private fun NumberText(label: String, state: NumberFieldState, field: ThreadField = ThreadField.Tpi, onText: (String) -> Unit) {
    OutlinedTextField(
        value = state.text,
        onValueChange = onText,
        label = { Text(label) },
        isError = state.error != null,
        supportingText = state.error?.let { error -> { Text(error.resolve()) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
        colors = accentTextFieldColors(),
        modifier = Modifier.fillMaxWidth().heightIn(min = LocalTouchTargets.current.button).testTag(fieldTextTag(ThreadTags.field(field))),
    )
}

@Composable
private fun TextInput(label: String, value: String, onChange: (String) -> Unit, tag: String, placeholder: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        supportingText = { Text(stringResource(Res.string.thrs_tag_default, placeholder)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        colors = accentTextFieldColors(),
        modifier = Modifier.fillMaxWidth().heightIn(min = LocalTouchTargets.current.button).testTag(fieldTextTag(tag)),
    )
}

/** A choice from a list: a button with the label and the value, and a menu with the items. */
@Composable
private fun Menu(label: String, value: String, items: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, tag: String) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(
                onClick = { open = true },
                colors = AccentButtons.outlined(),
                modifier = Modifier.fillMaxWidth().heightIn(min = LocalTouchTargets.current.button).testTag(tag)
                    .semantics { contentDescription = "$label: $value" },
            ) {
                Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                items.forEachIndexed { index, item ->
                    DropdownMenuItem(
                        text = { Text(item) },
                        leadingIcon = if (index == selectedIndex) {
                            { Icon(painterResource(Res.drawable.ic_check), contentDescription = null) }
                        } else {
                            null
                        },
                        onClick = {
                            open = false
                            onSelect(index)
                        },
                        modifier = Modifier.testTag(ThreadTags.item(tag, index)),
                    )
                }
            }
        }
    }
}
