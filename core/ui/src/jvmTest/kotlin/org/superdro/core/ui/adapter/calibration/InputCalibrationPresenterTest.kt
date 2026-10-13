package org.superdro.core.ui.adapter.calibration

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.SessionState
import org.superdro.core.connection.LinkFailure
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.AxisId
import org.superdro.core.model.BacklashMode
import org.superdro.core.model.CalibrationStatus
import org.superdro.core.model.Channels
import org.superdro.core.model.EncoderKind
import org.superdro.core.model.EncoderProfile
import org.superdro.core.model.EncoderType
import org.superdro.core.model.MachineType
import org.superdro.core.model.MapPoint
import org.superdro.core.readout.input.ChannelStatus
import org.superdro.core.readout.input.ChannelValue
import org.superdro.core.readout.input.InputSnapshot
import org.superdro.core.ui.adapter.AdapterFixture
import org.superdro.core.ui.adapter.ScriptedSession
import org.superdro.core.ui.tabs.english
import org.superdro.core.ui.text.UiText

/** The presenter tests of the calibration wizard of an adapter input. */
class InputCalibrationPresenterTest {

    private val id = DeviceId("mill")
    private var fixture: AdapterFixture? = null

    @AfterTest
    fun closeFixture() {
        fixture?.close()
    }

    /** The parts of one test: the fixture, the session, and the presenter. */
    private class Rig(val fixture: AdapterFixture, val session: ScriptedSession, val presenter: InputCalibrationPresenter, val input: Char) {
        var time: Long = 1_000L

        val state: CalibrationUiState get() = presenter.uiState.value
    }

    private suspend fun TestScope.rig(
        input: Char = Channels.X,
        encoder: EncoderProfile? = EncoderProfile.defaultFor(input),
        type: MachineType = MachineType.VerticalMill,
        metric: Boolean = false,
        withAdapter: Boolean = true,
        sessionState: SessionState = SessionState.Connected(0L),
    ): Rig {
        val made = AdapterFixture(this).also { fixture = it }
        if (withAdapter) {
            val encoders = AdapterProfile().encoders.toMutableMap()
            if (encoder == null) encoders.remove(Channels.normalize(input)) else encoders[Channels.normalize(input)] = encoder
            made.machineWithAdapter(id, AdapterProfile(alias = "Test adapter", model = "tda-test", encoders = encoders), type) { it.copy(metricDefault = metric) }
        } else {
            made.machineWithoutAdapter(id, type)
        }
        val session = made.connect(ScriptedSession(id, sessionState))
        val presenter = InputCalibrationPresenter(id, input, made.deps)
        advanceUntilIdle()
        return Rig(made, session, presenter, input)
    }

    /** Gives one new sample of the raw count [raw] to the session. */
    private fun TestScope.feed(rig: Rig, raw: Long) {
        rig.time += 20L
        rig.session.push(
            InputSnapshot(channels = mapOf(rig.input to ChannelValue(rig.input, raw, raw, raw.toDouble(), rig.time, ChannelStatus.Ok))),
        )
        runCurrent()
    }

    /** Starts the sample of the current indicate step, gives [values], and ends the 15000 ms. */
    private fun TestScope.sample(rig: Rig, values: List<Long>) {
        rig.presenter.onContinue()
        runCurrent()
        assertIs<CalibrationStep.Sampling>(rig.state.step)
        values.forEach { value -> feed(rig, value) }
        advanceTimeBy(15_000L)
        runCurrent()
    }

    private fun constant(value: Long, count: Int = 12): List<Long> = List(count) { value }

    private fun TestScope.block(rig: Rig, text: String, metric: Boolean) {
        rig.presenter.onBlockUnitChange(metric)
        rig.presenter.onBlockTextChange(text)
        rig.presenter.onContinue()
        runCurrent()
    }

