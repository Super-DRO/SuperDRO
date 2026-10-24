package org.superdro.core.ui.mcp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The QR encoder against the known values of the standard. */
class QrCodeTest {

    @Test
    fun theGeneratorPolynomialsMatchTheStandardTables() {
        // The coefficients of the degree 7 and the degree 10 generator polynomials, from the highest power, without the leading 1.
        assertContentEquals(bytes(127, 122, 154, 164, 11, 68, 117), QrCode.rsDivisor(7))
        assertContentEquals(bytes(216, 194, 159, 111, 199, 94, 95, 113, 157, 193), QrCode.rsDivisor(10))
    }

    @Test
    fun theErrorCorrectionOfTheKnownVector() {
        // The 16 data codewords of "HELLO WORLD" (version 1-M, alphanumeric) and their 10 error correction codewords.
        val data = bytes(32, 91, 11, 120, 209, 114, 220, 77, 67, 64, 236, 17, 236, 17, 236, 17)
        assertContentEquals(bytes(196, 35, 39, 119, 235, 215, 231, 226, 93, 23), QrCode.rsRemainder(data, QrCode.rsDivisor(10)))
    }

    @Test
    fun theDataCodewordsHaveTheModeTheCountTheBytesAndThePadBytes() {
        val codewords = QrCode.dataBits("A".encodeToByteArray(), 1)
        // 0100 (byte mode) 00000001 (one byte) 01000001 ('A') 0000 (terminator), then 0xEC and 0x11 to 16 codewords.
        assertContentEquals(bytes(0x40, 0x14, 0x10, 0xEC, 0x11, 0xEC, 0x11, 0xEC, 0x11, 0xEC, 0x11, 0xEC, 0x11, 0xEC, 0x11, 0xEC), codewords)
    }

    @Test
    fun theFormatAndTheVersionBitsMatchTheStandard() {
        assertEquals(0x5412, QrCode.formatBits(0))
        assertEquals(0x5125, QrCode.formatBits(1))
        assertEquals(0x07C94, QrCode.versionBits(7))
        assertEquals(0x0A4D3, QrCode.versionBits(10))
    }

    @Test
    fun theCapacitiesMatchTheStandardTable() {
        assertEquals(listOf(14, 26, 42, 62, 84, 106, 122, 152, 180, 213), (1..10).map(QrCode::byteCapacity))
    }

    @Test
    fun theCodeHasTheFindersTheTimingPatternAndTheDarkModule() {
        val code = assertNotNull(QrCode.encode("http://192.168.1.20:5810/mcp"))
        assertEquals(3, code.version)
        assertEquals(29, code.size)
        // The three finder patterns: dark 7x7 frame with a dark 3x3 center, a light separator.
        listOf(0 to 0, code.size - 7 to 0, 0 to code.size - 7).forEach { (left, top) ->
            for (dy in 0 until 7) {
                for (dx in 0 until 7) {
                    val ring = dx == 0 || dy == 0 || dx == 6 || dy == 6
                    val center = dx in 2..4 && dy in 2..4
                    assertEquals(ring || center, code[left + dx, top + dy], "finder at $left,$top module $dx,$dy")
                }
            }
        }
        assertTrue(!code[7, 7] && !code[code.size - 8, 7] && !code[7, code.size - 8], "The separators are light.")
        for (index in 8 until code.size - 8) {
            assertEquals(index % 2 == 0, code[index, 6], "timing column $index")
            assertEquals(index % 2 == 0, code[6, index], "timing row $index")
        }
        assertTrue(code[8, code.size - 8], "The dark module.")
        // The alignment pattern of version 3 at (22, 22).
        assertTrue(code[22, 22] && !code[21, 22] && code[20, 22])
        // The format bits read back as level M with the mask of the code.
        val bits = QrCode.formatBits(code.mask)
        for (index in 0..5) assertEquals((bits ushr index) and 1 != 0, code[8, index], "format bit $index")
        for (index in 0..7) assertEquals((bits ushr index) and 1 != 0, code[code.size - 1 - index, 8], "format copy bit $index")
    }

    @Test
    fun aLongCommandUsesAHighVersionAndATooLongTextGivesNull() {
        val command = "claude mcp add --transport http superdro http://192.168.1.20:5810/mcp --header \"Authorization: Bearer " + "a".repeat(43) + "\""
        val code = assertNotNull(QrCode.encode(command))
        assertTrue(code.version in 7..10, "version ${code.version}")
        assertEquals(17 + 4 * code.version, code.size)
        assertNull(QrCode.encode("x".repeat(214)))
        assertNotNull(QrCode.encode("x".repeat(213)))
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { index -> values[index].toByte() }
}
