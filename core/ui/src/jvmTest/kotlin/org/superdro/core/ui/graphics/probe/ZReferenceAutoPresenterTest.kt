package org.superdro.core.ui.graphics.probe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.SessionState
import org.superdro.core.model.AxisId
import org.superdro.core.model.ProbeConfig
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.ProbeType
import org.superdro.core.ui.graphics.GraphicsHarness
import org.superdro.core.ui.sound.UiSound

/** The presenter tests of the automatic Z reference. */
class ZReferenceAutoPresenterTest {

    private val setterProbe = ProbeConfig(enabled = true, toolSetterEnabled = true)

    private suspend fun TestScope.started(
        harness: GraphicsHarness,
        setter: ProbeProfile? = ProbeProfile(name = "Setter", type = ProbeType.ToolHeightSetter, port = 'p', zOffsetUm = 25_000.0),
        zEnabled: Boolean = true,
        state: SessionState = SessionState.Connected(0L),
        probe: ProbeConfig = setterProbe,
    ): ZReferenceAutoPresenter {
        val machine = probeMachine(probe = probe).let { base -> base.copy(z = base.z.copy(enabled = zEnabled)) }
        harness.start(machine, state = state)
        val stored = setter?.let { profile -> harness.storeProbe(profile) }
        harness.updateProfile { profile -> profile.copy(probeRoles = profile.probeRoles.copy(zReferenceProfileId = stored?.id)) }
        harness.moveTo(0L, x = 0L, y = 0L, z = 0L)
        val presenter = ZReferenceAutoPresenter(harness.deviceId, harness.readout, harness.deps)
        advanceUntilIdle()
        return presenter
    }

    @Test
    fun aProbeClosureSetsZToTheSetterHeightPlusBAtTheTriggerTime() = runTest {
        val harness = GraphicsHarness(this)
        try {
            val presenter = started(harness)
            presenter.setManualValues(setterHeightUm = null, desiredZUm = 5_000.0)
            val state = presenter.uiState.value
            assertTrue(state.visible)
            assertTrue(state.enabled)
            assertEquals("25.000", state.setterHeightText)
            presenter.toggle()
            advanceUntilIdle()
            assertTrue(presenter.uiState.value.armed)
            harness.moveTo(1_000L, z = -10_000L)
            harness.session.probe(active = true, timeMs = 1_000L)
            // The tool moves on after the contact: the latch keeps the position at the trigger.
            harness.moveTo(1_100L, z = -10_100L)
            harness.moveTo(1_100L, x = 0L, y = 0L)
            harness.session.tick(1_600L)
            advanceUntilIdle()
            val z = harness.workspace().axisStates.getValue(AxisId.Z)
            // Z reads A + B = 30 mm at the contact: A = 30000 − (−10000).
            assertEquals(40_000.0, z.absoluteOffset, 1e-9)
            assertFalse(z.relative)
            assertEquals(29_900.0, harness.readout.frame.value!!.reading(AxisId.Z).absolute, 1e-9)
            assertEquals(listOf(UiSound.Short), harness.sounds.played)
            assertFalse(presenter.uiState.value.armed)
            assertEquals(1, presenter.uiState.value.closeRequests)
            assertNull(presenter.uiState.value.message)
            presenter.close()
        } finally {
            harness.close()
        }
    }

    @Test
    fun aTypedSetterHeightGoesIntoTheMachineProfile() = runTest {
        val harness = GraphicsHarness(this)
        try {
            val presenter = started(harness, setter = null)
            presenter.setManualValues(setterHeightUm = 50_800.0, desiredZUm = null, setterMetric = false)
            presenter.toggle()
            harness.moveTo(1_000L, z = 0L)
            harness.touch(this, 1_000L)
            val profile = harness.fullProfile()
            assertEquals(50_800.0, profile.zSetterHeightUm)
            assertEquals(false, profile.zSetterMetric)
            assertEquals(50_800.0, harness.readout.frame.value!!.reading(AxisId.Z).absolute, 1e-9)
            presenter.close()
        } finally {
            harness.close()
        }
    }

