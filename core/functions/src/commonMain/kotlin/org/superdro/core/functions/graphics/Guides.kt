package org.superdro.core.functions.graphics

import kotlin.math.abs
import org.superdro.core.model.AxisId
import org.superdro.core.model.Guide
import org.superdro.core.model.Plane
import org.superdro.core.model.SubDatum

/** The direction of a guide line on the screen. */
enum class GuideOrientation { Horizontal, Vertical }

/**
 * A guide in the view of one plane.
 *
 * @property position For a [GuideOrientation.Vertical] guide, the h value of the line. For a
 *   [GuideOrientation.Horizontal] guide, the v value of the line, with the vertical sign of the
 *   plane.
 */
data class ViewGuide(val id: String, val orientation: GuideOrientation, val position: Double)

/**
 * The guide buttons of the sub-datum context menu. Each value is the guide position on the
 * axis that the guide stores, or null when the sub-datum has no such coordinate. A null value
 * disables its button.
 *
 * @property horizontalGuideValue "Add Horizontal Guide Here": the vertical coordinate of the point.
 * @property verticalGuideValue "Add Vertical Guide Here": the horizontal coordinate of the point.
 */
data class GuideButtons(val horizontalGuideValue: Double?, val verticalGuideValue: Double?) {
    val horizontalEnabled: Boolean get() = horizontalGuideValue != null
    val verticalEnabled: Boolean get() = verticalGuideValue != null
}

/**
 * The guide rules.
 *
 * | Machine | Plane | Horizontal guide stores | Vertical guide stores |
 * |---|---|---|---|
 * | Lathe | `ZX` | X | Z |
 * | Mill | `XY` | Y | X |
 * | Mill | `XZ` | Z | X |
 * | Mill | `YZ` | Z | Y |
 */
object GuideRules {

    /**
     * Two guides on the same axis are the same guide when their values are less than this far
     * apart (µm). This is half of the smallest resolution of 1 µm.
     */
    const val DUPLICATE_TOLERANCE_UM: Double = 0.5

    /** Gives the axis that a guide of [orientation] stores in [plane]. */
    fun axisOf(plane: Plane, orientation: GuideOrientation): AxisId = when (orientation) {
        GuideOrientation.Horizontal -> PlaneAxes.vertical(plane)
        GuideOrientation.Vertical -> PlaneAxes.horizontal(plane)
    }

    /** Gives the view of [guide] in [plane], or null when the guide axis is not an axis of the plane. */
    fun viewOf(plane: Plane, guide: Guide): ViewGuide? = when (guide.axis) {
        PlaneAxes.horizontal(plane) -> ViewGuide(guide.id, GuideOrientation.Vertical, guide.valueUm)
        PlaneAxes.vertical(plane) ->
            ViewGuide(guide.id, GuideOrientation.Horizontal, PlaneAxes.verticalSign(plane) * guide.valueUm)
        else -> null
    }

    /** Gives the views of the guides of [plane]. A guide on a different axis is not in the list. */
    fun viewsOf(plane: Plane, guides: List<Guide>): List<ViewGuide> = guides.mapNotNull { guide -> viewOf(plane, guide) }

    /** True when [guides] has a guide on [axis] at [valueUm] (within [DUPLICATE_TOLERANCE_UM]). */
    fun isDuplicate(guides: List<Guide>, axis: AxisId, valueUm: Double): Boolean =
        guides.any { guide -> guide.axis == axis && abs(guide.valueUm - valueUm) < DUPLICATE_TOLERANCE_UM }

    /**
     * Gives a new guide of [orientation] at the axis value [valueUm], or null when [guides] has
     * the same guide. The app does not add a duplicate guide.
     */
    fun newGuide(guides: List<Guide>, plane: Plane, orientation: GuideOrientation, valueUm: Double): Guide? {
        val axis = axisOf(plane, orientation)
        if (!valueUm.isFinite() || isDuplicate(guides, axis, valueUm)) return null
        return Guide(axis = axis, valueUm = valueUm)
    }

    /**
     * Gives the guide buttons of the context menu of [subDatum] in [plane]. Each button uses its
     * own coordinate, and is enabled only when that coordinate exists.
     */
    fun buttonsFor(plane: Plane, subDatum: SubDatum): GuideButtons = GuideButtons(
        horizontalGuideValue = PlaneAxes.valueOf(axisOf(plane, GuideOrientation.Horizontal), subDatum),
        verticalGuideValue = PlaneAxes.valueOf(axisOf(plane, GuideOrientation.Vertical), subDatum),
    )
}
