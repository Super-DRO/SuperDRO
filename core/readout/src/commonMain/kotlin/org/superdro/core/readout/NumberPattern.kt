package org.superdro.core.readout

import kotlin.math.floor
import kotlin.math.pow

/**
 * A number pattern of the readout, for example `##0.0000`.
 *
 * The rules of the text:
 * - The number of `0` and `#` characters after the `.` gives the decimals.
 * - The number of `0` characters before the `.` gives the minimum count of integer digits.
 * - The decimal mark is `.`, the minus sign is `-`, and the text has no group separator.
 * - The round-off goes half up toward +∞, as Java `Math.round`: 12345.5 gives 12346 and
 *   −12345.5 gives −12345.
 * - The text never shows "-0.0000", because the round-off gives the integer 0.
 * - A value that is not finite gives the pattern with `-` for each `#` and `0`, for example
 *   `---.----`.
 *
 * SuperDRO uses `Double` and `Long`, so the text has no `int` limit.
 */
class NumberPattern(val pattern: String) {

    init {
        require(pattern.isNotEmpty() && pattern.all { character -> character == '#' || character == '0' || character == '.' }) {
            "The pattern '$pattern' can contain only '#', '0', and '.'."
        }
        require(pattern.count { character -> character == '.' } <= 1) { "The pattern '$pattern' has more than one '.'." }
    }

    /** The number of digits after the decimal mark. */
    val decimals: Int = if ('.' in pattern) pattern.substringAfter('.').length else 0

    private val minimumIntegerDigits: Int = pattern.substringBefore('.').count { character -> character == '0' }.coerceAtLeast(1)

    private val scale: Double = 10.0.pow(decimals)

    /** The text for a value that is not finite: each `#` and `0` changes to `-`. */
    val notFiniteText: String = pattern.map { character -> if (character == '.') '.' else '-' }.joinToString("")

    /** Gives the text of [value]. */
    fun format(value: Double): String {
        if (!value.isFinite()) return notFiniteText
        val scaled = roundHalfUp(value * scale)
        val negative = scaled < 0L
        val digits = if (negative) scaled.toString().substring(1) else scaled.toString()
        val padded = digits.padStart(decimals + minimumIntegerDigits, '0')
        val text = if (decimals == 0) {
            padded
        } else {
            padded.substring(0, padded.length - decimals) + "." + padded.substring(padded.length - decimals)
        }
        return if (negative) "-$text" else text
    }

    override fun toString(): String = pattern

    override fun equals(other: Any?): Boolean = other is NumberPattern && other.pattern == pattern

    override fun hashCode(): Int = pattern.hashCode()

    companion object {
        /**
         * Rounds [value] half up toward +∞, as Java `Math.round(double)`. 0.5 gives 1 and −0.5
         * gives 0. The value 0.49999999999999994 gives 0.
         */
        fun roundHalfUp(value: Double): Long {
            val lower = floor(value)
            return if (value - lower >= 0.5) lower.toLong() + 1L else lower.toLong()
        }
    }
}
