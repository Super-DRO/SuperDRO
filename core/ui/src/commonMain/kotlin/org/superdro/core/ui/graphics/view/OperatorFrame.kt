package org.superdro.core.ui.graphics.view

import org.superdro.core.model.Plane
import org.superdro.core.functions.graphics.Vec2
import org.superdro.core.model.SubDatum
import org.superdro.core.readout.ReadoutFrame

/**
 * The operator frame of the graphical view and of the pattern functions. With a part rotation or an
 * axis alignment on, the user
 * sees the points through the display frame. A new point that the user makes in that frame (a
 * pattern point, a typed point) goes through the inverse display transform before the save.
 */
object OperatorFrame {

    /**
     * Gives [points] in the stored frame of the workspace of [frame]: X and Y go through the
     * inverse of the display frame (Rotate Part, then Align Axes) of the plane `XY`. The rotation
     * is always a rotation of X and Y on a mill, so the plane of the graphical view has no effect.
     * A point with no X or no Y, a lathe, and a frame with no active transform give no change.
     */
    fun toStored(frame: ReadoutFrame, points: List<SubDatum>): List<SubDatum> {
        if (frame.profile.machineType.isLathe) return points
        val display = GraphicalSceneBuilder.displayFrameOf(frame, Plane.XY)
        if (display.isIdentity) return points
        return points.map { point ->
            val x = point.x
            val y = point.y
            if (x == null || y == null) {
                point
            } else {
                val stored = display.inverse(Vec2(x, y))
                point.copy(x = stored.x, y = stored.y)
            }
        }
    }
}
