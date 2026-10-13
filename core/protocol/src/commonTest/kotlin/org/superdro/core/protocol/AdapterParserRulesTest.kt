package org.superdro.core.protocol

import org.superdro.core.protocol.AdapterMessage.AxisCount
import org.superdro.core.protocol.AdapterMessage.BinaryInput
import org.superdro.core.protocol.AdapterMessage.Error
import org.superdro.core.protocol.AdapterMessage.Info
import org.superdro.core.protocol.AdapterMessage.Signature
import org.superdro.core.protocol.AdapterMessage.Status
import org.superdro.core.protocol.AdapterMessage.Timestamp
import org.superdro.core.protocol.AdapterMessage.Unknown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The parser rules and the letter tests. */
class AdapterParserRulesTest {

    private fun parse(text: String, timeMs: Long = 0L): List<AdapterMessage> = AdapterParser().feed(text, timeMs)

    @Test
    fun dropsTheBytesOutsideThePrintableRange() {
        val parser = AdapterParser()
        val bytes = byteArrayOf(0x00, 0x1F, 0x7F, 0x80.toByte(), 0xFF.toByte()) + "?03a".encodeToByteArray() +
            byteArrayOf(0x0D, 0x0A) + "b;".encodeToByteArray()
        assertEquals(listOf(Info(3, "ab")), parser.feed(bytes, 0L))
        assertEquals(0L, parser.junkCount)
    }

    @Test
    fun countsTheJunkOutOfAMessage() {
        val parser = AdapterParser()
        assertEquals(emptyList(), parser.feed("ghij;#-0", 0L))
        assertEquals(8L, parser.junkCount)
        assertFalse(parser.inMessage)
    }

    @Test
    fun recordsTheArrivalTimeOfTheChunkWithTheStartCharacter() {
        val parser = AdapterParser()
        parser.feed("ghi", 10L)
        parser.feed("Y", 20L)
        assertEquals(listOf(AxisCount('Y', 42, 20L)), parser.feed("42;", 30L))
    }

    @Test
    fun endsTheMessageAndStartsANewOneAtAStartCharacter() {
        assertEquals(listOf(Unknown("X12"), AxisCount('Y', 34, 0L)), parse("X12Y34;"))
        assertEquals(listOf(Unknown("\$0A"), Status(0x0A, 0xA3)), parse("\$0A\$0AA3;"))
        assertEquals(listOf(Unknown("?0"), Info(5, "x")), parse("?0?05x;"))
    }

    @Test
    fun aStartCharacterInsideAnInfoTextIsText() {
        assertEquals(listOf(Info(3, "X12")), parse("?03X12;"))
    }

    @Test
    fun checksTheRangeAtEachDigit() {
        assertEquals(listOf(AxisCount('X', 2147483647, 0L)), parse("X2147483647;"))
        assertEquals(listOf(AxisCount('X', -2147483648, 0L)), parse("X-2147483648;"))
        assertEquals(listOf(Unknown("X214748364")), parse("X2147483648;"))
        assertEquals(listOf(Unknown("X-214748364")), parse("X-2147483649;"))
        assertEquals(listOf(Unknown("X999999999")), parse("X99999999999999999999;"))
        assertEquals(listOf(AxisCount('X', 7, 0L)), parse("X007;"))
        assertEquals(listOf(AxisCount('X', 7, 0L)), parse("X0000000000000000000007;"))
    }

    @Test
    fun refusesAnEmptyValueAndAPlusSign() {
        assertEquals(listOf(Unknown("X")), parse("X;"))
        assertEquals(listOf(Unknown("X-")), parse("X-;"))
        assertEquals(listOf(Unknown("X")), parse("X+1;"))
        assertEquals(listOf(Unknown("X-")), parse("X--1;"))
    }

    @Test
    fun acceptsOnlyOneBinaryDigit() {
        assertEquals(listOf(BinaryInput('P', 1, 0L)), parse("P1;"))
        assertEquals(listOf(BinaryInput('Q', 0, 0L)), parse("q0;"))
        assertEquals(listOf(Unknown("P")), parse("P2;"))
        assertEquals(listOf(Unknown("P")), parse("P;"))
        assertEquals(listOf(Unknown("P1")), parse("P10;"))
        assertEquals(listOf(Unknown("P1"), Unknown("x")), parse("P1x;"))
    }

    @Test
    fun needsOneOrMoreHexDigitsAndTheLimit() {
        assertEquals(listOf(Timestamp(0x3E7FFFFFC18, 0L)), parse("@3E7FFFFFC18;"))
        assertEquals(listOf(Unknown("@3E7FFFFFC1")), parse("@3E7FFFFFC19;"))
        assertEquals(listOf(Unknown("@")), parse("@;"))
        assertEquals(listOf(Unknown("@")), parse("@G1;"))
        assertEquals(listOf(Unknown("@1"), AxisCount('X', 1, 0L)), parse("@1X1;"))
        assertEquals(listOf(Timestamp(0x1F4, 0L)), parse("@1f4;"))
    }

    @Test
    fun needsTwoSubsystemDigitsAndOneToFourCodeDigits() {
        assertEquals(listOf(Status(0x0A, 0xA3)), parse("\$0AA3;"))
        assertEquals(listOf(Status(0x0A, 0x1234)), parse("\$0a1234;"))
        assertEquals(listOf(Error(0x78, 0x16)), parse("!7816;"))
        assertEquals(listOf(Unknown("\$0A1234")), parse("\$0A12345;"))
        assertEquals(listOf(Unknown("\$0A1234"), Unknown("a")), parse("\$0A1234a;"))
        assertEquals(listOf(Unknown("\$0A")), parse("\$0A;"))
        assertEquals(listOf(Unknown("\$5")), parse("\$5;"))
        assertEquals(listOf(Unknown("!5")), parse("!5;"))
        assertEquals(listOf(Unknown("\$")), parse("\$;"))
        assertFalse(parse("\$5;").single().isValid)
    }

