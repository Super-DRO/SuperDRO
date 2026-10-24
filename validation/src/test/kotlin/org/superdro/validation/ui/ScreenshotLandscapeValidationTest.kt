package org.superdro.validation.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import org.superdro.validation.ui.ScreenshotChecks.screensClipped

/** Each screen of the screen table shows with no clipped control at 1280 × 800 dp (landscape). */
class ScreenshotLandscapeValidationTest {

    @Test
    fun each_screen_of_7_4_has_no_clipped_control_in_landscape() {
        assertEquals(emptyList(), screensClipped(1280, 800))
    }
}
