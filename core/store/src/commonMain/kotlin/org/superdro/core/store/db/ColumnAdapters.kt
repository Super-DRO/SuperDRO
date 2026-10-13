package org.superdro.core.store.db

import app.cash.sqldelight.ColumnAdapter
import com.toxicbakery.logging.Arbor
import kotlin.time.Instant

/**
 * Keeps an [Instant] as ISO 8601 UTC text, for example `2026-10-12T08:30:00.125Z`.
 */
object InstantColumnAdapter : ColumnAdapter<Instant, String> {
    override fun decode(databaseValue: String): Instant = Instant.parse(databaseValue)

    override fun encode(value: Instant): String = value.toString()
}

/** Keeps an [Int] in an `INTEGER` column. */
object IntColumnAdapter : ColumnAdapter<Int, Long> {
    override fun decode(databaseValue: Long): Int = databaseValue.toInt()

    override fun encode(value: Int): Long = value.toLong()
}

/**
 * Keeps an enum value as its name. A name that this version does not know (for example from a
 * newer version of the app) gives [fallback] and a log line, not an exception. Thus one bad
 * value does not stop the load of all data, as `coerceInputValues` of the JSON columns.
 */
class EnumColumnAdapter<T : Enum<T>>(
    private val entries: List<T>,
    private val fallback: T,
) : ColumnAdapter<T, String> {
    override fun decode(databaseValue: String): T =
        entries.firstOrNull { entry -> entry.name == databaseValue } ?: fallback.also {
            log.w { "Unknown value $databaseValue. The store uses $fallback." }
        }

    override fun encode(value: T): String = value.name

    private companion object {
        val log = Arbor.tag("EnumColumnAdapter")
    }
}
