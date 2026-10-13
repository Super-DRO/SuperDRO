package org.superdro.core.readout.expression

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The decimal arithmetic of the evaluator. The expected values come from `java.math.BigDecimal`. */
class DecimalTest {

    private fun d(text: String): Decimal = requireNotNull(Decimal.parse(text)) { text }

    @Test
    fun parseForms() {
        assertEquals("1.5", d("1.5").toPlainString())
        assertEquals("0.5", d(".5").toPlainString())
        assertEquals("5", d("5.").toPlainString())
        assertEquals("-0.5", d("-0.5").toPlainString())
        assertEquals("2", d("+2").toPlainString())
        assertEquals("0.00001", d("1.0E-5").toPlainString())
        assertEquals("1200", d("12e2").toPlainString())
        assertNull(Decimal.parse(""))
        assertNull(Decimal.parse("."))
        assertNull(Decimal.parse("1e"))
        assertNull(Decimal.parse("1e+"))
        assertNull(Decimal.parse("e5"))
        assertNull(Decimal.parse("1.2.3"))
        assertNull(Decimal.parse("1,5"))
        assertNull(Decimal.parse("NaN"))
    }

    @Test
    fun plainTextHasNoExponentAndNoTrailingZeros() {
        assertEquals("1200", Decimal.of(12, -2).toPlainString())
        assertEquals("0.005", Decimal.of(5, 3).toPlainString())
        assertEquals("-1.5", Decimal.of(-1500, 3).toPlainString())
        assertEquals("0", Decimal.ZERO.toPlainString())
        assertEquals("0", Decimal.of(0, 5).toPlainString())
        assertEquals("100", Decimal.of(100, 0).toPlainString())
        assertEquals("10.01", Decimal.of(100100, 4).toPlainString())
    }

    @Test
    fun exactValueOfADouble() {
        assertEquals("0.1000000000000000055511151231257827021181583404541015625", Decimal.exactOf(0.1).toPlainString())
        assertEquals(
            "0.000010000000000000000818030539140313095458623138256371021270751953125",
            Decimal.exactOf(1e-5).toPlainString(),
        )
        assertEquals("-2.5", Decimal.exactOf(-2.5).toPlainString())
        assertEquals("123456.789000000004307366907596588134765625", Decimal.exactOf(123456.789).toPlainString())
        assertEquals("0", Decimal.exactOf(-0.0).toPlainString())
        assertEquals(d("4.940656458412465e-324"), Decimal.exactOf(Double.MIN_VALUE).round(16, RoundingMode.HALF_EVEN))
        assertEquals(Double.MAX_VALUE, Decimal.exactOf(Double.MAX_VALUE).toDouble())
    }

    @Test
    fun shortestValueOfADouble() {
        assertEquals("0.1", Decimal.shortestOf(0.1).toPlainString())
        assertEquals("0.00001", Decimal.shortestOf(1e-5).toPlainString())
        assertEquals("-12.7", Decimal.shortestOf(-12.7).toPlainString())
        assertEquals("1000000000000000000000", Decimal.shortestOf(1e21).toPlainString())
        assertEquals("10", Decimal.shortestOf(10.000).toPlainString())
    }

    @Test
    fun divisionWithEachRoundOff() {
        assertEquals(d("0.3333333333333333"), Decimal.ONE.divide(Decimal.of(3), 16, RoundingMode.HALF_EVEN))
        assertEquals(d("0.6666666666666667"), Decimal.of(2).divide(Decimal.of(3), 16, RoundingMode.HALF_UP))
        assertEquals(d("5.960464477539062e-8"), Decimal.ONE.divide(Decimal.of(16777216), 16, RoundingMode.HALF_EVEN))
        assertEquals(d("5.960464477539063e-8"), Decimal.ONE.divide(Decimal.of(16777216), 16, RoundingMode.HALF_UP))
        assertEquals("0.125", Decimal.ONE.divide(Decimal.of(8), 16, RoundingMode.HALF_EVEN).toPlainString())
        assertEquals("-2.5", Decimal.of(-5).divide(Decimal.of(2), 16, RoundingMode.HALF_EVEN).toPlainString())
        assertEquals("250", d("1e3").divide(d("4"), 16, RoundingMode.HALF_EVEN).toPlainString())
        assertEquals("0", Decimal.ZERO.divide(Decimal.of(7), 16, RoundingMode.HALF_EVEN).toPlainString())
    }

