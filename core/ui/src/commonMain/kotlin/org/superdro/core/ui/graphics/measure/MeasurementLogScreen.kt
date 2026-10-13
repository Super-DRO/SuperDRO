package org.superdro.core.ui.graphics.measure

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.functions.measurement.InspectionExport
import org.superdro.core.functions.measurement.InspectionSheetLabels
import org.superdro.core.functions.measurement.LogTimestamp
import org.superdro.core.functions.measurement.MeasurementFormat
import org.superdro.core.functions.measurement.MeasurementResult
import org.superdro.core.functions.measurement.PointNote
import org.superdro.core.functions.measurement.ResultKey
import org.superdro.core.model.MeasurementLogEntry
import org.superdro.core.resources.Res
import org.superdro.core.resources.ic_delete
import org.superdro.core.resources.ic_measure_export
import org.superdro.core.resources.ic_measure_log
import org.superdro.core.resources.ic_warning
import org.superdro.core.resources.measure_cancel
import org.superdro.core.resources.measure_log_clear
import org.superdro.core.resources.measure_log_clear_confirm
import org.superdro.core.resources.measure_log_clear_message
import org.superdro.core.resources.measure_log_clear_title
import org.superdro.core.resources.measure_log_col_function
import org.superdro.core.resources.measure_log_col_note
import org.superdro.core.resources.measure_log_col_seq
import org.superdro.core.resources.measure_log_col_system
import org.superdro.core.resources.measure_log_col_time
import org.superdro.core.resources.measure_log_col_values
import org.superdro.core.resources.measure_log_export_csv
import org.superdro.core.resources.measure_log_export_failed
import org.superdro.core.resources.measure_log_export_sheet
import org.superdro.core.resources.measure_log_exported
import org.superdro.core.resources.measure_log_none
import org.superdro.core.resources.measure_log_title
import org.superdro.core.resources.measure_log_warn
import org.superdro.core.resources.measure_sheet_note
import org.superdro.core.resources.measure_sheet_system
import org.superdro.core.resources.measure_sheet_title
import org.superdro.core.resources.measure_sheet_workspace
import org.superdro.core.ui.graphics.GraphicsDependencies
import org.superdro.core.ui.navigation.ScreenScaffold
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.readout.TabReadout
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.theme.AccentButtons

/** The test tag of "Export as CSV". */
const val LOG_EXPORT_CSV_TAG: String = "measure-log-export-csv"

/** The test tag of "Export as a printed inspection sheet". */
const val LOG_EXPORT_SHEET_TAG: String = "measure-log-export-sheet"

/** The test tag of "Clear the log". */
const val LOG_CLEAR_TAG: String = "measure-log-clear"

/** The test tag of "Clear" in the clear question. */
const val LOG_CLEAR_CONFIRM_TAG: String = "measure-log-clear-confirm"

/**
 * One row of the inspection log. The values use the display format
 * that the entry stores.
 *
 * @property values The value text of each result, in the order of the function.
 * @property systemNotes The system notes in lower case, separated by "; ", or "".
 */
@Immutable
data class LogRowUiState(
    val id: String,
    val sequence: Int,
    val timeText: String,
    val title: String,
    val values: List<Pair<ResultKey, String>>,
    val note: String?,
    val systemNotes: String,
) {
    /** True when the row has system notes: the row shows the warning icon and text. */
    val warn: Boolean get() = systemNotes.isNotEmpty()
}

/** The state of the inspection log screen. [clearAsked] is true while the clear question shows. */
@Immutable
data class MeasurementLogUiState(val rows: List<LogRowUiState> = emptyList(), val clearAsked: Boolean = false)

