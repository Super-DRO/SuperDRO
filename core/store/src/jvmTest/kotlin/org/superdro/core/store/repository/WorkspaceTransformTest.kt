package org.superdro.core.store.repository

import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.Drawing
import org.superdro.core.model.DrawingShape
import org.superdro.core.model.Guide
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.ReferenceImage
import org.superdro.core.model.ShapeType
import org.superdro.core.model.SubDatum
import org.superdro.core.store.StoreFixture
import org.superdro.core.store.valueOrNull

/**
 * The workspace transforms in one transaction and the undo. `core:functions` computes the new
 * rows; the store writes them.
 */
class WorkspaceTransformTest {

    private val scope = TestScope()
    private val store = StoreFixture(scope)

    @AfterTest
    fun close() = store.close()

    private suspend fun workspaceId(): String =
        store.machines.createProfile(MachineProfile.create(name = "Mill")).valueOrNull()!!.activeWorkspaceId

    @Test
    fun aFlipWritesTheNewRowsAndTheUndoRestoresTheOldRows() = scope.runTest {
        val id = workspaceId()
        // Flip across X at L = 25.000 mm: point (10.000, 40.000, -2.000) mm, guide Y = 30.000 mm.
        val point = store.machines.addSubDatum(id, SubDatum(x = 10000.0, y = 40000.0, z = -2000.0, tags = listOf("a", "b"))).valueOrNull()!!
        val guide = Guide(axis = AxisId.Y, valueUm = 30000.0)
        store.machines.saveGuide(id, guide)
        store.machines.updateWorkspace(id) { it.copy(activeSubDatumId = point.id) }

        val undo = store.machines.applyTransform(
            id,
            WorkspaceTransformResult(
                label = "Flip",
                subDatums = listOf(point.copy(y = 10000.0)),
                guides = listOf(guide.copy(valueUm = 20000.0)),
            ),
        ).valueOrNull()
        assertEquals("Flip", assertNotNull(undo).label)

        var workspace = store.machines.loadWorkspace(id)!!
        assertEquals(Triple(10000.0, 10000.0, -2000.0), workspace.subDatums.single().let { Triple(it.x, it.y, it.z) })
        assertEquals(listOf("a", "b"), workspace.subDatums.single().tags)
        assertEquals(20000.0, workspace.guides.single().valueUm)
        // The store deactivates the active sub-datum first.
        assertNull(workspace.activeSubDatumId)
        assertEquals(undo, store.machines.transformUndo(id).first())

        assertTrue(store.machines.undoLastTransform(id))
        workspace = store.machines.loadWorkspace(id)!!
        assertEquals(Triple(10000.0, 40000.0, -2000.0), workspace.subDatums.single().let { Triple(it.x, it.y, it.z) })
        assertEquals(listOf("a", "b"), workspace.subDatums.single().tags)
        assertEquals(30000.0, workspace.guides.single().valueUm)
        assertNull(store.machines.transformUndo(id).first())
        assertFalse(store.machines.undoLastTransform(id))
    }

    @Test
    fun aRotateCanConvertAGuideToAPointAndTheUndoPutsTheGuideBack() = scope.runTest {
        val id = workspaceId()
        // Rotate 30° about (10.000, 0.000) mm: point (20.000, 0.000, 3.000) mm gives (18.660, 5.000, 3.000) mm.
        val point = store.machines.addSubDatum(id, SubDatum(x = 20000.0, y = 0.0, z = 3000.0)).valueOrNull()!!
        val guide = Guide(axis = AxisId.X, valueUm = 15000.0)
        store.machines.saveGuide(id, guide)
        val angle = Math.toRadians(30.0)
        val x = 10000.0 + 10000.0 * cos(angle)
        val y = 10000.0 * sin(angle)
        val converted = SubDatum(name = "Guide", x = 15000.0)

        store.machines.applyTransform(id, WorkspaceTransformResult("Rotate", listOf(point.copy(x = x, y = y), converted), guides = emptyList()))

        var workspace = store.machines.loadWorkspace(id)!!
        assertEquals(18660.254, workspace.subDatums.first().x!!, 0.0005)
        assertEquals(5000.0, workspace.subDatums.first().y!!, 0.0005)
        assertEquals(3000.0, workspace.subDatums.first().z)
        assertEquals(2, workspace.subDatums.size)
        assertEquals(emptyList(), workspace.guides)

        store.machines.undoLastTransform(id)
        workspace = store.machines.loadWorkspace(id)!!
        assertEquals(listOf(point.id), workspace.subDatums.map { it.id })
        assertEquals(20000.0, workspace.subDatums.single().x)
        assertEquals(listOf(guide), workspace.guides)
    }

