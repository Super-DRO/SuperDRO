package org.superdro.core.drawing.tool

import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import org.superdro.core.drawing.DrawingConversion
import org.superdro.core.drawing.DrawingProblem
import org.superdro.core.drawing.DrawingProblemCodes
import org.superdro.core.drawing.WireframeReader
import org.superdro.core.drawing.WireframeWriter
import org.superdro.core.model.ToolGeometry

/** The result of [ToolGeometryCodec.decode]: the geometry, or the problems. */
data class ToolGeometryDecodeResult(val geometry: ToolGeometry?, val problems: List<DrawingProblem>)

/**
 * The text form of the cutter geometry of a lathe tool:
 * `{"v":1,"drawnIn":<factor>,"document":<document text>}`. The document is a wireframe document.
 * The factor is 1.0 (drawing in mm) or 25.4 (drawing in inch).
 */
object ToolGeometryCodec {
    const val VERSION: Int = 1

    /** The factor of a drawing in mm. */
    const val DRAWN_IN_MM: Double = 1.0

    /** The factor of a drawing in inch. */
    const val DRAWN_IN_INCH: Double = 25.4

    /** The text of a geometry that SuperDRO cannot read (string `drawing_tool_geometry_unreadable`). */
    const val UNREADABLE_MESSAGE: String = "This drawing couldn't be read. Try importing it again."

    private val json = Json { isLenient = false }

    /** Gives the text form of [geometry]. The document is the wireframe JSON text of the shapes. */
    fun encode(geometry: ToolGeometry, createdAt: Instant = Clock.System.now()): String {
        val document = DrawingConversion.toWireframe(geometry.layers, geometry.shapes, createdAt)
        return buildJsonObject {
            put("v", VERSION)
            put("drawnIn", geometry.drawnIn)
            put("document", WireframeWriter.write(document))
        }.toString()
    }

    /**
     * Reads the text form [text]. The document can be a JSON string with the wireframe text, a
     * JSON string with Base64 gzip data, or a JSON object. A factor that is not a finite number
     * gives 1.0.
     */
    fun decode(text: String): ToolGeometryDecodeResult {
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return unreadable("the cutter geometry is not a JSON object")
        val factor = (root["drawnIn"] as? JsonPrimitive)?.doubleOrNull?.takeIf { value -> value.isFinite() && value > 0 } ?: DRAWN_IN_MM
        val read = when (val document = root["document"]) {
            is JsonObject -> WireframeReader.readText(document.toString())
            is JsonPrimitive -> {
                val content = document.content
                if (content.trimStart().startsWith("{")) {
                    WireframeReader.readText(content)
                } else {
                    val bytes = try {
                        Base64.decode(content.trim())
                    } catch (_: IllegalArgumentException) {
                        return unreadable("the cutter geometry document is not JSON and not Base64 data")
                    }
                    WireframeReader.read(bytes)
                }
            }
            else -> return unreadable("the cutter geometry has no \"document\"")
        }
        val wireframe = read.document ?: return ToolGeometryDecodeResult(null, read.problems)
        return ToolGeometryDecodeResult(
            ToolGeometry(
                shapes = DrawingConversion.toShapes(wireframe),
                source = null,
                drawnIn = factor,
                layers = DrawingConversion.toLayers(wireframe),
            ),
            emptyList(),
        )
    }

    private fun unreadable(detail: String) =
        ToolGeometryDecodeResult(null, listOf(DrawingProblem(DrawingProblemCodes.WIREFRAME_INVALID, detail)))
}
