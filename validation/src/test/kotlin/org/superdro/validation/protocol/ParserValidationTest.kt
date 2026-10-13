package org.superdro.validation.protocol

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.superdro.core.protocol.AdapterMessage
import org.superdro.core.protocol.AdapterMessage.AxisCount
import org.superdro.core.protocol.AdapterMessage.BinaryInput
import org.superdro.core.protocol.AdapterMessage.Error
import org.superdro.core.protocol.AdapterMessage.Info
import org.superdro.core.protocol.AdapterMessage.Signature
import org.superdro.core.protocol.AdapterMessage.Status
import org.superdro.core.protocol.AdapterMessage.Timestamp
import org.superdro.core.protocol.AdapterMessage.Unknown
import org.superdro.core.protocol.AdapterParser

/**
 * The character set, the grammar, the regular
 * expression table, the parser rules, and the golden byte stream. The expected values are
 * independent of the implementation.
 */
class ParserValidationTest {

    private fun parse(text: String, timeMs: Long = 0L): List<AdapterMessage> = AdapterParser().feed(text.encodeToByteArray(), timeMs)

    private fun parseBytes(vararg bytes: Int): List<AdapterMessage> =
        AdapterParser().feed(ByteArray(bytes.size) { index -> bytes[index].toByte() }, 0L)

    /** A message with no time, so that two parses with different chunk times can be compared. */
    private fun AdapterMessage.untimed(): AdapterMessage = when (this) {
        is AxisCount -> copy(timeMs = 0L)
        is BinaryInput -> copy(timeMs = 0L)
        is Timestamp -> copy(timeMs = 0L)
        else -> this
    }

    // The golden stream.

    private val t0 = 10_000L
    private val t1 = 20_000L

    /** The rows of the golden stream with the arrival time of each row. */
    private val goldenRows: List<Pair<Long, String>> = listOf(
        t0 to "X1200;",
        t1 to "@3E8;",
        t1 to "Y-350;",
        t1 to "P1;",
        t1 + 1_000L to "@7D0;",
        t1 + 1_000L to "T1500;\r\n",
        t1 + 1_000L to "\$0AA3;",
        t1 + 1_000L to "?0530aea4a1b2c3;",
        t1 + 1_000L to "!0A21;",
        t1 + 1_000L to "X12 3;",
        t1 + 1_000L to "F5;",
        t1 + 1_000L to "f5;",
        t1 + 1_000L to "X12Y-5;",
        t1 + 1_000L to "P10;",
        t1 + 1_000L to "?64QUJD;",
        t1 + 1_000L to "?64QUJDRA==;",
        t1 + 1_000L to "?64QUJ;X1;",
        t1 + 1_000L to "\$5;",
        t1 + 1_000L to "\$3205;",
        t1 + 1_000L to "?64QUJ*RA==;",
        t1 + 1_000L to "@;",
        t1 + 1_000L to "P1x;",
    )

    private val goldenText: String = goldenRows.joinToString("") { it.second }

    private val t2 = t1 + 1_000L

    /**
     * The expected messages of the golden stream. The table gives "…" for the times of the rows after
     * `T1500;`. All these rows come after `@7D0;`, so their time is `tAdapter = t1 + 1000`.
     */
    private val goldenExpected: List<AdapterMessage> = listOf(
        AxisCount('X', 1200, t0),
        Timestamp(1000L, t1),
        AxisCount('Y', -350, t1),
        BinaryInput('P', 1, t1),
        Timestamp(2000L, t1 + 1_000L),
        AxisCount('T', 1500, t1 + 1_000L),
        Status(0x0A, 0xA3),
        Info(5, "30aea4a1b2c3"),
        Error(0x0A, 0x21),
        Unknown("X12"),
        AxisCount('F', 5, t2),
        AxisCount('F', 5, t2),
        Unknown("X12"),
        AxisCount('Y', -5, t2),
        Unknown("P1"),
        Signature("QUJD"),
        Signature("QUJDRA=="),
        Unknown("?64QUJ"),
        AxisCount('X', 1, t2),
        Unknown("\$5"),
        Status(0x32, 0x05),
        // `*` ends the message and is junk; then `R` and `A` are start
        // characters, and each starts a message that a character after it ends.
        Unknown("?64QUJ"),
        Unknown("R"),
        Unknown("A"),
        Unknown("@"),
        Unknown("P1"),
        Unknown("x"),
    )

