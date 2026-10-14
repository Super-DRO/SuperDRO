package org.superdro.core.ui.adapter.encoder.map

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.functions.errormap.MapKind
import org.superdro.core.functions.errormap.MapSummary
import org.superdro.core.resources.Res
import org.superdro.core.resources.encoder_backlash_map_title
import org.superdro.core.resources.encoder_counts_value
import org.superdro.core.resources.encoder_delete
import org.superdro.core.resources.encoder_error_map_title
import org.superdro.core.resources.encoder_loading
import org.superdro.core.resources.encoder_map_add
import org.superdro.core.resources.encoder_map_clear
import org.superdro.core.resources.encoder_map_clear_confirm
import org.superdro.core.resources.encoder_map_clear_text
import org.superdro.core.resources.encoder_map_clear_title
import org.superdro.core.resources.encoder_map_column_backlash
import org.superdro.core.resources.encoder_map_column_correction
import org.superdro.core.resources.encoder_map_column_position
import org.superdro.core.resources.encoder_map_curve_description
import org.superdro.core.resources.encoder_map_empty
import org.superdro.core.resources.encoder_map_export
import org.superdro.core.resources.encoder_map_import
import org.superdro.core.resources.encoder_map_import_problems
import org.superdro.core.resources.encoder_map_largest
import org.superdro.core.resources.encoder_map_made_by
import org.superdro.core.resources.encoder_map_made_on
import org.superdro.core.resources.encoder_map_passes
import org.superdro.core.resources.encoder_map_paste
import org.superdro.core.resources.encoder_map_plus_minus
import org.superdro.core.resources.encoder_map_problems
import org.superdro.core.resources.encoder_map_read_only
import org.superdro.core.resources.encoder_map_replace
import org.superdro.core.resources.encoder_map_replace_text
import org.superdro.core.resources.encoder_map_replace_title
import org.superdro.core.resources.encoder_map_row_title
import org.superdro.core.resources.encoder_map_row_new
import org.superdro.core.resources.encoder_map_save
import org.superdro.core.resources.encoder_map_source
import org.superdro.core.resources.encoder_map_spacing
import org.superdro.core.resources.encoder_map_span
import org.superdro.core.resources.encoder_map_span_value
import org.superdro.core.resources.encoder_map_spread
import org.superdro.core.resources.encoder_map_spread_value
import org.superdro.core.resources.encoder_map_tab_curve
import org.superdro.core.resources.encoder_map_tab_points
import org.superdro.core.resources.encoder_map_tab_summary
import org.superdro.core.resources.encoder_map_unsaved
import org.superdro.core.resources.encoder_map_wizard
import org.superdro.core.resources.encoder_map_wizard_confirm
import org.superdro.core.resources.encoder_map_wizard_text
import org.superdro.core.resources.encoder_map_wizard_title
import org.superdro.core.resources.encoder_no_adapter
import org.superdro.core.resources.encoder_no_adapter_offline
import org.superdro.core.resources.frame_cancel
import org.superdro.core.resources.frame_done
import org.superdro.core.resources.ic_delete
import org.superdro.core.resources.ic_encoder_download
import org.superdro.core.resources.ic_encoder_paste
import org.superdro.core.resources.ic_encoder_upload
import org.superdro.core.resources.ic_science
import org.superdro.core.ui.adapter.AdapterTexts
import org.superdro.core.ui.adapter.common.NumberField
import org.superdro.core.ui.adapter.common.SectionTitle
import org.superdro.core.ui.adapter.common.StatusKind
import org.superdro.core.ui.adapter.common.StatusLine
import org.superdro.core.ui.adapter.common.ValueRow
import org.superdro.core.ui.adapter.encoder.EncoderFormat
import org.superdro.core.ui.components.uniqueKeys
import org.superdro.core.ui.navigation.ScreenScaffold
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.LocalTouchTargets
import org.superdro.core.ui.theme.accentColor

/** The test tags of the map editor. */
object MapEditorTags {
    const val SAVE: String = "map-save"
    const val ADD: String = "map-add"
    const val IMPORT: String = "map-import"
    const val EXPORT: String = "map-export"
    const val PASTE: String = "map-paste"
    const val CLEAR: String = "map-clear"
    const val WIZARD: String = "map-wizard"
    const val ROW_POSITION: String = "map-row-position"
    const val ROW_VALUE: String = "map-row-value"
    const val ROW_DONE: String = "map-row-done"
    const val CURVE: String = "map-curve"

    /** The tag of the table row [index]. */
    fun row(index: Int): String = "map-row-$index"
}

