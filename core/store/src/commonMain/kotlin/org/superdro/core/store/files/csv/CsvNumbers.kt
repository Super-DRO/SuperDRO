package org.superdro.core.store.files.csv

import kotlin.math.abs
import kotlin.math.floor
import org.superdro.core.model.ReadoutFormat
import org.superdro.core.model.Units

/**
 * The numbers of the CSV files.
 *
 * The import gives each number text to the expression evaluator through a function
 * parameter `(String) -> Double?`. The evaluator applies the decimal comma rule.
 * [parsePlain] is the default of the parameter for a file of plain numbers.
 */
object CsvNumbers {

    private val PLAIN = Regex("""^[+-]?(\d+([.,]\d*)?|[.,]\d+)$""")
    private val NUMBER_LITERAL = Regex("""\d*[.,](\d+)""")

    /**
     * Reads a plain number: an optional sign, digits, and an optional decimal mark `.` or `,`.
     * Gives null for other text. Example: `"6,0"` gives 6.0.
     */
    fun parsePlain(text: String): Double? {
        val trimmed = text.trim()
        if (!PLAIN.matches(trimmed)) return null
        return trimmed.replace(',', '.').toDoubleOrNull()
    }

    /**
     * Gives the number of decimals of a number text: the most digits after a decimal mark in a
     * number of the text. A text with no decimal mark gives 0. Example: `"1.250"` gives 3.
     */
    fun decimals(text: String): Int = NUMBER_LITERAL.findAll(text).maxOfOrNull { match -> match.groupValues[1].length } ?: 0

    /**
     * The tolerance of the point match for a value text:
     * (1000 or 25400) / 10^decimals / 2 µm. Example: `1.250` in inch gives 12.7 µm.
     */
    fun tolerance(text: String, metric: Boolean): Double {
        var tolerance = unitFactor(metric) / 2.0
        repeat(decimals(text)) { tolerance /= 10.0 }
        return tolerance
    }

    /** µm in one mm (1000) or in one inch (25400). */
    fun unitFactor(metric: Boolean): Double = if (metric) Units.UM_PER_MM else Units.UM_PER_INCH

    /**
     * The number format of the 3.32.00 CSV export: mm `0.0####`, inch
     * `0.0#####`, `.` as the decimal mark, `-` as the minus sign, no group separator. The round-off
     * goes half up. Examples: 6350 µm gives `6.35` (mm) and `0.25` (inch).
     */
    fun formatCompact(um: Double, metric: Boolean): String {
        val maxDecimals = if (metric) 5 else 6
        val text = formatFixed(um / unitFactor(metric), maxDecimals)
        val point = text.indexOf('.')
        var end = text.length
        while (end > point + 2 && text[end - 1] == '0') end -= 1
        return text.substring(0, end)
    }

    /**
     * The number format of the readout format [format]: the decimals
     * of the format, half up. Example: 25400 µm in inch with format 3 gives `1.0000`.
     */
    fun formatReadout(um: Double, metric: Boolean, format: ReadoutFormat): String =
        formatFixed(um / unitFactor(metric), if (metric) format.mmDecimals else format.inchDecimals)

    /**
     * Gives [value] with exactly [decimals] decimals. The round-off goes half up toward +∞, as
     * Java `Math.round`. The text never shows a minus sign for 0.
     */
    fun formatFixed(value: Double, decimals: Int): String {
        var scale = 1.0
        repeat(decimals) { scale *= 10.0 }
        val scaled = floor(value * scale + 0.5).toLong()
        val negative = scaled < 0
        val digits = abs(scaled).toString().padStart(decimals + 1, '0')
        val integer = digits.substring(0, digits.length - decimals)
        val fraction = digits.substring(digits.length - decimals)
        val sign = if (negative) "-" else ""
        return if (decimals == 0) sign + integer else "$sign$integer.$fraction"
    }
}
