package org.superdro.core.ui.functions.importing

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.ResourceEnvironment
import org.superdro.core.model.AxisId
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.ProbeType
import org.superdro.core.model.ReadoutFormat
import org.superdro.core.model.Tool
import org.superdro.core.model.Workspace
import org.superdro.core.resources.Res
import org.superdro.core.resources.import_kind_profile
import org.superdro.core.resources.import_kind_sub_datums
import org.superdro.core.resources.import_kind_tdset
import org.superdro.core.resources.import_kind_tools
import org.superdro.core.resources.import_probe_profile_name
import org.superdro.core.resources.import_profile_created
import org.superdro.core.resources.import_profile_updated
import org.superdro.core.resources.import_provenance
import org.superdro.core.resources.import_provenance_time
import org.superdro.core.resources.import_row_guide
import org.superdro.core.resources.import_row_point
import org.superdro.core.resources.import_row_setting
import org.superdro.core.resources.import_row_setting_new
import org.superdro.core.resources.import_value_none
import org.superdro.core.store.StoreResult
import org.superdro.core.store.files.DetectedFile
import org.superdro.core.store.files.FileSource
import org.superdro.core.store.files.ImportFileDetector
import org.superdro.core.store.files.ImportFileKind
import org.superdro.core.store.files.ImportOperation
import org.superdro.core.store.files.ImportProblem
import org.superdro.core.store.files.ImportReport
import org.superdro.core.store.files.ImportText
import org.superdro.core.store.files.MessageKey
import org.superdro.core.store.files.PreviewRow
import org.superdro.core.store.files.Report
import org.superdro.core.store.files.RowAction
import org.superdro.core.store.files.Severity
import org.superdro.core.store.files.settings.ProfileFileImporter
import org.superdro.core.store.files.settings.SettingRow
import org.superdro.core.store.files.settings.SettingsImportPreview
import org.superdro.core.store.files.settings.SettingsImporter
import org.superdro.core.store.files.settings.SettingsTarget
import org.superdro.core.store.files.subdatum.SubDatumCsvImporter
import org.superdro.core.store.files.subdatum.SubDatumImportContext
import org.superdro.core.store.files.subdatum.SubDatumImportPreview
import org.superdro.core.store.files.subdatum.SubDatumItem
import org.superdro.core.store.files.tool.ToolCsvImporter
import org.superdro.core.store.files.tool.ToolImportContext
import org.superdro.core.store.files.tool.ToolImportPreview
import org.superdro.core.ui.functions.common.LengthTexts
import org.superdro.core.ui.functions.common.loadString
import org.superdro.core.ui.functions.common.FunctionServices
import org.superdro.core.ui.functions.tools.ToolTexts
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.toUiText

/** One row of the import preview: the row number, the label, the detail, the action, and the problems. */
@Immutable
data class ImportRowUi(
    val row: Int,
    val label: String,
    val detail: UiText?,
    val action: RowAction,
    val problems: List<UiText> = emptyList(),
)

/** The stage of the import flow of a tab. */
enum class ImportStage {
    /** No import. */
    Idle,

    /** The file picker is open, or the app reads the file. */
    Reading,

    /** The preview shows. */
    Preview,

    /** The import writes the data. */
    Writing,

    /** The user cancelled the file picker, or the import finished: the preview screen closes. */
    Closed,
}

/**
 * The UI state of the import preview screen: the three lists
 * (rows to import, rows skipped, warnings), the problems of the full file, and the choices of the
 * kind of the file.
 *
 * @property fileErrors The errors of the full file. An error stops the import.
 * @property operations The import operations. The first one is the default.
 * @property metric The unit of a sub-datum file: true for mm.
 * @property newProfile The target of a profile file: a new profile, or the settings of the profile of the tab.
 * @property name The name of the profile (the user selects the name).
 * @property report The report of the finished import, or null. The screen then shows the report screen.
 * @property done The message of the finished import, or null.
 */