/** The events of the map editor. */
interface MapEditorEvents {
    fun onTab(tab: MapEditorTab)
    fun onAddRow()
    fun onEditRow(index: Int)
    fun onRowPosition(text: String)
    fun onRowValue(text: String)
    fun onSaveRow()
    fun onDeleteRow()
    fun onCancelRow()
    fun onImport()
    fun onPaste()
    fun onExport()
    fun onClear()
    fun onSave()
    fun onConfirmReplace()
    fun onCancelReplace()
    fun onConfirmClear()
    fun onCancelClear()
    fun onWizard()
    fun onConfirmWizard()
    fun onCancelWizard()
}

/** The [MapEditorEvents] of a presenter. [readClipboard] gives the text of the clipboard. */
class PresenterMapEditorEvents(
    private val presenter: MapEditorPresenter,
    private val readClipboard: () -> String?,
    private val openWizard: () -> Unit,
) : MapEditorEvents {
    override fun onTab(tab: MapEditorTab) = presenter.onTab(tab)
    override fun onAddRow() = presenter.onAddRow()
    override fun onEditRow(index: Int) = presenter.onEditRow(index)
    override fun onRowPosition(text: String) = presenter.onRowPosition(text)
    override fun onRowValue(text: String) = presenter.onRowValue(text)
    override fun onSaveRow() = presenter.onSaveRow()
    override fun onDeleteRow() = presenter.onDeleteRow()
    override fun onCancelRow() = presenter.onCancelRow()
    override fun onImport() = presenter.onImport()
    override fun onPaste() = presenter.onPaste(readClipboard())
    override fun onExport() = presenter.onExport()
    override fun onClear() = presenter.onClear()
    override fun onSave() = presenter.onSave()
    override fun onConfirmReplace() = presenter.onConfirmReplace()
    override fun onCancelReplace() = presenter.onCancelReplace()
    override fun onConfirmClear() = presenter.onConfirmClear()
    override fun onCancelClear() = presenter.onCancelClear()
    override fun onWizard() {
        if (presenter.onWizard()) openWizard()
    }
    override fun onConfirmWizard() {
        if (presenter.onConfirmWizard()) openWizard()
    }
    override fun onCancelWizard() = presenter.onCancelWizard()
}

/**
 * The map editor of one input: the table, the summary, and the curve, with import,
 * export, paste, clear, and save. It has no cloud button.
 */
@Composable
fun MapEditorScreen(state: MapEditorUiState, events: MapEditorEvents, onBack: () -> Unit) {
    val titleRes = if (state.kind == MapKind.Error) Res.string.encoder_error_map_title else Res.string.encoder_backlash_map_title
    ScreenScaffold(
        title = stringResource(titleRes, AdapterTexts.encoderInputName(state.input).resolve()),
        onBack = onBack,
        actions = {
            if (state.available) {
                if (state.wizardAvailable) ToolbarIcon(Res.drawable.ic_science, Res.string.encoder_map_wizard, MapEditorTags.WIZARD, events::onWizard)
                ToolbarIcon(Res.drawable.ic_encoder_download, Res.string.encoder_map_import, MapEditorTags.IMPORT, events::onImport)
                ToolbarIcon(Res.drawable.ic_encoder_upload, Res.string.encoder_map_export, MapEditorTags.EXPORT, events::onExport)
                ToolbarIcon(Res.drawable.ic_encoder_paste, Res.string.encoder_map_paste, MapEditorTags.PASTE, events::onPaste)
                ToolbarIcon(Res.drawable.ic_delete, Res.string.encoder_map_clear, MapEditorTags.CLEAR, events::onClear)
            }
        },
    ) {
        when {
            !state.loaded -> Box(Modifier.padding(24.dp)) { StatusLine(StatusKind.Progress, stringResource(Res.string.encoder_loading)) }
            !state.available -> Box(Modifier.padding(24.dp)) {
                StatusLine(StatusKind.Warning, stringResource(if (state.online) Res.string.encoder_no_adapter else Res.string.encoder_no_adapter_offline))
            }
            else -> EditorBody(state, events)
        }
    }
    Dialogs(state, events)
}

@Composable
private fun ToolbarIcon(icon: DrawableResource, label: StringResource, tag: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.testTag(tag)) {
        Icon(painterResource(icon), contentDescription = stringResource(label))
    }
}

