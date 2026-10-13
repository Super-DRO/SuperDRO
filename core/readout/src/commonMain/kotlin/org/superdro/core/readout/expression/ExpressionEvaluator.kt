package org.superdro.core.readout.expression

/**
 * The expression evaluator of the numeric fields.
 *
 * The keypad and the import code call [evaluate]. The callers use
 * [ExpressionResult.Value.value] as a `Double` in display units.
 *
 * The evaluator uses decimal arithmetic:
 * - `+`, `-`, and `*` give the exact result.
 * - `/` and `%` give 16 significant digits with HALF_EVEN (as `MathContext.DECIMAL64`).
 * - `a ^ b` gives `a.pow(int(|b|)) × pow(a, frac(|b|))`. For b < 0 it gives `1 / result` with
 *   16 digits and HALF_UP. `√a` is `a ^ 0.5`.
 * - A number in the text has 16 significant digits with HALF_EVEN.
 * - The compare operators and `==` compare the values: `1 == 1.0` gives 1.
 *
 * Example: `evaluate("10/3")` gives the text "3.333333333333333". `evaluate("++1.5", 10.0)` gives 11.5.
 */
object ExpressionEvaluator {

    /** The prefix of a relative entry that adds to the current value. */
    const val ADD_PREFIX: String = "++"

    /** The prefix of a relative entry that subtracts from the current value. */
    const val SUBTRACT_PREFIX: String = "--"

    /**
     * Evaluates [text].
     *
     * Relative entry: a text that starts with `++x` gives `current + x`, and a text that
     * starts with `--x` gives `current − x`. Space, `\r`, and `\t` before the prefix are ignored.
     *
     * @param text The text of the field. The whole text must be one expression.
     * @param current The axis position in display units for a position field, and 0 for the
     *   other fields. Only a relative entry uses it.
     * @return The value, or the error with its text. A value is always a finite `Double`.
     */
    fun evaluate(text: String, current: Double = 0.0): ExpressionResult = try {
        val start = text.trimStart(' ', '\r', '\t')
        val value = when {
            start.startsWith(ADD_PREFIX) -> currentValue(current) + evaluateDecimal(start.substring(ADD_PREFIX.length))
            start.startsWith(SUBTRACT_PREFIX) ->
                currentValue(current) - evaluateDecimal(start.substring(SUBTRACT_PREFIX.length))
            else -> evaluateDecimal(text)
        }
        result(Range.checked(value))
    } catch (exception: ExpressionException) {
        ExpressionResult.Error(exception.error)
    }

    /** Evaluates one expression with no relative entry. */
    internal fun evaluateDecimal(text: String): Decimal {
        val tree = Parser(Lexer.scan(text)).parse()
        return Interpreter().evaluate(tree)
    }

    /** Gives the decimal of [current]: the shortest decimal that gives the same `Double`. */
    private fun currentValue(current: Double): Decimal {
        if (!current.isFinite()) throw ExpressionException(ExpressionError.OutOfRange)
        return Decimal.shortestOf(current)
    }

    private fun result(value: Decimal): ExpressionResult {
        val double = value.toDouble()
        if (!double.isFinite()) return ExpressionResult.Error(ExpressionError.OutOfRange)
        // A very small negative value gives the Double −0.0. The callers get 0.0.
        return ExpressionResult.Value(value.toPlainString(), if (double == 0.0) 0.0 else double)
    }
}
