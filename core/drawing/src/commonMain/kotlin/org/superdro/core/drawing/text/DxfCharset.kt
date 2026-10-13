package org.superdro.core.drawing.text

import kotlin.io.encoding.Base64
import org.superdro.core.drawing.codec.Gzip

/**
 * The character set of the strings of a DXF file: UTF-8 for AC1021
 * (DXF 2007) and later, and `$DWGCODEPAGE` for older files.
 */
sealed class DxfCharset(
    /** The name of the character set: `UTF-8` or a `$DWGCODEPAGE` name, for example `ANSI_1252`. */
    val name: String,
) {
    /** Gives the text of the bytes [start] (inclusive) to [end] (exclusive) of [bytes]. */
    abstract fun decode(bytes: ByteArray, start: Int = 0, end: Int = bytes.size): String

    /** UTF-8. A bad byte sequence gives U+FFFD. */
    data object Utf8 : DxfCharset("UTF-8") {
        override fun decode(bytes: ByteArray, start: Int, end: Int): String = bytes.decodeToString(start, end)
    }

    /**
     * A code page with one byte for each character. The bytes 0x00 to 0x7F are ASCII. [high] gives
     * the characters of the bytes 0x80 to 0xFF.
     */
    class SingleByte internal constructor(name: String, private val high: String) : DxfCharset(name) {
        init {
            require(high.length == 128) { "A code page table must have 128 characters, not ${high.length}." }
        }

        /** The byte of each character above 0x7F, for [encode]. */
        private val reverse: Map<Char, Byte> by lazy {
            buildMap {
                high.forEachIndexed { index, char ->
                    if (char != REPLACEMENT && char !in this) put(char, (index + 0x80).toByte())
                }
            }
        }

        override fun decode(bytes: ByteArray, start: Int, end: Int): String {
            val builder = StringBuilder(end - start)
            for (index in start until end) {
                val value = bytes[index].toInt() and 0xFF
                builder.append(if (value < 0x80) value.toChar() else high[value - 0x80])
            }
            return builder.toString()
        }

        /** True when this code page has a byte for [char]. */
        fun canEncode(char: Char): Boolean = char.code < 0x80 || char in reverse

        /** Gives the bytes of [text]. A character that the code page does not have gives `?`. */
        fun encode(text: String): ByteArray = ByteArray(text.length) { index ->
            val char = text[index]
            if (char.code < 0x80) char.code.toByte() else reverse[char] ?: '?'.code.toByte()
        }

        override fun equals(other: Any?): Boolean = other is SingleByte && other.name == name

        override fun hashCode(): Int = name.hashCode()

        override fun toString(): String = "SingleByte($name)"
    }

    /**
     * A code page with one or two bytes for each character (ANSI_932, ANSI_936, ANSI_949,
     * ANSI_950, ANSI_1361). A byte below 0x80 is ASCII. A lead byte and the next byte give one
     * character. [table] has 65536 characters: the index b for the single byte b, and the index
     * (lead × 256 + trail) for a pair.
     */
    class DoubleByte internal constructor(name: String, private val table: CharArray) : DxfCharset(name) {
        init {
            require(table.size == 65536) { "A double-byte table must have 65536 characters, not ${table.size}." }
        }

        /** True for each byte that starts a pair. */
        private val lead = BooleanArray(256) { value ->
            value >= 0x80 && (0x40..0xFF).any { trail -> table[(value shl 8) or trail] != REPLACEMENT }
        }

        override fun decode(bytes: ByteArray, start: Int, end: Int): String {
            val builder = StringBuilder(end - start)
            var index = start
            while (index < end) {
                val value = bytes[index].toInt() and 0xFF
                if (value >= 0x80 && lead[value] && index + 1 < end) {
                    val trail = bytes[index + 1].toInt() and 0xFF
                    builder.append(table[(value shl 8) or trail])
                    index += 2
                } else {
                    builder.append(if (value < 0x80) value.toChar() else table[value])
                    index++
                }
            }
            return builder.toString()
        }

        override fun equals(other: Any?): Boolean = other is DoubleByte && other.name == name

        override fun hashCode(): Int = name.hashCode()

        override fun toString(): String = "DoubleByte($name)"
    }

    companion object {
        /** The character U+FFFD. A byte that a code page does not define gives this character. */
        const val REPLACEMENT: Char = '�'

        /** windows-1252: the code page `ANSI_1252` of the DXF export. */
        val WINDOWS_1252: SingleByte = SingleByte("ANSI_1252", DXF_CODE_PAGE_TABLES.getValue("ANSI_1252"))

        /** The names of the single-byte code pages that SuperDRO reads. */
        val singleByteNames: Set<String> get() = DXF_CODE_PAGE_TABLES.keys

        /** The names of the double-byte code pages that SuperDRO reads. */
        val doubleByteNames: Set<String> get() = DXF_DOUBLE_BYTE_TABLES.keys

        /** Other names of the double-byte code pages. */
        private val DOUBLE_BYTE_ALIASES = mapOf(
            "DOS932" to "ANSI_932",
            "GB2312" to "ANSI_936",
            "KSC5601" to "ANSI_949",
            "BIG5" to "ANSI_950",
            "JOHAB" to "ANSI_1361",
        )

        /** The double-byte code pages. Each table decodes at its first use; `lazy` is thread safe. */
        private val doubleBytePages: Map<String, Lazy<DoubleByte>> = DXF_DOUBLE_BYTE_TABLES.mapValues { (name, parts) ->
            lazy { DoubleByte(name, unpack(parts)) }
        }

        /**
         * Gives the code page of the `$DWGCODEPAGE` value [name], or null when SuperDRO does not
         * have it. The test ignores case. `ASCII` gives windows-1252, because the bytes 0x00 to
         * 0x7F are the same.
         */
        fun forCodePage(name: String): DxfCharset? {
            val key = name.trim().uppercase()
            if (key == "ANSI_1252" || key == "ASCII" || key == "US-ASCII") return WINDOWS_1252
            DXF_CODE_PAGE_TABLES[key]?.let { table -> return SingleByte(key, table) }
            return doubleBytePages[DOUBLE_BYTE_ALIASES[key] ?: key]?.value
        }

        /** Decodes a double-byte table: Base64 text, gzip data, and 65536 little-endian 16-bit values. */
        private fun unpack(parts: List<String>): CharArray {
            val data = Gzip.decompress(Base64.decode(parts.joinToString("")))
            return CharArray(data.size / 2) { index ->
                ((data[2 * index].toInt() and 0xFF) or ((data[2 * index + 1].toInt() and 0xFF) shl 8)).toChar()
            }
        }
    }
}

