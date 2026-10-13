package org.superdro.core.drawing.dxf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.superdro.core.drawing.DrawingProblemCodes
import org.superdro.core.drawing.DxfLimits
import org.superdro.core.util.TestClock

class DxfTokenizerTest {

    /** A small binary DXF writer for the tests. */
    private class Binary(private val twoByteCodes: Boolean = true) {
        private val out = ArrayList<Byte>()

        init {
            DxfLimits.BINARY_SENTINEL.forEach { byte -> out += byte }
        }

        private fun code(code: Int) {
            if (twoByteCodes) {
                out += (code and 0xFF).toByte()
                out += (code ushr 8).toByte()
            } else if (code >= 255) {
                out += 0xFF.toByte()
                out += (code and 0xFF).toByte()
                out += (code ushr 8).toByte()
            } else {
                out += code.toByte()
            }
        }

        private fun little(value: Long, count: Int) {
            for (index in 0 until count) out += (value ushr (8 * index)).toByte()
        }

        fun text(code: Int, value: String) = apply {
            code(code)
            value.encodeToByteArray().forEach { byte -> out += byte }
            out += 0
        }

        fun int(code: Int, value: Long, count: Int) = apply {
            code(code)
            little(value, count)
        }

        fun real(code: Int, value: Double) = apply {
            code(code)
            little(value.toRawBits(), 8)
        }

        fun chunk(code: Int, data: ByteArray) = apply {
            code(code)
            out += data.size.toByte()
            data.forEach { byte -> out += byte }
        }

        fun bytes(): ByteArray = out.toByteArray()
    }

    private val ascii = """
          0
        SECTION
          2
        HEADER
        999
        a comment
          9
        ${'$'}ACADVER
          1
        AC1015
          0
        ENDSEC
          0
        SECTION
          2
        ENTITIES
          0
        LINE
          8
        0
         10
        1.5
         20
        -2.0E-3
         62
            -7
         90
        123456
        160
        9000000000
        290
        1
        310
        0aff
          0
        ENDSEC
          0
        EOF
    """.trimIndent()

    private fun binaryTwin(twoByteCodes: Boolean) = Binary(twoByteCodes)
        .text(0, "SECTION").text(2, "HEADER").text(9, "\$ACADVER").text(1, "AC1015").text(0, "ENDSEC")
        .text(0, "SECTION").text(2, "ENTITIES").text(0, "LINE").text(8, "0")
        .real(10, 1.5).real(20, -2.0E-3).int(62, -7, 2).int(90, 123456, 4).int(160, 9_000_000_000, 8).int(290, 1, 1)
        .chunk(310, byteArrayOf(0x0A, 0xFF.toByte()))
        .text(0, "ENDSEC").text(0, "EOF")
        .bytes()

    @Test
    fun theAsciiAndTheBinaryTokenizerGiveTheSameTags() {
        val fromAscii = DxfTokenizer.tokenize(ascii.encodeToByteArray())
        val fromBinary = DxfTokenizer.tokenize(binaryTwin(true))
        assertEquals(fromAscii.tags, fromBinary.tags)
        assertEquals(DxfEncoding.Ascii, fromAscii.encoding)
        assertEquals(DxfEncoding.BinaryTwoByteCodes, fromBinary.encoding)
        assertEquals("AC1015", fromAscii.acadVersion)
    }

    @Test
    fun theValueTypesComeFromTheGroupCode() {
        val tags = DxfTokenizer.tokenize(ascii.encodeToByteArray()).tags
        assertEquals(DxfTag.real(10, 1.5), tags.first { tag -> tag.code == 10 })
        assertEquals(DxfTag.real(20, -0.002), tags.first { tag -> tag.code == 20 })
        assertEquals(DxfTag.integer(62, -7), tags.first { tag -> tag.code == 62 })
        assertEquals(DxfTag.integer(90, 123456), tags.first { tag -> tag.code == 90 })
        assertEquals(DxfTag.integer(160, 9_000_000_000), tags.first { tag -> tag.code == 160 })
        assertEquals(DxfTag.integer(290, 1), tags.first { tag -> tag.code == 290 })
        assertEquals(DxfTag.text(310, "0AFF"), tags.first { tag -> tag.code == 310 })
        // The comment (999) is not in the stream.
        assertTrue(tags.none { tag -> tag.code == 999 })
    }

