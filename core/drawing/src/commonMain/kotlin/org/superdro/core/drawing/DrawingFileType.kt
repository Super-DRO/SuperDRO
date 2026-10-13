package org.superdro.core.drawing

/** The result of the type test by content. */
enum class DrawingFileType {
    /** A wireframe document: gzip data, or JSON text with `touchdro.wireframe` or `touchdro.convert.result`. */
    Wireframe,

    /** A binary DXF file: it starts with the 22-byte sentinel. */
    BinaryDxf,

    /** An ASCII DXF file: a pair `0`/`SECTION` in the first 64 tag pairs. */
    AsciiDxf,

    /** Other content: DWG, SVG, PDF, images, G-code, and all other files. */
    Unrecognized,
}

/** The type test of an imported file. It looks only at the content, never at the file name. */
object DrawingTypeTest {

    /** The number of characters that the JSON test examines. */
    const val JSON_PREFIX_CHARS: Int = 4096

    /** The number of tag pairs that the ASCII DXF test examines. */
    const val ASCII_TAG_PAIRS: Int = 64

    /** The text for [DrawingFileType.Unrecognized] (string `drawing_not_a_drawing`). */
    const val NOT_A_DRAWING_MESSAGE: String = "This file isn't a drawing SuperDRO can open."

    private val WIREFRAME_SCHEMAS = listOf("touchdro.wireframe", "touchdro.convert.result")

    /** Gives the type of the file content [bytes]. */
    fun detect(bytes: ByteArray): DrawingFileType = when {
        isGzip(bytes) -> DrawingFileType.Wireframe
        isWireframeJson(bytes) -> DrawingFileType.Wireframe
        DxfLimits.isBinaryDxf(bytes) -> DrawingFileType.BinaryDxf
        isAsciiDxf(bytes) -> DrawingFileType.AsciiDxf
        else -> DrawingFileType.Unrecognized
    }

    /** True when [bytes] start with the gzip magic `1F 8B`. */
    fun isGzip(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()

    /**
     * True when the text (with no byte order mark and no white space at the start) starts with
     * `{` and the first 4096 characters contain `touchdro.wireframe` or `touchdro.convert.result`.
     */
    fun isWireframeJson(bytes: ByteArray): Boolean {
        val start = skipBom(bytes)
        val end = minOf(bytes.size, start + JSON_PREFIX_CHARS * 4)
        val prefix = bytes.decodeToString(start, end).trimStart().take(JSON_PREFIX_CHARS)
        if (!prefix.startsWith("{")) return false
        return WIREFRAME_SCHEMAS.any { schema -> prefix.contains(schema) }
    }

    /**
     * True when a pair with the code `0` and the value `SECTION` occurs in the first 64 tag pairs,
     * after an optional UTF-8 byte order mark. Each group code must be a decimal number from 0 to
     * 999. The test skips the comment pairs (code 999). A code of 1000 or more, a code that is not a
     * number, or no value line gives false.
     */
    fun isAsciiDxf(bytes: ByteArray): Boolean {
        var position = skipBom(bytes)
        var pairs = 0
        while (pairs < ASCII_TAG_PAIRS) {
            val codeLine = readLine(bytes, position) ?: return false
            position = codeLine.next
            val valueLine = readLine(bytes, position) ?: return false
            position = valueLine.next
            val codeText = codeLine.text.trim()
            if (codeText.isEmpty() || codeText.length > 3 || !codeText.all { char -> char in '0'..'9' }) return false
            val code = codeText.toInt()
            if (code == 999) continue
            if (code == 0 && valueLine.text.trim() == "SECTION") return true
            pairs++
        }
        return false
    }

    private class Line(val text: String, val next: Int)

    /** Reads one line from [start]. A line ends at LF; a CR before the LF goes away. Null at the end of the data. */
    private fun readLine(bytes: ByteArray, start: Int): Line? {
        if (start >= bytes.size) return null
        var end = start
        val limit = minOf(bytes.size, start + MAX_TEST_LINE)
        while (end < limit && bytes[end] != LF) end++
        if (end == limit && end < bytes.size) return Line(LONG_LINE, end)
        val next = if (end < bytes.size) end + 1 else end
        var textEnd = end
        if (textEnd > start && bytes[textEnd - 1] == CR) textEnd--
        return Line(bytes.decodeToString(start, textEnd), next)
    }

    private fun skipBom(bytes: ByteArray): Int =
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0

    private const val LF: Byte = 0x0A
    private const val CR: Byte = 0x0D

    /** A line of the type test longer than this is not a group code line. */
    private const val MAX_TEST_LINE = 4096
    private const val LONG_LINE = "\u0000long"
}
