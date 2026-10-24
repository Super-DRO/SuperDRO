package org.superdro.validation.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import org.superdro.validation.ui.ScreenshotChecks.lightThinPrimary

/** No screen of the screen table has a text or a thin line in `primary` in light mode. */
class ScreenshotPrimaryValidationTest {

    @Test
    fun light_screens_have_no_text_or_thin_line_in_primary() {
        // The color rule: primary is "not for text or thin lines in light mode". A
        // filled shape in primary (the origin symbol of the graphical view, the track of a checked
        // Switch) is permitted.
        assertEquals(emptyMap(), lightThinPrimary(1280, 800))
    }
}
