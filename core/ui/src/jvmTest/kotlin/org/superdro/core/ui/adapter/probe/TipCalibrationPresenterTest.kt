package org.superdro.core.ui.adapter.probe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.SessionState
import org.superdro.core.functions.tipcal.ClockPosition
import org.superdro.core.functions.tipcal.ResultCheckKind
import org.superdro.core.functions.tipcal.TipCalibrationError
import org.superdro.core.functions.tipcal.TipCalibrationReadiness
import org.superdro.core.functions.tipcal.TipCalibrationRun
import org.superdro.core.functions.tipcal.TipCalibrationSessions
import org.superdro.core.functions.tipcal.TouchPrompt
import org.superdro.core.model.AxisId
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.ProbeType
import org.superdro.core.readout.input.AxisValue
import org.superdro.core.readout.input.AxisValueStatus
import org.superdro.core.readout.input.InputSnapshot
import org.superdro.core.readout.input.ProbeEvent
import org.superdro.core.ui.adapter.AdapterFixture
import org.superdro.core.ui.adapter.ScriptedSession
import org.superdro.core.ui.sound.UiSound

/**
 * The tip calibration with the probe events of a
 * scripted session. The ring gauge is 25 mm. The touches are 11500 µm from the center, so the tip
 * diameter is 25000 − 2 × 11500 = 2000 µm.
 */
class TipCalibrationPresenterTest {

    private val mill = DeviceId("mill")
    private var sequence = 0L

    private class Setup(val fixture: AdapterFixture, val session: ScriptedSession, val presenter: TipCalibrationPresenter)

    private suspend fun TestScope.setup(
        profile: ProbeProfile = ProbeProfile(id = "probe", profileId = "mill", type = ProbeType.TouchProbe, name = "Probe", port = 'p', stylusDiameterUm = 2000.0),
        connected: Boolean = true,
        axes: Boolean = true,
    ): Setup {
        val fixture = AdapterFixture(this)
        fixture.machineWithAdapter(mill)
        fixture.probes.saveProbeProfile(profile)
        val session = fixture.connect(ScriptedSession(mill, if (connected) SessionState.Connected(0L) else SessionState.Connecting(1)))
        if (axes) {
            session.inputs.value = InputSnapshot(
                axes = mapOf(
                    AxisId.X to AxisValue(AxisId.X, 0.0, 0L, AxisValueStatus.Ok),
                    AxisId.Y to AxisValue(AxisId.Y, 0.0, 0L, AxisValueStatus.Ok),
                ),
            )
        }
        val presenter = TipCalibrationPresenter(fixture.deps, profile.id, null)
        advanceUntilIdle()
        return Setup(fixture, session, presenter)
    }

    private fun TestScope.touch(setup: Setup, x: Double, y: Double, port: Char = 'p') {
        sequence++
        setup.session.probeEvents.tryEmit(
            ProbeEvent(
                sequence = sequence,
                port = port,
                timeMs = sequence * 1000L,
                inputPositions = emptyMap(),
                axisPositions = mapOf(AxisId.X to x, AxisId.Y to y),
                incompleteAxes = emptySet(),
                axisVelocities = mapOf(AxisId.X to -300.0, AxisId.Y to 0.0),
            ),
        )
        advanceUntilIdle()
    }

    private fun TestScope.touches(setup: Setup, x: Double, y: Double) {
        touch(setup, x - 0.5, y)
        touch(setup, x, y)
        touch(setup, x + 0.5, y)
    }

