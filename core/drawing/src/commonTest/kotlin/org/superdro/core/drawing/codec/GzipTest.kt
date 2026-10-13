package org.superdro.core.drawing.codec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GzipTest {

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { index -> values[index].toByte() }

    /** `gzip.compress(b"hello hello hello hello", mtime=0)` of Python: a fixed Huffman block with a back reference. */
    private val hello = bytes(
        0x1f, 0x8b, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02, 0xff, 0xcb, 0x48, 0xcd, 0xc9, 0xc9, 0x57,
        0xc8, 0x40, 0x27, 0x01, 0xe3, 0x51, 0x3d, 0x8d, 0x17, 0x00, 0x00, 0x00,
    )

    @Test
    fun aFixedHuffmanBlockDecodes() {
        assertEquals("hello hello hello hello", Gzip.decompress(hello).decodeToString())
    }

    @Test
    fun aStoredBlockDecodes() {
        // A stored block with "abc": BFINAL 1, BTYPE 0, LEN 3, NLEN 0xFFFC.
        val crc = Crc32.of("abc".encodeToByteArray())
        val data = bytes(0x1f, 0x8b, 8, 0, 0, 0, 0, 0, 0, 0xff, 0x01, 3, 0, 0xfc, 0xff, 'a'.code, 'b'.code, 'c'.code) +
            bytes(crc and 0xFF, (crc ushr 8) and 0xFF, (crc ushr 16) and 0xFF, (crc ushr 24) and 0xFF, 3, 0, 0, 0)
        assertEquals("abc", Gzip.decompress(data).decodeToString())
    }

    @Test
    fun twoMembersGiveTheTwoTexts() {
        assertEquals("hello hello hello hello".repeat(2), Gzip.decompress(hello + hello).decodeToString())
    }

    @Test
    fun aBadCrcIsAnError() {
        val bad = hello.copyOf().also { data -> data[data.size - 8] = (data[data.size - 8] + 1).toByte() }
        assertFailsWith<GzipException> { Gzip.decompress(bad) }
    }

    @Test
    fun theOutputLimitStopsTheDecoder() {
        val error = assertFailsWith<GzipSizeException> { Gzip.decompress(hello, maxOutput = 10) }
        assertEquals(10, error.limit)
    }

    @Test
    fun truncatedDataIsAnError() {
        assertFailsWith<GzipException> { Gzip.decompress(hello.copyOf(20)) }
        assertFailsWith<GzipException> { Gzip.decompress(bytes(0x1f, 0x8b)) }
    }

    @Test
    fun theCrc32OfTheCheckTextIsTheStandardValue() {
        assertEquals(0xCBF43926.toInt(), Crc32.of("123456789".encodeToByteArray()))
    }
}
