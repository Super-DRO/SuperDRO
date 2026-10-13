package org.superdro.core.ui.settings.logs

import com.toxicbakery.logging.Arbor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import org.superdro.core.connection.DeviceId
import org.superdro.core.ui.testing.FixedWallClock

/** The in-memory log sink of the log screen: one ring buffer for each session and one for the app. */
class SessionLogSinkTest {

    private val clock = FixedWallClock()

    @Test
    fun aSessionLineGoesToTheBufferOfItsDeviceAndEachOtherLineToTheAppBuffer() {
        val sink = SessionLogSink(clock)
        sink.add(LogLevel.Info, SessionLogSink.SESSION_TAG, "mill: link 1 open")
        sink.add(LogLevel.Info, SessionLogSink.SESSION_TAG, "lathe: the open failed: Refused")
        sink.add(LogLevel.Warning, "JsonFileStore", "Cannot decode tabs.json")
        // A session tag with no "<device>: " prefix is a line of the app.
        sink.add(LogLevel.Debug, SessionLogSink.SESSION_TAG, "no prefix")

        val mill = sink.lines(LogFilter.Machine(DeviceId("mill"))).single()
        assertEquals("link 1 open", mill.text)
        assertEquals(DeviceId("mill"), mill.device)
        assertEquals(SessionLogSink.SESSION_TAG, mill.tag)
        assertEquals(listOf("the open failed: Refused"), sink.lines(LogFilter.Machine(DeviceId("lathe"))).map { it.text })
        assertEquals(listOf("Cannot decode tabs.json", "no prefix"), sink.lines(LogFilter.App).map { it.text })
        assertEquals(listOf(1L, 2L, 3L, 4L), sink.lines(LogFilter.All).map { it.sequence })
        assertEquals(setOf(DeviceId("mill"), DeviceId("lathe")), sink.devices().toSet())
    }

    @Test
    fun eachBufferKeepsTheNewestLines() {
        val sink = SessionLogSink(clock, capacity = 3)
        (1..5).forEach { number -> sink.add(LogLevel.Info, SessionLogSink.SESSION_TAG, "mill: line $number") }
        (1..4).forEach { number -> sink.add(LogLevel.Info, "App", "app $number") }
        assertEquals(listOf("line 3", "line 4", "line 5"), sink.lines(LogFilter.Machine(DeviceId("mill"))).map { it.text })
        assertEquals(listOf("app 2", "app 3", "app 4"), sink.lines(LogFilter.App).map { it.text })
        assertEquals(6, sink.lines(LogFilter.All).size)
    }

    @Test
    fun theSinkKeepsTheTimeTheLevelAndTheSummaryOfTheThrowable() {
        val sink = SessionLogSink(clock)
        sink.log(Arbor.ERROR, "App", "An app coroutine failed", IllegalStateException("bad state"), null)
        clock.current = clock.current + 250.milliseconds
        sink.log(Arbor.WTF, "App", "never", null, null)
        val (first, second) = sink.lines(LogFilter.App)
        assertEquals(LogLevel.Error, first.level)
        assertEquals("java.lang.IllegalStateException: bad state", first.error)
        assertEquals(kotlin.time.Instant.parse("2026-10-12T08:00:00Z"), first.time)
        assertEquals(LogLevel.Error, second.level)
        assertNull(second.error)
        assertEquals(kotlin.time.Instant.parse("2026-10-12T08:00:00.250Z"), second.time)
    }

    @Test
    fun aVerboseLineIsLeftOutAndItsTextIsNotMade() {
        val sink = SessionLogSink(clock)
        var made = false
        sink.log(Arbor.VERBOSE, SessionLogSink.SESSION_TAG, { made = true; "mill: dirty status 3" }, null)
        sink.log(Arbor.VERBOSE, "App", "verbose", null, null)
        assertTrue(sink.lines().isEmpty())
        assertEquals(false, made)
        assertEquals(0L, sink.revision.value)
    }

    @Test
    fun theSinkGetsTheLinesOfArborAndTheRevisionChanges() {
        val sink = SessionLogSink(clock)
        Arbor.sow(sink)
        try {
            Arbor.tag(SessionLogSink.SESSION_TAG).i { "demo-mill: link 2 open" }
            Arbor.tag("ConnectionManager").w("New session demo-mill (Fake)")
        } finally {
            Arbor.harvest(sink)
        }
        assertEquals(listOf("link 2 open"), sink.lines(LogFilter.Machine(DeviceId("demo-mill"))).map { it.text })
        assertEquals(listOf(LogLevel.Warning), sink.lines(LogFilter.App).map { it.level })
        assertEquals(2L, sink.revision.value)
        sink.clear()
        assertTrue(sink.lines().isEmpty())
        assertEquals(3L, sink.revision.value)
    }

    @Test
    fun theDeviceOfALineComesOnlyFromTheSessionTag() {
        assertEquals(DeviceId("0f3e-9b"), SessionLogSink.deviceOf("DeviceSession", "0f3e-9b: no data for 30000 ms"))
        assertNull(SessionLogSink.deviceOf("App", "0f3e-9b: text"))
        assertNull(SessionLogSink.deviceOf("DeviceSession", ": text"))
        assertNull(SessionLogSink.deviceOf("DeviceSession", "two words: text"))
    }
}
