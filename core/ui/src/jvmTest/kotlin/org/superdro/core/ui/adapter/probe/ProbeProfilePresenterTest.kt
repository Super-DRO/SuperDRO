package org.superdro.core.ui.adapter.probe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.SessionState
import org.superdro.core.functions.tipcal.CalibrationRecord
import org.superdro.core.functions.tipcal.SpreadLimitSource
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.ProbeType
import org.superdro.core.readout.input.AxisValue
import org.superdro.core.readout.input.AxisValueStatus
import org.superdro.core.readout.input.InputSnapshot
import org.superdro.core.store.repository.ProbeRepository
import org.superdro.core.ui.adapter.AdapterFixture
import org.superdro.core.ui.adapter.ScriptedSession
import org.superdro.core.ui.tabs.english

/** The presenter of the probe profile screen. */
class ProbeProfilePresenterTest {

    private val mill = DeviceId("mill")

    @Test
    fun aNewProfileIsATouchProbeOnPortPInTheUnitsOfTheMachine() = runTest {
        val fixture = AdapterFixture(this)
        fixture.machineWithAdapter(mill) { profile -> profile.copy(metricDefault = false) }
        val presenter = ProbeProfilePresenter(fixture.deps, null, mill, ids = { "new-1" })
        advanceUntilIdle()
        val state = presenter.uiState.value
        assertTrue(state.isNew)
        assertEquals(ProbeType.TouchProbe, state.type)
        assertEquals('p', state.form.port)
        assertFalse(state.form.metric, "The units of a new profile are the units of the machine profile.")
        assertTrue(state.autoName)
        presenter.setType(ProbeType.Camera)
        assertNull(presenter.uiState.value.form.port, "A camera has no port.")
        presenter.setType(ProbeType.EdgeFinder2D)
        presenter.setDiameter("0.2")
        presenter.setMetric(true)
        assertEquals("5.080", presenter.uiState.value.form.diameter, "The units change converts the texts.")
        presenter.save("2D edge finder 5.080 mm")
        advanceUntilIdle()
        assertTrue(presenter.uiState.value.done)
        val saved = fixture.probes.loadProbeProfile("new-1")!!
        assertEquals("2D edge finder 5.080 mm", saved.name, "An empty name gives the automatic name.")
        assertEquals(5080.0, saved.stylusDiameterUm, 1e-9)
        assertEquals("mill", saved.profileId)
        assertTrue(saved.metric)
        assertNull(saved.port)
        presenter.close()
        fixture.close()
    }

    @Test
    fun anErrorKeepsTheScreenOpen() = runTest {
        val fixture = AdapterFixture(this)
        fixture.machineWithAdapter(mill) { profile -> profile.copy(metricDefault = true) }
        val presenter = ProbeProfilePresenter(fixture.deps, null, mill)
        advanceUntilIdle()
        presenter.save("Touch probe")
        advanceUntilIdle()
        assertFalse(presenter.uiState.value.done)
        assertEquals("Enter the tip diameter.", presenter.uiState.value.errors.getValue(ProbeField.Diameter).english())
        presenter.setDiameter("2")
        assertFalse(ProbeField.Diameter in presenter.uiState.value.errors, "A change of the field clears its error.")
        presenter.close()
        fixture.close()
    }

    @Test
    fun aStandardTipIsReadOnly() = runTest {
        val fixture = AdapterFixture(this)
        fixture.machineWithAdapter(mill)
        val tip = ProbeRepository.STANDARD_TIP_IDS[3]
        val presenter = ProbeProfilePresenter(fixture.deps, tip, mill)
        advanceUntilIdle()
        val state = presenter.uiState.value
        assertTrue(state.standardTip)
        assertEquals("4.000", state.form.diameter)
        presenter.setDiameter("5")
        assertEquals("4.000", presenter.uiState.value.form.diameter, "A standard tip does not change.")
        presenter.save("x")
        advanceUntilIdle()
        assertFalse(presenter.uiState.value.done)
        presenter.close()
        fixture.close()
    }

    @Test
    fun calibrateSavesFirstAndThenOpensTheTipCalibration() = runTest {
        val fixture = AdapterFixture(this)
        fixture.machineWithAdapter(mill) { profile -> profile.copy(metricDefault = true) }
        val presenter = ProbeProfilePresenter(fixture.deps, null, mill, ids = { "probe-1" })
        advanceUntilIdle()
        presenter.setDiameter("2")
        assertTrue(presenter.uiState.value.canCalibrate)
        presenter.calibrate("Touch probe 2.000 mm")
        advanceUntilIdle()
        assertEquals("probe-1", presenter.uiState.value.openCalibration)
        assertNotNull(fixture.probes.loadProbeProfile("probe-1"))
        assertFalse(presenter.uiState.value.done, "The screen stays below the tip calibration.")
        presenter.calibrationOpened()
        assertNull(presenter.uiState.value.openCalibration)
        presenter.setPort(null)
        assertEquals('p', presenter.uiState.value.form.port, "A touch probe always has a port.")
        val stored = fixture.probes.loadProbeProfile("probe-1")!!
        fixture.probes.saveProbeProfile(stored.copy(stylusDiameterUm = 1990.0, effectiveDiameterUm = 1990.0, calibratedOn = Instant.parse("2026-10-12T08:00:00Z")))
        advanceUntilIdle()
        assertEquals("1.990", presenter.uiState.value.form.diameter, "Apply of the tip calibration changes the field of the new profile too.")
        presenter.close()
        fixture.close()
    }

