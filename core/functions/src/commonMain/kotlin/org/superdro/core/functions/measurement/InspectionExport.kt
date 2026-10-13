package org.superdro.core.functions.measurement

import kotlin.math.max
import kotlin.time.Instant
import org.superdro.core.model.CollectedPoint
import org.superdro.core.model.LogDisplayFormat
import org.superdro.core.model.MeasurementLogEntry
import org.superdro.core.model.PointSource

/**
 * The labels of the text inspection sheet. The defaults are the English texts. The UI can give
 * the texts of the app language.
 *
 * @property resultLabels The label of each result key. A key with no label uses
 *   [ResultKey.englishLabel].
 */
data class InspectionSheetLabels(
    val title: String = "SuperDRO — Inspection Log",
    val workspace: String = "Workspace:",
    val note: String = "Note:",
    val system: String = "System:",
    val resultLabels: Map<ResultKey, String> = emptyMap(),
) {
    /** Gives the label of [key]. */
    fun label(key: ResultKey): String = resultLabels[key] ?: key.englishLabel
}

/**
 * The exports of the inspection log: the CSV file and the text inspection sheet.
 *
 * SuperDRO shows each entry in the units and the precision that the entry stores. The CSV
 * file has one unit in its header: the unit of the first entry. An entry with the other unit goes
 * into the unit of the header. Its precision then changes from d decimals in mm to d + 1 in inch,
 * or from d decimals in inch to d − 1 in mm.
 */
object InspectionExport {

    /** The MIME type of the CSV file. */
    const val CSV_MIME_TYPE: String = "text/csv"

    /** The MIME type of the text sheet. */
    const val TEXT_MIME_TYPE: String = "text/plain"

    /** The line end of the CSV file. */
    const val CSV_LINE_END: String = "\r\n"

    /** The line end of the text sheet. */
    const val TEXT_LINE_END: String = "\n"

    /** The width of a result label in the text sheet. */
    const val LABEL_WIDTH: Int = 12

    /** The 7 fixed CSV headers. */
    val FIXED_HEADERS: List<String> = listOf("Seq", "Timestamp", "Function", "Points", "Axis Angle (°)", "Note", "System Note")

    /** Gives the 23 CSV headers with the unit text "mm" ([metric]) or "in". */
    fun csvHeaders(metric: Boolean): List<String> {
        val unit = MeasurementFormat.unitText(metric)
        return FIXED_HEADERS + ResultKey.entries.flatMap { key -> key.csvHeaders.map { header -> header.replace("<u>", unit) } }
    }

    /**
     * Gives the CSV text of [entries]: 23 columns, each cell in `"` with a `"` in a cell doubled,
     * the separator `,`, and CR LF after each line. [metricWhenEmpty] gives the header unit when
     * there are no entries. [utcOffsetSeconds] gives the local time of the time stamps.
     */
    fun csv(entries: List<MeasurementLogEntry>, utcOffsetSeconds: Int, metricWhenEmpty: Boolean = true): String {
        val metric = entries.firstOrNull()?.display?.metric ?: metricWhenEmpty
        val text = StringBuilder()
        text.append(csvLine(csvHeaders(metric)))
        for (entry in entries) text.append(csvLine(csvRow(entry, metric, utcOffsetSeconds)))
        return text.toString()
    }

    /** Gives the CSV file of [entries] as UTF-8 bytes with no BOM. */
    fun csvBytes(entries: List<MeasurementLogEntry>, utcOffsetSeconds: Int, metricWhenEmpty: Boolean = true): ByteArray =
        csv(entries, utcOffsetSeconds, metricWhenEmpty).encodeToByteArray()

    /** Gives the 23 cells of [entry] in the header unit [metric]. */
    fun csvRow(entry: MeasurementLogEntry, metric: Boolean, utcOffsetSeconds: Int): List<String> {
        val format = formatIn(entry.display, metric)
        val cells = mutableListOf(
            entry.sequence.toString(),
            LogTimestamp.format(entry.createdOn, utcOffsetSeconds),
            entry.title,
            entry.points.mapIndexed { index, point ->
                "P${index + 1} ${sourceText(point.source)} ${pointText(point, format)}"
            }.joinToString("; "),
            if (entry.axisAngle == 0.0) "" else MeasurementFormat.angle(entry.axisAngle),
            entry.note ?: "",
            entry.notes.joinToString("; ") { wire -> PointNote.exportTextOf(wire) },
        )
        val values = entry.results.mapNotNull { logged -> MeasurementResult.valueOf(logged) }
        for (key in ResultKey.entries) {
            val value = values.firstOrNull { (resultKey, _) -> resultKey == key }?.second
            cells += when (value) {
                null -> List(key.quantity.csvCells) { "" }
                is ResultValue.Length -> listOf(MeasurementFormat.length(value.um, format))
                is ResultValue.Diameter -> listOf(MeasurementFormat.length(value.um, format))
                is ResultValue.Angle -> listOf(MeasurementFormat.angle(value.degrees))
                is ResultValue.Position -> listOf(
                    MeasurementFormat.length(value.xUm, format),
                    MeasurementFormat.length(value.yUm, format),
                )
            }
        }
        return cells
    }

