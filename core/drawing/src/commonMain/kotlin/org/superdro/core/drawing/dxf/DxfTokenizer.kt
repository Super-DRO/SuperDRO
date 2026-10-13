package org.superdro.core.drawing.dxf

import org.superdro.core.drawing.DrawingFailure
import org.superdro.core.drawing.DrawingProblemCodes
import org.superdro.core.drawing.DxfLimits
import org.superdro.core.drawing.text.DxfCharset
import org.superdro.core.drawing.text.DxfText
import org.superdro.core.drawing.text.selectDxfCharset
import org.superdro.core.util.Clock

/** The form of a DXF file. */
enum class DxfEncoding {
    /** ASCII DXF: one group code line and one value line for each tag. */
    Ascii,

    /** Binary DXF with a 2-byte group code. */
    BinaryTwoByteCodes,

    /** Binary DXF with a 1-byte group code (DXF R12 and older). */
    BinaryOneByteCodes,
}

/**
 * The tag stream of a DXF file: the result of the two tokenizers.
 *
 * @property tags The tags in file order, with no comment tags (code 999). The strings are decoded
 *   with [charset], and the escapes `\U+XXXX` are decoded.
 * @property acadVersion The value of `$ACADVER`, or null.
 * @property codePage The value of `$DWGCODEPAGE`, or null.
 * @property unknownCodePage A `$DWGCODEPAGE` value that SuperDRO does not have, or null.
 */
data class DxfTagStream(
    val tags: List<DxfTag>,
    val encoding: DxfEncoding,
    val charset: DxfCharset,
    val acadVersion: String?,
    val codePage: String?,
    val unknownCodePage: String? = null,
)

/** The time budget of one conversion. */
internal class ParseBudget(private val clock: Clock, private val limitMs: Long) {
    private val startMs = clock.nowMs()
    private var calls = 0

    /** Stops the conversion with `PARSE_TIME_EXCEEDED` when the time is more than the limit. */
    fun check() {
        if (clock.nowMs() - startMs > limitMs) {
            throw DrawingFailure(
                DrawingProblemCodes.PARSE_TIME_EXCEEDED,
                "the conversion took more than the $limitMs ms limit",
            )
        }
    }

    /** Calls [check] at each 1024th call. */
    fun tick() {
        if (++calls and 0x3FF == 0) check()
    }
}

/** The entry point of the two tokenizers. Both give the same [DxfTagStream] for the same drawing. */
object DxfTokenizer {

    /**
     * Tokenizes the DXF file [bytes]. A binary file starts with [DxfLimits.BINARY_SENTINEL]. Other
     * data goes to the ASCII tokenizer.
     *
     * @throws DrawingFailureException with the code `PARSE_ERROR` or `PARSE_TIME_EXCEEDED`.
     */
    fun tokenize(bytes: ByteArray, clock: Clock = Clock { 0L }, maxParseTimeMs: Long = Long.MAX_VALUE): DxfTagStream =
        runCatchingFailure { tokenize(bytes, ParseBudget(clock, maxParseTimeMs)) }

    internal fun tokenize(bytes: ByteArray, budget: ParseBudget): DxfTagStream {
        val (raw, encoding) = if (DxfLimits.isBinaryDxf(bytes)) {
            BinaryDxfTokenizer.tokenize(bytes, budget)
        } else {
            AsciiDxfTokenizer.tokenize(bytes, budget) to DxfEncoding.Ascii
        }
        return decodeStrings(raw, encoding)
    }

    /** Selects the character set from the header and decodes all strings again (the shared step). */
    private fun decodeStrings(raw: List<DxfTag>, encoding: DxfEncoding): DxfTagStream {
        var acadVersion: String? = null
        var codePage: String? = null
        var index = 0
        while (index < raw.size) {
            val tag = raw[index]
            if (tag.code == 0 && tag.text.trim() == "ENDSEC") break
            if (tag.code == 9 && index + 1 < raw.size) {
                when (tag.text.trim().uppercase()) {
                    "\$ACADVER" -> acadVersion = raw[index + 1].text.trim()
                    "\$DWGCODEPAGE" -> codePage = raw[index + 1].text.trim()
                }
            }
            index++
        }
        val selection = selectDxfCharset(acadVersion, codePage)
        val tags = raw.map { tag ->
            if (tag.type == DxfValueType.Text) {
                val decoded = DxfText.redecode(tag.text, selection.charset)
                if (decoded == tag.text) tag else tag.copy(text = decoded)
            } else {
                tag
            }
        }
        return DxfTagStream(tags, encoding, selection.charset, acadVersion, codePage, selection.unknownCodePage)
    }
}

