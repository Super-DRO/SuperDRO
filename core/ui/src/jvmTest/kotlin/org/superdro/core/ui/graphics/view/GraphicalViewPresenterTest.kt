package org.superdro.core.ui.graphics.view

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.functions.PointUm
import org.superdro.core.functions.compensation.ToolPosition
import org.superdro.core.functions.graphics.GraphicalViewport
import org.superdro.core.functions.graphics.ScreenMetrics
import org.superdro.core.functions.graphics.Vec2
import org.superdro.core.functions.graphics.ZoomPreset
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.GraphicalDisplay
import org.superdro.core.model.Guide
import org.superdro.core.model.SubDatum
import org.superdro.core.ui.graphics.GraphicsHarness
import org.superdro.core.ui.navigation.AddGuideRoute
import org.superdro.core.ui.navigation.RadiusCompensationRoute
import org.superdro.core.ui.navigation.WorkspaceTransformKind
import org.superdro.core.ui.navigation.WorkspaceTransformRoute

/** The presenter tests of the graphical view: the pan, the zoom, the hit test, and the gestures. */
class GraphicalViewPresenterTest {

    private val metrics = ScreenMetrics(xdpi = 160.0, ydpi = 160.0, density = 1.0)

    private class Fixture(val harness: GraphicsHarness, val tab: GraphicalTabPresenter, val view: GraphicalViewPresenter) {
        fun screenOf(point: PointUm): Vec2 = view.uiState.value.viewport!!.transform.toScreen(point)
        val port: GraphicalViewport get() = view.uiState.value.viewport!!
    }

    private suspend fun TestScope.fixture(
        points: List<SubDatum> = listOf(SubDatum(id = "p1", x = 10_000.0, y = 5_000.0), SubDatum(id = "p2", x = -15_000.0, y = 12_000.0)),
        guides: List<Guide> = listOf(Guide(id = "g1", axis = AxisId.X, valueUm = 30_000.0)),
        states: Map<AxisId, AxisState> = emptyMap(),
        toolDiameterUm: Double = 0.0,
    ): Fixture {
        val harness = GraphicsHarness(this)
        harness.start(states = states)
        val workspaceId = harness.profile.activeWorkspaceId
        harness.store.machines.insertSubDatums(workspaceId, points)
        guides.forEach { guide -> harness.store.machines.saveGuide(workspaceId, guide) }
        if (toolDiameterUm > 0.0) harness.updateWorkspace { workspace -> workspace.copy(toolXUm = toolDiameterUm) }
        harness.moveTo(10L, x = 0L, y = 0L, z = 0L)
        val tab = GraphicalTabPresenter(harness.deviceId, harness.readout, harness.deps)
        val view = GraphicalViewPresenter(tab)
        view.onSize(1000.0, 800.0, metrics)
        advanceUntilIdle()
        return Fixture(harness, tab, view)
    }

    private fun Fixture.close() {
        view.close()
        tab.close()
        harness.close()
    }

    @Test
    fun followModePutsTheToolAtTheCenterAndADragStopsIt() = runTest {
        val f = fixture()
        try {
            f.harness.moveTo(20L, x = 12_700L, y = -25_400L)
            val tool = f.screenOf(PointUm(12_700.0, -25_400.0))
            assertEquals(500.0, tool.x, 1e-9)
            assertEquals(400.0, tool.y, 1e-9)
            f.view.onDrag(30.0, -10.0)
            advanceUntilIdle()
            assertFalse(f.port.followMode)
            assertTrue(f.view.uiState.value.recenterVisible)
            assertEquals(530.0, f.screenOf(PointUm(12_700.0, -25_400.0)).x, 1e-9)
            f.view.onRecenter()
            advanceUntilIdle()
            assertTrue(f.port.followMode)
            assertEquals(500.0, f.screenOf(PointUm(12_700.0, -25_400.0)).x, 1e-9)
        } finally {
            f.close()
        }
    }

