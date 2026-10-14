package org.superdro.core.protocol

import org.superdro.core.protocol.AdapterMessage.AxisCount
import org.superdro.core.protocol.AdapterMessage.BinaryInput
import org.superdro.core.protocol.AdapterMessage.Unknown
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The split tests and the noise tests. */
class AdapterParserStreamTest {

    /** The worked byte stream with the messages of the other forms. */
    private val stream: String =
        "X1200;@3E8;Y-350;P1;@7D0;T1500;\r\n\$0AA3;?0530aea4a1b2c3;!0A21;X12 3;F5;f5;X12Y-5;P10;?64QUJD;" +
            "?64QUJDRA==;?64QUJ;X1;\$5;\$3205;?64QUJ*RA==;@;P1x;A12;f-3;r1;S0;.3;q0;?03Shop adapter;" +
            "?01x000f0002;\$78a0;!7816;?64" + "QUJD".repeat(256) + ";Z-2147483648;W2147483647;"

    private val streamBytes = stream.encodeToByteArray()

    private fun parseInChunks(size: Int, timeMs: Long = 1_000L): List<AdapterMessage> {
        val parser = AdapterParser()
        val messages = mutableListOf<AdapterMessage>()
        var offset = 0
        while (offset < streamBytes.size) {
            val length = minOf(size, streamBytes.size - offset)
            messages += parser.feed(streamBytes, offset, length, timeMs)
            offset += length
        }
        return messages
    }

    @Test
    fun oneListUsedAgainForEachChunkGivesTheSameMessages() {
        val whole = AdapterParser().feed(streamBytes, 1_000L)
        listOf(1, 2, 7, 64).forEach { size ->
            val parser = AdapterParser()
            val out = ArrayList<AdapterMessage>()
            val messages = mutableListOf<AdapterMessage>()
            var offset = 0
            while (offset < streamBytes.size) {
                val length = minOf(size, streamBytes.size - offset)
                out.clear()
                parser.feed(streamBytes, offset, length, 1_000L, out)
                messages += out
                offset += length
            }
            assertEquals(whole, messages, "Chunks of $size bytes")
        }
        // The parser adds to the list and does not clear it.
        val out = arrayListOf<AdapterMessage>(Unknown("old"))
        AdapterParser().feed("X5;".encodeToByteArray(), 0, 3, 7L, out)
        assertEquals(2, out.size)
        assertEquals(Unknown("old"), out[0])
    }

    @Test
    fun chunksOf1And2And7And64BytesGiveTheSameMessages() {
        val whole = AdapterParser().feed(streamBytes, 1_000L)
        assertTrue(whole.size > 30)
        listOf(1, 2, 7, 64).forEach { size ->
            assertEquals(whole, parseInChunks(size), "Chunks of $size bytes")
        }
    }

    @Test
    fun aPartMessageStaysBetweenTwoChunksAndTheStartTimeIsTheFirstChunk() {
        val parser = AdapterParser()
        assertEquals(emptyList(), parser.feed("X12", 1_000L))
        assertTrue(parser.inMessage)
        assertEquals(listOf(AxisCount('X', 1234, 1_000L)), parser.feed("34;", 1_200L))
        // A new parser for a new link has no part message of the old link.
        val oldLink = AdapterParser()
        oldLink.feed("X12", 1_000L)
        assertEquals(listOf(AxisCount('Y', 5, 2_000L)), AdapterParser().feed("Y5;", 2_000L))
    }

    @Test
    fun lineNoiseInsideAMessageHasNoEffect() {
        val noisy = byteArrayOf(
            'X'.code.toByte(), 0x0D, '1'.code.toByte(), 0x0A, 0x00, '2'.code.toByte(), 0x80.toByte(),
            0xFF.toByte(), '3'.code.toByte(), 0x07, ';'.code.toByte(),
        )
        val parser = AdapterParser()
        assertEquals(listOf(AxisCount('X', 123, 5L)), parser.feed(noisy, 5L))
        assertEquals(0L, parser.junkCount)
    }

    @Test
    fun theParserRecoversAtTheNextStartCharacterAfterRandomNoise() {
        // Noise without "?", "$", "!", and "@": these characters open a message that can keep hex
        // digits or text, so the recovery after them is at the next ";" (next test).
        val random = Random(4242)
        val noiseBytes = (0..255).filter { it.toChar() !in "?\$!@" }
        val valid = "X1200;Y-350;P1;q0;T1500;Z7;"
        val expected = listOf(
            AxisCount('X', 1200, 0L), AxisCount('Y', -350, 0L), BinaryInput('P', 1, 0L),
            BinaryInput('Q', 0, 0L), AxisCount('T', 1500, 0L), AxisCount('Z', 7, 0L),
        )
        repeat(500) { round ->
            val parser = AdapterParser()
            val noise = ByteArray(random.nextInt(0, 40)) { noiseBytes[random.nextInt(noiseBytes.size)].toByte() }
            parser.feed(noise, 0L)
            val messages = parser.feed(valid, 0L)
            val rest = if (messages.firstOrNull() is Unknown) messages.drop(1) else messages
            assertEquals(expected, rest, "Round $round, noise ${noise.joinToString(" ") { (it.toInt() and 0xFF).toString(16) }}")
        }
    }

    @Test
    fun theParserRecoversAfterTheNextSemicolonAfterAnyNoise() {
        val random = Random(77)
        val valid = "\$0AA3;?0530aea4a1b2c3;X1200;P1;"
        val expected = AdapterParser().feed(valid, 0L)
        repeat(500) { round ->
            val parser = AdapterParser()
            parser.feed(ByteArray(random.nextInt(0, 2000)) { random.nextInt(256).toByte() }, 0L)
            parser.feed(";", 0L)
            parser.resetTime()
            assertEquals(expected, parser.feed(valid, 0L), "Round $round")
        }
    }
}
