package org.superdro.core.ui.graphics.view

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.functions.PointUm
import org.superdro.core.functions.graphics.GraphSelection
import org.superdro.core.functions.graphics.GuideOrientation
import org.superdro.core.functions.thread.ThreadPoints
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.DrawingShape
import org.superdro.core.model.GraphicalDisplay
import org.superdro.core.model.Guide
import org.superdro.core.model.PartRotation
import org.superdro.core.model.Plane
import org.superdro.core.model.ShapeType
import org.superdro.core.model.SubDatum
import org.superdro.core.model.Tool
import org.superdro.core.model.ToolGeometry
import org.superdro.core.model.ToolKind

/** The tests of the scene of the graphical view: the planes, the signs, and the layers. */
class GraphicalSceneTest {

    private fun near(expected: Double, actual: Double) = assertEquals(expected, actual, 1e-6)

    @Test
    fun aMillShowsThePlaneXyWithTheAbsolutePositionsAndTheToolOffset() {
        val workspace = ViewSamples.millWorkspace().copy(
            axisStates = mapOf(AxisId.X to AxisState(absoluteOffset = 1_000.0, toolOffset = 5_000.0, toolOffsetActive = true)),
        )
        val scene = GraphicalSceneBuilder.build(ViewSamples.frame(ViewSamples.mill, workspace, x = 2_000.0, y = 3_000.0))
        assertEquals(Plane.XY, scene.plane)
        val tool = assertNotNull(scene.tool)
        // abs = R + T + A = 2000 + 5000 + 1000.
        near(8_000.0, tool.h)
        near(3_000.0, tool.v)
        // The cutter: center = tool − tool offset vector, radius = D / 2.
        val cutter = assertNotNull(scene.cutter)
        near(3_000.0, cutter.center.h)
        near(3_000.0, cutter.center.v)
        near(5_000.0, cutter.radiusUm)
        assertEquals(listOf("p1", "p2", "p3"), scene.points.map { point -> point.id })
        assertEquals(GraphSelection.Point("p1"), scene.selection)
    }

    @Test
    fun aLatheUsesThePlaneZxWithOneSignForTheToolThePointsAndTheGuides() {
        val workspace = ViewSamples.lathe.workspaces.first().copy(
            subDatums = listOf(SubDatum(id = "s", x = 2_000.0, z = 3_000.0)),
            guides = listOf(Guide(id = "h", axis = AxisId.X, valueUm = 4_000.0), Guide(id = "v", axis = AxisId.Z, valueUm = -6_000.0)),
        )
        val scene = GraphicalSceneBuilder.build(ViewSamples.frame(ViewSamples.lathe, workspace, x = 10_000.0, z = -5_000.0))
        assertEquals(Plane.ZX, scene.plane)
        val tool = assertNotNull(scene.tool)
        near(-5_000.0, tool.h)
        near(-10_000.0, tool.v)
        near(3_000.0, scene.points.single().position.h)
        near(-2_000.0, scene.points.single().position.v)
        val horizontal = scene.guides.single { guide -> guide.orientation == GuideOrientation.Horizontal }
        // The guide uses the same sign as the points.
        near(-4_000.0, horizontal.position)
        near(-6_000.0, scene.guides.single { guide -> guide.orientation == GuideOrientation.Vertical }.position)
        assertNull(scene.cutter)
    }

    @Test
    fun theMillPlanesXzAndYzUseTheirAxesAndALatheAlwaysUsesZx() {
        val workspace = ViewSamples.millWorkspace().copy(plane = Plane.XZ)
        val xz = GraphicalSceneBuilder.build(ViewSamples.frame(ViewSamples.mill, workspace, x = 1_000.0, y = 2_000.0, z = -3_000.0))
        near(1_000.0, xz.tool!!.h)
        near(-3_000.0, xz.tool!!.v)
        // Only the point with X and Z shows in XZ.
        assertEquals(listOf("p3"), xz.points.map { point -> point.id })
        assertEquals(0.0, xz.cutter!!.radiusUm)
        val yz = GraphicalSceneBuilder.build(ViewSamples.frame(ViewSamples.mill, workspace.copy(plane = Plane.YZ), x = 1_000.0, y = 2_000.0, z = -3_000.0))
        near(2_000.0, yz.tool!!.h)
        val lathe = GraphicalSceneBuilder.build(ViewSamples.frame(ViewSamples.lathe, ViewSamples.lathe.workspaces.first().copy(plane = Plane.XY)))
        assertEquals(Plane.ZX, lathe.plane)
    }

    @Test
    fun rotatePartTurnsThePointsAboutThePivotForTheDisplayOnly() {
        val workspace = ViewSamples.millWorkspace().copy(
            subDatums = listOf(SubDatum(id = "a", x = 10_000.0, y = 0.0)),
            partRotation = PartRotation(active = true, angleArcSec = 90.0 * 3600.0),
        )
        val scene = GraphicalSceneBuilder.build(ViewSamples.frame(ViewSamples.mill, workspace))
        // The stored point (10, 0) shows at (0, −10).
        near(0.0, scene.points.single().position.h)
        near(-10_000.0, scene.points.single().position.v)
        assertTrue(!scene.displayFrame.isIdentity)
    }

