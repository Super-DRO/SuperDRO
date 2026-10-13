package org.superdro.core.ui.camera

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.camera.CameraError
import org.superdro.core.camera.CameraErrorKind
import org.superdro.core.camera.CameraException
import org.superdro.core.camera.FakeStream
import org.superdro.core.camera.FakeUvcCameraBackend
import org.superdro.core.camera.ModeTestState
import org.superdro.core.connection.ConnectionRequest
import org.superdro.core.model.CameraCalibration
import org.superdro.core.model.CameraModeResult
import org.superdro.core.model.CameraModeStatus
import org.superdro.core.model.CameraSelection
import org.superdro.core.ui.tabs.english

/** The presenter of "Camera setup". */
class CameraSetupPresenterTest {

    private val scope = TestScope()
    private val harness = CameraHarness(scope)
    private val target = FakeUvcCameraBackend.NoTarget

    @AfterTest
    fun close() = harness.close()

    private suspend fun open(): CameraSetupPresenter {
        val id = harness.start()
        return harness.ui.setup(id).also { scope.advanceUntilIdle() }
    }

    @Test
    fun theSetupSelectsTheCameraAndTheBestModeOfTheList() = scope.runTest {
        val setup = open()
        val state = setup.state.value
        assertEquals(TestCameras.logitech, state.camera)
        assertEquals("Logitech C270 (046d:0825)", CameraTexts.cameraLabel(TestCameras.logitech).english())
        // One line for each codec and size, with the highest rate of the real intervals.
        assertEquals(
            listOf("MJPEG 1920 × 1080, 30 fps", "MJPEG 1280 × 720, 30 fps", "YUY2 640 × 480, 30 fps"),
            state.modes.map { row -> CameraTexts.modeLabel(row.label).english() },
        )
        assertEquals(TestCameras.mjpeg1080, state.mode)
        assertEquals("Not tested", state.result.english())
        assertEquals("Never", state.lastTest.english())
        assertFalse(state.canSave)
        assertTrue(state.canTest)
        setup.close()
    }

    @Test
    fun theSavedModeOfTheProfileIsTheFirstMode() = scope.runTest {
        val id = harness.start()
        harness.cameraStore.saveSelection(CameraSelection(id, TestCameras.logitech.key, TestCameras.yuy2Vga.key, harness.store.wallClock.now()))
        val setup = harness.ui.setup(id)
        advanceUntilIdle()
        assertEquals(TestCameras.yuy2Vga, setup.state.value.mode)
        setup.close()
    }

    @Test
    fun aGoodModeOfTheCameraModelIsTheFirstModeWithNoSelection() = scope.runTest {
        val id = harness.start()
        val time = harness.store.wallClock.now() - 10.minutes
        harness.cameraStore.saveModeResult(CameraModeResult(TestCameras.logitech.key, TestCameras.mjpeg720.key, CameraModeStatus.Good, 1280, 720, "MJPEG", 29.8, time))
        harness.cameraStore.saveModeResult(CameraModeResult(TestCameras.logitech.key, TestCameras.yuy2Vga.key, CameraModeStatus.Bad, 640, 480, "UNCOMPRESSED", null, time))
        val setup = harness.ui.setup(id)
        advanceUntilIdle()
        val state = setup.state.value
        assertEquals(TestCameras.mjpeg720, state.mode)
        assertEquals("Video OK: 1280 × 720 at 29.8 fps.", state.result.english())
        assertEquals("2026-10-12 07:50", state.lastTest.english())
        // The measured rate shows when it is 0.05 fps or more from the nominal rate.
        assertEquals("MJPEG 1280 × 720, 30 fps (measured 29.8 fps)", CameraTexts.modeLabel(state.modes[1].label).english())
        assertEquals(listOf(null, CameraModeStatus.Good, CameraModeStatus.Bad), state.modes.map { row -> row.label.status })
        assertTrue(state.canSave, "A GOOD mode of the mode memory can be saved with no test")
        setup.close()
    }

    @Test
    fun theTestPassesAfterTwoStartsOf3500MsAndSaveWritesTheSelection() = scope.runTest {
        val setup = open()
        setup.onPreviewTarget(target, 1920, 1080)
        setup.onTestTapped()
        runCurrent()
        assertEquals(ModeTestState.Testing, setup.state.value.test.state)
        assertTrue(setup.state.value.test.previewOn)
        advanceTimeBy(3_500L)
        runCurrent()
        assertEquals(ModeTestState.Verifying, setup.state.value.test.state)
        assertEquals(50, setup.state.value.test.progress)
        advanceTimeBy(3_500L)
        runCurrent()
        val state = setup.state.value
        assertEquals(ModeTestState.Ok, state.test.state)
        assertEquals(100, state.test.progress)
        assertEquals("Video OK: 1920 × 1080 at 30.3 fps. The first picture came after 0.2 s.", state.result.english())
        assertEquals("Now", state.lastTest.english())
        assertEquals(2, harness.backend.startAttempts.size, "The test starts the mode two times")
        val row = harness.cameraStore.loadModeResults(TestCameras.logitech.key).single()
        assertEquals(CameraModeStatus.Good to TestCameras.mjpeg1080.key, row.status to row.modeKey)
        assertEquals(30.30, row.measuredFps!!, 0.01)
        assertTrue(state.canSave)

        setup.onSave()
        advanceUntilIdle()
        assertTrue(setup.state.value.closed)
        assertEquals(TestCameras.mjpeg1080.key, harness.cameraStore.selection(harness.profile.id, TestCameras.logitech.key).first()?.modeKey)
        setup.close()
    }

