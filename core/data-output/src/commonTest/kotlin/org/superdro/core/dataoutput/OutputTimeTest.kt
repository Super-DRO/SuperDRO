package org.superdro.core.dataoutput

import kotlin.test.Test
import kotlin.test.assertEquals
import org.superdro.core.model.TimestampFormat

/** The time stamps of the data output. */
class OutputTimeTest {

    @Test
    fun theIsoTimeIsUtcWithMilliseconds() {
        assertEquals("2026-10-11T14:03:22.517Z", OutputTime.iso8601(1791727402517L))
        assertEquals(24, OutputTime.iso8601(1791727402517L).length)
        assertEquals("1970-01-01T00:00:00.000Z", OutputTime.iso8601(0L))
        assertEquals("1969-12-31T23:59:59.999Z", OutputTime.iso8601(-1L))
        assertEquals("2024-02-29T12:00:00.005Z", OutputTime.iso8601(1709208000005L))
        assertEquals("2000-03-01T00:00:00.000Z", OutputTime.iso8601(951868800000L))
    }

    @Test
    fun theEpochTimeIsTheMilliseconds() {
        assertEquals("1791727402517", OutputTime.text(1791727402517L, TimestampFormat.EpochMs))
        assertEquals("2026-10-11T14:03:22.517Z", OutputTime.text(1791727402517L, TimestampFormat.Iso8601))
    }

    @Test
    fun theWallClockTimeOfAMonotonicTime() {
        // wallNow − monotonicNow + t.
        assertEquals(1791727402517L, OutputTime.wallClockTime(monotonicMs = 9_517L, wallNowEpochMs = 1791727403000L, monotonicNowMs = 10_000L))
    }
}