    @Test
    fun theFullRunGivesTheTipDiameterAndApplyWritesTheProfile() = runTest {
        val setup = setup()
        val presenter = setup.presenter
        assertNull(presenter.uiState.value.readiness)
        assertEquals(TipStep.Gauge, presenter.uiState.value.step)
        assertEquals(mill, presenter.device, "A profile of the machine uses the session of the machine.")
        presenter.setGauge("25")
        presenter.continueGauge()
        advanceUntilIdle()
        assertEquals(TipStep.Touches, presenter.uiState.value.step)
        assertEquals(ClockPosition.Nine, presenter.uiState.value.clock)
        assertEquals(TouchPrompt.FirstPosition, presenter.uiState.value.prompt)
        assertFalse(presenter.uiState.value.canContinue)

        touches(setup, -11500.0, 0.0)
        assertEquals(3, presenter.uiState.value.touches)
        assertTrue(presenter.uiState.value.canContinue, "Continue is enabled from 3 touches.")
        assertEquals(listOf(UiSound.Short, UiSound.Short, UiSound.Long), setup.fixture.uiSounds.played, "The third touch gives the long sound.")
        touch(setup, -11500.0, 0.0)
        assertEquals(UiSound.Short, setup.fixture.uiSounds.played.last())
        presenter.continueTouches()
        advanceUntilIdle()
        val stored = TipCalibrationSessions.decode(setup.fixture.probes.loadProbeProfile("probe")!!.calibrationSession)!!
        assertFalse(stored.complete, "The run is saved with complete = false after each step.")
        assertEquals(listOf(ClockPosition.Nine), stored.positions.map { position -> position.clock })
        assertEquals(25000.0, stored.gauge)

        assertEquals(ClockPosition.Three, presenter.uiState.value.clock)
        assertEquals(TouchPrompt.NextPosition, presenter.uiState.value.prompt)
        touches(setup, 11500.0, 0.0)
        presenter.continueTouches()
        advanceUntilIdle()
        assertEquals(TipStep.Recenter, presenter.uiState.value.step, "\"Back to the middle\" comes before 12 o'clock.")
        val marker = assertNotNull(presenter.uiState.value.marker)
        assertEquals(0.0, marker.centerXUm, 1e-9)
        assertEquals(23000.0, marker.spanUm, 1e-9)
        presenter.continueRecenter()
        assertEquals(ClockPosition.Twelve, presenter.uiState.value.clock)
        touches(setup, 0.0, 11500.0)
        presenter.continueTouches()
        advanceUntilIdle()
        assertEquals(ClockPosition.Six, presenter.uiState.value.clock)
        touches(setup, 0.0, -11500.0)
        presenter.continueTouches()
        advanceUntilIdle()

        val state = presenter.uiState.value
        assertEquals(TipStep.Result, state.step)
        assertEquals(ResultCheckKind.Normal, state.check)
        val result = assertNotNull(state.result)
        assertEquals(2000.0, result.effectiveDiameterUm, 0.01, "tip = gauge − 2R")
        assertEquals(13, result.touchCount)
        assertNull(result.asymmetry)

        presenter.apply()
        advanceUntilIdle()
        assertTrue(presenter.uiState.value.done)
        val applied = setup.fixture.probes.loadProbeProfile("probe")!!
        assertEquals(result.effectiveDiameterUm, applied.stylusDiameterUm)
        assertEquals(result.effectiveDiameterUm, applied.effectiveDiameterUm)
        assertEquals(result.repeatabilityUm, applied.calibrationResidualUm, "The residual is the repeatability, not the fit residual.")
        assertEquals(setup.fixture.store.wallClock.current, applied.calibratedOn)
        val session = TipCalibrationSessions.decode(applied.calibrationSession)!!
        assertTrue(session.complete)
        assertEquals("2026-10-12 08:00:00", session.completed)
        assertEquals(4, session.positions.size)
        assertEquals(TipCalibrationSessions.digest(applied.calibrationSession!!), applied.sessionDigest, "The SHA-256 digest of the session JSON.")
        assertEquals(64, applied.sessionDigest!!.length)
        setup.presenter.close()
        setup.fixture.close()
    }

    @Test
    fun aTouchWithNoPositionGivesTheErrorSoundAndATouchOfTheOtherPortIsIgnored() = runTest {
        val setup = setup()
        setup.presenter.setGauge("25")
        setup.presenter.continueGauge()
        advanceUntilIdle()
        touch(setup, Double.NaN, 0.0)
        assertTrue(setup.presenter.uiState.value.touchRefused)
        assertEquals(0, setup.presenter.uiState.value.touches)
        assertEquals(listOf(UiSound.Error), setup.fixture.uiSounds.played)
        touch(setup, -11500.0, 0.0, port = 'q')
        assertEquals(0, setup.presenter.uiState.value.touches, "The profile listens only to its own port.")
        touch(setup, -11500.0, 0.0)
        assertEquals(1, setup.presenter.uiState.value.touches)
        assertFalse(setup.presenter.uiState.value.touchRefused)
        setup.presenter.close()
        setup.fixture.close()
    }