    private fun UiText.keyName(): String = when (this) {
        is UiText.Key -> key
        is UiText.Resource -> resource.key
        is UiText.Plain -> text
    }

    @Test
    fun linearInchExample() = runTest {
        val rig = rig()
        assertEquals(CalibrationStep.BlockSize, rig.state.step)
        assertFalse(rig.state.metric, "The default unit is the metric default of the profile: inch here.")
        block(rig, "2", metric = false)
        val first = assertIs<CalibrationStep.Indicate>(rig.state.step)
        assertEquals(CalibrationPosition.First, first.position)
        sample(rig, constant(1250L))
        assertEquals(CalibrationPosition.Second, assertIs<CalibrationStep.Indicate>(rig.state.step).position)
        sample(rig, constant(11_410L))
        val result = assertIs<CalibrationStep.Result>(rig.state.step)
        assertEquals(10_160.0, result.result.delta)
        assertEquals("5080.000", result.resolutionText)
        assertEquals("5.00", result.perCountText)
        assertFalse(result.result.reversed)
    }

    @Test
    fun linearMillimeterExampleIsReversed() = runTest {
        val rig = rig(metric = true)
        assertTrue(rig.state.metric)
        block(rig, "75", metric = true)
        sample(rig, constant(20_000L))
        // The trimmed list has six values of 4250 and four values of 4251: the mean is 4250.4.
        sample(rig, List(7) { 4250L } + List(5) { 4251L })
        val result = assertIs<CalibrationStep.Result>(rig.state.step)
        assertEquals(-15_749.6, result.result.delta, 1e-9)
        assertEquals("5333.865", result.resolutionText)
        assertEquals("4.76", result.perCountText)
        assertTrue(result.result.reversed)
    }

    @Test
    fun angularExampleHasNoBlockStep() = runTest {
        val rig = rig(input = Channels.W, encoder = EncoderProfile.defaultFor(Channels.W, EncoderKind.Angular))
        assertTrue(rig.state.angular)
        val first = assertIs<CalibrationStep.Indicate>(rig.state.step)
        assertNull(first.edge, "The angular procedure has no edge names.")
        sample(rig, constant(120L))
        sample(rig, constant(-35_880L))
        val result = assertIs<CalibrationStep.Result>(rig.state.step)
        assertEquals(-36_000.0, result.result.delta)
        assertEquals("7200.000", result.resolutionText)
        assertEquals("0.0500", result.perCountText)
        assertTrue(result.result.reversed)
    }

    @Test
    fun applyWritesTheEncoderProfile() = runTest {
        val rig = rig()
        block(rig, "2", metric = false)
        sample(rig, constant(1250L))
        sample(rig, constant(11_410L))
        rig.presenter.onApply()
        advanceUntilIdle()
        assertTrue(rig.state.done)
        assertTrue(rig.state.applied)
        val adapterId = rig.fixture.store.machines.loadProfile(id.value)!!.adapterProfileId!!
        val stored = rig.fixture.store.adapters.loadAdapterProfile(adapterId)!!.encoders.getValue(Channels.X)
        assertEquals(5080.0, stored.resolution)
        assertFalse(stored.reversed)
        assertEquals(CalibrationStatus.Calibrated, stored.calibration.status)
        assertEquals(rig.fixture.store.wallClock.current, stored.calibration.calibratedOn)
        assertEquals(MachineType.VerticalMill, stored.calibration.machineType)
        assertEquals(AxisId.X, stored.calibration.axisRole)
    }

    @Test
    fun applyOfAReversedResultWritesTheDirection() = runTest {
        val rig = rig(metric = true)
        block(rig, "75", metric = true)
        sample(rig, constant(20_000L))
        sample(rig, List(7) { 4250L } + List(5) { 4251L })
        rig.presenter.onApply()
        advanceUntilIdle()
        val adapterId = rig.fixture.store.machines.loadProfile(id.value)!!.adapterProfileId!!
        val stored = rig.fixture.store.adapters.loadAdapterProfile(adapterId)!!.encoders.getValue(Channels.X)
        assertEquals(5333.865, stored.resolution)
        assertTrue(stored.reversed)
    }

