package org.superdro.core.drawing

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.superdro.core.drawing.codec.Gzip
import org.superdro.core.drawing.codec.GzipException
import org.superdro.core.drawing.codec.GzipSizeException

/**
 * The result of [WireframeReader.read].
 *
 * @property result The envelope. A bare wireframe document gives an envelope with the status
 *   [ConvertStatus.Ready]. Null when [problems] is not empty.
 * @property problems The problem list of the "Drawing Not Imported" dialog.
 */
data class WireframeReadResult(
    val result: ConvertResult?,
    val problems: List<DrawingProblem>,
) {
    /** The wireframe document, or null. */
    val document: WireframeDocument? get() = result?.wireframe

    /** The warnings of the envelope and of the reader. */
    val warnings: List<DrawingProblem> get() = result?.warnings.orEmpty()
}

/**
 * The reader of the wireframe documents: `touchdro.wireframe`
 * version 3.x and the envelope `touchdro.convert.result`, as plain JSON or gzip data.
 */
object WireframeReader {
    /** The size limit of a `touchdro.wireframe` file: 2 MiB. */
    const val MAX_WIREFRAME_BYTES: Int = 2_097_152

    /** The size limit of a `touchdro.convert.result` file and of the decompressed gzip data: 2.0625 MiB. */
    const val MAX_RESULT_BYTES: Int = 2_162_688

    const val MAX_PROBLEMS: Int = 50
    const val MAX_WARNINGS: Int = 50
    const val MAX_LAYERS: Int = 1000
    const val MAX_LAYER_NAME: Int = 200
    const val MAX_ENTITIES: Int = 10_000
    const val MAX_POLYLINE_POINTS: Int = 10_000
    const val MAX_BEZIER_SEGMENTS: Int = 10_000
    const val MAX_POINT_NAME: Int = 60

    /** The text of the size problem. */
    const val TOO_LARGE_MESSAGE: String = "file exceeds the 2 MB limit"

    /** The last problem when the reader stops after [MAX_PROBLEMS] problems. */
    const val STOPPED_MESSAGE: String = "stopped after 50 problems; the rest of the file was not checked"

    private val json = Json { isLenient = false }

    /** Reads the file content [bytes]: gzip data (magic `1F 8B`) or UTF-8 JSON text. */
    fun read(bytes: ByteArray): WireframeReadResult {
        val data = if (DrawingTypeTest.isGzip(bytes)) {
            try {
                Gzip.decompress(bytes, MAX_RESULT_BYTES)
            } catch (_: GzipSizeException) {
                return failed(TOO_LARGE_MESSAGE)
            } catch (error: GzipException) {
                return failed("not valid gzip data: ${error.message}")
            }
        } else {
            bytes
        }
        if (data.size > MAX_RESULT_BYTES) return failed(TOO_LARGE_MESSAGE)
        val start = if (data.size >= 3 && data[0] == 0xEF.toByte() && data[1] == 0xBB.toByte() && data[2] == 0xBF.toByte()) 3 else 0
        return readText(data.decodeToString(start, data.size), data.size - start)
    }

    /** Reads the JSON [text]. [byteCount] is the size of the text for the size limits. */
    fun readText(text: String, byteCount: Int = text.encodeToByteArray().size): WireframeReadResult {
        if (byteCount > MAX_RESULT_BYTES) return failed(TOO_LARGE_MESSAGE)
        val root = try {
            json.parseToJsonElement(text)
        } catch (error: SerializationException) {
            return failed("not valid JSON: ${error.message?.lineSequence()?.firstOrNull().orEmpty()}")
        } catch (error: IllegalArgumentException) {
            return failed("not valid JSON: ${error.message?.lineSequence()?.firstOrNull().orEmpty()}")
        }
        val top = root as? JsonObject ?: return failed("not a wireframe document")
        return when (top.string("schema")) {
            ConvertResult.SCHEMA -> readEnvelope(top)
            WireframeDocument.SCHEMA -> {
                if (byteCount > MAX_WIREFRAME_BYTES) return failed(TOO_LARGE_MESSAGE)
                val reader = DocumentReader()
                val document = reader.read(top)
                if (document == null || reader.problems.isNotEmpty()) {
                    WireframeReadResult(null, reader.problems)
                } else {
                    WireframeReadResult(ConvertResult(ConvertStatus.Ready, document, reader.warnings), emptyList())
                }
            }
            else -> failed("not a wireframe document")
        }
    }

