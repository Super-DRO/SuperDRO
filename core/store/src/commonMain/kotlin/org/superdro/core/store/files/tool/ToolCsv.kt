package org.superdro.core.store.files.tool

import kotlin.math.floor
import kotlin.time.Clock
import kotlin.time.Instant
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.Tool
import org.superdro.core.model.ToolKind
import org.superdro.core.model.newId
import org.superdro.core.store.files.CsvRow
import org.superdro.core.store.files.ExportContext
import org.superdro.core.store.files.ExportFile
import org.superdro.core.store.files.ExportFileNames
import org.superdro.core.store.files.ImportMessage
import org.superdro.core.store.files.ImportOperation
import org.superdro.core.store.files.ImportPreview
import org.superdro.core.store.files.ImportProblem
import org.superdro.core.store.files.ImportReport
import org.superdro.core.store.files.ImportRow
import org.superdro.core.store.files.ImportText
import org.superdro.core.store.files.MachineNames
import org.superdro.core.store.files.MessageKey
import org.superdro.core.store.files.PreviewRow
import org.superdro.core.store.files.RowAction
import org.superdro.core.store.files.Severity
import org.superdro.core.store.files.csv.CsvMetadata
import org.superdro.core.store.files.csv.CsvNumbers
import org.superdro.core.store.files.csv.CsvTable
import org.superdro.core.store.files.csv.CsvWriter
import org.superdro.core.store.files.hasError

/** The columns of the tool CSV file. */
object ToolColumns {
    const val ID = "id"
    const val NAME = "name"
    const val DESCRIPTION = "description"
    const val METRIC = "metric"
    const val DIAMETER = "diameter"
    const val TOOTH_COUNT = "tooth_count"
    const val Z_OFFSET = "z_offset"
    const val INTERNAL = "internal"
    const val X_OFFSET = "x_offset"

    /** The mill export header `id,name,description,metric,diameter,tooth_count,z_offset`. */
    val MILL_HEADER: List<String> = listOf(ID, NAME, DESCRIPTION, METRIC, DIAMETER, TOOTH_COUNT, Z_OFFSET)

    /** The lathe export header `id,name,description,metric,internal,x_offset,z_offset`. */
    val LATHE_HEADER: List<String> = listOf(ID, NAME, DESCRIPTION, METRIC, INTERNAL, X_OFFSET, Z_OFFSET)

    /** The required columns of a mill file: all except `description` and `id`. */
    val MILL_REQUIRED: List<String> = listOf(NAME, METRIC, DIAMETER, TOOTH_COUNT, Z_OFFSET)

    /** The required columns of a lathe file. A blank X or Z offset is 0. */
    val LATHE_REQUIRED: List<String> = listOf(NAME, METRIC, INTERNAL)

    /** 50 characters or less. */
    const val MAX_NAME_LENGTH: Int = 50

    /** 100 characters or less. */
    const val MAX_DESCRIPTION_LENGTH: Int = 100
}

/**
 * The target of a tool import: the tool library of a profile, or the shared library, filtered by
 * the kind of the machine.
 *
 * @property library The tools of the library, for the match by `id` and by name.
 */
data class ToolImportContext(
    val profileId: String,
    val machineType: MachineType,
    val library: List<Tool> = emptyList(),
)

/** One row of the file. [tool] is null when the row has an error. [id] is the `id` text. */
data class ToolRow(
    val row: Int,
    val name: String,
    val id: String,
    val tool: Tool?,
    val problems: List<ImportProblem>,
)

/**
 * The result of an import operation: the records that the repository writes in one transaction.
 *
 * @property deleteIds The tools that the import deletes first ([ImportOperation.ReplaceAll]).
 */
data class ToolImportPlan(
    val operation: ImportOperation,
    val preview: ImportPreview<Tool>,
    val add: List<Tool>,
    val update: List<Tool>,
    val deleteIds: List<String>,
    val report: ImportReport,
) {
    /** "Tool import finished: <a> tools added, <u> tools updated.". */
    val finishedMessage: ImportMessage
        get() = ImportMessage(MessageKey.ToolImportFinished, add.size, update.size)
}

