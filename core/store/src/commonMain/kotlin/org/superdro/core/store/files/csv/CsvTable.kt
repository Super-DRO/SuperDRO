package org.superdro.core.store.files.csv

import org.superdro.core.store.files.ImportProblem
import org.superdro.core.store.files.ImportText
import org.superdro.core.store.files.MessageKey

/**
 * One data record of a CSV file.
 *
 * @property row The row number: the record index + 2. Row 1 is the header line. The number does
 *   not count the metadata lines and the empty lines.
 */
data class CsvRecord(val row: Int, val values: List<String>)

/**
 * A CSV import file: the metadata block, the header, and the data records.
 *
 * @property header The column names, trimmed and in lowercase.
 */
data class CsvTable(
    val metadata: CsvMetadata,
    val header: List<String>,
    val records: List<CsvRecord>,
) {
    /** The index of [column] in the header, or null. The first column of a name wins. */
    fun indexOf(column: String): Int? = header.indexOf(column).takeIf { index -> index >= 0 }

    fun has(column: String): Boolean = column in header

    /** The value of [column] in [record], or an empty text. */
    fun value(record: CsvRecord, column: String): String {
        val index = indexOf(column) ?: return ""
        return record.values.getOrElse(index) { "" }
    }

    companion object {
        /** The result of [read]: the table, or the problem that stops the import. */
        sealed interface Read {
            data class Table(val table: CsvTable) : Read
            data class Refused(val problem: ImportProblem) : Read
        }

        /**
         * Reads [text]. The byte order mark goes away. The lines at the start that are
         * blank or start with `#` are the metadata block. The first record after them is the
         * header. The header names can be in any case, with spaces around them.
         *
         * The checks: an empty file, a binary file, a format above 2, a file with `;` in place of
         * `,`, and a text that the parser cannot read.
         */
        fun read(text: String): Read {
            val clean = ImportText.removeBom(text)
            ImportText.check(clean)?.let { problem -> return Read.Refused(problem) }

            val lines = ImportText.lines(clean)
            var first = 0
            while (first < lines.size && (lines[first].isBlank() || lines[first].trimStart().startsWith("#"))) first += 1
            val metadata = CsvMetadata.parse(lines.subList(0, first).filter { line -> line.trimStart().startsWith("#") })
            val format = metadata.format
            if (format != null && format > CsvMetadata.FORMAT) {
                return Read.Refused(ImportProblem.error(MessageKey.FileTooNew, format.toString()))
            }
            val firstLine = lines.getOrNull(first)
            if (firstLine != null && ';' in firstLine && ',' !in firstLine) {
                return Read.Refused(ImportProblem.error(MessageKey.FileSemicolon))
            }

            val body = lines.drop(first).joinToString("\n")
            val records = try {
                CsvParser.parse(body)
            } catch (_: CsvFormatException) {
                return Read.Refused(ImportProblem.error(MessageKey.FileNotCsv))
            }
            if (records.isEmpty()) return Read.Refused(ImportProblem.error(MessageKey.FileNotCsv))
            val header = records.first().map { name -> name.trim().lowercase() }
            val data = records.drop(1).mapIndexed { index, values -> CsvRecord(index + 2, values) }
            return Read.Table(CsvTable(metadata, header, data))
        }
    }
}
