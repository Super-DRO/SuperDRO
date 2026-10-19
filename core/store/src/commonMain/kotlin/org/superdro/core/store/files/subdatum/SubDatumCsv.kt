package org.superdro.core.store.files.subdatum

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.time.Clock
import kotlin.time.Instant
import org.superdro.core.model.AxisId
import org.superdro.core.model.Guide
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.SubDatum
import org.superdro.core.model.newId
import org.superdro.core.store.files.ExportContext
import org.superdro.core.store.files.ExportFile
import org.superdro.core.store.files.ExportFileNames
import org.superdro.core.store.files.FileSource
import org.superdro.core.store.files.ImportMessage
import org.superdro.core.store.files.ImportOperation
import org.superdro.core.store.files.ImportPreview
import org.superdro.core.store.files.ImportProblem
import org.superdro.core.store.files.ImportReport
import org.superdro.core.store.files.ImportText
import org.superdro.core.store.files.MachineNames
import org.superdro.core.store.files.MessageKey
import org.superdro.core.store.files.PreviewRow
import org.superdro.core.store.files.RowAction
import org.superdro.core.store.files.Severity
import org.superdro.core.store.files.csv.CsvMetadata
import org.superdro.core.store.files.csv.CsvNumbers
import org.superdro.core.store.files.csv.CsvRecord
import org.superdro.core.store.files.csv.CsvTable
import org.superdro.core.store.files.csv.CsvWriter
import org.superdro.core.store.files.hasError

/** The columns of the sub-datum CSV file. */
object SubDatumColumns {
    const val NAME = "name"
    const val NOTE = "note"
    const val TAGS = "tags"
    const val X = "x"
    const val Y = "y"
    const val Z = "z"

    /** The export header `name,note,tags,x,y,z`: always six columns. */
    val HEADER: List<String> = listOf(NAME, NOTE, TAGS, X, Y, Z)

    /** The name of a guide row. */
    const val GUIDE_NAME = "Guide"

    /** The header names that show a tool file. */
    val TOOL_COLUMNS: Set<String> = setOf("x_offset", "diameter", "tooth_count", "internal")
}

/**
 * The target of a sub-datum import: the active workspace of a machine profile.
 *
 * @property subDatums The sub-datums of the active workspace, for the point match.
 * @property guides The guides of the active workspace.
 * @property unitsOverride The unit that the user selected: true for mm, false for inch. Null uses
 *   the `units` line of the file, else the metric default of the profile.
 */
data class SubDatumImportContext(
    val profileId: String,
    val machineType: MachineType,
    val metricDefault: Boolean,
    val subDatums: List<SubDatum> = emptyList(),
    val guides: List<Guide> = emptyList(),
    val unitsOverride: Boolean? = null,
)

/** The tolerance of each axis of a file point, in µm, from the decimals of the file text. */
data class PointTolerance(val x: Double? = null, val y: Double? = null, val z: Double? = null)

/** The record of one good row of a sub-datum file. */
sealed interface SubDatumItem {
    /** A sub-datum. */
    data class Point(val subDatum: SubDatum, val tolerance: PointTolerance) : SubDatumItem

    /** A row with one coordinate and the name "Guide". */
    data class Line(val guide: Guide, val toleranceUm: Double) : SubDatumItem
}

/** One row of the file. [item] is null when the row has an error. */
data class SubDatumRow(
    val row: Int,
    val name: String,
    val item: SubDatumItem?,
    val problems: List<ImportProblem>,
)

/**
 * The result of an import operation: the records that the repository writes in one transaction.
 *
 * @property deleteAll True for [ImportOperation.ReplaceAll]: delete all sub-datums and guides of
 *   the workspace first.
 */
data class SubDatumImportPlan(
    val operation: ImportOperation,
    val preview: ImportPreview<SubDatumItem>,
    val addSubDatums: List<SubDatum>,
    val updateSubDatums: List<SubDatum>,
    val addGuides: List<Guide>,
    val deleteAll: Boolean,
    val report: ImportReport,
) {
    /** "Sub-datum import finished: <a> points added, <u> points updated, <g> guides added." */
    val finishedMessage: ImportMessage
        get() = ImportMessage(MessageKey.SubDatumImportFinished, addSubDatums.size, updateSubDatums.size, addGuides.size)
}

