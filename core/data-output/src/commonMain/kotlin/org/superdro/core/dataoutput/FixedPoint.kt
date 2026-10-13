package org.superdro.core.dataoutput

import kotlin.math.abs

/**
 * The number text of the data output. The result is the
 * same as the Java call `String.format(Locale.US, "%.<decimals>f", value)`:
 *
 * - The decimal mark is `.`. There is no group separator and no exponent.
 * - The text has exactly [decimals] digits after the point. With 0 decimals, it has no point.
 * - The round-off is half up (away from zero) on the shortest decimal form of the value, as the
 *   Java `Formatter` does. Thus 1234.5 with 0 decimals gives `1235`, and −1234.5 gives `-1235`.
 * - A negative value keeps its sign also when the text is zero: −0.00001 with 4 decimals gives
 *   `-0.0000`. The value −0.0 is zero, not negative, and gives `0.0000` (a SuperDRO decision).
 *
 * The code is common Kotlin. It reads the digits from [Double.toString], which gives the shortest
 * decimal form that reads back as the same value.
 */
object FixedPoint {

    /** The largest number of decimals that [format] accepts. */
    const val MAX_DECIMALS: Int = 15

    /**
     * Gives [value] as text with [decimals] digits after the point.
     *
     * @throws IllegalArgumentException when [value] is not finite or [decimals] is not in
     *   0 to [MAX_DECIMALS].
     */
    fun format(value: Double, decimals: Int): String {
        require(value.isFinite()) { "The value $value is not finite." }
        require(decimals in 0..MAX_DECIMALS) { "The number of decimals $decimals is not in 0..$MAX_DECIMALS." }
        val negative = value < 0.0
        val decimal = Decimal.of(abs(value))
        val unscaled = decimal.roundedUnscaled(decimals)
        val text = if (decimals == 0) {
            unscaled
        } else {
            val padded = unscaled.padStart(decimals + 1, '0')
            padded.substring(0, padded.length - decimals) + "." + padded.substring(padded.length - decimals)
        }
        return if (negative) "-$text" else text
    }

    /**
     * A positive decimal number `0.<digits> × 10^pointPosition`. [digits] has no zero at the start,
     * or it is empty for the value zero.
     */
    private class Decimal(val digits: String, val pointPosition: Int) {

        /**
         * Gives the digits of `value × 10^decimals`, rounded half up to an integer, with no
         * zero at the start ("0" for zero).
         */
        fun roundedUnscaled(decimals: Int): String {
            if (digits.isEmpty()) return "0"
            val keep = pointPosition + decimals
            val result = when {
                keep >= digits.length -> digits + "0".repeat(keep - digits.length)
                keep < 0 -> "0"
                keep == 0 -> if (digits[0] >= '5') "1" else "0"
                else -> {
                    val head = digits.substring(0, keep)
                    if (digits[keep] >= '5') increment(head) else head
                }
            }
            return result.trimStart('0').ifEmpty { "0" }
        }

        companion object {
            /** Reads [value] (finite, not negative) from its shortest decimal form. */
            fun of(value: Double): Decimal {
                val text = value.toString()
                val exponentAt = text.indexOfFirst { char -> char == 'E' || char == 'e' }
                val mantissa = if (exponentAt < 0) text else text.substring(0, exponentAt)
                val exponent = if (exponentAt < 0) 0 else text.substring(exponentAt + 1).removePrefix("+").toInt()
                val pointAt = mantissa.indexOf('.')
                val integerPart = if (pointAt < 0) mantissa else mantissa.substring(0, pointAt)
                val fractionPart = if (pointAt < 0) "" else mantissa.substring(pointAt + 1)
                val allDigits = integerPart + fractionPart
                var point = integerPart.length + exponent
                var start = 0
                while (start < allDigits.length && allDigits[start] == '0') {
                    start++
                    point--
                }
                val significant = allDigits.substring(start).trimEnd('0')
                return if (significant.isEmpty()) Decimal("", 0) else Decimal(significant, point)
            }

            /** Adds 1 to the decimal integer [digits]. */
            private fun increment(digits: String): String {
                val chars = digits.toCharArray()
                var index = chars.size - 1
                while (index >= 0) {
                    if (chars[index] == '9') {
                        chars[index] = '0'
                        index--
                    } else {
                        chars[index] = chars[index] + 1
                        return chars.concatToString()
                    }
                }
                return "1" + chars.concatToString()
            }
        }
    }
}
