package org.superdro.core.ui.mcp.populate

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.superdro.core.drawing.DrawingConversion
import org.superdro.core.drawing.DrawingImportResult
import org.superdro.core.drawing.DrawingImporter
import org.superdro.core.drawing.DrawingProblem
import org.superdro.core.drawing.DxfLimits
import org.superdro.core.drawing.WireframeDocument
import org.superdro.core.drawing.WireframeExtents
import org.superdro.core.drawing.WireframeWriter
import org.superdro.core.drawing.dxf.DxfImportSettings
import org.superdro.core.mcp.McpTool
import org.superdro.core.mcp.ToolResult
import org.superdro.core.mcp.objectSchema
import org.superdro.core.model.Drawing
import org.superdro.core.model.DrawingFormat
import org.superdro.core.model.DrawingSource
import org.superdro.core.model.Units
import org.superdro.core.ui.mcp.populate.PopulateSchemas.document
import org.superdro.core.ui.mcp.populate.PopulateSchemas.fileId
import org.superdro.core.ui.mcp.populate.PopulateSchemas.machine
import org.superdro.core.ui.mcp.populate.PopulateSchemas.preview
import org.superdro.core.ui.mcp.populate.PopulateSchemas.units
import org.superdro.core.ui.mcp.populate.PopulateSchemas.workspace

/** A drawing source of a tool call: an upload or an inline wireframe document. */
internal class DrawingBytes(val bytes: ByteArray, val fileName: String)

/** The bytes of the upload [fileId]. An upload that is not there, or expired, throws [ToolError]. */
internal suspend fun PopulateDeps.uploadBytes(fileId: String): ByteArray =
    files.upload(fileId.trim())?.bytes ?: toolError("The upload \"$fileId\" is not there or expired. Upload the file again.")

/**
 * The drawing bytes of the call: the upload `fileId`, or the inline `document`. The call must give
 * one of the two. `fileName` names the source in the stored drawing.
 */
internal suspend fun PopulateDeps.drawingBytes(arguments: Arguments): DrawingBytes {
    val fileId = arguments.string("fileId")?.trim()?.ifEmpty { null }
    val document = arguments.obj("document")
    if ((fileId == null) == (document == null)) toolError("Give either fileId or document, not both and not none.")
    val fileName = arguments.string("fileName")?.trim()?.ifEmpty { null }
    return if (fileId != null) {
        DrawingBytes(uploadBytes(fileId), fileName ?: "upload-$fileId")
    } else {
        DrawingBytes(document.toString().encodeToByteArray(), fileName ?: "inline.wireframe.json")
    }
}

/** The DXF import settings of the app limits. */
internal fun PopulateDeps.dxfImportSettings(): DxfImportSettings = DxfImportSettings(limits = DxfLimits.from(dxfLimits()))

/** The JSON of the drawing results. */
internal object DrawingJson {

    fun problem(problem: DrawingProblem): JsonObject = buildJsonObject {
        put("code", problem.code)
        put("severity", problem.severity.name.lowercase())
        put("message", problem.message)
    }

    fun problems(problems: List<DrawingProblem>): JsonArray = JsonArray(problems.map(::problem))

    /** The extents in [units]: the drawing coordinates are nm. */
    fun extents(extents: WireframeExtents?, units: String): JsonObject = buildJsonObject {
        if (extents == null) return@buildJsonObject
        fun length(nm: Long) = McpUnits.lengthFromUm(nm.toDouble() / Units.NM_PER_UM, units)
        put("minX", length(extents.min.x))
        put("minY", length(extents.min.y))
        put("maxX", length(extents.max.x))
        put("maxY", length(extents.max.y))
        put("units", units)
    }

    /** The summary of an imported document: the layers, the shape count, the extents, and the warnings. */
    fun summary(document: WireframeDocument, format: DrawingFormat?, warnings: List<DrawingProblem>, units: String): JsonObject = buildJsonObject {
        put("format", format?.name?.let(::JsonPrimitive) ?: JsonNull)
        put("layers", JsonArray(document.layers.map { layer -> JsonPrimitive(layer.name) }))
        put("shapeCount", document.entities.size)
        put("extents", extents(document.extents ?: computedExtents(document), units))
        put("warnings", problems(warnings))
    }

    /** The extents of a document that has none: the conversion to a drawing and back calculates them. */
    private fun computedExtents(document: WireframeDocument): WireframeExtents? =
        DrawingConversion.toWireframe(DrawingConversion.toLayers(document), DrawingConversion.toShapes(document)).extents

