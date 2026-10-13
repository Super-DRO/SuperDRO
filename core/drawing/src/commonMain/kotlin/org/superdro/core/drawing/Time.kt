package org.superdro.core.drawing

import kotlin.time.Instant

/**
 * Gives the UTC time `yyyy-MM-dd'T'HH:mm:ss'Z'` of [instant]. The fraction of the second goes away.
 */
fun formatUtc(instant: Instant): String {
    val (date, secondOfDay) = civil(instant)
    val hour = secondOfDay / 3600
    val minute = secondOfDay / 60 % 60
    val second = secondOfDay % 60
    return "${date.first.pad(4)}-${date.second.pad(2)}-${date.third.pad(2)}T${hour.pad(2)}:${minute.pad(2)}:${second.pad(2)}Z"
}

/** Gives the UTC time stamp `yyyyMMdd_HHmmss` of [instant]. */
fun formatUtcStamp(instant: Instant): String {
    val (date, secondOfDay) = civil(instant)
    return "${date.first.pad(4)}${date.second.pad(2)}${date.third.pad(2)}_" +
        "${(secondOfDay / 3600).pad(2)}${(secondOfDay / 60 % 60).pad(2)}${(secondOfDay % 60).pad(2)}"
}

private fun Long.pad(width: Int): String = toString().padStart(width, '0')

/** Gives (year, month, day) and the second of the day in UTC (the civil calendar algorithm of H. Hinnant). */
private fun civil(instant: Instant): Pair<Triple<Long, Long, Long>, Long> {
    val seconds = instant.epochSeconds
    val days = seconds.floorDiv(86_400L)
    val secondOfDay = seconds.mod(86_400L)
    val z = days + 719_468L
    val era = z.floorDiv(146_097L)
    val dayOfEra = z - era * 146_097L
    val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val mp = (5 * dayOfYear + 2) / 153
    val day = dayOfYear - (153 * mp + 2) / 5 + 1
    val month = if (mp < 10) mp + 3 else mp - 9
    val year = yearOfEra + era * 400 + if (month <= 2) 1 else 0
    return Triple(year, month, day) to secondOfDay
}