    private fun parseGoldenByRows(): List<AdapterMessage> {
        val parser = AdapterParser()
        return goldenRows.flatMap { (time, text) -> parser.feed(text.encodeToByteArray(), time) }
    }

    @Test
    fun golden_stream_gives_the_expected_messages() {
        assertEquals(goldenExpected, parseGoldenByRows())
    }

    @Test
    fun golden_stream_rows_with_their_own_results() {
        assertEquals(listOf(AxisCount('X', 1200, 5L)), parse("X1200;", 5L))
        assertEquals(listOf(Unknown("X12")), parse("X12 3;"))
        assertEquals(listOf(AxisCount('F', 5, 0L)), parse("F5;"))
        assertEquals(listOf(AxisCount('F', 5, 0L)), parse("f5;"))
        assertEquals(listOf(Unknown("X12"), AxisCount('Y', -5, 0L)), parse("X12Y-5;"))
        assertEquals(listOf(Unknown("P1")), parse("P10;"))
        assertEquals(listOf(Signature("QUJD")), parse("?64QUJD;"))
        assertEquals(listOf(Signature("QUJDRA==")), parse("?64QUJDRA==;"))
        val absorbed = parse("?64QUJ;X1;")
        assertEquals(2, absorbed.size)
        assertIs<Unknown>(absorbed[0])
        assertEquals(AxisCount('X', 1, 0L), absorbed[1])
        assertEquals(listOf(Unknown("\$5")), parse("\$5;"))
        assertEquals(listOf(Status(0x32, 0x05)), parse("\$3205;"))
        assertEquals(listOf(Unknown("@")), parse("@;"))
        assertEquals(listOf(Unknown("P1"), Unknown("x")), parse("P1x;"))
    }

    @Test
    fun golden_row_with_a_character_that_is_not_base64_gives_only_unknown_messages() {
        val messages = parse("?64QUJ*RA==;")
        assertTrue(messages.isNotEmpty())
        assertIs<Unknown>(messages.first())
        assertTrue(messages.none { it.isValid }, "messages: $messages")
    }

    @Test
    fun golden_hex_bytes() {
        assertEquals(listOf(AxisCount('X', 1200, 0L)), parseBytes(0x58, 0x31, 0x32, 0x30, 0x30, 0x3B))
        assertEquals(listOf(AxisCount('Y', -350, 0L)), parseBytes(0x59, 0x2D, 0x33, 0x35, 0x30, 0x3B))
        assertEquals(listOf(BinaryInput('P', 1, 0L)), parseBytes(0x50, 0x31, 0x3B))
        assertEquals(listOf(AxisCount('T', 1500, 0L)), parseBytes(0x54, 0x31, 0x35, 0x30, 0x30, 0x3B, 0x0D, 0x0A))
        assertEquals(listOf(Status(0x0A, 0xA3)), parseBytes(0x24, 0x30, 0x41, 0x41, 0x33, 0x3B))
        assertEquals(listOf(AxisCount('F', 5, 0L)), parseBytes(0x46, 0x35, 0x3B))
        assertEquals(listOf(AxisCount('F', 5, 0L)), parseBytes(0x66, 0x35, 0x3B))
        val stamps = AdapterParser().feed(byteArrayOf(0x40, 0x33, 0x45, 0x38, 0x3B, 0x40, 0x37, 0x44, 0x30, 0x3B), 3_000L)
        assertEquals(listOf(1000L, 2000L), stamps.map { (it as Timestamp).adapterTime })
    }

    // The split tests.

    @Test
    fun split_golden_stream_in_chunks_of_1_2_7_and_64_bytes_gives_the_same_messages() {
        val bytes = goldenText.encodeToByteArray()
        val whole = AdapterParser().feed(bytes, 0L)
        assertEquals(goldenExpected.map { it.untimed() }, whole.map { it.untimed() })
        for (size in listOf(1, 2, 7, 64)) {
            val parser = AdapterParser()
            val messages = bytes.toList().chunked(size).flatMap { chunk -> parser.feed(chunk.toByteArray(), 0L) }
            assertEquals(whole, messages, "chunks of $size bytes")
        }
    }

