package org.superdro.core.ui.adapter.encoder.map

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.toLocalDateTime
import org.superdro.core.functions.errormap.MapEditor
import org.superdro.core.functions.errormap.MapExport
import org.superdro.core.functions.errormap.MapImportProblem
import org.superdro.core.functions.errormap.MapImportResult
import org.superdro.core.functions.errormap.MapKind
import org.superdro.core.functions.errormap.MapProvenance
import org.superdro.core.functions.errormap.MapSaveResult
import org.superdro.core.functions.errormap.MapSummary
import org.superdro.core.functions.errormap.MapValidation
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.Channels
import org.superdro.core.model.EncoderProfile
import org.superdro.core.model.MapPoint
import org.superdro.core.readout.encoder.MapText
import org.superdro.core.resources.Res
import org.superdro.core.resources.encoder_map_cleared
import org.superdro.core.resources.encoder_map_clipboard_empty
import org.superdro.core.resources.encoder_map_converted
import org.superdro.core.resources.encoder_map_export_failed
import org.superdro.core.resources.encoder_map_exported
import org.superdro.core.resources.encoder_map_file_too_large
import org.superdro.core.resources.encoder_map_import_refused
import org.superdro.core.resources.encoder_map_not_saved
import org.superdro.core.resources.encoder_map_row_error
import org.superdro.core.resources.encoder_map_saved
import org.superdro.core.resources.encoder_map_too_many_rows
import org.superdro.core.store.StoreResult
import org.superdro.core.store.files.ExportFileNames
import org.superdro.core.store.files.FileTimes
import org.superdro.core.ui.adapter.AdapterUiDeps
import org.superdro.core.ui.adapter.encoder.EncoderSource
import org.superdro.core.ui.adapter.encoder.adapterOf
import org.superdro.core.ui.adapter.encoder.encoderOf
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.text.UiText

/** The tabs of the map editor. */
enum class MapEditorTab { Points, Summary, Curve }

/**
 * The edit dialog of one row of the table.
 *
 * @property index The index of the row in [MapEditorUiState.rows], or null for a new row.
 */
@Immutable
data class RowEditUi(val index: Int?, val position: String, val value: String, val error: UiText? = null)

/**
 * The state of the map editor of one input.
 *
 * @property rows The points of the table, sorted by position.
 * @property readOnly True for a map from a wizard (a `generated-by` comment). The rows cannot
 *   change, but import, paste, and clear are permitted.
 * @property problems The validation problems of the table: at each load, and at a refused save.
 * @property importProblems The problems of the last import that the editor refused.
 * @property notices The notices of the last import, for example the absolute-value conversion.
 * @property replaceQuestion The question "Replace the current points?" of an import.
 * @property confirmClear The question of a save of an empty table: the map goes away.
 * @property confirmWizard The question before the wizard opens with unsaved changes.
 * @property wizardAvailable True for an error map of an online source: the wizard button shows.
 */
@Immutable
data class MapEditorUiState(
    val input: Char,
    val kind: MapKind,
    val online: Boolean,
    val loaded: Boolean = false,
    val available: Boolean = false,
    val adapterName: String = "",
    val rows: List<MapPoint> = emptyList(),
    val provenance: MapProvenance = MapProvenance(),
    val readOnly: Boolean = false,
    val summary: MapSummary = MapEditor.summary(emptyList()),
    val dirty: Boolean = false,
    val problems: List<UiText> = emptyList(),
    val importProblems: List<UiText> = emptyList(),
    val notices: List<UiText> = emptyList(),
    val tab: MapEditorTab = MapEditorTab.Points,
    val rowEdit: RowEditUi? = null,
    val replaceQuestion: ReplaceUi? = null,
    val confirmClear: Boolean = false,
    val confirmWizard: Boolean = false,
    val wizardAvailable: Boolean = false,
    val message: UiText? = null,
)

/** The data of the question "Replace the current points?" of the import. */
@Immutable
data class ReplaceUi(val currentCount: Int, val newCount: Int, val firstPosition: Long, val lastPosition: Long)