/** The preview of a tool CSV file before the import writes. */
class ToolImportPreview internal constructor(
    val metadata: CsvMetadata,
    val problems: List<ImportProblem>,
    val rows: List<ToolRow>,
    val operations: List<ImportOperation>,
    private val context: ToolImportContext,
    private val now: () -> Instant,
) {
    val usable: Boolean get() = !problems.hasError() && rows.any { row -> row.tool != null }

    val defaultOperation: ImportOperation? get() = operations.firstOrNull()

    /**
     * Gives the plan of [operation]:
     *
     * - [ImportOperation.UpdateMatching]: a row whose `id` is a library tool updates that tool.
     *   Else a row with the name of a library tool updates that tool. Else the row adds a tool. A
     *   second row for the same tool `id` is an error.
     * - [ImportOperation.AddAsNew]: each row adds a tool. The import ignores `id`.
     * - [ImportOperation.ReplaceAll]: the import deletes the library tools, then adds each row.
     *
     * An update keeps the ID, the creation time, and the cutter geometry of the tool.
     */
    fun plan(operation: ImportOperation = defaultOperation ?: ImportOperation.AddAsNew): ToolImportPlan {
        require(!usable || operation in operations) { "The operation $operation is not available." }
        val time = now()
        val previewRows = mutableListOf<PreviewRow<Tool>>()
        val add = mutableListOf<Tool>()
        val update = mutableListOf<Tool>()
        val matchedIds = mutableSetOf<String>()
        rows.forEach { row ->
            val tool = row.tool
            if (tool == null) {
                previewRows += PreviewRow(row.row, row.name, RowAction.Skip, null, row.problems)
                return@forEach
            }
            if (operation != ImportOperation.UpdateMatching) {
                add += tool
                previewRows += PreviewRow(row.row, row.name, RowAction.Add, tool, row.problems)
                return@forEach
            }
            val byId = row.id.takeIf(String::isNotEmpty)?.let { id -> context.library.firstOrNull { libraryTool -> libraryTool.id == id } }
            if (byId != null && byId.id in matchedIds) {
                val problems = row.problems + ImportProblem.error(MessageKey.ToolDuplicateId, row.id, row = row.row)
                previewRows += PreviewRow(row.row, row.name, RowAction.Skip, null, problems)
                return@forEach
            }
            val match = byId ?: context.library.firstOrNull { libraryTool -> libraryTool.name == tool.name && libraryTool.id !in matchedIds }
            if (match == null) {
                add += tool
                previewRows += PreviewRow(row.row, row.name, RowAction.Add, tool, row.problems)
            } else {
                matchedIds += match.id
                val updated = tool.copy(id = match.id, geometry = match.geometry, createdOn = match.createdOn, updatedOn = time)
                update += updated
                previewRows += PreviewRow(row.row, row.name, RowAction.Update, updated, row.problems)
            }
        }
        val preview = ImportPreview.of(previewRows, problems)
        val skippedProblems = previewRows.filter { row -> row.action == RowAction.Skip }
            .flatMap { row -> row.problems.filter { problem -> problem.severity == Severity.Error } }
        return ToolImportPlan(
            operation = operation,
            preview = preview,
            add = add,
            update = update,
            deleteIds = if (operation == ImportOperation.ReplaceAll) context.library.map(Tool::id) else emptyList(),
            report = ImportReport(
                imported = add.size + update.size,
                notImported = skippedProblems,
                defaults = emptyList(),
                probe = emptyList(),
                warnings = preview.warnings,
                notImportedTitle = MessageKey.ReportSkippedRows,
            ),
        )
    }
}

/**
 * The row rules that the CSV source ([ToolCsvImporter]) and the JSON source ([ToolJsonImporter])
 * share.
 *
 * @param parseNumber The expression evaluator.
 */