/**
 * The preview of a sub-datum CSV file before the import writes.
 *
 * @property metric The unit of the numbers: true for mm.
 * @property problems The problems of the full file. An error stops the import.
 * @property operations The available operations. The first one is the default.
 */
class SubDatumImportPreview internal constructor(
    val metadata: CsvMetadata,
    val metric: Boolean,
    val problems: List<ImportProblem>,
    val rows: List<SubDatumRow>,
    val operations: List<ImportOperation>,
    private val context: SubDatumImportContext,
    private val now: () -> Instant,
) {
    /** True when the import can write one or more rows. */
    val usable: Boolean get() = !problems.hasError() && rows.any { row -> row.item != null }

    val defaultOperation: ImportOperation? get() = operations.firstOrNull()

    /** Gives the plan of [operation]. The operation must be one of [operations]. */
    fun plan(operation: ImportOperation = defaultOperation ?: ImportOperation.AddAsNew): SubDatumImportPlan {
        require(!usable || operation in operations) { "The operation $operation is not available." }
        val time = now()
        val previewRows = mutableListOf<PreviewRow<SubDatumItem>>()
        val add = mutableListOf<SubDatum>()
        val updates = LinkedHashMap<String, SubDatum>()
        val addGuides = mutableListOf<Guide>()
        val matchOperation = operation == ImportOperation.AddNewOnly || operation == ImportOperation.MergeMatching
        // The grids find the possible matches of a row without a test of each earlier row and each
        // sub-datum. The result is the same as the result of a test of each one.
        val pointCell = SubDatumMatch.pointCell(rows)
        val earlierPoints = PointGrid(pointCell)
        val earlierLines = LineGrid(SubDatumMatch.lineCell(rows))
        // The sub-datums of the workspace, with the updates of the earlier rows. An update does not
        // change the coordinates, thus the grid of the workspace stays correct.
        val workspace = context.subDatums.toMutableList()
        val workspaceGrid = PointGrid(pointCell)
        if (matchOperation) workspace.forEach(workspaceGrid::add)

        rows.forEach { row ->
            val item = row.item
            if (item == null) {
                previewRows += PreviewRow(row.row, row.name, RowAction.Skip, null, row.problems)
                return@forEach
            }
            if (matchOperation && SubDatumMatch.isDuplicate(item, earlierPoints, earlierLines)) {
                val problems = row.problems + ImportProblem.error(MessageKey.DuplicatePoint, row = row.row)
                previewRows += PreviewRow(row.row, row.name, RowAction.Skip, null, problems)
                return@forEach
            }
            if (matchOperation) {
                when (item) {
                    is SubDatumItem.Point -> earlierPoints.add(item.subDatum)
                    is SubDatumItem.Line -> earlierLines.add(item.guide)
                }
            }
            when (item) {
                is SubDatumItem.Point -> {
                    val matchIndex = if (matchOperation) SubDatumMatch.nearestIndex(item, workspaceGrid) else null
                    val match = matchIndex?.let(workspace::get)
                    when {
                        match == null -> {
                            add += item.subDatum
                            previewRows += PreviewRow(row.row, row.name, RowAction.Add, item, row.problems)
                        }
                        operation == ImportOperation.AddNewOnly ->
                            previewRows += PreviewRow(row.row, row.name, RowAction.AlreadyHere, item, row.problems)
                        else -> {
                            val file = item.subDatum
                            val merged = match.copy(
                                name = file.name,
                                note = file.note,
                                tags = match.tags + file.tags.filter { tag -> tag !in match.tags },
                                updatedOn = time,
                            )
                            updates[match.id] = merged
                            workspace[matchIndex] = merged
                            previewRows += PreviewRow(row.row, row.name, RowAction.Update, SubDatumItem.Point(merged, item.tolerance), row.problems)
                        }
                    }
                }
                is SubDatumItem.Line -> {
                    val here = matchOperation && context.guides.any { guide -> SubDatumMatch.sameGuide(item, guide) }
                    if (here) {
                        previewRows += PreviewRow(row.row, row.name, RowAction.AlreadyHere, item, row.problems)
                    } else {
                        addGuides += item.guide
                        previewRows += PreviewRow(row.row, row.name, RowAction.Add, item, row.problems)
                    }
                }
            }
        }
        val preview = ImportPreview.of(previewRows, problems)
        val skippedProblems = previewRows.filter { row -> row.action == RowAction.Skip }
            .flatMap { row -> row.problems.filter { problem -> problem.severity == Severity.Error } }
        return SubDatumImportPlan(
            operation = operation,
            preview = preview,
            addSubDatums = add,
            updateSubDatums = updates.values.toList(),
            addGuides = addGuides,
            deleteAll = operation == ImportOperation.ReplaceAll,
            report = ImportReport(
                imported = add.size + updates.size + addGuides.size,
                notImported = skippedProblems,
                defaults = emptyList(),
                probe = emptyList(),
                warnings = preview.warnings,
                notImportedTitle = MessageKey.ReportSkippedRows,
            ),
        )
    }
}