@Composable
private fun EditorBody(state: MapEditorUiState, events: MapEditorEvents) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            if (state.readOnly) StatusLine(StatusKind.Info, stringResource(Res.string.encoder_map_read_only))
            if (state.dirty) StatusLine(StatusKind.Warning, stringResource(Res.string.encoder_map_unsaved))
            state.notices.forEach { StatusLine(StatusKind.Info, it.resolve()) }
            if (state.importProblems.isNotEmpty()) {
                Text(stringResource(Res.string.encoder_map_import_problems), style = MaterialTheme.typography.titleSmall)
                state.importProblems.take(MAX_SHOWN_PROBLEMS).forEach { StatusLine(StatusKind.Error, it.resolve()) }
            }
            if (state.problems.isNotEmpty()) {
                Text(stringResource(Res.string.encoder_map_problems), style = MaterialTheme.typography.titleSmall)
                state.problems.take(MAX_SHOWN_PROBLEMS).forEach { StatusLine(StatusKind.Error, it.resolve()) }
            }
        }
        val accent = accentColor()
        PrimaryTabRow(
            selectedTabIndex = state.tab.ordinal,
            indicator = {
                // A thin line: in light mode not `primary`.
                TabRowDefaults.PrimaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(state.tab.ordinal, matchContentSize = true),
                    width = Dp.Unspecified,
                    color = accent,
                )
            },
        ) {
            MapEditorTab.entries.forEach { tab ->
                Tab(
                    selected = state.tab == tab,
                    onClick = { events.onTab(tab) },
                    text = { Text(stringResource(tabLabel(tab))) },
                    selectedContentColor = accent,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (state.tab) {
                MapEditorTab.Points -> PointsTable(state, events)
                MapEditorTab.Summary -> SummaryTab(state.summary)
                MapEditorTab.Curve -> CurveTab(state)
            }
        }
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = events::onAddRow,
                enabled = !state.readOnly,
                colors = AccentButtons.outlined(),
                modifier = Modifier.heightIn(min = LocalTouchTargets.current.button).testTag(MapEditorTags.ADD),
            ) { Text(stringResource(Res.string.encoder_map_add)) }
            Button(
                onClick = events::onSave,
                enabled = state.dirty,
                modifier = Modifier.heightIn(min = LocalTouchTargets.current.button).testTag(MapEditorTags.SAVE),
                colors = AccentButtons.filled(),
            ) { Text(stringResource(Res.string.encoder_map_save)) }
        }
    }
}

private fun tabLabel(tab: MapEditorTab): StringResource = when (tab) {
    MapEditorTab.Points -> Res.string.encoder_map_tab_points
    MapEditorTab.Summary -> Res.string.encoder_map_tab_summary
    MapEditorTab.Curve -> Res.string.encoder_map_tab_curve
}

