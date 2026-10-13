package org.superdro.core.store.files

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import org.superdro.core.store.files.csv.CsvMetadata
import org.superdro.core.store.files.csv.CsvTable

/** The kinds of the JSON files. The value of the key `touchdro` or `superdro`. */
object JsonKinds {
    const val SETTINGS = "settings"
    const val PROFILE = "profile"
    const val ADAPTER_PROFILE = "adapter-profile"
    const val PROBE_PROFILES = "probe-profiles"
}

/** The header of a JSON import file. */
data class JsonFileHeader(
    val source: FileSource,
    val kind: String,
    val format: Int,
    val root: JsonObject,
)

/** The type of an import file, from its content. */
enum class ImportFileKind {
    ToolCsv,
    SubDatumCsv,

    /** A `.tdset` file of TouchDRO 3.32.00. */
    Settings,

    /** A SuperDRO profile file. */
    Profile,

    /** A `.tdap` file, or a SuperDRO adapter profile file. */
    AdapterProfile,

    /** A `.tdprb` file, or a SuperDRO probe profile file. */
    ProbeProfiles,
    Unknown,
}

/** The result of [ImportFileDetector.detect]. */
data class DetectedFile(val kind: ImportFileKind, val source: FileSource?)

/**
 * Finds the type of an import file by its content, not by its file name: the key `touchdro` or
 * `superdro` of a JSON file, the metadata of a CSV file, else the
 * CSV header.
 */
object ImportFileDetector {

    fun detect(bytes: ByteArray): DetectedFile = when (val decoded = ImportText.decode(bytes)) {
        is ImportText.Decoded.Text -> detect(decoded.text)
        is ImportText.Decoded.Refused -> DetectedFile(ImportFileKind.Unknown, null)
    }

    fun detect(text: String): DetectedFile {
        val clean = ImportText.removeBom(text)
        if (clean.trimStart().startsWith("{")) {
            val root = JsonFiles.parseObject(clean) ?: return DetectedFile(ImportFileKind.Unknown, null)
            val (source, kind) = JsonFiles.sourceAndKind(root) ?: return DetectedFile(ImportFileKind.Unknown, null)
            val fileKind = when (kind) {
                JsonKinds.SETTINGS -> ImportFileKind.Settings
                JsonKinds.PROFILE -> ImportFileKind.Profile
                JsonKinds.ADAPTER_PROFILE -> ImportFileKind.AdapterProfile
                JsonKinds.PROBE_PROFILES -> ImportFileKind.ProbeProfiles
                else -> ImportFileKind.Unknown
            }
            return DetectedFile(fileKind, source)
        }
        return csvKind(clean)
    }

    /** The kind of a CSV text: the metadata kind, else the header names. */
    internal fun csvKind(text: String): DetectedFile {
        val table = (CsvTable.read(text) as? CsvTable.Companion.Read.Table)?.table ?: return DetectedFile(ImportFileKind.Unknown, null)
        val source = table.metadata.source
        when (table.metadata.kind) {
            CsvMetadata.KIND_TOOLS -> return DetectedFile(ImportFileKind.ToolCsv, source)
            CsvMetadata.KIND_SUB_DATUMS -> return DetectedFile(ImportFileKind.SubDatumCsv, source)
        }
        val header = table.header.toSet()
        return when {
            "tags" in header || ("x" in header && "metric" !in header) -> DetectedFile(ImportFileKind.SubDatumCsv, source)
            "metric" in header && "name" in header -> DetectedFile(ImportFileKind.ToolCsv, source)
            else -> DetectedFile(ImportFileKind.Unknown, source)
        }
    }
}

/** Helpers to read the JSON import files. */
object JsonFiles {

    private val PARSER = Json { isLenient = false }

    /** The format of the JSON files: 1. */
    const val FORMAT: Int = 1

    fun parseObject(text: String): JsonObject? = try {
        PARSER.parseToJsonElement(text) as? JsonObject
    } catch (_: Exception) {
        null
    }