    @Test
    fun thePinchKeepsTheTouchPointAndTheScaleStaysInTheLimits() = runTest {
        val f = fixture()
        try {
            f.view.onDrag(0.0, 0.0)
            advanceUntilIdle()
            val focus = Vec2(700.0, 300.0)
            val model = f.port.transform.toModel(focus)
            f.view.onPinch(2.0, focus)
            advanceUntilIdle()
            assertEquals(2.0, f.port.scale, 1e-12)
            val after = f.port.transform.toScreen(model)
            assertEquals(700.0, after.x, 1e-6)
            assertEquals(300.0, after.y, 1e-6)
            f.view.onPinch(1_000.0, focus)
            advanceUntilIdle()
            assertEquals(10.0, f.port.scale)
            f.view.onPinch(1e-9, focus)
            advanceUntilIdle()
            assertEquals(0.01, f.port.scale)
            // A size change keeps the zoom.
            f.view.onSize(600.0, 900.0, metrics)
            advanceUntilIdle()
            assertEquals(0.01, f.port.scale)
        } finally {
            f.close()
        }
    }

    @Test
    fun theZoomPresetsGiveTheirScales() = runTest {
        val f = fixture()
        try {
            mapOf(ZoomPreset.FiveToOne to 0.2, ZoomPreset.OneToOne to 1.0, ZoomPreset.OneToFive to 5.0).forEach { (preset, scale) ->
                f.view.onZoomPreset(preset)
                advanceUntilIdle()
                assertEquals(scale, f.port.scale)
                assertEquals(preset.label, ZoomPreset.valueOf(preset.name).label)
            }
            assertEquals(listOf("5:1", "1:1", "1:5"), ZoomPreset.entries.map { preset -> preset.label })
        } finally {
            f.close()
        }
    }

    @Test
    fun theDoubleTapFitsTheOriginThePointsAndTheTool() = runTest {
        val f = fixture()
        try {
            f.harness.moveTo(20L, x = 50_000L, y = 40_000L)
            f.view.onDoubleTap()
            advanceUntilIdle()
            assertFalse(f.port.followMode)
            listOf(PointUm(0.0, 0.0), PointUm(50_000.0, 40_000.0), PointUm(-15_000.0, 12_000.0)).forEach { point ->
                val screen = f.screenOf(point)
                assertTrue(screen.x in 0.0..1000.0 && screen.y in 0.0..800.0, "$point is at $screen")
            }
            assertTrue(f.port.scale in 0.01..10.0)
        } finally {
            f.close()
        }
    }

    @Test
    fun aTapOnAPointActivatesItAndASecondTapClearsIt() = runTest {
        val f = fixture()
        try {
            f.tab.setSwitch(GraphicalSwitch.SnapToPoint, true)
            advanceUntilIdle()
            assertNull(f.view.onTap(f.screenOf(PointUm(10_000.0, 5_000.0))))
            advanceUntilIdle()
            assertEquals("p1", f.harness.workspace().activeSubDatumId)
            // A tap on a point turns snap to point off.
            assertFalse(f.harness.workspace().graphical.snapToPoint)
            f.view.onTap(f.screenOf(PointUm(10_000.0, 5_000.0)))
            advanceUntilIdle()
            assertNull(f.harness.workspace().activeSubDatumId)
        } finally {
            f.close()
        }
    }

    @Test
    fun aTapSelectsAGuideAndALongPressDeletesItWithUndo() = runTest {
        val f = fixture()
        try {
            val guideScreen = Vec2(f.screenOf(PointUm(30_000.0, 0.0)).x + 5.0, 100.0)
            f.view.onTap(guideScreen)
            advanceUntilIdle()
            assertEquals("g1", (f.view.uiState.value.scene!!.selection as org.superdro.core.functions.graphics.GraphSelection.Guide).id)
            f.view.onLongPress(guideScreen)
            runCurrent()
            val undo = assertNotNull(f.tab.undo.value)
            assertTrue(f.harness.workspace().guides.isEmpty())
            f.tab.onUndo(undo.id)
            advanceUntilIdle()
            assertEquals(listOf("g1"), f.harness.workspace().guides.map { guide -> guide.id })
        } finally {
            f.close()
        }
    }

