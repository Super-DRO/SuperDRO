package org.superdro.core.ui.adapter.probe

import androidx.compose.runtime.Immutable
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.SessionState
import org.superdro.core.functions.tipcal.CalibrationRecord
import org.superdro.core.functions.tipcal.CalibrationRecords
import org.superdro.core.functions.tipcal.SpreadLimit
import org.superdro.core.functions.tipcal.SpreadLimits
import org.superdro.core.model.AxisId
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.ProbeType
import org.superdro.core.model.Workspace
import org.superdro.core.model.newId
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.readout.input.InputSnapshot
import org.superdro.core.store.StoreResult
import org.superdro.core.ui.adapter.AdapterUiDeps
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.toUiText

/**
 * The calibration values of the stored profile that the screen shows: the green mark of
 * the diameter field and the calibration record.
 */
@Immutable
data class ProbeCalibrationInfo(val effectiveDiameterUm: Double?, val calibratedOn: Instant?, val residualUm: Double?)

/**
 * The state of the probe profile screen.
 *
 * @property autoName True while the name field is empty: the screen shows the automatic name.
 * @property spreadHint The spread limit that applies when the field "Max. touch spread" is empty,
 *   and its source: "(default)" or "(measured)".
 * @property canSync True when the "use the current reading" buttons work: the session is `Connected`.
 * @property record The calibration record that shows in the screen, or null.
 * @property refusal The text of a refused save, or null.
 * @property openCalibration The probe profile ID of the tip calibration to open after "Calibrate", or null.
 * @property done True after the save: the screen closes.
 */
@Immutable
data class ProbeProfileUiState(
    val loaded: Boolean = false,
    val notFound: Boolean = false,
    val isNew: Boolean = true,
    val standardTip: Boolean = false,
    val form: ProbeProfileForm = ProbeProfileForm(),
    val autoName: Boolean = true,
    val errors: Map<ProbeField, UiText> = emptyMap(),
    val spreadHint: SpreadLimit = SpreadLimits.of(null),
    val calibration: ProbeCalibrationInfo = ProbeCalibrationInfo(null, null, null),
    val canSync: Boolean = false,
    val record: CalibrationRecord? = null,
    val refusal: UiText? = null,
    val openCalibration: String? = null,
    val done: Boolean = false,
) {
    /** The type of the profile. A saved profile keeps its type. */
    val type: ProbeType get() = form.type

    /** True when the diameter field equals the calibrated diameter (the green mark). */
    val diameterCalibrated: Boolean
        get() = ProbeProfileSave.matchesCalibration(form, calibration.effectiveDiameterUm, calibration.calibratedOn)

    /** True when the "Calibrate" button shows: the type uses a diameter and has a port. */
    val canCalibrate: Boolean get() = ProbeTypeRules.canCalibrate(form.type, form.port)
}

