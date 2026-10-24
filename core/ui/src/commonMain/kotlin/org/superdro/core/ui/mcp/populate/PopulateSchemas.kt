package org.superdro.core.ui.mcp.populate

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import org.superdro.core.mcp.ObjectSchemaBuilder
import org.superdro.core.mcp.ToolResult
import org.superdro.core.mcp.arraySchema
import org.superdro.core.mcp.numberSchema
import org.superdro.core.mcp.objectSchema
import org.superdro.core.store.files.ImportOperation
import org.superdro.core.store.files.ImportPreview
import org.superdro.core.store.files.ImportProblem
import org.superdro.core.store.files.ImportTexts
import org.superdro.core.store.files.PreviewRow

/**
 * A bad argument or a refused call of a populate tool. The tool wrapper gives [ToolResult.Error]
 * with the message.
 */
class ToolError(message: String) : Exception(message)

/** Throws [ToolError] with [message]. */
fun toolError(message: String): Nothing = throw ToolError(message)

/** The shared schema parts of the populate tools. */
object PopulateSchemas {
    const val MACHINE_DESCRIPTION: String = "The profile ID or the profile name of the machine."
    const val WORKSPACE_DESCRIPTION: String = "The workspace ID or the workspace name."
    const val UNITS_DESCRIPTION: String = "The unit of the lengths in the arguments and the results: mm, inch, or um. " +
        "The default is the metric default of the profile (mm or inch). Angles are always degrees."
    const val PREVIEW_DESCRIPTION: String = "True gives the plan (toImport, skipped, warnings) and writes nothing."
    const val FILE_ID_DESCRIPTION: String = "The fileId of an upload (POST /upload)."

    /** The `operation` values of a list write, as the CSV import. */
    val OPERATIONS: List<String> = ImportOperation.entries.map(ImportOperation::wireName)

    fun ObjectSchemaBuilder.machine() = string("machine", required = true, description = MACHINE_DESCRIPTION)

    fun ObjectSchemaBuilder.workspace() = string("workspace", required = true, description = WORKSPACE_DESCRIPTION)

    fun ObjectSchemaBuilder.units() = enumString("units", McpUnits.ALL, description = UNITS_DESCRIPTION)

    fun ObjectSchemaBuilder.preview() = boolean("preview", description = PREVIEW_DESCRIPTION, default = false)

    fun ObjectSchemaBuilder.fileId() = string("fileId", description = FILE_ID_DESCRIPTION)

    fun ObjectSchemaBuilder.operation(description: String) = enumString("operation", OPERATIONS, description = description)

    /** The schema of an inline wireframe document (`touchdro.wireframe` JSON). */
    fun ObjectSchemaBuilder.document() = property(
        "document",
        objectSchema(additionalProperties = true, description = "An inline wireframe document (schema touchdro.wireframe) instead of fileId."),
    )

    /** The schema of a list of 9 numbers: a 3 × 3 matrix row by row. */
    fun matrixSchema(description: String): JsonObject = arraySchema(numberSchema(), description, minItems = 9, maxItems = 9)
}

/** Reads the arguments of one tool call. A bad value throws [ToolError]. */
class Arguments(val json: JsonObject) {

    fun has(name: String): Boolean = json[name].let { value -> value != null && value !is JsonNull }

    fun string(name: String): String? = when (val value = json[name]) {
        null, JsonNull -> null
        is JsonPrimitive -> if (value.isString) value.content else toolError("The argument \"$name\" must be a text.")
        else -> toolError("The argument \"$name\" must be a text.")
    }

    fun requiredString(name: String): String {
        val value = string(name)?.trim()
        if (value.isNullOrEmpty()) toolError("The argument \"$name\" is required.")
        return value
    }

    fun boolean(name: String, default: Boolean = false): Boolean = when (val value = json[name]) {
        null, JsonNull -> default
        is JsonPrimitive -> value.booleanOrNull ?: toolError("The argument \"$name\" must be true or false.")
        else -> toolError("The argument \"$name\" must be true or false.")
    }

    fun double(name: String): Double? = when (val value = json[name]) {
        null, JsonNull -> null
        is JsonPrimitive -> {
            val number = value.doubleOrNull
            if (number == null || !number.isFinite()) toolError("The argument \"$name\" must be a number.")
            number
        }
        else -> toolError("The argument \"$name\" must be a number.")
    }

