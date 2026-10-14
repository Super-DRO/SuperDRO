package org.superdro.core.ui.graphics.view

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.superdro.core.functions.graphics.GraphSelection
import org.superdro.core.functions.graphics.GuideOrientation
import org.superdro.core.functions.thread.ThreadCutStatus
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.GraphicalDisplay
import org.superdro.core.model.Guide
import org.superdro.core.model.MachineMode
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.Plane
import org.superdro.core.model.ProximityTarget
import org.superdro.core.model.SubDatum
import org.superdro.core.model.SubDatumGroupKind
import org.superdro.core.model.ThreadPass
import org.superdro.core.ui.graphics.GraphicsHarness
import org.superdro.core.ui.graphics.thread.ThreadPanelUiState

/** The tests of the automatic functions, the guides, the planes, and the thread cut panel of the graphical view. */
class GraphicalTabPresenterTest {

    private class Fixture(val harness: GraphicsHarness, val tab: GraphicalTabPresenter) {
        fun close() {
            tab.close()
            harness.close()
        }
    }

    private suspend fun TestScope.fixture(
        machine: MachineProfile = MachineProfile.create(MachineType.VerticalMill, "Mill").copy(metricDefault = true),
        points: List<SubDatum> = emptyList(),
        guides: List<Guide> = emptyList(),
        states: Map<AxisId, AxisState> = emptyMap(),
    ): Fixture {
        val harness = GraphicsHarness(this)
        harness.start(machine = machine, states = states)
        val workspaceId = harness.profile.activeWorkspaceId
        if (points.isNotEmpty()) harness.store.machines.insertSubDatums(workspaceId, points)
        guides.forEach { guide -> harness.store.machines.saveGuide(workspaceId, guide) }
        harness.moveTo(10L, x = 0L, y = 0L, z = 0L)
        val tab = GraphicalTabPresenter(harness.deviceId, harness.readout, harness.deps)
        advanceUntilIdle()
        return Fixture(harness, tab)
    }

    @Test
    fun snapToPointSelectsTheNearestPointWithinTheRadius() = runTest {
        val f = fixture(points = listOf(SubDatum(id = "near", x = 20_000.0, y = 0.0), SubDatum(id = "far", x = 40_000.0, y = 0.0)))
        try {
            f.tab.setSwitch(GraphicalSwitch.SnapToPoint, true)
            advanceUntilIdle()
            f.harness.moveTo(20L, x = 13_000L)
            assertNull(f.harness.workspace().activeSubDatumId)
            // 6350 µm (0.25 in) is the radius.
            f.harness.moveTo(30L, x = 13_650L)
            assertEquals("near", f.harness.workspace().activeSubDatumId)
            f.harness.moveTo(40L, x = 36_000L)
            assertEquals("far", f.harness.workspace().activeSubDatumId)
        } finally {
            f.close()
        }
    }

    @Test
    fun snapToPointDoesNotActInTheMeasurementMode() = runTest {
        val f = fixture(points = listOf(SubDatum(id = "near", x = 2_000.0, y = 0.0)))
        try {
            f.harness.updateProfile { profile -> profile.copy(machineMode = MachineMode.Measurement) }
            f.tab.setSwitch(GraphicalSwitch.SnapToPoint, true)
            advanceUntilIdle()
            f.harness.moveTo(20L, x = 1_000L)
            assertNull(f.harness.workspace().activeSubDatumId)
        } finally {
            f.close()
        }
    }

    @Test
    fun snapToGuideSelectsTheGuideAheadAndSetsTheToolOffsetAndTheProximityTarget() = runTest {
        val f = fixture(guides = listOf(Guide(id = "left", axis = AxisId.X, valueUm = -10_000.0), Guide(id = "right", axis = AxisId.X, valueUm = 10_000.0)))
        try {
            f.harness.updateWorkspace { workspace -> workspace.copy(toolXUm = 6_000.0) }
            GraphicalSwitch.entries.filter { switch ->
                switch == GraphicalSwitch.SnapToGuide || switch == GraphicalSwitch.AutomaticToolOffset || switch == GraphicalSwitch.AutomaticProximityWarning
            }.forEach { switch -> f.tab.setSwitch(switch, true) }
            advanceUntilIdle()
            // A motion in +X with more than 2 slope samples in the last 1000 ms, past the middle.
            // The velocity uses the clock of the presenter: 100 ms between the samples.
            listOf(100L to 500L, 200L to 1_000L, 300L to 1_500L, 400L to 2_000L).forEach { (time, x) ->
                testScheduler.advanceTimeBy(100L)
                f.harness.moveTo(time, x = x)
            }
            assertEquals(GraphSelection.Guide("right"), f.tab.scene.value!!.selection)
            val workspace = f.harness.workspace()
            // The position is less than the guide: sign +1, offset = +D / 2.
            assertEquals(3_000.0, workspace.axisStates.getValue(AxisId.X).toolOffset)
            assertTrue(workspace.axisStates.getValue(AxisId.X).toolOffsetActive)
            assertEquals(ProximityTarget(AxisId.X, 10_000.0, absolute = true), workspace.proximity)
        } finally {
            f.close()
        }
    }

