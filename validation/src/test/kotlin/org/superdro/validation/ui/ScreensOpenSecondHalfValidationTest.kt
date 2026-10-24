package org.superdro.validation.ui

import kotlin.test.Test

/** The second half of the screen table (each odd index) opens in `App()` with no placeholder. See [openEntriesWithNoPlaceholder]. */
class ScreensOpenSecondHalfValidationTest {

    @Test
    fun each_entry_of_the_second_half_opens_in_App_with_no_placeholder() = openEntriesWithNoPlaceholder { index -> index % 2 == 1 }
}