@Composable
private fun PointsTable(state: MapEditorUiState, events: MapEditorEvents) {
    if (state.rows.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp)) { StatusLine(StatusKind.Info, stringResource(Res.string.encoder_map_empty)) }
        return
    }
    val valueLabel = stringResource(if (state.kind == MapKind.Error) Res.string.encoder_map_column_correction else Res.string.encoder_map_column_backlash)
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text(stringResource(Res.string.encoder_map_column_position), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(valueLabel, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        }
        HorizontalDivider()
        LazyColumn(Modifier.widthIn(max = 640.dp).fillMaxWidth().weight(1f)) {
            val keys = uniqueKeys(state.rows) { row -> row.position.toString() }
            itemsIndexed(state.rows, key = { index, _ -> keys[index] }) { index, row ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(enabled = !state.readOnly) { events.onEditRow(index) }
                        .padding(horizontal = 24.dp)
                        .testTag(MapEditorTags.row(index)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(row.position.toString(), fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                    Text(row.value.toString(), fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SummaryTab(summary: MapSummary) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        SummaryRows(summary)
    }
}

@Composable
private fun SummaryRows(summary: MapSummary) {
    Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)) {
        val dash = "—"
        ValueRow(
            stringResource(Res.string.encoder_map_span),
            if (summary.firstPosition == null) dash else stringResource(Res.string.encoder_map_span_value, summary.firstPosition ?: 0L, summary.lastPosition ?: 0L),
        )
        ValueRow(stringResource(Res.string.encoder_map_spacing), summary.typicalSpacing?.let { stringResource(Res.string.encoder_counts_value, it.toString()) } ?: dash)
        ValueRow(
            stringResource(Res.string.encoder_map_largest),
            summary.largestCorrection?.let { stringResource(Res.string.encoder_map_plus_minus, it.toString()) } ?: dash,
        )
        val before = summary.errorBeforeHalf
        val after = summary.errorAfterHalf
        if (before != null && after != null) {
            ValueRow(stringResource(Res.string.encoder_map_spread), stringResource(Res.string.encoder_map_spread_value, EncoderFormat.number(before, 1), EncoderFormat.number(after, 1)))
        }
        val source = summary.source
        if (source.generatedBy != null || source.generatedOn != null || source.passes != null || source.notes.isNotEmpty()) {
            SectionTitle(stringResource(Res.string.encoder_map_source))
            source.generatedBy?.let { Text(stringResource(Res.string.encoder_map_made_by, it)) }
            source.generatedOn?.let { Text(stringResource(Res.string.encoder_map_made_on, it)) }
            source.passes?.let { Text(stringResource(Res.string.encoder_map_passes, it)) }
            source.notes.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun CurveTab(state: MapEditorUiState) {
    if (state.rows.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp)) { StatusLine(StatusKind.Info, stringResource(Res.string.encoder_map_empty)) }
        return
    }
    val series = listOf(ChartSeries(state.rows.map { it.position.toDouble() to it.value.toDouble() }, accentColor(), line = true))
    MapChart(
        series = series,
        axisColor = MaterialTheme.colorScheme.outline,
        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        description = stringResource(Res.string.encoder_map_curve_description, state.rows.size),
        modifier = Modifier.fillMaxSize().padding(24.dp).testTag(MapEditorTags.CURVE),
    )
}

@Composable
private fun Dialogs(state: MapEditorUiState, events: MapEditorEvents) {
    state.rowEdit?.let { edit ->
        AlertDialog(
            onDismissRequest = events::onCancelRow,
            title = { Text(stringResource(if (edit.index == null) Res.string.encoder_map_row_new else Res.string.encoder_map_row_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(
                        label = stringResource(Res.string.encoder_map_column_position),
                        value = edit.position,
                        onValueChange = events::onRowPosition,
                        decimal = false,
                        tag = MapEditorTags.ROW_POSITION,
                    )
                    NumberField(
                        label = stringResource(if (state.kind == MapKind.Error) Res.string.encoder_map_column_correction else Res.string.encoder_map_column_backlash),
                        value = edit.value,
                        onValueChange = events::onRowValue,
                        error = edit.error,
                        decimal = false,
                        tag = MapEditorTags.ROW_VALUE,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = events::onSaveRow, colors = AccentButtons.text(), modifier = Modifier.testTag(MapEditorTags.ROW_DONE)) {
                    Text(stringResource(Res.string.frame_done))
                }
            },
            dismissButton = {
                Row {
                    if (edit.index != null) {
                        TextButton(onClick = events::onDeleteRow, colors = AccentButtons.text()) { Text(stringResource(Res.string.encoder_delete)) }
                        Spacer(Modifier.size(8.dp))
                    }
                    TextButton(onClick = events::onCancelRow, colors = AccentButtons.text()) { Text(stringResource(Res.string.frame_cancel)) }
                }
            },
        )
    }
    state.replaceQuestion?.let { question ->
        ConfirmDialog(
            title = stringResource(Res.string.encoder_map_replace_title),
            text = stringResource(Res.string.encoder_map_replace_text, question.currentCount, question.newCount, question.firstPosition, question.lastPosition),
            confirm = stringResource(Res.string.encoder_map_replace),
            onConfirm = events::onConfirmReplace,
            onCancel = events::onCancelReplace,
        )
    }
    if (state.confirmClear) {
        ConfirmDialog(
            title = stringResource(Res.string.encoder_map_clear_title),
            text = stringResource(Res.string.encoder_map_clear_text),
            confirm = stringResource(Res.string.encoder_map_clear_confirm),
            onConfirm = events::onConfirmClear,
            onCancel = events::onCancelClear,
        )
    }
    if (state.confirmWizard) {
        ConfirmDialog(
            title = stringResource(Res.string.encoder_map_wizard_title),
            text = stringResource(Res.string.encoder_map_wizard_text),
            confirm = stringResource(Res.string.encoder_map_wizard_confirm),
            onConfirm = events::onConfirmWizard,
            onCancel = events::onCancelWizard,
        )
    }
}

/** A question with a confirm button and "Cancel". */
@Composable
internal fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onCancel: () -> Unit, cancel: String? = null) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm, colors = AccentButtons.text()) { Text(confirm) } },
        dismissButton = {
            TextButton(onClick = onCancel, colors = AccentButtons.text()) { Text(cancel ?: stringResource(Res.string.frame_cancel)) }
        },
    )
}

private const val MAX_SHOWN_PROBLEMS = 8
