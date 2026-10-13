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
import org.superdro.core.functions.thread.ThreadUnit
import org.superdro.core.functions.thread.label
import org.superdro.core.model.ThreadClass
import org.superdro.core.model.ThreadCutter
import org.superdro.core.model.ThreadForm
import org.superdro.core.resources.Res
import org.superdro.core.resources.fn_create
import org.superdro.core.resources.fn_unit_inch
import org.superdro.core.resources.fn_unit_mm
import org.superdro.core.resources.ic_check
import org.superdro.core.resources.ic_info
import org.superdro.core.resources.ic_sync
import org.superdro.core.resources.thrs_class
import org.superdro.core.resources.thrs_cutter
import org.superdro.core.resources.thrs_cutter_full_form
import org.superdro.core.resources.thrs_cutter_partial
import org.superdro.core.resources.thrs_cutter_sharp_v
import org.superdro.core.resources.thrs_external
import org.superdro.core.resources.thrs_form
import org.superdro.core.resources.thrs_form_custom_imperial
import org.superdro.core.resources.thrs_form_custom_metric
import org.superdro.core.resources.thrs_form_iso_coarse
import org.superdro.core.resources.thrs_form_iso_fine
import org.superdro.core.resources.thrs_form_unc
import org.superdro.core.resources.thrs_form_unef
import org.superdro.core.resources.thrs_form_unf
import org.superdro.core.resources.thrs_height
import org.superdro.core.resources.thrs_internal
import org.superdro.core.resources.thrs_length
import org.superdro.core.resources.thrs_major
import org.superdro.core.resources.thrs_minor
import org.superdro.core.resources.thrs_new
import org.superdro.core.resources.thrs_no_tolerance
import org.superdro.core.resources.thrs_pitch
import org.superdro.core.resources.thrs_pitch_diameter
import org.superdro.core.resources.thrs_restore
import org.superdro.core.resources.thrs_restore_item
import org.superdro.core.resources.thrs_results
import org.superdro.core.resources.thrs_results_missing
import org.superdro.core.resources.thrs_size
import org.superdro.core.resources.thrs_start_z
import org.superdro.core.resources.thrs_sync_z
import org.superdro.core.resources.thrs_tag
import org.superdro.core.resources.thrs_tag_default
import org.superdro.core.resources.thrs_tip_radius
import org.superdro.core.resources.thrs_title
import org.superdro.core.resources.thrs_tpi
import org.superdro.core.resources.thrs_type
import org.superdro.core.resources.thrs_wire_diameter
import org.superdro.core.resources.thrs_wire_measurement
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
    const val FORM = "thread-form"
    const val SIZE = "thread-size"
    const val RESTORE = "thread-restore"
    const val SYNC_Z = "thread-sync-z"
    const val RESULTS = "thread-results"
    const val PRE_OP = "thread-pre-op"
    const val CUT_TO = "thread-cut-to"
    fun field(field: ThreadField): String = "thread-field-${field.name}"
    fun item(menu: String, index: Int): String = "$menu-item-$index"
}

/** The events of the thread setup screen. */
interface ThreadSetupEvents {
    fun onRestore(id: String)
    fun onInternal(internal: Boolean)
    fun onForm(form: ThreadForm)
    fun onSize(size: String)
    fun onClass(threadClass: ThreadClass)
    fun onCutter(cutter: ThreadCutter)
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
    ThreadForm.CustomMetric -> Res.string.thrs_form_custom_metric
    ThreadForm.CustomImperial -> Res.string.thrs_form_custom_imperial
}

private fun cutterText(cutter: ThreadCutter): StringResource = when (cutter) {
    ThreadCutter.SharpV -> Res.string.thrs_cutter_sharp_v
    ThreadCutter.FullForm -> Res.string.thrs_cutter_full_form
    ThreadCutter.Partial -> Res.string.thrs_cutter_partial
}

/**
 * The thread setup screen: the inputs at the
 * start, and the live results (the pre-op and cut-to diameters, the class range, and the
 * three-wire values) at the end in landscape or at the top in portrait. "Create" stores the
 * thread and its points.
 */
@Composable
fun ThreadSetupScreen(state: ThreadSetupUiState, events: ThreadSetupEvents) {
    FunctionScreen(
        title = stringResource(Res.string.thrs_title),
        onBack = events::onBack,
        primaryLabel = stringResource(Res.string.fn_create),
        onPrimary = events::onCreate,
        primaryEnabled = state.loaded,
        errors = state.error?.let { ErrorBlock(null, listOf(it)) },
        preview = { Results(state) },
    ) {
        val unit = stringResource(if (state.unit == ThreadUnit.Millimeter) Res.string.fn_unit_mm else Res.string.fn_unit_inch)
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
                NumberText(stringResource(Res.string.thrs_tpi), state.tpi) { events.onText(ThreadField.Tpi, it) }
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
            if (state.classes.isNotEmpty()) {
                FieldGroupTitle(stringResource(Res.string.thrs_class))
                ChoiceRow(
                    options = state.classes.map { it to it.label },
                    selected = state.threadClass ?: state.classes.first(),
                    onSelect = events::onClass,
                    tag = "thread-class",
                )
            }
        }
        FieldGroupTitle(stringResource(Res.string.thrs_cutter))
        ChoiceRow(
            options = ThreadCutter.entries.map { cutter -> cutter to stringResource(cutterText(cutter)) },
            selected = state.cutter,
            onSelect = events::onCutter,
            tag = "thread-cutter",
        )
        if (state.cutter == ThreadCutter.Partial) {
            LengthText(stringResource(Res.string.thrs_tip_radius), state.tipRadius, unit, ThreadField.TipRadius, events)
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
    }
}

/** The live results of the thread setup. */
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(Res.drawable.ic_info), contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(stringResource(Res.string.thrs_no_tolerance), style = MaterialTheme.typography.bodyMedium)
                }
            }
            HorizontalDivider()
            Result(stringResource(Res.string.thrs_minor), "${results.minor} $unit", null, "thread-minor")
            Result(stringResource(Res.string.thrs_major), "${results.major} $unit", null, "thread-major")
            Result(stringResource(Res.string.thrs_pitch_diameter), "${results.pitchDiameter} $unit", null, "thread-pitch-diameter")
            Result(stringResource(Res.string.thrs_height), "${results.height} $unit", null, "thread-height")
            val wireDiameter = results.wireDiameter
            val wireMeasurement = results.wireMeasurement
            if (wireDiameter != null && wireMeasurement != null) {
                HorizontalDivider()
                Text(stringResource(Res.string.thrs_wire_title), style = MaterialTheme.typography.titleSmall)
                Result(stringResource(Res.string.thrs_wire_diameter), "$wireDiameter $unit", null, "thread-wire-diameter")
                Result(stringResource(Res.string.thrs_wire_measurement), "$wireMeasurement $unit", results.wireRange?.resolve(), "thread-wire-measurement")
            }
        }
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
private fun NumberText(label: String, state: NumberFieldState, onText: (String) -> Unit) {
    OutlinedTextField(
        value = state.text,
        onValueChange = onText,
        label = { Text(label) },
        isError = state.error != null,
        supportingText = state.error?.let { error -> { Text(error.resolve()) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
        colors = accentTextFieldColors(),
        modifier = Modifier.fillMaxWidth().heightIn(min = LocalTouchTargets.current.button).testTag(fieldTextTag(ThreadTags.field(ThreadField.Tpi))),
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
