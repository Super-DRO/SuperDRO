package org.superdro.core.store.files.csv

/** A CSV text that the parser cannot read, for example a quote with no end. */
class CsvFormatException(message: String) : Exception(message)

/**
 * The CSV parser of the import files.
 *
 * - The delimiter is `,`. The quote is `"`. A `""` in a quoted value is one `"`.
 * - A record ends with CR LF, LF, or CR. A quoted value can contain a line end.
 * - The parser ignores an empty line.
 */
object CsvParser {

    private const val DELIMITER = ','
    private const val QUOTE = '"'

    /** Gives the records of [text]. Throws [CsvFormatException] for a quoted value with no end. */
    fun parse(text: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        var fields = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var fieldWasQuoted = false
        var lineHasContent = false
        var index = 0

        fun endField() {
            fields.add(field.toString())
            field.clear()
            fieldWasQuoted = false
        }

        fun endRecord() {
            endField()
            if (lineHasContent) records.add(fields)
            fields = mutableListOf()
            lineHasContent = false
        }

        while (index < text.length) {
            val character = text[index]
            if (quoted) {
                if (character == QUOTE) {
                    if (index + 1 < text.length && text[index + 1] == QUOTE) {
                        field.append(QUOTE)
                        index += 1
                    } else {
                        quoted = false
                    }
                } else {
                    field.append(character)
                }
                index += 1
                continue
            }
            when (character) {
                QUOTE -> {
                    lineHasContent = true
                    if (field.isEmpty() && !fieldWasQuoted) {
                        quoted = true
                        fieldWasQuoted = true
                    } else {
                        field.append(character)
                    }
                }
                DELIMITER -> {
                    lineHasContent = true
                    endField()
                }
                '\r' -> {
                    endRecord()
                    if (index + 1 < text.length && text[index + 1] == '\n') index += 1
                }
                '\n' -> endRecord()
                else -> {
                    lineHasContent = true
                    field.append(character)
                }
            }
            index += 1
        }
        if (quoted) throw CsvFormatException("A quoted value has no end.")
        endRecord()
        return records
    }
}

/**
 * The CSV writer of the export files: delimiter `,`, quote `"`, record end CR LF.
 *
 * A value goes in quotes when it has a `,`, a `"`, a CR, or an LF, when it starts or ends with a
 * space, or when it starts with `#`. A value with `#` at the start of a line would look like a
 * metadata line to an import.
 */
object CsvWriter {

    const val RECORD_END: String = "\r\n"

    /** Gives one record with its record end. */
    fun record(values: List<String>, quoteAll: Boolean = false): String =
        values.joinToString(",") { value -> if (quoteAll || needsQuotes(value)) quote(value) else value } + RECORD_END

    /** Gives [value] in quotes, with each `"` written two times. */
    fun quote(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""

    internal fun needsQuotes(value: String): Boolean =
        value.any { character -> character == ',' || character == '"' || character == '\r' || character == '\n' } ||
            value.startsWith(' ') || value.endsWith(' ') || value.startsWith('#')
}
