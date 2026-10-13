package org.superdro.core.ui.adapter.encoder.wizard

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.superdro.core.connection.DeviceId
import org.superdro.core.functions.errormap.ErrorMapBuild
import org.superdro.core.functions.errormap.ErrorMapBuilder
import org.superdro.core.functions.errormap.ErrorMapDiagnostics
import org.superdro.core.functions.errormap.ErrorMapReport
import org.superdro.core.functions.errormap.ErrorMapWizardSession
import org.superdro.core.functions.errormap.RecordedPass
import org.superdro.core.functions.errormap.WizardContext
import org.superdro.core.functions.errormap.WizardSaveResult
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.Channels
import org.superdro.core.resources.Res
import org.superdro.core.resources.encoder_report_save_failed
import org.superdro.core.resources.encoder_report_saved
import org.superdro.core.store.StoreResult
import org.superdro.core.ui.adapter.AdapterUiDeps
import org.superdro.core.ui.adapter.encoder.EncoderFormat
import org.superdro.core.ui.adapter.encoder.encoderOf
import org.superdro.core.ui.adapter.encoder.map.MapEditorTexts
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.text.UiText

/** The data of a review: the adapter profile, the passes, and the result of the map builder. */
class WizardReview(
    val adapter: AdapterProfile,
    val session: ErrorMapWizardSession,
    val passes: List<RecordedPass>,
    val verification: RecordedPass?,
    val build: ErrorMapBuild,
)

/** The state of a screen that reads the wizard session: the report or the diagnostics. */
sealed interface ReviewLoad {
    /** The screen reads the session file and builds the map. */
    data object Loading : ReviewLoad

    /** The tab has no adapter profile, or the store has no session file of the input. */
    data object NoSession : ReviewLoad

    /** "Not enough data to build a map." */
    data object NotEnoughData : ReviewLoad
}

/**
 * Reads the wizard session of [input] of the adapter profile of the tab [deviceId] and builds the
 * map with the map builder of `core:functions`. Gives null when there is no session.
 */
suspend fun AdapterUiDeps.loadReview(deviceId: DeviceId, input: Char): WizardReview? {
    val adapter = tabAdapter(deviceId).first() ?: return null
    val letter = Channels.normalize(input)
    val session = adapters.loadWizardSession(adapter.id, letter, ErrorMapWizardSession.serializer()) ?: return null
    if (session.isEmpty) return null
    val passes = session.passes.map { it.toPass() }.filter { !it.isEmpty }
    val verification = session.verification?.toPass()
    return WizardReview(adapter, session, passes, verification, ErrorMapBuilder.build(passes, verification))
}

/**
 * The state of the report screen.
 *
 * @property report The report of the builder, or null with [load].
 * @property saveProblems The problems of a refused save: "The map could not be saved."
 * @property saved True after "Save Map": the screen closes the report and the wizard.
 */
@Immutable
data class ErrorMapReportUiState(
    val input: Char,
    val load: ReviewLoad? = ReviewLoad.Loading,
    val report: ErrorMapReport? = null,
    val saving: Boolean = false,
    val saveProblems: List<UiText> = emptyList(),
    val saved: Boolean = false,
    val message: UiText? = null,
)

/**
 * The presenter of the report screen of the error map wizard. It reads the session file
 * that the wizard wrote at "Review Map" and builds the map with [ErrorMapBuilder] away from the UI
 * thread (the dispatcher of the presenter context).
 *
 * "Save Map" is active only when [ErrorMapReport.canSave] is true: 2 passes or more, at least 1
 * node, and an improvement ratio of 1.0 or more. The save writes the map with the
 * provenance comments and deletes the session file.
 */
