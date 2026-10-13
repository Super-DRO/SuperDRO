package org.superdro.core.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The record flag of each device in the connection manager and the stream
 * of all input samples.
 */
class RecordFlagAndSamplesTest {

    private val mill = DeviceId("mill")

    private class Sinks {
        val opened = ArrayList<Int>()
        val recorder = StreamRecorder { _, _, link -> opened += link; MemorySink() }
    }

    @Test
    fun theRecordContinuesAtEachLinkUntilTheUserStopsIt() = runTest {
        val sinks = Sinks()
        val h = Harness(this, recorder = sinks.recorder)
        val session = h.manager.connect(device("mill", record = true))
        runCurrent()
        assertEquals(listOf(1), sinks.opened)
        assertEquals(setOf(mill), h.manager.recording.value)

        // A later connect with the default flag (the restore, the remembered machines, a settings change) keeps the record.
        h.manager.connect(device("mill", address = SPP))
        runCurrent()
        assertTrue(session.device.value.recordStream)
        assertEquals(listOf(1, 2), sinks.opened, "the new address opened a new link with a record")
        h.links.links.last().lose()
        runCurrent()
        h.test.testScheduler.advanceTimeBy(10_000L)
        runCurrent()
        assertTrue(sinks.opened.size >= 3, "the reconnect records too: ${sinks.opened}")

        // The switch of the logs stops the record with a new link at once, and the session stays connected.
        val links = h.links.links.size
        h.manager.setRecordStream(mill, on = false)
        runCurrent()
        assertFalse(session.device.value.recordStream)
        assertEquals(links + 1, h.links.links.size)
        val opened = sinks.opened.size
        h.manager.connect(device("mill", address = SPP))
        runCurrent()
        assertEquals(opened, sinks.opened.size, "no record after the stop")

        // The switch turns it on again; a user disconnect ends it.
        h.manager.setRecordStream(mill, on = true)
        runCurrent()
        assertEquals(opened + 1, sinks.opened.size)
        h.manager.disconnect(mill)
        runCurrent()
        assertEquals(emptySet(), h.manager.recording.value)
        h.manager.connect(device("mill", address = SPP))
        runCurrent()
        assertFalse(session.device.value.recordStream)
        h.manager.disconnectAll()
        runCurrent()
    }

    @Test
    fun theOldSwitchOfTheLogsStillWorks() = runTest {
        // LogsPresenter of core:ui: a disconnect, then a connect with the new flag.
        val sinks = Sinks()
        val h = Harness(this, recorder = sinks.recorder)
        val session = h.manager.connect(device("mill"))
        runCurrent()
        h.manager.disconnect(mill)
        h.manager.connect(session.device.value.copy(recordStream = true))
        runCurrent()
        assertEquals(listOf(2), sinks.opened)
        h.manager.disconnect(mill)
        h.manager.connect(session.device.value.copy(recordStream = false))
        runCurrent()
        assertEquals(listOf(2), sinks.opened)
        assertEquals(emptySet(), h.manager.recording.value)
        h.manager.disconnectAll()
        runCurrent()
    }

    @Test
    fun theSamplesStreamHasEachSampleInStreamOrder() = runTest {
        val h = Harness(this)
        h.setups[mill] = defaultSetup()
        val session = h.manager.connect(device("mill", twoWay = false))
        val samples = h.record(session.samples)
        runCurrent()
        // Three counts of X in one chunk: the conflated inputs show only the last one.
        h.links.links.single().receive("X5;Y7;X6;X8;")
        runCurrent()
        assertEquals(8L, session.inputs.value.channels.getValue('x').rawCount)
        assertEquals(listOf('x' to 5L, 'y' to 7L, 'x' to 6L, 'x' to 8L), samples.map { sample -> sample.input to sample.rawCount })
        // A letter that is not an encoder input gives no sample.
        h.links.links.single().receive("A9;P1;")
        runCurrent()
        assertEquals(4, samples.size)
        h.manager.disconnectAll()
        runCurrent()
    }
}