/** The point match of the sub-datum import. */
internal object SubDatumMatch {

    /**
     * True when each axis agrees: both values are absent, or the difference is not more than the
     * tolerance of the file value. An axis that only one point has does not agree.
     */
    fun matches(file: SubDatum, tolerance: PointTolerance, other: SubDatum): Boolean =
        agrees(file.x, tolerance.x, other.x) && agrees(file.y, tolerance.y, other.y) && agrees(file.z, tolerance.z, other.z)

    private fun agrees(value: Double?, tolerance: Double?, other: Double?): Boolean = when {
        value == null && other == null -> true
        value == null || other == null -> false
        else -> abs(value - other) <= (tolerance ?: 0.0)
    }

    /** The matching sub-datum with the smallest sum of squares, or null. */
    fun nearest(item: SubDatumItem.Point, candidates: List<SubDatum>): SubDatum? =
        candidates.filter { candidate -> matches(item.subDatum, item.tolerance, candidate) }
            .minByOrNull { candidate -> squares(item.subDatum, candidate) }

    /**
     * The index in [grid] of the matching point with the smallest sum of squares, or null. When
     * two points have the same sum, the point with the lower index wins, as in [nearest].
     */
    fun nearestIndex(item: SubDatumItem.Point, grid: PointGrid): Int? {
        var best: Int? = null
        var bestSquares = 0.0
        grid.forEachCandidate(item) { index, candidate ->
            if (!matches(item.subDatum, item.tolerance, candidate)) return@forEachCandidate
            val value = squares(item.subDatum, candidate)
            val current = best
            if (current == null || value < bestSquares || (value == bestSquares && index < current)) {
                best = index
                bestSquares = value
            }
        }
        return best
    }

    /** True when an earlier row of the file has the same point or guide as [item]. */
    fun isDuplicate(item: SubDatumItem, points: PointGrid, lines: LineGrid): Boolean {
        var found = false
        when (item) {
            is SubDatumItem.Point -> points.forEachCandidate(item) { _, other ->
                if (!found && matches(item.subDatum, item.tolerance, other)) found = true
            }
            is SubDatumItem.Line -> lines.forEachCandidate(item) { other ->
                if (!found && sameGuide(item, other)) found = true
            }
        }
        return found
    }

    /** The cell size of the point grid: two times the largest point tolerance of the file, in µm. */
    fun pointCell(rows: List<SubDatumRow>): Double = cellOf(
        rows.mapNotNull { row -> row.item as? SubDatumItem.Point }
            .flatMap { point -> listOf(point.tolerance.x, point.tolerance.y, point.tolerance.z) }
            .map { tolerance -> tolerance ?: 0.0 },
    )

