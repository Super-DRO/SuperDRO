package org.superdro.core.ui.graphics.view

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.superdro.core.camera.CameraViewSettings
import org.superdro.core.camera.CrosshairColor
import org.superdro.core.model.GraphicalDisplay

/** The overlay rules of the camera video behind the graphical view. */
class CameraSceneOverlayTest {

    private val scene = GraphicalScene(display = GraphicalDisplay(crosshairsDimmed = true), drawingAlpha = 1.0)

    @Test
    fun withNoVideoTheSceneDoesNotChange() {
        val overlay = CameraSceneOverlay(active = false)
        assertSame(scene, overlay.apply(scene))
        assertFalse(overlay.transparentBackground)
        assertTrue(overlay.referenceImageShown)
        assertEquals(0xFF112233L, overlay.cursorArgb(0xFF112233L))
    }

    @Test
    fun theVideoHidesTheOverlaysAndLocksTheView() {
        val overlay = CameraSceneOverlay(active = true, settings = CameraViewSettings(keepOverlays = false, crosshairColor = CrosshairColor.Cherry))
        val shown = overlay.apply(scene).display
        assertEquals(
            GraphicalDisplay(
                subDatumsShown = false,
                guidesShown = false,
                toolShown = false,
                drawingShown = false,
                crosshairsShown = true,
                crosshairsDimmed = true,
                viewLocked = true,
            ),
            shown,
        )
        assertEquals(0.0, overlay.apply(scene).drawingAlpha)
        assertTrue(overlay.transparentBackground)
        assertFalse(overlay.referenceImageShown)
        // The stored crosshair color; the canvas applies the half opacity itself.
        assertEquals(0xFF8C0020L, overlay.cursorArgb(0xFF112233L))
    }

    @Test
    fun keepOverlaysKeepsTheLayersAndTheCrosshairSwitchStays() {
        val overlay = CameraSceneOverlay(active = true, settings = CameraViewSettings(keepOverlays = true))
        val hidden = scene.copy(display = scene.display.copy(crosshairsShown = false))
        val shown = overlay.apply(hidden).display
        assertTrue(shown.subDatumsShown && shown.guidesShown && shown.toolShown && shown.drawingShown)
        assertFalse(shown.crosshairsShown)
        assertTrue(shown.viewLocked)
        // The default color is the theme color with the alpha 200.
        assertEquals(0xC8112233L, overlay.cursorArgb(0xFF112233L))
    }
}
