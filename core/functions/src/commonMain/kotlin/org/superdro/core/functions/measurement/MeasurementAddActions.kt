package org.superdro.core.functions.measurement

import kotlin.math.floor
import org.superdro.core.model.Drawing
import org.superdro.core.model.DrawingLayer
import org.superdro.core.model.DrawingShape
import org.superdro.core.model.Plane
import org.superdro.core.model.ShapeType
import org.superdro.core.model.SubDatum
import org.superdro.core.model.SubDatumGroupKind
import org.superdro.core.model.Units

/** The result of [MeasurementAddActions.withMeasuredLayer]: the drawing and the index of the layer. */
data class MeasuredLayer(val drawing: Drawing, val layerIndex: Int)

/**
 * The add actions of the results: a sub-datum with the tag "Measured", and a circle shape
 * on the drawing layer
 * "Measured".
 */
object MeasurementAddActions {

    /** The tag of each sub-datum that an add action makes. */
    const val MEASURED_TAG: String = "Measured"

    /** The name of the drawing layer of the fitted circles. */
    const val MEASURED_LAYER_NAME: String = "Measured"

    /** The stroke style of a fitted circle. */
    const val CIRCLE_STYLE: Int = 1

    /**
     * Makes the sub-datum of a result position ([x], [y]) in [plane], with [name] (the text of a
     * [MeasuredSubDatumName] in the app language) and the tag [tag]. The plane map is:
     *
     * | Plane | Sub-datum |
     * |---|---|
     * | XY | X = x, Y = y |
     * | XZ | X = x, Z = y |
     * | YZ | Y = x, Z = y |
     * | ZX | X = y, Z = x |
     */
    fun subDatum(name: String, x: Double, y: Double, plane: Plane, tag: String = MEASURED_TAG): SubDatum {
        val base = SubDatum(name = name, tags = listOf(tag), groupKind = SubDatumGroupKind.Measured)
        return when (plane) {
            Plane.XY -> base.copy(x = x, y = y)
            Plane.XZ -> base.copy(x = x, z = y)
            Plane.YZ -> base.copy(y = x, z = y)
            Plane.ZX -> base.copy(x = y, z = x)
        }
    }

    /**
     * Makes the circle shape of [circle] on the layer [layerIndex]: the center and the radius in
     * nm (`round(µm × 1000)`, with the round-off of `Math.round`), and the style 1.
     */
    fun circleShape(circle: FittedCircle, layerIndex: Int): DrawingShape = DrawingShape(
        type = ShapeType.Circle,
        layerIndex = layerIndex,
        style = CIRCLE_STYLE,
        x1 = nanometers(circle.centerXUm),
        y1 = nanometers(circle.centerYUm),
        radius = nanometers(circle.radiusUm),
    )

    /**
     * Gives [drawing] with the layer [layerName] and the index of that layer. If the layer is not
     * in the drawing, the function adds it with the next free index.
     */
    fun withMeasuredLayer(drawing: Drawing, layerName: String = MEASURED_LAYER_NAME): MeasuredLayer {
        val existing = drawing.layers.firstOrNull { layer -> layer.name == layerName }
        if (existing != null) return MeasuredLayer(drawing, existing.index)
        val index = (drawing.layers.maxOfOrNull { layer -> layer.index } ?: -1) + 1
        return MeasuredLayer(drawing.copy(layers = drawing.layers + DrawingLayer(index = index, name = layerName)), index)
    }

    /** Gives [drawing] with the circle of [circle] on the layer [layerName]. */
    fun addCircle(drawing: Drawing, circle: FittedCircle, layerName: String = MEASURED_LAYER_NAME): Drawing {
        val (withLayer, index) = withMeasuredLayer(drawing, layerName)
        return withLayer.copy(shapes = withLayer.shapes + circleShape(circle, index))
    }

    private fun nanometers(um: Double): Long = floor(um * Units.NM_PER_UM + 0.5).toLong()
}
