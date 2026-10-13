package org.superdro.core.readout.expression

import com.ionspin.kotlin.bignum.integer.BigInteger

/**
 * The round-off modes of the evaluator.
 *
 * The names have the meaning of `java.math.RoundingMode`.
 */
internal enum class RoundingMode {
    /** Toward zero. */
    DOWN,

    /** Toward negative infinity. */
    FLOOR,

    /** Toward positive infinity. */
    CEILING,

    /** To the nearest neighbor. A tie goes away from zero. */
    HALF_UP,

    /** To the nearest neighbor. A tie goes to the even neighbor. */
    HALF_EVEN,
}

/**
 * A decimal number with no limit on the digits: [unscaled] × 10^(−[scale]).
 *
 * Kotlin common code has no `BigDecimal`. This class gives the arithmetic of
 * `java.math.BigDecimal` that the evaluator needs. The integer arithmetic comes from the KMP
 * library `com.ionspin.kotlin:bignum`. The class itself controls each round-off, so that the
 * results agree with `MathContext.DECIMAL64` (16 significant digits, HALF_EVEN).
 *
 * - [plus], [minus], and [times] give the exact result.
 * - [divide] and [round] give a result with a given number of significant digits.
 * - [setScale] gives a result with a given number of digits after the decimal point.
 * - [equals] and [compareTo] compare the values. The scale does not count: 1 is equal to 1.0.
 */
