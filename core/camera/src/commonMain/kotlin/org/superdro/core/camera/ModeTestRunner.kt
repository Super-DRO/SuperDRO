package org.superdro.core.camera

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.superdro.core.model.CameraModeResult
import org.superdro.core.model.CameraModeStatus
import org.superdro.core.util.Clock

/**
 * The UI state of the mode test of "Camera setup".
 *
 * @property progress The progress bar value from 0 to 100 ([ModeTestProgress]).
 * @property previewOn True while the preview of the mode runs.
 */
data class ModeTestUiState(
    val mode: UvcMode? = null,
    val state: ModeTestState = ModeTestState.Untested,
    val attempt: Int = 0,
    val progress: Int = 0,
    val result: ModeTestResult = ModeTestResult.Untested,
    val previewOn: Boolean = false,
) {
    /** True when "Save" saves a mode. */
    val canSave: Boolean get() = state.canSave
}

/**
 * Runs the mode test of "Camera setup" with coroutines. It does
 * the effects of [ModeTestMachine]: the preview starts and stops, the timer of 3500 ms, the
 * progress ticks of 100 ms, and the mode memory writes.
 *
 * The time to the first frame in the result text is the time of the first start of the test. The
 * measured rate is the last statistics rate of the second start.
 *
 * @param preview Starts the preview of a mode. A cancel of the collector stops it.
 * @param store Writes the mode memory row of a mode ([CameraModeMemory.record]).
 */
class ModeTestRunner(
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val preview: (UvcMode) -> Flow<StreamEvent>,
    private val store: suspend (mode: UvcMode, status: CameraModeStatus, measuredFps: Double?) -> Unit,
) {
    private val machine = ModeTestMachine()
    private val progress = ModeTestProgress()
    private val _ui = MutableStateFlow(ModeTestUiState())

    /** The state for the UI. */
    val ui: StateFlow<ModeTestUiState> = _ui.asStateFlow()

    private var mode: UvcMode? = null
    private var storedStatus: CameraModeStatus? = null
    private var previewJob: Job? = null
    private var timerJob: Job? = null
    private var tickerJob: Job? = null
    private var attemptStartMs = 0L
    private var framesThisAttempt = 0
    private var lastFps: Double? = null
    private var firstFrameDelayMs: Long? = null
    private var videoWidth = 0
    private var videoHeight = 0

    /** Selects [newMode]. [memory] is its mode memory row, or null. A running test stops. */
    fun select(newMode: UvcMode, memory: CameraModeResult?) {
        apply(machine.select(memory?.status))
        mode = newMode
        storedStatus = memory?.status
        progress.reset()
        publish(ModeTestResult.fromMemory(newMode, memory?.status, memory?.measuredFps))
    }

    /** Starts the test of the selected mode. */
    fun test() {
        if (mode == null || machine.state.isRunning) return
        progress.reset()
        firstFrameDelayMs = null
        lastFps = null
        apply(machine.test())
        startTicker()
        publish(_ui.value.result)
    }

    /** Stops a running test ("Test stopped"). */
    fun cancel() {
        if (!machine.state.isRunning) return
        apply(machine.cancel(storedStatus))
        progress.reset()
        publish(ModeTestResult.Stopped)
    }

    /** Stops the preview and the timers. Call it when the setup closes. */
    fun close() {
        timerJob?.cancel()
        tickerJob?.cancel()
        stopPreview()
        _ui.update { state -> state.copy(previewOn = false) }
    }

    private fun apply(effects: List<ModeTestEffect>) {
        effects.forEach { effect ->
            when (effect) {
                ModeTestEffect.StartPreview -> startPreview()
                ModeTestEffect.RestartPreview -> {
                    stopPreview()
                    startPreview()
                }
                ModeTestEffect.StopPreview -> stopPreview()
                is ModeTestEffect.StartTimer -> startTimer(effect.delayMs)
                ModeTestEffect.CancelTimer -> {
                    timerJob?.cancel()
                    tickerJob?.cancel()
                }
                is ModeTestEffect.Store -> {
                    val target = mode ?: return@forEach
                    storedStatus = effect.status
                    scope.launch { store(target, effect.status, effect.measuredFps) }
                }
            }
        }
    }

    private fun startPreview() {
        val target = mode ?: return
        framesThisAttempt = 0
        attemptStartMs = clock.nowMs()
        val startedAt = attemptStartMs
        previewJob = scope.launch {
            preview(target).collect { event -> onEvent(event, startedAt) }
        }
        _ui.update { state -> state.copy(previewOn = true) }
    }

    private fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
        _ui.update { state -> state.copy(previewOn = false) }
    }

    private fun onEvent(event: StreamEvent, startedAt: Long) {
        when (event) {
            is StreamEvent.FirstFrame -> {
                framesThisAttempt += 1
                videoWidth = event.width
                videoHeight = event.height
                if (firstFrameDelayMs == null) firstFrameDelayMs = clock.nowMs() - startedAt
            }
            is StreamEvent.Stats -> {
                framesThisAttempt = maxOf(framesThisAttempt, event.frames)
                if (event.width > 0) videoWidth = event.width
                if (event.height > 0) videoHeight = event.height
                if (machine.state == ModeTestState.Verifying && event.fps > 0.0) lastFps = event.fps
            }
            is StreamEvent.Ended -> {
                val error = event.error ?: return
                apply(machine.onError(error.kind))
                if (machine.state == ModeTestState.Failed) {
                    publish(if (error.kind == CameraErrorKind.ModeFailed) ModeTestResult.Bad else ModeTestResult.Failed(error))
                }
            }
            is StreamEvent.Started, is StreamEvent.Diagnostic -> Unit
        }
    }

    private fun startTimer(delayMs: Long) {
        timerJob?.cancel()
        timerJob = scope.launch {
            delay(delayMs)
            val effects = machine.onTimer(framesThisAttempt, lastFps)
            apply(effects)
            when (machine.state) {
                ModeTestState.Ok -> {
                    tickerJob?.cancel()
                    publish(successResult())
                }
                ModeTestState.Failed -> {
                    tickerJob?.cancel()
                    publish(ModeTestResult.Bad)
                }
                else -> publish(_ui.value.result)
            }
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (true) {
                publish(_ui.value.result)
                delay(ModeTestProgress.TICK_MS)
            }
        }
    }

    private fun successResult(): ModeTestResult {
        val target = mode ?: return ModeTestResult.Untested
        val width = if (videoWidth > 0) videoWidth else target.width
        val height = if (videoHeight > 0) videoHeight else target.height
        val fps = lastFps?.let { rate -> FrameRateText.format(rate, FrameRateText.MEASURED_DECIMALS) }
        val delayMs = firstFrameDelayMs
        return when {
            fps != null && delayMs != null -> ModeTestResult.WorkingStarted(width, height, fps, delayMs / 1000.0)
            fps != null -> ModeTestResult.Working(width, height, fps)
            else -> ModeTestResult.WorkingNoFps(width, height)
        }
    }

    private fun publish(result: ModeTestResult) {
        val elapsed = clock.nowMs() - attemptStartMs
        val value = progress.update(machine.state, machine.attempt, elapsed)
        _ui.update { state ->
            state.copy(
                mode = mode,
                state = machine.state,
                attempt = machine.attempt,
                progress = value,
                result = result,
            )
        }
    }
}