    @Test
    fun aModeWithNoFramesFailsAfterTwoAttemptsAndIsBad() = scope.runTest {
        val setup = open()
        harness.stream(FakeStream(firstFrameDelayMs = null, endAfterMs = 10_000L))
        setup.onPreviewTarget(target, 1920, 1080)
        setup.onTestTapped()
        advanceTimeBy(3_500L)
        runCurrent()
        assertEquals(ModeTestState.Testing to 2, setup.state.value.test.state to setup.state.value.test.attempt)
        advanceTimeBy(3_500L)
        runCurrent()
        val state = setup.state.value
        assertEquals(ModeTestState.Failed, state.test.state)
        assertEquals("This mode gives no video.", state.result.english())
        assertEquals(CameraModeStatus.Bad, harness.cameraStore.loadModeResults(TestCameras.logitech.key).single().status)
        assertEquals(CameraModeStatus.Bad, state.modes.first().label.status)
        assertFalse(state.canSave)
        setup.close()
    }

    @Test
    fun stopTestGivesTheStateOfTheModeMemory() = scope.runTest {
        val setup = open()
        setup.onPreviewTarget(target, 1920, 1080)
        setup.onTestTapped()
        advanceTimeBy(1_000L)
        runCurrent()
        setup.onTestTapped()
        runCurrent()
        val state = setup.state.value
        assertEquals(ModeTestState.Untested, state.test.state)
        assertEquals("You stopped the test.", state.result.english())
        assertFalse(state.test.previewOn)
        assertTrue(harness.cameraStore.loadModeResults(TestCameras.logitech.key).isEmpty())
        setup.close()
    }

    @Test
    fun aRefusedUsbPermissionShowsItsText() = scope.runTest {
        harness.backend.accessResult = Result.failure(CameraException(CameraError(CameraErrorKind.UsbPermissionDenied, "refused")))
        val setup = open()
        val state = setup.state.value
        assertEquals(
            "SuperDRO did not get access to this USB camera. Disconnect the camera, connect it again, and allow the access.",
            state.error?.english(),
        )
        assertFalse(state.openSettings)
        assertTrue(state.modes.isEmpty())
        assertFalse(state.canTest)
        setup.close()
    }

    @Test
    fun aBlockedCameraPermissionOffersTheAppSettings() = scope.runTest {
        harness.backend.accessResult = Result.failure(CameraException(CameraError(CameraErrorKind.CameraPermissionMissing, openAppSettings = true)))
        val setup = open()
        assertEquals("The camera permission is off. Turn it on in the app settings of SuperDRO.", setup.state.value.error?.english())
        assertTrue(setup.state.value.openSettings)
        setup.onOpenSettings()
        assertEquals(listOf<ConnectionRequest>(ConnectionRequest.OpenAppSettings), harness.requests.sent)
        setup.close()
    }

    @Test
    fun withNoCameraTheSetupShowsTheNoDeviceText() = scope.runTest {
        harness.backend.detach(TestCameras.logitech)
        val setup = open()
        assertNull(setup.state.value.camera)
        assertEquals(
            "No USB camera is connected. Connect the camera. If you use a hub, use a hub with its own power supply.",
            setup.state.value.error?.english(),
        )
        // A camera that the user connects then loads.
        harness.backend.attach(TestCameras.noName)
        advanceUntilIdle()
        assertEquals(TestCameras.noName, setup.state.value.camera)
        assertEquals("1234:abcd", CameraTexts.cameraLabel(TestCameras.noName).english())
        assertEquals(TestCameras.yuy2Vga, setup.state.value.mode)
        setup.close()
    }

    @Test
    fun saveKeepsTheImageScaleOfTheCamera() = scope.runTest {
        val id = harness.start()
        val scale = CameraCalibration(20.0, 1280, 720)
        harness.cameraStore.saveSelection(CameraSelection(id, TestCameras.logitech.key, TestCameras.mjpeg720.key, harness.store.wallClock.now(), scale))
        harness.cameraStore.saveModeResult(
            CameraModeResult(TestCameras.logitech.key, TestCameras.mjpeg1080.key, CameraModeStatus.Good, 1920, 1080, "MJPEG", 30.0, harness.store.wallClock.now()),
        )
        val setup = harness.ui.setup(id)
        advanceUntilIdle()
        setup.onModeSelected(TestCameras.mjpeg1080)
        advanceUntilIdle()
        setup.onSave()
        advanceUntilIdle()
        val row = harness.cameraStore.selection(id, TestCameras.logitech.key).first()!!
        assertEquals(TestCameras.mjpeg1080.key, row.modeKey)
        assertEquals(scale, row.calibration)
        setup.close()
    }

    @Test
    fun theGateIsOpenWhileTheSetupIsOpen() = scope.runTest {
        val setup = open()
        assertTrue(harness.gate.open.value)
        setup.close()
        assertFalse(harness.gate.open.value)
        setup.close()
        assertFalse(harness.gate.open.value, "A second close does not change the count")
    }
}
