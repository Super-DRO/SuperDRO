package org.superdro.core.functions.graphics

import kotlin.math.abs
import kotlin.math.sqrt
import org.superdro.core.functions.PointUm
import org.superdro.core.model.MachineType
import org.superdro.core.model.Plane

/**
 * A sub-datum point in the view.
 *
 * @property position The view position (h, v), with the vertical sign of the plane.
 * @property order The creation rank of the point. A higher value is a newer point. When two
 *   points have the same distance, the point with the higher rank wins (the "higher ID" rule).
 */
data class ViewPoint(val id: String, val position: PointUm, val order: Long)

/** The selected item of the graphical view. One point or one guide can be selected. */
sealed interface GraphSelection {
    data object None : GraphSelection
    data class Point(val id: String) : GraphSelection
    data class Guide(val id: String) : GraphSelection
}

/** The result of a hit test. */
sealed interface GraphHit {
    data class Point(val point: ViewPoint) : GraphHit
    data class Guide(val guide: ViewGuide) : GraphHit
    data object Tool : GraphHit

    /** No item is at the touch point. [position] is the model position of the touch point. */
    data class Empty(val position: PointUm) : GraphHit
}

/** The action of a tap. */
sealed interface GraphTapAction {
    /**
     * The selection changes to [selection]. A tap on a point turns snap to point off. A tap on a
     * guide turns snap to guide off.
     */
    data class Select(
        val selection: GraphSelection,
        val snapToPointOff: Boolean,
        val snapToGuideOff: Boolean,
    ) : GraphTapAction

    /** Open the tool offset direction picker. */
    data object OpenDirectionPicker : GraphTapAction

    data object NoAction : GraphTapAction
}

/** The action of a long press. */
sealed interface GraphLongPressAction {
    /** Open the sub-datum context menu of the point. */
    data class OpenSubDatumMenu(val subDatumId: String) : GraphLongPressAction

    /** Open the pair commands: [pointAId] is the selected point, [pointBId] the pressed point. */
    data class OpenPairCommands(val pointAId: String, val pointBId: String) : GraphLongPressAction

    /** Ask "Delete guide" (with undo). */
    data class AskDeleteGuide(val guideId: String) : GraphLongPressAction

    /** Open the radius compensation sheet. */
    data object OpenRadiusCompensation : GraphLongPressAction

    /** Ask to clear the radius compensation. */
    data object AskClearRadiusCompensation : GraphLongPressAction

    /** Open the "Add guide" dialog. [position] is the model position of the touch point. */
    data class OpenAddGuide(val position: PointUm) : GraphLongPressAction

    data object NoAction : GraphLongPressAction
}

/**
 * The values that the tap and long-press actions need.
 *
 * @property selectedPoint The selected point when it is in the view, else null.
 * @property toolOffsetActive True when the X or the Y tool offset is on.
 */
data class GraphActionContext(
    val machineType: MachineType,
    val plane: Plane,
    val selection: GraphSelection,
    val selectedPoint: ViewPoint?,
    val toolDiameterUm: Double,
    val toolOffsetActive: Boolean,
)

/**
 * The hit test and the gesture actions of the graphical view. The order of the test is point,
 * guide, tool. The first hit wins.
 *
 * | Target | Hit area |
 * |---|---|
 * | Point | ±0.25 in on the screen; nearest point |
 * | Guide | ±0.125 in band; nearer guide |
 * | Tool | `√2 × 0.25` in + tool radius from the cutter center |
 *
 * The hit test uses the shown positions. When Rotate Part is on, give the shown (turned) points.
 */
object GraphHitTest {

    /** The half side of the hit square of a point, in inches on the screen. */
    const val POINT_HIT_INCH: Double = 0.25

    /** The half width of the hit band of a guide, in inches on the screen. */
    const val GUIDE_HIT_INCH: Double = 0.125

    /** The hit distance of the tool without the tool radius, in inches on the screen: `√2 × 0.25`. */
    val TOOL_HIT_INCH: Double = sqrt(2.0) * 0.25

    /** Gives the item at the screen position [touch]. */
    fun hit(
        touch: Vec2,
        transform: ViewTransform,
        points: List<ViewPoint>,
        guides: List<ViewGuide>,
        cutter: CutterCircle?,
    ): GraphHit {
        hitPoint(touch, transform, points)?.let { point -> return GraphHit.Point(point) }
        hitGuide(touch, transform, guides)?.let { guide -> return GraphHit.Guide(guide) }
        if (cutter != null && hitsTool(touch, transform, cutter)) return GraphHit.Tool
        return GraphHit.Empty(transform.toModel(touch))
    }

