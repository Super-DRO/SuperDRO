package org.superdro.core.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.model.CameraModeStatus

class ModeTestMachineTest {

    private val timer = ModeTestEffect.StartTimer(3_500L)

    @Test
    fun aGoodModeStartsTwoTimesAndStoresGood() {
        val machine = ModeTestMachine()
        assertEquals(listOf(ModeTestEffect.StartPreview, timer), machine.test())
        assertEquals(ModeTestState.Testing, machine.state)
        assertEquals(1, machine.attempt)
        assertEquals(listOf(ModeTestEffect.RestartPreview, timer), machine.onTimer(frames = 12, measuredFps = null))
        assertEquals(ModeTestState.Verifying, machine.state)
        assertEquals(1, machine.attempt)
        assertEquals(listOf(ModeTestEffect.Store(CameraModeStatus.Good, 29.8)), machine.onTimer(frames = 90, measuredFps = 29.8))
        assertEquals(ModeTestState.Ok, machine.state)
        assertTrue(machine.state.canSave)
    }

    @Test
    fun aModeWithNoFramesGetsASecondAttemptThenBad() {
        val machine = ModeTestMachine()
        machine.test()
        assertEquals(listOf(ModeTestEffect.RestartPreview, timer), machine.onTimer(frames = 0, measuredFps = null))
        assertEquals(2, machine.attempt)
        assertEquals(ModeTestState.Testing, machine.state)
        assertEquals(
            listOf(ModeTestEffect.StopPreview, ModeTestEffect.Store(CameraModeStatus.Bad, null)),
            machine.onTimer(frames = 0, measuredFps = null),
        )
        assertEquals(ModeTestState.Failed, machine.state)
        assertFalse(machine.state.canSave)
    }

    @Test
    fun theVerifyingStartAlsoHasTwoAttempts() {
        val machine = ModeTestMachine()
        machine.test()
        machine.onTimer(frames = 5, measuredFps = null)
        assertEquals(listOf(ModeTestEffect.RestartPreview, timer), machine.onTimer(frames = 0, measuredFps = null))
        assertEquals(ModeTestState.Verifying, machine.state)
        assertEquals(2, machine.attempt)
        assertEquals(listOf(ModeTestEffect.Store(CameraModeStatus.Good, null)), machine.onTimer(frames = 3, measuredFps = null))
        assertEquals(ModeTestState.Ok, machine.state)
    }

    @Test
    fun aModeErrorStoresBadAndOtherErrorsStoreNothing() {
        val modeFailed = ModeTestMachine()
        modeFailed.test()
        assertEquals(
            listOf(ModeTestEffect.CancelTimer, ModeTestEffect.StopPreview, ModeTestEffect.Store(CameraModeStatus.Bad, null)),
            modeFailed.onError(CameraErrorKind.ModeFailed),
        )
        assertEquals(ModeTestState.Failed, modeFailed.state)

        val disconnected = ModeTestMachine()
        disconnected.test()
        assertEquals(listOf(ModeTestEffect.CancelTimer, ModeTestEffect.StopPreview), disconnected.onError(CameraErrorKind.Disconnected))
        assertEquals(ModeTestState.Failed, disconnected.state)

        val noFrames = ModeTestMachine()
        noFrames.test()
        assertEquals(emptyList(), noFrames.onError(CameraErrorKind.NoFrames))
        assertEquals(ModeTestState.Testing, noFrames.state)
    }

    @Test
    fun aCancelGivesTheStateOfTheModeMemory() {
        val machine = ModeTestMachine(ModeTestState.KnownBad)
        machine.test()
        assertEquals(listOf(ModeTestEffect.CancelTimer, ModeTestEffect.StopPreview), machine.cancel(CameraModeStatus.Bad))
        assertEquals(ModeTestState.KnownBad, machine.state)
        machine.test()
        machine.cancel(null)
        assertEquals(ModeTestState.Untested, machine.state)
        assertEquals(emptyList(), machine.cancel(null))
    }

    @Test
    fun theMemoryGivesTheFirstState() {
        assertEquals(ModeTestState.KnownGood, ModeTestState.fromMemory(CameraModeStatus.Good))
        assertEquals(ModeTestState.KnownBad, ModeTestState.fromMemory(CameraModeStatus.Bad))
        assertEquals(ModeTestState.Untested, ModeTestState.fromMemory(null))
        assertTrue(ModeTestState.KnownGood.canSave)
        assertFalse(ModeTestState.KnownBad.canSave)
        assertFalse(ModeTestState.Untested.canSave)
    }

