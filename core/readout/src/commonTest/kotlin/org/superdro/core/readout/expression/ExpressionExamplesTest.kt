package org.superdro.core.readout.expression

import kotlin.test.Test

/** The example table of the evaluator. Each result is exact. */
class ExpressionExamplesTest {

    @Test
    fun fractionsAddExactly() {
        assertValue("0.3125", "1/8 + 3/16")
    }

    @Test
    fun multiplicationIsExact() {
        assertValue("50.8", "25.4*2")
    }

    @Test
    fun divisionHas16DigitsHalfEven() {
        assertValue("3.333333333333333", "10/3")
    }

    @Test
    fun powerIsRightAssociative() {
        assertValue("512", "2^3^2")
    }

    @Test
    fun unaryMinusHasALowerPriorityThanPower() {
        assertValue("-4", "-2^2")
        assertValue("4", "(-2)^2")
    }

    @Test
    fun negativeExponentGivesTheReciprocal() {
        assertValue("0.25", "2^-2")
    }

    @Test
    fun squareRootSign() {
        assertValue("4", "√16")
    }

    @Test
    fun fractionalExponentHas16Digits() {
        assertValue("1.414213562373095", "2^0.5")
    }

    @Test
    fun remainderHasTheSignOfTheDividend() {
        assertValue("1", "7 % 3")
        assertValue("-1", "-7 % 3")
    }

    @Test
    fun roundUsesHalfEven() {
        assertValue("2", "round(2.5)")
        assertValue("4", "round(3.5)")
        assertValue("2.34", "round(2.345, 2)")
    }

    @Test
    fun floorAndCeil() {
        assertValue("-3", "floor(-2.5)")
        assertValue("-2", "ceil(-2.5)")
    }

    @Test
    fun minMaxAndSum() {
        assertValue("1", "min(3, 1, 2)")
        assertValue("3", "max(3, 1, 2)")
        assertValue("6", "sum(1, 2, 3)")
    }

    @Test
    fun ifSelectsTheThirdArgumentForAFalseCondition() {
        assertValue("20", "if(1 > 2, 10, 20)")
    }

    @Test
    fun equalityComparesTheValues() {
        assertValue("1", "1 == 1.0")
    }

    @Test
    fun constantNamesAreNotCaseSensitive() {
        assertValue("6.283185307179586", "PI*2")
    }

    @Test
    fun assignmentGivesTheValue() {
        assertValue("2", "x = 2")
    }

    @Test
    fun relativeEntryAddsToThePositionOfTheAxis() {
        assertValue("11.5", "++1.5", current = 10.000)
    }

    @Test
    fun decimalCommaIsADecimalMark() {
        assertValue("1.5", "1,5")
    }

    @Test
    fun negativeBaseWithAFractionalExponentIsAnError() {
        assertError("Fractional power of a negative number", "(-8)^(1/3)")
    }
}
