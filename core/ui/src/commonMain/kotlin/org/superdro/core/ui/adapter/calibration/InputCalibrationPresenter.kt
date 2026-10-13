package org.superdro.core.ui.adapter.calibration

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.SessionState
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.AxisId
import org.superdro.core.model.Channels
import org.superdro.core.model.EncoderProfile
import org.superdro.core.model.MachineProfile
import org.superdro.core.readout.ReadoutMessage
import org.superdro.core.readout.calibration.BlockCheck
import org.superdro.core.readout.calibration.CalibrationEdge
import org.superdro.core.readout.calibration.CalibrationMath
import org.superdro.core.readout.calibration.CalibrationResult
import org.superdro.core.readout.calibration.CalibrationSampler
import org.superdro.core.readout.calibration.CalibrationStart
import org.superdro.core.readout.calibration.SampleCheck
import org.superdro.core.readout.input.ChannelValue
import org.superdro.core.resources.Res
import org.superdro.core.resources.calibration_block_required_detail
import org.superdro.core.resources.calibration_block_too_small_text
import org.superdro.core.resources.calibration_jitter_detail
import org.superdro.core.resources.calibration_link_lost
import org.superdro.core.resources.calibration_link_lost_detail
import org.superdro.core.resources.calibration_no_adapter
import org.superdro.core.resources.calibration_no_reading_detail
import org.superdro.core.resources.calibration_save_failed
import org.superdro.core.resources.calibration_too_close_detail
import org.superdro.core.resources.calibration_too_few_detail
import org.superdro.core.resources.readout_no_adapter
import org.superdro.core.resources.readout_waiting_for_adapter
import org.superdro.core.store.StoreResult
import org.superdro.core.ui.adapter.AdapterTexts
import org.superdro.core.ui.adapter.AdapterUiDeps
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.toUiText
import org.superdro.core.resources.integration_calibration_adapter_missing
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow

/** The two positions of a calibration: the two edges of the block, or 0° and 5 revolutions. */
enum class CalibrationPosition { First, Second }

/** One step of the calibration wizard. */
sealed interface CalibrationStep {

    /** The data loads. */
    data object Loading : CalibrationStep

    /** The first step of the linear procedure: the block size and its unit. */
    data object BlockSize : CalibrationStep

    /**
     * Find a position: the [edge] of the block (linear), or the 0° position and the position after
     * 5 clockwise revolutions (angular, [edge] is null).
     */
    data class Indicate(val position: CalibrationPosition, val edge: UiText?) : CalibrationStep

    /**
     * The app reads the input for [CalibrationMath.SAMPLE_TIME_MS]. [elapsedMs] grows each
     * [CalibrationMath.PROGRESS_INTERVAL_MS]. [count] is the number of readings.
     */
    data class Sampling(val position: CalibrationPosition, val elapsedMs: Long, val count: Int) : CalibrationStep {
        /** The progress from 0 to 1. */
        val progress: Float get() = (elapsedMs.toFloat() / CalibrationMath.SAMPLE_TIME_MS).coerceIn(0f, 1f)
    }

    /**
     * The result. "Apply" writes [result] into the encoder profile. "Discard" writes nothing.
     *
     * @property resolutionText The resolution with 3 decimals, for example "5080.000".
     * @property perCountText µm for each count with 2 decimals ("5.00"), or degrees for each count
     *   with 4 decimals ("0.0500").
     */
    data class Result(val result: CalibrationResult, val resolutionText: String, val perCountText: String) : CalibrationStep
}

/** The kind of a problem of the wizard. */
enum class CalibrationProblemKind {
    /** "Block Size Required": Retry only. */
    BlockRequired,

    /** "Block Too Small": Retry or Continue. */
    BlockTooSmall,

    /** "No Reading Received": Retry or Cancel. */
    NoReading,

    /** "Reading Sample Too Small": Retry or Cancel. */
    TooFew,

    /** "Excessive Jitter Detected": Retry or Cancel. */
    Jitter,

    /** "Readings Too Close": Retry (find the second position again) or Cancel. */
    TooClose,

    /** The session left `Connected` during a sample: Retry or Cancel. */
    LinkLost,
}

/**
 * A problem of the wizard: an error or a warning with its title and its text.
 *
 * @property warning True for a warning, false for an error.
 * @property canContinue True when the dialog has "Continue" (only "Block Too Small").
 * @property canCancel True when the dialog has "Cancel": it closes the wizard.
 */
