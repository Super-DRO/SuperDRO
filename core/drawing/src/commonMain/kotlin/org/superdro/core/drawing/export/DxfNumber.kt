package org.superdro.core.drawing.export

/**
 * The number format of the DXF export: the pattern `0.0########`
 * (one to nine decimals) for the coordinates and `0.0#################` (one to 17 decimals) for
 * the bulges. The decimal point is `.`, there is no group separator, and −0 is written as `0.0`.
 * The round-off is half up on the shortest decimal text of the double.
 */
object DxfNumber {

    /** Formats a coordinate: one to nine decimals. */
    fun coordinate(value: Double): String = format(value, 9)

    /** Formats a bulge: one to 17 decimals. */
    fun bulge(value: Double): String = format(value, 17)

    /** Formats [value] with one to [maxDecimals] decimals. */
    fun format(value: Double, maxDecimals: Int): String {
        require(value.isFinite()) { "A DXF number must be finite: $value" }
        if (value == 0.0) return "0.0"
        val negative = value < 0
        val (digits, pointIndex) = decimalDigits(if (negative) -value else value)
        // digits with the decimal point after pointIndex digits (pointIndex can be <= 0 or > length).
        var integerPart: String
        var fraction: String
        if (pointIndex <= 0) {
            integerPart = "0"
            fraction = "0".repeat(-pointIndex) + digits
        } else if (pointIndex >= digits.length) {
            integerPart = digits + "0".repeat(pointIndex - digits.length)
            fraction = ""
        } else {
            integerPart = digits.substring(0, pointIndex)
            fraction = digits.substring(pointIndex)
        }
        if (fraction.length > maxDecimals) {
            val roundUp = fraction[maxDecimals] >= '5'
            fraction = fraction.substring(0, maxDecimals)
            if (roundUp) {
                val combined = increment(integerPart + fraction)
                val integerLength = combined.length - fraction.length
                integerPart = combined.substring(0, integerLength)
                fraction = combined.substring(integerLength)
            }
        }
        fraction = fraction.trimEnd('0')
        if (fraction.isEmpty()) fraction = "0"
        integerPart = integerPart.trimStart('0').ifEmpty { "0" }
        val text = "$integerPart.$fraction"
        return if (negative && text != "0.0") "-$text" else text
    }

    /** Adds 1 to the last digit of the decimal digit text [digits]. */
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

    /**
     * Gives the significant digits of the shortest decimal text of [value] (> 0) and the position
     * of the decimal point: value = 0.digits × 10^pointIndex.
     */
    private fun decimalDigits(value: Double): Pair<String, Int> {
        val text = value.toString()
        val exponentIndex = text.indexOfFirst { char -> char == 'E' || char == 'e' }
        val mantissa = if (exponentIndex >= 0) text.substring(0, exponentIndex) else text
        val exponent = if (exponentIndex >= 0) text.substring(exponentIndex + 1).toInt() else 0
        val point = mantissa.indexOf('.')
        val integerDigits = if (point >= 0) mantissa.substring(0, point) else mantissa
        val fractionDigits = if (point >= 0) mantissa.substring(point + 1) else ""
        var all = integerDigits + fractionDigits
        var pointIndex = integerDigits.length + exponent
        val leading = all.length - all.trimStart('0').length
        all = all.substring(leading)
        pointIndex -= leading
        all = all.trimEnd('0')
        if (all.isEmpty()) return "0" to 1
        return all to pointIndex
    }
}
