package org.superdro.core.ui.graphics.view

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.functions.proximity.ProximityWarning
import org.superdro.core.model.AxisId
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.ProximityTarget
import org.superdro.core.model.SubDatum
import org.superdro.core.ui.graphics.GraphicsHarness

/**
 * The automatic proximity warning at a selected point: a target on the two axes of the plane.
 * The tone follows the axis that moves to its target, and the tone stops when the motion stops.
 */
class AutoProximityAtPointTest {

    private suspend fun TestScope.start(
        points: List<SubDatum> = listOf(SubDatum(id = "p1", x = 20_000.0, y = 0.0)),
        switches: List<GraphicalSwitch> = listOf(GraphicalSwitch.SnapToPoint, GraphicalSwitch.AutomaticProximityWarning, GraphicalSwitch.AutomaticZero),
    ): Pair<GraphicsHarness, GraphicalTabPresenter> {
        val harness = GraphicsHarness(this)
        harness.start(machine = MachineProfile.create(MachineType.VerticalMill, "Mill").copy(metricDefault = true))
        harness.store.machines.insertSubDatums(harness.profile.activeWorkspaceId, points)
        harness.moveTo(10L, x = 0L, y = 0L, z = 0L)
        val tab = GraphicalTabPresenter(harness.deviceId, harness.readout, harness.deps)
        advanceUntilIdle()
        switches.forEach { switch -> tab.setSwitch(switch, true) }
        advanceUntilIdle()
        return harness to tab
    }

    /** The approach to the point: the snap at 6350 µm, then the near zone (2540 µm) and the contact zone. */
    private fun GraphicsHarness.approach() {
        listOf(100L to 13_000L, 200L to 13_650L, 300L to 17_600L, 400L to 18_500L, 500L to 19_500L, 600L to 19_950L)
            .forEach { (time, x) -> moveTo(time, x = x) }
    }

    /** One motion step at [timeMs], then 100 ms of virtual time: less than the still time. */
    private fun TestScope.step(harness: GraphicsHarness, timeMs: Long, x: Long? = null, y: Long? = null) {
        x?.let { count -> harness.session.move('x', count, timeMs) }
        y?.let { count -> harness.session.move('y', count, timeMs) }
        runCurrent()
        advanceTimeBy(100L)
        runCurrent()
    }

    @Test
    fun theAutomaticProximityWarningSoundsAtASelectedPoint() = runTest {
        val (harness, tab) = start()
        try {
            harness.approach()
            // The point is selected and the incremental display shifts.
            assertEquals("p1", harness.workspace().activeSubDatumId)
            assertEquals(ProximityTarget(AxisId.X, 0.0, absolute = false, secondAxis = AxisId.Y, pointId = "p1"), harness.workspace().proximity)
            assertTrue(harness.sounds.proximityLevels.any { level -> level > 0.0 }, "no proximity tone: ${harness.sounds.proximityLevels}")
        } finally {
            tab.close()
            harness.close()
        }
    }

    @Test
    fun aManualTargetSoundsAtTheSamePoint() = runTest {
        val (harness, tab) = start(switches = listOf(GraphicalSwitch.SnapToPoint, GraphicalSwitch.AutomaticZero))
        try {
            // The control: the same motion with a target from the axis details.
            harness.updateWorkspace { workspace -> workspace.copy(proximity = ProximityTarget(AxisId.X, 0.0, absolute = false)) }
            harness.approach()
            assertEquals("p1", harness.workspace().activeSubDatumId)
            assertEquals(ProximityTarget(AxisId.X, 0.0, absolute = false), harness.workspace().proximity)
            assertTrue(harness.sounds.proximityLevels.any { level -> level > 0.0 }, "no proximity tone: ${harness.sounds.proximityLevels}")
        } finally {
            tab.close()
            harness.close()
        }
    }

    @Test
    fun theToneFollowsTheAxisThatMovesToItsTargetWhileTheOtherAxisIsFar() = runTest {
        // The point is 80 mm away in Y. Only X moves to the X line of the point.
        val (harness, tab) = start(
            points = listOf(SubDatum(id = "p2", x = 20_000.0, y = 80_000.0)),
            switches = listOf(GraphicalSwitch.AutomaticProximityWarning),
        )
        try {
            tab.onPointTap("p2")
            advanceUntilIdle()
            assertEquals(ProximityTarget(AxisId.X, 0.0, absolute = false, secondAxis = AxisId.Y, pointId = "p2"), harness.workspace().proximity)
            step(harness, 100L, x = 17_000L)
            step(harness, 200L, x = 18_000L)
            step(harness, 300L, x = 19_000L)
            step(harness, 400L, x = 19_500L)
            step(harness, 500L, x = 19_900L)
            // X: 3000 µm is outside the near zone. Then 2000, 1000, 500 µm, and the contact zone.
            assertEquals(listOf(0.25, 0.65, 0.85, 1.0), harness.sounds.proximityLevels)
        } finally {
            tab.close()
            harness.close()
        }
    }