    private fun readEnvelope(top: JsonObject): WireframeReadResult {
        val warnings = (top["warnings"] as? JsonArray).orEmpty().take(MAX_WARNINGS).mapNotNull { item ->
            val entry = item as? JsonObject ?: return@mapNotNull null
            DrawingProblem(
                code = entry.string("code") ?: DrawingProblemCodes.WIREFRAME_WARNING,
                message = entry.string("message").orEmpty(),
                severity = ProblemSeverity.Warning,
            )
        }
        val errors = (top["errors"] as? JsonArray).orEmpty().take(MAX_PROBLEMS).mapNotNull { item ->
            val entry = item as? JsonObject ?: return@mapNotNull null
            DrawingProblem(entry.string("code") ?: DrawingProblemCodes.CONVERSION_FAILED, entry.string("message").orEmpty())
        }
        val format = top.string("format") ?: "dxf"
        val build = top.string("converterBuild") ?: ""
        return when (top.string("status")) {
            "ready" -> {
                val wireframe = top["wireframe"] as? JsonObject ?: return failed("missing \"wireframe\"")
                if (wireframe.string("schema") != WireframeDocument.SCHEMA) return failed("not a wireframe document")
                val reader = DocumentReader()
                val document = reader.read(wireframe)
                if (document == null || reader.problems.isNotEmpty()) {
                    WireframeReadResult(null, reader.problems)
                } else {
                    WireframeReadResult(
                        ConvertResult(ConvertStatus.Ready, document, (warnings + reader.warnings).take(MAX_WARNINGS), emptyList(), format, build),
                        emptyList(),
                    )
                }
            }
            "failed" -> WireframeReadResult(
                null,
                errors.ifEmpty { listOf(DrawingProblem(DrawingProblemCodes.CONVERSION_FAILED, "the converter result has the status \"failed\"")) },
            )
            else -> failed("the converter result has no valid \"status\"")
        }
    }

    private fun failed(message: String): WireframeReadResult =
        WireframeReadResult(null, listOf(DrawingProblem(DrawingProblemCodes.WIREFRAME_INVALID, message)))

    /** The reader of one wireframe document. It stops after [MAX_PROBLEMS] problems. */
    private class DocumentReader {
        val problems = ArrayList<DrawingProblem>()
        val warnings = ArrayList<DrawingProblem>()

        private class Stop : RuntimeException()

        private fun problem(message: String) {
            if (problems.size >= MAX_PROBLEMS - 1) {
                problems += DrawingProblem(DrawingProblemCodes.TOO_MANY_PROBLEMS, STOPPED_MESSAGE)
                throw Stop()
            }
            problems += DrawingProblem(DrawingProblemCodes.WIREFRAME_INVALID, message)
        }

        private fun warning(message: String) {
            if (warnings.size < MAX_WARNINGS) warnings += DrawingProblem(DrawingProblemCodes.WIREFRAME_WARNING, message, ProblemSeverity.Warning)
        }

        fun read(document: JsonObject): WireframeDocument? = try {
            readDocument(document)
        } catch (_: Stop) {
            null
        }