    @Test
    fun discardWritesNothing() = runTest {
        val rig = rig()
        block(rig, "2", metric = false)
        sample(rig, constant(1250L))
        sample(rig, constant(11_410L))
        rig.presenter.onDiscard()
        advanceUntilIdle()
        assertTrue(rig.state.done)
        assertFalse(rig.state.applied)
        val adapterId = rig.fixture.store.machines.loadProfile(id.value)!!.adapterProfileId!!
        val stored = rig.fixture.store.adapters.loadAdapterProfile(adapterId)!!.encoders.getValue(Channels.X)
        assertEquals(EncoderProfile.DEFAULT_LINEAR_RESOLUTION, stored.resolution)
        assertEquals(CalibrationStatus.NotCalibrated, stored.calibration.status)
    }

    @Test
    fun blockSizeZeroIsRequired() = runTest {
        val rig = rig()
        block(rig, "0", metric = true)
        val problem = assertNotNull(rig.state.problem)
        assertEquals(CalibrationProblemKind.BlockRequired, problem.kind)
        assertEquals("Block Size Required", problem.title.english())
        assertFalse(problem.warning)
        assertFalse(problem.canContinue)
        assertFalse(problem.canCancel)
        rig.presenter.onProblemRetry()
        assertNull(rig.state.problem)
        assertEquals(CalibrationStep.BlockSize, rig.state.step)
        block(rig, "abc", metric = true)
        assertEquals(CalibrationProblemKind.BlockRequired, rig.state.problem?.kind, "A text that is not a number gives 0.")
    }

    @Test
    fun blockTooSmallHasRetryAndContinue() = runTest {
        val rig = rig()
        block(rig, "1", metric = false)
        val problem = assertNotNull(rig.state.problem)
        assertEquals(CalibrationProblemKind.BlockTooSmall, problem.kind)
        assertEquals("Block Too Small", problem.title.english())
        assertTrue(problem.text.english().contains("at least 50.000 mm / 2.0000 in"))
        assertTrue(problem.warning)
        assertTrue(problem.canContinue)
        rig.presenter.onProblemContinue()
        assertEquals(CalibrationPosition.First, assertIs<CalibrationStep.Indicate>(rig.state.step).position)
        // 1 in = 25400 µm: the result uses the small block.
        sample(rig, constant(1000L))
        sample(rig, constant(6080L))
        assertEquals("5080.000", assertIs<CalibrationStep.Result>(rig.state.step).resolutionText)
    }

    @Test
    fun blockOf50MillimetersHasNoWarning() = runTest {
        val rig = rig()
        block(rig, "50", metric = true)
        assertNull(rig.state.problem)
        assertIs<CalibrationStep.Indicate>(rig.state.step)
    }

    @Test
    fun theSampleTakes15000MillisecondsWithAProgressEachSecond() = runTest {
        val rig = rig()
        block(rig, "2", metric = false)
        rig.presenter.onContinue()
        runCurrent()
        assertEquals(0L, assertIs<CalibrationStep.Sampling>(rig.state.step).elapsedMs)
        feed(rig, 10L)
        feed(rig, 10L)
        assertEquals(2, assertIs<CalibrationStep.Sampling>(rig.state.step).count)
        advanceTimeBy(999L)
        runCurrent()
        assertEquals(0L, assertIs<CalibrationStep.Sampling>(rig.state.step).elapsedMs)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(1_000L, assertIs<CalibrationStep.Sampling>(rig.state.step).elapsedMs)
        advanceTimeBy(13_000L)
        runCurrent()
        val late = assertIs<CalibrationStep.Sampling>(rig.state.step)
        assertEquals(14_000L, late.elapsedMs)
        assertEquals(14f / 15f, late.progress)
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(CalibrationProblemKind.TooFew, rig.state.problem?.kind, "The sample ends after 15000 ms.")
        assertTrue(EnglishText.of("calibration_sampling_text").contains("up to 15 seconds"))
    }