/** A tokenizer or converter error in a public call. [problem] gives the code and the text. */
class DrawingFailureException(val problem: org.superdro.core.drawing.DrawingProblem) : RuntimeException(problem.message)

internal inline fun <T> runCatchingFailure(block: () -> T): T =
    try {
        block()
    } catch (failure: DrawingFailure) {
        throw DrawingFailureException(failure.problem)
    }

internal fun parseError(message: String): Nothing = throw DrawingFailure(DrawingProblemCodes.PARSE_ERROR, message)

/** The ASCII tokenizer: one group code line and one value line for each tag. */
internal object AsciiDxfTokenizer {

    /** Gives the tags. The strings have one character for each byte (see [DxfText.latin1]). */
    fun tokenize(bytes: ByteArray, budget: ParseBudget): List<DxfTag> {
        val tags = ArrayList<DxfTag>(bytes.size / 12 + 16)
        var position = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        var line = 1
        while (position < bytes.size) {
            budget.tick()
            val codeEnd = lineEnd(bytes, position)
            val codeText = DxfText.latin1(bytes, position, trimCr(bytes, position, codeEnd)).trim()
            if (codeText.isEmpty() && codeEnd >= bytes.size) break
            val code = codeText.toIntOrNull()
                ?: parseError("line $line: the group code \"${codeText.take(40)}\" is not a number")
            if (code < 0 || code > DxfValueType.MAX_GROUP_CODE) parseError("line $line: the group code $code is not valid")
            if (codeEnd >= bytes.size) parseError("line $line: the group code $code has no value line")
            val valueStart = codeEnd + 1
            val valueEnd = lineEnd(bytes, valueStart)
            val valueText = DxfText.latin1(bytes, valueStart, trimCr(bytes, valueStart, valueEnd))
            position = if (valueEnd < bytes.size) valueEnd + 1 else valueEnd
            val valueLine = line + 1
            line += 2
            if (code == 999) continue
            val tag = parseValue(code, valueText, valueLine)
            tags += tag
            if (code == 0 && tag.text.trim() == "EOF") break
        }
        return tags
    }

    private fun parseValue(code: Int, value: String, line: Int): DxfTag = when (DxfValueType.of(code)) {
        DxfValueType.Text -> DxfTag.text(code, value)
        DxfValueType.Real -> DxfTag.real(code, parseReal(value.trim()) ?: parseError("line $line: \"${value.trim().take(40)}\" is not a number (group code $code)"))
        DxfValueType.Binary -> {
            val hex = value.trim().uppercase()
            if (hex.length % 2 != 0 || !hex.all { char -> char in '0'..'9' || char in 'A'..'F' }) {
                parseError("line $line: \"${hex.take(40)}\" is not hexadecimal data (group code $code)")
            }
            DxfTag.text(code, hex)
        }
        else -> {
            val text = value.trim()
            val integer = text.toLongOrNull()
                ?: parseReal(text)?.takeIf { real -> real == kotlin.math.floor(real) && kotlin.math.abs(real) < 9.0e18 }?.toLong()
                ?: parseError("line $line: \"${text.take(40)}\" is not an integer (group code $code)")
            DxfTag.integer(code, integer)
        }
    }

    private val DECIMAL = Regex("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?")

    /** Parses a decimal number. Null when the text is not a decimal number (no NaN, no infinity, no hex). */
    fun parseReal(text: String): Double? = if (DECIMAL.matches(text)) text.toDoubleOrNull() else null

    private fun lineEnd(bytes: ByteArray, start: Int): Int {
        var index = start
        while (index < bytes.size && bytes[index] != 0x0A.toByte()) index++
        return index
    }

    private fun trimCr(bytes: ByteArray, start: Int, end: Int): Int =
        if (end > start && bytes[end - 1] == 0x0D.toByte()) end - 1 else end
}

/**
 * The binary tokenizer:
 *
 * 1. Test the 22-byte sentinel.
 * 2. Read the group code as 2 bytes, little-endian, or as 1 byte in older files. The form of the
 *    first tag `0`/`SECTION` selects the code size. In the 1-byte form, the byte 255 and a 2-byte
 *    code follow for a code of 255 or more.
 * 3. Select the value type from the group code range.
 */
internal object BinaryDxfTokenizer {