    @Test
    fun theGuideFunctionsDoNotActWhenTheGuidesAreHidden() = runTest {
        val f = fixture(guides = listOf(Guide(id = "right", axis = AxisId.X, valueUm = 10_000.0)))
        try {
            f.tab.updateDisplay { display -> display.copy(guidesShown = false) }
            f.tab.setSwitch(GraphicalSwitch.SnapToGuide, true)
            advanceUntilIdle()
            assertFalse(f.harness.fullProfile().graphicalDisplay.guidesShown)
            listOf(100L to 500L, 200L to 1_000L, 300L to 1_500L, 400L to 2_000L).forEach { (time, x) ->
                testScheduler.advanceTimeBy(100L)
                f.harness.moveTo(time, x = x)
            }
            assertEquals(GraphSelection.None, f.tab.scene.value!!.selection)
        } finally {
            f.close()
        }
    }

    @Test
    fun aPlungeGivesTheAutomaticZeroAndTheAutomaticSubDatum() = runTest {
        val f = fixture()
        try {
            f.harness.moveTo(20L, x = 5_000L, y = 3_000L, z = 0L)
            f.tab.setSwitch(GraphicalSwitch.AutomaticZero, true)
            f.tab.setSwitch(GraphicalSwitch.AutomaticSubDatum, true)
            advanceUntilIdle()
            // Down by more than 10 mm, then up by more than 10 mm, with X and Y within 250 µm.
            f.harness.moveTo(100L, z = -6_000L)
            f.harness.moveTo(200L, z = -12_000L, x = 5_100L)
            f.harness.moveTo(300L, z = -1_000L)
            val workspace = f.harness.workspace()
            val point = workspace.subDatums.single()
            assertEquals("Point 1", point.name)
            assertEquals(5_100.0, point.x)
            assertEquals(3_000.0, point.y)
            assertEquals(-12_000.0, point.z)
            val frame = f.harness.readout.frame.value!!
            assertTrue(frame.reading(AxisId.X).incremental)
            assertEquals(0.0, frame.reading(AxisId.Y).value, 1e-9)
            // A second plunge at the same place adds no point (250 µm).
            f.harness.moveTo(400L, z = -11_900L)
            f.harness.moveTo(500L, z = 0L)
            assertEquals(1, f.harness.workspace().subDatums.size)
        } finally {
            f.close()
        }
    }

    @Test
    fun aShortDipBetweenTwoThrottledFramesIsTheBottomOfThePlunge() = runTest {
        val f = fixture()
        try {
            f.tab.setSwitch(GraphicalSwitch.AutomaticSubDatum, true)
            advanceUntilIdle()
            f.harness.moveTo(100L, z = -6_000L)
            // The input stage gets the bottom of the plunge, but the throttle of the session does
            // not publish it: no frame shows −12000 µm.
            f.harness.session.stage.onAxisCount('z', -12_000L, 108L)
            f.harness.moveTo(116L, z = -1_000L)
            assertEquals(-1_000.0, f.harness.readout.frame.value!!.reading(AxisId.Z).absolute)
            val point = f.harness.workspace().subDatums.single()
            assertEquals(-12_000.0, point.z)
            // A dip after the down edge is the bottom too, not the lowest shown Z.
            f.harness.moveTo(200L, x = 1_000L)
            f.harness.moveTo(300L, z = -11_000L)
            f.harness.session.stage.onAxisCount('z', -11_480L, 308L)
            f.harness.moveTo(316L, z = -11_200L)
            f.harness.moveTo(400L, z = 0L)
            assertEquals(listOf(-12_000.0, -11_480.0), f.harness.workspace().subDatums.map { it.z })
        } finally {
            f.close()
        }
    }

    @Test
    fun aPlungeWithAMotionOfXOrYGivesNoAction() = runTest {
        val f = fixture()
        try {
            f.tab.setSwitch(GraphicalSwitch.AutomaticSubDatum, true)
            advanceUntilIdle()
            f.harness.moveTo(100L, z = -11_000L)
            f.harness.moveTo(200L, x = 400L)
            f.harness.moveTo(300L, z = 0L)
            assertTrue(f.harness.workspace().subDatums.isEmpty())
        } finally {
            f.close()
        }
    }

    @Test
    fun theAppDoesNotAddADuplicateGuide() = runTest {
        val f = fixture()
        try {
            assertTrue(f.tab.addGuide(GuideOrientation.Vertical, 12_000.0))
            advanceUntilIdle()
            assertFalse(f.tab.addGuide(GuideOrientation.Vertical, 12_000.2))
            assertTrue(f.tab.addGuide(GuideOrientation.Horizontal, 12_000.0))
            advanceUntilIdle()
            assertEquals(listOf(AxisId.X to 12_000.0, AxisId.Y to 12_000.0), f.harness.workspace().guides.map { guide -> guide.axis to guide.valueUm }.sortedBy { it.first })
        } finally {
            f.close()
        }
    }