    @Test
    fun noReadingIsAnError() = runTest {
        val rig = rig()
        block(rig, "2", metric = false)
        sample(rig, emptyList())
        val problem = assertNotNull(rig.state.problem)
        assertEquals(CalibrationProblemKind.NoReading, problem.kind)
        assertEquals("No Reading Received", problem.title.english())
        assertFalse(problem.warning)
        rig.presenter.onProblemRetry()
        runCurrent()
        assertIs<CalibrationStep.Sampling>(rig.state.step, "Retry reads the same position again.")
    }

    @Test
    fun fewerThan10ValuesIsAnError() = runTest {
        val rig = rig()
        block(rig, "2", metric = false)
        sample(rig, constant(1250L, count = 9))
        val problem = assertNotNull(rig.state.problem)
        assertEquals(CalibrationProblemKind.TooFew, problem.kind)
        assertEquals("Reading Sample Too Small", problem.title.english())
        assertEquals("The app got 9 readings from Input X. It needs 10 or more. Push Retry to read the input again.", problem.text.english())
    }

    @Test
    fun aBurstOfSamplesKeepsEachValue() = runTest {
        val rig = rig()
        block(rig, "2", metric = false)
        rig.presenter.onContinue()
        runCurrent()
        // Twelve samples come before the presenter runs: the conflated inputs keep only the last,
        // the sample stream keeps each one (adapter need 4).
        repeat(12) {
            rig.time += 20L
            rig.session.push(
                InputSnapshot(channels = mapOf(rig.input to ChannelValue(rig.input, 1250L, 1250L, 1250.0, rig.time, ChannelStatus.Ok))),
            )
        }
        runCurrent()
        assertEquals(12, assertIs<CalibrationStep.Sampling>(rig.state.step).count)
        advanceTimeBy(15_000L)
        runCurrent()
        assertNull(rig.state.problem)
        assertEquals(CalibrationPosition.Second, assertIs<CalibrationStep.Indicate>(rig.state.step).position)
    }

    @Test
    fun tenValuesAreEnough() = runTest {
        val rig = rig()
        block(rig, "2", metric = false)
        sample(rig, constant(1250L, count = 10))
        assertNull(rig.state.problem)
        assertEquals(CalibrationPosition.Second, assertIs<CalibrationStep.Indicate>(rig.state.step).position)
    }

    @Test
    fun jitterOfMoreThanOneCountIsAWarningWithRetry() = runTest {
        val rig = rig()
        block(rig, "2", metric = false)
        // The trim removes 99 and 103: the trimmed list has 100 and 102, a spread of 2 counts.
        sample(rig, listOf(99L, 103L) + List(5) { 100L } + List(5) { 102L })
        val problem = assertNotNull(rig.state.problem)
        assertEquals(CalibrationProblemKind.Jitter, problem.kind)
        assertEquals("Excessive Jitter Detected", problem.title.english())
        assertTrue(problem.warning)
        assertTrue(problem.canCancel)
        assertEquals("The readings changed by 2 counts while the axis did not move. Hold the axis still and push Retry.", problem.text.english())
        rig.presenter.onProblemRetry()
        runCurrent()
        assertEquals(CalibrationPosition.First, assertIs<CalibrationStep.Sampling>(rig.state.step).position)
    }

    @Test
    fun aSpreadOfOneCountIsNoJitter() = runTest {
        val rig = rig()
        block(rig, "2", metric = false)
        sample(rig, List(6) { 100L } + List(6) { 101L })
        assertNull(rig.state.problem)
    }