/** The result of [selectDxfCharset]. [unknownCodePage] holds a `$DWGCODEPAGE` value that SuperDRO does not have. */
data class DxfCharsetSelection(val charset: DxfCharset, val unknownCodePage: String? = null)

/**
 * Selects the character set of a DXF file from the header values `$ACADVER` and `$DWGCODEPAGE`.
 * AC1021 and later use UTF-8. An older file uses its code page. An
 * older file with no code page, or with a code page that SuperDRO does not have, uses
 * windows-1252.
 */
fun selectDxfCharset(acadVersion: String?, codePage: String?): DxfCharsetSelection {
    val version = acadVersion?.trim()?.uppercase().orEmpty()
    if (version.length >= 6 && version.startsWith("AC") && version >= "AC1021") {
        return DxfCharsetSelection(DxfCharset.Utf8)
    }
    if (codePage.isNullOrBlank()) return DxfCharsetSelection(DxfCharset.WINDOWS_1252)
    val selected = DxfCharset.forCodePage(codePage)
    return if (selected != null) {
        DxfCharsetSelection(selected)
    } else {
        DxfCharsetSelection(DxfCharset.WINDOWS_1252, unknownCodePage = codePage.trim())
    }
}

/** Text helpers of the DXF strings. */
object DxfText {

    /**
     * Gives the text of the bytes [start] to [end] with one character for each byte (ISO 8859-1).
     * The tokenizers use this lossless form first. [redecode] then applies the real character set.
     */
    fun latin1(bytes: ByteArray, start: Int, end: Int): String {
        val builder = StringBuilder(end - start)
        for (index in start until end) builder.append((bytes[index].toInt() and 0xFF).toChar())
        return builder.toString()
    }

    /** True when each character of [text] is below 0x80. */
    fun isAscii(text: String): Boolean = text.all { char -> char.code < 0x80 }

    /**
     * Decodes the ISO 8859-1 form [latin1Text] again with [charset], and then decodes the
     * escapes `\U+XXXX` (see [decodeUnicodeEscapes]).
     */
    fun redecode(latin1Text: String, charset: DxfCharset): String {
        val decoded = if (isAscii(latin1Text)) {
            latin1Text
        } else {
            val bytes = ByteArray(latin1Text.length) { index -> latin1Text[index].code.toByte() }
            charset.decode(bytes)
        }
        return decodeUnicodeEscapes(decoded)
    }

    /**
     * Replaces each escape `\U+XXXX` (four hexadecimal digits) with its character. AutoCAD and
     * other programs write a character that the code page does not have in this form.
     */
    fun decodeUnicodeEscapes(text: String): String {
        if (!text.contains("\\U+") && !text.contains("\\u+")) return text
        val builder = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (char == '\\' && index + 7 <= text.length && (text[index + 1] == 'U' || text[index + 1] == 'u') &&
                text[index + 2] == '+'
            ) {
                val digits = text.substring(index + 3, index + 7)
                val value = digits.toIntOrNull(16)
                if (value != null && digits.all { digit -> digit.isLetterOrDigit() }) {
                    builder.append(value.toChar())
                    index += 7
                    continue
                }
            }
            builder.append(char)
            index++
        }
        return builder.toString()
    }

    /**
     * Gives [text] with each character at or above 0x80 as the escape `\U+XXXX` (uppercase
     * hexadecimal). The DXF export writes the sub-datum names in this form.
     */
    fun encodeUnicodeEscapes(text: String): String {
        val builder = StringBuilder(text.length)
        for (char in text) {
            if (char.code < 0x80) {
                builder.append(char)
            } else {
                builder.append("\\U+")
                builder.append(char.code.toString(16).uppercase().padStart(4, '0'))
            }
        }
        return builder.toString()
    }
}
