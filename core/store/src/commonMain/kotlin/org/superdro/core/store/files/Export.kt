package org.superdro.core.store.files

import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.superdro.core.model.MachineType

/**
 * The values that each SuperDRO export file writes in its header. An
 * export file has no device identity.
 *
 * @property appVersion The version of the app, for example `1.0.0`. The header value `app` is
 *   [appText].
 * @property exportedAt The time of the export.
 * @property timeZone The time zone of the local times in the file.
 */
data class ExportContext(
    val appVersion: String,
    val exportedAt: Instant,
    val timeZone: TimeZone = TimeZone.UTC,
) {
    /** The value of the header key `app`. */
    val appText: String get() = "$APP_NAME $appVersion"

    /** The local time of the export. */
    val localTime: LocalDateTime get() = exportedAt.toLocalDateTime(timeZone)

    /** The value of the header key `exported`: the local time, `yyyy-MM-dd HH:mm:ss`. */
    val exportedText: String get() = FileTimes.format(localTime)

    companion object {
        const val APP_NAME: String = "SuperDRO"
    }
}

/** One export file: the proposed name, the MIME type, and the text (UTF-8 without a byte order mark). */
data class ExportFile(
    val fileName: String,
    val mimeType: String,
    val text: String,
) {
    fun bytes(): ByteArray = text.encodeToByteArray()

    companion object {
        const val MIME_CSV: String = "text/csv"
        const val MIME_JSON: String = "application/json"
        const val MIME_TEXT: String = "text/plain"
        const val MIME_TDAP: String = "application/x-touchdro-adapter-profile"
        const val MIME_TDPRB: String = "application/x-touchdro-probe-profiles"
    }
}

/**
 * The names of the export files:
 * `superdro-<kind>-<name>-<yyyyMMdd_HHmmss>.<ext>`, and for a CSV file of a profile
 * `superdro-<kind>-<profile>-<mill|lathe>-<yyyyMMdd_HHmmss>.csv`.
 *
 * Each part changes each run of characters that are not `A-Z`, `a-z`, or `0-9` to `_`, and
 * loses `_` at its two ends. An empty part is left out. Thus the kind `sub-datums` gives
 * `sub_datums`.
 */
object ExportFileNames {

    private val NOT_ALPHANUMERIC = Regex("[^A-Za-z0-9]+")

    /** Gives the name from [parts], the time [time], and the extension [extension]. */
    fun name(parts: List<String>, time: LocalDateTime, extension: String): String {
        val cleaned = (listOf(ExportContext.APP_NAME.lowercase()) + parts + FileTimes.formatCompact(time))
            .map(::cleanPart)
            .filter(String::isNotEmpty)
        return cleaned.joinToString("-") + "." + extension
    }

    /** `superdro-<kind>-<profile>-<mill|lathe>-<time>.csv`. */
    fun profileCsv(kind: String, profileName: String, machineType: MachineType, time: LocalDateTime): String =
        name(listOf(kind, profileName, millOrLathe(machineType)), time, "csv")

    /** Changes each run of characters that are not `A-Z`, `a-z`, or `0-9` to `_` and trims `_`. */
    fun cleanPart(part: String): String = NOT_ALPHANUMERIC.replace(part, "_").trim('_')

    /** `lathe` on a lathe, else `mill`. */
    fun millOrLathe(machineType: MachineType): String = if (machineType.isLathe) "lathe" else "mill"
}

/** The time texts of the files. */
object FileTimes {

    private val LOCAL_TIME = Regex("""^\s*(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2}):(\d{2})\s*$""")

    /** `yyyy-MM-dd HH:mm:ss`. */
    fun format(time: LocalDateTime): String =
        "${time.year.pad(4)}-${time.month.number.pad(2)}-${time.day.pad(2)} " +
            "${time.hour.pad(2)}:${time.minute.pad(2)}:${time.second.pad(2)}"

    /** `yyyyMMdd_HHmmss`, for a file name. */
    fun formatCompact(time: LocalDateTime): String =
        "${time.year.pad(4)}${time.month.number.pad(2)}${time.day.pad(2)}_" +
            "${time.hour.pad(2)}${time.minute.pad(2)}${time.second.pad(2)}"

    /** `yyyy-MM-dd HH:mm:ss Z`, for example `2026-10-11 14:25:30 -0700`. */
    fun formatWithOffset(instant: Instant, timeZone: TimeZone): String {
        val local = instant.toLocalDateTime(timeZone)
        val seconds = timeZone.offsetAt(instant).totalSeconds
        val sign = if (seconds < 0) "-" else "+"
        val minutes = kotlin.math.abs(seconds) / 60
        return "${format(local)} $sign${(minutes / 60).pad(2)}${(minutes % 60).pad(2)}"
    }

    /** Reads `yyyy-MM-dd HH:mm:ss` as a local time in [timeZone], or gives null. */
    fun parse(text: String, timeZone: TimeZone): Instant? {
        val match = LOCAL_TIME.matchEntire(text) ?: return null
        val values = match.groupValues.drop(1).map(String::toInt)
        return try {
            LocalDateTime(values[0], values[1], values[2], values[3], values[4], values[5]).toInstant(timeZone)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun Int.pad(length: Int): String = toString().padStart(length, '0')
}

/** The names of the machine types in the files: `VERTICAL_MILL`, `LATHE`, `HORIZONTAL_MILL`. */
object MachineNames {
    fun name(machineType: MachineType): String = when (machineType) {
        MachineType.VerticalMill -> "VERTICAL_MILL"
        MachineType.Lathe -> "LATHE"
        MachineType.HorizontalMill -> "HORIZONTAL_MILL"
    }

    /** Reads a machine type name in any case, or gives null. */
    fun parse(text: String): MachineType? = MachineType.entries.firstOrNull { type -> name(type).equals(text.trim(), ignoreCase = true) }
}
