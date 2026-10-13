package org.superdro.core.functions.tipcal

import kotlin.test.Test
import kotlin.test.assertEquals

/** The FIPS 180-4 test vectors of SHA-256. The tip calibration digest uses this hash. */
class Sha256Test {

    @Test
    fun theEmptyMessageGivesTheVectorDigest() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hexOfUtf8(""))
    }

    @Test
    fun abcGivesTheVectorDigest() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hexOfUtf8("abc"))
    }

    @Test
    fun theTwoBlockMessageGivesTheVectorDigest() {
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.hexOfUtf8("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
        )
    }

    @Test
    fun oneMillionLettersAGiveTheVectorDigest() {
        val message = ByteArray(1_000_000) { 'a'.code.toByte() }
        assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", Sha256.hex(message))
    }

    @Test
    fun aTextWithMultiByteCharactersUsesItsUtf8Bytes() {
        // The value comes from Python hashlib.sha256("Ø 50.000 mm".encode()).
        assertEquals("262c052bb87b70fa073331a60bf486eca4b28e2d989bab83e931a6f2af17d058", Sha256.hexOfUtf8("Ø 50.000 mm"))
    }

    @Test
    fun theDigestHas32BytesAnd64LowercaseHexDigits() {
        assertEquals(Sha256.DIGEST_BYTES, Sha256.digest("abc".encodeToByteArray()).size)
        val text = Sha256.hexOfUtf8("SuperDRO")
        assertEquals(64, text.length)
        assertEquals(text.lowercase(), text)
    }
}
