package org.superdro.core.ui.adapter.probe

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.toLocalDateTime
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.SessionState
import org.superdro.core.functions.tipcal.ClockPosition
import org.superdro.core.functions.tipcal.ResultCheckKind
import org.superdro.core.functions.tipcal.ResumeResult
import org.superdro.core.functions.tipcal.SpreadLimits
import org.superdro.core.functions.tipcal.TipCalibrationError
import org.superdro.core.functions.tipcal.TipCalibrationOutcome
import org.superdro.core.functions.tipcal.TipCalibrationReadiness
import org.superdro.core.functions.tipcal.TipCalibrationResult
import org.superdro.core.functions.tipcal.TipCalibrationRun
import org.superdro.core.functions.tipcal.TipCalibrationSessions
import org.superdro.core.functions.tipcal.TipCalibrationSetup
import org.superdro.core.functions.tipcal.TipCalibrationStep
import org.superdro.core.functions.tipcal.Touch
import org.superdro.core.functions.tipcal.TouchCollection
import org.superdro.core.functions.tipcal.TouchPrompt
import org.superdro.core.functions.tipcal.TouchResult
import org.superdro.core.functions.tipcal.ContinueResult
import org.superdro.core.functions.tipcal.TouchStatistics
import org.superdro.core.functions.tipcal.RecenterMarker
import org.superdro.core.model.AxisId
import org.superdro.core.model.Channels
import org.superdro.core.model.ProbeProfile
import org.superdro.core.readout.input.InputSnapshot
import org.superdro.core.readout.input.ProbeEvent
import org.superdro.core.store.files.FileTimes
import org.superdro.core.ui.adapter.AdapterUiDeps
import org.superdro.core.ui.presenter.ScopedPresenter

/** The steps of the tip calibration. */
enum class TipStep {
    /** "Ring Gauge Size": the diameter and the unit of the ring gauge. */
    Gauge,

    /** The touches at one clock position. */
    Touches,

    /** "Back to the middle": only before 12 o'clock. */
    Recenter,

    /** The result with "Apply" and "Discard". */
    Result,
}

/**
 * An error dialog of the tip calibration with "Retry" and "Cancel".
 *
 * @property spreadUm The spread of a scattered position, or null.
 * @property limitUm The spread limit of a scattered position, or null.
 * @property check The refused result check, or null.
 * @property result The refused result, or null.
 */
@Immutable
data class TipErrorUi(
    val error: TipCalibrationError,
    val spreadUm: Double? = null,
    val limitUm: Double? = null,
    val check: ResultCheckKind? = null,
    val result: TipCalibrationResult? = null,
)

/**
 * The state of the tip calibration screen.
 *
 * @property metric The units of the profile: the texts of the lengths.
 * @property readiness The failed readiness check, or null when the calibration can continue.
 * @property resumePositions The number of positions of an unfinished run that can continue, or null.
 * @property resumeRefused True when a stored run cannot continue ("Calibration Interrupted").
 * @property touchRefused True when the last trigger had no position: "could not record that touch".
 * @property liveXUm The live X position of the "Back to the middle" step, or null.
 * @property check The kind of the result dialog: [ResultCheckKind.Normal] or [ResultCheckKind.NominalMismatch].
 */
@Immutable
data class TipCalibrationUiState(
    val loaded: Boolean = false,
    val profileName: String = "",
    val metric: Boolean = true,
    val port: Char? = null,
    val nominalUm: Double = 0.0,
    val readiness: TipCalibrationReadiness? = null,
    val resumePositions: Int? = null,
    val resumeRefused: Boolean = false,
    val step: TipStep = TipStep.Gauge,
    val gaugeText: String = "",
    val gaugeMetric: Boolean = true,
    val clock: ClockPosition? = null,
    val captured: Set<ClockPosition> = emptySet(),
    val prompt: TouchPrompt = TouchPrompt.FirstPosition,
    val touches: Int = 0,
    val touchRefused: Boolean = false,
    val marker: RecenterMarker? = null,
    val liveXUm: Double? = null,
    val result: TipCalibrationResult? = null,
    val check: ResultCheckKind? = null,
    val error: TipErrorUi? = null,
    val done: Boolean = false,
) {
    /** "Continue" of the touch step is enabled from 3 touches. */
    val canContinue: Boolean get() = touches >= TouchCollection.MINIMUM_TOUCHES
}

