package org.superdro.core.readout.expression

import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.math.log10
import kotlin.math.pow

/**
 * The range of the values in the evaluator.
 *
 * A value with a power of ten above [MAX_EXPONENT] gives [ExpressionError.OutOfRange]. Such a
 * value is outside the range of `Double` (about 1.8 × 10^308). A value with a power of ten
 * below [MIN_EXPONENT] becomes 0, as the nearest `Double` of such a value is 0. The limits also
 * keep the number of digits small, so that no operation can use all the time or the memory.
 */
internal object Range {
    const val MAX_EXPONENT = 400L
    const val MIN_EXPONENT = -400L

    /** Gives [value], or 0 for a value that is too small. A value that is too large gives an error. */
    fun checked(value: Decimal): Decimal {
        if (value.isZero) return value
        val exponent = value.adjustedExponent
        if (exponent > MAX_EXPONENT) throw ExpressionException(ExpressionError.OutOfRange)
        return if (exponent < MIN_EXPONENT) Decimal.ZERO else value
    }
}

/**
 * The evaluator of a syntax tree (operator table and function table).
 *
 * One instance evaluates one expression. Thus a variable lives in one expression only. Names
 * are not case sensitive.
 */
internal class Interpreter {

    private val variables = HashMap<String, Decimal>()

    fun evaluate(expression: Expr): Decimal = when (expression) {
        is Expr.Literal -> expression.value
        is Expr.Grouping -> evaluate(expression.inner)
        is Expr.Variable -> variable(expression.name)
        is Expr.Assign -> evaluate(expression.value).also { value ->
            variables[expression.name.lowercase()] = value
        }
        is Expr.Unary -> unary(expression.operator, evaluate(expression.operand))
        is Expr.Binary -> binary(evaluate(expression.left), expression.operator, evaluate(expression.right))
        is Expr.Logical -> logical(expression)
        is Expr.Call -> call(expression)
    }

    private fun variable(name: String): Decimal {
        val key = name.lowercase()
        return variables[key] ?: CONSTANTS[key] ?: fail(ExpressionError.UndefinedVariable(name))
    }

    private fun unary(operator: TokenType, operand: Decimal): Decimal = when (operator) {
        TokenType.MINUS -> -operand
        TokenType.SQUARE_ROOT -> power(operand, HALF)
        else -> error("The parser gives no unary operator $operator.")
    }

    private fun binary(left: Decimal, operator: TokenType, right: Decimal): Decimal = when (operator) {
        TokenType.PLUS -> Range.checked(left + right)
        TokenType.MINUS -> Range.checked(left - right)
        TokenType.STAR -> Range.checked(left * right)
        TokenType.SLASH -> {
            if (right.isZero) fail(ExpressionError.DivisionByZero)
            Range.checked(left.divide(right, DIGITS, RoundingMode.HALF_EVEN))
        }
        TokenType.MODULO -> {
            if (right.isZero) fail(ExpressionError.DivisionByZero)
            Range.checked(left.remainder(right).round(DIGITS, RoundingMode.HALF_EVEN))
        }
        TokenType.EXPONENT -> power(left, right)
        // The compare uses the values. The scale does not count, so 1 == 1.0 gives 1.
        TokenType.EQUAL_EQUAL -> truth(left.compareTo(right) == 0)
        TokenType.NOT_EQUAL -> truth(left.compareTo(right) != 0)
        TokenType.GREATER -> truth(left > right)
        TokenType.GREATER_EQUAL -> truth(left >= right)
        TokenType.LESS -> truth(left < right)
        TokenType.LESS_EQUAL -> truth(left <= right)
        else -> error("The parser gives no binary operator $operator.")
    }

    /** `||` and `&&` give 1 or 0. The right side is evaluated only when it can change the result. */
    private fun logical(expression: Expr.Logical): Decimal {
        val left = isTrue(evaluate(expression.left))
        return when (expression.operator) {
            TokenType.OR -> truth(left || isTrue(evaluate(expression.right)))
            TokenType.AND -> truth(left && isTrue(evaluate(expression.right)))
            else -> error("The parser gives no logical operator ${expression.operator}.")
        }
    }

