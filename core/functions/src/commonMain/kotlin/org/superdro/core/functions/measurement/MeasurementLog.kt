package org.superdro.core.functions.measurement

import kotlin.math.abs
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.superdro.core.model.CollectedPoint
import org.superdro.core.model.LogDisplayFormat
import org.superdro.core.model.LoggedResult
import org.superdro.core.model.MeasurementLogEntry
import org.superdro.core.model.PointSource
import org.superdro.core.model.Units
import org.superdro.core.model.newId

/**
 * The inspection log rules of each workspace.
 *
 * `core:store` keeps the rows `(Id, WorkspaceId, Sequence, Payload, CreatedOn)`. [MeasurementLogPayload]
 * gives the payload text.
 */
object MeasurementLog {

    /**
     * Gives the sequence of a new entry: the largest sequence of the workspace + 1. The first entry
     * of a workspace has the sequence 1.
     */
    fun nextSequence(existingSequences: Iterable<Int>): Int = (existingSequences.maxOrNull() ?: 0) + 1

    /**
     * Makes the log entry of [session], or null when the user cannot log the set
     * ([MeasurementSession.canLog]).
     *
     * - The results are the results with a value, in the order of the function.
     * - The points are the slot points. SuperDRO stores the points and the results in the same
     *   frame and turns neither of them.
     * - The system notes are the union of the point notes.
     * - The note is trimmed. A blank note gives null.
     * - [axisAlignmentArcSec] goes into the entry in degrees.
     *
     * @param title The title of the function in the app language at the log time.
     * @param display The display format at the log time. The log view and the exports use it.
     */
    fun buildEntry(
        session: MeasurementSession,
        title: String,
        workspaceId: String,
        workspaceName: String,
        sequence: Int,
        display: LogDisplayFormat,
        axisAlignmentArcSec: Double,
        createdOn: Instant,
        id: String = newId(),
    ): MeasurementLogEntry? {
        if (!session.canLog) return null
        return MeasurementLogEntry(
            id = id,
            workspaceId = workspaceId,
            sequence = sequence,
            verb = session.verb.wire,
            title = title,
            results = session.evaluate().results.mapNotNull { result -> result.toLogged() },
            points = session.slots.filterNotNull(),
            notes = session.systemNotes(),
            note = session.note.trim().ifEmpty { null },
            workspaceName = workspaceName,
            display = display,
            axisAngle = axisAlignmentArcSec / Units.ARCSEC_PER_DEGREE,
            createdOn = createdOn,
        )
    }
}

/**
 * The JSON payload of a log row. The key order is:
 * `v` (always 1), `verb`, `title`, `units` ("mm" or "in"), `precision`, `workspace`, `axisAngle`,
 * `results`, `points`, `systemNotes` (only when not empty), and `note` (only when not blank).
 *
 * A result is `{"key": wire, "value": v}`: `v` is µm (length, diameter), degrees (angle), or
 * `{"x", "y"}` in µm (position). A point has `x`, `y`, an optional `z`, `source` (`LATCH`,
 * `PROBE`, `SUB_DATUM`, `CAMERA`), `kind` (`SURFACE_POINT`, or `TIP_CENTER` with a tip radius), an
 * optional `tipRadius`, an optional `approach {x, y, z?}`, an optional `sourceId`, an optional
 * `frame {instrumentId?, xOffset, yOffset}` (all sources but `SUB_DATUM`), and optional `notes`.
 * A number with no fraction has no decimal mark, for example `20000`.
 */
object MeasurementLogPayload {

    /** The payload version. */
    const val VERSION: Int = 1

    /** Gives the payload text of [entry]. */
    fun encode(entry: MeasurementLogEntry): String = encodeElement(entry).toString()

