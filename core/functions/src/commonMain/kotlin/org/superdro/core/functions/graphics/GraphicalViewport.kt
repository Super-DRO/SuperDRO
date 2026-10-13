package org.superdro.core.functions.graphics

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import org.superdro.core.functions.PointUm
import org.superdro.core.model.Plane
import org.superdro.core.model.Units

/**
 * The physical density of the screen.
 *
 * @property xdpi The pixels for each inch on the screen x axis (Android `DisplayMetrics.xdpi`).
 * @property ydpi The pixels for each inch on the screen y axis.
 * @property density The pixels for each dp (Android `DisplayMetrics.density`).
 */
data class ScreenMetrics(val xdpi: Double, val ydpi: Double, val density: Double) {

    /** The screen pixels on the x axis for each model µm at the scale 1. */
    val pxPerUmX: Double get() = xdpi / Units.UM_PER_INCH

    /** The screen pixels on the y axis for each model µm at the scale 1. */
    val pxPerUmY: Double get() = ydpi / Units.UM_PER_INCH

    /** Gives the pixels of [dp]. */
    fun dpToPx(dp: Double): Double = dp * density

    companion object {
        /**
         * The largest ratio between [xdpi] and [ydpi] that [fromReported] keeps. A larger ratio
         * is a wrong value of the device, because the pixels of a screen are square.
         */
        const val MAX_AXIS_RATIO: Double = 1.1

        /**
         * The largest ratio between the selected dpi and the density class that [fromReported]
         * keeps. A larger ratio is a wrong value of the device.
         */
        const val MAX_CLASS_RATIO: Double = 1.5

        /**
         * Gives the metrics of the values that the device reports. Some devices report a wrong
         * [xdpi] or [ydpi]. A wrong value makes a circle into an ellipse on the screen.
         *
         * - If the two values agree in [MAX_AXIS_RATIO], the function keeps the two values.
         * - If not, both axes use the value that is nearer to [densityDpi].
         * - If that value differs from [densityDpi] by more than [MAX_CLASS_RATIO], or if no
         *   value is positive, both axes use [densityDpi].
         *
         * @param densityDpi The dpi of the density class (Android `DisplayMetrics.densityDpi`).
         */
        fun fromReported(xdpi: Double, ydpi: Double, densityDpi: Double, density: Double): ScreenMetrics {
            val x = xdpi.takeIf(::isUsable)
            val y = ydpi.takeIf(::isUsable)
            if (x != null && y != null && max(x, y) / min(x, y) <= MAX_AXIS_RATIO) {
                return ScreenMetrics(x, y, density)
            }
            if (!isUsable(densityDpi)) {
                val single = x ?: y ?: DEFAULT_DPI
                return ScreenMetrics(single, single, density)
            }
            val nearest = listOfNotNull(x, y).minByOrNull { value -> abs(ln(value / densityDpi)) }
            val single = nearest?.takeIf { value -> max(value, densityDpi) / min(value, densityDpi) <= MAX_CLASS_RATIO }
                ?: densityDpi
            return ScreenMetrics(single, single, density)
        }

        /** The dpi when the device reports no usable value: the dpi of the mdpi class. */
        private const val DEFAULT_DPI: Double = 160.0

        private fun isUsable(dpi: Double): Boolean = dpi.isFinite() && dpi > 0.0
    }
}

/**
 * The view transform of the graphical view.
 * With the zoom scale `s`, the origin `O` (px), and the screen densities:
 *
 * ```text
 * screen_x = O.x + h × (xdpi / 25400) × s
 * screen_y = O.y − v × (ydpi / 25400) × s
 * ```
 *
 * At `s` = 1, one model mm is one physical mm on the screen.
 */
data class ViewTransform(val originX: Double, val originY: Double, val scale: Double, val metrics: ScreenMetrics) {

    /** The screen pixels on the x axis for each model µm. */
    val pxPerUmX: Double get() = metrics.pxPerUmX * scale

    /** The screen pixels on the y axis for each model µm. */
    val pxPerUmY: Double get() = metrics.pxPerUmY * scale