    private fun call(call: Expr.Call): Decimal {
        // The error texts of the argument count use the lower case name, for example
        // "abs requires one argument" also for `ABS()`.
        val name = call.name.lowercase()
        val arguments = call.arguments
        return when (name) {
            "abs" -> {
                requireArity(name, arguments.size == 1, ExpressionError.Arity.ONE)
                evaluate(arguments[0]).abs()
            }
            "sum" -> {
                requireArity(name, arguments.isNotEmpty(), ExpressionError.Arity.AT_LEAST_ONE)
                arguments.map(::evaluate).reduce { total, value -> Range.checked(total + value) }
            }
            // The error texts of floor and ceil name the function.
            "floor" -> {
                requireArity(name, arguments.size == 1, ExpressionError.Arity.ONE)
                evaluate(arguments[0]).setScale(0, RoundingMode.FLOOR)
            }
            "ceil" -> {
                requireArity(name, arguments.size == 1, ExpressionError.Arity.ONE)
                evaluate(arguments[0]).setScale(0, RoundingMode.CEILING)
            }
            "round" -> {
                requireArity(name, arguments.size == 1 || arguments.size == 2, ExpressionError.Arity.ONE_OR_TWO)
                val value = evaluate(arguments[0])
                val places = if (arguments.size == 2) places(evaluate(arguments[1])) else 0
                if (places >= value.scale) value else value.setScale(places, RoundingMode.HALF_EVEN)
            }
            "min" -> {
                requireArity(name, arguments.isNotEmpty(), ExpressionError.Arity.AT_LEAST_ONE)
                arguments.map(::evaluate).reduce { smallest, value -> if (value < smallest) value else smallest }
            }
            "max" -> {
                requireArity(name, arguments.isNotEmpty(), ExpressionError.Arity.AT_LEAST_ONE)
                arguments.map(::evaluate).reduce { largest, value -> if (value > largest) value else largest }
            }
            // The text "if requires three arguments" is new in SuperDRO. Only the selected
            // argument is evaluated.
            "if" -> {
                requireArity(name, arguments.size == 3, ExpressionError.Arity.THREE)
                if (isTrue(evaluate(arguments[0]))) evaluate(arguments[1]) else evaluate(arguments[2])
            }
            else -> fail(ExpressionError.UndefinedFunction(call.name))
        }
    }

    /**
     * Gives the integer part of the second argument of `round`. The value stops at limits that
     * give the same result as a larger value: a value has fewer than [MAX_PLACES] digits after
     * the decimal point, and less than 10^[Range.MAX_EXPONENT] + 1.
     */
    private fun places(value: Decimal): Int {
        val integer = value.integerPart()
        return when {
            integer > BigInteger.fromInt(MAX_PLACES) -> MAX_PLACES
            integer < BigInteger.fromInt(-MAX_PLACES) -> -MAX_PLACES
            else -> integer.intValue(true)
        }
    }

    private fun requireArity(name: String, accepted: Boolean, arity: ExpressionError.Arity) {
        if (!accepted) fail(ExpressionError.WrongArgumentCount(name, arity))
    }