    /** The wireframe document of [drawing] as a JSON object. */
    fun wireframe(drawing: Drawing): JsonObject =
        kotlinx.serialization.json.Json.parseToJsonElement(WireframeWriter.write(DrawingConversion.toWireframe(drawing, drawing.createdOn))) as JsonObject
}

/** The text of a failed drawing import. */
internal fun drawingFailure(result: DrawingImportResult): String = when (result) {
    is DrawingImportResult.Imported -> ""
    is DrawingImportResult.NotADrawing -> result.message
    is DrawingImportResult.TooLarge -> result.message
    is DrawingImportResult.NoShapes -> result.message
    is DrawingImportResult.NotImported -> "The drawing was not imported: " + result.problems.joinToString("; ") { problem -> "${problem.code}: ${problem.message}" }
}

/** The drawing tools: `set_drawing` and `clear_drawing`. */
internal fun drawingTools(deps: PopulateDeps): List<McpTool> = listOf(setDrawing(deps), clearDrawing(deps))

private fun setDrawing(deps: PopulateDeps) = populateTool(
    name = "set_drawing",
    title = "Set drawing",
    description = "Sets the drawing of a workspace from an uploaded DXF or wireframe file (fileId), or from an inline " +
        "wireframe document. The import runs with the DXF limits of the app settings and replaces the drawing of the " +
        "workspace. Gives the layer list, the extents, and the warnings.",
    schema = objectSchema {
        machine()
        workspace()
        fileId()
        document()
        string("fileName", description = "The name of the source file, for the drawing record.")
        units()
        preview()
    },
    idempotent = true,
) { call, arguments ->
    val profile = deps.lookup.profile(arguments.requiredString("machine"))
    val workspace = deps.lookup.workspace(profile.id, arguments.requiredString("workspace"))
    val units = arguments.units(McpUnits.defaultUnits(profile))
    val source = deps.drawingBytes(arguments)
    val result = DrawingImporter.import(source.bytes, deps.dxfImportSettings(), now = deps.now())
    val imported = result as? DrawingImportResult.Imported ?: toolError(drawingFailure(result))
    val document = imported.document
    val summary = DrawingJson.summary(document, imported.format, imported.warnings, units)
    val text = "${PopulateJson.count(document.entities.size, "shape")} on ${PopulateJson.count(document.layers.size, "layer")}" +
        if (imported.warnings.isEmpty()) "" else ", ${PopulateJson.count(imported.warnings.size, "warning")}"
    if (arguments.preview) return@populateTool ToolResult.Ok("Preview: $text", summary)
    deps.confirm(call, risky = false, "Set drawing", "Set the drawing of \"${workspace.name}\" on ${profile.name}", listOf(text, source.fileName))
        ?.let { return@populateTool it }
    val drawing = DrawingConversion.toDrawing(document, workspace.id, DrawingSource(source.fileName, imported.format), createdOn = deps.now())
    deps.drawings.replaceDrawing(drawing).valueOrError()
    ToolResult.Ok("Drawing set: $text", summary)
}

private fun clearDrawing(deps: PopulateDeps) = populateTool(
    name = "clear_drawing",
    title = "Clear drawing",
    description = "Removes the drawing of a workspace. The sub-datums stay.",
    schema = objectSchema {
        machine()
        workspace()
        preview()
    },
    idempotent = true,
    interaction = true,
) { call, arguments ->
    val profile = deps.lookup.profile(arguments.requiredString("machine"))
    val workspace = deps.lookup.workspace(profile.id, arguments.requiredString("workspace"))
    val drawing = deps.drawings.loadDrawing(workspace.id)
    val structured = buildJsonObject {
        put("hadDrawing", drawing != null)
        put("shapeCount", drawing?.shapes?.size ?: 0)
    }
    if (drawing == null) return@populateTool ToolResult.Ok("The workspace \"${workspace.name}\" has no drawing", structured)
    val text = "${PopulateJson.count(drawing.shapes.size, "shape")} on ${PopulateJson.count(drawing.layers.size, "layer")}"
    if (arguments.preview) return@populateTool ToolResult.Ok("Preview: the drawing ($text) would be removed", structured)
    deps.confirm(call, risky = true, "Clear drawing", "Remove the drawing of \"${workspace.name}\" on ${profile.name}", listOf(text))
        ?.let { return@populateTool it }
    deps.drawings.deleteDrawing(workspace.id)
    ToolResult.Ok("Drawing removed ($text)", structured)
}
