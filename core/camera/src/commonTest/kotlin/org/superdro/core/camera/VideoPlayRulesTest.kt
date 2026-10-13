package org.superdro.core.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VideoPlayRulesTest {

    @Test
    fun thePlayButtonStartsOnlyAConfiguredCamera() {
        val idle = VideoPlayState()
        assertEquals(idle to emptyList(), VideoPlayRules.onPlayTapped(idle, configured = false))
        val (playing, effects) = VideoPlayRules.onPlayTapped(idle, configured = true)
        assertEquals(VideoPlayState(wanted = true), playing)
        assertEquals(listOf(VideoPlayEffect.Start), effects)
        assertTrue(playing.shouldRun)
        val active = VideoPlayRules.onFirstFrame(playing)
        assertEquals(VideoPlayState() to listOf(VideoPlayEffect.Stop), VideoPlayRules.onPlayTapped(active, configured = true))
    }

    @Test
    fun aPauseStopsAndAResumeStartsAgain() {
        val playing = VideoPlayState(wanted = true, active = true)
        val (paused, stop) = VideoPlayRules.onPause(playing)
        assertEquals(VideoPlayState(wanted = true, paused = true), paused)
        assertEquals(listOf(VideoPlayEffect.Stop), stop)
        assertEquals(VideoPlayState(wanted = true) to listOf(VideoPlayEffect.Start), VideoPlayRules.onResume(paused))
        assertEquals(VideoPlayState() to emptyList(), VideoPlayRules.onResume(VideoPlayState(paused = true)))
    }

    @Test
    fun theSetupStopsTheVideoSoThatOnlyOneViewStreams() {
        val playing = VideoPlayState(wanted = true, active = true)
        val (open, stop) = VideoPlayRules.onSetupOpened(playing)
        assertEquals(listOf(VideoPlayEffect.Stop), stop)
        val (closed, start) = VideoPlayRules.onSetupClosed(open)
        assertEquals(VideoPlayState(wanted = true), closed)
        assertEquals(listOf(VideoPlayEffect.Start), start)
        assertEquals(emptyList(), VideoPlayRules.onSetupClosed(VideoPlayState(setupOpen = true)).second)
    }

    @Test
    fun aDetachAndAStreamErrorStopTheRequest() {
        val playing = VideoPlayState(wanted = true, active = true)
        val (detached, effects) = VideoPlayRules.onDetached(playing)
        assertEquals(VideoPlayState(), detached)
        assertEquals(
            listOf(VideoPlayEffect.Stop, VideoPlayEffect.ShowError(CameraError(CameraErrorKind.Disconnected, "detached"))),
            effects,
        )
        val error = CameraError(CameraErrorKind.NoFrames, "no frames for 3000 ms")
        val (stopped, errorEffects) = VideoPlayRules.onStreamError(playing, error)
        assertEquals(VideoPlayState(), stopped)
        assertEquals(listOf(VideoPlayEffect.Stop, VideoPlayEffect.ShowError(error)), errorEffects)
        assertEquals(VideoPlayState() to emptyList(), VideoPlayRules.onDetached(VideoPlayState()))
        assertEquals(VideoPlayState(), VideoPlayRules.onNoStart(VideoPlayState(wanted = true)))
    }
}
