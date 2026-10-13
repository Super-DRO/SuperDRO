package org.superdro.core.functions.graphics

import org.superdro.core.functions.PointUm
import org.superdro.core.model.AxisId
import org.superdro.core.model.Plane
import org.superdro.core.model.SubDatum

/**
 * The axes of each plane of the graphical view.
 *
 * | Plane | Horizontal | Vertical | Vertical sign |
 * |---|---|---|---|
 * | `XY` | X | Y | +1 |
 * | `XZ` | X | Z | +1 |
 * | `YZ` | Y | Z | +1 |
 * | `ZX` (lathe) | Z | X | −1 |
 *
 * A view position (h, v) is in µm. The value h is the value of the horizontal axis. The value v
 * is the value of the vertical axis multiplied by the vertical sign. All layers use the same
 * sign: points, the tool, the guides, the reference image, and the drawing.
 */
object PlaneAxes {

    /** Gives the axis on the screen x axis. */
    fun horizontal(plane: Plane): AxisId = when (plane) {
        Plane.XY, Plane.XZ -> AxisId.X
        Plane.YZ -> AxisId.Y
        Plane.ZX -> AxisId.Z
    }

    /** Gives the axis on the screen y axis. */
    fun vertical(plane: Plane): AxisId = when (plane) {
        Plane.XY -> AxisId.Y
        Plane.XZ, Plane.YZ -> AxisId.Z
        Plane.ZX -> AxisId.X
    }

    /** Gives the vertical sign: +1, or −1 for the lathe plane `ZX`. */
    fun verticalSign(plane: Plane): Int = if (plane == Plane.ZX) -1 else 1

    /** Gives the view position of the axis values ([horizontalValue], [verticalValue]). */
    fun toView(plane: Plane, horizontalValue: Double, verticalValue: Double): PointUm =
        PointUm(horizontalValue, verticalSign(plane) * verticalValue)

    /** Gives the axis values (horizontal, vertical) of the view position [point]. */
    fun fromView(plane: Plane, point: PointUm): Pair<Double, Double> = point.h to verticalSign(plane) * point.v

    /**
     * Gives the view position of a position with the axis values [x], [y], and [z]. Gives null
     * when the position has no value on one of the two plane axes.
     */
    fun viewOf(plane: Plane, x: Double?, y: Double?, z: Double?): PointUm? {
        val h = valueOf(horizontal(plane), x, y, z) ?: return null
        val v = valueOf(vertical(plane), x, y, z) ?: return null
        return toView(plane, h, v)
    }

    /**
     * Gives the view position of [subDatum], or null when the sub-datum has no value on one of
     * the two plane axes. Such a sub-datum is not in the view of this plane.
     */
    fun viewOf(plane: Plane, subDatum: SubDatum): PointUm? = viewOf(plane, subDatum.x, subDatum.y, subDatum.z)

    /** Gives the value of [axis] from the values [x], [y], and [z], or null for a different axis. */
    fun valueOf(axis: AxisId, x: Double?, y: Double?, z: Double?): Double? = when (axis) {
        AxisId.X -> x
        AxisId.Y -> y
        AxisId.Z -> z
        else -> null
    }

    /** Gives the value of [axis] of [subDatum]: X, Y, Z, or A (`Angular`). */
    fun valueOf(axis: AxisId, subDatum: SubDatum): Double? = when (axis) {
        AxisId.X -> subDatum.x
        AxisId.Y -> subDatum.y
        AxisId.Z -> subDatum.z
        AxisId.Angular -> subDatum.a
        else -> null
    }

    /** Gives the plane of a workspace in the view of [machineIsLathe]: a lathe always uses `ZX`. */
    fun planeFor(machineIsLathe: Boolean, selected: Plane): Plane = when {
        machineIsLathe -> Plane.ZX
        selected == Plane.ZX -> Plane.XY
        else -> selected
    }
}
