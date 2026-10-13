package org.superdro.core.readout.expression

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The error texts and the other errors of the evaluator. */
class ExpressionErrorTest {

    @Test
    fun invalidTokenNamesTheCharacter() {
        assertError("Invalid token '#'", "2 # 3")
        assertError("Invalid token '\n'", "1\n+2")
        assertError("Invalid token '.'", "2 + .")
    }

    @Test
    fun undefinedFunction() {
        assertError("Undefined function 'foo'", "foo(1)")
        assertError("Undefined function 'pi'", "pi(2)")
    }

    @Test
    fun undefinedVariable() {
        assertError("Undefined variable 'x'", "x + 1")
        assertError("Undefined variable 'abs'", "abs")
        // The right side is evaluated before the assignment.
        assertError("Undefined variable 'x'", "x = x + 1")
    }

    @Test
    fun invalidAssignmentTarget() {
        assertError("Invalid assignment target", "2 = 3")
        assertError("Invalid assignment target", "x + 1 = 3")
        assertError("Invalid assignment target", "(x) = 3")
    }

    @Test
    fun expectedParenAfterFunctionArguments() {
        assertError("Expected ')' after function arguments", "min(1, 2")
        assertError("Expected ')' after function arguments", "min(1 2)")
    }

    @Test
    fun expectedEndOfExpression() {
        assertError("Expected end of expression", "2 3")
        assertError("Expected end of expression", "2)")
        // No implicit multiplication.
        assertError("Expected end of expression", "2pi")
        assertError("Expected end of expression", "2(3)")
    }

    @Test
    fun argumentCountOfEachFunction() {
        assertError("abs requires one argument", "abs()")
        assertError("abs requires one argument", "abs(1, 2)")
        assertError("sum requires at least one argument", "sum()")
        assertError("floor requires one argument", "floor()")
        assertError("floor requires one argument", "floor(1, 2)")
        assertError("ceil requires one argument", "ceil()")
        assertError("ceil requires one argument", "ceil(1, 2)")
        assertError("round requires either one or two arguments", "round()")
        assertError("round requires either one or two arguments", "round(1, 2, 3)")
        assertError("min requires at least one argument", "min()")
        assertError("max requires at least one argument", "max()")
        assertError("if requires three arguments", "if(1, 2)")
        assertError("if requires three arguments", "if(1, 2, 3, 4)")
    }

    @Test
    fun argumentCountErrorUsesTheLowerCaseName() {
        assertError("abs requires one argument", "ABS()")
        assertError("if requires three arguments", "If(1)")
        // The name of an undefined function stays as the text has it.
        assertError("Undefined function 'Foo'", "Foo(1)")
    }

    @Test
    fun divisionByZero() {
        assertError("Division by zero", "1/0")
        assertError("Division by zero", "1/(2 - 2.0)")
        assertError("Division by zero", "5 % 0")
        assertError("Division by zero", "0^-1")
    }

    @Test
    fun negativeBaseWithAFractionalExponent() {
        assertError("Fractional power of a negative number", "(-8)^(1/3)")
        assertError("Fractional power of a negative number", "√-4")
        assertError("Fractional power of a negative number", "(-2)^-0.5")
    }

    @Test
    fun missingExpression() {
        assertError("Expected expression", "")
        assertError("Expected expression", " \t")
        assertError("Expected expression", "+5")
        assertError("Expected expression", ")")
        assertError("Expected expression after '*'", "2*")
        assertError("Expected expression after '+'", "5++3")
        assertError("Expected expression after ','", "min(1,)")
    }

    @Test
    fun missingParenAfterAGroup() {
        assertError("Expected ')' after expression", "(1 + 2")
    }

    @Test
    fun invalidNumber() {
        assertError("Invalid number '1.2.3'", "1.2.3")
        assertError("Invalid number '1,5,3'", "1,5,3")
    }

    @Test
    fun valueOutOfRange() {
        assertError("Value is out of range", "1e999")
        assertError("Value is out of range", "10^400")
        assertError("Value is out of range", "1e300 * 1e300")
        // Inside the range of the evaluator, but outside the range of Double.
        assertError("Value is out of range", "1e308 * 10")
    }

    @Test
    fun tooManyLevelsOrTokens() {
        assertError("Expression is too complex", "(".repeat(150) + "1" + ")".repeat(150))
        assertError("Expression is too complex", "0" + "-".repeat(250) + "1")
        assertError("Expression is too complex", List(600) { "1" }.joinToString("+"))
        assertValue("1", "(".repeat(50) + "1" + ")".repeat(50))
        assertValue("300", List(300) { "1" }.joinToString("+"))
    }

    @Test
    fun eachErrorHasAResourceKeyAndArguments() {
        val result = ExpressionEvaluator.evaluate("foo(1)")
        assertIs<ExpressionResult.Error>(result)
        assertEquals(ExpressionError.UndefinedFunction("foo"), result.error)
        assertEquals("expression_error_undefined_function", result.error.key)
        assertEquals(listOf("foo"), result.error.arguments)

        val arity = ExpressionError.WrongArgumentCount("if", ExpressionError.Arity.THREE)
        assertEquals("if requires three arguments", arity.message)
        assertEquals("expression_error_requires_three", arity.key)
        assertEquals(listOf("if"), arity.arguments)

        assertEquals("expression_error_expected_expression", ExpressionError.ExpectedExpression(null).key)
        assertEquals(emptyList(), ExpressionError.ExpectedExpression(null).arguments)
        assertEquals("expression_error_expected_expression_after", ExpressionError.ExpectedExpression("*").key)
        assertEquals(listOf("*"), ExpressionError.ExpectedExpression("*").arguments)
    }
}