    /** Gives the payload object of [entry]. */
    fun encodeElement(entry: MeasurementLogEntry): JsonObject = buildJsonObject {
        put("v", VERSION)
        put("verb", entry.verb)
        put("title", entry.title)
        put("units", MeasurementFormat.unitText(entry.display.metric))
        put("precision", entry.display.precision)
        put("workspace", entry.workspaceName)
        put("axisAngle", number(entry.axisAngle))
        putJsonArray("results") {
            entry.results.forEach { result -> encodeResult(result)?.let { element -> add(element) } }
        }
        putJsonArray("points") { entry.points.forEach { point -> add(encodePoint(point)) } }
        if (entry.notes.isNotEmpty()) putJsonArray("systemNotes") { entry.notes.forEach { note -> add(note) } }
        val note = entry.note
        if (!note.isNullOrBlank()) put("note", note)
    }

    /**
     * Gives the entry of [payload] with the row values [id], [workspaceId], [sequence], and
     * [createdOn], or null when the text is not a payload. The decoder skips unknown keys and
     * results that it cannot read.
     */
    fun decode(
        payload: String,
        id: String = newId(),
        workspaceId: String = "",
        sequence: Int = 0,
        createdOn: Instant = Instant.fromEpochMilliseconds(0),
    ): MeasurementLogEntry? = try {
        val root = Json.parseToJsonElement(payload) as? JsonObject
        root?.let { json -> decodeObject(json, id, workspaceId, sequence, createdOn) }
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun decodeObject(
        root: JsonObject,
        id: String,
        workspaceId: String,
        sequence: Int,
        createdOn: Instant,
    ): MeasurementLogEntry? {
        val verb = root.string("verb") ?: return null
        val units = root.string("units")
        val display = LogDisplayFormat(
            metric = units != "in",
            precision = root.primitive("precision")?.intOrNull ?: LogDisplayFormat().precision,
        )
        val results = (root["results"] as? JsonArray).orEmpty().mapNotNull { element -> decodeResult(element) }
        val points = (root["points"] as? JsonArray).orEmpty().mapNotNull { element -> decodePoint(element) }
        val notes = (root["systemNotes"] as? JsonArray).orEmpty().mapNotNull { element ->
            (element as? JsonPrimitive)?.contentOrNull
        }
        return MeasurementLogEntry(
            id = id,
            workspaceId = workspaceId,
            sequence = sequence,
            verb = verb,
            title = root.string("title") ?: "",
            results = results,
            points = points,
            notes = notes,
            note = root.string("note")?.takeIf { text -> text.isNotBlank() },
            workspaceName = root.string("workspace") ?: "",
            display = display,
            axisAngle = root.primitive("axisAngle")?.doubleOrNull ?: 0.0,
            createdOn = createdOn,
        )
    }

    private fun encodeResult(result: LoggedResult): JsonObject? {
        val value: JsonElement = when {
            result.x != null && result.y != null -> buildJsonObject {
                put("x", number(result.x!!))
                put("y", number(result.y!!))
            }
            result.value != null -> number(result.value!!)
            else -> return null
        }
        return buildJsonObject {
            put("key", result.key)
            put("value", value)
        }
    }

    private fun decodeResult(element: JsonElement): LoggedResult? {
        val json = element as? JsonObject ?: return null
        val key = json.string("key") ?: return null
        val quantity = ResultKey.ofWire(key)?.quantity ?: return null
        val value = json["value"] ?: return null
        return if (quantity == Quantity.Position) {
            val position = value as? JsonObject ?: return null
            val x = position.primitive("x")?.doubleOrNull ?: return null
            val y = position.primitive("y")?.doubleOrNull ?: return null
            LoggedResult(key = key, x = x, y = y)
        } else {
            LoggedResult(key = key, value = (value as? JsonPrimitive)?.doubleOrNull ?: return null)
        }
    }

    private fun encodePoint(point: CollectedPoint): JsonObject = buildJsonObject {
        put("x", number(point.x))
        put("y", number(point.y))
        point.z?.let { z -> put("z", number(z)) }
        put("source", sourceWire(point.source))
        val tipRadius = point.tipRadiusUm?.takeIf { radius -> radius > 0.0 }
        put("kind", if (tipRadius != null) KIND_TIP_CENTER else KIND_SURFACE_POINT)
        tipRadius?.let { radius -> put("tipRadius", number(radius)) }
        val hasApproach = point.approachX != 0 || point.approachY != 0 || (point.approachZ ?: 0) != 0
        if (point.source == PointSource.Probe || hasApproach) {
            putJsonObject("approach") {
                put("x", point.approachX)
                put("y", point.approachY)
                point.approachZ?.let { z -> put("z", z) }
            }
        }
        point.sourceId?.let { sourceId -> put("sourceId", sourceId) }
        if (point.source != PointSource.SubDatum) {
            putJsonObject("frame") {
                point.instrumentId?.let { instrumentId -> put("instrumentId", instrumentId) }
                put("xOffset", number(point.instrumentXOffsetUm))
                put("yOffset", number(point.instrumentYOffsetUm))
            }
        }
        if (point.notes.isNotEmpty()) putJsonArray("notes") { point.notes.forEach { note -> add(note) } }
    }

    private fun decodePoint(element: JsonElement): CollectedPoint? {
        val json = element as? JsonObject ?: return null
        val x = json.primitive("x")?.doubleOrNull ?: return null
        val y = json.primitive("y")?.doubleOrNull ?: return null
        val approach = json["approach"] as? JsonObject
        val frame = json["frame"] as? JsonObject
        return CollectedPoint(
            x = x,
            y = y,
            z = json.primitive("z")?.doubleOrNull,
            source = sourceOf(json.string("source")),
            tipRadiusUm = json.primitive("tipRadius")?.doubleOrNull,
            approachX = approach?.primitive("x")?.intOrNull ?: 0,
            approachY = approach?.primitive("y")?.intOrNull ?: 0,
            approachZ = approach?.primitive("z")?.intOrNull,
            sourceId = json.string("sourceId"),
            instrumentId = frame?.string("instrumentId"),
            instrumentXOffsetUm = frame?.primitive("xOffset")?.doubleOrNull ?: 0.0,
            instrumentYOffsetUm = frame?.primitive("yOffset")?.doubleOrNull ?: 0.0,
            notes = (json["notes"] as? JsonArray).orEmpty().mapNotNull { note -> (note as? JsonPrimitive)?.contentOrNull },
        )
    }

    /** Gives the wire name of [source]: `LATCH`, `PROBE`, `SUB_DATUM`, or `CAMERA`. */
    fun sourceWire(source: PointSource): String = when (source) {
        PointSource.Latch -> "LATCH"
        PointSource.Probe -> "PROBE"
        PointSource.SubDatum -> "SUB_DATUM"
        PointSource.Camera -> "CAMERA"
    }

    private fun sourceOf(wire: String?): PointSource = when (wire) {
        "PROBE" -> PointSource.Probe
        "SUB_DATUM" -> PointSource.SubDatum
        "CAMERA" -> PointSource.Camera
        else -> PointSource.Latch
    }

    /** Gives a JSON number. A finite value with no fraction has no decimal mark. */
    private fun number(value: Double): JsonPrimitive = when {
        !value.isFinite() -> JsonPrimitive(0)
        value == kotlin.math.floor(value) && abs(value) < WHOLE_LIMIT -> JsonPrimitive(value.toLong())
        else -> JsonPrimitive(value)
    }

    private fun JsonObject.primitive(key: String): JsonPrimitive? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }

    private fun JsonObject.string(key: String): String? = primitive(key)?.contentOrNull

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    private const val KIND_SURFACE_POINT = "SURFACE_POINT"
    private const val KIND_TIP_CENTER = "TIP_CENTER"

    /** 2^53: each whole number below this value is exact in a Double. */
    private const val WHOLE_LIMIT = 9_007_199_254_740_992.0
}