    @Test
    fun keepsZeroTo256TextCharacters() {
        assertEquals(listOf(Info(3, "")), parse("?03;"))
        val text256 = "a".repeat(256)
        assertEquals(listOf(Info(3, text256)), parse("?03$text256;"))
        // The 257th character ends the message. "b" is a start character, so it starts a new message.
        assertEquals(listOf(Unknown("?03$text256"), Unknown("b")), parse("?03${text256}b;"))
        assertEquals(listOf(Unknown("?03$text256")), parse("?03${text256}g;"))
        assertEquals(listOf(Info(0x65, "abc")), parse("?65abc;"))
        assertEquals(listOf(Info(0xFF, "")), parse("?ff;"))
    }

    @Test
    fun usesTheStrictSignatureRule() {
        assertEquals(listOf(Signature("QUJD")), parse("?64QUJD;"))
        assertEquals(listOf(Signature("QUJDRA==")), parse("?64QUJDRA==;"))
        assertEquals(listOf(Signature("a+/9")), parse("?64a+/9;"))
        assertEquals(listOf(Unknown("?64QUJ")), parse("?64QUJ;"))
        assertEquals(listOf(Unknown("?64")), parse("?64;"))
        assertEquals(listOf(Unknown("?64")), parse("?64====;"))
        assertEquals(listOf(Unknown("?64QU=")), parse("?64QU=J;"))
        assertEquals(listOf(Unknown("?64QUJ")), parse("?64QUJ*;"))
        assertEquals(listOf(Unknown("?64QUJ"), AxisCount('X', 1, 0L)), parse("?64QUJ;X1;"))
        val text1024 = "QUJD".repeat(256)
        assertEquals(listOf(Signature(text1024)), parse("?64$text1024;"))
        // The parser keeps 1024 characters or less. A longer text gives Unknown at ";".
        assertEquals(listOf(Unknown("?64$text1024")), parse("?64${text1024}AAAA;"))
        assertEquals(listOf(Unknown("?64$text1024")), parse("?64$text1024${"A".repeat(5000)};"))
    }

    @Test
    fun refusesTheShortFormOfInfo() {
        assertEquals(listOf(Unknown("?5")), parse("?5;"))
        assertEquals(listOf(Unknown("?")), parse("?;"))
    }

    @Test
    fun hexDigitsAreNotCaseSensitive() {
        assertEquals(parse("\$0aa3;").single(), parse("\$0AA3;").single())
        assertEquals(listOf(Info(10, "1")), parse("?0A1;"))
        assertEquals(listOf(Info(10, "1")), parse("?0a1;"))
        assertEquals(parse("@3e8;").single(), parse("@3E8;").single())
    }

    @Test
    fun theUpperCaseFIsAPrefix() {
        assertEquals(listOf(AxisCount('F', 5, 0L)), parse("F5;"))
        assertEquals(listOf(AxisCount('F', 5, 0L)), parse("f5;"))
    }

    @Test
    fun theLettersAToFRAndSAreValidButCarryNoValue() {
        val messages = parse("A12;f-3;r1;S0;")
        assertEquals(
            listOf(AxisCount('A', 12, 0L), AxisCount('F', -3, 0L), AxisCount('R', 1, 0L), AxisCount('S', 0, 0L)),
            messages,
        )
        messages.forEach { message ->
            assertTrue(message.isValid, "$message counts for the watchdog")
            val count = message as AxisCount
            assertFalse(count.carriesValue, "$message gives no axis a value")
            assertFalse(AdapterLetters.isProbePort(count.channel), "$message gives no probe a value")
            assertTrue(AdapterLetters.isIgnored(count.channel))
        }
    }

    @Test
    fun onlyXYZWUVTCarryValuesAndPQAreTheProbePorts() {
        "XYZWUVTxyzwuvt".forEach { assertTrue(AdapterLetters.carriesValue(it), "$it") }
        "ABCDEFRSPQ.abcdefrspq".forEach { assertFalse(AdapterLetters.carriesValue(it), "$it") }
        "PQpq".forEach { assertTrue(AdapterLetters.isProbePort(it), "$it") }
        "XYZWUVTRSABCDEF.".forEach { assertFalse(AdapterLetters.isProbePort(it), "$it") }
        assertEquals(listOf(AxisCount('X', 5, 0L)), parse("x5;"))
        assertEquals('x', (parse("X5;").single() as AxisCount).letter)
        assertEquals('p', (parse("P1;").single() as BinaryInput).letter)
    }

    @Test
    fun theDotReadoutIsTheDirtyStatus() {
        val dirty = parse(".3;").single() as AxisCount
        assertEquals(AxisCount('.', 3, 0L), dirty)
        assertTrue(dirty.isValid)
        assertTrue(dirty.isDirtyStatus)
        assertFalse(dirty.carriesValue)
    }

    @Test
    fun theValidMessageTypes() {
        val valid = parse("X1;P1;\$0aa3;!0a21;?03a;?64QUJD;")
        assertEquals(6, valid.size)
        assertTrue(valid.all { it.isValid })
        assertFalse(parse("@3E8;").single().isValid)
        assertFalse(parse("X;").single().isValid)
    }
}