    companion object {
        /** The significant digits of `/`, `%`, and `^` (`MathContext.DECIMAL64`). */
        const val DIGITS = 16

        /** The working digits of a power with a large exponent. */
        private const val WORK_DIGITS = 40

        /** The maximum of digits of an exact power `a.pow(n)`. A larger power uses [WORK_DIGITS]. */
        private const val EXACT_POWER_DIGITS = 2000

        /** See [places]. */
        private const val MAX_PLACES = 100_000

        private val HALF: Decimal = Decimal.of(5, 1)

        /** The constants. The names are lower case. */
        private val CONSTANTS: Map<String, Decimal> = mapOf(
            "pi" to requireNotNull(Decimal.parse("3.141592653589793")),
            "e" to requireNotNull(Decimal.parse("2.718281828459045")),
        )

        private fun truth(value: Boolean): Decimal = if (value) Decimal.ONE else Decimal.ZERO

        /** A value is true when it is not equal to 0 in a numeric compare. Thus 0.0 is false. */
        private fun isTrue(value: Decimal): Boolean = !value.isZero

        private fun fail(error: ExpressionError): Nothing = throw ExpressionException(error)

        /**
         * Gives `a ^ b`: `a.pow(int(|b|)) × pow(a, frac(|b|))`, with 16
         * digits and HALF_EVEN. If b < 0, the result is `1 / result` with 16 digits and HALF_UP.
         *
         * `pow(a, frac(|b|))` is the `Double` power. A negative base with a fractional exponent
         * gives an error, as does a result of 0 for b < 0.
         */
        fun power(base: Decimal, exponent: Decimal): Decimal {
            val magnitude = exponent.abs()
            val whole = magnitude.integerPart()
            val fraction = magnitude - Decimal.of(whole)
            if (base.signum < 0 && !fraction.isZero) fail(ExpressionError.NegativeBaseFractionalExponent)
            val wholePart = wholePower(base, whole)
            val fractionPart = if (fraction.isZero) {
                Decimal.ONE
            } else {
                val double = base.toDouble().pow(fraction.toDouble())
                when {
                    double.isNaN() -> fail(ExpressionError.NegativeBaseFractionalExponent)
                    double.isInfinite() -> fail(ExpressionError.OutOfRange)
                    else -> Decimal.exactOf(double)
                }
            }
            val result = Range.checked((wholePart * fractionPart).round(DIGITS, RoundingMode.HALF_EVEN))
            if (exponent.signum >= 0) return result
            if (result.isZero) fail(if (base.isZero) ExpressionError.DivisionByZero else ExpressionError.OutOfRange)
            return Range.checked(Decimal.ONE.divide(result, DIGITS, RoundingMode.HALF_UP))
        }

        /** Gives `base ^ exponent` for an integer exponent of 0 or more. */
        private fun wholePower(base: Decimal, exponent: BigInteger): Decimal {
            if (exponent.isZero()) return Decimal.ONE
            if (base.isZero) return Decimal.ZERO
            if (base.abs() == Decimal.ONE) {
                val odd = !exponent.rem(BigInteger.TWO).isZero()
                return if (base.signum < 0 && odd) -Decimal.ONE else Decimal.ONE
            }
            // The power of ten of the result, as an estimate. The check refuses a result outside
            // the range before the calculation.
            val estimate = exponent.doubleValue(false) * log10Abs(base)
            if (estimate > Range.MAX_EXPONENT + 1) fail(ExpressionError.OutOfRange)
            if (estimate < Range.MIN_EXPONENT - 1) return Decimal.ZERO
            val exactDigits = BigInteger.fromInt(EXACT_POWER_DIGITS / base.precision)
            if (exponent <= exactDigits) return Range.checked(base.pow(exponent.intValue(true)))
            // A large exponent: the square-and-multiply method with WORK_DIGITS digits. The
            // round-off errors stay far below the 16 digits of the result.
            var result = Decimal.ONE
            var square = base
            val bits = exponent.bitLength()
            for (bit in 0 until bits) {
                if (exponent.bitAt(bit.toLong())) result = (result * square).round(WORK_DIGITS, RoundingMode.HALF_EVEN)
                if (bit < bits - 1) square = (square * square).round(WORK_DIGITS, RoundingMode.HALF_EVEN)
            }
            return Range.checked(result)
        }

        /** Gives log10(|value|) for a value that is not 0. The value can be outside the range of `Double`. */
        private fun log10Abs(value: Decimal): Double {
            val digits = value.unscaled.abs().toString()
            val leading = digits.take(LEADING_DIGITS)
            return log10(leading.toDouble()) + (digits.length - leading.length) - value.scale
        }

        private const val LEADING_DIGITS = 17
    }
}