    /**
     * Gives the text inspection sheet of [entries], with LF after each line:
     *
     * ```
     * <title>
     * Workspace: <distinct workspace names in entry order, separated by ", ">
     * <empty line>
     * #<seq>  <createdOn>
     * <function title>
     *   <label filled with spaces to 12 characters><value text>     (one line for each result)
     *   Note: <note>                                                 (only when not blank)
     *   System: <notes, separated by ", ">                           (only when there are notes)
     *   P<n> <source>  <x>, <y> <unit>                               (one line for each point)
     * <empty line between two entries>
     * ```
     *
     * A label of 12 or more characters gets one space after it.
     */
    fun textSheet(
        entries: List<MeasurementLogEntry>,
        utcOffsetSeconds: Int,
        labels: InspectionSheetLabels = InspectionSheetLabels(),
    ): String {
        val lines = mutableListOf<String>()
        lines += labels.title
        lines += labels.workspace + " " + entries.map { entry -> entry.workspaceName }.distinct().joinToString(", ")
        lines += ""
        entries.forEachIndexed { index, entry ->
            if (index > 0) lines += ""
            val format = entry.display
            lines += "#${entry.sequence}  ${LogTimestamp.format(entry.createdOn, utcOffsetSeconds)}"
            lines += entry.title
            for (logged in entry.results) {
                val (key, value) = MeasurementResult.valueOf(logged) ?: continue
                val label = labels.label(key)
                val padded = if (label.length >= LABEL_WIDTH) "$label " else label.padEnd(LABEL_WIDTH)
                lines += "  $padded${MeasurementFormat.valueText(value, format)}"
            }
            val note = entry.note
            if (!note.isNullOrBlank()) lines += "  ${labels.note} $note"
            if (entry.notes.isNotEmpty()) {
                lines += "  ${labels.system} " + entry.notes.joinToString(", ") { wire -> PointNote.exportTextOf(wire) }
            }
            entry.points.forEachIndexed { pointIndex, point ->
                lines += "  P${pointIndex + 1} ${sourceText(point.source)}  ${pointText(point, format)}"
            }
        }
        return lines.joinToString(TEXT_LINE_END) + TEXT_LINE_END
    }

    /** Gives the text sheet of [entries] as UTF-8 bytes with no BOM. */
    fun textSheetBytes(
        entries: List<MeasurementLogEntry>,
        utcOffsetSeconds: Int,
        labels: InspectionSheetLabels = InspectionSheetLabels(),
    ): ByteArray = textSheet(entries, utcOffsetSeconds, labels).encodeToByteArray()

    /**
     * Gives the export file name
     * `superdro-inspection_log-<workspace>-<configuration>-<machine>-yyyyMMdd_HHmmss.<extension>`.
     * Each part is cleaned with [cleanPart]. An empty part goes out. `<machine>` is "lathe" when
     * [lathe] is true, else "mill".
     */
    fun fileName(
        workspace: String,
        configuration: String,
        lathe: Boolean,
        time: Instant,
        utcOffsetSeconds: Int,
        extension: String,
    ): String {
        val parts = listOf(
            "superdro",
            "inspection_log",
            workspace,
            configuration,
            if (lathe) "lathe" else "mill",
            LogTimestamp.fileStamp(time, utcOffsetSeconds),
        )
        return parts.map { part -> cleanPart(part) }.filter { part -> part.isNotEmpty() }.joinToString("-") + "." + extension
    }

    /** Changes each run of characters that are not `A-Z a-z 0-9` to `_`, then removes `_` at the two ends. */
    fun cleanPart(text: String): String = text.replace(NOT_ALPHANUMERIC, "_").trim('_')

    /** Gives the source text of a point list: lower case, with `-` in place of `_`. */
    fun sourceText(source: PointSource): String = MeasurementLogPayload.sourceWire(source).lowercase().replace('_', '-')

    private fun pointText(point: CollectedPoint, format: LogDisplayFormat): String =
        "${MeasurementFormat.length(point.x, format)}, ${MeasurementFormat.length(point.y, format)} " +
            MeasurementFormat.unitText(format.metric)

    /** Gives the display format of [stored] in the unit [metric]. */
    internal fun formatIn(stored: LogDisplayFormat, metric: Boolean): LogDisplayFormat = when {
        stored.metric == metric -> stored
        metric -> LogDisplayFormat(metric = true, precision = max(0, stored.precision - 1))
        else -> LogDisplayFormat(metric = false, precision = stored.precision + 1)
    }

    private fun csvLine(cells: List<String>): String =
        cells.joinToString(",") { cell -> "\"" + cell.replace("\"", "\"\"") + "\"" } + CSV_LINE_END

    private val NOT_ALPHANUMERIC = Regex("[^A-Za-z0-9]+")
}
