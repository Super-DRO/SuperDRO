package org.superdro.core.ui.graphics.drawing

import androidx.compose.runtime.Immutable
import kotlin.math.max
import kotlin.math.min
import org.superdro.core.functions.graphics.Matrix3
import org.superdro.core.functions.graphics.Vec2

/**
 * The pan and zoom of a drawing preview ("Set Up Drawing", the cutter geometry). A model position
 * (h, v) is in µm with the y axis up:
 *
 * ```text
 * screen_x = originX + h × scale
 * screen_y = originY − v × scale
 * ```
 *
 * @property scale The screen pixels for each model µm.
 * @property fitted False until the first fit for a known size.
 */
@Immutable
data class PreviewViewport(
    val widthPx: Double = 0.0,
    val heightPx: Double = 0.0,
    val scale: Double = 1.0,
    val originX: Double = 0.0,
    val originY: Double = 0.0,
    val fitted: Boolean = false,
) {
    /** Gives the screen position of the model position ([h], [v]). */
    fun toScreen(h: Double, v: Double): Vec2 = Vec2(originX + h * scale, originY - v * scale)

    /** Gives the model position (µm) of the screen position ([x], [y]). */
    fun toModel(x: Double, y: Double): Vec2 = Vec2((x - originX) / scale, -(y - originY) / scale)

    /** Gives the matrix from model µm to screen pixels. */
    fun matrix(): Matrix3 = Matrix3(scale, 0.0, originX, 0.0, -scale, originY, 0.0, 0.0, 1.0)

    /** Gives the state for the view size [width] × [height]. The model center stays at the center. */
    fun resize(width: Double, height: Double): PreviewViewport =
        copy(widthPx = width, heightPx = height, originX = originX + (width - widthPx) / 2.0, originY = originY + (height - heightPx) / 2.0)

    /**
     * Gives the state that shows the model box from ([minH], [minV]) to ([maxH], [maxV]) with
     * [MARGIN_FRACTION] of the view size on each side. A box with no size gets 10 mm.
     */
    fun fit(minH: Double, minV: Double, maxH: Double, maxV: Double): PreviewViewport {
        if (!(widthPx > 0.0) || !(heightPx > 0.0)) return this
        val boxWidth = max(maxH - minH, MIN_BOX_UM)
        val boxHeight = max(maxV - minV, MIN_BOX_UM)
        val usable = 1.0 - 2.0 * MARGIN_FRACTION
        val newScale = clamp(min(widthPx * usable / boxWidth, heightPx * usable / boxHeight))
        val centerH = (minH + maxH) / 2.0
        val centerV = (minV + maxV) / 2.0
        return copy(
            scale = newScale,
            originX = widthPx / 2.0 - centerH * newScale,
            originY = heightPx / 2.0 + centerV * newScale,
            fitted = true,
        )
    }

    /** Gives the state after a drag by ([dx], [dy]) pixels. */
    fun pan(dx: Double, dy: Double): PreviewViewport = copy(originX = originX + dx, originY = originY + dy)

    /** Gives the state after a pinch by [factor] about the screen point ([focusX], [focusY]). */
    fun zoom(factor: Double, focusX: Double, focusY: Double): PreviewViewport {
        if (!(factor > 0.0) || !factor.isFinite()) return this
        val newScale = clamp(scale * factor)
        val ratio = newScale / scale
        return copy(scale = newScale, originX = focusX - (focusX - originX) * ratio, originY = focusY - (focusY - originY) * ratio)
    }

    companion object {
        /** The part of the view size that stays free on each side of a fit. */
        const val MARGIN_FRACTION: Double = 0.08

        /** The smallest box of a fit, in µm. */
        const val MIN_BOX_UM: Double = 10_000.0

        /** The limits of the scale in pixels for each µm. */
        const val MIN_SCALE: Double = 1e-6
        const val MAX_SCALE: Double = 100.0

        private fun clamp(value: Double): Double = if (value.isNaN()) 1.0 else value.coerceIn(MIN_SCALE, MAX_SCALE)
    }
}