    /** The cell size of the guide grid: two times the largest guide tolerance of the file, in µm. */
    fun lineCell(rows: List<SubDatumRow>): Double =
        cellOf(rows.mapNotNull { row -> (row.item as? SubDatumItem.Line)?.toleranceUm })

    private fun cellOf(tolerances: List<Double>): Double {
        var largest = 0.0
        for (tolerance in tolerances) {
            if (tolerance.isNaN()) return Double.NaN
            largest = max(largest, tolerance)
        }
        return max(2.0 * largest, MIN_CELL_UM)
    }

    /** The minimum cell size, in µm. */
    private const val MIN_CELL_UM: Double = 1.0

    private fun squares(a: SubDatum, b: SubDatum): Double =
        listOf(a.x to b.x, a.y to b.y, a.z to b.z).sumOf { (first, second) ->
            if (first == null || second == null) 0.0 else (first - second) * (first - second)
        }

    fun sameGuide(item: SubDatumItem.Line, guide: Guide): Boolean =
        item.guide.axis == guide.axis && abs(item.guide.valueUm - guide.valueUm) <= item.toleranceUm

    fun sameItem(item: SubDatumItem, other: SubDatumItem): Boolean = when {
        item is SubDatumItem.Point && other is SubDatumItem.Point -> matches(item.subDatum, item.tolerance, other.subDatum)
        item is SubDatumItem.Line && other is SubDatumItem.Line -> sameGuide(item, other.guide)
        else -> false
    }
}

/**
 * A grid of points with cells of [cellUm] µm on each axis. A point goes into the cell of its
 * coordinates and of its set of axes. Each tolerance of a query must be not more than half of a
 * cell. Then a matching point is in the cell of the query or in an adjacent cell. A point or a
 * query that the grid cannot hold (a value that is not finite) uses a test of all points.
 */
internal class PointGrid(private val cellUm: Double) {

    private data class Cell(val axes: Int, val x: Long, val y: Long, val z: Long)

    private val points = ArrayList<SubDatum>()
    private val cells = HashMap<Cell, MutableList<Int>>()
    private val loose = ArrayList<Int>()
    private val usable = cellUm.isFinite() && cellUm > 0.0

    /** Adds a point. Its index is the number of points before it. */
    fun add(point: SubDatum) {
        val index = points.size
        points += point
        val cell = if (usable) cellOf(point) else null
        if (cell == null) loose += index else cells.getOrPut(cell) { ArrayList() } += index
    }

    /**
     * Gives each point that can match [query] to [action], with its index. The order is not
     * defined. Each point that matches is in the result.
     */
    fun forEachCandidate(query: SubDatumItem.Point, action: (Int, SubDatum) -> Unit) {
        val center = if (usable && fits(query.tolerance)) cellOf(query.subDatum) else null
        if (center == null) {
            points.forEachIndexed(action)
            return
        }
        for (index in loose) action(index, points[index])
        val xs = neighbors(center.axes and X_BIT != 0, center.x)
        val ys = neighbors(center.axes and Y_BIT != 0, center.y)
        val zs = neighbors(center.axes and Z_BIT != 0, center.z)
        for (x in xs) for (y in ys) for (z in zs) {
            cells[Cell(center.axes, x, y, z)]?.forEach { index -> action(index, points[index]) }
        }
    }

    private fun neighbors(present: Boolean, value: Long): List<Long> =
        if (present) listOf(value - 1, value, value + 1).distinct() else listOf(0L)

    private fun fits(tolerance: PointTolerance): Boolean =
        listOf(tolerance.x, tolerance.y, tolerance.z).all { value -> value == null || value <= cellUm / 2.0 }

    private fun cellOf(point: SubDatum): Cell? {
        val values = listOf(point.x, point.y, point.z)
        if (values.any { value -> value != null && !value.isFinite() }) return null
        var axes = 0
        if (point.x != null) axes = axes or X_BIT
        if (point.y != null) axes = axes or Y_BIT
        if (point.z != null) axes = axes or Z_BIT
        return Cell(axes, index(point.x), index(point.y), index(point.z))
    }

    private fun index(value: Double?): Long = if (value == null) 0L else floor(value / cellUm).toLong()