    @Test
    fun theFirstTagSelectsOneByteGroupCodes() {
        val oneByte = Binary(twoByteCodes = false)
            .text(0, "SECTION").text(2, "ENTITIES").text(0, "POINT").text(8, "0").real(10, 1.0).real(20, 2.0)
            .text(1001, "APP").text(1000, "text").text(0, "ENDSEC").text(0, "EOF").bytes()
        val stream = DxfTokenizer.tokenize(oneByte)
        assertEquals(DxfEncoding.BinaryOneByteCodes, stream.encoding)
        assertEquals(listOf(0, 2, 0, 8, 10, 20, 1001, 1000, 0, 0), stream.tags.map { tag -> tag.code })
        assertEquals("text", stream.tags[7].text)
    }

    @Test
    fun aBadFirstBinaryTagIsAParseError() {
        val bad = DxfLimits.BINARY_SENTINEL + byteArrayOf(1, 0) + "SECTION".encodeToByteArray() + byteArrayOf(0)
        val error = assertFailsWith<DrawingFailureException> { DxfTokenizer.tokenize(bad) }
        assertEquals(DrawingProblemCodes.PARSE_ERROR, error.problem.code)
        assertEquals("the first tag of the binary DXF data is not 0/SECTION", error.problem.message)
    }

    @Test
    fun truncatedBinaryDataIsAParseError() {
        val data = binaryTwin(true)
        val error = assertFailsWith<DrawingFailureException> { DxfTokenizer.tokenize(data.copyOf(data.size - 30)) }
        assertEquals(DrawingProblemCodes.PARSE_ERROR, error.problem.code)
    }

    @Test
    fun asciiErrorsGiveTheLineNumber() {
        val badCode = assertFailsWith<DrawingFailureException> { DxfTokenizer.tokenize("  0\nSECTION\nxx\nHEADER\n".encodeToByteArray()) }
        assertEquals("line 3: the group code \"xx\" is not a number", badCode.problem.message)
        val badReal = assertFailsWith<DrawingFailureException> { DxfTokenizer.tokenize("  0\nSECTION\n 10\n1,5\n".encodeToByteArray()) }
        assertEquals("line 4: \"1,5\" is not a number (group code 10)", badReal.problem.message)
        val noValue = assertFailsWith<DrawingFailureException> { DxfTokenizer.tokenize("  0\nSECTION\n  2".encodeToByteArray()) }
        assertEquals("line 3: the group code 2 has no value line", noValue.problem.message)
        val badCodeNumber = assertFailsWith<DrawingFailureException> { DxfTokenizer.tokenize("  0\nSECTION\n2000\nX\n".encodeToByteArray()) }
        assertEquals("line 3: the group code 2000 is not valid", badCodeNumber.problem.message)
    }

    @Test
    fun theParseTimeLimitStopsTheTokenizer() {
        val clock = TestClock()
        var calls = 0
        val advancing = org.superdro.core.util.Clock {
            calls++
            clock.advanceBy(10)
            clock.nowMs()
        }
        val text = buildString { repeat(5000) { append("  0\nPOINT\n") } }
        val error = assertFailsWith<DrawingFailureException> { DxfTokenizer.tokenize(text.encodeToByteArray(), advancing, maxParseTimeMs = 15) }
        assertEquals(DrawingProblemCodes.PARSE_TIME_EXCEEDED, error.problem.code)
        assertEquals("the conversion took more than the 15 ms limit", error.problem.message)
        assertTrue(calls >= 2)
    }

    @Test
    fun theCodePageDecodesTheStrings() {
        val text = "  0\nSECTION\n  2\nHEADER\n  9\n\$ACADVER\n  1\nAC1015\n  9\n\$DWGCODEPAGE\n  3\nANSI_1251\n  0\nENDSEC\n  0\nSECTION\n  2\nTABLES\n  0\nTABLE\n  2\nLAYER\n  0\nLAYER\n  2\n"
        val bytes = text.encodeToByteArray() + byteArrayOf(0xCE.toByte(), 0xF2.toByte()) + "\n  0\nENDTAB\n  0\nENDSEC\n  0\nEOF\n".encodeToByteArray()
        val stream = DxfTokenizer.tokenize(bytes)
        assertEquals("ANSI_1251", stream.charset.name)
        assertTrue(stream.tags.any { tag -> tag.code == 2 && tag.text == "От" })
    }
}