    @Test
    fun readingsTooCloseIsAWarningAndRetryFindsTheSecondEdgeAgain() = runTest {
        val rig = rig()
        block(rig, "2", metric = false)
        sample(rig, constant(1250L))
        sample(rig, constant(1259L))
        val problem = assertNotNull(rig.state.problem)
        assertEquals(CalibrationProblemKind.TooClose, problem.kind)
        assertEquals("Readings Too Close", problem.title.english())
        rig.presenter.onProblemRetry()
        assertEquals(CalibrationPosition.Second, assertIs<CalibrationStep.Indicate>(rig.state.step).position)
        sample(rig, constant(1260L))
        assertIs<CalibrationStep.Result>(rig.state.step, "10 counts apart are not too close.")
    }

    @Test
    fun cancelStopsTheSampleAndClosesTheWizard() = runTest {
        val rig = rig()
        block(rig, "2", metric = false)
        rig.presenter.onContinue()
        runCurrent()
        rig.presenter.onCancel()
        advanceTimeBy(20_000L)
        runCurrent()
        assertTrue(rig.state.done)
        assertNull(rig.state.problem, "The stopped sample gives no check.")
    }

    @Test
    fun aSessionThatIsNotConnectedGivesNoSample() = runTest {
        val rig = rig(sessionState = SessionState.Connecting(1))
        assertEquals("Waiting for the adapter", rig.state.unavailable?.english())
        block(rig, "2", metric = false)
        val step = assertIs<CalibrationStep.Indicate>(rig.state.step)
        assertFalse(rig.state.canContinue)
        rig.presenter.onContinue()
        runCurrent()
        assertEquals(step, rig.state.step, "Continue does nothing while the session is not connected.")
        rig.session.state.value = SessionState.Connected(10L)
        runCurrent()
        assertNull(rig.state.unavailable)
        assertTrue(rig.state.canContinue)
    }

    @Test
    fun aLinkDropStopsTheSample() = runTest {
        val rig = rig()
        block(rig, "2", metric = false)
        rig.presenter.onContinue()
        runCurrent()
        rig.session.state.value = SessionState.Reconnecting(1, 5_000L, LinkFailure.Lost)
        runCurrent()
        assertEquals(CalibrationProblemKind.LinkLost, rig.state.problem?.kind)
        assertEquals("No adapter connected", rig.state.unavailable?.english())
    }

    @Test
    fun theEdgeNamesFollowTheMachineTypeAndTheAxis() = runTest {
        suspend fun edges(type: MachineType, input: Char): Pair<String, String> {
            val rig = rig(input = input, type = type)
            block(rig, "2", metric = false)
            val first = assertIs<CalibrationStep.Indicate>(rig.state.step).edge!!.english()
            sample(rig, constant(0L))
            val second = assertIs<CalibrationStep.Indicate>(rig.state.step).edge!!.english()
            rig.presenter.close()
            fixture?.close()
            return first to second
        }
        assertEquals("left" to "right", edges(MachineType.VerticalMill, Channels.X))
        assertEquals("front" to "back", edges(MachineType.VerticalMill, Channels.Y))
        assertEquals("bottom" to "top", edges(MachineType.VerticalMill, Channels.Z))
        assertEquals("bottom" to "top", edges(MachineType.HorizontalMill, Channels.Y))
        assertEquals("front" to "back", edges(MachineType.HorizontalMill, Channels.Z))
        assertEquals("front" to "back", edges(MachineType.Lathe, Channels.X))
        assertEquals("left" to "right", edges(MachineType.Lathe, Channels.Z))
        assertEquals("first" to "second", edges(MachineType.VerticalMill, Channels.U), "No axis uses u.")
        fixture = null
    }

    @Test
    fun theEdgeTextNamesTheEdge() = runTest {
        val rig = rig()
        block(rig, "2", metric = false)
        val step = assertIs<CalibrationStep.Indicate>(rig.state.step)
        assertEquals("Move the axis to the left edge of the block. Then push Continue.", UiText.Key("calibration_edge_text", "", listOf(step.edge!!)).english())
    }