    /** Gives the screen position of the model position [point]. */
    fun toScreen(point: PointUm): Vec2 = Vec2(originX + point.h * pxPerUmX, originY - point.v * pxPerUmY)

    /** Gives the screen x value of the model value [h]. */
    fun screenX(h: Double): Double = originX + h * pxPerUmX

    /** Gives the screen y value of the model value [v]. */
    fun screenY(v: Double): Double = originY - v * pxPerUmY

    /** Gives the model position of the screen position [screen] (the inverse formula). */
    fun toModel(screen: Vec2): PointUm = PointUm((screen.x - originX) / pxPerUmX, -(screen.y - originY) / pxPerUmY)

    /** Gives the screen pixels of the model length [um] on the x axis. */
    fun umToPxX(um: Double): Double = um * pxPerUmX

    /** Gives the model µm of the screen length [px] on the x axis. */
    fun pxToUmX(px: Double): Double = px / pxPerUmX

    /** Gives the transform as a matrix: model (h, v) to screen pixels. */
    fun toMatrix(): Matrix3 = Matrix3(pxPerUmX, 0.0, originX, 0.0, -pxPerUmY, originY, 0.0, 0.0, 1.0)

    /**
     * Gives the matrix from the image pixels of the reference image to the screen pixels. The
     * [imageMatrix] maps image pixels to the machine values of [plane].
     * This function applies the vertical sign of the plane, so the image uses the same sign as
     * all other layers. The optional [displayFrame] (Rotate Part) comes after the sign.
     */
    fun imageToScreen(imageMatrix: Matrix3, plane: Plane, displayFrame: Matrix3 = Matrix3.IDENTITY): Matrix3 =
        toMatrix() * displayFrame * Matrix3.scale(1.0, PlaneAxes.verticalSign(plane).toDouble()) * imageMatrix
}

/** The zoom presets of the graphical view. */
enum class ZoomPreset(val label: String, val scale: Double) {
    FiveToOne("5:1", 0.2),
    OneToOne("1:1", 1.0),
    OneToFive("1:5", 5.0),
}

/**
 * The pan and zoom state of the graphical view. Each function gives a new state.
 *
 * - The scale stays in [MIN_SCALE] to [MAX_SCALE] for each change, also for zoom to fit.
 * - A size change does not reset the zoom.
 * - A drag stops follow mode. "Re-center" starts follow mode again.
 *
 * @property followMode True when the view keeps the tool at the view center (the default).
 */
