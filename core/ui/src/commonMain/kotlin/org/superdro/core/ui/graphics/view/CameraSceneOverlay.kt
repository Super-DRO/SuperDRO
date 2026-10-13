package org.superdro.core.ui.graphics.view

import androidx.compose.runtime.Immutable
import org.superdro.core.camera.CameraOverlay
import org.superdro.core.camera.CameraViewSettings
import org.superdro.core.camera.OverlayLayers

/**
 * The state of the camera video that the graphical view needs.
 *
 * @property active True from the first frame until the stop.
 * @property settings `camera_keep_overlays` and `camera_crosshair_color` of the machine profile.
 */
@Immutable
data class CameraSceneOverlay(
    val active: Boolean = false,
    val settings: CameraViewSettings = CameraViewSettings(),
) {
    /** True when the background of the canvas is transparent, so that the video shows behind it. */
    val transparentBackground: Boolean get() = CameraOverlay.transparentBackground(active)

    /** True when the reference image shows: the video hides it. */
    val referenceImageShown: Boolean get() = !active

    /**
     * Gives [scene] with the overlay rules of the video: while the video plays, the sub-datums, the
     * guides, the tool, and the drawing hide unless "keep overlays" is on, and the view stays
     * locked to the crosshairs (follow mode, no pan, no zoom to fit). The crosshair switch stays.
     */
    fun apply(scene: GraphicalScene): GraphicalScene {
        if (!active) return scene
        val display = scene.display
        val shown = OverlayLayers(
            crosshairs = display.crosshairsShown,
            subDatums = display.subDatumsShown,
            guides = display.guidesShown,
            toolShape = display.toolShown,
            drawing = display.drawingShown,
        )
        val layers = CameraOverlay.visibleLayers(active, settings, shown)
        return scene.copy(
            display = display.copy(
                crosshairsShown = layers.crosshairs,
                subDatumsShown = layers.subDatums,
                guidesShown = layers.guides,
                toolShown = layers.toolShape,
                drawingShown = layers.drawing,
                viewLocked = CameraOverlay.followMode(active, display.viewLocked),
            ),
            drawingAlpha = if (layers.drawing) scene.drawingAlpha else 0.0,
        )
    }

    /**
     * Gives the color of the crosshairs as `0xAARRGGBB` for the theme color [themeCursorArgb]:
     * the stored crosshair color while the video plays. The canvas applies the half opacity itself.
     */
    fun cursorArgb(themeCursorArgb: Long): Long = CameraOverlay.crosshairArgb(active, settings, themeCursorArgb, dimmed = false)
}