    @Test
    fun theSyncButtonsUseTheShownValueOfTheReadout() = runTest {
        val fixture = AdapterFixture(this)
        fixture.machineWithAdapter(mill) { profile -> profile.copy(metricDefault = true) }
        val profile = fixture.store.machines.loadProfile("mill")!!
        fixture.store.machines.setAxisState(profile.activeWorkspaceId, AxisId.X, AxisState(absoluteOffset = 1000.0))
        val session = fixture.connect(ScriptedSession(mill, SessionState.Connecting(1)))
        session.inputs.value = InputSnapshot(
            axes = mapOf(
                AxisId.X to AxisValue(AxisId.X, 12000.0, 0L, AxisValueStatus.Ok),
                AxisId.Y to AxisValue(AxisId.Y, -3000.0, 0L, AxisValueStatus.Ok),
                AxisId.Z to AxisValue(AxisId.Z, 500.0, 0L, AxisValueStatus.Ok),
            ),
        )
        val presenter = ProbeProfilePresenter(fixture.deps, null, mill)
        advanceUntilIdle()
        assertFalse(presenter.uiState.value.canSync, "The sync needs a Connected session.")
        presenter.sync(AxisId.X)
        assertEquals("", presenter.uiState.value.form.xOffset)
        session.state.value = SessionState.Connected(0L)
        advanceUntilIdle()
        assertTrue(presenter.uiState.value.canSync)
        presenter.sync(AxisId.X)
        presenter.sync(AxisId.Y)
        presenter.sync(AxisId.Z)
        assertEquals("13.000", presenter.uiState.value.form.xOffset, "R + the absolute offset of the workspace.")
        assertEquals("-3.000", presenter.uiState.value.form.yOffset)
        assertEquals("0.500", presenter.uiState.value.form.zOffset)
        presenter.close()
        fixture.close()
    }

    @Test
    fun aNewCalibrationChangesTheDiameterFieldAndTheSpreadHint() = runTest {
        val fixture = AdapterFixture(this)
        fixture.machineWithAdapter(mill)
        fixture.probes.saveProbeProfile(
            ProbeProfile(id = "p", profileId = "mill", type = ProbeType.TouchProbe, name = "Probe", port = 'p', stylusDiameterUm = 2000.0, spreadLimitUm = null),
        )
        val presenter = ProbeProfilePresenter(fixture.deps, "p", mill)
        advanceUntilIdle()
        assertEquals("2.000", presenter.uiState.value.form.diameter)
        assertEquals(SpreadLimitSource.Default, presenter.uiState.value.spreadHint.source)
        assertEquals(20.0, presenter.uiState.value.spreadHint.valueUm)
        assertFalse(presenter.uiState.value.diameterCalibrated)
        presenter.showRecord()
        assertEquals(CalibrationRecord.NoCalibration, presenter.uiState.value.record)
        presenter.dismissRecord()
        val stored = fixture.probes.loadProbeProfile("p")!!
        fixture.probes.saveProbeProfile(
            stored.copy(
                stylusDiameterUm = 1987.4,
                effectiveDiameterUm = 1987.4,
                calibrationResidualUm = 5.0,
                calibratedOn = Instant.parse("2026-10-12T08:00:00Z"),
            ),
        )
        advanceUntilIdle()
        val state = presenter.uiState.value
        assertEquals("1.987", state.form.diameter)
        assertTrue(state.diameterCalibrated, "The green mark shows when the field is the calibrated diameter.")
        assertEquals(SpreadLimitSource.Calibration, state.spreadHint.source)
        assertEquals(15.0, state.spreadHint.valueUm, "max(10 µm, 3 × 5 µm).")
        presenter.close()
        fixture.close()
    }

    @Test
    fun aMissingProfileShowsNotFound() = runTest {
        val fixture = AdapterFixture(this)
        val presenter = ProbeProfilePresenter(fixture.deps, "missing", null)
        advanceUntilIdle()
        assertTrue(presenter.uiState.value.notFound)
        presenter.close()
        fixture.close()
    }
}
