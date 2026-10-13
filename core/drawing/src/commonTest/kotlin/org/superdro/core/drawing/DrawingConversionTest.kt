package org.superdro.core.drawing

import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import org.superdro.core.model.DrawingFormat
import org.superdro.core.model.DrawingLayer
import org.superdro.core.model.DrawingShape
import org.superdro.core.model.DrawingSource
import org.superdro.core.model.ShapeType

class DrawingConversionTest {

    private val created = Instant.parse("2026-10-12T08:30:00Z")

    private val document = WireframeDocument(
        createdAt = "2026-10-12T08:30:00Z",
        layers = listOf(WireframeLayer("0"), WireframeLayer("CENTER", visible = false, style = 4)),
        entities = listOf(
            WireframeLine(NmPoint(0, 0), NmPoint(25_400_000, 0), layer = 1),
            WireframeLine(NmPoint(0, 0), NmPoint(0, 1), layer = 1, style = 2, group = "grp"),
            WireframePoint(NmPoint(5, 6), name = "Datum A"),
            WireframeCircle(NmPoint(1, 2), 3_175_000),
            WireframeArc(NmPoint(1_000_000, -2_000), 400_000, 0.5, -1.0),
            WireframePolyline(listOf(NmPoint(0, 0), NmPoint(1000, 0), NmPoint(1000, 1000)), closed = true, bulges = mapOf(1 to 0.414)),
            WireframeBezier(listOf(NmPoint(0, 0), NmPoint(1, 1), NmPoint(2, 1), NmPoint(3, 0)), closed = false),
        ),
    )

    @Test
    fun theDocumentGivesTheLayersAndTheShapesOfTheModel() {
        val drawing = DrawingConversion.toDrawing(document, "ws-1", DrawingSource("part.dxf", DrawingFormat.BinaryDxf), id = "d-1", createdOn = created)
        assertEquals("ws-1", drawing.workspaceId)
        assertEquals(DrawingSource("part.dxf", DrawingFormat.BinaryDxf), drawing.source)
        assertEquals(listOf(DrawingLayer(0, "0", false), DrawingLayer(1, "CENTER", true)), drawing.layers)
        assertEquals(
            listOf(
                DrawingShape(ShapeType.Line, layerIndex = 1, style = 4, x1 = 0, y1 = 0, x2 = 25_400_000, y2 = 0),
                DrawingShape(ShapeType.Line, layerIndex = 1, style = 2, groupId = "grp", x1 = 0, y1 = 0, x2 = 0, y2 = 1),
                DrawingShape(ShapeType.Point, layerIndex = 0, style = 1, x1 = 5, y1 = 6, name = "Datum A"),
                DrawingShape(ShapeType.Circle, layerIndex = 0, style = 1, x1 = 1, y1 = 2, radius = 3_175_000),
                DrawingShape(ShapeType.Arc, layerIndex = 0, style = 1, x1 = 1_000_000, y1 = -2_000, radius = 400_000, startAngle = 0.5, sweepAngle = -1.0),
                DrawingShape(ShapeType.Polyline, layerIndex = 0, style = 1, closed = true, points = listOf(0, 0, 1000, 0, 1000, 1000), bulges = mapOf(1 to 0.414)),
                DrawingShape(ShapeType.Bezier, layerIndex = 0, style = 1, points = listOf(0, 0, 1, 1, 2, 1, 3, 0)),
            ),
            drawing.shapes,
        )
    }

    @Test
    fun thePointNamesStayAfterTheConversion() {
        val drawing = DrawingConversion.toDrawing(document, "ws-1", createdOn = created)
        val back = DrawingConversion.toWireframe(drawing, created)
        assertEquals("Datum A", (back.entities[2] as WireframePoint).name)
    }

    @Test
    fun aDrawingGivesAWireframeDocumentWithTheStyleOfEachShape() {
        val drawing = DrawingConversion.toDrawing(document, "ws-1", createdOn = created)
        val back = DrawingConversion.toWireframe(drawing, created)
        assertEquals(listOf(WireframeLayer("0", true, 1), WireframeLayer("CENTER", false, 1)), back.layers)
        assertEquals(document.entities.map { entity -> document.styleOf(entity) }, back.entities.map { entity -> entity.style })
        assertEquals(DrawingConversion.toShapes(document), DrawingConversion.toShapes(back))
        assertEquals("2026-10-12T08:30:00Z", back.createdAt)
    }

    @Test
    fun aShapeWithMissingDataGoesAway() {
        val back = DrawingConversion.toWireframe(listOf(DrawingLayer(0, "")), listOf(DrawingShape(ShapeType.Line, x1 = 0)), created)
        assertEquals(emptyList(), back.entities)
        assertEquals("Layer 0", back.layers.single().name)
    }
}
