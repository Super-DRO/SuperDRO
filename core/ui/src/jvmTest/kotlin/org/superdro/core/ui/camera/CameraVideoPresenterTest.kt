package org.superdro.core.ui.camera

import org.superdro.core.camera.VideoTarget
import kotlin.test.assertNotNull
import kotlinx.coroutines.launch
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.camera.CameraError
import org.superdro.core.camera.CameraErrorKind
import org.superdro.core.camera.CrosshairColor
import org.superdro.core.camera.FakeStream
import org.superdro.core.camera.FakeUvcCameraBackend
import org.superdro.core.camera.OverlayLayers
import org.superdro.core.model.CameraCalibration
import org.superdro.core.model.CameraModeResult
import org.superdro.core.model.CameraModeStatus
import org.superdro.core.model.CameraSelection
import org.superdro.core.ui.tabs.english

/** The video of a tab: the camera screen and the backdrop. */
class CameraVideoPresenterTest {

    private val scope = TestScope()
    private val harness = CameraHarness(scope)
    private val target = FakeUvcCameraBackend.NoTarget
    private val themeCursor = 0xFF3F51B5L

    @AfterTest
    fun close() = harness.close()

    private suspend fun videoWithSavedMode(): CameraVideoPresenter {
        val id = harness.start()
        harness.cameraStore.saveSelection(CameraSelection(id, TestCameras.logitech.key, TestCameras.mjpeg720.key, harness.store.wallClock.now()))
        return harness.ui.video(id).also { scope.advanceUntilIdle() }
    }

    @Test
    fun theSavedModeStartsAndTheFirstFrameWritesTheModeMemory() = scope.runTest {
        val video = videoWithSavedMode()
        assertTrue(video.state.value.attached)
        assertTrue(video.state.value.configured)
        video.onPlayTapped()
        runCurrent()
        // No view: the video waits.
        assertTrue(video.state.value.starting)
        assertTrue(video.state.value.viewWanted)
        assertTrue(harness.backend.startAttempts.isEmpty())
        video.onTarget(target, 1280, 720)
        advanceTimeBy(1_000L)
        runCurrent()
        val state = video.state.value
        assertTrue(state.active)
        assertEquals(TestCameras.mjpeg720, state.mode)
        assertEquals(1280 to 720, state.videoWidth to state.videoHeight)
        assertEquals(30.30, state.fps!!, 0.01)
        assertTrue(state.viewLock.locked && state.viewLock.heldByCamera, "The video holds the view lock")
        assertTrue(state.transparentBackground)
        assertTrue(state.followMode)
        assertFalse(state.panAllowed)
        assertFalse(state.zoomToFitAllowed)
        val row = harness.cameraStore.loadModeResults(TestCameras.logitech.key).single()
        assertEquals(CameraModeStatus.Good to TestCameras.mjpeg720.key, row.status to row.modeKey)
        assertEquals(listOf(TestCameras.logitech.key to TestCameras.mjpeg720.key), harness.backend.startAttempts)

        video.onPlayTapped()
        advanceUntilIdle()
        val stopped = video.state.value
        assertFalse(stopped.active)
        assertFalse(stopped.play.wanted)
        assertNull(stopped.mode)
        assertFalse(stopped.viewLock.locked, "The lock state of the time before the video comes back")
        video.close()
    }

    @Test
    fun aCameraWithNoSavedModeAndNoGoodModeIsNotConfigured() = scope.runTest {
        val video = harness.ui.video(harness.start())
        advanceUntilIdle()
        assertTrue(video.state.value.attached)
        assertFalse(video.state.value.configured)
        video.onPlayTapped()
        advanceUntilIdle()
        assertFalse(video.state.value.play.wanted, "The play button does nothing for a camera that is not configured")
        video.close()
    }

    @Test
    fun aGoodModeThatTheCameraDoesNotHaveGivesNeedsSetup() = scope.runTest {
        val id = harness.start()
        harness.cameraStore.saveModeResult(
            CameraModeResult(TestCameras.logitech.key, "0/800/600/333333", CameraModeStatus.Good, 800, 600, "MJPEG", 30.0, harness.store.wallClock.now()),
        )
        val video = harness.ui.video(id)
        advanceUntilIdle()
        assertTrue(video.state.value.configured)
        video.onTarget(target, 0, 0)
        video.onPlayTapped()
        advanceUntilIdle()
        val dialog = video.state.value.dialog!!
        assertEquals("The saved video mode did not start. Open the camera setup and test a mode.", dialog.message.english())
        assertTrue(dialog.offersSetup)
        assertFalse(video.state.value.play.wanted)

        // With a selection of a different camera, the text is the wrong camera text.
        harness.cameraStore.saveSelection(CameraSelection(id, TestCameras.noName.key, TestCameras.yuy2Vga.key, harness.store.wallClock.now()))
        video.onDialogDismissed()
        video.onPlayTapped()
        advanceUntilIdle()
        assertEquals(
            "This machine uses a different camera. Connect that camera, or do the camera setup again.",
            video.state.value.dialog?.message?.english(),
        )
        video.close()
    }

