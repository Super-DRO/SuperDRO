package org.superdro.core.adapterprobe

import kotlin.math.abs
import kotlin.math.floor

/**
 * The text formats of `scripts/adapter_probe.py`. The summary lines and the phase tables of the
 * app must be the same as the lines of the PC tool.
 * Kotlin common code has no `String.format`, so this object gives the formats that the tool uses.
 */
object PyText {

    /**
     * Gives `"%.<decimals>f" % value` of Python. The rounding is "half to even" on the exact
     * value of the double, as in Python. `-0.04` with 1 decimal gives `-0.0`, as in Python.
     */
    fun fixed(value: Double, decimals: Int): String {
        require(decimals in 0..9) { "The decimals must be 0 to 9, not $decimals." }
        if (value.isNaN()) return "nan"
        if (value.isInfinite()) return if (value > 0) "inf" else "-inf"
        val negative = value < 0.0 || (value == 0.0 && 1.0 / value < 0.0)
        val magnitude = abs(value)
        var scale = 1.0
        repeat(decimals) { scale *= 10.0 }
        val scaled = magnitude * scale
        if (scaled >= LIMIT) return (if (negative) "-" else "") + magnitude.toString()
        var whole = floor(scaled)
        val fraction = scaled - whole
        val roundUp = when {
            fraction > 0.5 -> true
            fraction < 0.5 -> false
            else -> {
                // The product can be an exact half only after its own rounding. The exact error
                // of the product tells the side of the true value.
                val error = productError(magnitude, scale, scaled)
                when {
                    error > 0.0 -> true
                    error < 0.0 -> false
                    else -> whole % 2.0 != 0.0
                }
            }
        }
        if (roundUp) whole += 1.0
        val digits = whole.toLong().toString().padStart(decimals + 1, '0')
        val integer = digits.substring(0, digits.length - decimals)
        val text = if (decimals == 0) integer else integer + "." + digits.substring(digits.length - decimals)
        return if (negative) "-$text" else text
    }

    /** Gives `"%+.<decimals>f" % value` of Python: always a sign. */
    fun signed(value: Double, decimals: Int): String {
        val text = fixed(value, decimals)
        return if (text.startsWith("-")) text else "+$text"
    }

    /** Gives `ms(x)` of the tool: `-` for null, else `"%.1f" % (x * 1000)` for a time in seconds. */
    fun ms(seconds: Double?): String = if (seconds == null) "-" else fixed(seconds * 1000.0, 1)

    /** Gives `"%-<width>s"`: the text, then spaces up to [width]. */
    fun left(text: Any?, width: Int): String = str(text).padEnd(width)

    /** Gives `"%<width>s"`: spaces, then the text, up to [width]. */
    fun right(text: Any?, width: Int): String = str(text).padStart(width)

    /** Gives `str(value)` of Python for the values of the tool: `None`, an integer, a text, or a float. */
    fun str(value: Any?): String = when (value) {
        null -> "None"
        is Double -> floatText(value)
        is Float -> floatText(value.toDouble())
        is Boolean -> if (value) "True" else "False"
        else -> value.toString()
    }

    /** Gives `repr(text)` of Python for a text. */
    fun repr(text: String): String {
        val quote = if ('\'' in text && '"' !in text) '"' else '\''
        val out = StringBuilder(text.length + 2)
        out.append(quote)
        for (character in text) {
            val code = character.code
            when {
                character == quote || character == '\\' -> out.append('\\').append(character)
                character == '\n' -> out.append("\\n")
                character == '\r' -> out.append("\\r")
                character == '\t' -> out.append("\\t")
                code < 0x20 || code in 0x7F..0xA0 || code == 0xAD -> out.append("\\x").append(hex2(code))
                else -> out.append(character)
            }
        }
        out.append(quote)
        return out.toString()
    }

    /** Gives the 2 lower case hex digits of [value] (`"%02x"`). */
    fun hex2(value: Int): String = value.toString(16).padStart(2, '0')

    /** Gives the lower case hex digits of [value] (`"%x"`). */
    fun hex(value: Long): String = value.toString(16)

    /** Gives `"%02X"` for each byte of the 48-bit [value], joined by `:`. */
    fun macText(value: Long): String =
        (5 downTo 0).joinToString(":") { index -> ((value ushr (index * 8)) and 0xFF).toString(16).padStart(2, '0').uppercase() }

    private fun floatText(value: Double): String {
        if (value.isNaN()) return "nan"
        if (value.isInfinite()) return if (value > 0) "inf" else "-inf"
        val text = value.toString()
        return if ('.' in text || 'E' in text || 'e' in text) text else "$text.0"
    }

    /** The exact error of the product `a * b`, where [product] is the rounded product (Dekker). */
    private fun productError(a: Double, b: Double, product: Double): Double {
        val (aHigh, aLow) = split(a)
        val (bHigh, bLow) = split(b)
        return ((aHigh * bHigh - product) + aHigh * bLow + aLow * bHigh) + aLow * bLow
    }

    private fun split(value: Double): Pair<Double, Double> {
        val c = SPLITTER * value
        val high = c - (c - value)
        return high to (value - high)
    }

    private const val SPLITTER = 134_217_729.0
    private const val LIMIT = 4.0e15
}
