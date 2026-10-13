package org.superdro.core.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.AdapterAddress

/** The recorded byte streams and the replay transport. */
class RecordedStreamTest {

    private val chunks = listOf(
        RecordedChunk(0L, "X1200;".encodeToByteArray()),
        RecordedChunk(250L, byteArrayOf(0x40, 0x33, 0x45, 0x38, 0x3B, 0x0D, 0x0A, 0xFF.toByte())),
    )

    @Test
    fun theTextFormHasOneLineForEachChunk() {
        assertEquals("# SuperDRO byte stream 1\n0 58313230303b\n250 403345383b0d0aff\n", RecordedStream.format(chunks))
        assertEquals(chunks, RecordedStream.parse(RecordedStream.format(chunks)))
        assertEquals(chunks, RecordedStream.parse("# SuperDRO byte stream 1\n\n# a comment\n0 58 31 32 30 30 3B\n250 40334538 3B0D0AFF\n"))
        assertEquals(listOf(RecordedChunk(5L, ByteArray(0))), RecordedStream.parse("5"))
    }

    @Test
    fun aBadLineIsRefused() {
        assertFailsWith<IllegalArgumentException> { RecordedStream.parse("x 00") }
        assertFailsWith<IllegalArgumentException> { RecordedStream.parse("5 0") }
        assertFailsWith<IllegalArgumentException> { RecordedStream.parse("5 zz") }
        assertFailsWith<IllegalArgumentException> { RecordedStream.parse("5 00\n4 00") }
    }

    @Test
    fun theLineSinkWritesTheHeaderFirst() {
        val lines = ArrayList<String>()
        var closed = false
        val sink = LineRecordSink(writeLine = { line -> lines += line }, onClose = { closed = true })
        chunks.forEach(sink::write)
        sink.close()
        assertEquals(RecordedStream.format(chunks).trimEnd('\n').split('\n'), lines)
        assertTrue(closed)
        val empty = ArrayList<String>()
        LineRecordSink(writeLine = { line -> empty += line }).close()
        assertEquals(listOf(RecordedStream.HEADER), empty)
    }

    @Test
    fun theReplayTransportPlaysTheChunksWithTheirTimes() = runTest {
        val transport = ReplayTransport(AdapterAddress.Fake("replay"), chunks, endWithLoss = true)
        transport.open()
        val failure = assertFailsWith<LinkFailureException> { transport.incoming.toList() }
        assertEquals(LinkFailure.Lost, failure.failure)
        assertEquals(250L, testScheduler.currentTime)
        transport.write(byteArrayOf(0x07))
        assertEquals(listOf(ReplayTransport.Write(0L, listOf<Byte>(0x07))), transport.written)
        transport.close()
        assertFailsWith<LinkFailureException> { transport.open() }
    }
}
