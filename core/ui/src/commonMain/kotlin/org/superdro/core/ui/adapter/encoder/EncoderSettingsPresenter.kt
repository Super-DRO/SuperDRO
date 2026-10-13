package org.superdro.core.ui.adapter.encoder

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.superdro.core.functions.errormap.MapEditor
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.AxisId
import org.superdro.core.model.BacklashMode
import org.superdro.core.model.CalibrationStatus
import org.superdro.core.model.Channels
import org.superdro.core.model.EncoderKind
import org.superdro.core.model.EncoderProfile
import org.superdro.core.model.EncoderType
import org.superdro.core.model.MachineType
import org.superdro.core.model.MapPoint
import org.superdro.core.readout.encoder.EncoderProfileCheck
import org.superdro.core.resources.Res
import org.superdro.core.resources.encoder_calibrate_offline
import org.superdro.core.resources.encoder_save_failed
import org.superdro.core.store.StoreResult
import org.superdro.core.ui.adapter.AdapterUiDeps
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.toUiText

/**
 * The calibration record of the input for the screen.
 *
 * @property date The local date and time `yyyy-MM-dd HH:mm`, or null.
 */
@Immutable
data class CalibrationUi(
    val status: CalibrationStatus = CalibrationStatus.NotCalibrated,
    val date: String? = null,
    val machineType: MachineType? = null,
    val axis: AxisId? = null,
)

/**
 * The summary of a stored map: "%1$d intervals (%2$d to %3$d)". Null in the state means
 * "Not set".
 */
@Immutable
data class MapSummaryLine(val intervals: Int, val first: Long, val last: Long)

/**
 * The state of the encoder settings screen of one input.
 *
 * @property available False while the source has no adapter profile: the tab selects no adapter
 *   profile, or the stored profile is gone. The screen then shows a status text and no form.
 * @property problems The problems of the stored maps at each load: the text of each map
 *   check of `core:readout`.
 * @property confirmDeleteBacklashMap True while the question "Delete the backlash map?" shows.
 */
@Immutable
data class EncoderSettingsUiState(
    val input: Char,
    val online: Boolean,
    val loaded: Boolean = false,
    val available: Boolean = false,
    val adapterName: String = "",
    val form: EncoderForm = EncoderForm(),
    val visibility: EncoderVisibility = EncoderSettingsRules.visibility(EncoderForm(), input),
    val errors: EncoderFieldErrors = EncoderFieldErrors(),
    val calibration: CalibrationUi = CalibrationUi(),
    val errorMap: MapSummaryLine? = null,
    val backlashMap: MapSummaryLine? = null,
    val problems: List<UiText> = emptyList(),
    val confirmDeleteBacklashMap: Boolean = false,
    val message: UiText? = null,
)

/**
 * The presenter of the encoder settings screen of one input. The screen
 * opens online from the adapter details or from a tap on "Error" of the readout, and offline from
 * the adapters list.
 *
 * Rules:
 * - Each change that gives a valid profile goes to the store at once
 *   (`AdapterRepository.saveEncoderProfile`). The session of the tab then uses it at once (the
 *   `DeviceSetupSource` of `core:connection`). A field with an error writes nothing.
 * - A change that removes a stored backlash map asks "Delete the backlash map?" first.
 * - A change of the stored profile from a different screen (the calibration wizard, the map
 *   editor) fills the form again. The values that this presenter wrote do not.
 * - "Calibrate" needs the online source.
 */
