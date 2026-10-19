package org.superdro.core.ui.adapter.encoder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.DeviceId
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.BacklashMode
import org.superdro.core.model.CalibrationStatus
import org.superdro.core.model.EncoderKind
import org.superdro.core.model.EncoderProfile
import org.superdro.core.model.EncoderType
import org.superdro.core.model.MapPoint
import org.superdro.core.resources.Res
import org.superdro.core.resources.encoder_calibrate_offline
import org.superdro.core.ui.adapter.AdapterFixture
import org.superdro.core.ui.text.UiText

/** The presenter of the encoder settings screen. */
@OptIn(ExperimentalCoroutinesApi::class)
class EncoderSettingsPresenterTest {

    private val device = DeviceId("mill")

    private suspend fun TestScope.withFixture(block: suspend TestScope.(AdapterFixture) -> Unit) {
        val fixture = AdapterFixture(this)
        try {
            block(fixture)
        } finally {
            fixture.close()
        }
    }

    private suspend fun AdapterFixture.storedEncoder(adapterId: String, input: Char): EncoderProfile? =
        store.adapters.loadAdapterProfile(adapterId)?.encoders?.get(input)

    @Test
    fun aTabWithNoAdapterProfileShowsNoForm() = runTest {
        withFixture { fixture ->
            fixture.machineWithoutAdapter(device)
            val presenter = EncoderSettingsPresenter(EncoderSource.Online(device), 'x', fixture.deps)
            advanceUntilIdle()
            val state = presenter.uiState.value
            assertTrue(state.loaded)
            assertFalse(state.available)
            presenter.close()
        }
    }

    @Test
    fun aChangeGoesToTheStoreAtOnce() = runTest {
        withFixture { fixture ->
            val adapter = fixture.machineWithAdapter(device)
            val presenter = EncoderSettingsPresenter(EncoderSource.Online(device), 'X', fixture.deps)
            advanceUntilIdle()
            assertTrue(presenter.uiState.value.available)
            assertEquals("25400", presenter.uiState.value.form.resolution)

            presenter.onResolution("5080")
            presenter.onReversed(true)
            presenter.onHysteresis("3")
            advanceUntilIdle()
            val stored = fixture.storedEncoder(adapter.id, 'x')!!
            assertEquals(5080.0, stored.resolution)
            assertTrue(stored.reversed)
            assertEquals(BacklashMode.Constant, stored.backlashMode)
            assertEquals(3L, stored.backlashCounts)
            assertEquals(CalibrationStatus.UserSet, stored.calibration.status)
            // The values that the presenter wrote do not change the text of the fields.
            assertEquals("5080", presenter.uiState.value.form.resolution)
            presenter.close()
        }
    }

    @Test
    fun aFieldWithAnErrorWritesNothing() = runTest {
        withFixture { fixture ->
            val adapter = fixture.machineWithAdapter(device)
            val presenter = EncoderSettingsPresenter(EncoderSource.Online(device), 'x', fixture.deps)
            advanceUntilIdle()
            presenter.onResolution("0")
            advanceUntilIdle()
            assertEquals(EncoderFieldError.ResolutionNotPositive, presenter.uiState.value.errors.resolution)
            assertEquals(25400.0, fixture.storedEncoder(adapter.id, 'x')!!.resolution)
            assertFalse(presenter.onCalibrate(), "A form with an error does not open the calibration.")
            presenter.close()
        }
    }

    @Test
    fun aChangeOfTheStoreFromADifferentScreenFillsTheFormAgain() = runTest {
        withFixture { fixture ->
            val adapter = fixture.machineWithAdapter(device)
            val presenter = EncoderSettingsPresenter(EncoderSource.Online(device), 'x', fixture.deps)
            advanceUntilIdle()
            val calibrated = EncoderProfile.defaultFor('x').copy(resolution = 5333.865, reversed = true)
            fixture.store.adapters.saveEncoderProfile(adapter.id, calibrated)
            advanceUntilIdle()
            assertEquals("5333.865", presenter.uiState.value.form.resolution)
            assertTrue(presenter.uiState.value.form.reversed)
            presenter.close()
        }
    }