    private companion object {
        const val X_BIT = 1
        const val Y_BIT = 2
        const val Z_BIT = 4
    }
}

/**
 * A grid of guides with cells of [cellUm] µm on each axis. The rules are the rules of [PointGrid].
 */
internal class LineGrid(private val cellUm: Double) {

    private val guides = ArrayList<Guide>()
    private val cells = HashMap<Pair<AxisId, Long>, MutableList<Guide>>()
    private val loose = ArrayList<Guide>()
    private val usable = cellUm.isFinite() && cellUm > 0.0

    /** Adds a guide. */
    fun add(guide: Guide) {
        guides += guide
        if (usable && guide.valueUm.isFinite()) {
            cells.getOrPut(guide.axis to floor(guide.valueUm / cellUm).toLong()) { ArrayList() } += guide
        } else {
            loose += guide
        }
    }

    /** Gives each guide that can match [query] to [action]. Each guide that matches is in the result. */
    fun forEachCandidate(query: SubDatumItem.Line, action: (Guide) -> Unit) {
        val value = query.guide.valueUm
        if (!usable || !value.isFinite() || !(query.toleranceUm <= cellUm / 2.0)) {
            guides.forEach(action)
            return
        }
        loose.forEach(action)
        val center = floor(value / cellUm).toLong()
        listOf(center - 1, center, center + 1).distinct().forEach { cell ->
            cells[query.guide.axis to cell]?.forEach(action)
        }
    }
}

/**
 * The sub-datum CSV import. It reads the files of
 * TouchDRO 3.25.03 (no metadata) and the format 2 of 3.32.00, and the SuperDRO export.
 *
 * @param parseNumber The expression evaluator. It gives the number of a text in the file
 *   unit, or null.
 */
