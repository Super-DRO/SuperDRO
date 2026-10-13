package org.superdro.core.store.files

/** The level of an [ImportProblem]. A row with an [Error] does not import. A file-level [Error] stops the import. */
enum class Severity { Warning, Error }

/**
 * One problem of an import.
 *
 * @property row The row number of a CSV file (row 1 is the header line), or null for a problem
 *   of the full file.
 */
data class ImportProblem(
    val severity: Severity,
    val message: ImportMessage,
    val row: Int? = null,
) {
    /** The text "Row <n>: <message>", or the message alone when [row] is null. */
    fun describe(texts: ImportTexts = ImportTexts.English): String {
        val text = texts.text(message)
        return if (row == null) text else "${texts.text(ImportMessage(MessageKey.Row, row))}: $text"
    }

    companion object {
        fun warning(key: MessageKey, vararg args: Any, row: Int? = null): ImportProblem =
            ImportProblem(Severity.Warning, ImportMessage(key, args.toList()), row)

        fun error(key: MessageKey, vararg args: Any, row: Int? = null): ImportProblem =
            ImportProblem(Severity.Error, ImportMessage(key, args.toList()), row)
    }
}

/** True when one of the problems is an [Severity.Error]. */
fun List<ImportProblem>.hasError(): Boolean = any { problem -> problem.severity == Severity.Error }

/** The action of one row of an import preview. */
enum class RowAction {
    /** The row adds a new record. */
    Add,

    /** The row changes or replaces a record. */
    Update,

    /** The row has the same value as the record. */
    Unchanged,

    /** The row is equal to a record that is already there. It does not import. */
    AlreadyHere,

    /** The record has no row in the file. It does not change. */
    NotInFile,

    /** The row does not import. */
    Skip,
}

/**
 * One row of an import preview.
 *
 * @property row The row number of a CSV file, or the position in the JSON list (1 for the first item).
 * @property label A short text that names the row, for example a setting key or a tool name.
 * @property value The record that the row gives, or null when the row does not import.
 */
data class PreviewRow<out T>(
    val row: Int,
    val label: String,
    val action: RowAction,
    val value: T?,
    val problems: List<ImportProblem> = emptyList(),
)

/**
 * The preview of an import with its three lists: the rows to import, the rows that the import
 * skips, and the warnings. An import shows it before it writes.
 */
data class ImportPreview<out T>(
    val toImport: List<PreviewRow<T>>,
    val skipped: List<PreviewRow<T>>,
    val warnings: List<ImportProblem>,
) {
    companion object {
        /**
         * Puts each row into one list. A row with the action [RowAction.Skip],
         * [RowAction.AlreadyHere], or [RowAction.NotInFile] goes to [skipped]. The warnings are the
         * file warnings and each row warning.
         */
        fun <T> of(rows: List<PreviewRow<T>>, fileProblems: List<ImportProblem>): ImportPreview<T> {
            val skippedActions = setOf(RowAction.Skip, RowAction.AlreadyHere, RowAction.NotInFile)
            val warnings = fileProblems.filter { problem -> problem.severity == Severity.Warning } +
                rows.flatMap { row -> row.problems.filter { problem -> problem.severity == Severity.Warning } }
            return ImportPreview(
                toImport = rows.filter { row -> row.action !in skippedActions },
                skipped = rows.filter { row -> row.action in skippedActions },
                warnings = warnings,
            )
        }
    }
}

/**
 * The import operations of the CSV files and the probe profile files. [wireName] is the name of the
 * operation as text.
 */
enum class ImportOperation(val wireName: String) {
    /** A row that matches a record updates it. The other rows add records. */
    UpdateMatching("UPDATE_MATCHING"),

    /** Each row adds a record. */
    AddAsNew("ADD_AS_NEW"),

    /** A row that matches a record does not import. The other rows add records. */
    AddNewOnly("ADD_NEW_ONLY"),

    /** A row that matches a record updates its name and note. The other rows add records. */
    MergeMatching("MERGE_MATCHING"),

    /** The import deletes all records first. Then each row adds a record. */
    ReplaceAll("REPLACE_ALL"),
}

/** The application that wrote an import file. The key `touchdro` or `superdro` tells it. */
enum class FileSource(val key: String) {
    TouchDro("touchdro"),
    SuperDro("superdro"),
}