    @Test
    fun aDetachStopsTheVideoWithTheDisconnectedText() = scope.runTest {
        val video = videoWithSavedMode()
        video.onTarget(target, 1280, 720)
        video.onPlayTapped()
        advanceTimeBy(1_000L)
        runCurrent()
        assertTrue(video.state.value.active)
        harness.backend.detach(TestCameras.logitech)
        advanceUntilIdle()
        val state = video.state.value
        assertFalse(state.active)
        assertFalse(state.play.wanted)
        assertFalse(state.attached)
        assertEquals("The camera stopped the video. Examine the cable and the connection.", state.dialog?.message?.english())
        assertFalse(state.dialog!!.offersSetup)
        assertFalse(state.viewLock.locked)
        video.close()
    }

    @Test
    fun aStreamWithNoFramesShowsTheSetupDialog() = scope.runTest {
        val video = videoWithSavedMode()
        harness.stream(FakeStream(firstFrameDelayMs = null))
        video.onTarget(target, 1280, 720)
        video.onPlayTapped()
        advanceUntilIdle()
        val dialog = video.state.value.dialog!!
        assertEquals("The saved video mode did not start. Open the camera setup and test a mode.", dialog.message.english())
        assertTrue(dialog.offersSetup)
        assertFalse(video.state.value.play.wanted)
        video.close()
    }

    @Test
    fun aRefusedAccessShowsTheTextOfItsKind() = scope.runTest {
        val video = videoWithSavedMode()
        harness.backend.accessResult = Result.failure(org.superdro.core.camera.CameraException(CameraError(CameraErrorKind.CameraPermissionMissing, openAppSettings = true)))
        video.onTarget(target, 1280, 720)
        video.onPlayTapped()
        advanceUntilIdle()
        val dialog = video.state.value.dialog!!
        assertEquals("The camera permission is off. Turn it on in the app settings of SuperDRO.", dialog.message.english())
        assertTrue(dialog.openSettings)
        video.close()
    }

    @Test
    fun theViewLockStateOfTheTimeBeforeTheVideoComesBack() = scope.runTest {
        val video = videoWithSavedMode()
        video.onViewLockToggled()
        runCurrent()
        assertTrue(video.state.value.viewLock.locked)
        video.onTarget(target, 1280, 720)
        video.onPlayTapped()
        advanceTimeBy(1_000L)
        runCurrent()
        // The user turns the lock off during the video. The stop gives the old state again.
        video.onViewLockToggled()
        runCurrent()
        assertFalse(video.state.value.viewLock.locked)
        video.onPlayTapped()
        advanceUntilIdle()
        assertTrue(video.state.value.viewLock.locked)
        video.close()
    }

    @Test
    fun theCrosshairSwitchTheHalfOpacityAndTheViewLockAreStoredInTheProfile() = scope.runTest {
        val video = videoWithSavedMode()
        video.setCrosshairsShown(false)
        video.setDimmed(true)
        video.onViewLockToggled()
        advanceUntilIdle()
        val stored = harness.store.machines.profile(harness.profile.id).first()!!.graphicalDisplay
        assertFalse(stored.crosshairsShown)
        assertTrue(stored.crosshairsDimmed)
        assertTrue(stored.viewLocked)
        // A change of the display pane of the graphical view (the stored values) shows in the video state.
        harness.store.machines.updateProfile(harness.profile.id) { profile ->
            profile.copy(graphicalDisplay = profile.graphicalDisplay.copy(crosshairsShown = true, crosshairsDimmed = false, viewLocked = false))
        }
        advanceUntilIdle()
        assertTrue(video.state.value.crosshairsShown)
        assertFalse(video.state.value.dimmed)
        assertFalse(video.state.value.viewLock.locked)
        video.close()
    }

    @Test
    fun theCameraRowOfTheDisplayPaneStartsAndStopsTheVideo() = scope.runTest {
        val video = videoWithSavedMode()
        video.onTarget(target, 1280, 720)
        video.setWanted(true)
        advanceTimeBy(1_000L)
        runCurrent()
        assertTrue(video.state.value.active)
        // A second request for the same state does nothing.
        video.setWanted(true)
        advanceTimeBy(100L)
        runCurrent()
        assertTrue(video.state.value.active)
        video.setWanted(false)
        advanceUntilIdle()
        assertFalse(video.state.value.play.wanted)
        assertFalse(video.state.value.active)
        video.close()
    }