internal class ToolRowReader(
    private val parseNumber: (String) -> Double?,
    private val now: () -> Instant,
    private val ids: () -> String,
) {

    /**
     * The problems of a header that stop the import: a sub-datum file, or a missing required
     * column. An empty list means that the rows can be read.
     */
    fun headerProblems(header: Collection<String>, lathe: Boolean): List<ImportProblem> {
        // A header with `tags`, or with `x` and no `metric`, is a sub-datum file.
        if ("tags" in header || ("x" in header && ToolColumns.METRIC !in header)) {
            return listOf(ImportProblem.error(MessageKey.FileWrongKind, CsvMetadata.KIND_SUB_DATUMS, CsvMetadata.KIND_TOOLS))
        }
        val required = if (lathe) ToolColumns.LATHE_REQUIRED else ToolColumns.MILL_REQUIRED
        return required.filterNot(header::contains).map { column -> ImportProblem.error(MessageKey.ColumnMissing, column) }
    }

    /** The file problem when no row can import. */
    fun usableProblems(rows: List<ToolRow>): List<ImportProblem> =
        if (rows.none { row -> row.tool != null }) listOf(ImportProblem.error(MessageKey.NothingUsable)) else emptyList()

    /** The operations of a source. [ImportOperation.ReplaceAll] is not available when a row has an error. */
    fun operations(rows: List<ToolRow>): List<ImportOperation> = buildList {
        add(ImportOperation.UpdateMatching)
        add(ImportOperation.AddAsNew)
        if (rows.none { row -> row.problems.hasError() }) add(ImportOperation.ReplaceAll)
    }

    /**
     * Reads one row. [names] holds the names of the earlier rows; a second row with the same name
     * is an error. [zOverride] replaces the `z_offset` text of a lathe row with 8 values.
     */
    fun readRow(values: ImportRow, lathe: Boolean, names: MutableSet<String>, zOverride: String? = null): ToolRow {
        val row = values.row
        val problems = mutableListOf<ImportProblem>()
        val id = values.value(ToolColumns.ID).trim()
        val name = values.value(ToolColumns.NAME).trim()
        val description = values.value(ToolColumns.DESCRIPTION).trim()

        when {
            name.isEmpty() -> problems += ImportProblem.error(MessageKey.NameBlank, row = row)
            name.length > ToolColumns.MAX_NAME_LENGTH ->
                problems += ImportProblem.error(MessageKey.NameTooLong, name.length, ToolColumns.MAX_NAME_LENGTH, row = row)
        }
        if (name.isNotEmpty() && !names.add(name)) {
            problems += ImportProblem.error(MessageKey.ToolDuplicateName, row = row)
        }
        if (description.length > ToolColumns.MAX_DESCRIPTION_LENGTH) {
            problems += ImportProblem.error(MessageKey.DescriptionTooLong, description.length, ToolColumns.MAX_DESCRIPTION_LENGTH, row = row)
        }
        val metric = yesNo(values.value(ToolColumns.METRIC), ToolColumns.METRIC, row, problems)
        val factor = CsvNumbers.unitFactor(metric ?: false)

        fun number(column: String, text: String, required: Boolean): Double? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) {
                if (required) problems += ImportProblem.error(MessageKey.ValueBlank, column, row = row)
                return null
            }
            val value = parseNumber(trimmed)
            if (value == null || !value.isFinite()) {
                problems += ImportProblem.error(MessageKey.ValueNotNumber, column, trimmed, row = row)
                return null
            }
            return value
        }

        val time = now()
        val tool = if (lathe) {
            val internal = yesNo(values.value(ToolColumns.INTERNAL), ToolColumns.INTERNAL, row, problems)
            val xOffset = number(ToolColumns.X_OFFSET, values.value(ToolColumns.X_OFFSET), required = false) ?: 0.0
            val zOffset = number(ToolColumns.Z_OFFSET, zOverride ?: values.value(ToolColumns.Z_OFFSET), required = false) ?: 0.0
            if (problems.hasError() || metric == null || internal == null) {
                null
            } else {
                Tool(
                    id = ids(),
                    kind = ToolKind.Lathe,
                    name = name,
                    description = description,
                    metric = metric,
                    xOffsetUm = xOffset * factor,
                    zOffsetUm = zOffset * factor,
                    internal = internal,
                    toothCount = 1,
                    createdOn = time,
                    updatedOn = time,
                )
            }
        } else {
            val diameter = number(ToolColumns.DIAMETER, values.value(ToolColumns.DIAMETER), required = true)
            if (diameter != null && diameter == 0.0) problems += ImportProblem.error(MessageKey.DiameterZero, row = row)
            val toothText = values.value(ToolColumns.TOOTH_COUNT).trim()
            val tooth = number(ToolColumns.TOOTH_COUNT, toothText, required = true)
            val toothCount = tooth?.let { value -> if (value >= 0.0 && value == floor(value) && value <= Int.MAX_VALUE) value.toInt() else null }
            if (tooth != null && toothCount == null) problems += ImportProblem.error(MessageKey.ToothCountNotWhole, toothText, row = row)
            val zOffset = number(ToolColumns.Z_OFFSET, values.value(ToolColumns.Z_OFFSET), required = true)
            if (problems.hasError() || metric == null || diameter == null || toothCount == null || zOffset == null) {
                null
            } else {
                Tool(
                    id = ids(),
                    kind = ToolKind.Mill,
                    name = name,
                    description = description,
                    metric = metric,
                    diameterUm = diameter * factor,
                    toothCount = toothCount,
                    zOffsetUm = zOffset * factor,
                    createdOn = time,
                    updatedOn = time,
                )
            }
        }
        return ToolRow(row, name, id, tool, problems)
    }

    private fun yesNo(text: String, column: String, row: Int, problems: MutableList<ImportProblem>): Boolean? {
        val trimmed = text.trim()
        return when {
            trimmed.isEmpty() -> {
                problems += ImportProblem.error(MessageKey.ValueBlank, column, row = row)
                null
            }
            trimmed.equals("yes", ignoreCase = true) -> true
            trimmed.equals("no", ignoreCase = true) -> false
            else -> {
                problems += ImportProblem.error(MessageKey.ValueNotYesNo, column, trimmed, row = row)
                null
            }
        }
    }
}

