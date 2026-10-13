package org.superdro.core.readout.expression

import kotlin.test.Test

/** The grammar, the operator table, the function table, and the variables. */
class ExpressionGrammarTest {

    @Test
    fun priorityFromTheLowestToTheHighest() {
        assertValue("7", "1 + 2 * 3")
        assertValue("9", "(1 + 2) * 3")
        assertValue("18", "2 * 3 ^ 2")
        assertValue("-4", "1 - 2 - 3")
        assertValue("2", "8 / 2 / 2")
        assertValue("1", "1 < 2 == 1")
        assertValue("1", "1 + 1 == 2 && 3 > 2")
        assertValue("1", "0 && 1 || 1")
        assertValue("3", "x = 1 + 2")
        assertValue("0.0625", "2^-2^2")
        assertValue("-8", "-2^3")
        assertValue("2", "√2^2")
    }

    @Test
    fun additionAndSubtractionAreExact() {
        assertValue("0.3", "0.1 + 0.2")
        assertValue("0.0000000000000001", "0.1234567890123456 - 0.1234567890123455")
        assertValue("12345678901234.5678", "12345678901234 + 0.5678")
    }

    @Test
    fun multiplicationIsExactBeyond16Digits() {
        assertValue("1.5241578750190521", "1.23456789 * 1.23456789")
        assertValue("15241578750190521", "123456789 * 123456789")
    }

    @Test
    fun divisionRoundsTheSeventeenthDigitHalfEven() {
        assertValue("0.6666666666666667", "2/3")
        assertValue("0.1428571428571429", "1/7")
        assertValue("-0.1428571428571429", "-1/7")
        assertValue("3.141592920353982", "355/113")
        assertValue("8.466666666666667", "25.4/3")
        // An exact tie at the 17th digit goes to the even neighbor.
        assertValue("1000000000000000", "(1000000000000000 * 10 + 5) / 10")
        assertValue("1000000000000002", "(1000000000000001 * 10 + 5) / 10")
        // Each quotient has 16 digits before the next operation.
        assertValue("0.9999999999999999", "1/3*3")
        assertValue("0.6666666666666666", "1/3 + 1/3")
    }

    @Test
    fun remainderOfDecimals() {
        assertValue("1.5", "7.5 % 2")
        assertValue("-1.5", "-7.5 % 2")
        assertValue("1", "7 % -3")
        assertValue("0", "6 % 3")
    }

    @Test
    fun comparisonsGiveOneOrZeroAndIgnoreTheScale() {
        assertValue("1", "1 == 1.0")
        assertValue("1", "2.50 == 2.5")
        assertValue("0", "1 != 1.00")
        assertValue("1", "1 != 2")
        assertValue("1", "2 >= 2.0")
        assertValue("1", "2 <= 2.0")
        assertValue("0", "2 > 2.0")
        assertValue("0", "2 < 2.0")
        assertValue("1", "-1 < 0.5")
        assertValue("1", "10 > 9")
    }

    @Test
    fun logicOperatorsTestForZeroByValue() {
        assertValue("1", "1 || 0")
        assertValue("0", "0 || 0")
        assertValue("0", "0.0 || 0")
        assertValue("1", "0.0 || 2")
        assertValue("1", "2 && 3")
        assertValue("0", "1 && 0.0")
        assertValue("0", "0.00 && 1")
        assertValue("2", "if(0.0, 1, 2)")
        assertValue("1", "if(-0.5, 1, 2)")
    }

    @Test
    fun logicOperatorsAndIfEvaluateOnlyTheNecessaryPart() {
        assertValue("1", "1 || 1/0")
        assertValue("0", "0 && 1/0")
        assertValue("5", "if(1, 5, 1/0)")
        assertValue("0", "if(0, 1/0, 0)")
        assertError("Division by zero", "0 || 1/0")
    }

    @Test
    fun functionsAndConstants() {
        assertValue("2.5", "abs(-2.5)")
        assertValue("2.5", "abs(2.5)")
        assertValue("3.141592653589793", "pi")
        assertValue("2.718281828459045", "e")
        assertValue("2.718281828459045", "E")
        assertValue("2", "floor(2.9)")
        assertValue("3", "ceil(2.1)")
        assertValue("5", "floor(5)")
        assertValue("-2", "round(-2.5)")
        assertValue("-4", "round(-3.5)")
        assertValue("3", "round(2.51)")
        assertValue("2.36", "round(2.355, 2)")
        assertValue("2.34", "round(2.345, 2)")
        assertValue("2.5", "round(2.5, 3)")
        assertValue("1200", "round(1250, -2)")
        assertValue("1400", "round(1350, -2)")
        assertValue("0", "round(0.000001, -5)")
        // The places argument is truncated to an integer.
        assertValue("2.34", "round(2.345, 2.9)")
        assertValue("0.1", "sum(0.1)")
        assertValue("0.6", "sum(0.1, 0.2, 0.3)")
        assertValue("-1", "min(2, -1, 0.5)")
        assertValue("7", "max(7)")
        assertValue("10", "if(2, 10, 20)")
    }

    @Test
    fun functionNamesAreNotCaseSensitive() {
        assertValue("2", "ABS(-2)")
        assertValue("3", "Max(1, 3)")
        assertValue("10", "IF(1, 10, 20)")
        assertValue("3.141592653589793", "Pi")
    }

    @Test
    fun variablesLiveInOneExpression() {
        assertValue("9", "(x = 3) * x")
        assertValue("4", "(X = 2) * x")
        assertValue("4", "x = y = 4")
        assertValue("8", "(x = y = 4) + y")
        assertValue("3", "(pi = 3) * 1 + 0 * pi")
        assertValue("2", "x = 2")
        assertError("Undefined variable 'x'", "x")
    }

    @Test
    fun negativeZeroIsZero() {
        assertValue("0", "-0")
        assertValue("0", "-0.0 * 5")
    }
}
