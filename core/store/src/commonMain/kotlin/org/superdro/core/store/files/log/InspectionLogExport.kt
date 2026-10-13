package org.superdro.core.store.files.log

import org.superdro.core.model.CollectedPoint
import org.superdro.core.model.LoggedResult
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MeasurementLogEntry
import org.superdro.core.model.PointSource
import org.superdro.core.store.files.ExportContext
import org.superdro.core.store.files.ExportFile
import org.superdro.core.store.files.ExportFileNames
import org.superdro.core.store.files.FileTimes
import org.superdro.core.store.files.ImportMessage
import org.superdro.core.store.files.ImportTexts
import org.superdro.core.store.files.MessageKey
import org.superdro.core.store.files.csv.CsvNumbers
import org.superdro.core.store.files.csv.CsvWriter

/** The kind of a result value. */
internal enum class ResultQuantity { Length, Diameter, Position, Angle }

/**
 * The 12 result keys of the inspection log in enum order, with
 * the CSV header of each column and the label of the text sheet.
 */
internal enum class ResultKey(val wire: String, val quantity: ResultQuantity, val csvHeader: String, val label: MessageKey) {
    Diameter("diameter", ResultQuantity.Diameter, "Diameter", MessageKey.LogLabelDiameter),
    Center("center", ResultQuantity.Position, "Center", MessageKey.LogLabelCenter),
    Distance("distance", ResultQuantity.Length, "Distance", MessageKey.LogLabelDistance),
    Dx("dx", ResultQuantity.Length, "DX", MessageKey.LogLabelDx),
    Dy("dy", ResultQuantity.Length, "DY", MessageKey.LogLabelDy),
    Midpoint("midpoint", ResultQuantity.Position, "Midpoint", MessageKey.LogLabelMidpoint),
    IncludedAngle("included_angle", ResultQuantity.Angle, "Angle", MessageKey.LogLabelIncludedAngle),
    Supplement("supplement", ResultQuantity.Angle, "Supplement", MessageKey.LogLabelSupplement),
    Corner("corner", ResultQuantity.Position, "Corner", MessageKey.LogLabelCorner),
    AngleToX("angle_to_x", ResultQuantity.Angle, "Angle to X", MessageKey.LogLabelAngleToX),
    AngleToY("angle_to_y", ResultQuantity.Angle, "Angle to Y", MessageKey.LogLabelAngleToY),
    Position("position", ResultQuantity.Position, "Position", MessageKey.LogLabelPosition);

    companion object {
        fun of(wire: String): ResultKey? = entries.firstOrNull { key -> key.wire == wire }
    }
}

/**
 * The exports of the inspection log of a workspace.
 *
 * - CSV: 23 columns, each cell in quotes, CR LF, UTF-8 with no byte order mark.
 * - Text: the inspection sheet, LF.
 *
 * Each entry uses its stored unit and decimals. The CSV header has one unit: the unit of
 * the entries when they all have the same unit, else [MachineProfile.metricDefault]. An entry with
 * the other unit gets its decimals for that unit (mm + 1 for inch, inch − 1 for mm).
 */
object InspectionLogExport {

    private const val ANGLE_DECIMALS = 2
    private const val LABEL_WIDTH = 12

    /** The CSV header for the unit text [unit] (`mm` or `in`). */
    fun csvHeader(unit: String): List<String> = listOf("Seq", "Timestamp", "Function", "Points", "Axis Angle (°)", "Note", "System Note") +
        ResultKey.entries.flatMap { key ->
            when (key.quantity) {
                ResultQuantity.Position -> listOf("${key.csvHeader} X ($unit)", "${key.csvHeader} Y ($unit)")
                ResultQuantity.Angle -> listOf("${key.csvHeader} (°)")
                else -> listOf("${key.csvHeader} ($unit)")
            }
        }

    /** The CSV export: `superdro-inspection_log-<workspace>-<profile>-<mill|lathe>-<yyyyMMdd_HHmmss>.csv`. */
    fun csv(entries: List<MeasurementLogEntry>, workspaceName: String, profile: MachineProfile, context: ExportContext): ExportFile {
        val units = entries.map { entry -> entry.display.metric }.distinct()
        val metric = units.singleOrNull() ?: profile.metricDefault
        val unit = unitText(metric)
        val text = buildString {
            append(CsvWriter.record(csvHeader(unit), quoteAll = true))
            entries.sortedBy(MeasurementLogEntry::sequence).forEach { entry ->
                val decimals = decimals(entry, metric)
                val results = entry.results.associateBy(LoggedResult::key)
                val cells = mutableListOf(
                    entry.sequence.toString(),
                    FileTimes.formatWithOffset(entry.createdOn, context.timeZone),
                    entry.title,
                    entry.points.mapIndexed { index, point -> "P${index + 1} ${sourceText(point.source)} ${coordinates(point, metric, decimals)} $unit" }.joinToString("; "),
                    if (entry.axisAngle == 0.0) "" else CsvNumbers.formatFixed(entry.axisAngle, ANGLE_DECIMALS),
                    entry.note.orEmpty(),
                    entry.notes.joinToString("; ", transform = ::noteText),
                )
                ResultKey.entries.forEach { key ->
                    val result = results[key.wire]
                    when (key.quantity) {
                        ResultQuantity.Position -> {
                            cells += result?.x?.let { length(it, metric, decimals) }.orEmpty()
                            cells += result?.y?.let { length(it, metric, decimals) }.orEmpty()
                        }
                        ResultQuantity.Angle -> cells += result?.value?.let { CsvNumbers.formatFixed(it, ANGLE_DECIMALS) }.orEmpty()
                        else -> cells += result?.value?.let { length(it, metric, decimals) }.orEmpty()
                    }
                }
                append(CsvWriter.record(cells, quoteAll = true))
            }
        }
        return ExportFile(fileName(workspaceName, profile, context, "csv"), ExportFile.MIME_CSV, text)
    }

