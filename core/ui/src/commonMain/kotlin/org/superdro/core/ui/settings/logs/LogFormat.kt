package org.superdro.core.ui.settings.logs

import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.superdro.core.connection.DeviceId

/**
 * The texts of the log export: the time of a line, the export file, and the
 * file names. The file is plain text in English, for a bug report or a check by the user.
 */
object LogFormat {

    private val unsafe = Regex("[^A-Za-z0-9._-]")

    /** Gives `HH:mm:ss.SSS` of [time] in [zone]. */
    fun time(time: Instant, zone: TimeZone): String {
        val local = time.toLocalDateTime(zone)
        return "${two(local.hour)}:${two(local.minute)}:${two(local.second)}.${(local.nanosecond / 1_000_000).toString().padStart(3, '0')}"
    }

    /** Gives `yyyyMMdd-HHmmss` of [time] in [zone], for a file name. */
    fun stamp(time: Instant, zone: TimeZone): String {
        val local = time.toLocalDateTime(zone)
        return "${local.year}${two(local.month.ordinal + 1)}${two(local.day)}-${two(local.hour)}${two(local.minute)}${two(local.second)}"
    }

    /** Gives `yyyy-MM-dd HH:mm:ss` of [time] in [zone]. */
    fun dateTime(time: Instant, zone: TimeZone): String {
        val local: LocalDateTime = time.toLocalDateTime(zone)
        return "${local.year}-${two(local.month.ordinal + 1)}-${two(local.day)} ${two(local.hour)}:${two(local.minute)}:${two(local.second)}"
    }

    /** Gives [name] with `_` in place of each character that is not safe in a file name. */
    fun safeName(name: String): String = name.trim().replace(unsafe, "_").ifEmpty { "machine" }

    /**
     * Gives the name of the export of the lines of [machine] at [time]:
     * `superdro-log-<machine>-<yyyyMMdd-HHmmss>.txt`. [machine] is `all`, `app`, or the machine name.
     */
    fun logFileName(machine: String, time: Instant, zone: TimeZone): String =
        "superdro-log-${safeName(machine)}-${stamp(time, zone)}.txt"

    /** Gives the name of the export of a recorded link: `<device>-<link>.txt`. */
    fun streamFileName(device: String, link: Int): String = "${safeName(device)}-$link.txt"

    /**
     * Gives one line of the export: `HH:mm:ss.SSS L tag [machine]: text`, and the error on its own
     * line with two spaces before it. [machine] is null for a line of the app.
     */
    fun line(line: LogLine, machine: String?, zone: TimeZone): String {
        val source = if (machine != null) " [$machine]" else ""
        val first = "${time(line.time, zone)} ${line.level.letter} ${line.tag}$source: ${line.text}"
        return if (line.error != null) "$first\n  ${line.error}" else first
    }

    /**
     * Gives the text of the export of [lines]: three comment lines (the title, the machine, and the
     * time of the export), then one line for each log line. [names] gives the machine name of a
     * device.
     */
    fun export(lines: List<LogLine>, title: String, names: (DeviceId) -> String, exportedOn: Instant, zone: TimeZone): String =
        buildString {
            append("# SuperDRO log\n")
            append("# Machine: ").append(title).append('\n')
            append("# Exported: ").append(dateTime(exportedOn, zone)).append('\n')
            lines.forEach { line -> append(line(line, line.device?.let(names), zone)).append('\n') }
        }

    private fun two(value: Int): String = value.toString().padStart(2, '0')
}
