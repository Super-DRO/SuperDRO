package org.superdro.core.ui.camera

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.camera.FakeUvcCameraBackend
import org.superdro.core.connection.DeviceId
import org.superdro.core.model.AxisId
import org.superdro.core.model.CameraCalibration
import org.superdro.core.model.CameraSelection
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.ProbeRoles
import org.superdro.core.model.ProbeType
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.ui.tabs.english
import org.superdro.core.ui.text.UiText

/** The camera screen: the image calibration and the point under the crosshairs. */
class CameraScreenPresenterTest {

    private val scope = TestScope()
    private val harness = CameraHarness(scope)
    private val target = FakeUvcCameraBackend.NoTarget

    @AfterTest
    fun close() = harness.close()

    private class Screen(val video: CameraVideoPresenter, val screen: CameraScreenPresenter, val readout: FakeTabReadout)

    private suspend fun open(): Screen {
        val id = harness.start()
        harness.cameraStore.saveSelection(CameraSelection(id, TestCameras.logitech.key, TestCameras.mjpeg720.key, harness.store.wallClock.now()))
        val readout = FakeTabReadout(DeviceId(id))
        val profile = harness.store.machines.loadFullProfile(id)!!
        readout.frame.value = ReadoutFrame(profile, profile.activeWorkspace!!, mapOf(AxisId.X to 10_000.0, AxisId.Y to 3_000.0))
        val video = harness.ui.video(id)
        val screen = harness.ui.screen(id, video, readout)
        scope.advanceUntilIdle()
        return Screen(video, screen, readout)
    }

    private fun Screen.play() {
        video.onTarget(target, 1280, 720)
        video.onPlayTapped()
        scope.advanceTimeBy(1_000L)
        scope.runCurrent()
    }

    private fun Screen.stop() {
        video.onPlayTapped()
        scope.advanceTimeBy(100L)
        scope.runCurrent()
        screen.close()
        video.close()
    }

    @Test
    fun theCalibrationNeedsTheVideo() = scope.runTest {
        val screen = open()
        screen.screen.onStartCalibration(CalibrationMethod.KnownDistance)
        advanceUntilIdle()
        assertNull(screen.screen.state.value.calibration)
        assertEquals("Start the video first.", screen.screen.state.value.message?.english())
        screen.stop()
    }

    @Test
    fun aKnownDistanceStoresTheScaleAndTheTicksUseIt() = scope.runTest {
        val screen = open()
        screen.play()
        screen.screen.onStartCalibration(CalibrationMethod.KnownDistance)
        runCurrent()
        // A view of 1280 × 720 px: one view pixel is one image pixel.
        screen.screen.onImageTouched(100.0, 100.0, 1280.0, 720.0)
        screen.screen.onImageTouched(300.0, 100.0, 1280.0, 720.0)
        runCurrent()
        assertEquals(CalibrationStep.Distance, screen.screen.state.value.calibration?.step)
        screen.screen.onDistanceText("10")
        screen.screen.onDistanceConfirmed()
        advanceTimeBy(100L)
        runCurrent()
        assertNull(screen.screen.state.value.calibration)
        assertEquals("20 pixels for each mm", screen.screen.state.value.message?.english())
        val stored = harness.cameraStore.selection(harness.profile.id, TestCameras.logitech.key).first()!!
        assertEquals(TestCameras.mjpeg720.key, stored.modeKey, "The mode of the selection stays")
        assertEquals(20.0, stored.calibration?.pixelsPerMm)
        assertEquals(20.0, screen.video.state.value.imagePixelsPerMm)
        assertEquals("20 pixels for each mm", CameraScreenPresenter.scaleState(screen.video.state.value).english())

        screen.screen.onClearScale()
        advanceTimeBy(100L)
        runCurrent()
        assertNull(harness.cameraStore.selection(harness.profile.id, TestCameras.logitech.key).first()!!.calibration)
        assertEquals(
            "The image has no scale. The crosshairs show no ticks on the video.",
            CameraScreenPresenter.scaleState(screen.video.state.value).english(),
        )
        screen.stop()
    }