    @Test
    fun split_chunk_time_of_a_message_is_the_time_of_its_first_chunk() {
        val parser = AdapterParser()
        assertEquals(emptyList(), parser.feed("X12".encodeToByteArray(), 100L))
        assertEquals(listOf(AxisCount('X', 1234, 100L)), parser.feed("34;".encodeToByteArray(), 150L))
        assertEquals(listOf(AxisCount('Y', 1, 150L)), parser.feed("Y1;".encodeToByteArray(), 150L))
    }

    // The noise tests.

    @Test
    fun noise_bytes_outside_0x20_to_0x7e_are_dropped_also_inside_a_message() {
        val random = Random(17)
        val noise = (0x00..0x1F) + (0x7F..0xFF)
        val clean = goldenText.replace("\r\n", "")
        val noisy = ArrayList<Byte>()
        for (character in clean) {
            repeat(random.nextInt(3)) { noisy += noise[random.nextInt(noise.size)].toByte() }
            noisy += character.code.toByte()
        }
        val expected = AdapterParser().feed(clean.encodeToByteArray(), 0L)
        assertEquals(expected, AdapterParser().feed(noisy.toByteArray(), 0L))
        assertEquals(listOf(AxisCount('X', 1200, 0L)), parseBytes(0x58, 0x31, 0x0D, 0x00, 0x32, 0x80, 0xFF, 0x30, 0x7F, 0x30, 0x1F, 0x3B))
    }

    @Test
    fun noise_line_noise_between_messages_gives_the_same_messages() {
        val random = Random(23)
        // Printable characters that are not start characters.
        val lineNoise = (0x20..0x7E).map { it.toChar() }.filterNot { character ->
            character.lowercaseChar() in "abcdefpqrstuvwxyz" || character in "@\$!?."
        }
        val messages = listOf("X1200;", "Y-350;", "P1;", "\$0aa3;", "?05246f28a1b2c3;", "!0a21;", "T1500;", "@3E8;", "Z7;")
        val expected = AdapterParser().feed(messages.joinToString("").encodeToByteArray(), 0L)
        repeat(50) {
            val text = buildString {
                for (message in messages) {
                    repeat(random.nextInt(6)) { append(lineNoise[random.nextInt(lineNoise.size)]) }
                    append(message)
                }
            }
            assertEquals(expected, AdapterParser().feed(text.encodeToByteArray(), 0L), text)
        }
    }

    @Test
    fun noise_with_start_characters_recovers_at_the_next_start_character() {
        val parser = AdapterParser()
        val messages = parser.feed("X12 3;~%&\$5;Y12;\u0000ÿ\u007FZ-4;?5;P1;".encodeToByteArray(), 0L)
        assertEquals(listOf(Unknown("X12"), Unknown("\$5"), AxisCount('Y', 12, 0L), AxisCount('Z', -4, 0L), Unknown("?5"), BinaryInput('P', 1, 0L)), messages)
    }

    // Junk characters out of a message.

    @Test
    fun characters_out_of_a_message_are_counted_as_junk() {
        val parser = AdapterParser()
        parser.feed("X1;".encodeToByteArray(), 0L)
        val before = parser.junkCount
        assertEquals(emptyList(), parser.feed("3;#g".encodeToByteArray(), 0L))
        assertEquals(before + 4, parser.junkCount)
        assertFalse(parser.inMessage)
    }

    // Numeric readout.

    @Test
    fun numeric_readout_range_and_forms() {
        assertEquals(listOf(AxisCount('X', 2147483647, 0L)), parse("X2147483647;"))
        assertEquals(listOf(AxisCount('X', -2147483648, 0L)), parse("X-2147483648;"))
        assertIs<Unknown>(parse("X2147483648;").first())
        assertTrue(parse("X2147483648;").none { it.isValid })
        assertIs<Unknown>(parse("X-2147483649;").first())
        assertTrue(parse("X12345678901234567890;").none { it.isValid })
        assertTrue(parse("X99999999999999999999999;").none { it.isValid })
        assertEquals(listOf(AxisCount('X', 7, 0L)), parse("X007;"))
        assertEquals(listOf(AxisCount('X', 2147483647, 0L)), parse("X00000000000002147483647;"))
        assertEquals(listOf(Unknown("X")), parse("X;"))
        assertEquals(listOf(Unknown("X-")), parse("X-;"))
        assertIs<Unknown>(parse("X+1;").first())
        assertTrue(parse("X+1;").none { it.isValid })
        assertIs<Unknown>(parse("X--1;").first())
        assertEquals(listOf(AxisCount('X', 0, 0L)), parse("X-0;"))
    }

