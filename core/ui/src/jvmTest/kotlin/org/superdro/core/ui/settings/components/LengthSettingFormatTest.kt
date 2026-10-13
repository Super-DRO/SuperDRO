package org.superdro.core.ui.settings.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The length format `####0.0####` and the units of the settings. */
class LengthSettingFormatTest {

    @Test
    fun theWorkedExampleShowsZeroPointOneInchAndTwoPointFiveFourMillimeters() {
        // 0.1 in is written as 2540 µm, and shows 2.54 mm in metric mode.
        assertEquals(2540.0, LengthSettingFormat.parse("0.1", metric = false))
        assertEquals("0.1", LengthSettingFormat.format(2540.0, metric = false))
        assertEquals("2.54", LengthSettingFormat.format(2540.0, metric = true))
    }

    @Test
    fun theFactorIs1000ForMillimetersAnd25400ForInches() {
        assertEquals(1000.0, LengthSettingFormat.factor(metric = true))
        assertEquals(25400.0, LengthSettingFormat.factor(metric = false))
        assertEquals(20.0, LengthSettingFormat.parse("0.02", metric = true))
    }

    @Test
    fun theFormatHasOneDecimalOrMoreAndFiveDecimalsOrLess() {
        assertEquals("0.0", LengthSettingFormat.format(0.0, metric = true))
        assertEquals("12.0", LengthSettingFormat.format(12_000.0, metric = true))
        assertEquals("0.02", LengthSettingFormat.format(20.0, metric = true))
        // 20 µm is 0.000787401... in: five decimals give 0.00079.
        assertEquals("0.00079", LengthSettingFormat.format(20.0, metric = false))
        assertEquals("-1.25", LengthSettingFormat.format(-1250.0, metric = true))
        assertEquals("12345.6789", LengthSettingFormat.format(12_345_678.9, metric = true))
    }

    @Test
    fun theParseAcceptsADecimalCommaAndRefusesAText() {
        assertEquals(2540.0, LengthSettingFormat.parse("2,54", metric = true))
        assertNull(LengthSettingFormat.parse("abc", metric = true))
        assertNull(LengthSettingFormat.parse("", metric = true))
    }
}