    @Test
    fun aModeChangeThatRemovesTheBacklashMapAsksFirst() = runTest {
        withFixture { fixture ->
            val encoder = EncoderProfile.defaultFor('x').copy(
                type = EncoderType.LeadscrewEncoder,
                countsPerScrewRev = 2000.0,
                backlashMode = BacklashMode.Map,
                backlashMap = listOf(MapPoint(0, 20), MapPoint(10000, 25)),
            )
            val adapter = fixture.machineWithAdapter(device, AdapterProfile(alias = "Screw", encoders = mapOf('x' to encoder)))
            val presenter = EncoderSettingsPresenter(EncoderSource.Online(device), 'x', fixture.deps)
            advanceUntilIdle()
            assertEquals(1, presenter.uiState.value.backlashMap?.intervals)

            presenter.onBacklashMode(BacklashMode.None)
            assertTrue(presenter.uiState.value.confirmDeleteBacklashMap)
            presenter.onCancelDeleteBacklashMap()
            advanceUntilIdle()
            assertEquals(BacklashMode.Map, presenter.uiState.value.form.backlashMode)
            assertEquals(2, fixture.storedEncoder(adapter.id, 'x')!!.backlashMap.size)

            presenter.onBacklashMode(BacklashMode.None)
            presenter.onConfirmDeleteBacklashMap()
            advanceUntilIdle()
            val stored = fixture.storedEncoder(adapter.id, 'x')!!
            assertEquals(BacklashMode.None, stored.backlashMode)
            assertTrue(stored.backlashMap.isEmpty())
            presenter.close()
        }
    }

    @Test
    fun calibrateNeedsTheOnlineSource() = runTest {
        withFixture { fixture ->
            val adapter = fixture.machineWithAdapter(device)
            val online = EncoderSettingsPresenter(EncoderSource.Online(device), 'x', fixture.deps)
            val offline = EncoderSettingsPresenter(EncoderSource.Offline(adapter.id), 'x', fixture.deps)
            val tach = EncoderSettingsPresenter(EncoderSource.Online(device), 't', fixture.deps)
            advanceUntilIdle()
            assertTrue(online.onCalibrate())
            assertTrue(offline.uiState.value.available)
            assertFalse(offline.onCalibrate())
            assertEquals(UiText.of(Res.string.encoder_calibrate_offline), offline.uiState.value.message)
            offline.onMessageShown()
            assertNull(offline.uiState.value.message)
            assertFalse(tach.uiState.value.visibility.calibrate, "The input t has no calibration.")
            assertFalse(tach.onCalibrate())
            online.onKind(EncoderKind.Disabled)
            assertFalse(online.onCalibrate(), "A disabled input has no calibration.")
            listOf(online, offline, tach).forEach { it.close() }
        }
    }

    @Test
    fun theCyclicSwitchOfAnAngularInputGoesToTheStore() = runTest {
        withFixture { fixture ->
            val adapter = fixture.machineWithAdapter(device, AdapterProfile(alias = "Rotary", encoders = mapOf('w' to EncoderProfile.defaultFor('w', EncoderKind.Angular))))
            val presenter = EncoderSettingsPresenter(EncoderSource.Offline(adapter.id), 'w', fixture.deps)
            advanceUntilIdle()
            assertTrue(presenter.uiState.value.visibility.cyclic)
            assertTrue(presenter.uiState.value.form.cyclic)
            presenter.onCyclic(false)
            advanceUntilIdle()
            assertFalse(fixture.storedEncoder(adapter.id, 'w')!!.errorMapCyclic)
            presenter.close()
        }
    }

    @Test
    fun theProblemsOfTheStoredMapsShowAtEachLoad() = runTest {
        withFixture { fixture ->
            // The corrected position goes back between 0 and 100: 0 + 50 > 100 + (−60).
            val encoder = EncoderProfile.defaultFor('y').copy(errorMap = listOf(MapPoint(0, 50), MapPoint(100, -60)))
            val adapter = fixture.machineWithAdapter(device, AdapterProfile(alias = "Bad map", encoders = mapOf('y' to encoder)))
            val presenter = EncoderSettingsPresenter(EncoderSource.Offline(adapter.id), 'y', fixture.deps)
            advanceUntilIdle()
            val state = presenter.uiState.value
            assertEquals(1, state.problems.size)
            assertNotNull(state.errorMap)
            assertEquals(1, state.errorMap.intervals)
            assertEquals(0L, state.errorMap.first)
            assertEquals(100L, state.errorMap.last)
            presenter.close()
        }
    }

    @Test
    fun anInputWithNoProfileGetsTheDefaultProfile() = runTest {
        withFixture { fixture ->
            val adapter = fixture.machineWithAdapter(device, AdapterProfile(alias = "Empty", encoders = emptyMap()))
            val presenter = EncoderSettingsPresenter(EncoderSource.Online(device), 'z', fixture.deps)
            advanceUntilIdle()
            assertTrue(presenter.uiState.value.available)
            assertEquals("25400", presenter.uiState.value.form.resolution)
            assertNull(fixture.storedEncoder(adapter.id, 'z'), "The screen writes nothing before a change.")
            presenter.onFilter(true)
            advanceUntilIdle()
            assertTrue(fixture.storedEncoder(adapter.id, 'z')!!.filter)
            presenter.close()
        }
    }
}