/**
 * The presenter of the probe profile screen. [probeProfileId] null makes a new
 * profile of the type "Touch probe" for the machine profile [device] (null: for all machine
 * profiles).
 *
 * The lengths of the fields are in the units of the profile. A change of the units converts the
 * texts. The save follows the rules of [ProbeProfileSave].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProbeProfilePresenter(
    private val deps: AdapterUiDeps,
    private val probeProfileId: String?,
    private val device: DeviceId?,
    private val ids: () -> String = ::newId,
) : ScopedPresenter(deps.context) {

    private val mutable = MutableStateFlow(ProbeProfileUiState(form = ProbeProfileForm(metric = true)))

    /** The UI state of the screen. */
    val uiState: StateFlow<ProbeProfileUiState> = mutable.asStateFlow()

    private var existing: ProbeProfile? = null
    private var frame: ReadoutFrame? = null
    private var watching: Boolean = false

    init {
        scope.launch { load() }
    }

    private suspend fun load() {
        val id = probeProfileId
        if (id == null) {
            val metric = device?.let { deps.machines.loadProfile(it.value) }?.metricDefault ?: true
            mutable.update { state -> state.copy(loaded = true, isNew = true, form = ProbeProfileForm(metric = metric)) }
            watchReadings(device)
            return
        }
        val profile = deps.probes.loadProbeProfile(id)
        if (profile == null) {
            mutable.update { state -> state.copy(loaded = true, notFound = true) }
            return
        }
        existing = profile
        mutable.update { state ->
            state.copy(
                loaded = true,
                isNew = false,
                standardTip = profile.standardTip,
                form = ProbeProfileForm.of(profile),
                autoName = false,
                spreadHint = SpreadLimits.of(profile.copy(spreadLimitUm = null)),
                calibration = calibrationOf(profile),
            )
        }
        watchReadings(profile.profileId?.let(::DeviceId) ?: device)
        watchCalibration(profile)
    }

    /** The user selects the type of a new profile. A saved profile keeps its type. */
    fun setType(type: ProbeType) = edit { state ->
        if (!state.isNew) state else state.copy(form = state.form.copy(type = type, port = ProbeTypeRules.fixPort(type, state.form.port)))
    }

    /** The user types the name. An empty name gives the automatic name again. */
    fun setName(text: String) = edit { state ->
        val name = text.take(ProbeProfileForm.MAX_NAME_LENGTH)
        state.copy(form = state.form.copy(name = name), autoName = name.isEmpty())
    }

    /** The user selects the units of the profile. The lengths of the fields change to the new unit. */
    fun setMetric(metric: Boolean) = edit { state ->
        val form = state.form
        if (form.metric == metric) return@edit state
        fun convert(text: String) = ProbeLengths.convertText(text, form.metric, metric)
        state.copy(
            form = form.copy(
                metric = metric,
                diameter = convert(form.diameter),
                maxSpread = convert(form.maxSpread),
                xOffset = convert(form.xOffset),
                yOffset = convert(form.yOffset),
                zOffset = convert(form.zOffset),
            ),
        )
    }

    /** The user selects the trigger port. A port that the type does not permit changes to the default. */
    fun setPort(port: Char?) = edit { state -> state.copy(form = state.form.copy(port = ProbeTypeRules.fixPort(state.form.type, port))) }

    fun setDiameter(text: String) = edit { state -> state.copy(form = state.form.copy(diameter = text), errors = state.errors - ProbeField.Diameter) }

    fun setSampleCount(text: String) = edit { state -> state.copy(form = state.form.copy(sampleCount = text), errors = state.errors - ProbeField.SampleCount) }

    fun setMaxSpread(text: String) = edit { state -> state.copy(form = state.form.copy(maxSpread = text), errors = state.errors - ProbeField.MaxSpread) }

    fun setXOffset(text: String) = edit { state -> state.copy(form = state.form.copy(xOffset = text), errors = state.errors - ProbeField.XOffset) }

    fun setYOffset(text: String) = edit { state -> state.copy(form = state.form.copy(yOffset = text), errors = state.errors - ProbeField.YOffset) }

    fun setZOffset(text: String) = edit { state -> state.copy(form = state.form.copy(zOffset = text), errors = state.errors - ProbeField.ZOffset) }

    /**
     * "Use the current reading for this axis": the offset field of [axis] (X, Y, or Z)
     * gets the shown value of the axis in the readout of the machine. Only while the session is
     * `Connected`.
     */
    fun sync(axis: AxisId) {
        val current = frame ?: return
        if (!mutable.value.canSync) return
        val shown = current.reading(axis).shown
        if (!shown.isFinite()) return
        edit { state ->
            val text = ProbeLengths.text(shown, state.form.metric)
            when (axis) {
                AxisId.X -> state.copy(form = state.form.copy(xOffset = text), errors = state.errors - ProbeField.XOffset)
                AxisId.Y -> state.copy(form = state.form.copy(yOffset = text), errors = state.errors - ProbeField.YOffset)
                AxisId.Z -> state.copy(form = state.form.copy(zOffset = text), errors = state.errors - ProbeField.ZOffset)
                else -> state
            }
        }
    }

    /** Saves the profile and closes the screen. [autoName] is the automatic name in the language of the user. */
    fun save(autoName: String) {
        scope.launch { write(autoName)?.let { mutable.update { state -> state.copy(done = true) } } }
    }

    /** "Calibrate": saves the profile first, then opens the tip calibration. */
    fun calibrate(autoName: String) {
        scope.launch {
            val stored = write(autoName) ?: return@launch
            mutable.update { state -> state.copy(openCalibration = stored.id) }
        }
    }

    /** The screen opened the tip calibration. */
    fun calibrationOpened() = mutable.update { state -> state.copy(openCalibration = null) }

    /** Shows the calibration record of the stored profile. */
    fun showRecord() {
        val profile = existing ?: return
        mutable.update { state -> state.copy(record = CalibrationRecords.of(profile)) }
    }

    /** Closes the calibration record. */
    fun dismissRecord() = mutable.update { state -> state.copy(record = null) }

    /** Closes the refusal text. */
    fun dismissRefusal() = mutable.update { state -> state.copy(refusal = null) }

    private suspend fun write(autoName: String): ProbeProfile? {
        val state = mutable.value
        if (!state.loaded || state.notFound || state.standardTip) return null
        val machineProfileId = existing?.profileId ?: device?.value
        when (val result = ProbeProfileSave.build(state.form, existing, machineProfileId, autoName, ids())) {
            is ProbeSaveResult.Invalid -> {
                mutable.update { current -> current.copy(errors = result.errors) }
                return null
            }
            is ProbeSaveResult.Valid -> return when (val saved = deps.probes.saveProbeProfile(result.profile)) {
                is StoreResult.Ok -> {
                    existing = saved.value
                    watchCalibration(saved.value)
                    mutable.update { current -> current.copy(isNew = false, errors = emptyMap(), autoName = false, form = current.form.copy(name = saved.value.name)) }
                    saved.value
                }
                is StoreResult.Refused -> {
                    mutable.update { current -> current.copy(refusal = saved.refusal.toUiText()) }
                    null
                }
            }
        }
    }

    private fun edit(transform: (ProbeProfileUiState) -> ProbeProfileUiState) = mutable.update { state ->
        if (state.standardTip) state else transform(state)
    }

    /** Follows the readout of [readingsDevice] for the sync buttons. */
    private fun watchReadings(readingsDevice: DeviceId?) {
        if (readingsDevice == null) return
        val live: Flow<Pair<SessionState, InputSnapshot>?> = deps.session(readingsDevice).flatMapLatest { session ->
            if (session == null) flowOf(null) else combine(session.state, session.inputs) { state, inputs -> state to inputs }
        }
        scope.launch {
            combine(deps.machine(readingsDevice), deps.machines.activeWorkspace(readingsDevice.value), live) { machine, workspace, current ->
                Triple(machine, workspace, current)
            }.collect { (machine, workspace, current) ->
                frame = frameOf(machine, workspace, current?.second)
                val connected = current?.first is SessionState.Connected && frame != null
                mutable.update { state -> state.copy(canSync = connected) }
            }
        }
    }

    /** Follows the stored profile: a new tip calibration (Apply) changes the diameter field. */
    private fun watchCalibration(profile: ProbeProfile) {
        if (watching) return
        watching = true
        val library = profile.profileId?.let { id -> deps.probes.library(id) } ?: deps.probes.sharedProfiles()
        scope.launch {
            library.collect { profiles ->
                val stored = profiles.firstOrNull { item -> item.id == profile.id } ?: return@collect
                val base = existing ?: return@collect
                if (stored.calibratedOn == base.calibratedOn && stored.calibrationSession == base.calibrationSession) return@collect
                existing = base.copy(
                    stylusDiameterUm = stored.stylusDiameterUm,
                    effectiveDiameterUm = stored.effectiveDiameterUm,
                    calibrationResidualUm = stored.calibrationResidualUm,
                    calibratedOn = stored.calibratedOn,
                    calibrationSession = stored.calibrationSession,
                    sessionDigest = stored.sessionDigest,
                )
                mutable.update { state ->
                    val diameter = if (stored.calibratedOn != base.calibratedOn) ProbeLengths.text(stored.stylusDiameterUm, state.form.metric) else state.form.diameter
                    state.copy(
                        form = state.form.copy(diameter = diameter),
                        calibration = calibrationOf(stored),
                        spreadHint = SpreadLimits.of(stored.copy(spreadLimitUm = null)),
                    )
                }
            }
        }
    }

    private fun calibrationOf(profile: ProbeProfile) =
        ProbeCalibrationInfo(profile.effectiveDiameterUm, profile.calibratedOn, profile.calibrationResidualUm)

    private fun frameOf(machine: MachineProfile?, workspace: Workspace?, inputs: InputSnapshot?): ReadoutFrame? {
        if (machine == null || workspace == null || inputs == null) return null
        return ReadoutFrame(profile = machine, workspace = workspace, inputs = inputs.axisValues())
    }
}