    @Test
    fun each_numeric_prefix_in_both_cases() {
        for (letter in "ABCDEFRSTUVWXYZ") {
            assertEquals(listOf(AxisCount(letter, 42, 0L)), parse("${letter}42;"), "$letter")
            assertEquals(listOf(AxisCount(letter, -42, 0L)), parse("${letter.lowercaseChar()}-42;"), "${letter.lowercaseChar()}")
        }
        val dot = parse(".3;").single()
        assertIs<AxisCount>(dot)
        assertEquals(3, dot.count)
        assertTrue(dot.isValid)
    }

    // Boolean readout.

    @Test
    fun boolean_readout_forms() {
        assertEquals(listOf(BinaryInput('P', 0, 0L)), parse("P0;"))
        assertEquals(listOf(BinaryInput('P', 1, 0L)), parse("p1;"))
        assertEquals(listOf(BinaryInput('Q', 0, 0L)), parse("q0;"))
        assertEquals(listOf(BinaryInput('Q', 1, 0L)), parse("Q1;"))
        assertEquals(listOf(Unknown("P")), parse("P2;"))
        assertEquals(listOf(Unknown("P")), parse("P;"))
        assertEquals(listOf(Unknown("P1")), parse("P10;"))
        assertEquals(listOf(Unknown("Q1"), Unknown("x")), parse("Q1x;"))
        assertEquals(listOf(Unknown("P1"), AxisCount('X', 5, 0L)), parse("P1X5;"))
    }

    // Time stamp.

    @Test
    fun time_stamp_forms_and_limit() {
        assertEquals(1000L, (parse("@3E8;").single() as Timestamp).adapterTime)
        assertEquals(1000L, (parse("@3e8;").single() as Timestamp).adapterTime)
        assertEquals(0x3E7FFFFFC18L, (parse("@3E7FFFFFC18;").single() as Timestamp).adapterTime)
        assertEquals(0xFFFFFFFFL * 1000L, 0x3E7FFFFFC18L)
        assertTrue(parse("@3E7FFFFFC19;").none { it.isValid || it is Timestamp })
        assertTrue(parse("@FFFFFFFFFFFF;").none { it is Timestamp })
        assertEquals(listOf(Unknown("@")), parse("@;"))
        assertEquals(listOf(Unknown("@")), parse("@G1;"))
        assertEquals(listOf(Unknown("@"), AxisCount('X', 1, 0L)), parse("@X1;"))
    }

    // Status and error.

    @Test
    fun status_and_error_forms() {
        assertEquals(listOf(Status(0x0A, 0xA3)), parse("\$0aa3;"))
        assertEquals(listOf(Status(0x06, 0x0)), parse("\$060;"))
        assertEquals(listOf(Status(0x0A, 0x1234)), parse("\$0a1234;"))
        assertEquals(listOf(Status(0xFF, 0xFFFF)), parse("\$FFffff;"))
        assertEquals(listOf(Error(0x78, 0x16)), parse("!7816;"))
        assertEquals(listOf(Error(0x06, 0x03)), parse("!0603;"))
        assertTrue(parse("\$0a12345;").none { it.isValid })
        assertEquals(listOf(Unknown("\$0a1234")), parse("\$0a12345;"))
        assertEquals(listOf(Unknown("\$0a")), parse("\$0a;"))
        assertEquals(listOf(Unknown("\$0")), parse("\$0;"))
        assertEquals(listOf(Unknown("\$5")), parse("\$5;"))
        assertEquals(listOf(Unknown("!5")), parse("!5;"))
        assertEquals(listOf(Unknown("\$0")), parse("\$0g;"))
    }

    // Info and signature.