    /** The text inspection sheet: `superdro-inspection_log-<workspace>-<profile>-<mill|lathe>-<yyyyMMdd_HHmmss>.txt`. */
    fun text(
        entries: List<MeasurementLogEntry>,
        workspaceName: String,
        profile: MachineProfile,
        context: ExportContext,
        texts: ImportTexts = ImportTexts.English,
    ): ExportFile {
        val sorted = entries.sortedBy(MeasurementLogEntry::sequence)
        val workspaces = sorted.map(MeasurementLogEntry::workspaceName).filter(String::isNotEmpty).distinct().ifEmpty { listOf(workspaceName) }
        val text = buildString {
            append(texts.text(ImportMessage(MessageKey.LogTitle))).append('\n')
            append(texts.text(ImportMessage(MessageKey.LogWorkspace, workspaces.joinToString(", ")))).append('\n')
            sorted.forEach { entry ->
                val metric = entry.display.metric
                val decimals = entry.display.precision
                append('\n')
                append('#').append(entry.sequence).append("  ").append(FileTimes.formatWithOffset(entry.createdOn, context.timeZone)).append('\n')
                append(entry.title).append('\n')
                entry.results.forEach { result ->
                    val key = ResultKey.of(result.key) ?: return@forEach
                    val value = valueText(key, result, metric, decimals) ?: return@forEach
                    append("  ").append(texts.text(ImportMessage(key.label)).padEnd(LABEL_WIDTH)).append(value).append('\n')
                }
                entry.note?.takeIf(String::isNotBlank)?.let { note -> append("  ").append(texts.text(ImportMessage(MessageKey.LogNote, note))).append('\n') }
                if (entry.notes.isNotEmpty()) {
                    append("  ").append(texts.text(ImportMessage(MessageKey.LogSystem, entry.notes.joinToString(", ", transform = ::noteText)))).append('\n')
                }
                entry.points.forEachIndexed { index, point ->
                    append("  P").append(index + 1).append(' ').append(sourceText(point.source)).append("  ")
                        .append(coordinates(point, metric, decimals)).append(' ').append(unitText(metric)).append('\n')
                }
            }
        }
        return ExportFile(fileName(workspaceName, profile, context, "txt"), ExportFile.MIME_TEXT, text)
    }

    private fun fileName(workspaceName: String, profile: MachineProfile, context: ExportContext, extension: String): String =
        ExportFileNames.name(
            listOf("inspection-log", workspaceName, profile.name, ExportFileNames.millOrLathe(profile.machineType)),
            context.localTime,
            extension,
        )

    /** The decimals of [entry] in the unit [metric]. */
    private fun decimals(entry: MeasurementLogEntry, metric: Boolean): Int = when {
        entry.display.metric == metric -> entry.display.precision
        metric -> (entry.display.precision - 1).coerceAtLeast(0)
        else -> entry.display.precision + 1
    }

    private fun length(um: Double, metric: Boolean, decimals: Int): String = CsvNumbers.formatFixed(um / CsvNumbers.unitFactor(metric), decimals)

    private fun coordinates(point: CollectedPoint, metric: Boolean, decimals: Int): String =
        "${length(point.x, metric, decimals)}, ${length(point.y, metric, decimals)}"

    /** The value text of the sheet: `50.000 mm`, `1.9685"`, `Ø 50.000 mm`, `20.000, 15.000 mm`, `120.00°`. */
    private fun valueText(key: ResultKey, result: LoggedResult, metric: Boolean, decimals: Int): String? {
        val suffix = if (metric) " mm" else "\""
        return when (key.quantity) {
            ResultQuantity.Length -> result.value?.let { length(it, metric, decimals) + suffix }
            ResultQuantity.Diameter -> result.value?.let { "Ø " + length(it, metric, decimals) + suffix }
            ResultQuantity.Position -> {
                val x = result.x ?: return null
                val y = result.y ?: return null
                "${length(x, metric, decimals)}, ${length(y, metric, decimals)}$suffix"
            }
            ResultQuantity.Angle -> result.value?.let { CsvNumbers.formatFixed(it, ANGLE_DECIMALS) + "°" }
        }
    }

    private fun unitText(metric: Boolean): String = if (metric) "mm" else "in"

    /** `latch`, `probe`, `sub-datum`, `camera`. */
    private fun sourceText(source: PointSource): String = when (source) {
        PointSource.Latch -> "latch"
        PointSource.Probe -> "probe"
        PointSource.SubDatum -> "sub-datum"
        PointSource.Camera -> "camera"
    }

    /** `UNCOMPENSATED` gives `uncompensated`; `FRAME_CHANGED` gives `frame changed`. */
    private fun noteText(note: String): String = note.lowercase().replace('_', ' ')
}