    @Test
    fun aLongPressOnAnEmptyAreaOpensTheAddGuideDialogAtTheTouchPosition() = runTest {
        val f = fixture()
        try {
            val touch = f.screenOf(PointUm(-30_000.0, -30_000.0))
            assertEquals(AddGuideRoute, f.view.onLongPress(touch))
            val request = assertNotNull(f.tab.addGuide.value)
            assertEquals(-30_000.0, request.position.h, 1e-6)
            assertEquals(-30_000.0, request.position.v, 1e-6)
        } finally {
            f.close()
        }
    }

    @Test
    fun aTapOnTheToolOpensTheDirectionPickerThatSetsTheToolOffset() = runTest {
        val f = fixture(points = emptyList(), guides = emptyList(), toolDiameterUm = 10_000.0)
        try {
            f.view.onTap(Vec2(500.0, 400.0))
            advanceUntilIdle()
            val picker = assertIs<ViewOverlay.DirectionPicker>(f.view.uiState.value.overlay)
            assertEquals(ToolPosition.Center, picker.current)
            f.view.onDirection(ToolPosition.BackLeft)
            advanceUntilIdle()
            val workspace = f.harness.workspace()
            // D = 10 mm, Back-Left: X = +5000 µm, Y = −5000 µm.
            assertEquals(5_000.0, workspace.axisStates.getValue(AxisId.X).toolOffset)
            assertEquals(-5_000.0, workspace.axisStates.getValue(AxisId.Y).toolOffset)
            assertNull(f.view.uiState.value.overlay)
            // The picker shows the signs of the offsets.
            f.view.onTap(f.screenOf(PointUm(f.port.transform.toModel(Vec2(500.0, 400.0)).h - 5_000.0, f.port.transform.toModel(Vec2(500.0, 400.0)).v + 5_000.0)))
            advanceUntilIdle()
            assertEquals(ToolPosition.BackLeft, (f.view.uiState.value.overlay as ViewOverlay.DirectionPicker).current)
        } finally {
            f.close()
        }
    }

    @Test
    fun aLongPressOnTheToolClearsAnActiveCompensationOrOpensTheSheet() = runTest {
        val f = fixture(points = emptyList(), guides = emptyList())
        try {
            assertEquals(RadiusCompensationRoute, f.view.onLongPress(Vec2(500.0, 400.0)))
            f.harness.updateWorkspace { workspace ->
                workspace.copy(toolXUm = 6_000.0, axisStates = workspace.axisStates + (AxisId.X to AxisState(toolOffset = 3_000.0, toolOffsetActive = true)))
            }
            val tool = f.screenOf(f.view.uiState.value.scene!!.cutter!!.center)
            assertNull(f.view.onLongPress(tool))
            advanceUntilIdle()
            assertFalse(f.harness.workspace().axisStates.getValue(AxisId.X).toolOffsetActive)
            assertEquals(0.0, f.harness.workspace().toolXUm)
        } finally {
            f.close()
        }
    }

    @Test
    fun aLongPressOnAPointWithADifferentSelectedPointOpensThePairCommands() = runTest {
        val f = fixture()
        try {
            f.view.onTap(f.screenOf(PointUm(10_000.0, 5_000.0)))
            advanceUntilIdle()
            val route = f.view.onLongPress(f.screenOf(PointUm(-15_000.0, 12_000.0)))
            assertEquals(WorkspaceTransformRoute(WorkspaceTransformKind.Pair, "p1", "p2"), route)
            // A long press on the selected point opens the context menu.
            assertNull(f.view.onLongPress(f.screenOf(PointUm(10_000.0, 5_000.0))))
            runCurrent()
            val menu = assertIs<ViewOverlay.SubDatumMenu>(f.view.uiState.value.overlay)
            assertEquals("p1", menu.subDatumId)
        } finally {
            f.close()
        }
    }

