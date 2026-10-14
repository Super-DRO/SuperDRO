package org.superdro.core.ui.graphics.view

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.superdro.core.functions.graphics.GraphSelection
import org.superdro.core.functions.thread.ThreadPoints
import org.superdro.core.model.AxisId
import org.superdro.core.model.Guide
import org.superdro.core.model.PartRotation
import org.superdro.core.model.Plane
import org.superdro.core.model.SubDatum

/** The tests of the scene cache: the structure stays while only the tool moves, else it changes. */
class GraphicalSceneCacheTest {

    @Test
    fun aPositionChangeKeepsThePointsAndTheGuidesAndMovesTheTool() {
        val cache = GraphicalSceneCache()
        val workspace = ViewSamples.millWorkspace()
        val firstFrame = ViewSamples.frame(ViewSamples.mill, workspace, x = 1_000.0, y = 2_000.0)
        val secondFrame = ViewSamples.frame(ViewSamples.mill, workspace, x = 4_000.0, y = -3_000.0)
        val first = cache.build(firstFrame)
        val second = cache.build(secondFrame)
        assertSame(first.points, second.points)
        assertSame(first.guides, second.guides)
        assertSame(first.ghostPoints, second.ghostPoints)
        assertSame(first.ghostGuides, second.ghostGuides)
        assertNotEquals(first.tool, second.tool)
        assertNotEquals(first.cutter, second.cutter)
        // The scenes are the same as the scenes of the builder.
        assertEquals(GraphicalSceneBuilder.build(firstFrame), first)
        assertEquals(GraphicalSceneBuilder.build(secondFrame), second)
    }

    @Test
    fun aPositionChangeWithAnArmedThreadKeepsTheThreadOutline() {
        val cache = GraphicalSceneCache()
        val spec = ViewSamples.m10Thread()
        val workspace = ViewSamples.lathe.workspaces.first().copy(
            subDatums = ThreadPoints.toSubDatums(spec) + SubDatum(id = "other", x = 1_000.0, z = 1_000.0),
            armedThreadId = spec.id,
        )
        val threads = listOf(spec)
        val first = cache.build(ViewSamples.frame(ViewSamples.lathe, workspace, x = 5_000.0, z = 0.0), threads = threads)
        val secondFrame = ViewSamples.frame(ViewSamples.lathe, workspace, x = 4_000.0, z = -2_000.0)
        val second = cache.build(secondFrame, threads = threads)
        assertSame(first.threadOutline, second.threadOutline)
        assertSame(first.points, second.points)
        assertSame(first.ghostPoints, second.ghostPoints)
        assertEquals(GraphicalSceneBuilder.build(secondFrame, threads = threads), second)
    }

    @Test
    fun eachStructureChangeMakesANewStructureThatIsTheSameAsTheBuilder() {
        val cache = GraphicalSceneCache()
        val workspace = ViewSamples.millWorkspace()
        val base = cache.build(ViewSamples.frame(ViewSamples.mill, workspace))
        val changes = listOf(
            workspace.copy(subDatums = workspace.subDatums + SubDatum(id = "p4", x = 1.0, y = 2.0)),
            workspace.copy(guides = listOf(Guide(id = "g3", axis = AxisId.X, valueUm = 5.0))),
            workspace.copy(plane = Plane.XZ),
            workspace.copy(partRotation = PartRotation(active = true, angleArcSec = 90.0 * 3600.0)),
        )
        for (changed in changes) {
            val frame = ViewSamples.frame(ViewSamples.mill, changed)
            val scene = cache.build(frame)
            assertNotSame(base.points, scene.points)
            assertEquals(GraphicalSceneBuilder.build(frame), scene)
            // Back to the first workspace: the result is the same as the builder again.
            val again = ViewSamples.frame(ViewSamples.mill, workspace)
            assertEquals(GraphicalSceneBuilder.build(again), cache.build(again))
        }
    }

    @Test
    fun theSelectionFollowsTheActiveSubDatumAndTheSelectedGuide() {
        val cache = GraphicalSceneCache()
        val workspace = ViewSamples.millWorkspace()
        val frame = ViewSamples.frame(ViewSamples.mill, workspace)
        assertEquals(GraphSelection.Point("p1"), cache.build(frame).selection)
        assertEquals(GraphSelection.Guide("g1"), cache.build(frame, selectedGuideId = "g1").selection)
        assertEquals(GraphSelection.Point("p1"), cache.build(frame).selection)
        val other = ViewSamples.frame(ViewSamples.mill, workspace.copy(activeSubDatumId = "p2"))
        assertEquals(GraphSelection.Point("p2"), cache.build(other).selection)
        val none = ViewSamples.frame(ViewSamples.mill, workspace.copy(activeSubDatumId = null))
        assertEquals(GraphSelection.None, cache.build(none).selection)
        assertTrue(cache.build(none).points.isNotEmpty())
    }
}
