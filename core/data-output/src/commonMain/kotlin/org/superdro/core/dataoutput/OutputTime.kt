package org.superdro.core.dataoutput

import kotlin.time.Clock as SystemTimeClock
import org.superdro.core.model.TimestampFormat

/**
 * A wall clock in milliseconds since 1970-01-01 00:00 UTC. The time stamps of the records use it.
 * A test gives a fixed value.
 */
fun interface WallClock {
    /** The milliseconds since 1970-01-01 00:00 UTC. */
    fun nowEpochMs(): Long

    companion object {
        /** The clock of the system. */
        val System: WallClock = WallClock { SystemTimeClock.System.now().toEpochMilliseconds() }
    }
}

/** The time stamp texts of the data output. */
object OutputTime {

    private const val MS_PER_DAY: Long = 86_400_000L

    /**
     * Gives the wall clock time of a monotonic time [monotonicMs]: `wallNow − monotonicNow + t`.
     * The session code uses it to fill [OutputValues.updatedAtEpochMs].
     */
    fun wallClockTime(monotonicMs: Long, wallNowEpochMs: Long, monotonicNowMs: Long): Long =
        wallNowEpochMs - monotonicNowMs + monotonicMs

    /** Gives the time stamp text of [epochMs] in [format], with no quotes. */
    fun text(epochMs: Long, format: TimestampFormat): String = when (format) {
        TimestampFormat.Iso8601 -> iso8601(epochMs)
        TimestampFormat.EpochMs -> epochMs.toString()
    }

    /**
     * Gives [epochMs] in the pattern `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` in UTC, for example
     * `2026-10-11T14:03:22.517Z`. The text has 24 characters for the years 0 to 9999.
     */
    fun iso8601(epochMs: Long): String {
        val days = epochMs.floorDiv(MS_PER_DAY)
        val msOfDay = epochMs.mod(MS_PER_DAY)
        val date = civilDate(days)
        val hours = msOfDay / 3_600_000L
        val minutes = msOfDay / 60_000L % 60L
        val seconds = msOfDay / 1000L % 60L
        val millis = msOfDay % 1000L
        return buildString {
            append(date.year.toString().padStart(4, '0'))
            append('-')
            append(two(date.month))
            append('-')
            append(two(date.day))
            append('T')
            append(two(hours))
            append(':')
            append(two(minutes))
            append(':')
            append(two(seconds))
            append('.')
            append(millis.toString().padStart(3, '0'))
            append('Z')
        }
    }

    private fun two(value: Long): String = value.toString().padStart(2, '0')

    private class CivilDate(val year: Long, val month: Long, val day: Long)

    /** The proleptic Gregorian date of the day [days] since 1970-01-01 (the days-to-civil algorithm). */
    private fun civilDate(days: Long): CivilDate {
        val z = days + 719_468L
        val era = z.floorDiv(146_097L)
        val dayOfEra = z - era * 146_097L
        val yearOfEra = (dayOfEra - dayOfEra / 1460L + dayOfEra / 36_524L - dayOfEra / 146_096L) / 365L
        val dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
        val shiftedMonth = (5L * dayOfYear + 2L) / 153L
        val day = dayOfYear - (153L * shiftedMonth + 2L) / 5L + 1L
        val month = if (shiftedMonth < 10L) shiftedMonth + 3L else shiftedMonth - 9L
        val year = yearOfEra + era * 400L + if (month <= 2L) 1L else 0L
        return CivilDate(year, month, day)
    }
}
