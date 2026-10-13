package org.superdro.core.dataoutput

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The number text `%.<n>f` with `Locale.US` and the round-off half up. */
class NumberTextTest {

    @Test
    fun theTextHasTheDecimalsAndAPointOnlyWithDecimals() {
        assertEquals("12.7000", FixedPoint.format(12.7, 4))
        assertEquals("13", FixedPoint.format(12.7, 0))
        assertEquals("0.0", FixedPoint.format(0.0, 1))
        assertEquals("0", FixedPoint.format(0.0, 0))
        assertEquals("1000000.000000", FixedPoint.format(1_000_000.0, 6))
        assertEquals("0.000001", FixedPoint.format(0.000001, 6))
        assertEquals("1000000000000000000000.00", FixedPoint.format(1e21, 2))
    }

    @Test
    fun theRoundOffIsHalfUpAwayFromZero() {
        assertEquals("1235", FixedPoint.format(1234.5, 0))
        assertEquals("-1235", FixedPoint.format(-1234.5, 0))
        assertEquals("0.2", FixedPoint.format(0.15, 1))
        assertEquals("1.01", FixedPoint.format(1.005, 2))
        assertEquals("1.00", FixedPoint.format(1.0049, 2))
        assertEquals("10.00", FixedPoint.format(9.995, 2))
        assertEquals("1", FixedPoint.format(0.5, 0))
        assertEquals("0.0001", FixedPoint.format(0.00005, 4))
        assertEquals("0.0000", FixedPoint.format(0.00004, 4))
    }

    @Test
    fun aNegativeValueKeepsTheSign() {
        assertEquals("-3.1750", FixedPoint.format(-3.175, 4))
        assertEquals("-0.0000", FixedPoint.format(-0.00001, 4))
        assertEquals("-0", FixedPoint.format(-0.4, 0))
        // Negative zero is zero (a SuperDRO decision).
        assertEquals("0.0000", FixedPoint.format(-0.0, 4))
    }

    @Test
    fun theTextHasNoExponentAndNoGroupSeparator() {
        assertEquals("1791727402517", FixedPoint.format(1791727402517.0, 0))
        assertEquals("0.0000123", FixedPoint.format(1.23e-5, 7))
        assertEquals("12345678.9", FixedPoint.format(12345678.9, 1))
    }

    @Test
    fun aValueThatIsNotFiniteIsRefused() {
        assertFailsWith<IllegalArgumentException> { FixedPoint.format(Double.NaN, 2) }
        assertFailsWith<IllegalArgumentException> { FixedPoint.format(1.0, 16) }
    }
}
