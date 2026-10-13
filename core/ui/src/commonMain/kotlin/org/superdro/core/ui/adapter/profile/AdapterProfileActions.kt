package org.superdro.core.ui.adapter.profile

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.newId
import org.superdro.core.resources.Res
import org.superdro.core.resources.adapter_export_failed
import org.superdro.core.resources.adapter_export_saved
import org.superdro.core.resources.adapter_forget_done
import org.superdro.core.resources.adapter_import_exported
import org.superdro.core.resources.adapter_import_file_name
import org.superdro.core.resources.adapter_import_model
import org.superdro.core.resources.adapter_import_source_superdro
import org.superdro.core.resources.adapter_import_source_touchdro
import org.superdro.core.resources.adapter_import_title
import org.superdro.core.resources.adapter_copy_done
import org.superdro.core.store.StoreResult
import org.superdro.core.store.files.adapter.AdapterImportPreview
import org.superdro.core.store.files.adapter.AdapterProfileFile
import org.superdro.core.store.files.adapter.AdapterProfileImporter
import org.superdro.core.store.files.adapter.AdapterTarget
import org.superdro.core.store.files.ExportFile
import org.superdro.core.store.files.FileSource
import org.superdro.core.store.files.PreviewRow
import org.superdro.core.ui.adapter.AdapterUiDeps
import org.superdro.core.ui.adapter.common.ImportPreviewUi
import org.superdro.core.ui.adapter.common.ImportReportUi
import org.superdro.core.ui.adapter.common.PreviewLineUi
import org.superdro.core.ui.adapter.common.errorTexts
import org.superdro.core.ui.adapter.common.toUi
import org.superdro.core.ui.adapter.common.toUiText
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.toUiText

/** The two forms of the adapter profile export. */
enum class AdapterExportKind {
    /** The SuperDRO adapter profile file (`.json`). */
    SuperDro,

    /** The TouchDRO 3.32.00 form (`.tdap`), so that the user can move the calibration back. */
    Tdap,
}

/** The dialog of the profile actions. */
sealed interface ProfileDialog {
    /** The choice of the export form. */
    data object ExportChoice : ProfileDialog

    /** The import preview of a file. */
    data class Preview(val preview: ImportPreviewUi) : ProfileDialog

    /** The report after an import. It shows in the screen, not in a dialog (the dialog rule). */
    data class Report(val report: ImportReportUi) : ProfileDialog

    /** The question before "Forget" deletes the profile [alias]. */
    data class ConfirmForget(val alias: String) : ProfileDialog
}

/** The state of the profile actions: the open dialog and a busy flag while a file operation runs. */
@Immutable
data class ProfileActionsUiState(val dialog: ProfileDialog? = null, val busy: Boolean = false)

/** The events of the profile actions that the screens call. [AdapterProfileActions] implements them. */
interface ProfileActionEvents {
    fun copy()
    fun askExport()
    fun export(kind: AdapterExportKind)
    fun importIntoProfile()
    fun importAsNew()
    fun confirmImport()
    fun askForget()
    fun confirmForget()
    fun dismiss()
}

/**
 * The actions on one stored adapter profile: copy, export,
 * import, and forget. The adapter details (online), the offline adapter details, and the
 * adapters list use them. The actions change only app data. They send no byte to an adapter.
 *
 * - Copy: a new profile with the name "<name> (copy)" (or "(copy 2)", and so on, when the name
 *   exists), with all encoder profiles and maps, and with no identity and no link: an identity
 *   belongs to one physical adapter.
 * - Export: the SuperDRO file or the `.tdap` form, through the file picker.
 * - Import: the file picker, the preview, then the write and the report. The file type comes from
 *   the content. Into an existing profile, the import replaces the calibration of the inputs and
 *   keeps the name, the identity, and the link.
 * - Forget: a question, then the delete of the profile with its encoder profiles, its maps, and
 *   its wizard sessions. A machine profile that used it then has no adapter profile.
 *
 * @param profile Gives the current stored profile, or null.
 * @param message Shows a short message (the snackbar of the frame).
 * @param onForgotten Called after the delete.
 */
