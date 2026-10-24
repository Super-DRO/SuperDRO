package org.superdro.core.mcp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Base64 of the token and of the header values. */
class Base64TextTest {

    @Test
    fun theUrlSafeFormOf32BytesHas43CharactersAndNoPadding() {
        val bytes = ByteArray(32) { index -> (index * 7 + 250).toByte() }
        val text = Base64Text.encodeUrlSafe(bytes)
        assertEquals(43, text.length)
        assertEquals(-1, text.indexOf('='))
        assertEquals(-1, text.indexOf('+'))
        assertEquals(-1, text.indexOf('/'))
        assertContentEquals(bytes, Base64Text.decode(text))
    }

    @Test
    fun theStandardFormMatchesTheKnownVectors() {
        assertEquals("", Base64Text.encodeStandard(ByteArray(0)))
        assertEquals("Zg==", Base64Text.encodeStandard("f".encodeToByteArray()))
        assertEquals("Zm8=", Base64Text.encodeStandard("fo".encodeToByteArray()))
        assertEquals("Zm9v", Base64Text.encodeStandard("foo".encodeToByteArray()))
        assertEquals("SGVsbG8sIOS4lueVjA==", Base64Text.encodeStandard("Hello, 世界".encodeToByteArray()))
    }

    @Test
    fun theDecoderAcceptsBothAlphabetsAndRefusesOtherCharacters() {
        assertEquals("foo", Base64Text.decode("Zm9v")?.decodeToString())
        assertEquals("fo", Base64Text.decode("Zm8")?.decodeToString())
        assertContentEquals(byteArrayOf(0xFB.toByte(), 0xFF.toByte()), Base64Text.decode("+/8="))
        assertContentEquals(byteArrayOf(0xFB.toByte(), 0xFF.toByte()), Base64Text.decode("-_8"))
        assertNull(Base64Text.decode("Zm9*"))
        assertNull(Base64Text.decode("Z"))
    }
}
