package org.superdro.core.ui.adapter.calibration

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.SessionState
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.AxisId
import org.superdro.core.model.Calibration
import org.superdro.core.model.CalibrationStatus
import org.superdro.core.model.Channels
import org.superdro.core.model.EncoderProfile
import org.superdro.core.model.InputConfig
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.ui.adapter.AdapterFixture
import org.superdro.core.ui.adapter.ScriptedSession
import org.superdro.core.ui.navigation.InputCalibrationRoute
import org.superdro.core.ui.text.UiText

/**
 * The start of the calibration from the setup reminder banner, and the wizard of a
 * machine whose adapter profile is missing.
 */
class CalibrationWizardStartTest {

    private val id = DeviceId("mill")
    private var fixture: AdapterFixture? = null

    @AfterTest
    fun closeFixture() {
        fixture?.close()
    }

    private val calibrated = EncoderProfile.defaultFor(Channels.X).copy(calibration = Calibration(status = CalibrationStatus.Calibrated))

    private fun adapter(vararg encoders: Pair<Char, EncoderProfile?>): AdapterProfile {
        val map = AdapterProfile().encoders.toMutableMap()
        encoders.forEach { (input, encoder) -> if (encoder == null) map.remove(input) else map[input] = encoder }
        return AdapterProfile(alias = "Test adapter", model = "tda-test", encoders = map)
    }

    private fun UiText?.keyName(): String? = when (this) {
        null -> null
        is UiText.Key -> key
        is UiText.Resource -> resource.key
        is UiText.Plain -> text
    }

    private suspend fun TestScope.start(axis: AxisId?, adapter: AdapterProfile, configure: (MachineProfile) -> MachineProfile = { it }): CalibrationWizardStartState? {
        val made = AdapterFixture(this).also { fixture = it }
        made.machineWithAdapter(id, adapter, MachineType.VerticalMill, configure)
        val presenter = CalibrationWizardStartPresenter(id, axis, made.deps)
        advanceUntilIdle()
        val state = presenter.uiState.value
        presenter.close()
        return state
    }

    @Test
    fun calibrateOnTheBannerOfAnAxisOpensTheWizardOfItsPrimaryInput() = runTest {
        val state = start(AxisId.Y, adapter(Channels.X to calibrated))
        assertEquals(CalibrationWizardStartState.Open(InputCalibrationRoute(Channels.Y)), state)
    }

    @Test
    fun aSecondaryInputThatNeedsTheCalibrationOpensItsWizard() = runTest {
        val state = start(AxisId.X, adapter(Channels.X to calibrated)) { profile ->
            profile.copy(x = profile.x.copy(secondary = InputConfig(Channels.W)))
        }
        assertEquals(CalibrationWizardStartState.Open(InputCalibrationRoute(Channels.W)), state)
    }

    @Test
    fun withNoAxisTheStartUsesTheFirstAxisOfTheReminder() = runTest {
        val yCalibrated = EncoderProfile.defaultFor(Channels.Y).copy(calibration = Calibration(status = CalibrationStatus.Calibrated))
        val state = start(null, adapter(Channels.X to calibrated, Channels.Y to yCalibrated))
        assertEquals(CalibrationWizardStartState.Open(InputCalibrationRoute(Channels.Z)), state)
    }

    @Test
    fun theTachometerHasNoCalibration() = runTest {
        val state = start(AxisId.Tachometer, adapter())
        assertEquals("calibration_tach_input", (state as CalibrationWizardStartState.Refused).message.keyName())
    }

    @Test
    fun aMissingAdapterProfileShowsAnErrorWithAWayBack() = runTest {
        val made = AdapterFixture(this).also { fixture = it }
        // The machine profile keeps the ID of an adapter profile that the store does not have
        // (adapter need 10): a delete with no foreign key check, as on a database with the
        // foreign keys off.
        val stored = made.machineWithAdapter(id, adapter())
        made.store.driver.execute(null, "PRAGMA foreign_keys = OFF", 0)
        made.store.driver.execute(null, "DELETE FROM adapter_profile WHERE id = '${stored.id}'", 0)
        made.connect(ScriptedSession(id, SessionState.Connected(0L)))
        val presenter = InputCalibrationPresenter(id, Channels.X, made.deps)
        advanceUntilIdle()
        val state = presenter.uiState.value
        assertEquals("integration_calibration_adapter_missing", state.blocked.keyName())
        assertEquals(CalibrationStep.Loading, state.step)
        presenter.close()
        assertNull(made.store.adapters.loadAdapterProfile(stored.id))
        assertEquals(stored.id, made.store.machines.loadProfile(id.value)!!.adapterProfileId)
    }
}