    fun tokenize(bytes: ByteArray, budget: ParseBudget): Pair<List<DxfTag>, DxfEncoding> {
        val start = DxfLimits.BINARY_SENTINEL.size
        if (!DxfLimits.isBinaryDxf(bytes)) parseError("the binary DXF sentinel is not present")
        val section = "SECTION".encodeToByteArray()
        val twoByte = matches(bytes, start, byteArrayOf(0, 0) + section + byteArrayOf(0))
        val oneByte = !twoByte && matches(bytes, start, byteArrayOf(0) + section + byteArrayOf(0))
        if (!twoByte && !oneByte) parseError("the first tag of the binary DXF data is not 0/SECTION")
        val encoding = if (twoByte) DxfEncoding.BinaryTwoByteCodes else DxfEncoding.BinaryOneByteCodes
        val tags = ArrayList<DxfTag>(bytes.size / 8 + 16)
        var position = start
        while (position < bytes.size) {
            budget.tick()
            val tagStart = position
            val code: Int
            if (twoByte) {
                need(bytes, position, 2, tagStart)
                code = u16(bytes, position)
                position += 2
            } else {
                need(bytes, position, 1, tagStart)
                val first = bytes[position].toInt() and 0xFF
                position += 1
                if (first == 255) {
                    need(bytes, position, 2, tagStart)
                    code = u16(bytes, position)
                    position += 2
                } else {
                    code = first
                }
            }
            if (code > DxfValueType.MAX_GROUP_CODE) parseError("byte $tagStart: the group code $code is not valid")
            val tag: DxfTag
            when (DxfValueType.of(code)) {
                DxfValueType.Text -> {
                    var end = position
                    while (end < bytes.size && bytes[end] != 0.toByte()) end++
                    if (end >= bytes.size) parseError("byte $tagStart: the string of group code $code has no NUL byte")
                    tag = DxfTag.text(code, DxfText.latin1(bytes, position, end))
                    position = end + 1
                }
                DxfValueType.Int16 -> {
                    need(bytes, position, 2, tagStart)
                    tag = DxfTag.integer(code, u16(bytes, position).toShort().toLong())
                    position += 2
                }
                DxfValueType.Int32 -> {
                    need(bytes, position, 4, tagStart)
                    tag = DxfTag.integer(code, littleEndian(bytes, position, 4).toInt().toLong())
                    position += 4
                }
                DxfValueType.Int64 -> {
                    need(bytes, position, 8, tagStart)
                    tag = DxfTag.integer(code, littleEndian(bytes, position, 8))
                    position += 8
                }
                DxfValueType.Real -> {
                    need(bytes, position, 8, tagStart)
                    tag = DxfTag.real(code, Double.fromBits(littleEndian(bytes, position, 8)))
                    position += 8
                }
                DxfValueType.Bool -> {
                    need(bytes, position, 1, tagStart)
                    tag = DxfTag.integer(code, (bytes[position].toInt() and 0xFF).toLong())
                    position += 1
                }
                DxfValueType.Binary -> {
                    need(bytes, position, 1, tagStart)
                    val length = bytes[position].toInt() and 0xFF
                    position += 1
                    need(bytes, position, length, tagStart)
                    tag = DxfTag.text(code, hex(bytes, position, length))
                    position += length
                }
            }
            tags += tag
            if (code == 0 && tag.text == "EOF") break
        }
        return tags to encoding
    }

    private fun matches(bytes: ByteArray, start: Int, expected: ByteArray): Boolean =
        bytes.size >= start + expected.size && expected.indices.all { index -> bytes[start + index] == expected[index] }

    private fun need(bytes: ByteArray, position: Int, count: Int, tagStart: Int) {
        if (position + count > bytes.size) parseError("byte $tagStart: the binary DXF data ends inside a tag")
    }

    private fun u16(bytes: ByteArray, position: Int): Int =
        (bytes[position].toInt() and 0xFF) or ((bytes[position + 1].toInt() and 0xFF) shl 8)

    private fun littleEndian(bytes: ByteArray, position: Int, count: Int): Long {
        var value = 0L
        for (index in count - 1 downTo 0) value = (value shl 8) or (bytes[position + index].toLong() and 0xFF)
        return value
    }

    private const val HEX = "0123456789ABCDEF"

    private fun hex(bytes: ByteArray, position: Int, length: Int): String {
        val builder = StringBuilder(length * 2)
        for (index in position until position + length) {
            val value = bytes[index].toInt() and 0xFF
            builder.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return builder.toString()
    }
}