class EncoderSettingsPresenter(
    val source: EncoderSource,
    input: Char,
    private val deps: AdapterUiDeps,
) : ScopedPresenter(deps.context) {

    /** The lowercase input letter. */
    val input: Char = Channels.normalize(input)

    private val mutable = MutableStateFlow(EncoderSettingsUiState(this.input, source.online))
    private val writeLock = Mutex()
    private var adapterId: String? = null
    private var stored: EncoderProfile? = null
    private val written = ArrayDeque<EncoderProfile>()
    private var pending: EncoderProfile? = null

    /** The state of the screen. */
    val uiState: StateFlow<EncoderSettingsUiState> = mutable.asStateFlow()

    init {
        scope.launch { deps.adapterOf(source).collect(::onAdapter) }
    }

    private fun onAdapter(adapter: AdapterProfile?) {
        if (adapter == null) {
            adapterId = null
            stored = null
            mutable.update { it.copy(loaded = true, available = false) }
            return
        }
        adapterId = adapter.id
        val profile = encoderOf(adapter, input)
        val ownWrite = written.any { it == profile }
        stored = profile
        mutable.update { state ->
            val form = if (ownWrite && state.available) state.form else EncoderSettingsRules.formOf(profile)
            state.copy(
                loaded = true,
                available = true,
                adapterName = adapter.alias,
                form = form,
                visibility = EncoderSettingsRules.visibility(form, input),
                errors = if (ownWrite && state.available) state.errors else EncoderFieldErrors(),
                calibration = calibrationOf(profile),
                errorMap = summaryOf(profile.errorMap),
                backlashMap = summaryOf(profile.backlashMap),
                problems = EncoderProfileCheck.problems(profile).map { problem -> problem.message.toUiText() },
            )
        }
    }

    private fun calibrationOf(profile: EncoderProfile): CalibrationUi = CalibrationUi(
        status = profile.calibration.status,
        date = profile.calibration.calibratedOn?.let { EncoderFormat.dateTime(it, deps.timeZone) },
        machineType = profile.calibration.machineType,
        axis = profile.calibration.axisRole,
    )

    // ---------------------------------------------------------------------------------------
    // Events

    fun onType(type: EncoderType) = change { it.copy(type = type) }

    fun onKind(kind: EncoderKind) = change { it.copy(kind = kind) }

    fun onResolution(text: String) = change { it.copy(resolution = text) }

    fun onCountsPerScrewRev(text: String) = change { it.copy(countsPerScrewRev = text) }

    fun onReversed(reversed: Boolean) = change { it.copy(reversed = reversed) }

    fun onFilter(filter: Boolean) = change { it.copy(filter = filter) }

    fun onHysteresis(text: String) = change { it.copy(hysteresis = text) }

    fun onBacklashMode(mode: BacklashMode) = change { it.copy(backlashMode = mode) }

    fun onBacklashConstant(text: String) = change { it.copy(backlashConstant = text) }

    fun onCyclic(cyclic: Boolean) = change { it.copy(cyclic = cyclic) }

    /** "Delete" of the question "Delete the backlash map?": the change goes to the store. */
    fun onConfirmDeleteBacklashMap() {
        val profile = pending ?: return
        pending = null
        mutable.update { it.copy(confirmDeleteBacklashMap = false) }
        write(profile)
    }

    /** "Cancel" of the question: the form goes back to the stored profile. */
    fun onCancelDeleteBacklashMap() {
        pending = null
        val profile = stored
        mutable.update { state ->
            val form = profile?.let(EncoderSettingsRules::formOf) ?: state.form
            state.copy(confirmDeleteBacklashMap = false, form = form, visibility = EncoderSettingsRules.visibility(form, input), errors = EncoderFieldErrors())
        }
    }

    /**
     * "Calibrate": gives true when the screen can open the calibration wizard of the input. The
     * offline source shows a message and gives false. A form with an error gives false.
     */
    fun onCalibrate(): Boolean {
        val state = mutable.value
        if (!state.available) return false
        if (!state.visibility.calibrate) return false
        if (!source.online) {
            mutable.update { it.copy(message = UiText.of(Res.string.encoder_calibrate_offline)) }
            return false
        }
        return state.errors.isEmpty
    }

    /** The map editor of the error map or the backlash map: gives true when the screen can open it. */
    fun onEditMap(): Boolean {
        val state = mutable.value
        return state.available && state.errors.isEmpty && !state.confirmDeleteBacklashMap
    }

    /** The screen showed [message]. */
    fun onMessageShown() = mutable.update { it.copy(message = null) }

    // ---------------------------------------------------------------------------------------
    // Private parts

    private fun change(transform: (EncoderForm) -> EncoderForm) {
        val base = stored ?: return
        val form = transform(mutable.value.form)
        when (val build = EncoderSettingsRules.build(base, form)) {
            is EncoderBuild.Invalid -> mutable.update { it.copy(form = form, visibility = EncoderSettingsRules.visibility(form, input), errors = build.errors) }
            is EncoderBuild.Ok -> {
                mutable.update { it.copy(form = form, visibility = EncoderSettingsRules.visibility(form, input), errors = EncoderFieldErrors()) }
                if (build.profile == base) return
                if (build.deletesBacklashMap) {
                    pending = build.profile
                    mutable.update { it.copy(confirmDeleteBacklashMap = true) }
                } else {
                    write(build.profile)
                }
            }
        }
    }

    private fun write(profile: EncoderProfile) {
        val id = adapterId ?: return
        written.addLast(profile)
        while (written.size > WRITE_HISTORY) written.removeFirst()
        stored = profile
        scope.launch {
            val result = writeLock.withLock { deps.adapters.saveEncoderProfile(id, profile) }
            if (result is StoreResult.Refused) mutable.update { it.copy(message = UiText.of(Res.string.encoder_save_failed)) }
        }
    }

    private companion object {
        const val WRITE_HISTORY = 16

        fun summaryOf(points: List<MapPoint>): MapSummaryLine? {
            if (points.isEmpty()) return null
            val summary = MapEditor.summary(points)
            return MapSummaryLine(summary.intervalCount, summary.firstPosition ?: 0L, summary.lastPosition ?: 0L)
        }
    }
}
