package org.superdro.core.readout.expression

import kotlin.test.Test

/**
 * The power rule: `a.pow(int(|b|)) × pow(a, frac(|b|))` with 16 digits,
 * and `1 / result` with 16 digits and HALF_UP for b < 0. `√a` is `a ^ 0.5`. The expected values
 * come from `java.math.BigDecimal` with the same steps.
 */
class PowerTest {

    @Test
    fun integerExponent() {
        assertValue("1024", "2^10")
        assertValue("1", "0^0")
        assertValue("0", "0^2")
        assertValue("-8", "(-2)^3")
        assertValue("16", "(-2)^4")
        assertValue("0.001", "10^-3")
        assertValue("7.389056098930649", "e^2")
    }

    @Test
    fun reciprocalUsesHalfUpButDivisionUsesHalfEven() {
        // 1 / 16777216 = 5.9604644775390625E-8: an exact tie at the 17th digit.
        assertValue("0.00000005960464477539063", "2^-24")
        assertValue("0.00000005960464477539062", "1/2^24")
    }

    @Test
    fun fractionalExponent() {
        assertValue("1.414213562373095", "2^0.5")
        assertValue("2.82842712474619", "2^1.5")
        assertValue("1.316074012952492", "3^0.25")
        assertValue("2.755675960631075", "1.5^2.5")
        assertValue("0.7071067811865475", "2^-0.5")
        assertValue("1.071773462536293", "2^0.1")
        assertValue("0", "0^0.5")
    }

    @Test
    fun squareRootIsThePowerOneHalf() {
        assertValue("4", "√16")
        assertValue("1.414213562373095", "√2")
        assertValue("0.5", "√0.25")
        assertValue("1.1", "√1.21")
        assertValue("0.3162277660168379", "√0.1")
        assertValue("3", "√√81")
    }

    @Test
    fun integerPowerHas16Digits() {
        assertValue("12157665459056930000", "3^40")
        assertValue("406561177535215200", "1.5^100")
        assertValue("0.000000000000000002459654426579829", "1.5^-100")
        assertValue("1071508607186267" + "0".repeat(286), "2^1000")
    }

    @Test
    fun largeExponentWithTheWorkingDigits() {
        assertValue("2.718281692544966", "1.0000001^10000000")
        assertValue("2.718281828459048", "1.0000001^10000000.5")
        assertValue("2.718281824381622", "1.000000001^999999999")
        assertValue("0.00000000000000000000000000000000000000000003538527688343442", "0.999^100000")
    }

    @Test
    fun baseOfOneOrMinusOne() {
        assertValue("1", "1^1e300")
        assertValue("-1", "(-1)^1000001")
        assertValue("1", "(-1)^1000000")
        assertValue("1", "1.0^-5")
    }

    @Test
    fun resultsOutsideTheRange() {
        assertValue("0", "0.5^2000")
        assertError("Value is out of range", "0.5^-2000")
        assertError("Value is out of range", "2^1100")
        assertError("Value is out of range", "10^99999999999")
    }

    @Test
    fun negativeBaseNeedsAnIntegerExponent() {
        assertError("Fractional power of a negative number", "(-8)^(1/3)")
        assertError("Fractional power of a negative number", "(-1)^0.5")
        assertError("Fractional power of a negative number", "(-4)^2.5")
    }
}