    @Test
    fun theToneStopsWhenTheMotionStopsAndStartsAgainWithTheMotion() = runTest {
        val (harness, tab) = start(
            points = listOf(SubDatum(id = "p2", x = 20_000.0, y = 80_000.0)),
            switches = listOf(GraphicalSwitch.AutomaticProximityWarning),
        )
        try {
            tab.onPointTap("p2")
            advanceUntilIdle()
            step(harness, 100L, x = 18_000L)
            step(harness, 200L, x = 19_000L)
            assertEquals(listOf(0.25, 0.65), harness.sounds.proximityLevels)
            // The last motion was 100 ms ago. The tone continues until the still time ends.
            advanceTimeBy(ProximityWarning.STILL_MS - 101L)
            runCurrent()
            assertEquals(0.65, harness.sounds.proximityLevels.last())
            advanceTimeBy(1L)
            runCurrent()
            assertEquals(0.0, harness.sounds.proximityLevels.last())
            // The next motion to the target starts the tone again.
            step(harness, 2_000L, x = 19_500L)
            assertEquals(listOf(0.25, 0.65, 0.0, 0.85), harness.sounds.proximityLevels)
            advanceUntilIdle()
            assertEquals(0.0, harness.sounds.proximityLevels.last())
        } finally {
            tab.close()
            harness.close()
        }
    }

    @Test
    fun twoAxesThatMoveToTheirTargetsGiveTheLevelOfTheNearerAxis() = runTest {
        val (harness, tab) = start(
            points = listOf(SubDatum(id = "p3", x = 20_000.0, y = 20_000.0)),
            switches = listOf(GraphicalSwitch.AutomaticProximityWarning),
        )
        try {
            harness.moveTo(20L, x = 18_000L, y = 18_000L)
            tab.onPointTap("p3")
            advanceUntilIdle()
            // X goes to 500 µm (0.85). Y goes to 1500 µm (0.45).
            step(harness, 100L, x = 19_500L, y = 18_500L)
            assertEquals(0.85, harness.sounds.proximityLevels.last())
            // X moves away (no tone). Y moves to 1000 µm (0.65).
            step(harness, 200L, x = 19_000L, y = 19_000L)
            assertEquals(0.65, harness.sounds.proximityLevels.last())
        } finally {
            tab.close()
            harness.close()
        }
    }

    @Test
    fun thePointTargetClearsWhenThePointIsNotSelected() = runTest {
        val (harness, tab) = start(
            points = listOf(SubDatum(id = "p2", x = 20_000.0, y = 80_000.0)),
            switches = listOf(GraphicalSwitch.AutomaticProximityWarning),
        )
        try {
            // A second tap on the point clears the selection and the target.
            tab.onPointTap("p2")
            advanceUntilIdle()
            assertEquals("p2", harness.workspace().proximity?.pointId)
            tab.onPointTap("p2")
            advanceUntilIdle()
            assertNull(harness.workspace().activeSubDatumId)
            assertNull(harness.workspace().proximity)
            // A deactivation from a different screen also clears the target.
            tab.onPointTap("p2")
            advanceUntilIdle()
            assertEquals("p2", harness.workspace().proximity?.pointId)
            harness.updateWorkspace { workspace -> workspace.copy(activeSubDatumId = null) }
            assertNull(harness.workspace().proximity)
            step(harness, 100L, x = 19_000L)
            assertTrue(harness.sounds.proximityLevels.all { level -> level == 0.0 }, "${harness.sounds.proximityLevels}")
        } finally {
            tab.close()
            harness.close()
        }
    }

    @Test
    fun theWarningOffSetsNoTargetAtAPoint() = runTest {
        val (harness, tab) = start(
            points = listOf(SubDatum(id = "p2", x = 20_000.0, y = 80_000.0)),
            switches = emptyList(),
        )
        try {
            tab.onPointTap("p2")
            advanceUntilIdle()
            assertEquals("p2", harness.workspace().activeSubDatumId)
            assertNull(harness.workspace().proximity)
        } finally {
            tab.close()
            harness.close()
        }
    }
}
