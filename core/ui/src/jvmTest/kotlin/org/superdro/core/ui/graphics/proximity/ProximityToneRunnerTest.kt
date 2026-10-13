package org.superdro.core.ui.graphics.proximity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.AxisId
import org.superdro.core.model.MachineMode
import org.superdro.core.model.ProximityTarget
import org.superdro.core.ui.graphics.GraphicsHarness

/** The tests of the proximity warning tone of the tab. */
class ProximityToneRunnerTest {

    @Test
    fun theAxisDetailsButtonStartsAndStopsTheWarningOfOneAxis() {
        val workspace = org.superdro.core.model.Workspace()
        val on = ProximityTargets.toggle(workspace, AxisId.X)
        assertEquals(ProximityTarget(AxisId.X, 0.0, absolute = false), on.proximity)
        assertTrue(ProximityTargets.isOn(on, AxisId.X))
        assertFalse(ProximityTargets.isOn(on, AxisId.Y))
        // A second axis takes the warning: one axis at a time.
        assertEquals(AxisId.Y, ProximityTargets.toggle(on, AxisId.Y).proximity?.axis)
        assertEquals(null, ProximityTargets.toggle(on, AxisId.X).proximity)
    }

    @Test
    fun theLevelFollowsTheDistanceToTheTargetAndStopsAfterTheSilenceTime() = runTest {
        val harness = GraphicsHarness(this)
        try {
            harness.start()
            harness.moveTo(10L, x = 5_000L, y = 0L, z = 0L)
            harness.updateWorkspace { workspace -> ProximityTargets.toggle(workspace, AxisId.X) }
            harness.moveTo(20L, x = 3_000L)
            // 3000 µm is outside the near zone of 2540 µm: no tone.
            assertTrue(harness.sounds.proximityLevels.isEmpty())
            harness.moveTo(30L, x = 1_000L)
            // D = 1000 µm and the axis moves closer: 0.65. Then 5000 ms
            // with no new value give the level 0.
            assertEquals(listOf(0.65, 0.0), harness.sounds.proximityLevels)
            harness.moveTo(40L, x = 100L)
            // The contact zone of 127 µm gives the continuous tone.
            assertEquals(listOf(0.65, 0.0, 1.0, 0.0), harness.sounds.proximityLevels)
        } finally {
            harness.close()
        }
    }

    @Test
    fun theMeasurementModeAndTheMuteSwitchStopTheTone() = runTest {
        val harness = GraphicsHarness(this)
        try {
            harness.start()
            harness.moveTo(10L, x = 5_000L, y = 0L, z = 0L)
            harness.updateWorkspace { workspace -> ProximityTargets.toggle(workspace, AxisId.X) }
            harness.store.appSettings.update { settings -> settings.copy(muteAllSounds = true) }
            harness.moveTo(20L, x = 1_000L)
            // A muted app gets the level 0 only.
            assertTrue(harness.sounds.proximityLevels.all { level -> level == 0.0 })
            harness.store.appSettings.update { settings -> settings.copy(muteAllSounds = false) }
            harness.updateProfile { profile -> profile.copy(machineMode = MachineMode.Measurement) }
            harness.moveTo(30L, x = 500L)
            harness.moveTo(40L, x = 100L)
            assertTrue(harness.sounds.proximityLevels.all { level -> level == 0.0 })
        } finally {
            harness.close()
        }
    }
}
