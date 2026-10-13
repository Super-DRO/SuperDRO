package org.superdro.core.ui.graphics.view

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.AxisId
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.ProximityTarget
import org.superdro.core.model.SubDatum
import org.superdro.core.ui.graphics.GraphicsHarness

/**
 * Reproduces the report: the automatic functions select a drawing point and shift the
 * incremental display, but the automatic proximity warning gives no tone.
 */
class AutoProximityAtPointTest {

    private suspend fun TestScope.start(): Pair<GraphicsHarness, GraphicalTabPresenter> {
        val harness = GraphicsHarness(this)
        harness.start(machine = MachineProfile.create(MachineType.VerticalMill, "Mill").copy(metricDefault = true))
        harness.store.machines.insertSubDatums(harness.profile.activeWorkspaceId, listOf(SubDatum(id = "p1", x = 20_000.0, y = 0.0)))
        harness.moveTo(10L, x = 0L, y = 0L, z = 0L)
        val tab = GraphicalTabPresenter(harness.deviceId, harness.readout, harness.deps)
        advanceUntilIdle()
        listOf(GraphicalSwitch.SnapToPoint, GraphicalSwitch.AutomaticProximityWarning, GraphicalSwitch.AutomaticZero)
            .forEach { switch -> tab.setSwitch(switch, true) }
        advanceUntilIdle()
        return harness to tab
    }

    /** The approach to the point: the snap at 6350 µm, then the near zone (2540 µm) and the contact zone. */
    private fun GraphicsHarness.approach() {
        listOf(100L to 13_000L, 200L to 13_650L, 300L to 17_600L, 400L to 18_500L, 500L to 19_500L, 600L to 19_950L)
            .forEach { (time, x) -> moveTo(time, x = x) }
    }

    @Test
    fun theAutomaticProximityWarningSoundsAtASelectedPoint() = runTest {
        val (harness, tab) = start()
        try {
            harness.approach()
            // The point is selected and the incremental display shifts: this part works.
            assertEquals("p1", harness.workspace().activeSubDatumId)
            // The bug: no proximity target and no tone.
            assertNotNull(harness.workspace().proximity, "the automatic proximity warning set no target at the point")
            assertTrue(harness.sounds.proximityLevels.any { level -> level > 0.0 }, "no proximity tone: ${harness.sounds.proximityLevels}")
        } finally {
            tab.close()
            harness.close()
        }
    }

    @Test
    fun aManualTargetSoundsAtTheSamePoint() = runTest {
        val (harness, tab) = start()
        try {
            // The control: the same motion with a target from the axis details.
            harness.updateWorkspace { workspace -> workspace.copy(proximity = ProximityTarget(AxisId.X, 0.0, absolute = false)) }
            harness.approach()
            assertEquals("p1", harness.workspace().activeSubDatumId)
            assertTrue(harness.sounds.proximityLevels.any { level -> level > 0.0 }, "no proximity tone: ${harness.sounds.proximityLevels}")
        } finally {
            tab.close()
            harness.close()
        }
    }
}
