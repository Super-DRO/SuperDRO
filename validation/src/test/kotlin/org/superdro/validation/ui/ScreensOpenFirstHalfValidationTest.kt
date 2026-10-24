package org.superdro.validation.ui

import kotlin.test.Test

/** The first half of the screen table (each even index) opens in `App()` with no placeholder. See [openEntriesWithNoPlaceholder]. */
class ScreensOpenFirstHalfValidationTest {

    @Test
    fun each_entry_of_the_first_half_opens_in_App_with_no_placeholder() = openEntriesWithNoPlaceholder { index -> index % 2 == 0 }
}