@Immutable
data class CalibrationProblem(
    val kind: CalibrationProblemKind,
    val title: UiText,
    val text: UiText,
    val warning: Boolean,
    val position: CalibrationPosition? = null,
) {
    val canContinue: Boolean get() = kind == CalibrationProblemKind.BlockTooSmall
    val canCancel: Boolean get() = kind != CalibrationProblemKind.BlockRequired && kind != CalibrationProblemKind.BlockTooSmall
}

/**
 * The state of the calibration wizard of one adapter input.
 *
 * @property inputName The name of the input, for example "Input X".
 * @property axis The axis that uses the input, or null.
 * @property axisLabel The label of [axis] in the machine profile, for example "X" or "α", or null.
 * @property angular True for the angular procedure (5 revolutions).
 * @property blocked The text that stops the wizard, or null.
 * @property unavailable The text of a session that is not `Connected`, or null. The wizard
 *   does not sample while it is not null.
 * @property liveCount The raw count of the input, or null before the first value.
 * @property done True after "Apply", "Discard", or "Cancel": the screen closes.
 * @property applied True when "Apply" wrote the encoder profile.
 */
@Immutable
data class CalibrationUiState(
    val input: Char,
    val inputName: UiText,
    val axis: AxisId? = null,
    val axisLabel: String? = null,
    val angular: Boolean = false,
    val blocked: UiText? = null,
    val unavailable: UiText? = null,
    val step: CalibrationStep = CalibrationStep.Loading,
    val blockText: String = "",
    val metric: Boolean = true,
    val liveCount: Long? = null,
    val problem: CalibrationProblem? = null,
    val saving: Boolean = false,
    val saveError: UiText? = null,
    val done: Boolean = false,
    val applied: Boolean = false,
) {
    /** True when "Continue" of the step can act. */
    val canContinue: Boolean
        get() = blocked == null && problem == null && when (step) {
            CalibrationStep.BlockSize -> true
            is CalibrationStep.Indicate -> unavailable == null
            else -> false
        }
}

