package org.superdro.core.functions.measurement

import kotlin.math.floor
import kotlin.math.pow
import kotlin.time.Instant
import org.superdro.core.model.LogDisplayFormat
import org.superdro.core.model.Units

/**
 * The number texts and the value texts of the measurement functions.
 *
 * The rules:
 *
 * - The round-off goes half toward positive infinity: `floor(v × 10^d + 0.5)`.
 * - The text has no group separator, a '.' decimal mark, and a '-' minus sign.
 * - A value that rounds to 0 has no minus sign. Thus "-0.000" does not occur.
 * - A length is mm (µm ÷ 1000) or inch (µm ÷ 25400) with the decimals of the display format.
 * - An angle always has 2 decimals.
 */
object MeasurementFormat {

    /** The decimals of an angle (`##0.00`). */
    const val ANGLE_DECIMALS: Int = 2

    /** The text for a value that is not finite. */
    const val NOT_FINITE: String = "—"

    /** The unit text of the CSV header and of a point list: "mm" or "in". */
    fun unitText(metric: Boolean): String = if (metric) "mm" else "in"

    /**
     * Gives the text of [value] with [decimals] decimals. The round-off goes half toward
     * positive infinity.
     */
    fun fixed(value: Double, decimals: Int): String = scaledText(value * 10.0.pow(decimals), decimals)

    /**
     * Gives the text of the length [um] in mm or in inch, with [decimals] decimals and no unit.
     * The function multiplies before it divides. Thus 105773.5 µm gives "105.774" with 3 decimals.
     */
    fun length(um: Double, metric: Boolean, decimals: Int): String {
        val divisor = if (metric) Units.UM_PER_MM else Units.UM_PER_INCH
        return scaledText(um * 10.0.pow(decimals) / divisor, decimals)
    }

    /** Gives the text of the length [um] in the display format [format], with no unit. */
    fun length(um: Double, format: LogDisplayFormat): String = length(um, format.metric, format.precision)

    /** Gives the text of the angle [degrees] with 2 decimals and no unit. */
    fun angle(degrees: Double): String = fixed(degrees, ANGLE_DECIMALS)

    /**
     * Gives the value text of [value]:
     *
     * - Length: `50.000 mm` or `1.9685"` (no space before `"`).
     * - Diameter: `Ø 50.000 mm` or `Ø 1.9685"`.
     * - Position: `20.000, 15.000 mm` or `0.7874, 0.5906"`.
     * - Angle: `120.00°`.
     */
    fun valueText(value: ResultValue, format: LogDisplayFormat): String {
        val suffix = if (format.metric) " mm" else "\""
        return when (value) {
            is ResultValue.Length -> length(value.um, format) + suffix
            is ResultValue.Diameter -> "Ø " + length(value.um, format) + suffix
            is ResultValue.Position -> length(value.xUm, format) + ", " + length(value.yUm, format) + suffix
            is ResultValue.Angle -> angle(value.degrees) + "°"
        }
    }

    private fun scaledText(scaled: Double, decimals: Int): String {
        require(decimals in 0..MAX_DECIMALS) { "decimals must be 0 to $MAX_DECIMALS" }
        if (!scaled.isFinite()) return NOT_FINITE
        val count = floor(scaled + 0.5).toLong()
        val negative = count < 0
        val magnitude = if (negative) -count else count
        val factor = 10.0.pow(decimals).toLong()
        val whole = magnitude / factor
        val text = StringBuilder()
        if (negative) text.append('-')
        text.append(whole)
        if (decimals > 0) {
            text.append('.')
            text.append((magnitude % factor).toString().padStart(decimals, '0'))
        }
        return text.toString()
    }

    private const val MAX_DECIMALS = 9
}

/**
 * The text of a time with a UTC offset, for example `2026-10-11 14:25:30 -0700`.
 * The functions use their own civil date algorithm, so the
 * module needs no date library.
 */
object LogTimestamp {

    /**
     * Gives `yyyy-MM-dd HH:mm:ss Z` for [instant] in the local time of [utcOffsetSeconds]. `Z` is
     * the offset as `+HHMM` or `-HHMM`.
     */
    fun format(instant: Instant, utcOffsetSeconds: Int): String {
        val local = LocalTime.of(instant, utcOffsetSeconds)
        return "${local.date()} ${two(local.hour)}:${two(local.minute)}:${two(local.second)} " +
            offsetText(utcOffsetSeconds)
    }

    /** Gives `yyyyMMdd_HHmmss` for [instant] in the local time of [utcOffsetSeconds]. */
    fun fileStamp(instant: Instant, utcOffsetSeconds: Int): String {
        val local = LocalTime.of(instant, utcOffsetSeconds)
        return "${four(local.year)}${two(local.month)}${two(local.day)}_" +
            "${two(local.hour)}${two(local.minute)}${two(local.second)}"
    }

    /** Gives `+HHMM` or `-HHMM` for [utcOffsetSeconds]. */
    fun offsetText(utcOffsetSeconds: Int): String {
        val sign = if (utcOffsetSeconds < 0) '-' else '+'
        val minutes = kotlin.math.abs(utcOffsetSeconds) / 60
        return "$sign${two(minutes / 60)}${two(minutes % 60)}"
    }

    private class LocalTime(
        val year: Long,
        val month: Int,
        val day: Int,
        val hour: Int,
        val minute: Int,
        val second: Int,
    ) {
        fun date(): String = "${four(year)}-${two(month)}-${two(day)}"

        companion object {
            fun of(instant: Instant, utcOffsetSeconds: Int): LocalTime {
                val localSeconds = instant.epochSeconds + utcOffsetSeconds
                val days = localSeconds.floorDiv(SECONDS_PER_DAY)
                val secondOfDay = localSeconds.mod(SECONDS_PER_DAY).toInt()
                // The civil date of a day number (days since 1970-01-01), proleptic Gregorian.
                val z = days + DAYS_0000_TO_1970
                val era = z.floorDiv(DAYS_PER_ERA)
                val dayOfEra = z - era * DAYS_PER_ERA
                val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36524 - dayOfEra / 146096) / 365
                val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
                val monthIndex = (5 * dayOfYear + 2) / 153
                val day = (dayOfYear - (153 * monthIndex + 2) / 5 + 1).toInt()
                val month = (if (monthIndex < 10) monthIndex + 3 else monthIndex - 9).toInt()
                val year = yearOfEra + era * 400 + if (month <= 2) 1 else 0
                return LocalTime(
                    year = year,
                    month = month,
                    day = day,
                    hour = secondOfDay / 3600,
                    minute = secondOfDay / 60 % 60,
                    second = secondOfDay % 60,
                )
            }
        }
    }

    private fun two(value: Int): String = value.toString().padStart(2, '0')

    private fun four(value: Long): String = value.toString().padStart(4, '0')

    private const val SECONDS_PER_DAY = 86_400L
    private const val DAYS_0000_TO_1970 = 719_468L
    private const val DAYS_PER_ERA = 146_097L
}