/**
 * The presenter of the tip calibration of the probe profile [probeProfileId]. The touches come
 * from the probe events of the session of the
 * machine of the probe on the port of the profile. The positions are the latched X and Y of each event.
 *
 * The math is in `core:functions` (package `tipcal`): [TipCalibrationRun], [TouchCollection],
 * [TouchStatistics], [TipCalibrationSessions]. The spread of a position is the largest distance
 * between two touches.
 *
 * @param fallbackDevice The selected tab. The machine of the probe is the machine profile of the
 *   probe, or this device for a profile for all machine profiles.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TipCalibrationPresenter(
    private val deps: AdapterUiDeps,
    private val probeProfileId: String,
    private val fallbackDevice: DeviceId?,
) : ScopedPresenter(deps.context) {

    private val mutable = MutableStateFlow(TipCalibrationUiState())

    /** The UI state of the screen. */
    val uiState: StateFlow<TipCalibrationUiState> = mutable.asStateFlow()

    /** The machine of the probe, after the load. */
    var device: DeviceId? = null
        private set

    private var sounds = ProbeSounds(deps.sounds, fallbackDevice)
    private val mutex = Mutex()
    private var profile: ProbeProfile? = null
    private var run: TipCalibrationRun? = null
    private var resumable: TipCalibrationRun? = null
    private var collection: TouchCollection? = null
    private var sessionState: SessionState? = null
    private var inputs: InputSnapshot = InputSnapshot.EMPTY
    private var started: Boolean = false

    init {
        scope.launch { load() }
    }

    /** Follows the session of [machine]: the state, the inputs, and the probe events. */
    private fun watch(machine: DeviceId) {
        val session = deps.session(machine)
        scope.launch {
            session.flatMapLatest { current -> current?.state ?: flowOf(null) }.collect { state ->
                sessionState = state
                refreshReadiness()
            }
        }
        scope.launch {
            session.flatMapLatest { current -> current?.inputs ?: flowOf(InputSnapshot.EMPTY) }.collect { snapshot ->
                inputs = snapshot
                mutable.update { state -> state.copy(liveXUm = snapshot.axisValue(AxisId.X)) }
                refreshReadiness()
            }
        }
        scope.launch {
            session.flatMapLatest { current -> current?.probeEvents ?: emptyFlow() }.collect { event -> onProbeEvent(event) }
        }
    }

    private suspend fun load() {
        val stored = deps.probes.loadProbeProfile(probeProfileId)
        profile = stored
        val machine = stored?.profileId?.let(::DeviceId) ?: fallbackDevice
        device = machine
        sounds = ProbeSounds(deps.sounds, machine)
        if (machine != null) watch(machine)
        if (stored == null) {
            mutable.update { state -> state.copy(loaded = true, readiness = TipCalibrationReadiness.ProfileGone) }
            return
        }
        var resumeCount: Int? = null
        var refused = false
        val session = TipCalibrationSessions.decode(stored.calibrationSession)
        if (session != null && !session.complete) {
            when (val resumed = TipCalibrationSessions.resume(stored.calibrationSession)) {
                is ResumeResult.Resumed -> if (resumed.run.gaugeUm != null && resumed.run.positions.isNotEmpty()) {
                    resumable = resumed.run
                    resumeCount = resumed.run.positions.size
                }
                ResumeResult.Interrupted -> refused = true
                ResumeResult.AlreadyComplete -> Unit
            }
        } else if (session == null && !stored.calibrationSession.isNullOrBlank()) {
            refused = true
        }
        mutable.update { state ->
            state.copy(
                loaded = true,
                profileName = stored.name,
                metric = stored.metric,
                port = stored.port,
                nominalUm = stored.stylusDiameterUm,
                gaugeMetric = stored.metric,
                resumePositions = resumeCount,
                resumeRefused = refused,
            )
        }
        refreshReadiness()
    }

    // ---------------------------------------------------------------------------------------
    // Events

    /** The user types the ring gauge diameter. */
    fun setGauge(text: String) = mutable.update { state -> state.copy(gaugeText = text) }

    /** The user selects the unit of the ring gauge. */
    fun setGaugeMetric(metric: Boolean) = mutable.update { state -> state.copy(gaugeMetric = metric) }

    /** The user continues the unfinished run. */
    fun resume() {
        val stored = resumable ?: return
        resumable = null
        run = stored
        started = true
        mutable.update { state -> state.copy(resumePositions = null) }
        next(stored)
    }

    /** The user starts a new run in place of the unfinished run. */
    fun startAgain() {
        resumable = null
        mutable.update { state -> state.copy(resumePositions = null) }
    }

    /** "Continue" of the gauge step: the gauge must be usable for the nominal tip. */
    fun continueGauge() {
        val current = profile ?: return
        if (!ready()) return
        val state = mutable.value
        val value = state.gaugeText.trim().replace(',', '.').toDoubleOrNull() ?: 0.0
        val gaugeUm = TipCalibrationSetup.gaugeDiameterUm(value, state.gaugeMetric)
        val base = run ?: TipCalibrationRun(profileId = current.id, started = nowText())
        when (val step = base.withGauge(gaugeUm, current.stylusDiameterUm)) {
            is TipCalibrationStep.Failed -> mutable.update { it.copy(error = TipErrorUi(step.error)) }
            is TipCalibrationStep.Accepted -> {
                run = step.run
                started = true
                saveRun(step.run)
                next(step.run)
            }
        }
    }

    /** "Continue" of the touch step: the statistics of the position. */
    fun continueTouches() {
        val current = profile ?: return
        val touches = collection ?: return
        val base = run ?: return
        if (!ready()) return
        val limit = SpreadLimits.of(current).valueUm
        when (val captured = TouchStatistics.capture(touches, limit)) {
            ContinueResult.NotEnoughTouches -> Unit
            is ContinueResult.Scattered -> mutable.update { state ->
                state.copy(error = TipErrorUi(TipCalibrationError.PositionScattered, spreadUm = captured.spreadUm, limitUm = captured.limitUm))
            }
            is ContinueResult.Captured -> {
                val updated = base.withPosition(captured.position)
                run = updated
                saveRun(updated)
                next(updated)
            }
        }
    }

    /** "Continue" of the step "Back to the middle". */
    fun continueRecenter() {
        val base = run ?: return
        if (!ready()) return
        startTouches(base, ClockPosition.Twelve)
    }

    /** "Retry" of the error dialog. */
    fun retry() {
        val error = mutable.value.error ?: return
        val base = run
        mutable.update { state -> state.copy(error = null) }
        if (base == null) {
            mutable.update { state -> state.copy(step = TipStep.Gauge) }
            return
        }
        val updated = base.retry(error.error)
        run = updated
        mutable.update { state -> state.copy(captured = updated.positions.mapTo(LinkedHashSet()) { position -> position.clock }) }
        when (error.error) {
            TipCalibrationError.GaugeUnusable -> mutable.update { state -> state.copy(step = TipStep.Gauge) }
            TipCalibrationError.PositionScattered -> startTouches(updated, mutable.value.clock ?: updated.nextPosition ?: ClockPosition.Nine)
            TipCalibrationError.FitFailed, TipCalibrationError.ResultRefused -> next(updated)
        }
    }

    /** "Cancel" of a dialog, or the back action: the screen closes. The stored session stays. */
    fun cancel() = mutable.update { state -> state.copy(error = null, done = true) }

    /**
     * "Apply": the profile gets the diameter, the effective diameter, the residual (the
     * repeatability), the date, the session with `complete = true`, and its SHA-256 digest.
     */
    fun apply() {
        val current = profile ?: return
        val base = run ?: return
        val result = mutable.value.result ?: return
        scope.launch {
            val now = deps.wallClock.now()
            val applied = TipCalibrationSessions.apply(current, base, result, FileTimes.format(now.toLocalDateTime(deps.timeZone)), now)
            deps.probes.saveProbeProfile(applied)
            profile = applied
            mutable.update { state -> state.copy(done = true) }
        }
    }

    /** "Discard": the screen closes and writes no result. */
    fun discard() = mutable.update { state -> state.copy(done = true) }

    // ---------------------------------------------------------------------------------------
    // Private parts

    private fun next(updated: TipCalibrationRun) {
        mutable.update { state -> state.copy(captured = updated.positions.mapTo(LinkedHashSet()) { position -> position.clock }) }
        when {
            updated.gaugeUm == null -> mutable.update { state -> state.copy(step = TipStep.Gauge) }
            updated.hasAllPositions -> evaluate(updated)
            updated.needsRecenter && mutable.value.step != TipStep.Recenter -> mutable.update { state ->
                state.copy(step = TipStep.Recenter, marker = updated.recenterMarker, clock = null, touches = 0, touchRefused = false)
            }
            else -> startTouches(updated, updated.nextPosition ?: ClockPosition.Nine)
        }
    }

    private fun startTouches(updated: TipCalibrationRun, clock: ClockPosition) {
        collection = TouchCollection(clock)
        mutable.update { state ->
            state.copy(step = TipStep.Touches, clock = clock, prompt = updated.prompt, touches = 0, touchRefused = false)
        }
    }

    private fun evaluate(updated: TipCalibrationRun) {
        val nominal = profile?.stylusDiameterUm ?: 0.0
        when (val outcome = updated.evaluate(nominal)) {
            TipCalibrationOutcome.NotComplete -> startTouches(updated, updated.nextPosition ?: ClockPosition.Nine)
            TipCalibrationOutcome.FitFailed -> mutable.update { state -> state.copy(error = TipErrorUi(TipCalibrationError.FitFailed)) }
            is TipCalibrationOutcome.Refused -> mutable.update { state ->
                state.copy(error = TipErrorUi(TipCalibrationError.ResultRefused, check = outcome.check, result = outcome.result))
            }
            is TipCalibrationOutcome.Ready -> mutable.update { state ->
                state.copy(step = TipStep.Result, result = outcome.result, check = outcome.check, clock = null)
            }
        }
    }

    private suspend fun onProbeEvent(event: ProbeEvent) = mutex.withLock {
        val state = mutable.value
        val current = profile ?: return@withLock
        if (state.step != TipStep.Touches || state.error != null || state.readiness != null) return@withLock
        val port = current.port ?: return@withLock
        if (Channels.normalize(event.port) != Channels.normalize(port)) return@withLock
        val touches = collection ?: return@withLock
        val touch = Touch(
            xUm = event.axisPositions[AxisId.X] ?: Double.NaN,
            yUm = event.axisPositions[AxisId.Y] ?: Double.NaN,
            vxUmPerS = event.axisVelocities[AxisId.X] ?: Double.NaN,
            vyUmPerS = event.axisVelocities[AxisId.Y] ?: Double.NaN,
            timeMs = event.timeMs,
        )
        when (val added = touches.add(touch)) {
            TouchResult.NoPosition -> {
                sounds.refused()
                mutable.update { it.copy(touchRefused = true) }
            }
            is TouchResult.Added -> {
                collection = added.touches
                sounds.touch(added.beep)
                mutable.update { it.copy(touches = added.touches.count, touchRefused = false) }
            }
        }
    }

    /** Saves the run with `complete = false` after each step. */
    private fun saveRun(updated: TipCalibrationRun) {
        val current = profile ?: return
        val withSession = TipCalibrationSessions.withRunSession(current, updated)
        if (withSession == current) return
        profile = withSession
        scope.launch { deps.probes.saveProbeProfile(withSession) }
    }

    private fun readinessNow(): TipCalibrationReadiness = TipCalibrationSetup.readiness(
        profile = profile,
        connected = sessionState is SessionState.Connected,
        xHasPosition = inputs.axisValue(AxisId.X) != null,
        yHasPosition = inputs.axisValue(AxisId.Y) != null,
    )

    /** The check of 13.10.1 before each step. A failed check shows its dialog. */
    private fun ready(): Boolean {
        val readiness = readinessNow()
        if (readiness == TipCalibrationReadiness.Ready) return true
        mutable.update { state -> state.copy(readiness = readiness) }
        return false
    }

    /**
     * Applies a change of the session or of the profile to the readiness. Before the first step the
     * dialog follows the conditions. After the first step only a step shows the dialog ([ready]).
     * A dialog closes by itself when its condition is gone.
     */
    private fun refreshReadiness() {
        if (!mutable.value.loaded) return
        val readiness = readinessNow()
        mutable.update { current ->
            when {
                readiness == TipCalibrationReadiness.Ready -> current.copy(readiness = null)
                !started -> current.copy(readiness = readiness)
                else -> current
            }
        }
    }

    private fun nowText(): String = FileTimes.format(deps.wallClock.now().toLocalDateTime(deps.timeZone))
}
