package org.superdro.core.store.files.tool

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
 * The tool import from a JSON list, for the MCP tool `put_tools`. The row rules are the rules of
 * [ToolCsvImporter]. The preview and the plan are the CSV types.
 *
 * - The keys of a row are the CSV column names: `id`, `name`, `description`, `metric`,
 *   `diameter`, `tooth_count`, `z_offset`, `internal`, and `x_offset`. The header of the source
 *   is the union of the keys of all rows.
 * - A number is a JSON number or a text in the CSV form. `metric` and `internal` are JSON
 *   booleans or the texts `yes` and `no`. The `metric` value of a row sets the unit of the row.
 * - The row number of a problem is the position in the list (1 for the first object).
 *
 * @param parseNumber The number parser of a text value. The default reads the CSV form.
 */
class ToolJsonImporter(
    private val now: () -> Instant = { Clock.System.now() },
    parseNumber: (String) -> Double? = CsvNumbers::parsePlain,
    ids: () -> String = ::newId,
) {
    private val reader = ToolRowReader(parseNumber, now, ids)

    /** Reads [rows]. Each exception gives a problem, not a crash. */
    fun read(rows: List<JsonObject>, context: ToolImportContext): ToolImportPreview = try {
        readRows(rows, context)
    } catch (error: Exception) {
        refused(listOf(ImportProblem.error(MessageKey.FileFailed, error.message ?: error.toString())), context)
    }

    private fun refused(problems: List<ImportProblem>, context: ToolImportContext) =
        ToolImportPreview(METADATA, problems, emptyList(), emptyList(), context, now)

    private fun readRows(objects: List<JsonObject>, context: ToolImportContext): ToolImportPreview {
        val lathe = context.machineType.isLathe
        if (objects.isEmpty()) return refused(listOf(ImportProblem.error(MessageKey.NoRows)), context)
        val sourceRows = jsonRows(objects)
        val headerProblems = reader.headerProblems(jsonHeader(sourceRows), lathe)
        if (headerProblems.isNotEmpty()) return refused(headerProblems, context)

        val names = mutableSetOf<String>()
        val rows = sourceRows.map { values -> reader.readRow(values, lathe, names) }
        val problems = reader.usableProblems(rows)
        return ToolImportPreview(METADATA, problems, rows, reader.operations(rows), context, now)
    }

    private companion object {
        val METADATA = CsvMetadata(kind = CsvMetadata.KIND_TOOLS)
    }
}