    @Test
    fun thePlaneSelectorStoresTheMillPlaneAndALatheKeepsZx() = runTest {
        val f = fixture()
        try {
            f.tab.setPlane(Plane.YZ)
            advanceUntilIdle()
            assertEquals(Plane.YZ, f.harness.workspace().plane)
            assertEquals(listOf(Plane.XY, Plane.XZ, Plane.YZ), f.tab.controls.value.planes)
            f.tab.setPlane(Plane.ZX)
            advanceUntilIdle()
            assertEquals(Plane.YZ, f.harness.workspace().plane)
        } finally {
            f.close()
        }
        val lathe = fixture(machine = MachineProfile.create(MachineType.Lathe, "Lathe"))
        try {
            assertEquals(listOf(Plane.ZX), lathe.tab.controls.value.planes)
            lathe.tab.setPlane(Plane.XY)
            advanceUntilIdle()
            assertEquals(Plane.ZX, lathe.harness.workspace().plane)
            // A lathe has no plunge switches and no tool offset switch.
            assertEquals(listOf(GraphicalSwitch.SnapToPoint, GraphicalSwitch.SnapToGuide, GraphicalSwitch.AutomaticProximityWarning), lathe.tab.controls.value.switches.map { it.first })
        } finally {
            lathe.close()
        }
    }

    @Test
    fun theDisplayPaneStateIsStoredInTheMachineProfile() = runTest {
        val f = fixture()
        try {
            f.tab.updateDisplay { display -> display.copy(toolShown = false, crosshairsDimmed = true, viewLocked = true) }
            advanceUntilIdle()
            assertEquals(GraphicalDisplay(toolShown = false, crosshairsDimmed = true, viewLocked = true), f.harness.fullProfile().graphicalDisplay)
            assertTrue(f.tab.controls.value.display.viewLocked)
        } finally {
            f.close()
        }
    }

    @Test
    fun theThreadArmAsksToCreateAThreadWhenTheWorkspaceHasNone() = runTest {
        val f = fixture(machine = MachineProfile.create(MachineType.Lathe, "Lathe"))
        try {
            f.tab.threadCut.onArmButton()
            advanceUntilIdle()
            assertTrue(f.tab.threadCut.createQuestion.value)
            assertNull(f.tab.threadCut.panel.value)
        } finally {
            f.close()
        }
    }

    @Test
    fun theArmedThreadGivesTheLiveValuesOfTheWorkedExample() = runTest {
        val f = fixture(
            machine = MachineProfile.create(MachineType.Lathe, "Lathe").copy(metricDefault = true),
            states = mapOf(AxisId.X to AxisState(diameterMode = false)),
        )
        try {
            val workspaceId = f.harness.profile.activeWorkspaceId
            val spec = ViewSamples.m10Thread(workspaceId)
            f.harness.store.machines.saveThread(workspaceId, spec)
            f.harness.updateWorkspace { workspace -> workspace.copy(threadPass = ThreadPass(deepestUm = 9_600.0, threadId = spec.id)) }
            f.harness.moveTo(20L, x = 4_700L, z = -5_000L)
            f.tab.threadCut.onArmButton()
            advanceUntilIdle()
            val workspace = f.harness.workspace()
            assertEquals(spec.id, workspace.armedThreadId)
            assertEquals(4, workspace.subDatums.count { point -> point.groupKind == SubDatumGroupKind.Thread && point.groupRef?.startsWith(spec.id) == true }.coerceAtMost(4))
            val active = workspace.subDatums.single { point -> point.id == workspace.activeSubDatumId }
            assertEquals(4_064.0, active.x)
            // Last pass 0.736, infeed 0.100, cut load 0.7×, "In the cut".
            val panel = assertIs<ThreadPanelUiState.Armed>(f.tab.threadCut.panel.value)
            assertEquals("0.736", panel.lastPass)
            assertEquals("0.100", panel.infeed)
            assertEquals("0.7", panel.cutLoad)
            assertEquals(ThreadCutStatus.InTheCut, panel.status)
            // A pass over 25 % to 75 % of the length at a larger depth gives a new record.
            f.harness.moveTo(30L, x = 4_600L, z = -2_000L)
            f.harness.moveTo(40L, z = -12_000L)
            assertEquals(ThreadPass(9_200.0, 9_600.0, spec.id), f.harness.workspace().threadPass)
            f.tab.threadCut.disarm()
            advanceUntilIdle()
            assertNull(f.harness.workspace().armedThreadId)
            assertNull(f.tab.threadCut.panel.value)
        } finally {
            f.close()
        }
    }

    @Test
    fun aTapOnAGuideClearsTheSelectedGuideOnTheSecondTap() = runTest {
        val f = fixture(guides = listOf(Guide(id = "g", axis = AxisId.Y, valueUm = 4_000.0)))
        try {
            f.tab.onGuideTap("g")
            advanceUntilIdle()
            assertEquals(GraphSelection.Guide("g"), f.tab.scene.value!!.selection)
            f.tab.onGuideTap("g")
            advanceUntilIdle()
            assertEquals(GraphSelection.None, f.tab.scene.value!!.selection)
            assertNotNull(f.tab.controls.value)
        } finally {
            f.close()
        }
    }
}
