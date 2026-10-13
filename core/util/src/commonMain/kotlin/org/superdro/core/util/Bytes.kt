package org.superdro.core.util

/**
 * Byte helpers for the adapter links. The adapter protocol is ASCII text.
 */
object Bytes {

    /** The BEL byte `0x07`. A session sends it after 500 ms with no other write. */
    const val BEL: Byte = 0x07

    private const val HEX_DIGITS = "0123456789abcdef"

    /** Gives the bytes of [text] as US-ASCII. A character above `0x7F` gives `?`. */
    fun ascii(text: String): ByteArray = ByteArray(text.length) { index ->
        val code = text[index].code
        if (code in 0..0x7F) code.toByte() else '?'.code.toByte()
    }

    /**
     * Gives the text of [bytes] as US-ASCII. A byte above `0x7F` gives the replacement
     * character U+FFFD.
     */
    fun asciiText(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): String {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) {
            "The range $offset + $length is not in a buffer of ${bytes.size} bytes."
        }
        val builder = StringBuilder(length)
        for (index in offset until offset + length) {
            val value = bytes[index].toInt() and 0xFF
            builder.append(if (value <= 0x7F) value.toChar() else '�')
        }
        return builder.toString()
    }

    /** Gives the lowercase hexadecimal text of [bytes], two digits for each byte, with no space. */
    fun toHex(bytes: ByteArray): String {
        val builder = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            builder.append(HEX_DIGITS[value ushr 4])
            builder.append(HEX_DIGITS[value and 0x0F])
        }
        return builder.toString()
    }

    /**
     * Gives the bytes of the hexadecimal text [hex]. The text must have an even length and only
     * the characters `0-9`, `a-f`, and `A-F`.
     */
    fun fromHex(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "A hexadecimal text must have an even length: \"$hex\"." }
        return ByteArray(hex.length / 2) { index ->
            val high = hexValue(hex[index * 2])
            val low = hexValue(hex[index * 2 + 1])
            ((high shl 4) or low).toByte()
        }
    }

    /** Gives the value 0 to 15 of one hexadecimal digit. */
    fun hexValue(digit: Char): Int = when (digit) {
        in '0'..'9' -> digit - '0'
        in 'a'..'f' -> digit - 'a' + 10
        in 'A'..'F' -> digit - 'A' + 10
        else -> throw IllegalArgumentException("'$digit' is not a hexadecimal digit.")
    }

    /**
     * Gives a text for a log: printable ASCII stays, and each other byte shows as `\xNN`.
     * Example: `x1234;` and the BEL byte give `x1234;\x07`.
     */
    fun printable(bytes: ByteArray): String {
        val builder = StringBuilder(bytes.size)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            if (value in 0x20..0x7E) {
                builder.append(value.toChar())
            } else {
                builder.append("\\x")
                builder.append(HEX_DIGITS[value ushr 4])
                builder.append(HEX_DIGITS[value and 0x0F])
            }
        }
        return builder.toString()
    }
}