internal class Decimal private constructor(
    val unscaled: BigInteger,
    val scale: Int,
) : Comparable<Decimal> {

    /** −1, 0, or 1. */
    val signum: Int get() = unscaled.signum()

    /** True when the value is 0. */
    val isZero: Boolean get() = unscaled.isZero()

    /** The number of digits of the unscaled value. Zero has 1 digit. */
    val precision: Int get() = digitCount(unscaled)

    /**
     * The power of ten of the first significant digit. For example 0 for 1.5, 2 for 123, and
     * −3 for 0.00456.
     */
    val adjustedExponent: Long get() = precision.toLong() - 1L - scale.toLong()

    /** Gives the exact sum. */
    operator fun plus(other: Decimal): Decimal {
        val target = maxOf(scale, other.scale)
        return Decimal(alignedTo(target) + other.alignedTo(target), target)
    }

    /** Gives the exact difference. */
    operator fun minus(other: Decimal): Decimal {
        val target = maxOf(scale, other.scale)
        return Decimal(alignedTo(target) - other.alignedTo(target), target)
    }

    /** Gives the exact product. */
    operator fun times(other: Decimal): Decimal = Decimal(unscaled * other.unscaled, scale + other.scale)

    /** Gives the value with the opposite sign. */
    operator fun unaryMinus(): Decimal = Decimal(unscaled.negate(), scale)

    /** Gives the absolute value. */
    fun abs(): Decimal = if (signum < 0) -this else this

    /**
     * Gives this value divided by [divisor], with [digits] significant digits and the round-off
     * [mode]. An exact quotient has no trailing zeros after the preferred scale
     * (this scale minus the divisor scale), as `BigDecimal.divide(divisor, MathContext)` gives.
     *
     * The caller must make sure that [divisor] is not 0.
     */
    fun divide(divisor: Decimal, digits: Int, mode: RoundingMode): Decimal {
        require(!divisor.isZero) { "Division by zero" }
        val preferredScale = scale - divisor.scale
        if (isZero) return Decimal(BigInteger.ZERO, preferredScale)
        // The shift gives a quotient with a minimum of digits + 3 digits, so that the round-off
        // has a digit to drop.
        val shift = digits + 3 + divisor.precision - precision
        var numerator = unscaled.abs()
        var denominator = divisor.unscaled.abs()
        if (shift >= 0) {
            numerator *= tenPow(shift)
        } else {
            denominator *= tenPow(-shift)
        }
        val quotient = numerator.divrem(denominator)
        val rounded = roundMagnitude(
            magnitude = quotient.quotient,
            scale = preferredScale + shift,
            digits = digits,
            mode = mode,
            sticky = !quotient.remainder.isZero(),
            negative = signum != divisor.signum,
        )
        return rounded.stripTrailingZeros(minimumScale = preferredScale)
    }

    /**
     * Gives the remainder of the division by [divisor] with a quotient that goes toward zero.
     * The sign of the result is the sign of this value. Thus −7 % 3 gives −1. The result is exact.
     *
     * The caller must make sure that [divisor] is not 0.
     */
    fun remainder(divisor: Decimal): Decimal {
        require(!divisor.isZero) { "Division by zero" }
        val target = maxOf(scale, divisor.scale)
        // The magnitudes give the remainder. The sign of the dividend goes on the result.
        val magnitude = alignedTo(target).abs().rem(divisor.alignedTo(target).abs())
        return Decimal(if (signum < 0) magnitude.negate() else magnitude, target)
    }

    /** Gives the value with [digits] significant digits and the round-off [mode]. */
    fun round(digits: Int, mode: RoundingMode): Decimal = roundMagnitude(
        magnitude = unscaled.abs(),
        scale = scale,
        digits = digits,
        mode = mode,
        sticky = false,
        negative = signum < 0,
    )

    /**
     * Gives the value with [newScale] digits after the decimal point and the round-off [mode].
     * A negative [newScale] rounds to a power of ten, for example −2 rounds to hundreds.
     */
    fun setScale(newScale: Int, mode: RoundingMode): Decimal {
        if (newScale >= scale) return Decimal(alignedTo(newScale), newScale)
        val drop = scale.toLong() - newScale.toLong()
        val magnitude = unscaled.abs()
        val negative = signum < 0
        if (drop > precision.toLong() + 1L) {
            // The magnitude is less than a tenth of the last digit that stays. Only the modes that
            // go away from zero can give a digit.
            val awayFromZero = !isZero && when (mode) {
                RoundingMode.FLOOR -> negative
                RoundingMode.CEILING -> !negative
                else -> false
            }
            val digit = if (awayFromZero) BigInteger.ONE else BigInteger.ZERO
            return Decimal(if (negative) digit.negate() else digit, newScale)
        }
        val divisor = tenPow(drop.toInt())
        val parts = magnitude.divrem(divisor)
        val up = roundsUp(parts.quotient, parts.remainder, divisor, sticky = false, mode, negative)
        val result = if (up) parts.quotient + BigInteger.ONE else parts.quotient
        return Decimal(if (negative) result.negate() else result, newScale)
    }

    /** Gives the exact power for an exponent of 0 or more. */
    fun pow(exponent: Int): Decimal {
        require(exponent >= 0) { "The exponent must be 0 or more." }
        return Decimal(unscaled.pow(exponent), scale * exponent)
    }

    /** Gives the integer part. The digits after the decimal point go away (round-off toward zero). */
    fun integerPart(): BigInteger = setScale(0, RoundingMode.DOWN).unscaled

    /** Gives the value without trailing zeros after the decimal point, down to [minimumScale]. */
    fun stripTrailingZeros(minimumScale: Int = Int.MIN_VALUE): Decimal {
        if (isZero) return Decimal(BigInteger.ZERO, maxOf(0, minimumScale))
        var value = unscaled
        var newScale = scale
        while (newScale > minimumScale) {
            val parts = value.divrem(BigInteger.TEN)
            if (!parts.remainder.isZero()) break
            value = parts.quotient
            newScale--
        }
        return Decimal(value, newScale)
    }

    /**
     * Gives the nearest `Double`. A value outside the range of `Double` gives an infinity. The
     * platform parser does the round-off, so the result is the correctly rounded `Double`.
     */
    fun toDouble(): Double = "${unscaled}E${-scale.toLong()}".toDouble()

    /**
     * Gives the value in plain notation, without an exponent and without trailing zeros after the
     * decimal point. Examples: "512", "-4", "0.25", "3.333333333333333", "1200".
     */
    fun toPlainString(): String {
        if (isZero) return "0"
        val stripped = stripTrailingZeros(minimumScale = 0)
        val digits = stripped.unscaled.abs().toString()
        val body = when {
            stripped.scale <= 0 -> digits + "0".repeat(-stripped.scale)
            digits.length > stripped.scale ->
                digits.substring(0, digits.length - stripped.scale) + "." +
                    digits.substring(digits.length - stripped.scale)
            else -> "0." + "0".repeat(stripped.scale - digits.length) + digits
        }
        return if (stripped.signum < 0) "-$body" else body
    }

    override fun compareTo(other: Decimal): Int {
        if (signum != other.signum) return signum.compareTo(other.signum)
        val target = maxOf(scale, other.scale)
        return alignedTo(target).compareTo(other.alignedTo(target))
    }

    /** Two values are equal when [compareTo] gives 0. The scale does not count. */
    override fun equals(other: Any?): Boolean = other is Decimal && compareTo(other) == 0

    override fun hashCode(): Int = toPlainString().hashCode()

    override fun toString(): String = toPlainString()

    /** Gives the unscaled value for [target] digits after the decimal point. [target] is not less than [scale]. */
    private fun alignedTo(target: Int): BigInteger =
        if (target == scale) unscaled else unscaled * tenPow(target - scale)

    companion object {
        val ZERO: Decimal = Decimal(BigInteger.ZERO, 0)
        val ONE: Decimal = Decimal(BigInteger.ONE, 0)

        /** Gives the decimal of an integer. */
        fun of(value: Long): Decimal = Decimal(BigInteger.fromLong(value), 0)

        /** Gives the decimal of an integer. */
        fun of(value: BigInteger): Decimal = Decimal(value, 0)

        /** Gives the decimal [unscaled] × 10^(−[scale]). */
        fun of(unscaled: Long, scale: Int): Decimal = Decimal(BigInteger.fromLong(unscaled), scale)

        /**
         * Reads a number in the form `digits[.digits][(e|E)[+|-]digits]`, with an optional sign
         * at the start. A text that starts or ends with the decimal point is permitted ("5.", ".5").
         * Gives null for a different text.
         */
        fun parse(text: String): Decimal? {
            var index = 0
            var negative = false
            if (index < text.length && (text[index] == '-' || text[index] == '+')) {
                negative = text[index] == '-'
                index++
            }
            val digits = StringBuilder()
            var fractionDigits = 0
            var point = false
            while (index < text.length) {
                val char = text[index]
                when {
                    char in '0'..'9' -> {
                        digits.append(char)
                        if (point) fractionDigits++
                    }
                    char == '.' && !point -> point = true
                    else -> break
                }
                index++
            }
            if (digits.isEmpty()) return null
            var exponent = 0L
            if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
                index++
                var exponentNegative = false
                if (index < text.length && (text[index] == '-' || text[index] == '+')) {
                    exponentNegative = text[index] == '-'
                    index++
                }
                val start = index
                while (index < text.length && text[index] in '0'..'9') {
                    // The value stops at a limit far outside the range of the evaluator. Thus the
                    // value cannot overflow, and the range check refuses the number later.
                    if (exponent < EXPONENT_TEXT_LIMIT) exponent = exponent * 10 + (text[index] - '0')
                    index++
                }
                if (index == start) return null
                if (exponentNegative) exponent = -exponent
            }
            if (index != text.length) return null
            val magnitude = BigInteger.parseString(digits.toString(), 10)
            return Decimal(if (negative) magnitude.negate() else magnitude, (fractionDigits - exponent).toInt())
        }

        /**
         * Gives the exact value of a finite `Double`, as `new BigDecimal(double)` gives. For
         * example 0.1 gives 0.1000000000000000055511151231257827021181583404541015625.
         */
        fun exactOf(value: Double): Decimal {
            require(value.isFinite()) { "The value must be finite." }
            if (value == 0.0) return ZERO
            val bits = value.toRawBits()
            val exponentBits = ((bits ushr 52) and 0x7FFL).toInt()
            var mantissa = bits and 0xFFFFFFFFFFFFFL
            var exponent = if (exponentBits == 0) {
                -1074
            } else {
                mantissa = mantissa or (1L shl 52)
                exponentBits - 1075
            }
            while (exponent < 0 && (mantissa and 1L) == 0L) {
                mantissa = mantissa shr 1
                exponent++
            }
            val signed = BigInteger.fromLong(if (bits < 0) -mantissa else mantissa)
            return if (exponent >= 0) {
                Decimal(signed * BigInteger.TWO.pow(exponent), 0)
            } else {
                Decimal(signed * BigInteger.fromInt(5).pow(-exponent), -exponent)
            }
        }

        /**
         * Gives the shortest decimal that gives the same `Double` again, for example 0.1 for the
         * `Double` 0.1. The value must be finite.
         */
        fun shortestOf(value: Double): Decimal {
            require(value.isFinite()) { "The value must be finite." }
            return parse(value.toString()) ?: exactOf(value)
        }

        /** The limit of the exponent digits that [parse] reads. */
        private const val EXPONENT_TEXT_LIMIT = 100_000_000L

        private fun tenPow(exponent: Int): BigInteger = BigInteger.TEN.pow(exponent)

        private fun digitCount(value: BigInteger): Int =
            if (value.isZero()) 1 else value.abs().toString().length

        /**
         * Rounds the magnitude [magnitude] × 10^(−[scale]) to [digits] significant digits. [sticky]
         * tells that the exact value has more nonzero digits after [magnitude].
         */
        private fun roundMagnitude(
            magnitude: BigInteger,
            scale: Int,
            digits: Int,
            mode: RoundingMode,
            sticky: Boolean,
            negative: Boolean,
        ): Decimal {
            var value = magnitude
            var newScale = scale
            if (digitCount(value) <= digits && sticky) {
                // Add one zero digit, so that the sticky digits have a position to round.
                value *= BigInteger.TEN
                newScale++
            }
            val drop = digitCount(value) - digits
            if (drop > 0) {
                val divisor = tenPow(drop)
                val parts = value.divrem(divisor)
                val up = roundsUp(parts.quotient, parts.remainder, divisor, sticky, mode, negative)
                value = if (up) parts.quotient + BigInteger.ONE else parts.quotient
                newScale -= drop
                if (digitCount(value) > digits) {
                    // 99...9 + 1 gives 10^digits. The last digit is 0, so the division is exact.
                    value = value.divide(BigInteger.TEN)
                    newScale--
                }
            }
            return Decimal(if (negative) value.negate() else value, newScale)
        }

        /**
         * Tells if the round-off adds one to [quotient]. [remainder] is the part that goes away, and
         * [divisor] is the power of ten of that part.
         */
        private fun roundsUp(
            quotient: BigInteger,
            remainder: BigInteger,
            divisor: BigInteger,
            sticky: Boolean,
            mode: RoundingMode,
            negative: Boolean,
        ): Boolean {
            if (remainder.isZero() && !sticky) return false
            return when (mode) {
                RoundingMode.DOWN -> false
                RoundingMode.FLOOR -> negative
                RoundingMode.CEILING -> !negative
                RoundingMode.HALF_UP, RoundingMode.HALF_EVEN -> {
                    val half = (remainder * BigInteger.TWO).compareTo(divisor)
                    when {
                        half > 0 -> true
                        half < 0 -> false
                        sticky -> true
                        mode == RoundingMode.HALF_UP -> true
                        else -> !quotient.rem(BigInteger.TWO).isZero()
                    }
                }
            }
        }
    }
}