    @Test
    fun roundToSignificantDigitsCarriesIntoANewDigit() {
        assertEquals("10", d("9.9999999999999999").round(16, RoundingMode.HALF_EVEN).toPlainString())
        assertEquals(16, d("9.9999999999999999").round(16, RoundingMode.HALF_EVEN).precision)
        assertEquals("1.234567890123457", d("1.23456789012345678").round(16, RoundingMode.HALF_EVEN).toPlainString())
        assertEquals("-1.234567890123457", d("-1.23456789012345678").round(16, RoundingMode.HALF_EVEN).toPlainString())
    }

    @Test
    fun setScaleWithEachRoundOff() {
        assertEquals("-3", d("-2.5").setScale(0, RoundingMode.FLOOR).toPlainString())
        assertEquals("-2", d("-2.5").setScale(0, RoundingMode.CEILING).toPlainString())
        assertEquals("-2", d("-2.5").setScale(0, RoundingMode.DOWN).toPlainString())
        assertEquals("-3", d("-2.5").setScale(0, RoundingMode.HALF_UP).toPlainString())
        assertEquals("-2", d("-2.5").setScale(0, RoundingMode.HALF_EVEN).toPlainString())
        assertEquals("1", d("1e-10").setScale(0, RoundingMode.CEILING).toPlainString())
        assertEquals("-1", d("-1e-10").setScale(0, RoundingMode.FLOOR).toPlainString())
        assertEquals("0", d("1e-10").setScale(0, RoundingMode.FLOOR).toPlainString())
        assertEquals("0", d("0.9e-10").setScale(0, RoundingMode.HALF_UP).toPlainString())
        assertEquals("1200", d("1250").setScale(-2, RoundingMode.HALF_EVEN).toPlainString())
        assertEquals("1300", d("1250").setScale(-2, RoundingMode.HALF_UP).toPlainString())
        assertEquals(3, d("2.5").setScale(3, RoundingMode.HALF_EVEN).scale)
    }

    @Test
    fun remainderHasTheSignOfTheDividend() {
        assertEquals("-1", Decimal.of(-7).remainder(Decimal.of(3)).toPlainString())
        assertEquals("1", Decimal.of(7).remainder(Decimal.of(-3)).toPlainString())
        assertEquals("0.1", d("10.1").remainder(d("0.5")).toPlainString())
    }

    @Test
    fun compareAndEqualityIgnoreTheScale() {
        assertEquals(Decimal.ONE, Decimal.of(10, 1))
        assertEquals(Decimal.ONE.hashCode(), Decimal.of(10, 1).hashCode())
        assertEquals(0, d("2.50").compareTo(d("2.5")))
        assertTrue(d("-3") < d("-2.5"))
        assertTrue(d("0.001") > Decimal.ZERO)
        assertTrue(d("1e3") > d("999.9999"))
    }

    @Test
    fun toDoubleIsTheNearestDouble() {
        assertEquals(0.1, d("0.1").toDouble())
        assertEquals(3.333333333333333, d("3.333333333333333").toDouble())
        assertEquals(1e300, d("1e300").toDouble())
        assertEquals(Double.POSITIVE_INFINITY, d("1e400").toDouble())
        assertEquals(-2.5, d("-2.5").toDouble())
    }

    @Test
    fun adjustedExponentIsThePowerOfTenOfTheFirstDigit() {
        assertEquals(0L, d("1.5").adjustedExponent)
        assertEquals(2L, d("123").adjustedExponent)
        assertEquals(-3L, d("0.00456").adjustedExponent)
        assertEquals(400L, d("1e400").adjustedExponent)
    }
}