    @Test
    fun theColorsAndKeepOverlaysAreSettingsOfTheProfile() = scope.runTest {
        val video = videoWithSavedMode()
        assertEquals(themeCursor, video.state.value.crosshairArgb(themeCursor), "With no video the crosshairs have the theme color")
        video.setCrosshairColor(CrosshairColor.Cherry)
        video.setKeepOverlays(true)
        advanceUntilIdle()
        assertEquals("cherry", harness.store.machines.profile(harness.profile.id).first()!!.display.cameraCrosshairColor)
        assertEquals(CrosshairColor.Cherry, video.state.value.settings.crosshairColor)
        video.onTarget(target, 1280, 720)
        video.onPlayTapped()
        advanceTimeBy(1_000L)
        runCurrent()
        val state = video.state.value
        assertEquals(0xFF8C0020L, state.crosshairArgb(themeCursor))
        video.setDimmed(true)
        runCurrent()
        assertEquals(0x808C0020L, video.state.value.crosshairArgb(themeCursor), "Half opacity: the alpha times 0.5")
        // Keep overlays: the overlays stay, the reference image hides.
        assertEquals(OverlayLayers(referenceImage = false), video.state.value.visibleLayers())
        video.setKeepOverlays(false)
        video.setCrosshairsShown(false)
        // The fake stream sends frames until the stop: use a time step, not advanceUntilIdle.
        advanceTimeBy(100L)
        runCurrent()
        assertEquals(
            OverlayLayers(crosshairs = false, subDatums = false, guides = false, toolShape = false, drawing = false, referenceImage = false),
            video.state.value.visibleLayers(),
        )
        video.close()
    }

    @Test
    fun theTicksUseTheImageScaleOfTheFrameSize() = scope.runTest {
        val id = harness.start()
        harness.cameraStore.saveSelection(
            CameraSelection(id, TestCameras.logitech.key, TestCameras.mjpeg1080.key, harness.store.wallClock.now(), CameraCalibration(20.0, 1280, 720)),
        )
        val video = harness.ui.video(id)
        advanceUntilIdle()
        video.onTarget(target, 1920, 1080)
        video.onPlayTapped()
        advanceTimeBy(1_000L)
        runCurrent()
        // The scale of 1280 × 720 times the width ratio 1.5.
        assertEquals(30.0, video.state.value.imagePixelsPerMm!!, 1e-9)
        assertFalse(video.state.value.calibrationForOtherMode)
        assertEquals("30 pixels for each mm", CameraScreenPresenter.scaleState(video.state.value).english())
        video.close()
    }

    @Test
    fun theSetupStopsTheVideoAndTheVideoStartsAgainAfterTheSetup() = scope.runTest {
        val video = videoWithSavedMode()
        video.onTarget(target, 1280, 720)
        video.onPlayTapped()
        advanceTimeBy(1_000L)
        runCurrent()
        assertTrue(video.state.value.active)
        val setup = harness.ui.setup(harness.profile.id)
        advanceTimeBy(100L)
        runCurrent()
        assertFalse(video.state.value.active)
        assertFalse(video.state.value.viewWanted, "Only the setup streams")
        assertTrue(video.state.value.play.wanted)
        setup.close()
        advanceTimeBy(1_000L)
        runCurrent()
        assertTrue(video.state.value.active)
        assertEquals(2, harness.backend.startAttempts.size)
        video.close()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theStoredCameraRowStartsTheBackdropWhenTheViewShows() = runComposeUiTest {
        // A stored "Shown" starts the video when the view comes back (after a restart, or after the camera screen).
        var made: CameraVideoPresenter? = null
        scope.launch {
            val id = harness.start()
            harness.cameraStore.saveSelection(CameraSelection(id, TestCameras.logitech.key, TestCameras.mjpeg720.key, harness.store.wallClock.now()))
            made = harness.ui.video(id)
        }
        scope.advanceUntilIdle()
        val video = assertNotNull(made)
        var shown by mutableStateOf(true)
        setContent { org.superdro.core.ui.graphics.CameraBackdropSync(video, shown) }
        waitForIdle()
        scope.advanceUntilIdle()
        assertTrue(video.state.value.play.wanted)
        shown = false
        waitForIdle()
        scope.advanceUntilIdle()
        assertFalse(video.state.value.play.wanted)
    }

    @Test
    fun aNewSurfaceWithNoGapMovesTheRunToTheNewSurface() = scope.runTest {
        // The camera screen gives its surface before the graphical view removes its own surface, or a view makes a
        // new SurfaceView. The run must move to the new surface; else it draws into a surface that does not show.
        val video = videoWithSavedMode()
        val first = object : VideoTarget {}
        val second = object : VideoTarget {}
        video.onPlayTapped()
        video.onTarget(first, 1280, 720)
        advanceTimeBy(1_000L)
        runCurrent()
        try {
            assertTrue(video.state.value.active)
            video.onTarget(second, 1280, 720)
            video.onTargetGone(first)
            advanceTimeBy(1_000L)
            runCurrent()
            assertTrue(video.state.value.active)
            assertTrue(harness.backend.startTargets.last() === second, "the last start uses the new surface")
            assertEquals(2, harness.backend.startTargets.size)
            assertEquals(null, video.state.value.dialog)
        } finally {
            // Stop the video, so that the stream of the fake ends and the test scope becomes idle, also on a failure.
            if (video.state.value.play.wanted) video.onPlayTapped()
            advanceUntilIdle()
        }
        assertFalse(video.state.value.play.wanted)
    }
}