class ErrorMapReportPresenter(
    val deviceId: DeviceId,
    input: Char,
    private val deps: AdapterUiDeps,
) : ScopedPresenter(deps.context) {

    /** The lowercase input letter. */
    val input: Char = Channels.normalize(input)

    private val mutable = MutableStateFlow(ErrorMapReportUiState(this.input))
    private var review: WizardReview? = null

    /** The state of the screen. */
    val uiState: StateFlow<ErrorMapReportUiState> = mutable.asStateFlow()

    init {
        scope.launch {
            val loaded = deps.loadReview(deviceId, input)
            review = loaded
            mutable.update { state ->
                when (val build = loaded?.build) {
                    null -> state.copy(load = ReviewLoad.NoSession)
                    ErrorMapBuild.NotEnoughData -> state.copy(load = ReviewLoad.NotEnoughData)
                    is ErrorMapBuild.Built -> state.copy(load = null, report = build.report)
                }
            }
        }
    }

    /** "Save Map". */
    fun onSave() {
        val current = review ?: return
        val report = (current.build as? ErrorMapBuild.Built)?.report ?: return
        if (!report.canSave || mutable.value.saving) return
        val generatedOn = EncoderFormat.dateTimeSeconds(deps.wallClock.now(), deps.timeZone)
        when (val result = report.saveMap(generatedOn)) {
            WizardSaveResult.NotAllowed -> Unit
            is WizardSaveResult.Refused -> mutable.update {
                it.copy(saveProblems = result.problems.map(MapEditorTexts::problem), message = UiText.of(Res.string.encoder_report_save_failed))
            }
            is WizardSaveResult.Saved -> {
                mutable.update { it.copy(saving = true) }
                scope.launch {
                    val adapter = current.adapter
                    val encoder = encoderOf(deps.adapters.loadAdapterProfile(adapter.id) ?: adapter, input)
                    val profile = encoder.copy(errorMap = result.points, errorMapComments = result.provenance.toComments())
                    val written = deps.adapters.saveEncoderProfile(adapter.id, profile)
                    if (written is StoreResult.Ok) {
                        deps.adapters.deleteWizardSession(adapter.id, input)
                        mutable.update { it.copy(saving = false, saved = true, message = UiText.of(Res.string.encoder_report_saved)) }
                    } else {
                        mutable.update { it.copy(saving = false, message = UiText.of(Res.string.encoder_report_save_failed)) }
                    }
                }
            }
        }
    }

    fun onMessageShown() = mutable.update { it.copy(message = null) }
}

/**
 * The state of the diagnostics screen.
 *
 * @property diagnostics The values of the rows, or null with [load].
 */
@Immutable
data class ErrorMapDiagnosticsUiState(
    val input: Char,
    val load: ReviewLoad? = ReviewLoad.Loading,
    val diagnostics: ErrorMapDiagnostics? = null,
)

/**
 * The presenter of the diagnostics screen "Error map diagnostics". It reads the session
 * file and builds the map again, then gives the diagnostics rows ([ErrorMapDiagnostics]).
 * The context rows have the app version, the adapter name, the firmware of the session, and the
 * reference input.
 */
class ErrorMapDiagnosticsPresenter(
    val deviceId: DeviceId,
    input: Char,
    private val deps: AdapterUiDeps,
) : ScopedPresenter(deps.context) {

    /** The lowercase input letter. */
    val input: Char = Channels.normalize(input)

    private val mutable = MutableStateFlow(ErrorMapDiagnosticsUiState(this.input))

    /** The state of the screen. */
    val uiState: StateFlow<ErrorMapDiagnosticsUiState> = mutable.asStateFlow()

    init {
        scope.launch {
            val loaded = deps.loadReview(deviceId, input)
            val firmware = deps.session(deviceId).first()?.adapterInfo?.value?.values?.let { values ->
                listOfNotNull(values.version.value, values.build.value).joinToString(", ").ifEmpty { null }
            }
            mutable.update { state ->
                when (val build = loaded?.build) {
                    null -> state.copy(load = ReviewLoad.NoSession)
                    ErrorMapBuild.NotEnoughData -> state.copy(load = ReviewLoad.NotEnoughData)
                    is ErrorMapBuild.Built -> {
                        val context = WizardContext(
                            appVersion = deps.appVersion,
                            adapterName = loaded.adapter.alias,
                            firmware = firmware,
                            referenceInput = loaded.session.reference?.firstOrNull(),
                        )
                        state.copy(load = null, diagnostics = ErrorMapDiagnostics.of(build.report, loaded.passes, context))
                    }
                }
            }
        }
    }
}