@Immutable
data class ImportUiState(
    val stage: ImportStage = ImportStage.Idle,
    val kind: ImportKind? = null,
    val fileName: String = "",
    val kindText: UiText? = null,
    val provenance: UiText? = null,
    val fileErrors: List<UiText> = emptyList(),
    val toImport: List<ImportRowUi> = emptyList(),
    val skipped: List<ImportRowUi> = emptyList(),
    val warnings: List<UiText> = emptyList(),
    val operations: List<ImportOperation> = emptyList(),
    val operation: ImportOperation? = null,
    val metric: Boolean? = null,
    val newProfile: Boolean = true,
    val name: String = "",
    val nameError: UiText? = null,
    val usable: Boolean = false,
    val report: Report? = null,
    val done: UiText? = null,
) {
    /** The count of the confirm button. */
    val importCount: Int get() = toImport.size
}

/**
 * The import flow of one tab. The tab owns it.
 *
 * 1. [start] opens the file picker and reads the file in a coroutine that catches each
 *    exception. The import tests the type of the file by its content.
 * 2. The preview shows the three lists, the problems, and the operations. The import
 *    writes nothing yet.
 * 3. [confirm] writes the data, gives the result message, and makes the report.
 */
class ImportFlowPresenter(
    private val profileId: String,
    private val services: FunctionServices,
) : ScopedPresenter(services.context) {

    private val mutable = MutableStateFlow(ImportUiState())
    private var pending: Pending? = null
    private var job: Job? = null

    /**
     * The resource environment of the texts of the report and of a new probe profile. The
     * preview entry gives it (`rememberResourceEnvironment()`), because these texts load outside
     * of a composition.
     */
    var environment: ResourceEnvironment? = null

    /** The UI state of the import preview screen. */
    val uiState: StateFlow<ImportUiState> = mutable.asStateFlow()

    /** Starts the import of [kind]: the file picker, then the preview. */
    fun start(kind: ImportKind) {
        job?.cancel()
        pending = null
        mutable.value = ImportUiState(stage = ImportStage.Reading, kind = kind)
        job = scope.launch {
            val picked = try {
                services.filePicker.openDocument(mimeTypes(kind))
            } catch (error: Exception) {
                null
            }
            if (picked == null) {
                mutable.value = ImportUiState(stage = ImportStage.Closed, kind = kind)
                return@launch
            }
            read(kind, picked.name, picked.content)
        }
    }

    /** Reads the bytes [content] of the file [fileName] as an import of [kind]. Each exception gives a problem. */
    suspend fun read(kind: ImportKind, fileName: String, content: ByteArray) {
        val next = try {
            when (kind) {
                ImportKind.ToolCsv -> readTools(fileName, content)
                ImportKind.SubDatumCsv -> readSubDatums(fileName, content, unitsOverride = null)
                ImportKind.Profile -> readProfile(fileName, content, newProfile = true, name = null)
            }
        } catch (error: Exception) {
            Pending.Failed(fileName, kind, ImportProblem.error(MessageKey.FileFailed, error.message ?: error.toString()))
        }
        show(next)
    }

    /** Selects the import operation [operation]. */
    fun onOperation(operation: ImportOperation) {
        val current = pending ?: return
        if (operation !in current.operations) return
        current.operation = operation
        show(current)
    }

    /** Selects the unit of a sub-datum file: true for mm. */
    fun onUnits(metric: Boolean) {
        val current = pending as? Pending.SubDatums ?: return
        scope.launch { show(readSubDatums(current.fileName, current.content, unitsOverride = metric, operation = current.operation)) }
    }

    /** Selects the target of a profile file: a new profile, or the settings of the profile of the tab. */
    fun onTarget(newProfile: Boolean) {
        val current = pending as? Pending.Settings ?: return
        if (current.newProfile == newProfile) return
        scope.launch { show(readProfile(current.fileName, current.content, newProfile, name = null)) }
    }

    /** Sets the name of the profile. */
    fun onName(name: String) {
        val current = pending as? Pending.Settings ?: return
        current.name = name
        mutable.update { state -> state.copy(name = name, nameError = null) }
    }

    /** The user closed the preview with no import. */
    fun cancel() {
        job?.cancel()
        pending = null
        mutable.value = ImportUiState(stage = ImportStage.Closed)
    }

    /** The screen took the result of the import. */
    fun onClosed() {
        if (mutable.value.stage == ImportStage.Closed) mutable.value = ImportUiState()
    }

    /**
     * Writes the import of the preview. The result: the stage
     * [ImportStage.Closed] with the message [ImportUiState.done] and the report
     * [ImportUiState.report], or the preview again with a name error.
     */
    fun confirm() {
        val current = pending ?: return
        if (!mutable.value.usable || mutable.value.stage != ImportStage.Preview) return
        mutable.update { state -> state.copy(stage = ImportStage.Writing) }
        job = scope.launch {
            val outcome = try {
                when (current) {
                    is Pending.Tools -> writeTools(current)
                    is Pending.SubDatums -> writeSubDatums(current)
                    is Pending.Settings -> writeSettings(current)
                    is Pending.Failed -> null
                }
            } catch (error: Exception) {
                Outcome(MessageKey.FileFailed.toUiText(error.message ?: error.toString()), null)
            }
            if (outcome == null) {
                // The write refused the name: the preview stays with the name error.
                mutable.update { state -> state.copy(stage = ImportStage.Preview) }
                return@launch
            }
            val report = outcome.report?.takeIf(ImportReport::hasItems)?.let { ImportUiTexts.render(it, environment) }
            pending = null
            mutable.value = ImportUiState(stage = ImportStage.Closed, kind = current.kind, done = outcome.message, report = report)
        }
    }

    // -----------------------------------------------------------------------------------------
    // The reads

    private suspend fun readTools(fileName: String, content: ByteArray): Pending {
        val profile = services.machines.loadProfile(profileId) ?: return failed(fileName, ImportKind.ToolCsv)
        val library = services.tools.loadLibrary(profileId)
        val preview = ToolCsvImporter(parseNumber = services.parseNumber, now = { services.wallClock.now() })
            .read(content, ToolImportContext(profileId, profile.machineType, library))
        return Pending.Tools(fileName, preview, profile)
    }

    private suspend fun readSubDatums(
        fileName: String,
        content: ByteArray,
        unitsOverride: Boolean?,
        operation: ImportOperation? = null,
    ): Pending {
        val profile = services.machines.loadProfile(profileId) ?: return failed(fileName, ImportKind.SubDatumCsv)
        val workspace = activeWorkspace(profile) ?: return failed(fileName, ImportKind.SubDatumCsv)
        val context = SubDatumImportContext(
            profileId = profileId,
            machineType = profile.machineType,
            metricDefault = profile.metricDefault,
            subDatums = workspace.subDatums,
            guides = workspace.guides,
            unitsOverride = unitsOverride,
        )
        val preview = SubDatumCsvImporter(parseNumber = services.parseNumber, now = { services.wallClock.now() }).read(content, context)
        return Pending.SubDatums(fileName, content, preview, profile, workspace.id).also { next ->
            if (operation != null && operation in next.operations) next.operation = operation
        }
    }

    private suspend fun readProfile(fileName: String, content: ByteArray, newProfile: Boolean, name: String?): Pending {
        val profile = services.machines.loadProfile(profileId) ?: return failed(fileName, ImportKind.Profile)
        val target = if (newProfile) SettingsTarget.NewProfile else SettingsTarget.SelectedProfile(profile)
        val detected: DetectedFile = ImportFileDetector.detect(content)
        val preview = when (detected.kind) {
            ImportFileKind.Settings -> SettingsImporter(now = { services.wallClock.now() }).read(content, target)
            ImportFileKind.Profile -> ProfileFileImporter(now = { services.wallClock.now() }).read(content, target)
            ImportFileKind.Unknown -> return Pending.Failed(fileName, ImportKind.Profile, unknownProblem(content))
            else -> return Pending.Failed(
                fileName,
                ImportKind.Profile,
                ImportProblem.error(MessageKey.FileWrongKind, kindWord(detected.kind), "settings"),
            )
        }
        val suggested = name ?: preview.suggestedName ?: profile.name
        return Pending.Settings(fileName, content, preview, profile, newProfile, detected.kind == ImportFileKind.Settings, suggested)
    }

    private fun unknownProblem(content: ByteArray): ImportProblem {
        // A refused text (empty, binary, too large) keeps its own problem.
        return when (val decoded = ImportText.decode(content)) {
            is ImportText.Decoded.Refused -> decoded.problem
            is ImportText.Decoded.Text -> ImportProblem.error(MessageKey.FileUnknownKind)
        }
    }

    private fun kindWord(kind: ImportFileKind): String = when (kind) {
        ImportFileKind.ToolCsv -> "tools"
        ImportFileKind.SubDatumCsv -> "sub-datums"
        ImportFileKind.Settings -> "settings"
        ImportFileKind.Profile -> "profile"
        ImportFileKind.AdapterProfile -> "adapter-profile"
        ImportFileKind.ProbeProfiles -> "probe-profiles"
        ImportFileKind.Unknown -> "?"
    }

    private suspend fun activeWorkspace(profile: MachineProfile): Workspace? {
        val id = profile.activeWorkspaceId.ifEmpty { null }
        return id?.let { services.machines.loadWorkspace(it) }
            ?: services.machines.loadFullProfile(profileId)?.activeWorkspace
    }

    private fun failed(fileName: String, kind: ImportKind): Pending =
        Pending.Failed(fileName, kind, ImportProblem.error(MessageKey.FileFailed, "profile $profileId"))

    // -----------------------------------------------------------------------------------------
    // The writes

    private suspend fun writeTools(current: Pending.Tools): Outcome {
        val plan = current.preview.plan(current.operation ?: current.preview.defaultOperation ?: ImportOperation.AddAsNew)
        if (plan.deleteIds.isNotEmpty()) services.tools.deleteTools(plan.deleteIds)
        services.tools.saveTools(profileId, plan.add + plan.update)
        val message = plan.finishedMessage
        return Outcome(message.key.toUiText(*message.args.toTypedArray()), plan.report)
    }

    /** Writes the plan of a sub-datum file in one transaction: the delete (REPLACE_ALL), the points, and the guides. */
    private suspend fun writeSubDatums(current: Pending.SubDatums): Outcome {
        val plan = current.preview.plan(current.operation ?: current.preview.defaultOperation ?: ImportOperation.AddAsNew)
        services.machines.applySubDatumImport(current.workspaceId, plan)
        val message = plan.finishedMessage
        return Outcome(message.key.toUiText(*message.args.toTypedArray()), plan.report)
    }

    private suspend fun writeSettings(current: Pending.Settings): Outcome? {
        val preview = current.preview
        val name = current.name.trim()
        val profile = preview.profile(name)
        if (current.newProfile) {
            val created = when (val result = services.machines.createProfile(profile)) {
                is StoreResult.Refused -> {
                    mutable.update { state -> state.copy(stage = ImportStage.Preview, nameError = result.refusal.toUiText()) }
                    return null
                }
                is StoreResult.Ok -> result.value
            }
            applyProbeValues(created, preview)
            return Outcome(UiText.of(Res.string.import_profile_created, created.name, preview.changeCount), preview.report)
        }
        val stored = current.target
        if (profile.machineType != stored.machineType) services.machines.changeMachineType(stored.id, profile.machineType)
        val update = profile.copy(id = stored.id, createdOn = stored.createdOn)
        when (val result = services.machines.updateProfile(update)) {
            is StoreResult.Refused -> {
                mutable.update { state -> state.copy(stage = ImportStage.Preview, nameError = result.refusal.toUiText()) }
                return null
            }
            is StoreResult.Ok -> applyProbeValues(result.value, preview)
        }
        return Outcome(UiText.of(Res.string.import_profile_updated, preview.changeCount, update.name), preview.report)
    }

    /**
     * Gives the probe values of a `.tdset` file their place: the contact type and the
     * debounce go to the adapter profile of [profile], and the edge finder values go to a new probe
     * profile in the probe library of [profile]. The report tells it.
     */
    private suspend fun applyProbeValues(profile: MachineProfile, preview: SettingsImportPreview) {
        val values = preview.probeValues
        val adapterId = profile.adapterProfileId
        if (adapterId != null && (values.contactType != null || values.debounceMs != null)) {
            services.adapters.loadAdapterProfile(adapterId)?.let { adapter ->
                services.adapters.saveAdapterProfile(
                    adapter.copy(
                        probeContactType = values.contactType ?: adapter.probeContactType,
                        probeDebounceMs = values.debounceMs ?: adapter.probeDebounceMs,
                    ),
                )
            }
        }
        if (values.xOffsetUm != null || values.yOffsetUm != null || values.sampleCount != null || values.spreadLimitUm != null) {
            val probe = ProbeProfile(
                profileId = profile.id,
                type = ProbeType.EdgeFinder2D,
                name = UiText.of(Res.string.import_probe_profile_name).loadString(environment),
                xOffsetUm = values.xOffsetUm ?: 0.0,
                yOffsetUm = values.yOffsetUm ?: 0.0,
                metric = profile.metricDefault,
                sampleCount = values.sampleCount ?: ProbeProfile.DEFAULT_SAMPLE_COUNT,
                spreadLimitUm = values.spreadLimitUm ?: ProbeProfile.DEFAULT_SPREAD_LIMIT_UM,
            )
            val saved = services.probes.saveProbeProfile(probe)
            if (saved is StoreResult.Ok && profile.probeRoles.edgeFinderProfileId == null) {
                services.machines.updateProfile(profile.id) { stored -> stored.copy(probeRoles = stored.probeRoles.copy(edgeFinderProfileId = saved.value.id)) }
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // The UI state

    private fun show(next: Pending) {
        pending = next
        mutable.value = when (next) {
            is Pending.Failed -> ImportUiState(
                stage = ImportStage.Preview,
                kind = next.kind,
                fileName = next.fileName,
                kindText = kindText(next.kind, tdset = false),
                fileErrors = listOf(ImportUiTexts.problem(next.problem)),
            )
            is Pending.Tools -> {
                val operation = next.operation ?: next.preview.defaultOperation
                next.operation = operation
                val plan = next.preview.plan(operation ?: ImportOperation.AddAsNew)
                val format = next.profile.display.readoutFormat
                base(next, next.preview.problems, next.preview.usable, next.preview.metadata.source, next.preview.metadata.profile, next.preview.metadata.exported).copy(
                    toImport = plan.preview.toImport.map { row -> toolRow(row, next.profile.metricDefault, format) },
                    skipped = plan.preview.skipped.map { row -> toolRow(row, next.profile.metricDefault, format) },
                    warnings = plan.preview.warnings.map(ImportUiTexts::problem),
                    operations = next.preview.operations,
                    operation = operation,
                )
            }
            is Pending.SubDatums -> {
                val operation = next.operation ?: next.preview.defaultOperation
                next.operation = operation
                val plan = next.preview.plan(operation ?: ImportOperation.AddAsNew)
                val metric = next.preview.metric
                base(next, next.preview.problems, next.preview.usable, next.preview.metadata.source, next.preview.metadata.profile, next.preview.metadata.exported).copy(
                    toImport = plan.preview.toImport.map { row -> subDatumRow(row, metric) },
                    skipped = plan.preview.skipped.map { row -> subDatumRow(row, metric) },
                    warnings = plan.preview.warnings.map(ImportUiTexts::problem),
                    operations = next.preview.operations,
                    operation = operation,
                    metric = metric,
                )
            }
            is Pending.Settings -> {
                val preview = next.preview.preview
                base(next, next.preview.problems, next.preview.usable, next.preview.source, next.preview.fileProfileName, next.preview.exported).copy(
                    kindText = kindText(ImportKind.Profile, tdset = next.tdset),
                    toImport = preview.toImport.map { row -> settingRow(row, next.newProfile) },
                    skipped = preview.skipped.map { row -> settingRow(row, next.newProfile) },
                    warnings = preview.warnings.map(ImportUiTexts::problem),
                    newProfile = next.newProfile,
                    name = next.name,
                )
            }
        }
    }

    private fun base(
        next: Pending,
        problems: List<ImportProblem>,
        usable: Boolean,
        source: FileSource?,
        profileName: String?,
        exported: String?,
    ) = ImportUiState(
        stage = ImportStage.Preview,
        kind = next.kind,
        fileName = next.fileName,
        kindText = kindText(next.kind, tdset = false),
        provenance = provenance(source, profileName, exported),
        fileErrors = problems.filter { it.severity == Severity.Error }.map(ImportUiTexts::problem),
        usable = usable,
    )

    private fun provenance(source: FileSource?, profileName: String?, exported: String?): UiText? {
        if (source == null && profileName == null && exported == null) return null
        val app = when (source) {
            FileSource.TouchDro -> "TouchDRO"
            FileSource.SuperDro -> "SuperDRO"
            null -> "—"
        }
        return if (profileName != null) {
            UiText.of(Res.string.import_provenance, app, profileName, exported ?: "—")
        } else {
            UiText.of(Res.string.import_provenance_time, app, exported ?: "—")
        }
    }

    private fun kindText(kind: ImportKind, tdset: Boolean): UiText = UiText.of(
        when (kind) {
            ImportKind.ToolCsv -> Res.string.import_kind_tools
            ImportKind.SubDatumCsv -> Res.string.import_kind_sub_datums
            ImportKind.Profile -> if (tdset) Res.string.import_kind_tdset else Res.string.import_kind_profile
        },
    )

    private fun toolRow(row: PreviewRow<Tool>, profileMetric: Boolean, format: ReadoutFormat) = ImportRowUi(
        row = row.row,
        label = row.label,
        detail = row.value?.let { tool -> ToolTexts.row(tool, profileMetric, format) },
        action = row.action,
        problems = row.problems.map(ImportUiTexts::problem),
    )

    private fun subDatumRow(row: PreviewRow<SubDatumItem>, metric: Boolean) = ImportRowUi(
        row = row.row,
        label = row.label,
        detail = when (val item = row.value) {
            is SubDatumItem.Point -> UiText.of(
                Res.string.import_row_point,
                coordinate(item.subDatum.x, metric),
                coordinate(item.subDatum.y, metric),
                coordinate(item.subDatum.z, metric),
                UiText.of(LengthTexts.unit(metric)),
            )
            is SubDatumItem.Line -> UiText.of(
                Res.string.import_row_guide,
                if (item.guide.axis == AxisId.Angular) "A" else item.guide.axis.name,
                LengthTexts.format(item.guide.valueUm, metric),
                UiText.of(LengthTexts.unit(metric)),
            )
            null -> null
        },
        action = row.action,
        problems = row.problems.map(ImportUiTexts::problem),
    )

    private fun coordinate(value: Double?, metric: Boolean): Any =
        if (value == null) UiText.of(Res.string.import_value_none) else LengthTexts.format(value, metric)

    private fun settingRow(row: PreviewRow<SettingRow>, newProfile: Boolean): ImportRowUi {
        val setting = row.value
        val currentValue = setting?.currentValue
        val detail = when {
            setting == null -> null
            newProfile || currentValue == null -> UiText.of(Res.string.import_row_setting_new, setting.newValue ?: setting.fileValue)
            else -> UiText.of(Res.string.import_row_setting, currentValue, setting.newValue ?: setting.fileValue)
        }
        return ImportRowUi(row.row, row.label, detail, row.action, row.problems.map(ImportUiTexts::problem))
    }

    private fun mimeTypes(kind: ImportKind): List<String> = when (kind) {
        ImportKind.ToolCsv, ImportKind.SubDatumCsv -> listOf("text/csv", "text/comma-separated-values", "application/csv", "text/plain")
        ImportKind.Profile -> listOf("application/json", "application/octet-stream", "*/*")
    }

    /** The result of a write: the message and the report. */
    private data class Outcome(val message: UiText, val report: ImportReport?)

    /** The file of the preview, with its choices. */
    private sealed class Pending(val fileName: String, val kind: ImportKind) {
        abstract val operations: List<ImportOperation>
        var operation: ImportOperation? = null

        class Tools(fileName: String, val preview: ToolImportPreview, val profile: MachineProfile) : Pending(fileName, ImportKind.ToolCsv) {
            override val operations: List<ImportOperation> get() = preview.operations
        }

        class SubDatums(
            fileName: String,
            val content: ByteArray,
            val preview: SubDatumImportPreview,
            val profile: MachineProfile,
            val workspaceId: String,
        ) : Pending(fileName, ImportKind.SubDatumCsv) {
            override val operations: List<ImportOperation> get() = preview.operations
        }

        class Settings(
            fileName: String,
            val content: ByteArray,
            val preview: SettingsImportPreview,
            val target: MachineProfile,
            val newProfile: Boolean,
            val tdset: Boolean,
            var name: String,
        ) : Pending(fileName, ImportKind.Profile) {
            override val operations: List<ImportOperation> get() = emptyList()
        }

        class Failed(fileName: String, kind: ImportKind, val problem: ImportProblem) : Pending(fileName, kind) {
            override val operations: List<ImportOperation> get() = emptyList()
        }
    }

    companion object {
        /** The name of the presenter in the presenters of the tab. */
        const val NAME: String = "functions.import"
    }
}