class AdapterProfileActions(
    private val scope: CoroutineScope,
    private val deps: AdapterUiDeps,
    private val profile: () -> AdapterProfile?,
    private val message: (UiText) -> Unit,
    private val onForgotten: () -> Unit = {},
) : ProfileActionEvents {
    private val mutable = MutableStateFlow(ProfileActionsUiState())

    /** The state of the dialogs. */
    val state: StateFlow<ProfileActionsUiState> = mutable.asStateFlow()

    private var pending: AdapterImportPreview? = null

    /** Copies the profile ("Make a copy of this adapter profile"). */
    override fun copy() {
        val source = profile() ?: return
        scope.launch {
            val alias = uniqueAlias(deps, source.alias, COPY_SUFFIX)
            val copy = source.copy(
                id = newId(),
                alias = alias,
                identity = null,
                identitySource = null,
                lastLink = null,
                lastSeenOn = null,
                createdOn = deps.wallClock.now(),
            )
            when (val result = deps.adapters.saveAdapterProfile(copy)) {
                is StoreResult.Ok -> message(UiText.of(Res.string.adapter_copy_done, result.value.alias))
                is StoreResult.Refused -> message(result.refusal.toUiText())
            }
        }
    }

    /** Opens the choice of the export form. */
    override fun askExport() {
        if (profile() == null) return
        mutable.update { state -> state.copy(dialog = ProfileDialog.ExportChoice) }
    }

    /** Writes the export file of the form [kind] through the file picker. */
    override fun export(kind: AdapterExportKind) {
        val source = profile() ?: return
        mutable.update { state -> state.copy(dialog = null, busy = true) }
        scope.launch {
            val context = deps.exportContext()
            val file: ExportFile = when (kind) {
                AdapterExportKind.SuperDro -> AdapterProfileFile.exportSuperDro(source, context)
                AdapterExportKind.Tdap -> AdapterProfileFile.exportTdap(source, context)
            }
            val saved = deps.filePicker.createDocument(file.fileName, file.mimeType, file.bytes())
            mutable.update { state -> state.copy(busy = false) }
            message(if (saved) UiText.of(Res.string.adapter_export_saved, file.fileName) else UiText.of(Res.string.adapter_export_failed))
        }
    }

    /** Imports a file into this profile ("Import an adapter profile into this one"). */
    override fun importIntoProfile() {
        val target = profile() ?: return
        pick(AdapterTarget.Existing(target))
    }

    /** Imports a file as a new adapter profile. */
    override fun importAsNew() = pick(AdapterTarget.NewAdapter)

    /** Writes the previewed import. */
    override fun confirmImport() {
        val preview = pending ?: return
        val adapter = preview.adapter
        if (!preview.usable || adapter == null) return
        pending = null
        mutable.update { state -> state.copy(dialog = null, busy = true) }
        scope.launch {
            val toWrite = if (preview.target is AdapterTarget.NewAdapter) {
                adapter.copy(alias = uniqueAlias(deps, adapter.alias.ifBlank { IMPORTED_NAME }, null))
            } else {
                adapter
            }
            when (val result = deps.adapters.saveAdapterProfile(toWrite)) {
                is StoreResult.Ok -> mutable.update { state -> state.copy(busy = false, dialog = ProfileDialog.Report(preview.report.toUi())) }
                is StoreResult.Refused -> {
                    mutable.update { state -> state.copy(busy = false) }
                    message(result.refusal.toUiText())
                }
            }
        }
    }

    /** Asks before "Forget" deletes the profile. */
    override fun askForget() {
        val target = profile() ?: return
        mutable.update { state -> state.copy(dialog = ProfileDialog.ConfirmForget(target.alias)) }
    }

    /** Deletes the profile with its encoder profiles and maps ("Forget"). */
    override fun confirmForget() {
        val target = profile() ?: return
        mutable.update { state -> state.copy(dialog = null) }
        scope.launch {
            deps.adapters.deleteAdapterProfile(target.id)
            message(UiText.of(Res.string.adapter_forget_done, target.alias))
            onForgotten()
        }
    }

    /** Closes the open dialog. An import that the user did not confirm writes nothing. */
    override fun dismiss() {
        pending = null
        mutable.update { state -> state.copy(dialog = null) }
    }

    private fun pick(target: AdapterTarget) {
        mutable.update { state -> state.copy(busy = true) }
        scope.launch {
            val file = deps.filePicker.openDocument(IMPORT_MIME_TYPES)
            if (file == null) {
                mutable.update { state -> state.copy(busy = false) }
                return@launch
            }
            val preview = AdapterProfileImporter(now = { deps.wallClock.now() }, timeZone = deps.timeZone).read(file.content, target)
            pending = preview
            mutable.update { state -> state.copy(busy = false, dialog = ProfileDialog.Preview(previewUi(file.name, preview))) }
        }
    }

    companion object {
        /** The suffix of the name of a copy. */
        const val COPY_SUFFIX: String = " (copy)"

        /** The name of an imported profile when the file has no name. */
        const val IMPORTED_NAME: String = "Imported adapter"

        /** The MIME types of the import picker: the SuperDRO file, the `.tdap` file, and each other file. */
        val IMPORT_MIME_TYPES: List<String> = listOf(ExportFile.MIME_JSON, ExportFile.MIME_TDAP, "application/octet-stream", "*/*")

        /** The UI form of the preview of an adapter profile file. */
        fun previewUi(fileName: String, preview: AdapterImportPreview): ImportPreviewUi {
            val header = buildList {
                add(UiText.of(Res.string.adapter_import_file_name, fileName))
                when (preview.source) {
                    FileSource.SuperDro -> add(UiText.of(Res.string.adapter_import_source_superdro))
                    FileSource.TouchDro -> add(UiText.of(Res.string.adapter_import_source_touchdro))
                    null -> Unit
                }
                preview.adapterName?.let { name -> add(UiText.Plain(name)) }
                preview.model?.let { model -> add(UiText.of(Res.string.adapter_import_model, model)) }
                preview.exported?.let { exported -> add(UiText.of(Res.string.adapter_import_exported, exported)) }
            }
            val lists = preview.preview
            fun line(row: PreviewRow<*>) = PreviewLineUi(row.label, row.action.toUiText(), row.problems.map { problem -> problem.toUiText() })
            return ImportPreviewUi(
                title = UiText.of(Res.string.adapter_import_title),
                header = header,
                toImport = lists.toImport.map(::line),
                skipped = lists.skipped.map(::line),
                warnings = lists.warnings.map { problem -> problem.toUiText() },
                errors = preview.problems.errorTexts(),
                canImport = preview.usable,
            )
        }

        /**
         * Gives [base] + [suffix] when no other adapter profile has that name, else the name with
         * the number 2, 3, and so on. The name keeps the limit of 40 characters. A null
         * [suffix] tries [base] first, then " (2)", " (3)", and so on.
         */
        suspend fun uniqueAlias(deps: AdapterUiDeps, base: String, suffix: String?): String {
            val taken = deps.adapters.adapterProfiles().first().map { profile -> profile.alias.trim().lowercase() }.toSet()
            return uniqueName(base.trim(), suffix, taken)
        }

        /** The name rule of [uniqueAlias] with the names [taken] in lower case. */
        fun uniqueName(base: String, suffix: String?, taken: Set<String>): String {
            for (number in 1..999) {
                val end = when {
                    suffix == null && number == 1 -> ""
                    suffix == null -> " ($number)"
                    number == 1 -> suffix
                    else -> suffix.dropLast(1) + " $number)"
                }
                val name = base.take(AdapterProfile.MAX_ALIAS_LENGTH - end.length).trimEnd() + end
                if (name.lowercase() !in taken) return name
            }
            return newId().take(AdapterProfile.MAX_ALIAS_LENGTH)
        }
    }
}
