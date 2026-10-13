package org.superdro.core.drawing

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.superdro.core.model.Drawing
import org.superdro.core.model.DrawingLayer
import org.superdro.core.model.DrawingShape
import org.superdro.core.model.ShapeType

class DrawingLayerModelTest {

    private val drawing = Drawing(
        layers = listOf(DrawingLayer(0, "A"), DrawingLayer(1, "B", hidden = true)),
        shapes = listOf(
            DrawingShape(ShapeType.Line, layerIndex = 0, x1 = 0, y1 = 0, x2 = 10_000_000, y2 = 0),
            DrawingShape(ShapeType.Circle, layerIndex = 0, x1 = 20_000_000, y1 = 0, radius = 5_000_000),
            DrawingShape(ShapeType.Arc, layerIndex = 0, x1 = 0, y1 = 20_000_000, radius = 5_000_000, startAngle = 0.0, sweepAngle = PI / 2),
            DrawingShape(ShapeType.Line, layerIndex = 1, x1 = 0, y1 = 1_000, x2 = 10_000_000, y2 = 1_000),
            DrawingShape(ShapeType.Polyline, layerIndex = 0, points = listOf(40_000_000, 0, 50_000_000, 0), bulges = mapOf(0 to 1.0)),
            DrawingShape(ShapeType.Bezier, layerIndex = 0, points = listOf(60_000_000, 0, 60_000_000, 3_000_000, 63_000_000, 3_000_000, 63_000_000, 0)),
            DrawingShape(ShapeType.Point, layerIndex = 0, x1 = 80_000_000, y1 = 80_000_000),
        ),
    )

    @Test
    fun theSixStrokeStylesHaveTheExpectedValues() {
        assertEquals(listOf(230, 230, 230, 120, 230, 60), DrawingStrokeStyles.STYLES.map { style -> style.alpha })
        assertEquals(listOf(null, 1.0, 2.0, 0.7, 1.0, 0.8), DrawingStrokeStyles.STYLES.map { style -> style.widthDp })
        assertEquals(StrokeStyle(4, StrokeColorRole.Construction, 230, 1.0, 8.0, 4.0), DrawingStrokeStyles.of(4))
        assertEquals(StrokeStyle(5, StrokeColorRole.Shape, 60, 0.8, 4.0, 3.0), DrawingStrokeStyles.of(5))
        assertEquals(DrawingStrokeStyles.of(1), DrawingStrokeStyles.of(9))
        assertEquals(2.5, DrawingStrokeStyles.SELECTED.widthDp)
    }

    @Test
    fun aHiddenLayerAndADeletedShapeChangeTheDrawing() {
        val shown = DrawingEdits.withLayerHidden(drawing, 1, false)
        assertEquals(false, shown.layers[1].hidden)
        val hidden = DrawingEdits.withLayerHidden(drawing, 0, true)
        assertEquals(true, hidden.layers[0].hidden)
        val fewer = DrawingEdits.withoutShape(drawing, 1)
        assertEquals(drawing.shapes.size - 1, fewer.shapes.size)
        assertEquals(ShapeType.Arc, fewer.shapes[1].type)
        assertEquals(drawing, DrawingEdits.withoutShape(drawing, 99))
    }

    @Test
    fun theHitTestGivesTheNearestShownShape() {
        // The line of the hidden layer B is nearer, but it is not shown.
        assertEquals(0, DrawingHitTest.nearestShape(drawing, 5_000_000, 2_000, 10_000.0))
        assertEquals(1, DrawingHitTest.nearestShape(drawing, 25_000_000, 1_000, 10_000.0))
        assertEquals(2, DrawingHitTest.nearestShape(drawing, 3_535_534, 23_535_534, 10_000.0))
        // The arc goes from 0° to 90°: a point near 180° is far from it.
        assertNull(DrawingHitTest.nearestShape(drawing, -5_000_000, 20_000_000, 10_000.0))
        // The bulge 1 is a half circle below the chord from (40, 0) to (50, 0) mm: its lowest point is (45, −5).
        assertEquals(4, DrawingHitTest.nearestShape(drawing, 45_000_000, -5_000_000, 10_000.0))
        assertEquals(5, DrawingHitTest.nearestShape(drawing, 61_500_000, 2_250_000, 10_000.0))
        assertEquals(6, DrawingHitTest.nearestShape(drawing, 80_000_500, 80_000_000, 10_000.0))
        assertNull(DrawingHitTest.nearestShape(drawing, 90_000_000, 0, 10_000.0))
    }
}