/**
 * The tool CSV import. It reads the files of TouchDRO
 * 3.25.03, also the lathe files with 8 values, the format 2 of 3.32.00, and the SuperDRO
 * export.
 *
 * - `metric` and `internal`: `yes` or `no` in any case. The `metric` column of the row sets
 *   the units of the row.
 * - A second row with the same name is an error.
 * - `tooth_count`: a whole number. `2.0` gives 2.
 * - The import keeps `internal`.
 *
 * @param parseNumber The expression evaluator.
 */
class ToolCsvImporter(
    parseNumber: (String) -> Double? = CsvNumbers::parsePlain,
    private val now: () -> Instant = { Clock.System.now() },
    ids: () -> String = ::newId,
) {
    private val reader = ToolRowReader(parseNumber, now, ids)

    fun read(bytes: ByteArray, context: ToolImportContext): ToolImportPreview =
        when (val decoded = ImportText.decode(bytes)) {
            is ImportText.Decoded.Text -> read(decoded.text, context)
            is ImportText.Decoded.Refused -> refused(listOf(decoded.problem), context)
        }

    /** Reads the text of a file. Each exception gives a problem, not a crash. */
    fun read(text: String, context: ToolImportContext): ToolImportPreview = try {
        readTable(text, context)
    } catch (error: Exception) {
        refused(listOf(ImportProblem.error(MessageKey.FileFailed, error.message ?: error.toString())), context)
    }

    private fun refused(problems: List<ImportProblem>, context: ToolImportContext, metadata: CsvMetadata = CsvMetadata()) =
        ToolImportPreview(metadata, problems, emptyList(), emptyList(), context, now)

    private fun readTable(text: String, context: ToolImportContext): ToolImportPreview {
        val table = when (val read = CsvTable.read(text)) {
            is CsvTable.Companion.Read.Refused -> return refused(listOf(read.problem), context)
            is CsvTable.Companion.Read.Table -> read.table
        }
        val metadata = table.metadata
        val lathe = context.machineType.isLathe
        if (metadata.kind != null && metadata.kind != CsvMetadata.KIND_TOOLS) {
            return refused(listOf(ImportProblem.error(MessageKey.FileWrongKind, metadata.kind, CsvMetadata.KIND_TOOLS)), context, metadata)
        }
        val headerProblems = reader.headerProblems(table.header, lathe)
        if (headerProblems.isNotEmpty()) return refused(headerProblems, context, metadata)
        if (table.records.isEmpty()) return refused(listOf(ImportProblem.error(MessageKey.NoRows)), context, metadata)
        val problems = mutableListOf<ImportProblem>()
        val fileMachine = metadata.machine
        if (fileMachine != null && fileMachine.isLathe != lathe) {
            problems += ImportProblem.warning(MessageKey.MachineMismatch, MachineNames.name(fileMachine), MachineNames.name(context.machineType))
        }

        // A lathe row of TouchDRO 3.25.03 has 8 values for 7 header names.
        val legacyLathe = lathe && !table.has(ToolColumns.TOOTH_COUNT)
        if (legacyLathe && table.records.any { record -> record.values.size == table.header.size + 1 }) {
            problems += ImportProblem.warning(MessageKey.LegacyLatheRows)
        }

        val names = mutableSetOf<String>()
        val rows = table.records.map { record ->
            // With 8 values for 7 header names, the 8th value is the Z offset and the 7th
            // value (the tooth count) is ignored.
            val zOverride = if (legacyLathe && record.values.size == table.header.size + 1) record.values.last() else null
            reader.readRow(CsvRow(table, record), lathe, names, zOverride)
        }
        problems += reader.usableProblems(rows)
        return ToolImportPreview(metadata, problems, rows, reader.operations(rows), context, now)
    }
}