class SubDatumCsvImporter(
    private val parseNumber: (String) -> Double? = CsvNumbers::parsePlain,
    private val now: () -> Instant = { Clock.System.now() },
    private val ids: () -> String = ::newId,
) {

    /** Reads the bytes of a file. Each exception gives a problem, not a crash. */
    fun read(bytes: ByteArray, context: SubDatumImportContext): SubDatumImportPreview =
        when (val decoded = ImportText.decode(bytes)) {
            is ImportText.Decoded.Text -> read(decoded.text, context)
            is ImportText.Decoded.Refused -> refused(listOf(decoded.problem), context)
        }

    /** Reads the text of a file. Each exception gives a problem, not a crash. */
    fun read(text: String, context: SubDatumImportContext): SubDatumImportPreview = try {
        readTable(text, context)
    } catch (error: Exception) {
        refused(listOf(ImportProblem.error(MessageKey.FileFailed, error.message ?: error.toString())), context)
    }

    private fun refused(problems: List<ImportProblem>, context: SubDatumImportContext, metadata: CsvMetadata = CsvMetadata()) =
        SubDatumImportPreview(metadata, context.unitsOverride ?: context.metricDefault, problems, emptyList(), emptyList(), context, now)

    private fun readTable(text: String, context: SubDatumImportContext): SubDatumImportPreview {
        val table = when (val read = CsvTable.read(text)) {
            is CsvTable.Companion.Read.Refused -> return refused(listOf(read.problem), context)
            is CsvTable.Companion.Read.Table -> read.table
        }
        val metadata = table.metadata
        val lathe = context.machineType.isLathe
        val problems = mutableListOf<ImportProblem>()

        if (metadata.kind != null && metadata.kind != CsvMetadata.KIND_SUB_DATUMS) {
            return refused(listOf(ImportProblem.error(MessageKey.FileWrongKind, metadata.kind, CsvMetadata.KIND_SUB_DATUMS)), context, metadata)
        }
        if (table.header.any { column -> column in SubDatumColumns.TOOL_COLUMNS }) {
            return refused(listOf(ImportProblem.error(MessageKey.FileWrongKind, CsvMetadata.KIND_TOOLS, CsvMetadata.KIND_SUB_DATUMS)), context, metadata)
        }
        val required = if (lathe) listOf(SubDatumColumns.NAME, SubDatumColumns.X, SubDatumColumns.Z) else listOf(SubDatumColumns.NAME, SubDatumColumns.X)
        required.filterNot(table::has).forEach { column -> problems += ImportProblem.error(MessageKey.ColumnMissing, column) }
        if (problems.isNotEmpty()) return refused(problems, context, metadata)
        if (table.records.isEmpty()) return refused(listOf(ImportProblem.error(MessageKey.NoRows)), context, metadata)

        val metric = context.unitsOverride ?: metadata.metric ?: context.metricDefault
        metadata.unknownUnits?.let { units -> problems += ImportProblem.warning(MessageKey.UnitsUnknown, units) }
        when {
            context.unitsOverride != null && metadata.metric != null && metadata.metric != context.unitsOverride ->
                problems += ImportProblem.warning(MessageKey.UnitsOverridden, unitName(metadata.metric), unitName(context.unitsOverride))
            context.unitsOverride == null && metadata.metric == null ->
                problems += ImportProblem.warning(MessageKey.UnitsUndeclared, unitName(context.metricDefault))
        }
        val fileMachine = metadata.machine
        if (fileMachine != null && fileMachine.isLathe != lathe) {
            problems += ImportProblem.warning(MessageKey.MachineMismatch, MachineNames.name(fileMachine), MachineNames.name(context.machineType))
        }
        if (lathe && table.has(SubDatumColumns.Y) && table.records.any { record -> table.value(record, SubDatumColumns.Y).isNotBlank() }) {
            problems += ImportProblem.warning(MessageKey.LatheYIgnored)
        }

        val rows = table.records.map { record -> readRow(table, record, metric, lathe) }
        if (rows.none { row -> row.item != null }) problems += ImportProblem.error(MessageKey.NothingUsable)

        val ownFile = metadata.source == FileSource.SuperDro && metadata.profileId == context.profileId
        val operations = buildList {
            if (ownFile) {
                add(ImportOperation.MergeMatching)
                add(ImportOperation.AddAsNew)
                add(ImportOperation.AddNewOnly)
            } else {
                add(ImportOperation.AddAsNew)
                add(ImportOperation.AddNewOnly)
                add(ImportOperation.MergeMatching)
            }
            // REPLACE_ALL is not available when a row has an error.
            if (rows.none { row -> row.problems.hasError() }) add(ImportOperation.ReplaceAll)
        }
        return SubDatumImportPreview(metadata, metric, problems, rows, operations, context, now)
    }

    private fun readRow(table: CsvTable, record: CsvRecord, metric: Boolean, lathe: Boolean): SubDatumRow {
        val row = record.row
        val problems = mutableListOf<ImportProblem>()
        val name = table.value(record, SubDatumColumns.NAME).trim()
        val note = table.value(record, SubDatumColumns.NOTE)
        val tags = table.value(record, SubDatumColumns.TAGS).split(WHITESPACE).filter(String::isNotEmpty)
        val factor = CsvNumbers.unitFactor(metric)

        fun coordinate(column: String): Pair<Double?, Double?> {
            val text = table.value(record, column).trim()
            if (text.isEmpty()) return null to null
            val number = parseNumber(text)
            if (number == null || !number.isFinite()) {
                problems += ImportProblem.error(MessageKey.ValueNotNumber, column, text, row = row)
                return null to null
            }
            return number * factor to CsvNumbers.tolerance(text, metric)
        }

        val (x, toleranceX) = coordinate(SubDatumColumns.X)
        val (y, toleranceY) = if (lathe) null to null else coordinate(SubDatumColumns.Y)
        val (z, toleranceZ) = coordinate(SubDatumColumns.Z)
        val count = listOfNotNull(x, y, z).size

        if (name.isEmpty()) problems += ImportProblem.error(MessageKey.NameBlank, row = row)
        val guide = name == SubDatumColumns.GUIDE_NAME && count == 1
        if (!problems.hasError() && !guide) {
            when {
                count == 0 -> problems += ImportProblem.error(MessageKey.NoCoordinate, row = row)
                x == null -> problems += ImportProblem.error(MessageKey.ValueBlank, SubDatumColumns.X, row = row)
                lathe && z == null -> problems += ImportProblem.error(MessageKey.ValueBlank, SubDatumColumns.Z, row = row)
            }
        }
        if (problems.hasError()) return SubDatumRow(row, name, null, problems)

        val time = now()
        val item = if (guide) {
            val (axis, value, tolerance) = when {
                x != null -> Triple(AxisId.X, x, toleranceX)
                y != null -> Triple(AxisId.Y, y, toleranceY)
                else -> Triple(AxisId.Z, z!!, toleranceZ)
            }
            SubDatumItem.Line(Guide(id = ids(), axis = axis, valueUm = value, createdOn = time), tolerance ?: 0.0)
        } else {
            SubDatumItem.Point(
                SubDatum(id = ids(), name = name, note = note, tags = tags, x = x, y = y, z = z, createdOn = time, updatedOn = time),
                PointTolerance(toleranceX, toleranceY, toleranceZ),
            )
        }
        return SubDatumRow(row, name, item, problems)
    }

    private companion object {
        val WHITESPACE = Regex("""\s+""")

        fun unitName(metric: Boolean): String = if (metric) "mm" else "inch"
    }
}

