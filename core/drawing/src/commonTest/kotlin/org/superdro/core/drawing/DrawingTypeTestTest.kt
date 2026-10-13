package org.superdro.core.drawing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DrawingTypeTestTest {

    private fun pairs(count: Int, last: String = "0\nSECTION\n"): String = buildString {
        repeat(count) { append("  9\n\$X\n") }
        append(last)
    }

    @Test
    fun theMessageOfOtherContentIsTheSuperDroText() {
        assertEquals("This file isn't a drawing SuperDRO can open.", DrawingTypeTest.NOT_A_DRAWING_MESSAGE)
        assertEquals(DrawingTypeTest.NOT_A_DRAWING_MESSAGE, DrawingImportResult.NotADrawing().message)
    }

    @Test
    fun gzipDataIsAWireframe() {
        assertEquals(DrawingFileType.Wireframe, DrawingTypeTest.detect(byteArrayOf(0x1F, 0x8B.toByte(), 8, 0)))
    }

    @Test
    fun jsonWithTheSchemaInTheFirst4096CharactersIsAWireframe() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val json = "  \n {\"schema\":\"touchdro.wireframe\",\"v\":\"3.0\"}"
        assertEquals(DrawingFileType.Wireframe, DrawingTypeTest.detect(bom + json.encodeToByteArray()))
        assertEquals(DrawingFileType.Wireframe, DrawingTypeTest.detect("{\"schema\":\"touchdro.convert.result\"}".encodeToByteArray()))
        val late = "{\"pad\":\"" + "x".repeat(4100) + "\",\"schema\":\"touchdro.wireframe\"}"
        assertEquals(DrawingFileType.Unrecognized, DrawingTypeTest.detect(late.encodeToByteArray()))
        assertEquals(DrawingFileType.Unrecognized, DrawingTypeTest.detect("{\"schema\":\"other\"}".encodeToByteArray()))
        assertEquals(DrawingFileType.Unrecognized, DrawingTypeTest.detect("[\"touchdro.wireframe\"]".encodeToByteArray()))
    }

    @Test
    fun theBinarySentinelGivesBinaryDxf() {
        val data = DxfLimits.BINARY_SENTINEL + byteArrayOf(0, 0) + "SECTION".encodeToByteArray() + byteArrayOf(0)
        assertEquals(DrawingFileType.BinaryDxf, DrawingTypeTest.detect(data))
        val wrong = DxfLimits.BINARY_SENTINEL.copyOf().also { bytes -> bytes[21] = 1 }
        assertEquals(DrawingFileType.Unrecognized, DrawingTypeTest.detect(wrong))
    }

    @Test
    fun aSectionPairInTheFirst64PairsGivesAsciiDxf() {
        assertEquals(DrawingFileType.AsciiDxf, DrawingTypeTest.detect("  0\r\nSECTION\r\n  2\r\nHEADER\r\n".encodeToByteArray()))
        // The pair 64 is still in the range; the pair 65 is not.
        assertTrue(DrawingTypeTest.isAsciiDxf(pairs(63).encodeToByteArray()))
        assertFalse(DrawingTypeTest.isAsciiDxf(pairs(64).encodeToByteArray()))
    }

    @Test
    fun commentPairsDoNotCount() {
        val comments = buildString {
            repeat(100) { append("999\ncomment\n") }
            append("0\nSECTION\n")
        }
        assertTrue(DrawingTypeTest.isAsciiDxf(comments.encodeToByteArray()))
    }

    @Test
    fun aBomIsPermitted() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertTrue(DrawingTypeTest.isAsciiDxf(bom + "0\nSECTION\n".encodeToByteArray()))
    }

    @Test
    fun aBadGroupCodeOrAMissingValueLineIsNotDxf() {
        assertFalse(DrawingTypeTest.isAsciiDxf("1000\nx\n0\nSECTION\n".encodeToByteArray()))
        assertFalse(DrawingTypeTest.isAsciiDxf("G21\nG90\n0\nSECTION\n".encodeToByteArray()))
        assertFalse(DrawingTypeTest.isAsciiDxf("-1\nx\n0\nSECTION\n".encodeToByteArray()))
        assertFalse(DrawingTypeTest.isAsciiDxf("  9\n\$ACADVER\n  0".encodeToByteArray()))
        assertFalse(DrawingTypeTest.isAsciiDxf(ByteArray(0)))
    }

    @Test
    fun otherSamplesAreNotDrawings() {
        val samples = listOf(
            "%PDF-1.4\n%%EOF\n",
            "<?xml version=\"1.0\"?><svg xmlns=\"http://www.w3.org/2000/svg\"/>",
            "G21 G90\nG0 X0 Y0\nM30\n",
            "AC1032\u0000\u0000\u0000",
        )
        for (sample in samples) assertEquals(DrawingFileType.Unrecognized, DrawingTypeTest.detect(sample.encodeToByteArray()), sample)
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13)
        assertEquals(DrawingFileType.Unrecognized, DrawingTypeTest.detect(png))
    }
}
