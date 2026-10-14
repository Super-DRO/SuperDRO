package org.superdro.core.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** The path of the bytes from the transport to the parser, the probe events, and the record. */
class LinkPathTest {

    private val mill = DeviceId("mill")

    @Test
    fun theLastBytesBeforeTheEndOfTheStreamGoToTheParser() = runTest {
        val h = Harness(this)
        h.setups[mill] = defaultSetup()
        val session = h.manager.connect(device("mill", USB, twoWay = false))
        val samples = h.record(session.samples)
        runCurrent()
        val link = h.links.last
        link.receive("X100;")
        runCurrent()
        // The last chunk and the failure come together: the parser must get the chunk first.
        link.receive("X123;Y7;")
        link.lose()
        runCurrent()
        assertEquals(listOf('x' to 100L, 'x' to 123L, 'y' to 7L), samples.map { sample -> sample.input to sample.rawCount })
        assertEquals(123L, session.inputs.value.channels.getValue('x').rawCount)
        assertTrue(session.state.value is SessionState.Reconnecting, "state ${session.state.value}")
        h.manager.disconnectAll()
        runCurrent()
    }

    @Test
    fun aCollectorThatStopsDoesNotStopTheParse() = runTest {
        val h = Harness(this)
        h.setups[mill] = defaultSetup(debounceMs = 0L)
        val session = h.manager.connect(device("mill", USB, twoWay = false))
        // One collector stops at its first event. A second collector reads each event.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { session.probeEvents.collect { awaitCancellation() } }
        val events = h.record(session.probeEvents)
        runCurrent()
        // With no sample of an input, each activation gives an event at once.
        h.links.last.receive("P1;P0;".repeat(1_100) + "X5;")
        runCurrent()
        assertEquals(5L, session.inputs.value.channels.getValue('x').rawCount, "the parse continued")
        assertTrue(session.state.value is SessionState.Connected)
        // The first event, then the 1024 events of the buffer, in order and with no gap.
        assertEquals((1L..1_025L).toList(), events.map { event -> event.sequence })
        assertEquals(1_100L, session.inputs.value.probeSequence)
        h.manager.disconnectAll()
        runCurrent()
    }
}

/** The record of a link: a queue, batches on the link I/O dispatcher, and the last chunks at the close. */
class QueuedRecordTest {

    /** A sink that keeps each batch. */
    private class BatchSink : RecordSink {
        val batches = ArrayList<List<RecordedChunk>>()
        var closed = false

        override fun write(chunk: RecordedChunk) {
            batches += listOf(chunk)
        }

        override fun writeAll(chunks: List<RecordedChunk>) {
            batches += chunks.toList()
        }

        override fun close() {
            closed = true
        }
    }

    @Test
    fun theRecordGetsTheChunksInBatchesAndTheLastChunksAtTheClose() = runTest {
        val sink = BatchSink()
        val h = Harness(this, recorder = StreamRecorder { _, _, _ -> sink })
        h.setups[DeviceId("mill")] = defaultSetup()
        h.manager.connect(device("mill", USB, twoWay = false, record = true))
        runCurrent()
        val link = h.links.last
        link.receive("X1;")
        runCurrent()
        assertEquals(listOf(listOf("X1;")), sink.batches.map { batch -> batch.map { chunk -> chunk.bytes.decodeToString() } })
        // In the interval, the chunks wait in the queue.
        link.receive("X2;")
        advanceTimeBy(10)
        link.receive("X3;")
        advanceTimeBy(10)
        runCurrent()
        assertEquals(1, sink.batches.size)
        advanceTimeBy(QueuedRecordSink.BATCH_MS)
        runCurrent()
        assertEquals(listOf("X2;", "X3;"), sink.batches[1].map { chunk -> chunk.bytes.decodeToString() })
        assertEquals(10L, sink.batches[1][1].timeMs - sink.batches[1][0].timeMs, "each chunk keeps its arrival time")
        // The close gives the chunks in the queue at once, then closes the sink.
        link.receive("X4;")
        runCurrent()
        link.receive("X5;")
        runCurrent()
        h.manager.disconnectAll()
        runCurrent()
        assertEquals(listOf("X1;", "X2;", "X3;", "X4;", "X5;"), sink.batches.flatten().map { chunk -> chunk.bytes.decodeToString() })
        assertTrue(sink.closed)
    }
}
