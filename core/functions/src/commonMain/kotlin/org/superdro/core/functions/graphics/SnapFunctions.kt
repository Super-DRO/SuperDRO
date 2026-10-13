package org.superdro.core.functions.graphics

import org.superdro.core.functions.PointUm

/**
 * Snap to point. When the tool moves, the
 * view selects the nearest point within 6350 µm (0.25 in). On equal distance, the higher rank
 * ([ViewPoint.order]) wins.
 */
object SnapToPoint {

    /** The search radius in µm. */
    const val RADIUS_UM: Double = 6350.0

    /**
     * Gives the ID of the point to select for the tool position [tool]. Gives null when the
     * selection does not change. This occurs when no point is within [RADIUS_UM], or when the
     * nearest point is [selectedId] already.
     */
    fun select(tool: PointUm, points: List<ViewPoint>, selectedId: String?): String? {
        val nearest = nearest(tool, points) ?: return null
        return if (nearest.id == selectedId) null else nearest.id
    }

    /** Gives the nearest point within [RADIUS_UM] of [tool], or null. */
    fun nearest(tool: PointUm, points: List<ViewPoint>): ViewPoint? {
        var best: ViewPoint? = null
        var bestDistance = Double.POSITIVE_INFINITY
        for (point in points) {
            val dh = point.position.h - tool.h
            val dv = point.position.v - tool.v
            val distance = dh * dh + dv * dv
            if (distance > RADIUS_UM * RADIUS_UM) continue
            val current = best
            if (current == null || distance < bestDistance || (distance == bestDistance && point.order > current.order)) {
                best = point
                bestDistance = distance
            }
        }
        return best
    }
}

/**
 * The velocity of one axis over the samples of the last [windowMs].
 * Each pair of adjacent samples gives one slope sample. The velocity is the mean slope. It is 0
 * unless the window has more than 2 slope samples.
 */
class VelocityEstimator(private val windowMs: Long = WINDOW_MS) {

    private val times = ArrayDeque<Long>()
    private val positions = ArrayDeque<Double>()

    /** Adds the position [positionUm] at the time [timeMs]. */
    fun add(timeMs: Long, positionUm: Double) {
        times.addLast(timeMs)
        positions.addLast(positionUm)
        trim(timeMs)
    }

    /** Gives the velocity in µm/ms at the time [nowMs], or 0. */
    fun velocity(nowMs: Long): Double {
        trim(nowMs)
        var sum = 0.0
        var count = 0
        for (index in 1 until times.size) {
            val dt = times[index] - times[index - 1]
            if (dt <= 0L) continue
            sum += (positions[index] - positions[index - 1]) / dt
            count++
        }
        return if (count > 2) sum / count else 0.0
    }

    /** Removes all samples. */
    fun clear() {
        times.clear()
        positions.clear()
    }

    private fun trim(nowMs: Long) {
        while (times.isNotEmpty() && times.first() < nowMs - windowMs) {
            times.removeFirst()
            positions.removeFirst()
        }
    }

    companion object {
        /** The window of the velocity estimate. */
        const val WINDOW_MS: Long = 1000L
    }
}

/**
 * Snap to guide. For each axis, the
 * snapper keeps the nearest guide below the tool (`lower`) and at or above the tool (`upper`).
 * The velocity comes from the last 1000 ms. The rules are:
 *
 * - select `lower` if `pos < (lower + upper)/2` and the velocity is negative;
 * - select `upper` if `pos > (lower + upper)/2` and the velocity is positive.
 *
 * Vertical motion uses horizontal guides. Horizontal motion uses vertical guides. When one side
 * has no guide, the middle value is −∞ (no `lower`) or +∞ (no `upper`). A guide that the
 * snapper selected is not selected again until the snapper selects a different guide.
 */
class GuideSnapper {

    private val horizontalMotion = AxisSnap(GuideOrientation.Vertical)
    private val verticalMotion = AxisSnap(GuideOrientation.Horizontal)

    /**
     * Adds the tool position [tool] at the time [timeMs]. Gives the ID of the guide to select, or
     * null. When both axes give a guide at the same time, the guide of the horizontal motion wins.
     */
    fun update(timeMs: Long, tool: PointUm, guides: List<ViewGuide>): String? {
        val vertical = verticalMotion.update(timeMs, tool.v, guides)
        if (vertical != null) horizontalMotion.lastSnap = null
        val horizontal = horizontalMotion.update(timeMs, tool.h, guides)
        if (horizontal != null) verticalMotion.lastSnap = null
        return horizontal?.id ?: vertical?.id
    }

    /** Removes all samples and the memory of the last snap. */
    fun reset() {
        horizontalMotion.reset()
        verticalMotion.reset()
    }

    private class AxisSnap(val orientation: GuideOrientation) {
        val velocity = VelocityEstimator()
        var lastSnap: Double? = null

        fun update(timeMs: Long, position: Double, guides: List<ViewGuide>): ViewGuide? {
            velocity.add(timeMs, position)
            val candidates = guides.filter { guide -> guide.orientation == orientation }
            val lower = candidates.filter { guide -> guide.position < position }.maxByOrNull { guide -> guide.position }
            val upper = candidates.filter { guide -> guide.position >= position }.minByOrNull { guide -> guide.position }
            val speed = velocity.velocity(timeMs)
            val middle = when {
                lower != null && upper != null -> (lower.position + upper.position) / 2.0
                lower != null -> Double.POSITIVE_INFINITY
                else -> Double.NEGATIVE_INFINITY
            }
            val target = when {
                lower != null && speed < 0.0 && position < middle -> lower
                upper != null && speed > 0.0 && position > middle -> upper
                else -> null
            } ?: return null
            if (target.position == lastSnap) return null
            lastSnap = target.position
            return target
        }

        fun reset() {
            velocity.clear()
            lastSnap = null
        }
    }
}
