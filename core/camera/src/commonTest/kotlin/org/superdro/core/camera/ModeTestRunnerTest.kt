package org.superdro.core.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.CameraModeResult
import org.superdro.core.model.CameraModeStatus
import org.superdro.core.util.Clock

/** The mode test with virtual time. */
class ModeTestRunnerTest {

    private val camera = CameraIdentity(0x046D, 0x0825, "Logitech", "C270", "/dev/bus/usb/001/002")
    private val mode = UvcMode(UvcCodec.Mjpeg, 1280, 720, 333_333L, "MJPG")

    private class Stored(val mode: UvcMode, val status: CameraModeStatus, val fps: Double?)

    private fun TestScope.runner(backend: FakeUvcCameraBackend, stored: MutableList<Stored>): ModeTestRunner =
        ModeTestRunner(
            scope = backgroundScope,
            clock = Clock { testScheduler.currentTime },
            preview = { target -> backend.start(camera, target, FakeUvcCameraBackend.NoTarget) },
            store = { target, status, fps -> stored += Stored(target, status, fps) },
        )

    private fun TestScope.advanceTo(timeMs: Long) {
        advanceTimeBy(timeMs - testScheduler.currentTime)
        runCurrent()
    }

    @Test
    fun aGoodModeGivesOkAfterTwoStartsOf3500Ms() = runTest {
        val backend = FakeUvcCameraBackend(listOf(camera))
        val stored = mutableListOf<Stored>()
        val runner = runner(backend, stored)
        runner.select(mode, null)
        assertEquals(ModeTestResult.Untested, runner.ui.value.result)
        runner.test()
        runCurrent()
        assertEquals(ModeTestState.Testing, runner.ui.value.state)
        assertTrue(runner.ui.value.previewOn)

        val progress = mutableListOf<Int>()
        for (time in 100L..6_900L step 100L) {
            advanceTo(time)
            progress += runner.ui.value.progress
            if (time == 3_400L) assertEquals(ModeTestState.Testing, runner.ui.value.state)
            if (time == 3_600L) assertEquals(ModeTestState.Verifying, runner.ui.value.state)
        }
        assertEquals(progress.sorted(), progress)
        assertTrue(progress.last() in 74..75)

        advanceTo(7_000L)
        val state = runner.ui.value
        assertEquals(ModeTestState.Ok, state.state)
        assertEquals(100, state.progress)
        assertTrue(state.canSave)
        assertEquals(ModeTestResult.WorkingStarted(1280, 720, "30.3", 0.2), state.result)
        assertEquals(
            "Video OK: 1280 × 720 at 30.3 fps. The first picture came after 0.2 s.",
            state.result.englishText(),
        )
        assertEquals(1, stored.size)
        assertEquals(CameraModeStatus.Good, stored[0].status)
        assertEquals(1000.0 / 33.0, stored[0].fps!!, 1e-9)
        assertEquals(mode, stored[0].mode)
        assertTrue(state.previewOn)
        runner.close()
        assertFalse(runner.ui.value.previewOn)
    }

    @Test
    fun aModeWithNoFramesFailsAfterTwoAttemptsAndStoresBad() = runTest {
        val backend = FakeUvcCameraBackend(listOf(camera), stream = { _, _ -> FakeStream(firstFrameDelayMs = null) })
        val stored = mutableListOf<Stored>()
        val runner = runner(backend, stored)
        runner.select(mode, null)
        runner.test()
        advanceTo(3_600L)
        assertEquals(ModeTestState.Testing, runner.ui.value.state)
        assertEquals(2, runner.ui.value.attempt)
        assertTrue(stored.isEmpty())
        advanceTo(7_000L)
        assertEquals(ModeTestState.Failed, runner.ui.value.state)
        assertEquals(ModeTestResult.Bad, runner.ui.value.result)
        assertEquals(listOf(CameraModeStatus.Bad), stored.map { row -> row.status })
        assertFalse(runner.ui.value.previewOn)
        assertEquals(2, backend.startAttempts.size)
    }

    @Test
    fun aModeThatTheCameraRefusesFailsAfterTheRetriesAndStoresBad() = runTest {
        val refused = CameraError(CameraErrorKind.ModeFailed, "stream failed: Invalid mode (-51)")
        val backend = FakeUvcCameraBackend(listOf(camera), stream = { _, _ -> FakeStream(startErrors = List(3) { refused }) })
        val stored = mutableListOf<Stored>()
        val runner = runner(backend, stored)
        runner.select(mode, null)
        runner.test()
        advanceTo(799L)
        assertEquals(ModeTestState.Testing, runner.ui.value.state)
        advanceTo(800L)
        assertEquals(ModeTestState.Failed, runner.ui.value.state)
        assertEquals(ModeTestResult.Bad, runner.ui.value.result)
        assertEquals(listOf(CameraModeStatus.Bad), stored.map { row -> row.status })
        assertEquals(3, backend.startAttempts.size)
    }

    @Test
    fun aDisconnectFailsTheTestWithNoStore() = runTest {
        val lost = CameraError(CameraErrorKind.Disconnected, "stream ended (code -1)")
        val backend = FakeUvcCameraBackend(listOf(camera), stream = { _, _ -> FakeStream(endAfterMs = 1_000L, endError = lost) })
        val stored = mutableListOf<Stored>()
        val runner = runner(backend, stored)
        runner.select(mode, null)
        runner.test()
        advanceTo(1_100L)
        assertEquals(ModeTestState.Failed, runner.ui.value.state)
        assertEquals(ModeTestResult.Failed(lost), runner.ui.value.result)
        assertEquals("camera_error_disconnected", runner.ui.value.result.messageKey)
        assertTrue(stored.isEmpty())
    }

    @Test
    fun aCancelStopsTheTestAndGivesTheStateOfTheMemory() = runTest {
        val backend = FakeUvcCameraBackend(listOf(camera))
        val stored = mutableListOf<Stored>()
        val runner = runner(backend, stored)
        runner.select(mode, CameraModeResult(camera.key, mode.key, CameraModeStatus.Good, 1280, 720, "MJPEG", 29.0))
        assertEquals(ModeTestState.KnownGood, runner.ui.value.state)
        assertEquals(ModeTestResult.Working(1280, 720, "29"), runner.ui.value.result)
        runner.test()
        advanceTo(1_000L)
        runner.cancel()
        runCurrent()
        assertEquals(ModeTestState.KnownGood, runner.ui.value.state)
        assertEquals(ModeTestResult.Stopped, runner.ui.value.result)
        assertEquals("You stopped the test.", runner.ui.value.result.englishText())
        assertFalse(runner.ui.value.previewOn)
        advanceTo(8_000L)
        assertEquals(ModeTestState.KnownGood, runner.ui.value.state)
        assertTrue(stored.isEmpty())
    }
}
