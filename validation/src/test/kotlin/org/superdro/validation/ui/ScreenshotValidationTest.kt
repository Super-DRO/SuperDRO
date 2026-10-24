package org.superdro.validation.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.superdro.core.model.AxisId
import org.superdro.core.model.SidePanelMode
import org.superdro.core.ui.readout.valueTag
import org.superdro.core.ui.theme.Theme
import org.superdro.validation.ui.ScreenshotChecks.clipped
import org.superdro.validation.ui.ScreenshotChecks.toArgbOpaque

/**
 * The screenshot tests of the main screen in light and dark mode at the widths 600 dp, 840 dp, and
 * 1200 dp, landscape and portrait. The app operates on a tablet in landscape and in portrait: the
 * main screen shows with no clipped control at 1280 × 800 dp and 800 × 1280 dp. The PNG files go to
 * `validation/build/ui-screenshots`. The other screens: [ScreenshotLandscapeValidationTest],
 * [ScreenshotPortraitValidationTest], and [ScreenshotPrimaryValidationTest].
 */
@OptIn(ExperimentalTestApi::class)
class ScreenshotValidationTest {

    /** The window sizes: each width in portrait and in landscape (16 : 10). */
    private val sizes: List<Pair<Int, Int>> = listOf(600, 840, 1200).flatMap { width ->
        listOf(width to width * 16 / 10, width to width * 10 / 16)
    }

    @Test
    fun screenshots_of_the_main_screen_in_light_and_dark_mode() {
        val files = ArrayList<String>()
        listOf(false, true).forEach { dark ->
            (sizes + listOf(1280 to 800, 800 to 1280)).forEach { (width, height) ->
                runWindowTest(width, height) {
                    setContent { SampleMainScreen(MainScreenSamples.mill(), dark = dark) }
                    waitForIdle()
                    val name = "main-${if (dark) "dark" else "light"}-${width}x$height"
                    files += writePng(captureToImage(), name).name
                }
            }
            // The side panel in the supporting pane (landscape) and in its bottom sheet (portrait).
            listOf(1280 to 800, 800 to 1280).forEach { (width, height) ->
                runWindowTest(width, height) {
                    setContent { SampleMainScreen(MainScreenSamples.mill(sidePanel = SidePanelMode.SubDatums, reminder = true), dark = dark) }
                    waitForIdle()
                    mainClock.advanceTimeBy(1_000L)
                    files += writePng(captureToImage(), "main-panel-${if (dark) "dark" else "light"}-${width}x$height").name
                }
            }
        }
        assertEquals(2 * (sizes.size + 4), files.size)
    }

    @Test
    fun readout_digits_are_onSurface_on_surface_in_the_two_modes() {
        listOf(false, true).forEach { dark ->
            runWindowTest(1280, 800) {
                setContent { SampleMainScreen(MainScreenSamples.mill(), dark = dark) }
                waitForIdle()
                val scheme = if (dark) Theme.darkScheme else Theme.lightScheme
                val box = onNodeWithTag(valueTag(AxisId.X)).rect()
                val image = captureToImage()
                val all = pixels(image)
                val surface = scheme.surface.hex()
                val onSurface = scheme.onSurface.hex()
                var surfaceCount = 0
                var digitCount = 0
                for (y in (box.top + 30f).toInt() until box.bottom.toInt()) {
                    for (x in box.left.toInt() until (box.right - 72f).toInt()) {
                        when ("#%06X".format(all[y * image.width + x] and 0xFFFFFF)) {
                            surface -> surfaceCount++
                            onSurface -> digitCount++
                        }
                    }
                }
                assertTrue(surfaceCount > 1000, "the value box has the surface color $surface (dark $dark)")
                assertTrue(digitCount > 500, "the digits have the onSurface color $onSurface: $digitCount pixels (dark $dark)")
            }
        }
    }

    @Test
    fun light_main_screen_has_no_primary_text_or_line() {
        listOf(1280 to 800, 800 to 1280).forEach { (width, height) ->
            runWindowTest(width, height) {
                setContent { SampleMainScreen(MainScreenSamples.mill(reminder = true), dark = false) }
                waitForIdle()
                val primary = Theme.lightScheme.primary.toArgbOpaque()
                val count = pixels(captureToImage()).count { pixel -> pixel or 0xFF000000.toInt() == primary }
                assertEquals(0, count, "pixels of primary #FF6D00 in light mode at $width x $height")
            }
        }
    }

    @Test
    fun main_screen_has_no_clipped_control_at_the_tablet_sizes() {
        val problems = ArrayList<String>()
        listOf(1280 to 800, 800 to 1280).forEach { (width, height) ->
            listOf(SidePanelMode.Off, SidePanelMode.SubDatums).forEach { panel ->
                runWindowTest(width, height) {
                    setContent { SampleMainScreen(MainScreenSamples.mill(sidePanel = panel, reminder = true), dark = false) }
                    waitForIdle()
                    mainClock.advanceTimeBy(1_000L)
                    problems += clipped(width, height).map { "$width x $height, panel $panel: $it" }
                }
            }
        }
        assertEquals(emptyList(), problems)
    }

    @Test
    fun measurement_mode_chip_has_no_primary_icon_in_light_mode() = runWindowTest(400, 120) {
        setContent {
            org.superdro.core.ui.theme.AppTheme(systemInDarkTheme = false) {
                androidx.compose.material3.Surface {
                    org.superdro.core.ui.graphics.measure.MeasurementModeToggle(on = false, onToggle = {})
                }
            }
        }
        waitForIdle()
        val primary = Theme.lightScheme.primary.toArgbOpaque()
        val count = pixels(captureToImage()).count { pixel -> pixel or 0xFF000000.toInt() == primary }
        assertEquals(0, count, "pixels of primary #FF6D00 in the chip (an outline icon is a thin line)")
    }
}