/**
 * The sub-datum CSV export.
 *
 * - Rows: the selected sub-datums, else all shown sub-datums and guides. A guide is a row
 *   with one coordinate and the name "Guide".
 * - `x`: when X is enabled. `y`: when the machine is not a lathe and Y is enabled. `z`: when Z is
 *   enabled. The value must also be present. A lathe X is the stored radius.
 * - Units: the metric default of the profile. Decimals: the readout format (`display_format`).
 * - The metadata block has `superdro` and no device line.
 */
object SubDatumCsvExporter {

    /**
     * @param subDatums The sub-datums that the list shows.
     * @param guides The guides that the list shows.
     * @param selectedIds The selected rows. An empty set exports all shown rows.
     */
    fun export(
        profile: MachineProfile,
        subDatums: List<SubDatum>,
        guides: List<Guide>,
        context: ExportContext,
        selectedIds: Set<String> = emptySet(),
    ): ExportFile {
        val points = if (selectedIds.isEmpty()) subDatums else subDatums.filter { point -> point.id in selectedIds }
        val lines = if (selectedIds.isEmpty()) guides else guides.filter { guide -> guide.id in selectedIds }
        val metric = profile.metricDefault
        val lathe = profile.machineType.isLathe
        val showX = profile.x.enabled
        val showY = !lathe && profile.y.enabled
        val showZ = profile.z.enabled

        fun number(value: Double?, shown: Boolean): String =
            if (shown && value != null) CsvNumbers.formatReadout(value, metric, profile.display.readoutFormat) else ""

        val text = buildString {
            append(CsvMetadata.write(CsvMetadata.KIND_SUB_DATUMS, context, profile.id, profile.name, profile.machineType, metric))
            append(CsvWriter.record(SubDatumColumns.HEADER))
            points.forEach { point ->
                append(
                    CsvWriter.record(
                        listOf(point.name, point.note, point.tags.joinToString(" "), number(point.x, showX), number(point.y, showY), number(point.z, showZ)),
                    ),
                )
            }
            lines.forEach { guide ->
                append(
                    CsvWriter.record(
                        listOf(
                            SubDatumColumns.GUIDE_NAME,
                            "",
                            "",
                            number(guide.valueUm.takeIf { guide.axis == AxisId.X }, showX),
                            number(guide.valueUm.takeIf { guide.axis == AxisId.Y }, showY),
                            number(guide.valueUm.takeIf { guide.axis == AxisId.Z }, showZ),
                        ),
                    ),
                )
            }
        }
        val fileName = ExportFileNames.profileCsv(CsvMetadata.KIND_SUB_DATUMS, profile.name, profile.machineType, context.localTime)
        return ExportFile(fileName, ExportFile.MIME_CSV, text)
    }
}