    @Test
    fun theSpreadIsTheLargestDistanceBetweenTwoTouches() = runTest {
        val setup = setup()
        setup.presenter.setGauge("25")
        setup.presenter.continueGauge()
        advanceUntilIdle()
        touch(setup, -11500.0, 0.0)
        touch(setup, -11540.0, 0.0)
        touch(setup, -11500.0, 0.0)
        setup.presenter.continueTouches()
        advanceUntilIdle()
        val error = assertNotNull(setup.presenter.uiState.value.error, "The first and the last touch agree, but the middle touch is 40 µm away.")
        assertEquals(TipCalibrationError.PositionScattered, error.error)
        assertEquals(40.0, error.spreadUm!!, 1e-9)
        assertEquals(20.0, error.limitUm, "The default spread limit of a profile.")
        setup.presenter.retry()
        assertNull(setup.presenter.uiState.value.error)
        assertEquals(ClockPosition.Nine, setup.presenter.uiState.value.clock, "Retry takes the same position again.")
        assertEquals(0, setup.presenter.uiState.value.touches)
        setup.presenter.close()
        setup.fixture.close()
    }

    @Test
    fun aRingGaugeThatIsNotLargerThanTheTipIsRefused() = runTest {
        val setup = setup()
        setup.presenter.setGauge("2")
        setup.presenter.continueGauge()
        advanceUntilIdle()
        assertEquals(TipCalibrationError.GaugeUnusable, setup.presenter.uiState.value.error?.error)
        setup.presenter.retry()
        assertEquals(TipStep.Gauge, setup.presenter.uiState.value.step, "Retry asks for the ring gauge again.")
        setup.presenter.setGaugeMetric(false)
        setup.presenter.setGauge("1")
        setup.presenter.continueGauge()
        advanceUntilIdle()
        assertEquals(TipStep.Touches, setup.presenter.uiState.value.step, "1 in = 25400 µm is usable.")
        setup.presenter.cancel()
        assertTrue(setup.presenter.uiState.value.done)
        setup.presenter.close()
        setup.fixture.close()
    }

    @Test
    fun theReadinessChecksComeInTheirOrder() = runTest {
        val notConnected = setup(connected = false)
        assertEquals(TipCalibrationReadiness.NotConnected, notConnected.presenter.uiState.value.readiness)
        notConnected.session.state.value = SessionState.Connected(0L)
        advanceUntilIdle()
        assertNull(notConnected.presenter.uiState.value.readiness, "The dialog closes when the condition is gone.")
        notConnected.presenter.close()
        notConnected.fixture.close()

        val noAxes = setup(axes = false)
        assertEquals(TipCalibrationReadiness.NoXyAxes, noAxes.presenter.uiState.value.readiness)
        noAxes.presenter.close()
        noAxes.fixture.close()

        val noPort = setup(profile = ProbeProfile(id = "edge", profileId = "mill", type = ProbeType.EdgeFinder2D, name = "Edge", port = null, stylusDiameterUm = 4000.0))
        assertEquals(TipCalibrationReadiness.NotCalibratable, noPort.presenter.uiState.value.readiness)
        noPort.presenter.close()
        noPort.fixture.close()

        val fixture = AdapterFixture(this)
        val gone = TipCalibrationPresenter(fixture.deps, "missing", mill)
        advanceUntilIdle()
        assertEquals(TipCalibrationReadiness.ProfileGone, gone.uiState.value.readiness)
        gone.close()
        fixture.close()
    }

    @Test
    fun anUnfinishedRunCanContinue() = runTest {
        val base = ProbeProfile(id = "probe", profileId = "mill", type = ProbeType.TouchProbe, name = "Probe", port = 'p', stylusDiameterUm = 2000.0)
        var run = TipCalibrationRun(profileId = "probe", started = "2026-10-12 07:00:00", gaugeUm = 25000.0)
        listOf(ClockPosition.Nine to -11500.0, ClockPosition.Three to 11500.0).forEach { (clock, x) ->
            val touches = org.superdro.core.functions.tipcal.TouchCollection(clock, List(3) { org.superdro.core.functions.tipcal.Touch(x, 0.0, 0.0, 0.0, 0L) })
            run = (run.continueWith(touches, 20.0) as org.superdro.core.functions.tipcal.TipCalibrationStep.Accepted).run
        }
        val setup = setup(profile = TipCalibrationSessions.withRunSession(base, run))
        assertEquals(2, setup.presenter.uiState.value.resumePositions)
        setup.presenter.resume()
        advanceUntilIdle()
        assertNull(setup.presenter.uiState.value.resumePositions)
        assertEquals(TipStep.Recenter, setup.presenter.uiState.value.step, "The run continues at the step before 12 o'clock.")
        assertEquals(setOf(ClockPosition.Nine, ClockPosition.Three), setup.presenter.uiState.value.captured)
        setup.presenter.close()
        setup.fixture.close()
    }
}
