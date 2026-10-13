package org.superdro.core.protocol

import kotlin.random.Random
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The regular expression tests: generated strings for each expression of the message table. A
 * string that matches gives the type of its row. A string that does not match gives `Unknown`.
 */
class AdapterParserRegexTest {

    private val numeric = Regex("^[ABCDEFRSTUVWXYZabcdefrstuvwxyz.]-?[0-9]+;$")
    private val boolean = Regex("^[PpQq][01];$")
    private val timestamp = Regex("^@[0-9A-Fa-f]+;$")
    private val status = Regex("^\\$[0-9A-Fa-f]{2}[0-9A-Fa-f]{1,4};$")
    private val error = Regex("^![0-9A-Fa-f]{2}[0-9A-Fa-f]{1,4};$")
    private val info = Regex("^\\?(?!64)[0-9A-Fa-f]{2}[\\x20-\\x3A\\x3C-\\x7E]{0,256};$")
    private val signature = Regex("^\\?64[A-Za-z0-9+/]+=*;$")

    /** The oracle: the row of the table that the message matches, with the extra rules, or null for `Unknown`. */
    private fun expectedType(message: String): KClass<out AdapterMessage>? = when {
        numeric.matches(message) -> {
            val value = message.substring(1, message.length - 1).toBigValueOrNull()
            if (value != null) AdapterMessage.AxisCount::class else null
        }
        boolean.matches(message) -> AdapterMessage.BinaryInput::class
        timestamp.matches(message) -> {
            val digits = message.substring(1, message.length - 1).trimStart('0')
            val inRange = digits.length < 11 || (digits.length == 11 && digits.lowercase() <= "3e7fffffc18")
            if (inRange) AdapterMessage.Timestamp::class else null
        }
        status.matches(message) -> AdapterMessage.Status::class
        error.matches(message) -> AdapterMessage.Error::class
        info.matches(message) -> AdapterMessage.Info::class
        signature.matches(message) -> {
            val length = message.length - 4
            if (length in 4..1024 && length % 4 == 0) AdapterMessage.Signature::class else null
        }
        else -> null
    }

    /** Gives the value of a decimal text when it is in the 32-bit range, else null. */
    private fun String.toBigValueOrNull(): Int? {
        val negative = startsWith("-")
        val digits = removePrefix("-").trimStart('0').ifEmpty { "0" }
        if (digits.length > 10) return null
        val value = digits.toLong()
        return if (negative) (-value).takeIf { it >= Int.MIN_VALUE }?.toInt() else value.takeIf { it <= Int.MAX_VALUE }?.toInt()
    }

    private fun assertParses(message: String) {
        val messages = AdapterParser().feed(message, 0L)
        val expected = expectedType(message)
        if (expected != null) {
            assertEquals(1, messages.size, "\"$message\" gives $messages")
            assertEquals(expected, messages.single()::class, "\"$message\"")
        } else {
            assertTrue(messages.isNotEmpty(), "\"$message\" gives no message")
            assertEquals(AdapterMessage.Unknown::class, messages.first()::class, "\"$message\" gives $messages")
        }
    }

    private val random = Random(20261012)
    private val hex = "0123456789abcdefABCDEF"
    private val base64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private fun Random.pick(characters: String): Char = characters[nextInt(characters.length)]
    private fun Random.text(characters: String, length: Int): String = buildString { repeat(length) { append(pick(characters)) } }
    private val printable = (0x20..0x7E).map { it.toChar() }.filter { it != ';' }.joinToString("")

    @Test
    fun generatedMatchesGiveTheTypeOfTheirRow() {
        repeat(300) {
            val numericMessage = random.pick("ABCDEFRSTUVWXYZabcdefrstuvwxyz.") +
                (if (random.nextBoolean()) "-" else "") + random.text("0123456789", random.nextInt(1, 11)) + ";"
            assertParses(numericMessage)
            assertParses(random.pick("PpQq") + random.pick("01").toString() + ";")
            assertParses("@" + random.text(hex, random.nextInt(1, 12)) + ";")
            assertParses("$" + random.text(hex, 2) + random.text(hex, random.nextInt(1, 5)) + ";")
            assertParses("!" + random.text(hex, 2) + random.text(hex, random.nextInt(1, 5)) + ";")
            var field = random.text(hex, 2)
            if (field == "64") field = "65"
            assertParses("?" + field + random.text(printable, random.nextInt(0, 257)) + ";")
            val body = random.text(base64, random.nextInt(1, 300))
            val padded = body + "=".repeat((4 - body.length % 4) % 4)
            assertParses("?64$padded;")
        }
    }

    @Test
    fun theLimitsOfEachRowAreExact() {
        listOf(
            "X2147483647;", "X-2147483648;", "X2147483648;", "X-2147483649;", "X00000000000000000007;",
            "X99999999999999999999;", "@3E7FFFFFC18;", "@3E7FFFFFC19;", "@0003E7FFFFFC18;", "@1000000000000;",
            "?03" + "a".repeat(256) + ";", "?03" + "a".repeat(257) + ";",
            "?64" + "QUJD".repeat(256) + ";", "?64" + "QUJD".repeat(256) + "AAAA;", "?64QQ==;", "?64Q===;",
            "?64QUJ;", "?64;", "?64====;", "?64QU=J;", "\$0A;", "\$0A1234;", "\$0A12345;", "!5;", "?5;", "\$5;",
            "P2;", "P;", "P10;", "X;", "X-;", "X+1;", "@;", "@G1;", "F5;", "f5;", ".3;", ".-3;",
        ).forEach { assertParses(it) }
    }

    @Test
    fun generatedStringsThatDoNotMatchGiveUnknown() {
        val starts = "XxYyAaFf.PpQq@\$!?"
        val body = "0123456789aAfFxX-=+/ :;#*QZ6"
        var unknown = 0
        repeat(5000) {
            val text = random.pick(starts) + random.text(body.replace(";", ""), random.nextInt(0, 8)) + ";"
            assertParses(text)
            if (expectedType(text) == null) unknown++
        }
        assertTrue(unknown > 1000, "Only $unknown strings that do not match")
    }
}