        private fun readDocument(document: JsonObject): WireframeDocument? {
            val version = document.string("v")
            when {
                version == null -> problem("missing \"v\"")
                !version.startsWith("3.") -> problem("unsupported version \"$version\"")
                version != WireframeDocument.VERSION -> warning("version $version; SuperDRO reads it as version 3.0")
            }
            val axes = (document["axes"] as? JsonArray)?.map { item -> (item as? JsonPrimitive)?.content.orEmpty() }
                ?: WireframeDocument.DEFAULT_AXES
            if (axes != WireframeDocument.DEFAULT_AXES) warning("axes ${axes.joinToString("/")} shown on the X/Y plane")
            val extents = (document["ext"] as? JsonObject)?.let { ext ->
                val min = point(ext["min"])
                val max = point(ext["max"])
                if (min != null && max != null) WireframeExtents(min, max) else null
            }
            val layersJson = document["layers"] as? JsonArray
            if (layersJson == null) problem("missing \"layers\"")
            val entitiesJson = document["entities"] as? JsonArray
            if (entitiesJson == null) problem("missing \"entities\"")
            if (layersJson == null || entitiesJson == null) return null
            if (layersJson.size > MAX_LAYERS) {
                problem("${layersJson.size} layers exceeds the $MAX_LAYERS cap")
                return null
            }
            val layers = layersJson.mapIndexed { index, item -> layer(index, item) }
            if (entitiesJson.size > MAX_ENTITIES) {
                problem("${entitiesJson.size} entities exceeds the $MAX_ENTITIES cap")
                return null
            }
            val ids = HashSet<String>()
            val entities = ArrayList<WireframeEntity>()
            var segments = 0L
            entitiesJson.forEachIndexed { index, item ->
                val entity = entity(index, item, layers.size, ids)
                if (entity != null) {
                    entities += entity
                    if (entity is WireframeBezier) segments += entity.segmentCount
                }
            }
            if (segments > MAX_BEZIER_SEGMENTS) problem("$segments Bézier segments exceeds the $MAX_BEZIER_SEGMENTS cap")
            if (problems.isNotEmpty()) return null
            return WireframeDocument(
                version = version ?: WireframeDocument.VERSION,
                createdAt = document.string("createdAt").orEmpty(),
                axes = axes,
                extents = extents,
                layers = layers.map { layer -> layer ?: WireframeLayer("") },
                entities = entities,
            )
        }

        private fun layer(index: Int, item: JsonElement): WireframeLayer? {
            val layer = item as? JsonObject
            if (layer == null) {
                problem("layer $index: not an object")
                return null
            }
            val name = layer.string("n")
            if (name.isNullOrEmpty()) {
                problem("layer $index: empty name")
                return null
            }
            val visible = when (val value = layer["v"]) {
                null, JsonNull -> true
                else -> flag(value) ?: run {
                    problem("layer $index: \"v\" must be 0 or 1")
                    true
                }
            }
            val style = style(layer["st"], "layer $index") ?: WireframeDocument.DEFAULT_STYLE
            return WireframeLayer(name.take(MAX_LAYER_NAME), visible, style)
        }

        private fun style(value: JsonElement?, where: String): Int? {
            if (value == null || value is JsonNull) return null
            val style = (value as? JsonPrimitive)?.longOrNull
            if (style == null || style !in 0L..5L) {
                problem("$where: style must be 0 to 5, got $value")
                return null
            }
            return style.toInt()
        }