    @Test
    fun theContextMenuEnablesEachGuideButtonOnlyWithItsOwnCoordinate() = runTest {
        val f = fixture(points = listOf(SubDatum(id = "px", x = 10_000.0, y = 5_000.0), SubDatum(id = "only-x", x = 7_000.0)))
        try {
            f.view.onLongPress(f.screenOf(PointUm(10_000.0, 5_000.0)))
            runCurrent()
            val menu = assertIs<ViewOverlay.SubDatumMenu>(f.view.uiState.value.overlay)
            assertEquals(5_000.0, menu.buttons.horizontalGuideValue)
            assertEquals(10_000.0, menu.buttons.verticalGuideValue)
            assertTrue(f.tab.addGuideAtPoint("px", org.superdro.core.functions.graphics.GuideOrientation.Horizontal))
            advanceUntilIdle()
            assertTrue(f.harness.workspace().guides.any { guide -> guide.axis == AxisId.Y && guide.valueUm == 5_000.0 })
            // A point with no Y: "Add Horizontal Guide Here" is disabled and adds nothing.
            assertFalse(f.tab.addGuideAtPoint("only-x", org.superdro.core.functions.graphics.GuideOrientation.Horizontal))
            assertTrue(f.tab.addGuideAtPoint("only-x", org.superdro.core.functions.graphics.GuideOrientation.Vertical))
            advanceUntilIdle()
            assertTrue(f.harness.workspace().guides.any { guide -> guide.axis == AxisId.X && guide.valueUm == 7_000.0 })
        } finally {
            f.close()
        }
    }

    @Test
    fun setAbsoluteOriginHereMovesTheOriginWithUndoAndDeleteHasUndo() = runTest {
        val f = fixture()
        try {
            f.harness.moveTo(20L, x = 12_000L, y = 7_000L)
            f.tab.setOriginAt("p1")
            advanceUntilIdle()
            val frame = f.harness.readout.frame.value!!
            assertEquals(2_000.0, frame.reading(AxisId.X).absolute, 1e-9)
            assertEquals(2_000.0, frame.reading(AxisId.Y).absolute, 1e-9)
            f.tab.deleteSubDatum("p2")
            runCurrent()
            val undo = assertNotNull(f.tab.undo.value)
            assertEquals(listOf("p1"), f.harness.workspace().subDatums.map { point -> point.id })
            f.tab.onUndo(undo.id)
            advanceUntilIdle()
            assertEquals(setOf("p1", "p2"), f.harness.workspace().subDatums.map { point -> point.id }.toSet())
        } finally {
            f.close()
        }
    }

    @Test
    fun theViewLockKeepsFollowModeAndADragDoesNotPan() = runTest {
        val f = fixture()
        try {
            f.harness.updateProfile { profile -> profile.copy(graphicalDisplay = GraphicalDisplay(viewLocked = true)) }
            assertTrue(f.view.uiState.value.locked)
            f.view.onDrag(100.0, 100.0)
            advanceUntilIdle()
            assertTrue(f.port.followMode)
            assertEquals(500.0, f.screenOf(PointUm(0.0, 0.0)).x, 1e-9)
        } finally {
            f.close()
        }
    }

    @Test
    fun theViewStateChangesOnlyWhenAValueChanges() = runTest {
        val f = fixture()
        try {
            val states = mutableListOf<GraphicalViewUiState>()
            val job = launch { f.view.uiState.collect { state -> states += state } }
            advanceUntilIdle()
            val start = states.size
            // The same counts again: no new value, no new state, no new draw.
            f.harness.moveTo(30L, x = 0L, y = 0L, z = 0L)
            f.harness.moveTo(40L, x = 0L, y = 0L, z = 0L)
            assertEquals(start, states.size)
            f.harness.moveTo(50L, x = 100L)
            assertEquals(start + 1, states.size)
            job.cancel()
        } finally {
            f.close()
        }
    }
}