/**
 * The presenter of the map editor of one input:
 * the table, the summary, the curve, the CSV import and export, paste, clear, and save. The rules
 * of the editor are in `core:functions` ([MapEditor], [MapValidation], [MapExport]).
 *
 * - The editor validates the stored map at each load and the table at each save.
 * - "Save" writes the map in one transaction of the store. The session uses it at once.
 * - The editor has no cloud button.
 * - The wizard button shows only for an error map of an online source.
 */
class MapEditorPresenter(
    val source: EncoderSource,
    input: Char,
    val kind: MapKind,
    private val deps: AdapterUiDeps,
) : ScopedPresenter(deps.context) {

    /** The lowercase input letter. */
    val input: Char = Channels.normalize(input)

    private val mutable = MutableStateFlow(
        MapEditorUiState(this.input, kind, source.online, wizardAvailable = source.online && kind == MapKind.Error),
    )
    private val writeLock = Mutex()
    private var adapter: AdapterProfile? = null
    private var stored: EncoderProfile? = null
    private var pendingImport: MapImportResult? = null

    /** The state of the screen. */
    val uiState: StateFlow<MapEditorUiState> = mutable.asStateFlow()

    init {
        scope.launch { deps.adapterOf(source).collect(::onAdapter) }
    }

    private val screwRev: Long? get() = stored?.countsPerScrewRev?.toLong()?.takeIf { it > 0L }

    private fun onAdapter(profile: AdapterProfile?) {
        adapter = profile
        if (profile == null) {
            stored = null
            mutable.update { it.copy(loaded = true, available = false) }
            return
        }
        val encoder = encoderOf(profile, input)
        stored = encoder
        val state = mutable.value
        if (state.available && state.dirty) {
            mutable.update { it.copy(adapterName = profile.alias) }
            return
        }
        val points = mapOf(encoder).sortedBy { it.position }
        val provenance = if (kind == MapKind.Error) MapProvenance.fromComments(encoder.errorMapComments) else MapProvenance()
        mutable.update {
            it.copy(
                loaded = true,
                available = true,
                adapterName = profile.alias,
                rows = points,
                provenance = provenance,
                readOnly = provenance.readOnly,
                summary = MapEditor.summary(points, provenance),
                problems = MapValidation.validate(kind, points, encoder.countsPerScrewRev?.toLong()).map(MapEditorTexts::problem),
            )
        }
    }

    private fun mapOf(encoder: EncoderProfile): List<MapPoint> = if (kind == MapKind.Error) encoder.errorMap else encoder.backlashMap

    // ---------------------------------------------------------------------------------------
    // The table

    fun onTab(tab: MapEditorTab) = mutable.update { it.copy(tab = tab) }

    /** "Add point": opens the row dialog with empty fields. */
    fun onAddRow() {
        if (mutable.value.readOnly) return
        mutable.update { it.copy(rowEdit = RowEditUi(null, "", "")) }
    }

    /** A tap on a row: opens the row dialog. */
    fun onEditRow(index: Int) {
        val state = mutable.value
        if (state.readOnly) return
        val row = state.rows.getOrNull(index) ?: return
        mutable.update { it.copy(rowEdit = RowEditUi(index, row.position.toString(), row.value.toString())) }
    }

    fun onRowPosition(text: String) = mutable.update { it.copy(rowEdit = it.rowEdit?.copy(position = text, error = null)) }

    fun onRowValue(text: String) = mutable.update { it.copy(rowEdit = it.rowEdit?.copy(value = text, error = null)) }

    /** "Done" of the row dialog: both fields must be whole numbers of counts. */
    fun onSaveRow() {
        val state = mutable.value
        val edit = state.rowEdit ?: return
        val position = edit.position.trim().toLongOrNull()
        val value = edit.value.trim().toLongOrNull()
        if (position == null || value == null) {
            mutable.update { it.copy(rowEdit = edit.copy(error = UiText.of(Res.string.encoder_map_row_error))) }
            return
        }
        val rows = state.rows.toMutableList()
        val point = MapPoint(position, value)
        if (edit.index == null) {
            if (rows.size >= MapText.MAX_POINTS) {
                mutable.update { it.copy(rowEdit = null, message = UiText.of(Res.string.encoder_map_too_many_rows, MapText.MAX_POINTS)) }
                return
            }
            rows.add(point)
        } else {
            rows[edit.index] = point
        }
        setRows(rows.sortedBy { it.position })
        mutable.update { it.copy(rowEdit = null) }
    }

    /** "Delete" of the row dialog. */
    fun onDeleteRow() {
        val state = mutable.value
        val index = state.rowEdit?.index ?: return
        setRows(state.rows.filterIndexed { i, _ -> i != index })
        mutable.update { it.copy(rowEdit = null) }
    }

    fun onCancelRow() = mutable.update { it.copy(rowEdit = null) }

    private fun setRows(rows: List<MapPoint>, provenance: MapProvenance = mutable.value.provenance, readOnly: Boolean = mutable.value.readOnly) {
        mutable.update {
            it.copy(
                rows = rows,
                provenance = provenance,
                readOnly = readOnly,
                summary = MapEditor.summary(rows, provenance),
                dirty = true,
                problems = emptyList(),
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // Import, paste, export, clear

    /** "Import": the user selects a CSV file. */
    fun onImport() {
        scope.launch {
            val picked = deps.filePicker.openDocument(IMPORT_MIME_TYPES) ?: return@launch
            if (!MapEditor.isCsvFileName(picked.name)) {
                refuseImport(listOf(MapEditorTexts.importProblem(MapImportProblem.ChooseCsvFile)))
                return@launch
            }
            if (picked.content.size > MAX_FILE_BYTES) {
                refuseImport(listOf(UiText.of(Res.string.encoder_map_file_too_large, MAX_FILE_BYTES)))
                return@launch
            }
            importText(picked.content.decodeToString())
        }
    }

    /** "Paste": [text] is the text of the clipboard, or null when it has no text. */
    fun onPaste(text: String?) {
        if (text.isNullOrBlank()) {
            mutable.update { it.copy(message = UiText.of(Res.string.encoder_map_clipboard_empty)) }
            return
        }
        importText(text)
    }

    private fun importText(text: String) {
        val result = MapEditor.import(text, kind, mutable.value.rows.size, screwRev)
        if (!result.accepted) {
            refuseImport(result.problems.map(MapEditorTexts::importProblem))
            return
        }
        val question = result.replaceQuestion
        if (question != null) {
            pendingImport = result
            mutable.update {
                it.copy(replaceQuestion = ReplaceUi(question.currentCount, question.newCount, question.firstPosition, question.lastPosition))
            }
            return
        }
        applyImport(result)
    }

    private fun refuseImport(problems: List<UiText>) {
        mutable.update { it.copy(importProblems = problems, notices = emptyList(), message = UiText.of(Res.string.encoder_map_import_refused)) }
    }

    /** "Replace" of the question "Replace the current points?". */
    fun onConfirmReplace() {
        val result = pendingImport ?: return
        pendingImport = null
        mutable.update { it.copy(replaceQuestion = null) }
        applyImport(result)
    }

    /** "Cancel" of the question: the table does not change. */
    fun onCancelReplace() {
        pendingImport = null
        mutable.update { it.copy(replaceQuestion = null) }
    }

    private fun applyImport(result: MapImportResult) {
        setRows(result.points, result.provenance, result.readOnly)
        mutable.update {
            it.copy(
                importProblems = emptyList(),
                notices = if (result.convertedFromAbsolute) listOf(UiText.of(Res.string.encoder_map_converted)) else emptyList(),
            )
        }
    }

    /** "Export": writes the rows of the table to a CSV file with the header of the map kind. */
    fun onExport() {
        val state = mutable.value
        if (!state.available) return
        scope.launch {
            val context = deps.exportContext()
            val exportedOn = FileTimes.format(context.exportedAt.toLocalDateTime(context.timeZone))
            val text = MapExport.text(kind, state.rows, state.adapterName, input, exportedOn, state.provenance)
            val name = exportFileName(state.adapterName, context.exportedAt.toLocalDateTime(context.timeZone))
            val written = deps.filePicker.createDocument(name, CSV_MIME, text.encodeToByteArray())
            mutable.update { it.copy(message = UiText.of(if (written) Res.string.encoder_map_exported else Res.string.encoder_map_export_failed, name)) }
        }
    }

    /** The name of an export file: `superdro-<error_map|backlash_map>-<adapter>-<INPUT>-<yyyyMMdd_HHmmss>.csv`. */
    fun exportFileName(adapterName: String, time: kotlinx.datetime.LocalDateTime): String =
        ExportFileNames.name(listOf(exportKind(kind), adapterName, input.uppercaseChar().toString()), time, "csv")

    /** "Clear": the table has no rows. "Save" then asks before it removes the map. */
    fun onClear() {
        setRows(MapEditor.clear(), MapProvenance(), readOnly = false)
        mutable.update { it.copy(importProblems = emptyList(), notices = emptyList()) }
    }

    // ---------------------------------------------------------------------------------------
    // Save

    /** "Save": validates the table and writes the map. An empty table asks first. */
    fun onSave() {
        val state = mutable.value
        if (!state.available) return
        when (val result = MapEditor.save(kind, state.rows, state.provenance, screwRev)) {
            MapSaveResult.Cleared -> mutable.update { it.copy(confirmClear = true) }
            is MapSaveResult.Refused -> {
                val problems = if (result.tooManyPoints) {
                    listOf(UiText.of(Res.string.encoder_map_too_many_rows, MapText.MAX_POINTS))
                } else {
                    result.problems.map(MapEditorTexts::problem)
                }
                mutable.update { it.copy(problems = problems, message = UiText.of(Res.string.encoder_map_not_saved)) }
            }
            is MapSaveResult.Saved -> writeMap(result.points, state.provenance, Res.string.encoder_map_saved)
        }
    }

    /** "Clear the map" of the question of an empty save. */
    fun onConfirmClear() {
        mutable.update { it.copy(confirmClear = false) }
        writeMap(emptyList(), MapProvenance(), Res.string.encoder_map_cleared)
    }

    fun onCancelClear() = mutable.update { it.copy(confirmClear = false) }

    private fun writeMap(points: List<MapPoint>, provenance: MapProvenance, done: org.jetbrains.compose.resources.StringResource) {
        val id = adapter?.id ?: return
        val base = stored ?: return
        val profile = when (kind) {
            MapKind.Error -> base.copy(errorMap = points, errorMapComments = provenance.toComments())
            MapKind.Backlash -> base.copy(backlashMap = points)
        }
        mutable.update { it.copy(dirty = false, problems = emptyList()) }
        stored = profile
        scope.launch {
            val result = writeLock.withLock { deps.adapters.saveEncoderProfile(id, profile) }
            val text = if (result is StoreResult.Ok) UiText.of(done) else UiText.of(Res.string.encoder_map_not_saved)
            mutable.update { it.copy(message = text) }
        }
    }

    // ---------------------------------------------------------------------------------------
    // The wizard

    /** The wizard button: gives true when the screen can open the wizard. Unsaved changes ask first. */
    fun onWizard(): Boolean {
        val state = mutable.value
        if (!state.wizardAvailable || !state.available) return false
        if (state.dirty) {
            mutable.update { it.copy(confirmWizard = true) }
            return false
        }
        return true
    }

    /** "Continue" of the question: the unsaved changes go away, and the wizard opens. */
    fun onConfirmWizard(): Boolean {
        mutable.update { it.copy(confirmWizard = false, dirty = false) }
        adapter?.let(::onAdapter)
        return true
    }

    fun onCancelWizard() = mutable.update { it.copy(confirmWizard = false) }

    fun onMessageShown() = mutable.update { it.copy(message = null) }

    companion object {
        /** The MIME types of the import picker. */
        val IMPORT_MIME_TYPES: List<String> = listOf("text/csv", "text/*", "application/octet-stream")

        /** The largest file of an import. */
        const val MAX_FILE_BYTES: Int = 1_000_000

        private const val CSV_MIME = "text/csv"

        /** The kind part of an export file name. */
        fun exportKind(kind: MapKind): String = when (kind) {
            MapKind.Error -> "error-map"
            MapKind.Backlash -> "backlash-map"
        }
    }
}