        private fun entity(index: Int, item: JsonElement, layerCount: Int, ids: MutableSet<String>): WireframeEntity? {
            val entity = item as? JsonObject
            if (entity == null) {
                problem("entity $index: not an object")
                return null
            }
            val where = "entity $index"
            val layerValue = entity["l"]
            val layer = (layerValue as? JsonPrimitive)?.longOrNull
            if (layerValue == null) {
                problem("$where: missing \"l\"")
                return null
            }
            if (layer == null || layer < 0 || layer >= layerCount) {
                problem("$where: layer index $layerValue is out of range")
                return null
            }
            val id = (entity["id"] as? JsonPrimitive)?.takeIf { value -> value !is JsonNull }?.content
            if (id != null && !ids.add(id)) {
                problem("$where: duplicate id \"$id\"")
                return null
            }
            val style = style(entity["st"], where)
            val group = (entity["g"] as? JsonPrimitive)?.takeIf { value -> value !is JsonNull }?.content
            val l = layer.toInt()
            return when (val type = entity.string("t")) {
                "L" -> {
                    val points = points(entity["p"])
                    if (points == null || points.size != 2) {
                        problem("$where: a line needs \"p\" with 2 points")
                        null
                    } else {
                        WireframeLine(points[0], points[1], l, id, style, group)
                    }
                }
                "PT" -> {
                    val point = point(entity["p"])
                    if (point == null) {
                        problem("$where: a point needs \"p\" = [x, y]")
                        null
                    } else {
                        WireframePoint(point, entity.string("name")?.take(MAX_POINT_NAME), l, id, style, group)
                    }
                }
                "C" -> {
                    val center = point(entity["c"])
                    val radius = integer(entity["r"])
                    when {
                        center == null -> {
                            problem("$where: a circle needs \"c\" = [x, y]")
                            null
                        }
                        radius == null || radius <= 0 -> {
                            problem("$where: circle radius must be > 0, got ${radius ?: entity["r"]} nm")
                            null
                        }
                        else -> WireframeCircle(center, radius, l, id, style, group)
                    }
                }
                "A" -> {
                    val center = point(entity["c"])
                    val radius = integer(entity["r"])
                    val angles = (entity["a"] as? JsonArray)?.map { value -> (value as? JsonPrimitive)?.doubleOrNull }
                    when {
                        center == null -> {
                            problem("$where: an arc needs \"c\" = [x, y]")
                            null
                        }
                        radius == null || radius <= 0 -> {
                            problem("$where: arc radius must be > 0, got ${radius ?: entity["r"]} nm")
                            null
                        }
                        angles == null || angles.size != 2 || angles.any { value -> value == null || !value.isFinite() } -> {
                            problem("$where: an arc needs \"a\" = [start, sweep]")
                            null
                        }
                        abs(angles[1]!!) <= ANGLE_EPSILON || abs(angles[1]!!) >= 2 * PI - ANGLE_EPSILON -> {
                            problem("$where: arc sweep must be between 1e-6 and 2π − 1e-6 rad, got ${angles[1]}")
                            null
                        }
                        else -> WireframeArc(center, radius, angles[0]!!, angles[1]!!, l, id, style, group)
                    }
                }
                "P" -> {
                    val points = points(entity["pts"])
                    if (points == null || points.size < 2 || points.size > MAX_POLYLINE_POINTS) {
                        problem("$where: polyline needs 2 to $MAX_POLYLINE_POINTS points, got ${points?.size ?: 0}")
                        return null
                    }
                    val bulges = LinkedHashMap<Int, Double>()
                    val bulgeJson = entity["bul"]
                    if (bulgeJson != null && bulgeJson !is JsonNull) {
                        val list = bulgeJson as? JsonArray
                        if (list == null) {
                            problem("$where: \"bul\" must be an array")
                            return null
                        }
                        for (pair in list) {
                            val values = (pair as? JsonArray)?.map { value -> (value as? JsonPrimitive)?.doubleOrNull }
                            val segment = values?.getOrNull(0)
                            val bulge = values?.getOrNull(1)
                            if (values == null || values.size != 2 || segment == null || bulge == null || !bulge.isFinite() ||
                                segment != floor(segment) || segment < 0 || segment >= points.size
                            ) {
                                problem("$where: a bulge must be [index, bulge] with an index of a segment")
                                return null
                            }
                            if (bulge != 0.0) bulges[segment.toInt()] = bulge
                        }
                    }
                    WireframePolyline(points, closed(entity["cl"]), bulges, l, id, style, group)
                }
                "B" -> {
                    val points = points(entity["p"])
                    if (points == null || points.size < 4 || (points.size - 1) % 3 != 0) {
                        problem("$where: Bézier chain needs 1 + 3·N points, got ${points?.size ?: 0}")
                        null
                    } else {
                        WireframeBezier(points, closed(entity["cl"]), l, id, style, group)
                    }
                }
                else -> {
                    problem("$where: unknown type \"${type.orEmpty()}\"")
                    null
                }
            }
        }

        private fun closed(value: JsonElement?): Boolean = value != null && value !is JsonNull && flag(value) == true

        private fun flag(value: JsonElement): Boolean? {
            val primitive = value as? JsonPrimitive ?: return null
            primitive.booleanOrNull?.let { flag -> return flag }
            return when (primitive.longOrNull) {
                0L -> false
                1L -> true
                else -> null
            }
        }
    }

    private const val ANGLE_EPSILON = 1e-6

    /** A coordinate: an integer, or a number with no fraction. */
    private fun integer(value: JsonElement?): Long? {
        val primitive = value as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        primitive.longOrNull?.let { number -> return number }
        val real = primitive.doubleOrNull ?: return null
        if (!real.isFinite() || abs(real) >= 9.2e18) return null
        return floor(real + 0.5).toLong()
    }

    private fun point(value: JsonElement?): NmPoint? {
        val array = value as? JsonArray ?: return null
        if (array.size < 2) return null
        val x = integer(array[0]) ?: return null
        val y = integer(array[1]) ?: return null
        return NmPoint(x, y)
    }