/**
 * The presenter of the inspection log screen of the active workspace, on both mill types. The
 * rows, the CSV export, the text inspection sheet, and the clear of the log.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeasurementLogPresenter(
    private val readout: TabReadout,
    private val deps: GraphicsDependencies,
) : ScopedPresenter(deps.context) {

    private val clearAsked = MutableStateFlow(false)
    private val noticeFlow = MutableSharedFlow<UiText>(extraBufferCapacity = 8)

    /** The texts that the screen shows in its snackbar. */
    val notices: SharedFlow<UiText> = noticeFlow.asSharedFlow()

    private val entries: StateFlow<List<MeasurementLogEntry>> = readout.frame
        .map { frame -> frame?.workspace?.id }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else deps.measurementLog.entries(id) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The state of the screen. */
    val uiState: StateFlow<MeasurementLogUiState> = combine(entries, clearAsked) { list, asked ->
        val offset = deps.utcOffsetSeconds()
        MeasurementLogUiState(rows = list.map { entry -> rowOf(entry, offset) }, clearAsked = asked)
    }.stateIn(scope, SharingStarted.Eagerly, MeasurementLogUiState())

    /** "Export as CSV": 23 columns, each cell quoted, CR LF, UTF-8. */
    fun onExportCsv() {
        val list = entries.value
        val frame = readout.frame.value ?: return
        export(fileName("csv"), InspectionExport.CSV_MIME_TYPE, InspectionExport.csvBytes(list, deps.utcOffsetSeconds(), frame.metric(org.superdro.core.model.AxisId.X)))
    }

    /** "Export as a printed inspection sheet" with the texts [labels] of the app language. */
    fun onExportSheet(labels: InspectionSheetLabels) {
        val list = entries.value
        export(fileName("txt"), InspectionExport.TEXT_MIME_TYPE, InspectionExport.textSheetBytes(list, deps.utcOffsetSeconds(), labels))
    }

    /** "Clear the log": shows or hides the question. */
    fun onClearAsk(show: Boolean) {
        clearAsked.value = show
    }

    /** "Clear" in the question: deletes all log entries of the workspace. */
    fun onClearConfirm() {
        clearAsked.value = false
        val id = readout.frame.value?.workspace?.id ?: return
        scope.launch { deps.measurementLog.clear(id) }
    }

    private fun export(name: String, mime: String, bytes: ByteArray) {
        scope.launch {
            val saved = deps.filePicker.createDocument(name, mime, bytes)
            noticeFlow.tryEmit(UiText.of(if (saved) Res.string.measure_log_exported else Res.string.measure_log_export_failed))
        }
    }

    private fun fileName(extension: String): String {
        val frame = readout.frame.value
        return InspectionExport.fileName(
            workspace = frame?.workspace?.name.orEmpty(),
            configuration = frame?.profile?.name.orEmpty(),
            lathe = frame?.profile?.machineType?.isLathe == true,
            time = deps.wallClock.now(),
            utcOffsetSeconds = deps.utcOffsetSeconds(),
            extension = extension,
        )
    }

    companion object {
        /** Gives the row of [entry] with the time in the local time of [utcOffsetSeconds]. */
        fun rowOf(entry: MeasurementLogEntry, utcOffsetSeconds: Int): LogRowUiState = LogRowUiState(
            id = entry.id,
            sequence = entry.sequence,
            timeText = LogTimestamp.format(entry.createdOn, utcOffsetSeconds),
            title = entry.title,
            values = entry.results.mapNotNull { logged ->
                MeasurementResult.valueOf(logged)?.let { (key, value) -> key to MeasurementFormat.valueText(value, entry.display) }
            },
            note = entry.note,
            systemNotes = entry.notes.joinToString("; ") { wire -> PointNote.exportTextOf(wire) },
        )
    }
}

/** Gives the labels of the text inspection sheet in the app language. */
@Composable
fun inspectionSheetLabels(): InspectionSheetLabels = InspectionSheetLabels(
    title = stringResource(Res.string.measure_sheet_title),
    workspace = stringResource(Res.string.measure_sheet_workspace),
    note = stringResource(Res.string.measure_sheet_note),
    system = stringResource(Res.string.measure_sheet_system),
    resultLabels = ResultKey.entries.associateWith { key -> stringResource(MeasurementTexts.resultLabel(key)) },
)

/**
 * The inspection log screen: the table of the entries, the two
 * exports, and the clear of the log.
 */
