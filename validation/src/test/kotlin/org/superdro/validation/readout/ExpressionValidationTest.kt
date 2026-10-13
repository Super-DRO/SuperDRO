package org.superdro.validation.readout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail
import org.superdro.core.readout.expression.ExpressionEvaluator
import org.superdro.core.readout.expression.ExpressionResult

/**
 * Each row of the example table, each error text, the decimal
 * comma rule, and the numeric compare.
 */
class ExpressionValidationTest {

    private fun value(text: String, current: Double = 0.0): ExpressionResult.Value {
        val result = ExpressionEvaluator.evaluate(text, current)
        return result as? ExpressionResult.Value ?: fail("'$text' gives $result")
    }

    private fun assertValue(expected: Double, text: String, current: Double = 0.0) {
        assertEquals(expected, value(text, current).value, "text '$text'")
    }

    private fun assertText(expected: String, text: String) {
        assertEquals(expected, value(text).text, "text '$text'")
    }

    private fun assertError(expectedMessage: String?, text: String) {
        val result = ExpressionEvaluator.evaluate(text)
        val error = assertIs<ExpressionResult.Error>(result, "text '$text'")
        if (expectedMessage != null) assertEquals(expectedMessage, error.message, "text '$text'")
    }

    // The example table.

    @Test
    fun one_eighth_plus_three_sixteenths_gives_0_3125() = assertValue(0.3125, "1/8 + 3/16")

    @Test
    fun expression_25_4_times_2_gives_50_8() = assertValue(50.8, "25.4*2")

    @Test
    fun expression_10_over_3_gives_16_digits() {
        assertText("3.333333333333333", "10/3")
        assertValue(3.333333333333333, "10/3")
    }

    @Test
    fun power_is_right_associative() = assertValue(512.0, "2^3^2")

    @Test
    fun unary_minus_has_lower_priority_than_power() = assertValue(-4.0, "-2^2")

    @Test
    fun group_of_minus_2_squared_gives_4() = assertValue(4.0, "(-2)^2")

    @Test
    fun negative_exponent_gives_0_25() = assertValue(0.25, "2^-2")

    @Test
    fun square_root_sign_of_16_gives_4() = assertValue(4.0, "√16")

    @Test
    fun expression_2_power_0_5_gives_1_414213562373095() {
        assertText("1.414213562373095", "2^0.5")
        assertValue(1.414213562373095, "2^0.5")
    }

    @Test
    fun modulo_keeps_the_sign_of_the_dividend() {
        assertValue(1.0, "7 % 3")
        assertValue(-1.0, "-7 % 3")
    }

    @Test
    fun round_is_half_even() {
        assertValue(2.0, "round(2.5)")
        assertValue(4.0, "round(3.5)")
        assertValue(2.34, "round(2.345, 2)")
    }

    @Test
    fun floor_and_ceil_of_minus_2_5() {
        assertValue(-3.0, "floor(-2.5)")
        assertValue(-2.0, "ceil(-2.5)")
    }

    @Test
    fun min_max_sum() {
        assertValue(1.0, "min(3, 1, 2)")
        assertValue(3.0, "max(3, 1, 2)")
        assertValue(6.0, "sum(1, 2, 3)")
    }

    @Test
    fun if_with_a_false_condition_gives_the_third_argument() = assertValue(20.0, "if(1 > 2, 10, 20)")

    @Test
    fun expression_1_equals_1_0_gives_1() = assertValue(1.0, "1 == 1.0")

    @Test
    fun PI_times_2_and_names_are_not_case_sensitive() {
        assertValue(6.283185307179586, "PI*2")
        assertValue(3.141592653589793, "pi")
        assertValue(2.718281828459045, "E")
        assertValue(2.0, "ABS(-2)")
    }

    @Test
    fun assignment_returns_the_value() = assertValue(2.0, "x = 2")

    @Test
    fun plus_plus_1_5_at_10_000_gives_11_5() = assertValue(11.5, "++1.5", current = 10.0)

    @Test
    fun minus_minus_x_gives_current_minus_x_and_other_fields_use_0() {
        assertValue(9.5, "--0.5", current = 10.0)
        assertValue(1.5, "++1.5")
    }

    @Test
    fun decimal_comma_1_5_gives_1_5() = assertValue(1.5, "1,5")