/**
 * The tool CSV export: 7 values for 7 header names,
 * `yes`/`no` for `metric` and `internal`, each value in the units of its tool. The numbers use
 * the 3.32.00 format (mm `0.0####`, inch `0.0#####`). The metadata block has `superdro` and no
 * device line.
 */
object ToolCsvExporter {

    /**
     * @param tools The tools of the library. The export writes only the tools of the machine kind.
     * @param selectedIds The selected tools. An empty set exports all tools.
     */
    fun export(profile: MachineProfile, tools: List<Tool>, context: ExportContext, selectedIds: Set<String> = emptySet()): ExportFile {
        val lathe = profile.machineType.isLathe
        val kind = if (lathe) ToolKind.Lathe else ToolKind.Mill
        val rows = tools.filter { tool -> tool.kind == kind && (selectedIds.isEmpty() || tool.id in selectedIds) }
        val text = buildString {
            append(CsvMetadata.write(CsvMetadata.KIND_TOOLS, context, profile.id, profile.name, profile.machineType))
            append(CsvWriter.record(if (lathe) ToolColumns.LATHE_HEADER else ToolColumns.MILL_HEADER))
            rows.forEach { tool ->
                val values = if (lathe) {
                    listOf(
                        tool.id, tool.name, tool.description, yesNo(tool.metric), yesNo(tool.internal),
                        CsvNumbers.formatCompact(tool.xOffsetUm, tool.metric), CsvNumbers.formatCompact(tool.zOffsetUm, tool.metric),
                    )
                } else {
                    listOf(
                        tool.id, tool.name, tool.description, yesNo(tool.metric), CsvNumbers.formatCompact(tool.diameterUm, tool.metric),
                        tool.toothCount.toString(), CsvNumbers.formatCompact(tool.zOffsetUm, tool.metric),
                    )
                }
                append(CsvWriter.record(values))
            }
        }
        val fileName = ExportFileNames.profileCsv(CsvMetadata.KIND_TOOLS, profile.name, profile.machineType, context.localTime)
        return ExportFile(fileName, ExportFile.MIME_CSV, text)
    }

    private fun yesNo(value: Boolean): String = if (value) "yes" else "no"
}
