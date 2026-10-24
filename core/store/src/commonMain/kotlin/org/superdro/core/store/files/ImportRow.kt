package org.superdro.core.store.files

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.superdro.core.store.files.csv.CsvRecord
import org.superdro.core.store.files.csv.CsvTable

/**
 * One data row of an import source: a record of a CSV table, or an object of a JSON list. The
 * row readers of the sub-datum import and the tool import read each value as text, so the two
 * sources share one set of row rules.
 */
internal interface ImportRow {
    /** The row number: the line of a CSV file, or the position in a JSON list (1 for the first object). */
    val row: Int

    /** The text of [column], or an empty text when the row does not have the column. */
    fun value(column: String): String
}

/** A record of a CSV table as an [ImportRow]. */
internal class CsvRow(private val table: CsvTable, val record: CsvRecord) : ImportRow {
    override val row: Int get() = record.row

    override fun value(column: String): String = table.value(record, column)
}

/**
 * An object of a JSON list as an [ImportRow]. The keys are the CSV column names, in lower case.
 * The value rules:
 * - A string gives its text.
 * - A number gives its text as the JSON has it, so `1.250` keeps its three decimals.
 * - `true` gives `yes` and `false` gives `no`, for the columns `metric` and `internal`.
 * - An array of primitives gives its items with one space between them, for the column `tags`.
 * - `null` and a missing key give an empty text.
 */
internal class JsonRow(override val row: Int, private val values: Map<String, JsonElement>) : ImportRow {

    constructor(row: Int, source: JsonObject) : this(row, source.mapKeys { (key, _) -> key.trim().lowercase() })

    /** The keys of the object, in lower case. */
    val keys: Set<String> get() = values.keys

    override fun value(column: String): String = text(values[column])

    private companion object {
        fun text(element: JsonElement?): String = when (element) {
            null, JsonNull -> ""
            is JsonPrimitive -> when {
                element.isString -> element.content
                element.content == "true" -> "yes"
                element.content == "false" -> "no"
                else -> element.content
            }
            is JsonArray -> element.joinToString(" ") { item -> text(item) }
            is JsonObject -> element.toString()
        }
    }
}

/** Gives the rows of [objects] with the positions 1, 2, and so on. */
internal fun jsonRows(objects: List<JsonObject>): List<JsonRow> = objects.mapIndexed { index, source -> JsonRow(index + 1, source) }

/** The union of the keys of [rows], in lower case: the header of a JSON source. */
internal fun jsonHeader(rows: List<JsonRow>): List<String> = rows.flatMap(JsonRow::keys).distinct()
