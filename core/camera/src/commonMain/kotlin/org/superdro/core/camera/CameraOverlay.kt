package org.superdro.core.camera

import kotlin.math.roundToLong

/**
 * The six crosshair colors of the video. [key] is the stored
 * value of the setting `camera_crosshair_color` (default `default`). The color applies only while
 * the video plays.
 *
 * @property argb The color as `0xAARRGGBB`, or null for [Default]: the theme cursor color with the
 *   alpha 200.
 * @property labelKey The name of the label in `strings_camera.xml`.
 */
enum class CrosshairColor(val key: String, val argb: Long?, val labelKey: String) {
    Default("default", null, "camera_color_default"),
    White("white", 0xFFFFFFFFL, "camera_color_white"),
    Black("black", 0xFF000000L, "camera_color_black"),
    Cherry("cherry", 0xFF8C0020L, "camera_color_cherry"),
    DarkGreen("dark_green", 0xFF0B5D1EL, "camera_color_dark_green"),
    DarkMagenta("dark_magenta", 0xFF7A0C6EL, "camera_color_dark_magenta");

    /** Gives the color. [Default] gives [themeCursorArgb] with the alpha [DEFAULT_ALPHA]. */
    fun resolve(themeCursorArgb: Long): Long = argb ?: CameraOverlay.withAlpha(themeCursorArgb, DEFAULT_ALPHA)

    companion object {
        /** The alpha of the [Default] color. */
        const val DEFAULT_ALPHA: Int = 200

        /** Gives the color of a stored [key]. An unknown key gives [Default]. */
        fun fromKey(key: String?): CrosshairColor = entries.firstOrNull { color -> color.key == key } ?: Default
    }
}

/**
 * The camera settings of the graphical view: `camera_keep_overlays`
 * (default false) and `camera_crosshair_color` (default `default`). The settings store keeps them.
 */
data class CameraViewSettings(
    val keepOverlays: Boolean = false,
    val crosshairColor: CrosshairColor = CrosshairColor.Default,
)

/**
 * The layers of the graphical view that the video changes.
 * Each value is true when the layer shows.
 */
data class OverlayLayers(
    val crosshairs: Boolean = true,
    val subDatums: Boolean = true,
    val guides: Boolean = true,
    val toolShape: Boolean = true,
    val drawing: Boolean = true,
    val referenceImage: Boolean = true,
)

/**
 * The view lock state of the graphical view. The video
 * holds the lock: [onVideoStarted] stores the old state and turns the lock on, [onVideoStopped]
 * gives the old state back.
 *
 * @property locked `c_state_view_locked`: the view keeps the tool position at the view center.
 * @property heldByCamera `c_state_view_lock_held_by_camera`.
 * @property lockedBeforeCamera `c_state_view_locked_before_camera`.
 */
data class ViewLockState(
    val locked: Boolean = false,
    val heldByCamera: Boolean = false,
    val lockedBeforeCamera: Boolean = false,
) {
    /** The video started. */
    fun onVideoStarted(): ViewLockState =
        if (heldByCamera) this else ViewLockState(locked = true, heldByCamera = true, lockedBeforeCamera = locked)

    /** The video stopped. */
    fun onVideoStopped(): ViewLockState =
        if (!heldByCamera) this else ViewLockState(locked = lockedBeforeCamera, heldByCamera = false, lockedBeforeCamera = false)

    /** The user changes the lock. During the video the old state comes back at the stop. */
    fun toggled(): ViewLockState = copy(locked = !locked)
}

/**
 * The reference image alpha during the video: the image is hidden
 * (alpha 0), and the old alpha comes back after the video.
 */
data class ReferenceImageAlpha(val alpha: Int, val beforeCamera: Int? = null) {
    fun onVideoStarted(): ReferenceImageAlpha = if (beforeCamera != null) this else ReferenceImageAlpha(0, alpha)

    fun onVideoStopped(): ReferenceImageAlpha = if (beforeCamera == null) this else ReferenceImageAlpha(beforeCamera, null)
}

/** The overlay rules of the video backdrop. */
object CameraOverlay {

    /** The alpha of the camera row of the display panel when a UVC camera is attached. */
    const val CAMERA_ROW_ALPHA_ATTACHED: Float = 1.0f

    /** The alpha of the camera row when no UVC camera is attached. */
    const val CAMERA_ROW_ALPHA_DETACHED: Float = 0.4f

    /** The background of the graphical view while the video plays: transparent (`0x00000000`). */
    const val TRANSPARENT_ARGB: Long = 0x00000000L

    /** The alpha factor of "Half opacity" (`c_state_cross_hairs_dimmed`). */
    const val DIMMED_FACTOR: Double = 0.5

    /**
     * Gives the layers that show. While the video plays: the reference image is hidden; the
     * sub-datums, the guides, the tool shape, and the drawing are hidden unless
     * [CameraViewSettings.keepOverlays] is true. The crosshairs keep their own switch.
     */
    fun visibleLayers(videoOn: Boolean, settings: CameraViewSettings, shown: OverlayLayers): OverlayLayers {
        if (!videoOn) return shown
        val keep = settings.keepOverlays
        return OverlayLayers(
            crosshairs = shown.crosshairs,
            subDatums = keep && shown.subDatums,
            guides = keep && shown.guides,
            toolShape = keep && shown.toolShape,
            drawing = keep && shown.drawing,
            referenceImage = false,
        )
    }

    /**
     * Gives the crosshair color: while the video plays the color of the setting, else
     * [themeCursorArgb]. [dimmed] multiplies the alpha by 0.5.
     */
    fun crosshairArgb(videoOn: Boolean, settings: CameraViewSettings, themeCursorArgb: Long, dimmed: Boolean): Long {
        val color = if (videoOn) settings.crosshairColor.resolve(themeCursorArgb) else themeCursorArgb
        return if (dimmed) withAlpha(color, (alphaOf(color) * DIMMED_FACTOR).roundToLong().toInt()) else color
    }

    /** True when the background of the graphical view is transparent. */
    fun transparentBackground(videoOn: Boolean): Boolean = videoOn

    /** True when follow mode is on: always while the video plays. */
    fun followMode(videoOn: Boolean, locked: Boolean): Boolean = videoOn || locked

    /** True when a drag pans the view: not when locked, not during the video. */
    fun panAllowed(locked: Boolean, videoOn: Boolean): Boolean = !locked && !videoOn

    /** True when a double tap zooms to fit: not during the video. */
    fun zoomToFitAllowed(videoOn: Boolean): Boolean = !videoOn

    /** True when a drawing load zooms to the drawing: only when not locked and with no video. */
    fun zoomToDrawingAllowed(locked: Boolean, videoOn: Boolean): Boolean = !locked && !videoOn

    /** The alpha of the camera row of the display panel. */
    fun cameraRowAlpha(cameraAttached: Boolean): Float =
        if (cameraAttached) CAMERA_ROW_ALPHA_ATTACHED else CAMERA_ROW_ALPHA_DETACHED

    /** Gives [argb] with the alpha [alpha] (0 to 255). */
    fun withAlpha(argb: Long, alpha: Int): Long = ((alpha.coerceIn(0, 255).toLong()) shl 24) or (argb and 0x00FFFFFFL)

    /** Gives the alpha of [argb]. */
    fun alphaOf(argb: Long): Int = ((argb shr 24) and 0xFFL).toInt()
}