    @Test
    fun saveWritesTheSelectedModeOrTheTestedMode() {
        val selected = UvcMode(UvcCodec.Mjpeg, 1280, 720, 333_333L, "MJPG")
        val tested = UvcMode(UvcCodec.Mjpeg, 1920, 1080, 333_333L, "MJPG")
        assertEquals(selected, modeToSave(ModeTestState.KnownGood, selected, tested))
        assertEquals(tested, modeToSave(ModeTestState.Ok, selected, tested))
        assertNull(modeToSave(ModeTestState.Failed, selected, tested))
        assertNull(modeToSave(ModeTestState.Testing, selected, tested))
    }

    @Test
    fun theProgressFormulaGivesZeroToOneHundred() {
        assertEquals(0, ModeTestProgress.formula(ModeTestState.Testing, 1, 0L))
        assertEquals(13, ModeTestProgress.formula(ModeTestState.Testing, 1, 1_750L))
        assertEquals(25, ModeTestProgress.formula(ModeTestState.Testing, 1, 3_500L))
        assertEquals(25, ModeTestProgress.formula(ModeTestState.Testing, 1, 9_000L))
        assertEquals(0, ModeTestProgress.formula(ModeTestState.Testing, 1, -10L))
        assertEquals(50, ModeTestProgress.formula(ModeTestState.Testing, 2, 3_500L))
        assertEquals(50, ModeTestProgress.formula(ModeTestState.Verifying, 1, 0L))
        assertEquals(63, ModeTestProgress.formula(ModeTestState.Verifying, 1, 1_750L))
        assertEquals(75, ModeTestProgress.formula(ModeTestState.Verifying, 1, 3_500L))
        assertEquals(100, ModeTestProgress.formula(ModeTestState.Verifying, 2, 3_500L))
    }

    @Test
    fun theProgressDoesNotGoDown() {
        val progress = ModeTestProgress()
        assertEquals(25, progress.update(ModeTestState.Testing, 2, 0L))
        assertEquals(25, progress.update(ModeTestState.Testing, 1, 100L))
        assertEquals(100, progress.update(ModeTestState.Ok, 1, 0L))
        progress.reset()
        assertEquals(0, progress.value)
        assertEquals(0, progress.update(ModeTestState.Untested, 0, 0L))
        assertEquals(100L, ModeTestProgress.TICK_MS)
    }

    @Test
    fun theResultTextsAreExact() {
        assertEquals(
            "Video OK: 1920 × 1080 at 29.8 fps. The first picture came after 1.2 s.",
            ModeTestResult.WorkingStarted(1920, 1080, "29.8", 1.24).englishText(),
        )
        assertEquals(
            "Video OK: 640 × 480 at 30 fps. The first picture came after 2.0 s.",
            ModeTestResult.WorkingStarted(640, 480, "30", 2.0).englishText(),
        )
        assertEquals("Video OK: 1280 × 720 at 15 fps.", ModeTestResult.Working(1280, 720, "15").englishText())
        assertEquals("Video OK: 1280 × 720.", ModeTestResult.WorkingNoFps(1280, 720).englishText())
        assertEquals("This mode gives no video.", ModeTestResult.Bad.englishText())
        assertEquals("You stopped the test.", ModeTestResult.Stopped.englishText())
        assertEquals("Not tested", ModeTestResult.Untested.englishText())
        assertEquals("camera_test_working_started", ModeTestResult.WorkingStarted(1, 1, "1", 1.0).messageKey)
        assertEquals("0,3", ModeTestResult.WorkingStarted(1, 1, "1", 0.25).startSecondsText(decimalSeparator = ','))
    }

    @Test
    fun theMemoryGivesTheResultBeforeATest() {
        val mode = UvcMode(UvcCodec.Mjpeg, 1280, 720, 333_333L, "MJPG")
        assertEquals(ModeTestResult.Working(1280, 720, "29.9"), ModeTestResult.fromMemory(mode, CameraModeStatus.Good, 29.93))
        assertEquals(ModeTestResult.WorkingNoFps(1280, 720), ModeTestResult.fromMemory(mode, CameraModeStatus.Good, null))
        assertEquals(ModeTestResult.Bad, ModeTestResult.fromMemory(mode, CameraModeStatus.Bad, 30.0))
        assertEquals(ModeTestResult.Untested, ModeTestResult.fromMemory(mode, null, null))
    }
}