    @Test
    fun theDisplayStateGivesTheDrawingAlpha() {
        val dimmed = ViewSamples.mill.copy(graphicalDisplay = GraphicalDisplay(drawingDimmed = true))
        val scene = GraphicalSceneBuilder.build(ViewSamples.frame(dimmed), ViewSamples.drawing)
        near(127.0 / 255.0, scene.drawingAlpha)
        val hidden = ViewSamples.mill.copy(graphicalDisplay = GraphicalDisplay(drawingShown = false))
        near(0.0, GraphicalSceneBuilder.build(ViewSamples.frame(hidden), ViewSamples.drawing).drawingAlpha)
        near(1.0, GraphicalSceneBuilder.build(ViewSamples.frame(ViewSamples.mill), ViewSamples.drawing).drawingAlpha)
    }

    @Test
    fun anArmedThreadRestrictsThePointsAndShowsTheGhostLayersAndTheOutline() {
        val spec = ViewSamples.m10Thread()
        val corners = ThreadPoints.toSubDatums(spec)
        val other = SubDatum(id = "other", x = 1_000.0, z = 1_000.0)
        val workspace = ViewSamples.lathe.workspaces.first().copy(
            subDatums = corners + other,
            guides = listOf(Guide(id = "g", axis = AxisId.Z, valueUm = 2_000.0)),
            armedThreadId = spec.id,
        )
        val scene = GraphicalSceneBuilder.build(ViewSamples.frame(ViewSamples.lathe, workspace, x = 5_000.0, z = 0.0), ViewSamples.drawing, threads = listOf(spec))
        assertTrue(scene.restricted)
        assertEquals(4, scene.points.size)
        assertTrue(scene.ghostPoints.any { point -> point == PointUm(1_000.0, -1_000.0) })
        assertTrue(scene.guides.isEmpty())
        assertEquals(1, scene.ghostGuides.size)
        near(0.5, scene.drawingAlpha)
        assertTrue(!scene.drawingSelectable)
        val outline = assertNotNull(scene.threadOutline)
        assertEquals(2, outline.polylines.size)
        // The profile starts at Zmin on the crest (the major radius of an external thread) and its mirror is at −X.
        val front = outline.polylines[0]
        near(-15_000.0, front.first().h)
        near(-5_000.0, front.first().v)
        near(-15_000.0 + 750.0, front[1].h)
        near(-4_064.0, front[1].v)
        near(5_000.0, outline.polylines[1].first().v)
    }

    @Test
    fun theActiveLatheToolWithAGeometryGivesTheToolOutline() {
        val geometry = ToolGeometry(
            shapes = listOf(DrawingShape(type = ShapeType.Line, x1 = 0, y1 = 0, x2 = 1_000_000, y2 = 0)),
            drawnIn = 25.4,
        )
        val tool = Tool(id = "t1", kind = ToolKind.Lathe, name = "Turning", geometry = geometry)
        val workspace = ViewSamples.lathe.workspaces.first().copy(activeToolId = "t1")
        val scene = GraphicalSceneBuilder.build(ViewSamples.frame(ViewSamples.lathe, workspace, x = 1_000.0, z = 2_000.0), tools = listOf(tool))
        val outline = assertNotNull(scene.toolOutline)
        assertEquals(25.4, outline.geometry.drawnIn)
        assertEquals(PointUm(2_000.0, -1_000.0), outline.anchor)
        assertNull(GraphicalSceneBuilder.build(ViewSamples.frame(ViewSamples.mill), tools = listOf(tool)).toolOutline)
    }

    @Test
    fun aSelectedGuideIsTheSelectionInPlaceOfTheActivePoint() {
        val scene = GraphicalSceneBuilder.build(ViewSamples.frame(ViewSamples.mill, ViewSamples.millWorkspace()), selectedGuideId = "g1")
        assertEquals(GraphSelection.Guide("g1"), scene.selection)
        assertNull(scene.selectedPoint)
    }
}

/** The tests of the operator frame of the pattern points. */
class OperatorFrameTest {

    @Test
    fun aPatternPointGoesThroughTheInverseDisplayTransformBeforeTheSave() {
        val workspace = ViewSamples.millWorkspace().copy(partRotation = PartRotation(active = true, angleArcSec = 90.0 * 3600.0))
        val frame = ViewSamples.frame(ViewSamples.mill, workspace)
        // The stored point (10, 0) shows at (0, −10). Thus the shown
        // point (0, −10) is stored at (10, 0).
        val stored = OperatorFrame.toStored(frame, listOf(SubDatum(id = "n", x = 0.0, y = -10_000.0, z = -1_000.0), SubDatum(id = "z", z = 5.0)))
        assertEquals(10_000.0, stored[0].x!!, 1e-6)
        assertEquals(0.0, stored[0].y!!, 1e-6)
        assertEquals(-1_000.0, stored[0].z)
        assertEquals(SubDatum(id = "z", z = 5.0).copy(createdOn = stored[1].createdOn, updatedOn = stored[1].updatedOn), stored[1])
        // With no rotation the points do not change.
        val plain = listOf(SubDatum(id = "p", x = 1.0, y = 2.0))
        assertEquals(plain, OperatorFrame.toStored(ViewSamples.frame(ViewSamples.mill), plain))
    }

    @Test
    fun thePlaneOfTheGraphicalViewDoesNotStopTheInverseTransform() {
        // The part rotation is a rotation of X and Y. A view in the plane XZ does not show it,
        // but a new point of the operator frame still goes through it.
        val workspace = ViewSamples.millWorkspace().copy(plane = Plane.XZ, partRotation = PartRotation(active = true, angleArcSec = 90.0 * 3600.0))
        val stored = OperatorFrame.toStored(ViewSamples.frame(ViewSamples.mill, workspace), listOf(SubDatum(id = "n", x = 0.0, y = -10_000.0)))
        assertEquals(10_000.0, stored[0].x!!, 1e-6)
        assertEquals(0.0, stored[0].y!!, 1e-6)
    }
}
