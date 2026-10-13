package org.superdro.core.ui.settings.logs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import org.superdro.core.connection.DeviceId

/** The texts of the log export. */
class LogFormatTest {

    private val zone = TimeZone.UTC
    private val time = Instant.parse("2026-10-12T08:05:09.042Z")

    @Test
    fun theTimeAndTheFileNames() {
        assertEquals("08:05:09.042", LogFormat.time(time, zone))
        assertEquals("20261012-080509", LogFormat.stamp(time, zone))
        assertEquals("superdro-log-all-20261012-080509.txt", LogFormat.logFileName("all", time, zone))
        assertEquals("superdro-log-Bridgeport_mill-20261012-080509.txt", LogFormat.logFileName("Bridgeport mill", time, zone))
        assertEquals("Demo_mill-2.txt", LogFormat.streamFileName("Demo mill", 2))
        assertEquals("machine-1.txt", LogFormat.streamFileName("  ", 1))
    }

    @Test
    fun theExportHasTheHeaderAndOneLineForEachLogLine() {
        val lines = listOf(
            LogLine(1L, time, LogLevel.Info, "DeviceSession", DeviceId("mill"), "link 1 open"),
            LogLine(2L, time, LogLevel.Error, "App", null, "An app coroutine failed", "java.lang.IllegalStateException: bad"),
        )
        val text = LogFormat.export(lines, "all", { id -> if (id.value == "mill") "Mill" else id.value }, time, zone)
        assertEquals(
            "# SuperDRO log\n" +
                "# Machine: all\n" +
                "# Exported: 2026-10-12 08:05:09\n" +
                "08:05:09.042 I DeviceSession [Mill]: link 1 open\n" +
                "08:05:09.042 E App: An app coroutine failed\n" +
                "  java.lang.IllegalStateException: bad\n",
            text,
        )
    }
}