    @Test
    fun aMachineMoveUsesTheReadoutPositions() = scope.runTest {
        val screen = open()
        screen.play()
        screen.screen.onStartCalibration(CalibrationMethod.MachineMove)
        runCurrent()
        screen.screen.onImageTouched(640.0, 360.0, 1280.0, 720.0)
        runCurrent()
        val profile = harness.store.machines.loadFullProfile(harness.profile.id)!!
        // The machine moves 10 mm in Y. The feature moves 300 px up in the image.
        screen.readout.frame.value = ReadoutFrame(profile, profile.activeWorkspace!!, mapOf(AxisId.X to 10_000.0, AxisId.Y to 13_000.0))
        screen.screen.onImageTouched(640.0, 60.0, 1280.0, 720.0)
        advanceTimeBy(100L)
        runCurrent()
        val calibration = harness.cameraStore.selection(harness.profile.id, TestCameras.logitech.key).first()!!.calibration!!
        assertEquals(30.0, calibration.pixelsPerMm, 1e-9)
        assertEquals(0.0, calibration.rotationArcSec, 1e-6)
        screen.stop()
    }

    @Test
    fun theMachineMoveNeedsAConnectedAdapter() = scope.runTest {
        val screen = open()
        screen.play()
        screen.readout.unavailable = UiText.Plain("No adapter connected")
        screen.screen.onStartCalibration(CalibrationMethod.MachineMove)
        runCurrent()
        assertNull(screen.screen.state.value.calibration)
        assertEquals("No adapter connected", screen.screen.state.value.message?.english())
        screen.stop()
    }

    @Test
    fun aScaleOfADifferentAspectRatioNeedsItsOwnCalibration() = scope.runTest {
        val screen = open()
        harness.cameraStore.saveCalibration(harness.profile.id, TestCameras.logitech.key, CameraCalibration(20.0, 640, 480), harness.store.wallClock.now())
        screen.play()
        assertTrue(screen.video.state.value.calibrationForOtherMode)
        assertNull(screen.video.state.value.imagePixelsPerMm, "No ticks with a scale of 4:3 on a 16:9 video")
        assertEquals(
            "The scale is for a different image size. Do the calibration for this mode.",
            CameraScreenPresenter.scaleState(screen.video.state.value).english(),
        )
        screen.stop()
    }

    @Test
    fun thePointUnderTheCrosshairsHasTheOffsetOfTheCameraProfile() = scope.runTest {
        val screen = open()
        assertTrue(screen.screen.state.value.probes.isEmpty())
        harness.probes.saveProbeProfile(ProbeProfile(id = "touch", profileId = harness.profile.id, type = ProbeType.TouchProbe, name = "Touch probe"))
        harness.probes.saveProbeProfile(
            ProbeProfile(id = "scope", profileId = harness.profile.id, type = ProbeType.Camera, name = "Microscope", port = null, xOffsetUm = 5_000.0, yOffsetUm = -2_000.0),
        )
        advanceUntilIdle()
        val state = screen.screen.state.value
        assertEquals(listOf("scope"), state.probes.map { probe -> probe.id })
        // X = 5 mm + 10 mm, Y = −2 mm + 3 mm, in the readout format of a metric mill.
        assertEquals("15.000" to "1.000", state.pointX to state.pointY)
        screen.stop()
    }

    @Test
    fun theEdgeFinderProfileIsTheFirstCameraProfile() {
        val first = ProbeProfile(id = "a", type = ProbeType.Camera, name = "A")
        val second = ProbeProfile(id = "b", type = ProbeType.Camera, name = "B")
        val touch = ProbeProfile(id = "t", type = ProbeType.TouchProbe, name = "T")
        val profiles = listOf(touch, first, second)
        assertEquals("b", CameraCrosshairPoint.preferred(profiles, ProbeRoles(edgeFinderProfileId = "b"))?.id)
        assertEquals("b", CameraCrosshairPoint.preferred(profiles, ProbeRoles(edgeFinderProfileId = "t", measurementInstrumentProfileId = "b"))?.id)
        assertEquals("a", CameraCrosshairPoint.preferred(profiles, ProbeRoles())?.id)
        assertNull(CameraCrosshairPoint.preferred(listOf(touch), ProbeRoles()))
    }
}