/**
 * The presenter of the calibration wizard of the adapter input [input].
 *
 * Data flow:
 * - The machine profile of the tab gives the machine type, the axis of the input, and
 *   the metric default of the block unit. The adapter profile of the tab gives the encoder profile
 *   of the input. The kind "angular" selects the angular procedure.
 * - The session gives the raw count of the input in its `InputSnapshot`: one value for each new
 *   sample time. [CalibrationSampler] of `core:readout` adds the error map correction and
 *   the hysteresis of the input when the count goes up.
 * - A sample takes [CalibrationMath.SAMPLE_TIME_MS] with a progress step each
 *   [CalibrationMath.PROGRESS_INTERVAL_MS] on the presenter dispatcher (virtual time in the
 *   tests). Then [CalibrationMath.checkSample] gives the checks of the sample.
 * - "Apply" writes the resolution with 3 decimals, the direction, the status CALIBRATED, the date,
 *   the machine type, and the axis ([CalibrationMath.apply]). "Discard" writes nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InputCalibrationPresenter(
    val deviceId: DeviceId,
    input: Char,
    private val deps: AdapterUiDeps,
) : ScopedPresenter(deps.context) {

    private val letter: Char = Channels.normalize(input)

    private val machine: StateFlow<MachineProfile?> = deps.machine(deviceId).stateIn(scope, SharingStarted.Eagerly, null)
    // Null until the store gives the adapter profile of the tab. A loaded value with no profile
    // tells that the machine profile selects no adapter profile, or an ID that the store does not have.
    private val adapter: StateFlow<LoadedAdapter?> = deps.tabAdapter(deviceId).map(::LoadedAdapter).stateIn(scope, SharingStarted.Eagerly, null)
    private val sessionState: StateFlow<SessionState?> = deps.sessionState(deviceId).stateIn(scope, SharingStarted.Eagerly, null)

    private val state = MutableStateFlow(CalibrationUiState(input = letter, inputName = AdapterTexts.encoderInputName(letter)))

    /** The UI state of the wizard. */
    val uiState: StateFlow<CalibrationUiState> = state.asStateFlow()

    private var sampler: CalibrationSampler? = null
    private var encoder: EncoderProfile? = null
    private var adapterId: String? = null
    private var blockUm: Double = 0.0
    private var firstMean: Double? = null
    private var sampling: Boolean = false
    private var lastSampleTime: Long? = null
    private var sampleJob: Job? = null

    init {
        scope.launch {
            combine(machine, adapter) { profile, adapterProfile -> profile to adapterProfile }.collect { (profile, adapterProfile) ->
                onSetup(profile, adapterProfile)
            }
        }
        scope.launch {
            sessionState.collect { current ->
                val text = unavailableText(current)
                state.update { old -> old.copy(unavailable = text) }
                if (text != null && sampling) onLinkLost()
            }
        }
        // Each sample of the input, not the conflated inputs: a sample must not be lost.
        // The current value of the inputs comes first, for the live count.
        scope.launch {
            deps.session(deviceId)
                .flatMapLatest { session ->
                    if (session == null) {
                        emptyFlow()
                    } else {
                        flow {
                            session.inputs.value.channels[letter]?.let { value -> emit(value) }
                            emitAll(session.samples.filter { value -> Channels.normalize(value.input) == letter })
                        }
                    }
                }
                .collect { value -> onValue(value) }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Events

    /** The text of the block size changes. */
    fun onBlockTextChange(text: String) = state.update { old -> old.copy(blockText = text) }

    /** The unit of the block size changes: mm when [metric] is true, else inch. */
    fun onBlockUnitChange(metric: Boolean) = state.update { old -> old.copy(metric = metric) }

    /**
     * "Continue": the block size step checks the size; an indicate step starts the sample of its
     * position.
     */
    fun onContinue() {
        val current = state.value
        if (!current.canContinue) return
        when (val step = current.step) {
            CalibrationStep.BlockSize -> checkBlock(current)
            is CalibrationStep.Indicate -> startSample(step.position)
            else -> Unit
        }
    }

    /**
     * "Retry" of a problem: the block size again, the same sample again, or the second position
     * again ("Readings Too Close").
     */
    fun onProblemRetry() {
        val problem = state.value.problem ?: return
        state.update { old -> old.copy(problem = null) }
        when (problem.kind) {
            CalibrationProblemKind.BlockRequired, CalibrationProblemKind.BlockTooSmall -> setStep(CalibrationStep.BlockSize)
            CalibrationProblemKind.TooClose -> setStep(indicate(CalibrationPosition.Second))
            CalibrationProblemKind.NoReading, CalibrationProblemKind.TooFew, CalibrationProblemKind.Jitter, CalibrationProblemKind.LinkLost -> {
                val position = problem.position ?: CalibrationPosition.First
                if (state.value.unavailable == null) startSample(position) else setStep(indicate(position))
            }
        }
    }

    /** "Continue" of the warning "Block Too Small": the wizard uses the small block. */
    fun onProblemContinue() {
        val problem = state.value.problem ?: return
        if (!problem.canContinue) return
        state.update { old -> old.copy(problem = null) }
        setStep(indicate(CalibrationPosition.First))
    }

    /** The dialog of a problem closes with no button: the step of the problem shows again, with no new sample. */
    fun onProblemDismiss() {
        val problem = state.value.problem ?: return
        state.update { old -> old.copy(problem = null) }
        when (problem.kind) {
            CalibrationProblemKind.BlockRequired, CalibrationProblemKind.BlockTooSmall -> setStep(CalibrationStep.BlockSize)
            else -> setStep(indicate(problem.position ?: CalibrationPosition.First))
        }
    }

    /** "Cancel": the sample stops, the wizard writes nothing, and the screen closes. */
    fun onCancel() {
        stopSample()
        state.update { old -> old.copy(problem = null, done = true) }
    }

    /** "Discard" of the result: the wizard writes nothing, and the screen closes. */
    fun onDiscard() = onCancel()

    /** "Apply" of the result: writes the encoder profile, then the screen closes. */
    fun onApply() {
        val step = state.value.step as? CalibrationStep.Result ?: return
        val id = adapterId ?: return
        if (state.value.saving) return
        val profile = machine.value ?: return
        val base = encoder ?: EncoderProfile.defaultFor(letter)
        val updated = CalibrationMath.apply(base, step.result, profile.machineType, state.value.axis, deps.wallClock.now())
        state.update { old -> old.copy(saving = true, saveError = null) }
        scope.launch {
            when (deps.adapters.saveEncoderProfile(id, updated)) {
                is StoreResult.Ok -> state.update { old -> old.copy(saving = false, applied = true, done = true) }
                is StoreResult.Refused -> state.update { old -> old.copy(saving = false, saveError = UiText.of(Res.string.calibration_save_failed)) }
            }
        }
    }

    override fun close() {
        stopSample()
        super.close()
    }

    // ---------------------------------------------------------------------------------------
    // Setup

    /** The adapter profile of the tab after the store gave it: null for no profile. */
    private data class LoadedAdapter(val profile: AdapterProfile?)

    private fun onSetup(profile: MachineProfile?, loaded: LoadedAdapter?) {
        if (profile == null || loaded == null) return
        if (state.value.step != CalibrationStep.Loading && state.value.blocked == null) return
        val adapterProfile = loaded.profile
        if (adapterProfile == null) {
            // No adapter profile, or an ID that the store does not have: the wizard cannot load.
            // The blocked state shows the text and "Close", so the user has a way back.
            val text = if (profile.adapterProfileId == null) Res.string.calibration_no_adapter else Res.string.integration_calibration_adapter_missing
            state.update { old -> old.copy(blocked = UiText.of(text), step = CalibrationStep.Loading) }
            return
        }
        val profileOfInput = adapterProfile.encoders[letter]
        val refusal = InputCalibrationStart.inputRefusal(letter, profileOfInput)
        val axis = CalibrationStart.axisForInput(profile, letter)
        val base = profileOfInput ?: if (letter == Channels.T) null else EncoderProfile.defaultFor(letter)
        val angular = base != null && CalibrationStart.angular(base)
        encoder = base
        adapterId = adapterProfile.id
        if (refusal != null || base == null) {
            state.update { old -> old.copy(blocked = refusal, axis = axis, axisLabel = axisLabel(profile, axis), angular = angular) }
            return
        }
        sampler = CalibrationSampler(base)
        state.update { old ->
            old.copy(
                blocked = null,
                axis = axis,
                axisLabel = axisLabel(profile, axis),
                angular = angular,
                metric = profile.metricDefault,
                step = if (angular) indicate(CalibrationPosition.First, axis, profile, angular = true) else CalibrationStep.BlockSize,
            )
        }
    }

    private fun indicate(position: CalibrationPosition): CalibrationStep.Indicate {
        val current = state.value
        return indicate(position, current.axis, machine.value, current.angular)
    }

    private fun indicate(position: CalibrationPosition, axis: AxisId?, profile: MachineProfile?, angular: Boolean): CalibrationStep.Indicate {
        if (angular || profile == null) return CalibrationStep.Indicate(position, null)
        val (first, second) = CalibrationStart.edges(profile.machineType, axis)
        val edge: CalibrationEdge = if (position == CalibrationPosition.First) first else second
        return CalibrationStep.Indicate(position, ReadoutMessage(edge.text).toUiText())
    }

    private fun setStep(step: CalibrationStep) = state.update { old -> old.copy(step = step) }

    // ---------------------------------------------------------------------------------------
    // Block size

    private fun checkBlock(current: CalibrationUiState) {
        val value = current.blockText.trim().replace(',', '.').toDoubleOrNull() ?: 0.0
        when (val check = CalibrationMath.checkBlock(value, current.metric)) {
            BlockCheck.Required -> problem(
                CalibrationProblemKind.BlockRequired,
                BlockCheck.Required.message.toUiText(),
                UiText.of(Res.string.calibration_block_required_detail),
                warning = false,
            )
            is BlockCheck.TooSmall -> {
                blockUm = check.blockUm
                problem(
                    CalibrationProblemKind.BlockTooSmall,
                    check.message.toUiText(),
                    UiText.of(Res.string.calibration_block_too_small_text, check.detail.toUiText()),
                    warning = true,
                )
            }
            is BlockCheck.Ok -> {
                blockUm = check.blockUm
                setStep(indicate(CalibrationPosition.First))
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Samples

    private fun startSample(position: CalibrationPosition) {
        val sampler = sampler ?: return
        if (state.value.unavailable != null) return
        stopSample()
        sampler.startSample()
        sampling = true
        setStep(CalibrationStep.Sampling(position, 0L, 0))
        sampleJob = scope.launch {
            var elapsed = 0L
            while (elapsed < CalibrationMath.SAMPLE_TIME_MS) {
                delay(CalibrationMath.PROGRESS_INTERVAL_MS)
                elapsed += CalibrationMath.PROGRESS_INTERVAL_MS
                val count = sampler.sampleValues.size
                state.update { old ->
                    val step = old.step as? CalibrationStep.Sampling ?: return@update old
                    old.copy(step = step.copy(elapsedMs = elapsed, count = count))
                }
            }
            sampling = false
            onSampleEnd(position, sampler.check())
        }
    }

    private fun stopSample() {
        sampling = false
        sampleJob?.cancel()
        sampleJob = null
    }

    private fun onValue(value: ChannelValue) {
        state.update { old -> old.copy(liveCount = value.rawCount) }
        if (value.timeMs == lastSampleTime) return
        lastSampleTime = value.timeMs
        val sampler = sampler ?: return
        if (sampling) {
            sampler.sample(value.rawCount)
            val count = sampler.sampleValues.size
            state.update { old ->
                val step = old.step as? CalibrationStep.Sampling ?: return@update old
                old.copy(step = step.copy(count = count))
            }
        } else {
            sampler.observe(value.rawCount)
        }
    }

    private fun onLinkLost() {
        val step = state.value.step as? CalibrationStep.Sampling ?: return
        stopSample()
        problem(
            CalibrationProblemKind.LinkLost,
            UiText.of(Res.string.calibration_link_lost),
            UiText.of(Res.string.calibration_link_lost_detail),
            warning = false,
            position = step.position,
        )
    }

    private fun onSampleEnd(position: CalibrationPosition, check: SampleCheck) {
        val inputName = state.value.inputName
        when (check) {
            SampleCheck.NoReading -> problem(
                CalibrationProblemKind.NoReading,
                SampleCheck.NoReading.message.toUiText(),
                UiText.of(Res.string.calibration_no_reading_detail, inputName),
                warning = false,
                position = position,
            )
            is SampleCheck.TooFew -> problem(
                CalibrationProblemKind.TooFew,
                check.message.toUiText(),
                UiText.of(Res.string.calibration_too_few_detail, check.count, inputName, CalibrationMath.MIN_SAMPLES),
                warning = false,
                position = position,
            )
            is SampleCheck.Jitter -> problem(
                CalibrationProblemKind.Jitter,
                check.message.toUiText(),
                UiText.of(Res.string.calibration_jitter_detail, countText(check.spread)),
                warning = true,
                position = position,
            )
            is SampleCheck.Ok -> onMean(position, check.mean)
        }
    }

    private fun onMean(position: CalibrationPosition, mean: Double) {
        if (position == CalibrationPosition.First) {
            firstMean = mean
            setStep(indicate(CalibrationPosition.Second))
            return
        }
        val first = firstMean ?: return setStep(indicate(CalibrationPosition.First))
        if (CalibrationMath.tooClose(first, mean)) {
            problem(
                CalibrationProblemKind.TooClose,
                CalibrationMath.tooCloseMessage.toUiText(),
                UiText.of(Res.string.calibration_too_close_detail, CalibrationMath.MIN_DELTA_COUNTS.toInt()),
                warning = true,
                position = CalibrationPosition.Second,
            )
            return
        }
        val result = if (state.value.angular) CalibrationMath.angular(first, mean) else CalibrationMath.linear(first, mean, blockUm)
        val perCount = if (state.value.angular) result.degreesPerCountText else result.umPerCountText
        setStep(CalibrationStep.Result(result, result.resolutionText, perCount.orEmpty()))
    }

    private fun problem(kind: CalibrationProblemKind, title: UiText, text: UiText, warning: Boolean, position: CalibrationPosition? = null) {
        state.update { old -> old.copy(problem = CalibrationProblem(kind, title, text, warning, position)) }
    }

    companion object {
        /** The label of [axis] in [profile]: the label of the axis row, or null for no axis. */
        fun axisLabel(profile: MachineProfile, axis: AxisId?): String? = when (axis) {
            AxisId.X -> profile.x.label
            AxisId.Y -> profile.y.label
            AxisId.Z -> profile.z.label
            AxisId.Angular -> profile.angular.label
            AxisId.Caliper, AxisId.Tachometer -> axis.name
            null -> null
        }

        /** The text of a session that is not `Connected`, or null for `Connected`. */
        fun unavailableText(state: SessionState?): UiText? = when (state) {
            is SessionState.Connected -> null
            is SessionState.Connecting, SessionState.AwaitingData, SessionState.NoData -> UiText.of(Res.string.readout_waiting_for_adapter)
            else -> UiText.of(Res.string.readout_no_adapter)
        }

        /** A count spread as text: a whole number with no decimals, else with up to 2 decimals. */
        fun countText(value: Double): String {
            val whole = value.toLong()
            return if (value == whole.toDouble()) whole.toString() else ((value * 100.0).toLong() / 100.0).toString()
        }
    }
}