    @Test
    fun negative_base_with_a_fractional_exponent_gives_an_error() = assertError(null, "(-8)^(1/3)")

    // Decimal comma rule: outside the parentheses of a function call, a comma between two
    // digits is a decimal mark. Inside them, the comma separates the arguments.

    @Test
    fun comma_in_a_call_separates_the_arguments() {
        assertValue(1.0, "min(3,1,2)")
        assertValue(5.0, "max(1,5)")
    }

    @Test
    fun comma_rule_also_works_with_function_calls() {
        // The comma rule also applies when the text has a function call.
        assertValue(3.5, "1,5+max(1,2)")
        assertValue(3.5, "2,5+1")
    }

    @Test
    fun comma_in_a_group_is_a_decimal_mark_and_a_comma_needs_two_digits() {
        assertValue(1.5, "(1,5)")
        assertValue(1.0, "min(1,5, 2)")
        assertError(null, "1, 5")
    }

    // Numeric compare: 0.0 is false, 1.0 is true.

    @Test
    fun zero_tests_use_the_numeric_value() {
        assertValue(20.0, "if(0.0, 10, 20)")
        assertValue(0.0, "0.0 || 0")
        assertValue(1.0, "1.0 && 2")
        assertValue(0.0, "1 != 1.0")
        assertValue(1.0, "2 >= 2.00")
    }

    // Operators with 16 digits and HALF_EVEN.

    @Test
    fun division_has_16_significant_digits_half_even() {
        assertText("0.6666666666666667", "2/3")
        assertText("0.3333333333333333", "1/3")
    }

    @Test
    fun number_with_an_exponent() {
        assertValue(1000.0, "1e3")
        assertValue(0.25, "2.5E-1")
    }

    @Test
    fun assignment_is_right_associative() = assertValue(3.0, "x = y = 3")

    @Test
    fun square_root_of_a_group_and_integer_powers_of_a_negative_base() {
        assertValue(4.0, "\u221A(9+7)")
        assertValue(-8.0, "(-2)^3")
        assertValue(64.0, "(-8)^2")
    }

    @Test
    fun logical_operators_give_1_or_0() {
        assertValue(1.0, "2 || 0")
        assertValue(0.0, "2 && 0")
        assertValue(1.0, "3 > 2")
        assertValue(0.0, "3 <= 2")
    }

    @Test
    fun a_variable_lives_in_one_expression_only() {
        assertValue(2.0, "x = 2")
        assertError("Undefined variable 'x'", "x")
    }

    @Test
    fun no_unary_plus_and_no_implicit_multiplication() {
        assertError(null, "+2")
        assertError(null, "2pi")
    }

    @Test
    fun division_by_zero_gives_an_error() {
        assertError(null, "1/0")
        assertError(null, "1 % 0")
    }

    // The error texts.

    @Test
    fun invalid_token_texts() {
        assertError("Invalid token '$'", "2 $ 3")
        assertError("Invalid token '!'", "!1")
        assertError("Invalid token '|'", "1 | 2")
        assertError("Invalid token '&'", "1 & 2")
    }

    @Test
    fun abs_requires_one_argument() = assertError("abs requires one argument", "abs(1, 2)")

    @Test
    fun sum_requires_at_least_one_argument() = assertError("sum requires at least one argument", "sum()")

    @Test
    fun floor_requires_one_argument() = assertError("floor requires one argument", "floor(1, 2)")

    @Test
    fun ceil_requires_one_argument() = assertError("ceil requires one argument", "ceil()")

    @Test
    fun round_requires_either_one_or_two_arguments() =
        assertError("round requires either one or two arguments", "round(1, 2, 3)")

    @Test
    fun min_and_max_require_at_least_one_argument() {
        assertError("min requires at least one argument", "min()")
        assertError("max requires at least one argument", "max()")
    }

    @Test
    fun if_requires_three_arguments() = assertError("if requires three arguments", "if(1, 2)")

    @Test
    fun undefined_function_and_variable() {
        assertError("Undefined function 'foo'", "foo(1)")
        assertError("Undefined variable 'y'", "y + 1")
    }

    @Test
    fun invalid_assignment_target() = assertError("Invalid assignment target", "1 = 2")

    @Test
    fun expected_paren_after_function_arguments() =
        assertError("Expected ')' after function arguments", "min(1, 2")

    @Test
    fun expected_end_of_expression() = assertError("Expected end of expression", "1 2")
}