    /** The source key (`touchdro` or `superdro`) and its kind, or null. */
    fun sourceAndKind(root: JsonObject): Pair<FileSource, String>? {
        FileSource.entries.forEach { source ->
            val kind = root[source.key]?.let(::text)?.trim()?.lowercase()
            if (!kind.isNullOrEmpty()) return source to kind
        }
        return null
    }

    /** The result of [readHeader]. */
    sealed interface HeaderRead {
        data class Header(val header: JsonFileHeader) : HeaderRead
        data class Refused(val problem: ImportProblem) : HeaderRead
    }

    /**
     * Reads the header of a JSON file of the kind [expectedKind]. The checks: an empty or binary
     * file, a CSV file of the other import, a text that is not JSON, no `touchdro` or `superdro`
     * key, a different kind, and a format above [maxFormat] (absent means 1).
     */
    fun readHeader(text: String, expectedKind: String, maxFormat: Int = FORMAT): HeaderRead {
        val clean = ImportText.removeBom(text)
        ImportText.check(clean)?.let { problem -> return HeaderRead.Refused(problem) }
        val root = if (clean.trimStart().startsWith("{")) parseObject(clean) else null
        if (root == null) {
            val csv = ImportFileDetector.csvKind(clean)
            val csvKind = when (csv.kind) {
                ImportFileKind.ToolCsv -> CsvMetadata.KIND_TOOLS
                ImportFileKind.SubDatumCsv -> CsvMetadata.KIND_SUB_DATUMS
                else -> null
            }
            return HeaderRead.Refused(
                if (csvKind != null) ImportProblem.error(MessageKey.FileWrongKind, csvKind, expectedKind) else ImportProblem.error(MessageKey.FileNotJson),
            )
        }
        val (source, kind) = sourceAndKind(root) ?: return HeaderRead.Refused(ImportProblem.error(MessageKey.FileUnknownKind))
        if (kind != expectedKind) return HeaderRead.Refused(ImportProblem.error(MessageKey.FileWrongKind, kind, expectedKind))
        val formatElement = root["format"]
        val format = if (formatElement == null || formatElement is JsonNull) FORMAT else number(formatElement)?.toInt() ?: FORMAT
        if (format > maxFormat) return HeaderRead.Refused(ImportProblem.error(MessageKey.FileTooNew, format.toString()))
        return HeaderRead.Header(JsonFileHeader(source, kind, format, root))
    }

    private val LINE_BREAKS = Regex("[\r\n]+")

    /** Changes each run of CR or LF to one space and trims the text. */
    fun singleLine(text: String): String = LINE_BREAKS.replace(text, " ").trim()

    /** The text of a primitive, or null for an object, an array, or null. */
    fun text(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    /** A JSON string value, or null. */
    fun string(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** A JSON number, or a text with a number. Null for other values. */
    fun number(element: JsonElement?): Double? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        return if (primitive.isString) primitive.content.trim().toDoubleOrNull() else primitive.doubleOrNull
    }

    /** A whole JSON number, or a text with a whole number. */
    fun long(element: JsonElement?): Long? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        if (!primitive.isString) primitive.longOrNull?.let { return it }
        val value = number(primitive) ?: return null
        return if (value.isFinite() && value == kotlin.math.floor(value)) value.toLong() else null
    }

    /** A JSON boolean, or the texts `true` and `false`. */
    fun boolean(element: JsonElement?): Boolean? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        if (!primitive.isString) {
            primitive.booleanOrNull?.let { return it }
            return when (primitive.intOrNull) {
                1 -> true
                0 -> false
                else -> null
            }
        }
        return when (primitive.content.trim().lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    /** A JSON array, or null. */
    fun array(element: JsonElement?): JsonArray? = element as? JsonArray

    /** A JSON object, or null. */
    fun obj(element: JsonElement?): JsonObject? = element as? JsonObject

    /** A JSON number for an export. A whole value is written with no decimals. */
    fun exportNumber(value: Double): JsonPrimitive =
        if (value.isFinite() && value == kotlin.math.floor(value) && kotlin.math.abs(value) < 1e15) JsonPrimitive(value.toLong()) else JsonPrimitive(value)
}
