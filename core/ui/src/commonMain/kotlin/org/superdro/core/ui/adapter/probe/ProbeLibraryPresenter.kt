package org.superdro.core.ui.adapter.probe

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.superdro.core.connection.DeviceId
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.ProbeType
import org.superdro.core.resources.Res
import org.superdro.core.resources.probelib_export_failed
import org.superdro.core.resources.probelib_export_nothing
import org.superdro.core.resources.probelib_export_saved
import org.superdro.core.resources.probelib_import_exported
import org.superdro.core.resources.probelib_import_failed
import org.superdro.core.resources.probelib_import_from_profile
import org.superdro.core.resources.probelib_import_op_add
import org.superdro.core.resources.probelib_import_op_add_new_only
import org.superdro.core.resources.probelib_import_op_merge
import org.superdro.core.resources.probelib_import_op_replace
import org.superdro.core.resources.probelib_import_op_update
import org.superdro.core.resources.probelib_import_source_superdro
import org.superdro.core.resources.probelib_import_source_touchdro
import org.superdro.core.resources.probelib_import_source_unknown
import org.superdro.core.resources.probelib_import_title
import org.superdro.core.store.files.FileSource
import org.superdro.core.store.files.ImportOperation
import org.superdro.core.store.files.PreviewRow
import org.superdro.core.store.files.RowAction
import org.superdro.core.store.files.probe.ProbeImportContext
import org.superdro.core.store.files.probe.ProbeImportPreview
import org.superdro.core.store.files.probe.ProbeProfileFile
import org.superdro.core.store.files.probe.ProbeProfileImporter
import org.superdro.core.ui.adapter.AdapterUiDeps
import org.superdro.core.ui.adapter.common.ImportPreviewUi
import org.superdro.core.ui.adapter.common.ImportReportUi
import org.superdro.core.ui.adapter.common.PreviewLineUi
import org.superdro.core.ui.adapter.common.errorTexts
import org.superdro.core.ui.adapter.common.toUi
import org.superdro.core.ui.adapter.common.toUiText
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.text.UiText

/**
 * One row of the probe library.
 *
 * @property diameterUm The tip diameter for a type with a diameter, else null.
 * @property shared True for a profile for all machine profiles.
 */
@Immutable
data class ProbeLibraryRow(
    val id: String,
    val name: String,
    val type: ProbeType,
    val diameterUm: Double?,
    val metric: Boolean,
    val port: Char?,
    val standardTip: Boolean,
    val shared: Boolean,
    val calibrated: Boolean,
)

/** The two export forms of the probe library. */
enum class ProbeExportForm {
    /** The SuperDRO probe profile file (`.json`). */
    SuperDro,

    /** The `.tdprb` form of TouchDRO 3.32.00, so that the user can move the profiles back. */
    Tdprb,
}

/**
 * The state of the probe library screen.
 *
 * @property machineName The machine profile of the selected tab, or null with no tab.
 * @property canTransfer True when the export and the import are available: a machine profile is the target.
 * @property confirmDelete The row of the delete question, or null.
 * @property standardTipNotice True while the notice "a standard tip is read only" shows. It shows
 *   in the screen, not in a dialog (the dialog rule).
 * @property standardTipNoticeId The standard tip below whose row the notice shows, or null for the
 *   top of the profiles.
 * @property importPreview The preview of an import, or null.
 * @property report The report after an import, or null.
 * @property message A message for the snackbar, or null.
 */
@Immutable
data class ProbeLibraryUiState(
    val loaded: Boolean = false,
    val rows: List<ProbeLibraryRow> = emptyList(),
    val machineName: String? = null,
    val canTransfer: Boolean = false,
    val confirmDelete: ProbeLibraryRow? = null,
    val standardTipNotice: Boolean = false,
    val standardTipNoticeId: String? = null,
    val importPreview: ImportPreviewUi? = null,
    val report: ImportReportUi? = null,
    val message: UiText? = null,
)

/**
 * The presenter of the probe library. The library belongs to the machine
 * profile of the selected tab [device]: its own profiles first, then the profiles for all machine
 * profiles with the six standard tips, each part in the natural order of the names
 * ([sortedForLibrary]). With no tab, only the profiles for all machine profiles show.
 */
