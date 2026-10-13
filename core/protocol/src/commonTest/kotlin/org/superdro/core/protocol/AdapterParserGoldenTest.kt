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

/** The worked byte stream (the golden test). */
class AdapterParserGoldenTest {

    private val t0 = 100_000L
    private val t1 = 105_000L
    private val t2 = 109_000L

    /** One row of the table: the bytes, the arrival time, the messages, and the junk characters. */
    private class Row(val bytes: ByteArray, val timeMs: Long, val expected: List<AdapterMessage>, val junk: Long = 0L)

    private fun row(ascii: String, timeMs: Long, vararg expected: AdapterMessage, junk: Long = 0L) =
        Row(ascii.encodeToByteArray(), timeMs, expected.toList(), junk)

    private val rows: List<Row> = listOf(
        row("X1200;", t0, AxisCount('X', 1200, t0)),
        row("@3E8;", t1, Timestamp(1000, t1)),
        row("Y-350;", t1, AxisCount('Y', -350, t1)),
        row("P1;", t1, BinaryInput('P', 1, t1)),
        row("@7D0;", t1, Timestamp(2000, t1 + 1000)),
        Row(byteArrayOf(0x54, 0x31, 0x35, 0x30, 0x30, 0x3B, 0x0D, 0x0A), t1, listOf(AxisCount('T', 1500, t1 + 1000))),
        row("\$0AA3;", t2, Status(0x0A, 0xA3)),
        row("?0530aea4a1b2c3;", t2, Info(5, "30aea4a1b2c3")),
        row("!0A21;", t2, Error(0x0A, 0x21)),
        // The space ends the message. It is not a start character, so it is junk too: " ", "3", ";".
        row("X12 3;", t2, Unknown("X12"), junk = 3L),
        row("F5;", t2, AxisCount('F', 5, t1 + 1000)),
        row("f5;", t2, AxisCount('F', 5, t1 + 1000)),
        row("X12Y-5;", t2, Unknown("X12"), AxisCount('Y', -5, t1 + 1000)),
        row("P10;", t2, Unknown("P1"), junk = 2L),
        row("?64QUJD;", t2, Signature("QUJD")),
        row("?64QUJDRA==;", t2, Signature("QUJDRA==")),
        row("?64QUJ;X1;", t2, Unknown("?64QUJ"), AxisCount('X', 1, t1 + 1000)),
        row("\$5;", t2, Unknown("\$5")),
        row("\$3205;", t2, Status(0x32, 0x05)),
        // "*" ends the signature. Then "R" and "A" start numeric readouts that "A" and "=" end.
        row("?64QUJ*RA==;", t2, Unknown("?64QUJ"), Unknown("R"), Unknown("A"), junk = 4L),
        row("@;", t2, Unknown("@")),
        row("P1x;", t2, Unknown("P1"), Unknown("x")),
    )

    @Test
    fun eachRowGivesTheMessagesOfTheTable() {
        val parser = AdapterParser()
        rows.forEach { row ->
            val junkBefore = parser.junkCount
            val messages = parser.feed(row.bytes, row.timeMs)
            val text = row.bytes.decodeToString()
            assertEquals(row.expected, messages, "Row \"$text\"")
            assertEquals(row.junk, parser.junkCount - junkBefore, "Junk of the row \"$text\"")
            assertFalse(parser.inMessage, "Row \"$text\" leaves a part message")
        }
    }

    @Test
    fun theTimeStampsSetTheOffsetOfTheTable() {
        val parser = AdapterParser()
        parser.feed("X1200;", t0)
        parser.feed("@3E8;", t1)
        assertEquals(t1 - 1000, parser.timeBase.offsetMs)
        assertEquals(t1, parser.timeBase.adapterTimeMs)
        parser.feed("@7D0;", t1)
        assertEquals(t1 + 1000, parser.timeBase.adapterTimeMs)
    }

    @Test
    fun theFirstReadoutIsValidAndTheTimeStampIsNot() {
        // The first valid message changes AwaitingData to Connected.
        val parser = AdapterParser()
        assertTrue(parser.feed("X1200;", t0).single().isValid)
        assertFalse(parser.feed("@3E8;", t1).single().isValid)
        assertFalse(parser.feed("\$5;", t1).single().isValid)
    }

    @Test
    fun theDirtyAxesStatusMarksXAndZ() {
        val status = AdapterParser().feed("\$3205;", t2).single() as Status
        assertEquals(Subsystem.DIRTY_AXES, status.subsystem)
        assertEquals(setOf(InputChannel.X, InputChannel.Z), InputChannel.dirtyChannels(status.code))
    }

    @Test
    fun theWholeStreamInOneChunkGivesTheSameMessages() {
        val stream = rows.fold(ByteArray(0)) { all, row -> all + row.bytes }
        val oneChunk = AdapterParser().feed(stream, t2)
        val rowByRow = AdapterParser().let { parser -> rows.flatMap { parser.feed(it.bytes, t2) } }
        assertEquals(rowByRow, oneChunk)
        assertEquals(rows.sumOf { it.expected.size }, oneChunk.size)
    }
}