    @Test
    fun info_forms_and_text_limit() {
        assertEquals(listOf(Info(5, "246f28a1b2c3")), parse("?05246f28a1b2c3;"))
        assertEquals(listOf(Info(4, "tda-example")), parse("?04tda-example;"))
        assertEquals(listOf(Info(1, "x000f0002")), parse("?01x000f0002;"))
        assertEquals(listOf(Info(0x0A, "1")), parse("?0a1;"))
        assertEquals(listOf(Info(5, "")), parse("?05;"))
        assertEquals(listOf(Info(3, "A b@\$!?.X1")), parse("?03A b@\$!?.X1;"))
        val longest = "#".repeat(256)
        assertEquals(listOf(Info(3, longest)), parse("?03$longest;"))
        assertEquals(listOf(Unknown("?03$longest")), parse("?03$longest#;"))
        assertEquals(listOf(Unknown("?5")), parse("?5;"))
        assertEquals(listOf(Unknown("?")), parse("?;"))
        assertEquals(listOf(Unknown("?0")), parse("?0g;"))
    }

    @Test
    fun strict_signature_rule() {
        assertEquals(listOf(Signature("QUJD")), parse("?64QUJD;"))
        assertEquals(listOf(Signature("QUJDRA==")), parse("?64QUJDRA==;"))
        assertEquals(listOf(Signature("ab+/")), parse("?64ab+/;"))
        val max = "A".repeat(1024)
        assertEquals(listOf(Signature(max)), parse("?64$max;"))
        val tooLong = "A".repeat(1028)
        val messages = parse("?64$tooLong;X1;")
        assertIs<Unknown>(messages.first())
        assertEquals(AxisCount('X', 1, 0L), messages.last())
        assertTrue(messages.dropLast(1).none { it.isValid })
        assertIs<Unknown>(parse("?64;").single())
        assertIs<Unknown>(parse("?64QUJ;").single())
        assertIs<Unknown>(parse("?64QUJDR;").single())
        assertIs<Unknown>(parse("?64====;").first())
        assertTrue(parse("?64QU=D;").none { it.isValid })
        assertTrue(parse("?64QUJ*RA==;").none { it.isValid })
        assertTrue(parse("?64QU J;").none { it.isValid })
    }

    @Test
    fun signature_length_7_with_one_equals_is_not_a_multiple_of_4() {
        // "QUJDRA=" has 7 characters: not a multiple of 4, so the result is Unknown.
        assertTrue(parse("?64QUJDRA=;").none { it is Signature })
    }

    // The valid messages.

    @Test
    fun message_types_valid_for_the_session_states() {
        assertTrue(parse("X1;").single().isValid)
        assertTrue(parse("P1;").single().isValid)
        assertTrue(parse("\$0aa3;").single().isValid)
        assertTrue(parse("!0a21;").single().isValid)
        assertTrue(parse("?05;").single().isValid)
        assertTrue(parse("?64QUJD;").single().isValid)
        assertFalse(parse("@3E8;").single().isValid)
        assertFalse(parse("X;").single().isValid)
    }

    // The letters A to F, R, and S (the parser part).

    @Test
    fun letters_A_to_F_R_and_S_are_valid_messages() {
        val messages = parse("A12;f-3;r1;S0;")
        assertEquals(listOf(AxisCount('A', 12, 0L), AxisCount('F', -3, 0L), AxisCount('R', 1, 0L), AxisCount('S', 0, 0L)), messages)
        assertTrue(messages.all { it.isValid })
        assertTrue(messages.all { message -> !(message as AxisCount).carriesValue })
        assertTrue("XYZWUVT".all { letter -> (parse("${letter}1;").single() as AxisCount).carriesValue })
    }

    // The regular expression tests.

    private val numeric = Regex("""^[ABCDEFRSTUVWXYZabcdefrstuvwxyz.]-?[0-9]+;$""")
    private val boolean = Regex("""^[PpQq][01];$""")
    private val timestamp = Regex("""^@[0-9A-Fa-f]+;$""")
    private val status = Regex("""^\$[0-9A-Fa-f]{2}[0-9A-Fa-f]{1,4};$""")
    private val error = Regex("""^![0-9A-Fa-f]{2}[0-9A-Fa-f]{1,4};$""")
    private val info = Regex("""^\?(?!64)[0-9A-Fa-f]{2}[\x20-\x3A\x3C-\x7E]{0,256};$""")
    private val signature = Regex("""^\?64[A-Za-z0-9+/]+=*;$""")

