package org.superdro.core.store.files.subdatum

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.json.JsonObject
import org.superdro.core.model.newId
import org.superdro.core.store.files.ImportProblem
import org.superdro.core.store.files.MessageKey
import org.superdro.core.store.files.csv.CsvMetadata
import org.superdro.core.store.files.csv.CsvNumbers
import org.superdro.core.store.files.jsonHeader
import org.superdro.core.store.files.jsonRows

/**
 * The sub-datum import from a JSON list, for the MCP tool `put_sub_datums`. The row rules are
 * the rules of [SubDatumCsvImporter]. The preview and the plan are the CSV types.
 *
 * - The keys of a row are the CSV column names: `id`, `name`, `note`, `x`, `y`, `z`, `a`, and
 *   `tags`. The header of the source is the union of the keys of all rows.
 * - A number is a JSON number or a text in the CSV form. `x`, `y`, and `z` are in the unit
 *   [read] gets. `a` is in degrees. `tags` is an array of strings or one text with spaces.
 * - The row number of a problem is the position in the list (1 for the first object).
 * - The operations are [org.superdro.core.store.files.ImportOperation.UpdateMatching] (the
 *   default: a row updates the stored point with its `id`, else with its name), then the CSV
 *   operations.
 *
 * @param parseNumber The number parser of a text value. The default reads the CSV form.
 */
class SubDatumJsonImporter(
    private val now: () -> Instant = { Clock.System.now() },
    parseNumber: (String) -> Double? = CsvNumbers::parsePlain,
    ids: () -> String = ::newId,
) {
    private val reader = SubDatumRowReader(parseNumber, now, ids)

    /**
     * Reads [rows] in the unit [metric] (true for mm). Each exception gives a problem, not a
     * crash.
     */
    fun read(rows: List<JsonObject>, metric: Boolean, context: SubDatumImportContext): SubDatumImportPreview = try {
        readRows(rows, metric, context)
    } catch (error: Exception) {
        refused(listOf(ImportProblem.error(MessageKey.FileFailed, error.message ?: error.toString())), metric, context)
    }

    private fun refused(problems: List<ImportProblem>, metric: Boolean, context: SubDatumImportContext) =
        SubDatumImportPreview(metadata(metric), metric, problems, emptyList(), emptyList(), context, now)

    private fun metadata(metric: Boolean) = CsvMetadata(kind = CsvMetadata.KIND_SUB_DATUMS, metric = metric)

    private fun readRows(objects: List<JsonObject>, metric: Boolean, context: SubDatumImportContext): SubDatumImportPreview {
        val lathe = context.machineType.isLathe
        if (objects.isEmpty()) return refused(listOf(ImportProblem.error(MessageKey.NoRows)), metric, context)
        val sourceRows = jsonRows(objects)
        val header = jsonHeader(sourceRows)
        val headerProblems = reader.headerProblems(header, lathe)
        if (headerProblems.isNotEmpty()) return refused(headerProblems, metric, context)

        val problems = mutableListOf<ImportProblem>()
        if (reader.hasLatheY(header, sourceRows, lathe)) problems += ImportProblem.warning(MessageKey.LatheYIgnored)
        val rows = sourceRows.map { values -> reader.readRow(values, metric, lathe) }
        problems += reader.usableProblems(rows)
        val operations = reader.operations(rows, ownFile = false, updateMatching = true)
        return SubDatumImportPreview(metadata(metric), metric, problems, rows, operations, context, now)
    }
}