    @Test
    fun aScaleChangesTheDrawingAndTheImageMatrixToo() = scope.runTest {
        val id = workspaceId()
        // Scale 150 % about (100.000, 50.000, 0.000) mm: point (120, 50, -10) mm gives (130, 50, -15) mm.
        val point = store.machines.addSubDatum(id, SubDatum(x = 120000.0, y = 50000.0, z = -10000.0)).valueOrNull()!!
        val line = DrawingShape(type = ShapeType.Line, x1 = 120_000_000, y1 = 50_000_000, x2 = 100_000_000, y2 = 50_000_000)
        store.drawings.replaceDrawing(Drawing(workspaceId = id, shapes = listOf(line)))
        store.machines.setReferenceImage(id, ReferenceImage(fileName = "images/a.jpg"))
        val matrix = listOf(1.5, 0.0, -50000.0, 0.0, 1.5, -25000.0, 0.0, 0.0, 1.0)

        store.machines.applyTransform(
            id,
            WorkspaceTransformResult(
                label = "Scale",
                subDatums = listOf(point.copy(x = 130000.0, z = -15000.0)),
                guides = emptyList(),
                shapes = listOf(line.copy(x1 = 130_000_000)),
                referenceImageMatrix = matrix,
            ),
        )
        assertEquals(Triple(130000.0, 50000.0, -15000.0), store.machines.loadWorkspace(id)!!.subDatums.single().let { Triple(it.x, it.y, it.z) })
        assertEquals(130_000_000L, store.drawings.loadDrawing(id)!!.shapes.single().x1)
        assertEquals(matrix, store.machines.loadWorkspace(id)!!.referenceImage!!.matrix)

        store.machines.undoLastTransform(id)
        assertEquals(120_000_000L, store.drawings.loadDrawing(id)!!.shapes.single().x1)
        assertEquals(ReferenceImage.IDENTITY, store.machines.loadWorkspace(id)!!.referenceImage!!.matrix)
        assertEquals(-10000.0, store.machines.loadWorkspace(id)!!.subDatums.single().z)
    }

    @Test
    fun aTranslateMovesTheAbsoluteOriginAndTheUndoMovesItBack() = scope.runTest {
        val id = workspaceId()
        // Align Absolute Origin to S = (25, 40, -5) mm: point (30, 50, 0) mm gives (5, 10, 5) mm.
        val point = store.machines.addSubDatum(id, SubDatum(x = 30000.0, y = 50000.0, z = 0.0)).valueOrNull()!!
        store.machines.setAxisState(id, AxisId.X, AxisState(absoluteOffset = 1000.0, relative = true))
        val offsets = mapOf(AxisId.X to -24000.0, AxisId.Y to -40000.0, AxisId.Z to 5000.0)

        store.machines.applyTransform(id, WorkspaceTransformResult("Translate", listOf(point.copy(x = 5000.0, y = 10000.0, z = 5000.0)), emptyList(), absoluteOffsets = offsets))
        var states = store.machines.loadWorkspace(id)!!.axisStates
        assertEquals(-24000.0, states.getValue(AxisId.X).absoluteOffset)
        assertTrue(states.getValue(AxisId.X).relative)
        assertEquals(5000.0, states.getValue(AxisId.Z).absoluteOffset)

        store.machines.undoLastTransform(id)
        states = store.machines.loadWorkspace(id)!!.axisStates
        assertEquals(AxisState(absoluteOffset = 1000.0, relative = true), states.getValue(AxisId.X))
        assertEquals(0.0, states.getValue(AxisId.Y).absoluteOffset)
        assertEquals(30000.0, store.machines.loadWorkspace(id)!!.subDatums.single().x)
    }

    @Test
    fun aLaterChangeRemovesTheUndoCopy() = scope.runTest {
        val id = workspaceId()
        val point = store.machines.addSubDatum(id, SubDatum(x = 1.0, y = 1.0)).valueOrNull()!!
        store.machines.applyTransform(id, WorkspaceTransformResult("Flip", listOf(point.copy(y = -1.0)), emptyList()))
        // A zero of an axis does not change the data of the copy: the copy stays.
        store.machines.setAxisState(id, AxisId.X, AxisState(absoluteOffset = 7.0))
        assertNotNull(store.machines.transformUndo(id).first())
        // A new sub-datum after the transform removes the copy, so the undo cannot remove the new point.
        store.machines.addSubDatum(id, SubDatum(x = 2.0))
        assertNull(store.machines.transformUndo(id).first())
        assertFalse(store.machines.undoLastTransform(id))
        assertEquals(2, store.machines.loadWorkspace(id)!!.subDatums.size)
    }

    @Test
    fun anIdentityTransformWritesNothing() = scope.runTest {
        val id = workspaceId()
        val point = store.machines.addSubDatum(id, SubDatum(x = 1.0)).valueOrNull()!!
        val result = store.machines.applyTransform(id, WorkspaceTransformResult("Rotate", listOf(point), emptyList()))
        assertNull(result.valueOrNull())
        assertNull(store.machines.transformUndo(id).first())
    }

    @Test
    fun aTransformIsOneTransaction() = scope.runTest {
        val id = workspaceId()
        val point = store.machines.addSubDatum(id, SubDatum(x = 1.0, y = 1.0)).valueOrNull()!!
        // The test makes the write of the guide fail after the write of the sub-datum.
        store.driver.execute(null, "CREATE TRIGGER test_fail BEFORE INSERT ON guide WHEN new.value_um = -1 BEGIN SELECT RAISE(ABORT, 'test'); END", 0)

        assertFailsWith<Exception> {
            store.machines.applyTransform(id, WorkspaceTransformResult("Flip", listOf(point.copy(y = -1.0)), listOf(Guide(valueUm = -1.0))))
        }
        assertEquals(1.0, store.machines.loadWorkspace(id)!!.subDatums.single().y)
        assertNull(store.machines.transformUndo(id).first())
    }
}
