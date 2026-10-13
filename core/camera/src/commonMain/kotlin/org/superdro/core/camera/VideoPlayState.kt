package org.superdro.core.camera

/**
 * The play state of the video backdrop and of the camera screen. [wanted] is the
 * play request of the user. The video runs when the request
 * is on, the view is not paused, and "Camera setup" is closed. SuperDRO has no Plus gate.
 *
 * @property active True after the first frame, until the stop.
 */
data class VideoPlayState(
    val wanted: Boolean = false,
    val paused: Boolean = false,
    val setupOpen: Boolean = false,
    val active: Boolean = false,
) {
    /** True when the video must run now. */
    val shouldRun: Boolean get() = wanted && !paused && !setupOpen
}

/** An action of the play state machine. */
sealed interface VideoPlayEffect {
    /** Start the video ([CameraModeMemory.cameraForPlay] and [CameraModeMemory.playChoice]). */
    data object Start : VideoPlayEffect

    /** Stop the video. */
    data object Stop : VideoPlayEffect

    /** Show the error dialog of [error] ([CameraErrorDialog]). */
    data class ShowError(val error: CameraError) : VideoPlayEffect
}

/**
 * The rules of the play button and of the stop conditions. Each function gives the new state and
 * the effects.
 */
object VideoPlayRules {

    /**
     * The play button: during the video it stops the request. Else a camera that is not
     * "configured" ([UvcModeRules.isConfigured]) gives no action. Else the request goes on.
     */
    fun onPlayTapped(state: VideoPlayState, configured: Boolean): Pair<VideoPlayState, List<VideoPlayEffect>> = when {
        state.wanted -> stop(state)
        !configured -> state to emptyList()
        else -> {
            val next = state.copy(wanted = true)
            next to if (next.shouldRun) listOf(VideoPlayEffect.Start) else emptyList()
        }
    }

    /** The view pauses (the app goes to the background): stop. The request stays. */
    fun onPause(state: VideoPlayState): Pair<VideoPlayState, List<VideoPlayEffect>> {
        val next = state.copy(paused = true, active = false)
        return next to if (state.shouldRun) listOf(VideoPlayEffect.Stop) else emptyList()
    }

    /** The view resumes: start again when the request is on. */
    fun onResume(state: VideoPlayState): Pair<VideoPlayState, List<VideoPlayEffect>> {
        val next = state.copy(paused = false)
        return next to if (!state.shouldRun && next.shouldRun) listOf(VideoPlayEffect.Start) else emptyList()
    }

    /** "Camera setup" opens: stop, so that only one view streams. */
    fun onSetupOpened(state: VideoPlayState): Pair<VideoPlayState, List<VideoPlayEffect>> {
        val next = state.copy(setupOpen = true, active = false)
        return next to if (state.shouldRun) listOf(VideoPlayEffect.Stop) else emptyList()
    }

    /** "Camera setup" closes: start again when the request is on. */
    fun onSetupClosed(state: VideoPlayState): Pair<VideoPlayState, List<VideoPlayEffect>> {
        val next = state.copy(setupOpen = false)
        return next to if (!state.shouldRun && next.shouldRun) listOf(VideoPlayEffect.Start) else emptyList()
    }

    /** The first frame came. */
    fun onFirstFrame(state: VideoPlayState): VideoPlayState = state.copy(active = true)

    /**
     * The camera of the video detached: stop with [CameraErrorKind.Disconnected] "detached". The
     * request goes off.
     */
    fun onDetached(state: VideoPlayState): Pair<VideoPlayState, List<VideoPlayEffect>> {
        if (!state.wanted) return state to emptyList()
        val error = CameraError(CameraErrorKind.Disconnected, "detached")
        return state.copy(wanted = false, active = false) to listOf(VideoPlayEffect.Stop, VideoPlayEffect.ShowError(error))
    }

    /** A stream error: the request goes off, and the error dialog shows. */
    fun onStreamError(state: VideoPlayState, error: CameraError): Pair<VideoPlayState, List<VideoPlayEffect>> {
        if (!state.wanted) return state to emptyList()
        return state.copy(wanted = false, active = false) to listOf(VideoPlayEffect.Stop, VideoPlayEffect.ShowError(error))
    }

    /**
     * The start found no camera or no mode ([PlayChoice]): the request goes off. The UI shows the
     * message of the choice.
     */
    fun onNoStart(state: VideoPlayState): VideoPlayState = state.copy(wanted = false, active = false)

    private fun stop(state: VideoPlayState): Pair<VideoPlayState, List<VideoPlayEffect>> {
        val next = state.copy(wanted = false, active = false)
        return next to if (state.shouldRun) listOf(VideoPlayEffect.Stop) else emptyList()
    }
}
