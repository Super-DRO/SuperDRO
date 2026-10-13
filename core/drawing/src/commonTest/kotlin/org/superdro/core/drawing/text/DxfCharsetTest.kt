package org.superdro.core.drawing.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DxfCharsetTest {

    @Test
    fun ac1021AndLaterUseUtf8() {
        assertEquals(DxfCharset.Utf8, selectDxfCharset("AC1021", "ANSI_1251").charset)
        assertEquals(DxfCharset.Utf8, selectDxfCharset("AC1032", null).charset)
    }

    @Test
    fun olderFilesUseTheCodePage() {
        assertEquals("ANSI_1251", selectDxfCharset("AC1015", "ANSI_1251").charset.name)
        assertEquals("ANSI_1252", selectDxfCharset("AC1009", null).charset.name)
        assertEquals("ANSI_1252", selectDxfCharset("AC1015", "ascii").charset.name)
        assertEquals("DOS437", selectDxfCharset("AC1012", "dos437").charset.name)
        assertNull(selectDxfCharset("AC1015", "ANSI_1252").unknownCodePage)
    }

    @Test
    fun anUnknownCodePageGivesWindows1252AndItsName() {
        val selection = selectDxfCharset("AC1015", "ANSI_9999")
        assertEquals("ANSI_1252", selection.charset.name)
        assertEquals("ANSI_9999", selection.unknownCodePage)
    }

    @Test
    fun theDoubleByteCodePagesDecodePairs() {
        // "図面" in Shift-JIS (ANSI_932), GBK (ANSI_936), and Big5 (ANSI_950); "도면" in ANSI_949.
        val shiftJis = byteArrayOf(0x90.toByte(), 0x7D, 0x96.toByte(), 0xCA.toByte())
        assertEquals("図面", DxfCharset.forCodePage("ANSI_932")!!.decode(shiftJis))
        // A single-byte katakana (0xB1) and ASCII in Shift-JIS.
        assertEquals("Aｱ", DxfCharset.forCodePage("dos932")!!.decode(byteArrayOf(0x41, 0xB1.toByte())))
        assertEquals("图面", DxfCharset.forCodePage("ANSI_936")!!.decode(byteArrayOf(0xCD.toByte(), 0xBC.toByte(), 0xC3.toByte(), 0xE6.toByte())))
        assertEquals("도면", DxfCharset.forCodePage("ANSI_949")!!.decode(byteArrayOf(0xB5.toByte(), 0xB5.toByte(), 0xB8.toByte(), 0xE9.toByte())))
        assertEquals("圖面", DxfCharset.forCodePage("BIG5")!!.decode(byteArrayOf(0xB9.toByte(), 0xCF.toByte(), 0xAD.toByte(), 0xB1.toByte())))
        assertEquals(setOf("ANSI_932", "ANSI_936", "ANSI_949", "ANSI_950", "ANSI_1361"), DxfCharset.doubleByteNames)
        assertEquals("ANSI_936", selectDxfCharset("AC1015", "ANSI_936").charset.name)
        // A lead byte at the end of the data gives U+FFFD.
        assertEquals("\uFFFD", DxfCharset.forCodePage("ANSI_932")!!.decode(byteArrayOf(0x90.toByte())))
    }

    @Test
    fun theTablesDecodeTheHighBytes() {
        val bytes = byteArrayOf(0x80.toByte(), 0xE4.toByte(), 0xC0.toByte())
        assertEquals("€äÀ", DxfCharset.WINDOWS_1252.decode(bytes))
        assertEquals("ЂдА", DxfCharset.forCodePage("ANSI_1251")!!.decode(bytes))
        assertEquals("ÇõÀ".substring(0, 1), DxfCharset.forCodePage("DOS850")!!.decode(byteArrayOf(0x80.toByte())))
        assertEquals("�", DxfCharset.WINDOWS_1252.decode(byteArrayOf(0x81.toByte())))
        assertTrue(DxfCharset.singleByteNames.containsAll(listOf("ANSI_874", "ANSI_1250", "ANSI_1258", "DOS866", "MACINTOSH")))
    }

    @Test
    fun windows1252EncodesItsCharacters() {
        val bytes = DxfCharset.WINDOWS_1252.encode("Aä€Ж")
        assertEquals(listOf(0x41, 0xE4, 0x80, '?'.code), bytes.map { byte -> byte.toInt() and 0xFF })
        assertTrue(DxfCharset.WINDOWS_1252.canEncode('ä'))
        assertEquals(false, DxfCharset.WINDOWS_1252.canEncode('Ж'))
    }

    @Test
    fun theUnicodeEscapesDecodeAndEncode() {
        assertEquals("Ø_holes", DxfText.decodeUnicodeEscapes("\\U+00D8_holes"))
        assertEquals("a\\U+00Zb", DxfText.decodeUnicodeEscapes("a\\U+00Zb"))
        assertEquals("Point \\U+00E4 \\U+2300", DxfText.encodeUnicodeEscapes("Point ä ⌀"))
        assertEquals("ä", DxfText.redecode("Ã¤", DxfCharset.Utf8))
    }
}
