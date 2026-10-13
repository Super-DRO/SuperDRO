package org.superdro.core.util

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BytesTest {

    @Test
    fun asciiGivesOneByteForEachCharacter() {
        assertContentEquals(byteArrayOf(0x23, 0x67, 0x65, 0x74, 0x3A), Bytes.ascii("#get:"))
    }

    @Test
    fun asciiChangesACharacterAbove0x7FToAQuestionMark() {
        assertContentEquals(byteArrayOf(0x61, 0x3F), Bytes.ascii("aµ"))
    }

    @Test
    fun asciiTextReadsARange() {
        val bytes = Bytes.ascii("x1234;y-56;")
        assertEquals("y-56;", Bytes.asciiText(bytes, offset = 6, length = 5))
        assertEquals("x1234;y-56;", Bytes.asciiText(bytes))
    }

    @Test
    fun asciiTextChangesAByteAbove0x7FToTheReplacementCharacter() {
        assertEquals("a�", Bytes.asciiText(byteArrayOf(0x61, 0xC3.toByte())))
    }

    @Test
    fun hexGoesBothWays() {
        val bytes = byteArrayOf(0x24, 0x6F, 0x28, 0xA1.toByte(), 0xB2.toByte(), 0xC3.toByte())
        assertEquals("246f28a1b2c3", Bytes.toHex(bytes))
        assertContentEquals(bytes, Bytes.fromHex("246F28a1b2c3"))
    }

    @Test
    fun aBadHexTextFails() {
        assertFailsWith<IllegalArgumentException> { Bytes.fromHex("abc") }
        assertFailsWith<IllegalArgumentException> { Bytes.fromHex("zz") }
    }

    @Test
    fun theBelByteIs0x07() {
        assertEquals(0x07.toByte(), Bytes.BEL)
    }

    @Test
    fun printableShowsControlBytesAsEscapes() {
        assertEquals("x1234;\\x07\\x0a", Bytes.printable(Bytes.ascii("x1234;") + byteArrayOf(0x07, 0x0A)))
    }
}
