package org.superdro.core.store.repository

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.Drawing
import org.superdro.core.model.DrawingFormat
import org.superdro.core.model.DrawingLayer
import org.superdro.core.model.DrawingShape
import org.superdro.core.model.DrawingSource
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.ShapeType
import org.superdro.core.store.StoreFixture
import org.superdro.core.store.valueOrNull

class DrawingRepositoryTest {

    private val scope = TestScope()
    private val store = StoreFixture(scope)

    @AfterTest
    fun close() = store.close()

    private suspend fun workspaceId(): String =
        store.machines.createProfile(MachineProfile.create(name = "Mill")).valueOrNull()!!.activeWorkspaceId

    @Test
    fun aDrawingKeepsItsLayersAndShapesInNanometers() = scope.runTest {
        val id = workspaceId()
        val drawing = Drawing(
            workspaceId = id,
            layers = listOf(DrawingLayer(0, "0"), DrawingLayer(1, "Holes", hidden = true)),
            shapes = listOf(
                DrawingShape(type = ShapeType.Line, x1 = -1_000_000_000_000L, y1 = 2, x2 = Long.MAX_VALUE, y2 = Long.MIN_VALUE),
                DrawingShape(type = ShapeType.Arc, layerIndex = 1, style = 5, x1 = 0, y1 = 0, radius = 12_700_000, startAngle = 0.25, sweepAngle = -1.5),
                DrawingShape(type = ShapeType.Polyline, groupId = "g1", closed = true, points = listOf(0, 0, 10, 0, 10, 10), bulges = mapOf(1 to 0.414, 2 to -1.0)),
                DrawingShape(type = ShapeType.Point, x1 = 5, y1 = 6),
            ),
            source = DrawingSource("part.dxf", DrawingFormat.BinaryDxf),
        )
        store.drawings.replaceDrawing(drawing)
        assertEquals(drawing, store.drawings.loadDrawing(id))
        assertEquals(drawing, store.drawings.drawing(id).first())

        store.drawings.setLayerHidden(id, 1, false)
        assertFalse(store.drawings.loadDrawing(id)!!.layers[1].hidden)
        assertTrue(store.drawings.deleteShape(id, 1))
        assertEquals(listOf(ShapeType.Line, ShapeType.Polyline, ShapeType.Point), store.drawings.loadDrawing(id)!!.shapes.map { it.type })
        assertFalse(store.drawings.deleteShape(id, 3))

        // A new drawing replaces the old drawing of the workspace.
        store.drawings.replaceDrawing(Drawing(workspaceId = id))
        assertEquals(0L, store.count("drawing_shape"))
        assertEquals(1L, store.count("drawing"))
        store.drawings.deleteDrawing(id)
        assertNull(store.drawings.loadDrawing(id))
    }

    @Test
    fun aDrawingOf10000ShapesIsOneTransaction() = scope.runTest {
        val id = workspaceId()
        val shapes = List(10_000) { index -> DrawingShape(type = ShapeType.Polyline, points = List(20) { it * 1000L + index }) }
        store.drawings.replaceDrawing(Drawing(workspaceId = id, shapes = shapes))
        assertEquals(shapes, store.drawings.loadDrawing(id)!!.shapes)
    }

    @Test
    fun theBlobFormHasAVersionByteAndLittleEndianValues() {
        val points = ShapeBlobs.encodePoints(listOf(1L, -2L))!!
        assertEquals(17, points.size)
        assertEquals(1.toByte(), points[0])
        assertEquals(1.toByte(), points[1])
        assertEquals((-1).toByte(), points[16])
        assertEquals(listOf(1L, -2L), ShapeBlobs.decodePoints(points))
        assertNull(ShapeBlobs.encodePoints(emptyList()))
        assertEquals(mapOf(3 to 0.5), ShapeBlobs.decodeBulges(ShapeBlobs.encodeBulges(mapOf(3 to 0.5))))
    }
}