    fun int(name: String): Int? = double(name)?.let { number ->
        if (number != kotlin.math.floor(number) || number > Int.MAX_VALUE || number < Int.MIN_VALUE) {
            toolError("The argument \"$name\" must be a whole number.")
        }
        number.toInt()
    }

    fun array(name: String): List<JsonElement>? = when (val value = json[name]) {
        null, JsonNull -> null
        is JsonArray -> value
        else -> toolError("The argument \"$name\" must be a list.")
    }

    fun requiredArray(name: String): List<JsonElement> = array(name) ?: toolError("The argument \"$name\" is required.")

    fun objects(name: String): List<JsonObject>? = array(name)?.mapIndexed { index, element ->
        element as? JsonObject ?: toolError("Item ${index + 1} of \"$name\" must be an object.")
    }

    fun strings(name: String): List<String>? = array(name)?.mapIndexed { index, element ->
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: toolError("Item ${index + 1} of \"$name\" must be a text.")
    }

    fun doubles(name: String): List<Double>? = array(name)?.mapIndexed { index, element ->
        (element as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() } ?: toolError("Item ${index + 1} of \"$name\" must be a number.")
    }

    fun obj(name: String): JsonObject? = when (val value = json[name]) {
        null, JsonNull -> null
        is JsonObject -> value
        else -> toolError("The argument \"$name\" must be an object.")
    }

    /** The `units` argument, or [default]. */
    fun units(default: String): String {
        val units = string("units") ?: return default
        if (!McpUnits.isValid(units)) toolError("The units must be mm, inch, or um, not \"$units\".")
        return units
    }

    /** The `operation` argument, or [default]. */
    fun operation(default: ImportOperation): ImportOperation {
        val text = string("operation") ?: return default
        return ImportOperation.entries.firstOrNull { operation -> operation.wireName == text }
            ?: toolError("The operation must be one of ${PopulateSchemas.OPERATIONS.joinToString()}, not \"$text\".")
    }

    val preview: Boolean get() = boolean("preview")
}

/** The JSON of the preview and the results. */
object PopulateJson {

    /** The text of [problem] in English. */
    fun problemText(problem: ImportProblem): String = problem.describe(ImportTexts.English)

    fun problem(problem: ImportProblem): JsonObject = buildJsonObject {
        put("severity", problem.severity.name.lowercase())
        put("message", problemText(problem))
        problem.row?.let { row -> put("row", row) }
    }

    fun problems(problems: List<ImportProblem>): JsonArray = JsonArray(problems.map(::problem))

    /**
     * The three lists of an import preview: `toImport`, `skipped`, and `warnings`. [value] gives the
     * extra fields of a row with a value.
     */
    fun <T> preview(preview: ImportPreview<T>, value: (T) -> JsonObject): JsonObject = buildJsonObject {
        put("toImport", rows(preview.toImport, value))
        put("skipped", rows(preview.skipped, value))
        put("warnings", problems(preview.warnings))
    }

    private fun <T> rows(rows: List<PreviewRow<T>>, value: (T) -> JsonObject): JsonArray = buildJsonArray {
        rows.forEach { row ->
            add(
                buildJsonObject {
                    put("row", row.row)
                    put("label", row.label)
                    put("action", row.action.name)
                    row.value?.let { item -> value(item).forEach { (key, element) -> put(key, element) } }
                    if (row.problems.isNotEmpty()) put("problems", problems(row.problems))
                },
            )
        }
    }

    /** A plain row list for the previews of the tools that have no import preview. */
    fun simpleRows(rows: List<Pair<String, String>>): JsonArray = buildJsonArray {
        rows.forEachIndexed { index, (label, action) ->
            add(buildJsonObject { put("row", index + 1); put("label", label); put("action", action) })
        }
    }

    /** The preview object of a simple write: [toImport] and [skipped] are label and action pairs. */
    fun simplePreview(toImport: List<Pair<String, String>>, skipped: List<Pair<String, String>> = emptyList(), warnings: List<String> = emptyList()): JsonObject =
        buildJsonObject {
            put("toImport", simpleRows(toImport))
            put("skipped", simpleRows(skipped))
            put("warnings", JsonArray(warnings.map { text -> buildJsonObject { put("severity", "warning"); put("message", text) } }))
        }

    /** A JSON number of [value], or null. */
    fun number(value: Double?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)

    /** The text "<count> <singular>" or "<count> <plural>". */
    fun count(count: Int, singular: String, plural: String = singular + "s"): String = "$count ${if (count == 1) singular else plural}"
}
