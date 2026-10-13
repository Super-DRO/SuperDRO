package org.superdro.core.camera

import kotlin.math.floor
import org.superdro.core.model.CameraModeStatus

/** The states of a mode test in "Camera setup". */
enum class ModeTestState {
    /** The mode memory has no result for the mode. */
    Untested,

    /** The mode memory has the status GOOD. */
    KnownGood,

    /** The mode memory has the status BAD. */
    KnownBad,

    /** The first start of the test runs. */
    Testing,

    /** The second start runs. It checks that the camera opens again. */
    Verifying,

    /** The test passed. The mode memory has GOOD with the measured rate. */
    Ok,

    /** The test failed. */
    Failed;

    /** True while a test runs. */
    val isRunning: Boolean get() = this == Testing || this == Verifying

    /** True when "Save" saves a mode. */
    val canSave: Boolean get() = this == Ok || this == KnownGood

    companion object {
        /** The state of a mode from the mode memory. Null (no row) gives [Untested]. */
        fun fromMemory(status: CameraModeStatus?): ModeTestState = when (status) {
            CameraModeStatus.Good -> KnownGood
            CameraModeStatus.Bad -> KnownBad
            null -> Untested
        }
    }
}

/** An action that the mode test asks for. */
sealed interface ModeTestEffect {
    /** Start the preview of the mode, and count its frames from 0. */
    data object StartPreview : ModeTestEffect

    /** Stop the preview, then start it again. Count the frames from 0. */
    data object RestartPreview : ModeTestEffect

    /** Stop the preview. */
    data object StopPreview : ModeTestEffect

    /** Start the timer of one attempt. */
    data class StartTimer(val delayMs: Long) : ModeTestEffect

    /** Stop the timer. */
    data object CancelTimer : ModeTestEffect

    /** Write the mode memory row of the tested mode. */
    data class Store(val status: CameraModeStatus, val measuredFps: Double?) : ModeTestEffect
}

/**
 * The state machine of the mode test. The test starts and stops
 * the mode two times. Each attempt has [CameraIdentity.MODE_TEST_TIMEOUT_MS]. The machine has no
 * timer and no I/O: [ModeTestRunner] does the effects.
 *
 * | Event | Result |
 * |---|---|
 * | [test] | Attempt 1, [ModeTestState.Testing], start the preview, timer 3500 ms |
 * | Timer, frames, Testing | [ModeTestState.Verifying], attempt 1, restart, timer 3500 ms |
 * | Timer, frames, Verifying | Store GOOD with the rate, [ModeTestState.Ok] |
 * | Timer, no frames, attempt 2 or more | Stop, store BAD, [ModeTestState.Failed] |
 * | Timer, no frames, attempt 1 | Attempt + 1, restart, timer 3500 ms |
 * | Error [CameraErrorKind.ModeFailed] | Stop, store BAD, [ModeTestState.Failed] |
 * | Other error, not [CameraErrorKind.NoFrames] | Stop, [ModeTestState.Failed], no store |
 * | [cancel] | Stop, the state of the mode memory, the text "stopped" |
 */
class ModeTestMachine(initial: ModeTestState = ModeTestState.Untested) {

    var state: ModeTestState = initial
        private set

    /** The attempt of the current start, 1 or 2. */
    var attempt: Int = 0
        private set

    /** Starts a test. A test that runs already does not start again. */
    fun test(): List<ModeTestEffect> {
        if (state.isRunning) return emptyList()
        attempt = 1
        state = ModeTestState.Testing
        return listOf(ModeTestEffect.StartPreview, ModeTestEffect.StartTimer(CameraIdentity.MODE_TEST_TIMEOUT_MS))
    }

    /**
     * The timer of an attempt ended. [frames] is the number of frames of this attempt. [measuredFps]
     * is the last measured rate of this attempt, or null.
     */
    fun onTimer(frames: Int, measuredFps: Double?): List<ModeTestEffect> {
        if (!state.isRunning) return emptyList()
        return if (frames > 0) {
            if (state == ModeTestState.Testing) {
                state = ModeTestState.Verifying
                attempt = 1
                listOf(ModeTestEffect.RestartPreview, ModeTestEffect.StartTimer(CameraIdentity.MODE_TEST_TIMEOUT_MS))
            } else {
                state = ModeTestState.Ok
                listOf(ModeTestEffect.Store(CameraModeStatus.Good, measuredFps))
            }
        } else if (attempt >= CameraIdentity.MODE_TEST_ATTEMPTS) {
            state = ModeTestState.Failed
            listOf(ModeTestEffect.StopPreview, ModeTestEffect.Store(CameraModeStatus.Bad, null))
        } else {
            attempt += 1
            listOf(ModeTestEffect.RestartPreview, ModeTestEffect.StartTimer(CameraIdentity.MODE_TEST_TIMEOUT_MS))
        }
    }

    /** An error of the preview during a test. */
    fun onError(kind: CameraErrorKind): List<ModeTestEffect> {
        if (!state.isRunning || kind == CameraErrorKind.NoFrames) return emptyList()
        state = ModeTestState.Failed
        return if (kind == CameraErrorKind.ModeFailed) {
            listOf(ModeTestEffect.CancelTimer, ModeTestEffect.StopPreview, ModeTestEffect.Store(CameraModeStatus.Bad, null))
        } else {
            listOf(ModeTestEffect.CancelTimer, ModeTestEffect.StopPreview)
        }
    }

    /** The user stops the test. [stored] is the status of the mode memory, or null. */
    fun cancel(stored: CameraModeStatus?): List<ModeTestEffect> {
        if (!state.isRunning) return emptyList()
        state = ModeTestState.fromMemory(stored)
        return listOf(ModeTestEffect.CancelTimer, ModeTestEffect.StopPreview)
    }

