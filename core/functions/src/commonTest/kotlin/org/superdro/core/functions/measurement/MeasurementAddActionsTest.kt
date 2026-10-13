package org.superdro.core.functions.measurement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.superdro.core.model.Drawing
import org.superdro.core.model.DrawingLayer
import org.superdro.core.model.DrawingShape
import org.superdro.core.model.Plane
import org.superdro.core.model.ShapeType
import org.superdro.core.model.SubDatumGroupKind

class MeasurementAddActionsTest {

    // The plane map
    @Test
    fun theSubDatumGetsThePlaneCoordinatesAndTheTag() {
        val xy = MeasurementAddActions.subDatum("Fitted center", 20000.0, 15000.0, Plane.XY)
        assertEquals(listOf(20000.0, 15000.0, null), listOf(xy.x, xy.y, xy.z))
        assertEquals("Fitted center", xy.name)
        assertEquals(listOf("Measured"), xy.tags)
        assertEquals(SubDatumGroupKind.Measured, xy.groupKind)
        val xz = MeasurementAddActions.subDatum("Corner", 1.0, 2.0, Plane.XZ)
        assertEquals(listOf(1.0, null, 2.0), listOf(xz.x, xz.y, xz.z))
        val yz = MeasurementAddActions.subDatum("Corner", 1.0, 2.0, Plane.YZ)
        assertEquals(listOf(null, 1.0, 2.0), listOf(yz.x, yz.y, yz.z))
        val zx = MeasurementAddActions.subDatum("Corner", 1.0, 2.0, Plane.ZX)
        assertEquals(listOf(2.0, null, 1.0), listOf(zx.x, zx.y, zx.z))
    }

    // The shape row
    @Test
    fun theFittedCircleGivesACircleShapeInNanometers() {
        val shape = MeasurementAddActions.circleShape(FittedCircle(20000.0, 15000.0, 26000.0, side = -1), layerIndex = 3)
        assertEquals(
            DrawingShape(type = ShapeType.Circle, layerIndex = 3, style = 1, x1 = 20_000_000L, y1 = 15_000_000L, radius = 26_000_000L),
            shape,
        )
        val rounded = MeasurementAddActions.circleShape(FittedCircle(-0.0005, 1.0004, 0.0015), 0)
        assertEquals(listOf(0L, 1000L, 2L), listOf(rounded.x1, rounded.y1, rounded.radius))
    }

    @Test
    fun theMeasuredLayerIsFoundOrAdded() {
        val empty = Drawing(id = "d1")
        val added = MeasurementAddActions.withMeasuredLayer(empty)
        assertEquals(0, added.layerIndex)
        assertEquals(listOf(DrawingLayer(index = 0, name = "Measured")), added.drawing.layers)
        val withLayers = Drawing(id = "d2", layers = listOf(DrawingLayer(0, "Outline"), DrawingLayer(4, "Holes")))
        val next = MeasurementAddActions.withMeasuredLayer(withLayers)
        assertEquals(5, next.layerIndex)
        val again = MeasurementAddActions.withMeasuredLayer(next.drawing)
        assertEquals(5, again.layerIndex)
        assertEquals(3, again.drawing.layers.size)
        val drawn = MeasurementAddActions.addCircle(withLayers, FittedCircle(1.0, 2.0, 3.0))
        assertEquals(5, drawn.shapes.single().layerIndex)
        assertNull(drawn.shapes.single().x2)
    }
}
