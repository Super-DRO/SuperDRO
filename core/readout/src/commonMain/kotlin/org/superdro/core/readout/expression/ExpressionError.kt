package org.superdro.core.readout.expression

/**
 * An error of the expression evaluator.
 *
 * [message] is the English text. The UI shows the text of the string resource
 * [key] in the file `strings_expression.xml` of `core:resources`, with [arguments] as the format
 * arguments. The English value of each resource is equal to [message].
 */
sealed class ExpressionError {

    /** The English text. */
    abstract val message: String

    /** The name of the string resource in `strings_expression.xml`. */
    abstract val key: String

    /** The format arguments of the string resource, in the order `%1$s`, `%2$s`. */
    open val arguments: List<String> get() = emptyList()

    /** The lexer found a character that starts no token, or a single `!`, `|`, or `&`. */
    data class InvalidToken(val character: String) : ExpressionError() {
        override val message: String get() = "Invalid token '$character'"
        override val key: String get() = "expression_error_invalid_token"
        override val arguments: List<String> get() = listOf(character)
    }

    /** A number token that is not a number, for example `1.2.3`. */
    data class InvalidNumber(val text: String) : ExpressionError() {
        override val message: String get() = "Invalid number '$text'"
        override val key: String get() = "expression_error_invalid_number"
        override val arguments: List<String> get() = listOf(text)
    }

    /** A call of a name that is not a function. [name] is the name as the text has it. */
    data class UndefinedFunction(val name: String) : ExpressionError() {
        override val message: String get() = "Undefined function '$name'"
        override val key: String get() = "expression_error_undefined_function"
        override val arguments: List<String> get() = listOf(name)
    }

    /** A name that is not a variable or a constant. [name] is the name as the text has it. */
    data class UndefinedVariable(val name: String) : ExpressionError() {
        override val message: String get() = "Undefined variable '$name'"
        override val key: String get() = "expression_error_undefined_variable"
        override val arguments: List<String> get() = listOf(name)
    }

    /** The left side of `=` is not a variable. */
    data object InvalidAssignmentTarget : ExpressionError() {
        override val message: String get() = "Invalid assignment target"
        override val key: String get() = "expression_error_invalid_assignment_target"
    }

    /** A function call has no `)` after the arguments. */
    data object ExpectedParenAfterArguments : ExpressionError() {
        override val message: String get() = "Expected ')' after function arguments"
        override val key: String get() = "expression_error_expected_paren_after_arguments"
    }

    /** A group `( expression` has no `)`. */
    data object ExpectedParenAfterExpression : ExpressionError() {
        override val message: String get() = "Expected ')' after expression"
        override val key: String get() = "expression_error_expected_paren_after_expression"
    }

    /**
     * The text has no expression where the grammar needs one. [after] is the text of the token
     * before that position, or null at the start of the text (for example an empty text).
     */
    data class ExpectedExpression(val after: String?) : ExpressionError() {
        override val message: String
            get() = if (after == null) "Expected expression" else "Expected expression after '$after'"
        override val key: String
            get() = if (after == null) "expression_error_expected_expression" else "expression_error_expected_expression_after"
        override val arguments: List<String> get() = listOfNotNull(after)
    }

    /** The text has more tokens after one complete expression. */
    data object ExpectedEndOfExpression : ExpressionError() {
        override val message: String get() = "Expected end of expression"
        override val key: String get() = "expression_error_expected_end"
    }

    /**
     * A function call has a number of arguments that the function does not accept.
     * [function] is the lower case name, for example "abs".
     */
    data class WrongArgumentCount(val function: String, val arity: Arity) : ExpressionError() {
        override val message: String get() = "$function ${arity.text}"
        override val key: String get() = arity.key
        override val arguments: List<String> get() = listOf(function)
    }

    /** A division or a remainder with a divisor of 0. */
    data object DivisionByZero : ExpressionError() {
        override val message: String get() = "Division by zero"
        override val key: String get() = "expression_error_division_by_zero"
    }

    /** A power with a negative base and an exponent that is not an integer, for example `(-8)^(1/3)`. */
    data object NegativeBaseFractionalExponent : ExpressionError() {
        override val message: String get() = "Fractional power of a negative number"
        override val key: String get() = "expression_error_negative_base_fractional_exponent"
    }

    /** A value is too large for the evaluator or for a `Double`. */
    data object OutOfRange : ExpressionError() {
        override val message: String get() = "Value is out of range"
        override val key: String get() = "expression_error_out_of_range"
    }

    /**
     * The text has too many tokens or too many nested levels. The limits keep the stack of the
     * recursive parser small. A text that a person types does not get near them.
     */
    data object TooComplex : ExpressionError() {
        override val message: String get() = "Expression is too complex"
        override val key: String get() = "expression_error_too_complex"
    }

    /** The numbers of arguments that a function accepts, and the end of the error text. */
    enum class Arity(val text: String, val key: String) {
        /** `abs`, `floor`, `ceil`. */
        ONE("requires one argument", "expression_error_requires_one"),

        /** `sum`, `min`, `max`. */
        AT_LEAST_ONE("requires at least one argument", "expression_error_requires_at_least_one"),

        /** `round`. */
        ONE_OR_TWO("requires either one or two arguments", "expression_error_requires_one_or_two"),

        /** `if`. */
        THREE("requires three arguments", "expression_error_requires_three"),
    }
}

/** The evaluator stops with this exception. [ExpressionEvaluator.evaluate] changes it to [ExpressionResult.Error]. */
internal class ExpressionException(val error: ExpressionError) : Exception(error.message)