    /** The user selects a different mode. [stored] is its status in the mode memory, or null. */
    fun select(stored: CameraModeStatus?): List<ModeTestEffect> {
        val effects = if (state.isRunning) listOf(ModeTestEffect.CancelTimer, ModeTestEffect.StopPreview) else emptyList()
        state = ModeTestState.fromMemory(stored)
        attempt = 0
        return effects
    }
}

/**
 * The value of the progress bar of a mode test, from 0 to 100:
 * `round(((clamp(t, 0, 3500) / 3500) + (attempt − 1)) × 25) + (50 if Verifying)`. The value does
 * not go down. The UI updates it each [TICK_MS].
 */
class ModeTestProgress {
    /** The last value. */
    var value: Int = 0
        private set

    /** Calculates the value at [elapsedMs] after the start of the current attempt. */
    fun update(state: ModeTestState, attempt: Int, elapsedMs: Long): Int {
        val next = when (state) {
            ModeTestState.Testing, ModeTestState.Verifying -> formula(state, attempt, elapsedMs)
            ModeTestState.Ok, ModeTestState.Failed -> 100
            else -> 0
        }
        value = if (state.isRunning || state == ModeTestState.Ok || state == ModeTestState.Failed) maxOf(value, next) else next
        return value
    }

    /** Sets the value to 0, for a new test. */
    fun reset() {
        value = 0
    }

    companion object {
        /** The update interval of the progress bar. */
        const val TICK_MS: Long = 100L

        /** The formula of the progress value of the mode test. */
        fun formula(state: ModeTestState, attempt: Int, elapsedMs: Long): Int {
            val limit = CameraIdentity.MODE_TEST_TIMEOUT_MS
            val t = elapsedMs.coerceIn(0L, limit).toDouble()
            // Half up, as `Math.round` of the JVM.
            val base = floor(((t / limit) + (attempt - 1)) * 25.0 + 0.5).toInt()
            return base + if (state == ModeTestState.Verifying) 50 else 0
        }
    }
}

/**
 * The result text of "Camera setup". [messageKey] is the name in
 * `strings_camera.xml`. [englishText] gives the base text for the log and the tests.
 */
sealed interface ModeTestResult {
    val messageKey: String

    /** The test passed. [fps] is the measured rate text. [startSeconds] is the time to the first frame. */
    data class WorkingStarted(val width: Int, val height: Int, val fps: String, val startSeconds: Double) : ModeTestResult {
        override val messageKey: String get() = "camera_test_working_started"

        /**
         * The time to the first frame with one decimal, for example `1.2`. The string resource has
         * `%4$s`, because the Compose resources format only `%N$s` and `%N$d`.
         */
        fun startSecondsText(decimalSeparator: Char = '.'): String = oneDecimal(startSeconds).replace('.', decimalSeparator)
    }

    /** A GOOD mode with a known rate. */
    data class Working(val width: Int, val height: Int, val fps: String) : ModeTestResult {
        override val messageKey: String get() = "camera_test_working"
    }

    /** A GOOD mode with no known rate. */
    data class WorkingNoFps(val width: Int, val height: Int) : ModeTestResult {
        override val messageKey: String get() = "camera_test_working_no_fps"
    }

    /** The mode gave no video. */
    data object Bad : ModeTestResult {
        override val messageKey: String get() = "camera_test_bad"
    }

    /** The user stopped the test. */
    data object Stopped : ModeTestResult {
        override val messageKey: String get() = "camera_test_stopped"
    }

    /** The mode has no test result. */
    data object Untested : ModeTestResult {
        override val messageKey: String get() = "camera_test_untested"
    }

    /** The test failed with [error]. The UI shows the message of the error kind. */
    data class Failed(val error: CameraError) : ModeTestResult {
        override val messageKey: String get() = error.kind.messageKey
    }

    /** The English text, the same as the base strings of `strings_camera.xml`. */
    fun englishText(): String = when (this) {
        is WorkingStarted -> "Video OK: $width × $height at $fps fps. The first picture came after ${startSecondsText()} s."
        is Working -> "Video OK: $width × $height at $fps fps."
        is WorkingNoFps -> "Video OK: $width × $height."
        Bad -> "This mode gives no video."
        Stopped -> "You stopped the test."
        Untested -> "Not tested"
        is Failed -> error.kind.messageKey
    }

    companion object {
        /** Gives the text `%.1f` of [value] with a point. */
        fun oneDecimal(value: Double): String = FrameRateText.format(value, 1)?.let { text ->
            if ('.' in text) text else "$text.0"
        } ?: "0.0"

        /**
         * Gives the result of a mode from the mode memory, before a test. [fps] is the measured
         * rate of the mode memory, or null.
         */
        fun fromMemory(mode: UvcMode, status: CameraModeStatus?, fps: Double?): ModeTestResult = when (status) {
            CameraModeStatus.Good -> FrameRateText.format(fps ?: 0.0, FrameRateText.MEASURED_DECIMALS)
                ?.let { text -> Working(mode.width, mode.height, text) }
                ?: WorkingNoFps(mode.width, mode.height)
            CameraModeStatus.Bad -> Bad
            null -> Untested
        }
    }
}

/**
 * The mode that "Save" writes: in [ModeTestState.KnownGood] the
 * selected mode, in [ModeTestState.Ok] the tested mode, else null (no action).
 */
fun modeToSave(state: ModeTestState, selected: UvcMode?, tested: UvcMode?): UvcMode? = when (state) {
    ModeTestState.KnownGood -> selected
    ModeTestState.Ok -> tested
    else -> null
}
