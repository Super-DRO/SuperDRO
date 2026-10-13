package org.superdro.core.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CameraOverlayTest {

    private val theme = 0xFF00DDFFL
    private val allShown = OverlayLayers()

    @Test
    fun thereAreSixCrosshairColors() {
        assertEquals(
            listOf("default", "white", "black", "cherry", "dark_green", "dark_magenta"),
            CrosshairColor.entries.map { color -> color.key },
        )
        assertEquals(0xFFFFFFFFL, CrosshairColor.White.resolve(theme))
        assertEquals(0xFF000000L, CrosshairColor.Black.resolve(theme))
        assertEquals(0xFF8C0020L, CrosshairColor.Cherry.resolve(theme))
        assertEquals(0xFF0B5D1EL, CrosshairColor.DarkGreen.resolve(theme))
        assertEquals(0xFF7A0C6EL, CrosshairColor.DarkMagenta.resolve(theme))
        assertEquals(0xC800DDFFL, CrosshairColor.Default.resolve(theme))
        assertEquals(CrosshairColor.DarkGreen, CrosshairColor.fromKey("dark_green"))
        assertEquals(CrosshairColor.Default, CrosshairColor.fromKey("purple"))
        assertEquals(CrosshairColor.Default, CrosshairColor.fromKey(null))
        assertEquals(CrosshairColor.Default, CameraViewSettings().crosshairColor)
        assertFalse(CameraViewSettings().keepOverlays)
    }

    @Test
    fun theColorAppliesOnlyWhileTheVideoPlaysAndDimHalvesTheAlpha() {
        val settings = CameraViewSettings(crosshairColor = CrosshairColor.Cherry)
        assertEquals(0xFF8C0020L, CameraOverlay.crosshairArgb(videoOn = true, settings, theme, dimmed = false))
        assertEquals(theme, CameraOverlay.crosshairArgb(videoOn = false, settings, theme, dimmed = false))
        assertEquals(0x808C0020L, CameraOverlay.crosshairArgb(videoOn = true, settings, theme, dimmed = true))
        assertEquals(0x6400DDFFL, CameraOverlay.crosshairArgb(videoOn = true, CameraViewSettings(), theme, dimmed = true))
    }

    @Test
    fun theVideoHidesTheOverlaysUnlessKeepOverlaysIsOn() {
        assertEquals(allShown, CameraOverlay.visibleLayers(videoOn = false, CameraViewSettings(), allShown))
        assertEquals(
            OverlayLayers(crosshairs = true, subDatums = false, guides = false, toolShape = false, drawing = false, referenceImage = false),
            CameraOverlay.visibleLayers(videoOn = true, CameraViewSettings(), allShown),
        )
        assertEquals(
            OverlayLayers(crosshairs = true, subDatums = true, guides = true, toolShape = true, drawing = true, referenceImage = false),
            CameraOverlay.visibleLayers(videoOn = true, CameraViewSettings(keepOverlays = true), allShown),
        )
        assertEquals(
            OverlayLayers(crosshairs = false, subDatums = false, guides = true, toolShape = true, drawing = true, referenceImage = false),
            CameraOverlay.visibleLayers(
                videoOn = true,
                CameraViewSettings(keepOverlays = true),
                OverlayLayers(crosshairs = false, subDatums = false),
            ),
        )
    }

    @Test
    fun theVideoHoldsTheViewLockAndGivesTheOldStateBack() {
        val unlocked = ViewLockState(locked = false)
        val during = unlocked.onVideoStarted()
        assertEquals(ViewLockState(locked = true, heldByCamera = true, lockedBeforeCamera = false), during)
        assertEquals(during, during.onVideoStarted())
        assertEquals(unlocked, during.onVideoStopped())
        val locked = ViewLockState(locked = true)
        assertEquals(locked, locked.onVideoStarted().onVideoStopped())
        assertEquals(unlocked, unlocked.onVideoStopped())
        assertEquals(unlocked, during.toggled().onVideoStopped())
    }

    @Test
    fun theReferenceImageIsHiddenDuringTheVideo() {
        val image = ReferenceImageAlpha(127)
        val during = image.onVideoStarted()
        assertEquals(0, during.alpha)
        assertEquals(during, during.onVideoStarted())
        assertEquals(image, during.onVideoStopped())
    }

    @Test
    fun theGesturesAndTheBackgroundFollowTheVideo() {
        assertTrue(CameraOverlay.transparentBackground(videoOn = true))
        assertFalse(CameraOverlay.transparentBackground(videoOn = false))
        assertTrue(CameraOverlay.followMode(videoOn = true, locked = false))
        assertFalse(CameraOverlay.followMode(videoOn = false, locked = false))
        assertTrue(CameraOverlay.panAllowed(locked = false, videoOn = false))
        assertFalse(CameraOverlay.panAllowed(locked = false, videoOn = true))
        assertFalse(CameraOverlay.panAllowed(locked = true, videoOn = false))
        assertFalse(CameraOverlay.zoomToFitAllowed(videoOn = true))
        assertTrue(CameraOverlay.zoomToFitAllowed(videoOn = false))
        assertFalse(CameraOverlay.zoomToDrawingAllowed(locked = false, videoOn = true))
        assertTrue(CameraOverlay.zoomToDrawingAllowed(locked = false, videoOn = false))
        assertEquals(1.0f, CameraOverlay.cameraRowAlpha(cameraAttached = true))
        assertEquals(0.4f, CameraOverlay.cameraRowAlpha(cameraAttached = false))
        assertEquals(0L, CameraOverlay.TRANSPARENT_ARGB)
    }
}