@Composable
fun MeasurementLogScreen(
    state: MeasurementLogUiState,
    onBack: () -> Unit,
    onExportCsv: () -> Unit,
    onExportSheet: (InspectionSheetLabels) -> Unit,
    onClearAsk: (Boolean) -> Unit,
    onClearConfirm: () -> Unit,
) {
    val labels = inspectionSheetLabels()
    val empty = state.rows.isEmpty()
    ScreenScaffold(
        title = stringResource(Res.string.measure_log_title),
        onBack = onBack,
        actions = {
            val sheet = stringResource(Res.string.measure_log_export_sheet)
            IconButton(onClick = { onExportSheet(labels) }, enabled = !empty, modifier = Modifier.size(64.dp).testTag(LOG_EXPORT_SHEET_TAG)) {
                Icon(painterResource(Res.drawable.ic_measure_log), contentDescription = sheet)
            }
            val csv = stringResource(Res.string.measure_log_export_csv)
            IconButton(onClick = onExportCsv, enabled = !empty, modifier = Modifier.size(64.dp).testTag(LOG_EXPORT_CSV_TAG)) {
                Icon(painterResource(Res.drawable.ic_measure_export), contentDescription = csv)
            }
            val clear = stringResource(Res.string.measure_log_clear)
            IconButton(onClick = { onClearAsk(true) }, enabled = !empty, modifier = Modifier.size(64.dp).testTag(LOG_CLEAR_TAG)) {
                Icon(painterResource(Res.drawable.ic_delete), contentDescription = clear)
            }
        },
    ) {
        if (empty) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(Res.string.measure_log_none), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LogTable(state.rows)
        }
    }
    if (state.clearAsked) {
        AlertDialog(
            onDismissRequest = { onClearAsk(false) },
            title = { Text(stringResource(Res.string.measure_log_clear_title)) },
            text = { Text(stringResource(Res.string.measure_log_clear_message, state.rows.size)) },
            dismissButton = { TextButton(onClick = { onClearAsk(false) }, colors = AccentButtons.text()) { Text(stringResource(Res.string.measure_cancel)) } },
            confirmButton = {
                TextButton(onClick = onClearConfirm, colors = AccentButtons.text(), modifier = Modifier.testTag(LOG_CLEAR_CONFIRM_TAG)) {
                    Text(stringResource(Res.string.measure_log_clear_confirm))
                }
            },
        )
    }
}

@Composable
private fun LogTable(rows: List<LogRowUiState>) {
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().horizontalScroll(scroll)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Header(stringResource(Res.string.measure_log_col_seq), SEQ_WIDTH)
            Header(stringResource(Res.string.measure_log_col_time), TIME_WIDTH)
            Header(stringResource(Res.string.measure_log_col_function), FUNCTION_WIDTH)
            Header(stringResource(Res.string.measure_log_col_values), VALUES_WIDTH)
            Header(stringResource(Res.string.measure_log_col_note), NOTE_WIDTH)
            Header(stringResource(Res.string.measure_log_col_system), SYSTEM_WIDTH)
        }
        HorizontalDivider(Modifier.width(TABLE_WIDTH), color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(Modifier.width(TABLE_WIDTH)) {
            items(rows, key = { row -> row.id }) { row -> LogRow(row) }
        }
    }
}

@Composable
private fun Header(text: String, width: Dp) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(width).padding(end = 8.dp))
}

@Composable
private fun LogRow(row: LogRowUiState) {
    val colors = MaterialTheme.colorScheme
    val parts = ArrayList<String>()
    for ((key, value) in row.values) parts += "${stringResource(MeasurementTexts.resultLabel(key))} $value"
    val valueText = parts.joinToString("; ")
    Column {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp).heightIn(min = 48.dp).testTag("measure-log-row-${row.sequence}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cell("#${row.sequence}", SEQ_WIDTH, monospace = true)
            Cell(row.timeText, TIME_WIDTH, monospace = true, small = true)
            Cell(row.title, FUNCTION_WIDTH)
            Cell(valueText, VALUES_WIDTH, monospace = true)
            Cell(row.note.orEmpty(), NOTE_WIDTH)
            Row(Modifier.width(SYSTEM_WIDTH), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (row.warn) {
                    Icon(painterResource(Res.drawable.ic_warning), contentDescription = null, tint = colors.error, modifier = Modifier.size(20.dp))
                    Column {
                        Text(stringResource(Res.string.measure_log_warn), style = MaterialTheme.typography.labelLarge, color = colors.error)
                        Text(row.systemNotes, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
                    }
                }
            }
        }
        HorizontalDivider(color = colors.outlineVariant)
    }
}

@Composable
private fun Cell(text: String, width: Dp, monospace: Boolean = false, small: Boolean = false) {
    Text(
        text,
        style = if (small) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
        fontFamily = if (monospace) FontFamily.Monospace else null,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.width(width).padding(end = 8.dp),
    )
}

private val SEQ_WIDTH = 56.dp
private val TIME_WIDTH = 240.dp
private val FUNCTION_WIDTH = 170.dp
private val VALUES_WIDTH = 420.dp
private val NOTE_WIDTH = 140.dp
private val SYSTEM_WIDTH = 190.dp
private val TABLE_WIDTH = SEQ_WIDTH + TIME_WIDTH + FUNCTION_WIDTH + VALUES_WIDTH + NOTE_WIDTH + SYSTEM_WIDTH + 32.dp