    /** Gives the nearest point in the hit square, or null. On equal distance, the higher rank wins. */
    fun hitPoint(touch: Vec2, transform: ViewTransform, points: List<ViewPoint>): ViewPoint? {
        val limitX = POINT_HIT_INCH * transform.metrics.xdpi
        val limitY = POINT_HIT_INCH * transform.metrics.ydpi
        var best: ViewPoint? = null
        var bestDistance = Double.POSITIVE_INFINITY
        for (point in points) {
            val screen = transform.toScreen(point.position)
            val dx = screen.x - touch.x
            val dy = screen.y - touch.y
            if (abs(dx) > limitX || abs(dy) > limitY) continue
            val distance = dx * dx + dy * dy
            val current = best
            if (current == null || distance < bestDistance || (distance == bestDistance && point.order > current.order)) {
                best = point
                bestDistance = distance
            }
        }
        return best
    }

    /** Gives the nearest guide in the hit band, or null. */
    fun hitGuide(touch: Vec2, transform: ViewTransform, guides: List<ViewGuide>): ViewGuide? {
        var best: ViewGuide? = null
        var bestDistance = Double.POSITIVE_INFINITY
        for (guide in guides) {
            val (distance, limit) = when (guide.orientation) {
                GuideOrientation.Vertical ->
                    abs(transform.screenX(guide.position) - touch.x) to GUIDE_HIT_INCH * transform.metrics.xdpi
                GuideOrientation.Horizontal ->
                    abs(transform.screenY(guide.position) - touch.y) to GUIDE_HIT_INCH * transform.metrics.ydpi
            }
            if (distance <= limit && distance < bestDistance) {
                best = guide
                bestDistance = distance
            }
        }
        return best
    }

    /** True when [touch] is within `√2 × 0.25` in + the tool radius of the cutter center. */
    fun hitsTool(touch: Vec2, transform: ViewTransform, cutter: CutterCircle): Boolean {
        val center = transform.toScreen(cutter.center)
        val limit = TOOL_HIT_INCH * transform.metrics.xdpi + transform.umToPxX(cutter.radiusUm)
        return center.distanceTo(touch) <= limit
    }

    /** Gives the action of a tap on [hit]. */
    fun tapAction(hit: GraphHit, context: GraphActionContext): GraphTapAction = when (hit) {
        is GraphHit.Point -> {
            val selected = context.selection == GraphSelection.Point(hit.point.id)
            GraphTapAction.Select(
                selection = if (selected) GraphSelection.None else GraphSelection.Point(hit.point.id),
                snapToPointOff = true,
                snapToGuideOff = false,
            )
        }
        is GraphHit.Guide -> {
            val selected = context.selection == GraphSelection.Guide(hit.guide.id)
            GraphTapAction.Select(
                selection = if (selected) GraphSelection.None else GraphSelection.Guide(hit.guide.id),
                snapToPointOff = false,
                snapToGuideOff = true,
            )
        }
        GraphHit.Tool ->
            if (!context.machineType.isLathe && context.plane == Plane.XY && context.toolDiameterUm > 0.0) {
                GraphTapAction.OpenDirectionPicker
            } else {
                GraphTapAction.NoAction
            }
        is GraphHit.Empty -> GraphTapAction.NoAction
    }

    /** Gives the action of a long press on [hit]. */
    fun longPressAction(hit: GraphHit, context: GraphActionContext): GraphLongPressAction = when (hit) {
        is GraphHit.Point -> {
            val selected = context.selectedPoint
            if (selected != null && selected.id != hit.point.id) {
                GraphLongPressAction.OpenPairCommands(pointAId = selected.id, pointBId = hit.point.id)
            } else {
                GraphLongPressAction.OpenSubDatumMenu(hit.point.id)
            }
        }
        is GraphHit.Guide -> GraphLongPressAction.AskDeleteGuide(hit.guide.id)
        GraphHit.Tool -> when {
            context.plane != Plane.XY -> GraphLongPressAction.NoAction
            context.toolOffsetActive || context.toolDiameterUm != 0.0 -> GraphLongPressAction.AskClearRadiusCompensation
            else -> GraphLongPressAction.OpenRadiusCompensation
        }
        is GraphHit.Empty -> GraphLongPressAction.OpenAddGuide(hit.position)
    }
}