data class GraphicalViewport(
    val widthPx: Double,
    val heightPx: Double,
    val metrics: ScreenMetrics,
    val scale: Double = 1.0,
    val originX: Double = widthPx / 2.0,
    val originY: Double = heightPx / 2.0,
    val followMode: Boolean = true,
) {

    /** The transform of this state. */
    val transform: ViewTransform get() = ViewTransform(originX, originY, scale, metrics)

    /**
     * Gives the state for the tool position [tool]. In follow mode, the origin moves so that the
     * tool is at the view center. The formulas are `O.x = width/2 − h_tool × (xdpi/25400) × s`
     * and `O.y = height/2 + v_tool × (ydpi/25400) × s`. Out of follow mode, the state does not
     * change.
     */
    fun follow(tool: PointUm): GraphicalViewport {
        if (!followMode) return this
        return copy(
            originX = widthPx / 2.0 - tool.h * metrics.pxPerUmX * scale,
            originY = heightPx / 2.0 + tool.v * metrics.pxPerUmY * scale,
        )
    }

    /** Gives the state after a drag of the finger by ([dxPx], [dyPx]). Follow mode stops. */
    fun panBy(dxPx: Double, dyPx: Double): GraphicalViewport =
        copy(originX = originX + dxPx, originY = originY + dyPx, followMode = false)

    /**
     * Gives the state after a pinch by [factor] about the touch point [focus]. The focus point
     * keeps its screen position: `O = F − (F − O) × s_new ÷ s_old`. Follow mode does not change.
     * In follow mode, [follow] then puts the tool at the view center again.
     */
    fun pinch(factor: Double, focus: Vec2): GraphicalViewport {
        if (!factor.isFinite() || factor <= 0.0) return this
        return zoomAbout(scale * factor, focus)
    }

    /** Gives the state with the scale [newScale], clamped, about the view center. */
    fun zoomTo(newScale: Double): GraphicalViewport = zoomAbout(newScale, Vec2(widthPx / 2.0, heightPx / 2.0))

    /** Gives the state with the scale of [preset], about the view center. */
    fun zoomTo(preset: ZoomPreset): GraphicalViewport = zoomTo(preset.scale)

    /** Gives the state with follow mode on ("Re-center"). */
    fun recenter(): GraphicalViewport = copy(followMode = true)

    /**
     * Gives the state for a new view size. The zoom does not change. The model position at
     * the view center stays at the view center.
     */
    fun resize(newWidthPx: Double, newHeightPx: Double): GraphicalViewport = copy(
        widthPx = newWidthPx,
        heightPx = newHeightPx,
        originX = originX + (newWidthPx - widthPx) / 2.0,
        originY = originY + (newHeightPx - heightPx) / 2.0,
    )

    /**
     * Gives the state after a double tap: zoom to fit. The box holds
     * the model origin, all [points], and the [tool] position. The margin is 0.5 in on each side.
     * The scale is clamped. Follow mode stops.
     */
    fun zoomToFit(points: List<PointUm>, tool: PointUm): GraphicalViewport {
        var minH = 0.0
        var maxH = 0.0
        var minV = 0.0
        var maxV = 0.0
        for (point in points + tool) {
            if (!point.h.isFinite() || !point.v.isFinite()) continue
            minH = min(minH, point.h)
            maxH = max(maxH, point.h)
            minV = min(minV, point.v)
            maxV = max(maxV, point.v)
        }
        return zoomToBox(minH, minV, maxH, maxV)
    }

    /**
     * Gives the state that shows the model box from ([minH], [minV]) to ([maxH], [maxV]) with a
     * margin of 0.5 in on each side. A box side of 0 gives the ratio 1.0 for that side. The scale
     * is clamped. Follow mode stops.
     */
    fun zoomToBox(minH: Double, minV: Double, maxH: Double, maxV: Double): GraphicalViewport {
        val boxWidth = maxH - minH
        val boxHeight = maxV - minV
        val ratioX = if (boxWidth == 0.0) 1.0 else
            widthPx / (boxWidth * metrics.pxPerUmX + 2.0 * FIT_MARGIN_INCH * metrics.xdpi)
        val ratioY = if (boxHeight == 0.0) 1.0 else
            heightPx / (boxHeight * metrics.pxPerUmY + 2.0 * FIT_MARGIN_INCH * metrics.ydpi)
        val newScale = clampScale(min(ratioX, ratioY))
        val centerH = (minH + maxH) / 2.0
        val centerV = (minV + maxV) / 2.0
        return copy(
            scale = newScale,
            originX = widthPx / 2.0 - centerH * metrics.pxPerUmX * newScale,
            originY = heightPx / 2.0 + centerV * metrics.pxPerUmY * newScale,
            followMode = false,
        )
    }

    private fun zoomAbout(newScale: Double, focus: Vec2): GraphicalViewport {
        val clamped = clampScale(newScale)
        val ratio = clamped / scale
        return copy(
            scale = clamped,
            originX = focus.x - (focus.x - originX) * ratio,
            originY = focus.y - (focus.y - originY) * ratio,
        )
    }

    companion object {
        /** The smallest zoom scale. It lets a large drawing fit on the screen. */
        const val MIN_SCALE: Double = 0.01

        /** The largest zoom scale. */
        const val MAX_SCALE: Double = 10.0

        /** The margin of zoom to fit on each side, in inches on the screen. */
        const val FIT_MARGIN_INCH: Double = 0.5

        /** Gives [scale] clamped to [MIN_SCALE]…[MAX_SCALE]. NaN gives 1.0. */
        fun clampScale(scale: Double): Double = if (scale.isNaN()) 1.0 else scale.coerceIn(MIN_SCALE, MAX_SCALE)
    }
}
