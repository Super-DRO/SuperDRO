package org.superdro.core.ui

import androidx.compose.ui.graphics.toArgb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.superdro.core.model.ThemeMode
import org.superdro.core.ui.theme.Theme

/**
 * Draws the empty [AppPreview] in the light and the dark scheme to PNG files
 * (`core/ui/build/screenshots/`), and checks the surface color of each scheme.
 */
class AppScreenshotTest {

    @Test
    fun theEmptyAppInLightMode() {
        val file = ScreenshotRenderer.render("app-empty-light") {
            AppPreview(themeMode = ThemeMode.System, systemInDarkTheme = false, state = AppFrameState(rememberedMachineCount = 2))
        }
        assertTrue(file.length() > 0)
        assertCornerColor("app-empty-light", Theme.lightScheme.surface.toArgb())
    }

    @Test
    fun theEmptyAppInDarkMode() {
        val file = ScreenshotRenderer.render("app-empty-dark") {
            AppPreview(themeMode = ThemeMode.Dark, systemInDarkTheme = false, state = AppFrameState(rememberedMachineCount = 2))
        }
        assertTrue(file.length() > 0)
        assertCornerColor("app-empty-dark", Theme.darkScheme.surface.toArgb())
    }

    @Test
    fun theEmptyAppInPortrait() {
        ScreenshotRenderer.render("app-empty-portrait-dark", widthDp = 800, heightDp = 1280) {
            AppPreview(themeMode = ThemeMode.Dark, systemInDarkTheme = false)
        }
    }

    /** The bottom left pixel shows the surface of the scheme. */
    private fun assertCornerColor(name: String, expected: Int) {
        val image = ScreenshotRenderer.renderImage {
            AppPreview(themeMode = if (name.endsWith("dark")) ThemeMode.Dark else ThemeMode.System, systemInDarkTheme = false)
        }
        val bitmap = Bitmap.makeFromImage(image)
        val pixel = bitmap.getColor(4, image.height - 4)
        assertEquals(Integer.toHexString(expected), Integer.toHexString(pixel), name)
    }
}