    @Test
    fun aSetterHeightThatIsNotMoreThanZeroIsRefused() = runTest {
        listOf(0.0, -5_000.0).forEach { height ->
            val harness = GraphicsHarness(this)
            try {
                val presenter = started(harness, setter = ProbeProfile(type = ProbeType.ToolHeightSetter, port = 'p', zOffsetUm = height))
                presenter.toggle()
                harness.moveTo(1_000L, z = 0L)
                harness.touch(this, 1_000L)
                assertEquals("Tool setter height must be a positive number", presenter.uiState.value.message.english())
                assertEquals(listOf(UiSound.Error), harness.sounds.played)
                assertTrue(presenter.uiState.value.armed)
                assertEquals(0.0, harness.workspace().axisStates.getValue(AxisId.Z).absoluteOffset)
                presenter.close()
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun autoNeedsZAndAConnection() = runTest {
        val harness = GraphicsHarness(this)
        try {
            val presenter = started(harness, zEnabled = false)
            assertFalse(presenter.uiState.value.enabled)
            assertEquals("Z readout must be enabled", presenter.uiState.value.message.english())
            presenter.toggle()
            advanceUntilIdle()
            assertFalse(presenter.uiState.value.armed)
            assertEquals(listOf(UiSound.Error), harness.sounds.played)
            presenter.close()
        } finally {
            harness.close()
        }
        val second = GraphicsHarness(this)
        try {
            val presenter = started(second, state = SessionState.AwaitingData)
            assertFalse(presenter.uiState.value.enabled)
            assertEquals("The adapter must be connected", presenter.uiState.value.message.english())
            presenter.close()
        } finally {
            second.close()
        }
    }

    @Test
    fun noZPositionAtTheTriggerIsRefused() = runTest {
        val harness = GraphicsHarness(this)
        try {
            harness.start(probeMachine(probe = setterProbe))
            val stored = harness.storeProbe(ProbeProfile(type = ProbeType.ToolHeightSetter, port = 'p', zOffsetUm = 25_000.0))
            harness.updateProfile { profile -> profile.copy(probeRoles = profile.probeRoles.copy(zReferenceProfileId = stored.id)) }
            // Z has no sample, so the latch has no Z position.
            harness.moveTo(0L, x = 0L, y = 0L)
            val presenter = ZReferenceAutoPresenter(harness.deviceId, harness.readout, harness.deps)
            advanceUntilIdle()
            presenter.toggle()
            harness.touch(this, 1_000L)
            assertEquals("Could not read the Z position at the moment of contact. Try again.", presenter.uiState.value.message.english())
            assertEquals(listOf(UiSound.Error), harness.sounds.played)
            presenter.close()
        } finally {
            harness.close()
        }
    }

    @Test
    fun theAutoButtonShowsOnlyWithTheToolSetterSwitch() = runTest {
        val harness = GraphicsHarness(this)
        try {
            val presenter = started(harness, probe = ProbeConfig(enabled = true, toolSetterEnabled = false))
            assertFalse(presenter.uiState.value.visible)
            presenter.close()
        } finally {
            harness.close()
        }
    }

    @Test
    fun aDisconnectionDisarmsTheProbe() = runTest {
        val harness = GraphicsHarness(this)
        try {
            val presenter = started(harness)
            presenter.toggle()
            advanceUntilIdle()
            assertTrue(presenter.uiState.value.armed)
            harness.session.state.value = SessionState.Reconnecting(1, 0L, org.superdro.core.connection.LinkFailure.Lost)
            advanceUntilIdle()
            assertFalse(presenter.uiState.value.armed)
            presenter.close()
        } finally {
            harness.close()
        }
    }

    @Test
    fun theSelectorStoresTheZReferenceProfile() = runTest {
        val harness = GraphicsHarness(this)
        try {
            val presenter = started(harness, setter = null)
            val touch = harness.storeProbe(ProbeProfile(name = "Touch", type = ProbeType.TouchProbe, port = 'p', zOffsetUm = 12_000.0))
            harness.storeProbe(ProbeProfile(name = "Edge", type = ProbeType.EdgeFinder2D, port = 'p'))
            advanceUntilIdle()
            assertEquals(listOf("Touch"), presenter.uiState.value.selector.options.map { item -> item.name })
            presenter.onSelectProbe(touch.id)
            advanceUntilIdle()
            assertEquals(touch.id, harness.fullProfile().probeRoles.zReferenceProfileId)
            assertEquals("12.000", presenter.uiState.value.setterHeightText)
            presenter.close()
        } finally {
            harness.close()
        }
    }

    @Test
    fun aNewProfileFromNewProfileBecomesTheZReferenceProfile() = runTest {
        val harness = GraphicsHarness(this)
        try {
            val presenter = started(harness, setter = null)
            advanceUntilIdle()
            presenter.onNewProbe()
            // An edge finder is not a Z reference profile, so it does not become the selection.
            harness.storeProbe(ProbeProfile(name = "Edge", type = ProbeType.EdgeFinder2D, port = 'p'))
            advanceUntilIdle()
            assertNull(harness.fullProfile().probeRoles.zReferenceProfileId)
            val setter = harness.storeProbe(ProbeProfile(name = "Setter 30", type = ProbeType.ToolHeightSetter, port = 'p', zOffsetUm = 30_000.0))
            advanceUntilIdle()
            assertEquals(setter.id, harness.fullProfile().probeRoles.zReferenceProfileId)
            assertEquals("30.000", presenter.uiState.value.setterHeightText)
            presenter.close()
        } finally {
            harness.close()
        }
    }
}