    /** The type that the message table gives for one message [s], or null for `Unknown`. */
    private fun tableType(s: String): String? = when {
        numeric.matches(s) -> {
            val digits = s.substring(1, s.length - 1)
            val value = digits.toBigInteger()
            if (value >= Int.MIN_VALUE.toBigInteger() && value <= Int.MAX_VALUE.toBigInteger()) "AxisCount" else null
        }
        boolean.matches(s) -> "BinaryInput"
        timestamp.matches(s) -> if (s.substring(1, s.length - 1).toBigInteger(16) <= 0x3E7FFFFFC18L.toBigInteger()) "Timestamp" else null
        status.matches(s) -> "Status"
        error.matches(s) -> "Error"
        info.matches(s) -> "Info"
        signature.matches(s) -> {
            val length = s.length - 4
            if (length in 4..1024 && length % 4 == 0) "Signature" else null
        }
        else -> null
    }

    private fun typeName(message: AdapterMessage): String = message::class.simpleName!!

    private fun randomMessage(random: Random): String {
        val starts = "ABCDEFRSTUVWXYZabcdefrstuvwxyzPpQq.@\$!?"
        val body = "0123456789abcdefABCDEF-=+/ xyzXYZgG#;"
        val start = starts[random.nextInt(starts.length)]
        val length = when (random.nextInt(10)) {
            0 -> random.nextInt(240, 270)
            1 -> random.nextInt(1000, 1040)
            else -> random.nextInt(0, 14)
        }
        val text = buildString {
            append(start)
            if (start == '?' && random.nextBoolean()) append("64")
            repeat(length) {
                val character = body[random.nextInt(body.length - 1)]
                append(character)
            }
        }
        return "$text;"
    }

    @Test
    fun regex_table_generated_strings_give_the_type_of_the_table() {
        val random = Random(5_18)
        var matched = 0
        repeat(20_000) {
            val s = randomMessage(random)
            val messages = AdapterParser().feed(s.encodeToByteArray(), 0L)
            val expected = tableType(s)
            if (expected != null) {
                matched++
                assertEquals(1, messages.size, "\"$s\" gives $messages")
                assertEquals(expected, typeName(messages.single()), "\"$s\"")
            } else {
                assertTrue(messages.isNotEmpty(), "\"$s\" gives no message")
                assertIs<Unknown>(messages.first(), "\"$s\" gives $messages")
            }
        }
        assertTrue(matched > 300, "only $matched generated strings match a row")
    }

    @Test
    fun regex_table_strings_built_for_each_row_give_the_type() {
        val random = Random(55)
        val hex = "0123456789abcdefABCDEF"
        val b64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        fun hexDigits(n: Int) = (1..n).map { hex[random.nextInt(hex.length)] }.joinToString("")
        repeat(500) {
            val prefix = "ABCDEFRSTUVWXYZabcdefrstuvwxyz."[random.nextInt(31)]
            val value = random.nextInt()
            assertEquals("AxisCount", typeName(parse("$prefix$value;").single()))
            assertEquals("BinaryInput", typeName(parse("${"PpQq"[random.nextInt(4)]}${random.nextInt(2)};").single()))
            assertEquals("Timestamp", typeName(parse("@${hexDigits(random.nextInt(1, 11))};").single()))
            assertEquals("Status", typeName(parse("\$${hexDigits(2)}${hexDigits(random.nextInt(1, 5))};").single()))
            assertEquals("Error", typeName(parse("!${hexDigits(2)}${hexDigits(random.nextInt(1, 5))};").single()))
            var field = hexDigits(2)
            while (field == "64") field = hexDigits(2)
            val text = (1..random.nextInt(0, 257)).map { (0x20 + random.nextInt(0x5F)).toChar() }.filter { it != ';' }.joinToString("")
            assertEquals("Info", typeName(parse("?$field$text;").single()), "?$field$text;")
            val groups = random.nextInt(1, 257)
            val base = (1..groups * 4).map { b64[random.nextInt(b64.length)] }.joinToString("")
            val padded = when (random.nextInt(3)) {
                0 -> base
                1 -> base.dropLast(1) + "="
                else -> base.dropLast(2) + "=="
            }
            assertEquals("Signature", typeName(parse("?64$padded;").single()), "?64$padded;")
        }
    }
}