    @Test
    fun theErrorMapCorrectsEachSample() = runTest {
        // Correction at 1250: rint(20 × 1250 / 20000) = 1. At 11410: rint(20 × 0.5705) = 11.
        val mapped = EncoderProfile.defaultFor(Channels.X).copy(errorMap = listOf(MapPoint(0L, 0L), MapPoint(20_000L, 20L)))
        val rig = rig(encoder = mapped)
        block(rig, "2", metric = false)
        sample(rig, constant(1250L))
        sample(rig, constant(11_410L))
        val result = assertIs<CalibrationStep.Result>(rig.state.step)
        assertEquals(10_170.0, result.result.delta)
        assertEquals("5085.000", result.resolutionText)
    }

    @Test
    fun theSampleAddsTheHysteresisOfTheInputWhenTheCountGoesUp() = runTest {
        val lag = EncoderProfile.defaultFor(Channels.X).copy(backlashMode = BacklashMode.Constant, backlashCounts = 3L)
        val rig = rig(encoder = lag)
        block(rig, "2", metric = false)
        // The axis comes up to the first edge: each value gets + 3.
        feed(rig, 1000L)
        feed(rig, 1250L)
        sample(rig, constant(1250L))
        // The axis comes down to the second edge: no hysteresis.
        feed(rig, 12_000L)
        feed(rig, 11_410L)
        sample(rig, constant(11_410L))
        val result = assertIs<CalibrationStep.Result>(rig.state.step)
        assertEquals(10_157.0, result.result.delta)
        assertEquals("5078.500", result.resolutionText)
    }

    @Test
    fun aMachineWithNoAdapterProfileCannotStart() = runTest {
        val rig = rig(withAdapter = false)
        assertEquals("calibration_no_adapter", rig.state.blocked?.keyName())
        assertFalse(rig.state.canContinue)
    }

    @Test
    fun theTachometerInputHasNoCalibration() = runTest {
        val rig = rig(input = Channels.T, encoder = EncoderProfile.defaultFor(Channels.T))
        assertEquals("calibration_tach_input", rig.state.blocked?.keyName())
    }

    @Test
    fun aDisabledInputCannotStart() = runTest {
        val rig = rig(encoder = EncoderProfile.defaultFor(Channels.X).copy(kind = EncoderKind.Disabled))
        assertEquals("calibration_disabled_input", rig.state.blocked?.keyName())
    }

    @Test
    fun anInputWithNoEncoderProfileUsesTheDefaultProfile() = runTest {
        val rig = rig(encoder = null)
        assertNull(rig.state.blocked)
        assertEquals(CalibrationStep.BlockSize, rig.state.step)
        block(rig, "2", metric = false)
        sample(rig, constant(1250L))
        sample(rig, constant(11_410L))
        rig.presenter.onApply()
        advanceUntilIdle()
        val adapterId = rig.fixture.store.machines.loadProfile(id.value)!!.adapterProfileId!!
        val stored = rig.fixture.store.adapters.loadAdapterProfile(adapterId)!!.encoders.getValue(Channels.X)
        assertEquals(5080.0, stored.resolution)
        assertEquals(EncoderType.Scale, stored.type)
    }

    @Test
    fun theLiveCountShowsTheRawCount() = runTest {
        val rig = rig()
        assertNull(rig.state.liveCount)
        feed(rig, 4321L)
        assertEquals(4321L, rig.state.liveCount)
    }

    @Test
    fun theAxisOfTheInputComesFromTheMachineProfile() = runTest {
        val rig = rig(input = Channels.Z)
        assertEquals(AxisId.Z, rig.state.axis)
        assertEquals("Input Z", rig.state.inputName.english())
    }
}

/** The English text of a string of `core:resources`. */
internal object EnglishText {
    fun of(key: String): String = UiText.Key(key, "").english()
}