class ProbeLibraryPresenter(
    private val deps: AdapterUiDeps,
    val device: DeviceId?,
    private val importer: ProbeProfileImporter = ProbeProfileImporter(timeZone = deps.timeZone),
) : ScopedPresenter(deps.context) {

    private val mutable = MutableStateFlow(ProbeLibraryUiState())

    /** The UI state of the screen. */
    val uiState: StateFlow<ProbeLibraryUiState> = mutable.asStateFlow()

    /** The probe selectors of the three functions of the machine profile, or an empty list with no tab. */
    val selectors: List<ProbeSelectorDelegate> =
        if (device == null) emptyList() else ProbeFunction.entries.map { function -> ProbeSelectorDelegate(deps, device, function, scope) }

    private var machine: MachineProfile? = null
    private var profiles: List<ProbeProfile> = emptyList()
    private var preview: ProbeImportPreview? = null
    private var operations: List<ImportOperation> = emptyList()

    init {
        val machineFlow = device?.let(deps::machine) ?: flowOf(null)
        val libraryFlow = device?.let { id -> deps.probes.library(id.value) } ?: deps.probes.sharedProfiles()
        scope.launch {
            combine(machineFlow, libraryFlow) { current, library -> current to library }.collect { (current, library) ->
                machine = current
                profiles = library
                mutable.update { state ->
                    state.copy(
                        loaded = true,
                        rows = library.sortedForLibrary().map(::rowOf),
                        machineName = current?.name,
                        canTransfer = current != null,
                    )
                }
            }
        }
    }

    /** The user taps the delete button of [id]: the question shows. */
    fun askDelete(id: String) = mutable.update { state -> state.copy(confirmDelete = state.rows.firstOrNull { row -> row.id == id }) }

    /** The user cancels the delete. */
    fun cancelDelete() = mutable.update { state -> state.copy(confirmDelete = null) }

    /**
     * The user confirms the delete. A standard tip goes for all machine profiles. Each machine
     * profile that selects the profile for a function loses the selection.
     */
    fun confirmDelete() {
        val row = mutable.value.confirmDelete ?: return
        mutable.update { state -> state.copy(confirmDelete = null) }
        scope.launch { deps.probes.deleteProbeProfile(row.id) }
    }

    /** The user taps a standard tip: the notice that a standard tip is read only shows. */
    fun showStandardTipNotice() = mutable.update { state -> state.copy(standardTipNotice = true) }

    /**
     * The user taps the standard tip [id]: the notice shows below its row (the dialog rule). A
     * second tap on the same row closes the notice.
     */
    fun toggleStandardTipNotice(id: String) = mutable.update { state ->
        if (state.standardTipNotice && state.standardTipNoticeId == id) {
            state.copy(standardTipNotice = false, standardTipNoticeId = null)
        } else {
            state.copy(standardTipNotice = true, standardTipNoticeId = id)
        }
    }

    /** The user closes the notice. */
    fun dismissStandardTipNotice() = mutable.update { state -> state.copy(standardTipNotice = false, standardTipNoticeId = null) }

    /** The frame showed the message. */
    fun messageShown() = mutable.update { state -> state.copy(message = null) }

    /**
     * Exports the own profiles of the machine profile, sorted by name, with no standard tips, in
     * the form [form].
     */
    fun export(form: ProbeExportForm) {
        val current = machine ?: return
        val own = ProbeProfileFile.ownProfiles(current.id, profiles)
        if (own.isEmpty()) {
            mutable.update { state -> state.copy(message = UiText.of(Res.string.probelib_export_nothing)) }
            return
        }
        scope.launch {
            val context = deps.exportContext()
            val file = when (form) {
                ProbeExportForm.SuperDro -> ProbeProfileFile.exportSuperDro(current.id, current.name, own, context)
                ProbeExportForm.Tdprb -> ProbeProfileFile.exportTdprb(current.id, current.name, own, context)
            }
            val written = deps.filePicker.createDocument(file.fileName, file.mimeType, file.bytes())
            val text = if (written) UiText.of(Res.string.probelib_export_saved, file.fileName) else UiText.of(Res.string.probelib_export_failed)
            mutable.update { state -> state.copy(message = text) }
        }
    }

    /**
     * Lets the user select a file, reads it, and shows the preview. The import
     * finds the file type from the content, not from the name.
     */
    fun startImport() {
        val current = machine ?: return
        scope.launch {
            val picked = deps.filePicker.openDocument(listOf(IMPORT_MIME_ANY)) ?: return@launch
            val context = ProbeImportContext(current.id, current.name, ProbeProfileFile.ownProfiles(current.id, profiles))
            val read = importer.read(picked.content, context)
            preview = read
            operations = if (read.usable) read.operations else emptyList()
            mutable.update { state -> state.copy(importPreview = previewUi(read, 0)) }
        }
    }

    /** The user selects the import operation with the index [index]. */
    fun selectOperation(index: Int) {
        val read = preview ?: return
        if (index !in operations.indices) return
        mutable.update { state -> state.copy(importPreview = previewUi(read, index)) }
    }

    /** The user cancels the import. Nothing changes. */
    fun cancelImport() {
        preview = null
        mutable.update { state -> state.copy(importPreview = null) }
    }

    /**
     * The user confirms the import: in one transaction, "Replace all" deletes the own profiles
     * first, then the import writes the new and the changed profiles. The report shows.
     */
    fun confirmImport() {
        val read = preview ?: return
        val shown = mutable.value.importPreview ?: return
        if (!shown.canImport) return
        val operation = operations.getOrNull(shown.selectedOperation) ?: return
        preview = null
        scope.launch {
            val plan = read.plan(operation)
            val report = try {
                deps.probes.applyImport(plan)
                plan.report.toUi()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                null
            }
            mutable.update { state ->
                state.copy(
                    importPreview = null,
                    report = report,
                    message = if (report == null) UiText.of(Res.string.probelib_import_failed) else state.message,
                )
            }
        }
    }

    /** The user closes the report. */
    fun dismissReport() = mutable.update { state -> state.copy(report = null) }

    private fun rowOf(profile: ProbeProfile): ProbeLibraryRow = ProbeLibraryRow(
        id = profile.id,
        name = profile.name,
        type = profile.type,
        diameterUm = if (ProbeTypeRules.usesDiameter(profile.type)) profile.effectiveDiameterUm ?: profile.stylusDiameterUm else null,
        metric = profile.metric,
        port = profile.port,
        standardTip = profile.standardTip,
        shared = profile.profileId == null,
        calibrated = profile.calibratedOn != null,
    )

    private fun previewUi(read: ProbeImportPreview, selected: Int): ImportPreviewUi {
        val header = buildList {
            add(
                UiText.of(
                    when (read.source) {
                        FileSource.SuperDro -> Res.string.probelib_import_source_superdro
                        FileSource.TouchDro -> Res.string.probelib_import_source_touchdro
                        null -> Res.string.probelib_import_source_unknown
                    },
                ),
            )
            read.fileProfileName?.let { name -> add(UiText.of(Res.string.probelib_import_from_profile, name)) }
            read.exported?.let { time -> add(UiText.of(Res.string.probelib_import_exported, time)) }
        }
        val errors = read.problems.errorTexts()
        if (!read.usable || operations.isEmpty()) {
            return ImportPreviewUi(
                title = UiText.of(Res.string.probelib_import_title),
                header = header,
                skipped = read.rows.map { row -> PreviewLineUi(row.name, RowAction.Skip.toUiText(), row.problems.map { it.toUiText() }) },
                errors = errors,
                canImport = false,
            )
        }
        val plan = read.plan(operations[selected])
        val rowProblems = (plan.preview.toImport + plan.preview.skipped).flatMap { row -> row.problems }.toSet()
        return ImportPreviewUi(
            title = UiText.of(Res.string.probelib_import_title),
            header = header,
            toImport = plan.preview.toImport.map(::lineOf),
            skipped = plan.preview.skipped.map(::lineOf),
            warnings = plan.preview.warnings.filter { problem -> problem !in rowProblems }.map { problem -> problem.toUiText() },
            errors = errors,
            operations = operations.map(::operationText),
            selectedOperation = selected,
            canImport = plan.preview.toImport.isNotEmpty(),
        )
    }

    private fun lineOf(row: PreviewRow<ProbeProfile>): PreviewLineUi =
        PreviewLineUi(row.label, row.action.toUiText(), row.problems.map { problem -> problem.toUiText() })

    private fun operationText(operation: ImportOperation): UiText = UiText.of(
        when (operation) {
            ImportOperation.AddAsNew -> Res.string.probelib_import_op_add
            ImportOperation.UpdateMatching -> Res.string.probelib_import_op_update
            ImportOperation.ReplaceAll -> Res.string.probelib_import_op_replace
            ImportOperation.AddNewOnly -> Res.string.probelib_import_op_add_new_only
            ImportOperation.MergeMatching -> Res.string.probelib_import_op_merge
        },
    )

    private companion object {
        /** The import accepts each file. The content tells the type. */
        const val IMPORT_MIME_ANY: String = "*/*"
    }
}