    private fun points(value: JsonElement?): List<NmPoint>? {
        val array = value as? JsonArray ?: return null
        val result = ArrayList<NmPoint>(array.size)
        for (item in array) result += point(item) ?: return null
        return result
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { value -> value.isString }?.content
}

/**
 * The writer of the wireframe documents. It writes the key order of the format:
 * `schema`, `v`, `createdAt`, `axes`, `ext`, `layers`, `entities`.
 */
object WireframeWriter {

    /** Gives the JSON text of [document] (schema `touchdro.wireframe`). */
    fun write(document: WireframeDocument): String = documentJson(document).toString()

    /** Gives the JSON text of the envelope [result] (schema `touchdro.convert.result`). */
    fun writeResult(result: ConvertResult): String = buildJsonObject {
        put("schema", ConvertResult.SCHEMA)
        put("v", ConvertResult.ENVELOPE_VERSION)
        put("status", if (result.status == ConvertStatus.Ready) "ready" else "failed")
        put("format", result.format)
        put("converterBuild", result.converterBuild)
        put("errors", buildJsonArray {
            result.errors.forEach { error ->
                add(buildJsonObject {
                    put("code", error.code)
                    put("message", error.message)
                })
            }
        })
        put("warnings", buildJsonArray {
            result.warnings.forEach { warning ->
                add(buildJsonObject {
                    put("code", warning.code)
                    put("severity", "warning")
                    put("message", warning.message)
                })
            }
        })
        result.wireframe?.let { document -> put("wireframe", documentJson(document)) }
    }.toString()

    private fun documentJson(document: WireframeDocument): JsonObject = buildJsonObject {
        put("schema", WireframeDocument.SCHEMA)
        put("v", document.version)
        put("createdAt", document.createdAt)
        put("axes", buildJsonArray { document.axes.forEach { axis -> add(JsonPrimitive(axis)) } })
        val extents = document.extents ?: WireframeExtents(NmPoint(0, 0), NmPoint(0, 0))
        put("ext", buildJsonObject {
            put("min", pointJson(extents.min))
            put("max", pointJson(extents.max))
        })
        put("layers", buildJsonArray {
            document.layers.forEach { layer ->
                add(buildJsonObject {
                    put("n", layer.name)
                    put("v", if (layer.visible) 1 else 0)
                    put("st", layer.style)
                })
            }
        })
        put("entities", buildJsonArray { document.entities.forEach { entity -> add(entityJson(entity)) } })
    }

    private fun entityJson(entity: WireframeEntity): JsonObject = buildJsonObject {
        put("t", entity.typeKey)
        put("l", entity.layer)
        entity.id?.let { id -> put("id", id) }
        entity.style?.let { style -> put("st", style) }
        entity.group?.let { group -> put("g", group) }
        when (entity) {
            is WireframeLine -> put("p", buildJsonArray {
                add(pointJson(entity.start))
                add(pointJson(entity.end))
            })
            is WireframePoint -> {
                put("p", pointJson(entity.point))
                entity.name?.let { name -> put("name", name) }
            }
            is WireframeCircle -> {
                put("c", pointJson(entity.center))
                put("r", entity.radius)
            }
            is WireframeArc -> {
                put("c", pointJson(entity.center))
                put("r", entity.radius)
                put("a", buildJsonArray {
                    add(JsonPrimitive(entity.start))
                    add(JsonPrimitive(entity.sweep))
                })
            }
            is WireframePolyline -> {
                put("pts", buildJsonArray { entity.points.forEach { point -> add(pointJson(point)) } })
                put("cl", if (entity.closed) 1 else 0)
                if (entity.bulges.isNotEmpty()) {
                    put("bul", buildJsonArray {
                        entity.bulges.entries.sortedBy { entry -> entry.key }.forEach { (index, bulge) ->
                            add(buildJsonArray {
                                add(JsonPrimitive(index))
                                add(JsonPrimitive(bulge))
                            })
                        }
                    })
                }
            }
            is WireframeBezier -> {
                put("p", buildJsonArray { entity.points.forEach { point -> add(pointJson(point)) } })
                put("cl", if (entity.closed) 1 else 0)
            }
        }
    }

    private fun pointJson(point: NmPoint): JsonArray = buildJsonArray {
        add(JsonPrimitive(point.x))
        add(JsonPrimitive(point.y))
    }
}
