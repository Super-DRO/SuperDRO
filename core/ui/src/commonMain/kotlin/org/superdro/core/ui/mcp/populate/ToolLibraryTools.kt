package org.superdro.core.ui.mcp.populate

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.superdro.core.drawing.tool.ToolGeometryCodec
import org.superdro.core.drawing.tool.ToolGeometryImport
import org.superdro.core.drawing.tool.ToolGeometryImportResult
import org.superdro.core.drawing.tool.ToolGeometryReport
import org.superdro.core.mcp.McpTool
import org.superdro.core.mcp.ToolResult
import org.superdro.core.mcp.enumSchema
import org.superdro.core.mcp.objectSchema
import org.superdro.core.mcp.stringSchema
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.Tool
import org.superdro.core.model.ToolKind
import org.superdro.core.store.files.ExportContext
import org.superdro.core.store.files.ImportOperation
import org.superdro.core.store.files.tool.ToolColumns
import org.superdro.core.store.files.tool.ToolCsvExporter
import org.superdro.core.store.files.tool.ToolImportContext
import org.superdro.core.store.files.tool.ToolJsonImporter
import org.superdro.core.ui.mcp.populate.PopulateSchemas.document
import org.superdro.core.ui.mcp.populate.PopulateSchemas.fileId
import org.superdro.core.ui.mcp.populate.PopulateSchemas.machine
import org.superdro.core.ui.mcp.populate.PopulateSchemas.operation
import org.superdro.core.ui.mcp.populate.PopulateSchemas.preview
import org.superdro.core.ui.mcp.populate.PopulateSchemas.units

/** The JSON of the tools in the results. */
internal object ToolJson {

    fun tool(tool: Tool, units: String): JsonObject = buildJsonObject {
        put("id", tool.id)
        put("kind", tool.kind.name)
        put("name", tool.name)
        put("description", tool.description)
        put("metric", tool.metric)
        if (tool.kind == ToolKind.Lathe) {
            put("xOffset", McpUnits.lengthFromUm(tool.xOffsetUm, units))
            put("zOffset", McpUnits.lengthFromUm(tool.zOffsetUm, units))
            put("internal", tool.internal)
        } else {
            put("diameter", McpUnits.lengthFromUm(tool.diameterUm, units))
            put("toothCount", tool.toothCount)
            put("zOffset", McpUnits.lengthFromUm(tool.zOffsetUm, units))
        }
        put("hasOutline", tool.geometry != null)
    }

    fun report(report: ToolGeometryReport, units: String): JsonObject = buildJsonObject {
        put(
            "checks",
            buildJsonArray {
                report.checks.forEach { check -> add(buildJsonObject { put("code", check.name); put("message", check.message) }) }
            },
        )
        put("width", McpUnits.lengthFromUm(report.widthUm, units))
        put("height", McpUnits.lengthFromUm(report.heightUm, units))
        put("closedOutlines", report.closedOutlines)
        put("openChains", report.openChains)
        put("units", units)
    }
}

private val toolRowSchema = objectSchema(description = "One tool. A mill tool has diameter and toothCount. A lathe tool has xOffset, zOffset, and internal.") {
    string("id", description = "The ID of a stored tool to update (UPDATE_MATCHING).")
    string("name", required = true, maxLength = ToolColumns.MAX_NAME_LENGTH)
    string("description", maxLength = ToolColumns.MAX_DESCRIPTION_LENGTH)
    boolean("metric", description = "The display unit of the tool. The default follows units.")
    number("diameter", description = "Mill: the cutter diameter in units. It must not be 0.")
    integer("toothCount", description = "Mill: the number of teeth. The default is 0.", minimum = 0)
    number("zOffset", description = "The Z offset in units. The default is 0.")
    number("xOffset", description = "Lathe: the X offset in units. The default is 0.")
    boolean("internal", description = "Lathe: true for a boring tool. The default is false.")
}

/** The tool library tools: `put_tools`, `delete_tools`, `set_tool_outline`, `clear_tool_outline`, `export_tools`. */
internal fun toolLibraryTools(deps: PopulateDeps): List<McpTool> = listOf(
    putTools(deps),
    deleteTools(deps),
    setToolOutline(deps),
    clearToolOutline(deps),
    exportTools(deps),
)

/** The active tool of the active workspace of an online machine, or null. */
private suspend fun PopulateDeps.activeOnlineToolId(profile: MachineProfile): String? {
    if (!isOnline(profile)) return null
    val workspaceId = profile.activeWorkspace?.id ?: profile.activeWorkspaceId
    return machines.loadWorkspace(workspaceId)?.activeToolId
}

private fun putTools(deps: PopulateDeps) = populateTool(
    name = "put_tools",
    title = "Put tools",
    description = "Adds or updates the tools of the library that a machine uses (its own or the shared library). " +
        "The operations are the operations of the CSV import: UPDATE_MATCHING (the default; a row updates the stored " +
        "tool with its id, else with its name), ADD_AS_NEW, and REPLACE_ALL (deletes the library first). The name has " +
        "50 characters or less, the description 100.",
    schema = objectSchema {
        machine()
        array("tools", toolRowSchema, required = true, minItems = 1)
        operation("The import operation. The default is UPDATE_MATCHING.")
        units()
        preview()
    },
    idempotent = true,
    interaction = true,
) { call, arguments ->
    val profile = deps.lookup.profile(arguments.requiredString("machine"))
    val units = arguments.units(McpUnits.defaultUnits(profile))
    val operation = arguments.operation(ImportOperation.UpdateMatching)
    val lathe = profile.machineType.isLathe
    val rows = (arguments.objects("tools") ?: toolError("The argument \"tools\" is required.")).map { row -> toolRow(row, lathe, units) }
    val library = deps.tools.loadLibrary(profile.id)
    val read = ToolJsonImporter(now = deps::now).read(rows, ToolImportContext(profile.id, profile.machineType, library))
    if (!read.usable) toolError(read.problems.map(PopulateJson::problemText).ifEmpty { listOf("No row can be imported.") }.joinToString(" "))
    if (operation !in read.operations) {
        toolError("The operation ${operation.wireName} is not available for this list. Use ${read.operations.joinToString { it.wireName }}.")
    }
    val plan = read.plan(operation)
    val counts = "${PopulateJson.count(plan.add.size, "tool")} added, ${PopulateJson.count(plan.update.size, "tool")} updated, " +
        "${PopulateJson.count(plan.preview.skipped.size, "row")} skipped" +
        if (plan.deleteIds.isEmpty()) "" else ", ${PopulateJson.count(plan.deleteIds.size, "tool")} deleted"
    val structured = buildJsonObject {
        put("operation", operation.wireName)
        put("added", plan.add.size)
        put("updated", plan.update.size)
        put("deleted", plan.deleteIds.size)
        put("skippedCount", plan.preview.skipped.size)
        put("units", units)
        PopulateJson.preview(plan.preview) { tool -> ToolJson.tool(tool, units) }.forEach { (key, element) -> put(key, element) }
    }
    if (arguments.preview) return@populateTool ToolResult.Ok("Preview: $counts", structured)

    val activeToolId = deps.activeOnlineToolId(profile)
    val risky = plan.deleteIds.isNotEmpty() || (activeToolId != null && plan.update.any { tool -> tool.id == activeToolId })
    deps.confirm(call, risky, "Put tools", "${operation.wireName} tools on ${profile.name}", listOf(counts))?.let { return@populateTool it }
    if (plan.deleteIds.isNotEmpty()) deps.tools.deleteTools(plan.deleteIds)
    deps.tools.saveTools(profile.id, plan.add + plan.update).valueOrError()
    ToolResult.Ok(counts, structured)
}

/** The row of the JSON importer: the CSV column names, the lengths in the unit of the row. */
private fun toolRow(row: JsonObject, lathe: Boolean, units: String): JsonObject {
    val arguments = Arguments(row)
    val metric = row["metric"]?.let { arguments.boolean("metric") } ?: McpUnits.isMetric(units)
    val rowUnits = if (metric) McpUnits.MM else McpUnits.INCH
    fun length(name: String, default: Double?): Double? = arguments.double(name)?.let { value -> McpUnits.lengthFromUm(McpUnits.lengthToUm(value, units), rowUnits) } ?: default
    return buildJsonObject {
        arguments.string("id")?.let { put(ToolColumns.ID, it) }
        put(ToolColumns.NAME, arguments.string("name") ?: "")
        arguments.string("description")?.let { put(ToolColumns.DESCRIPTION, it) }
        put(ToolColumns.METRIC, metric)
        put(ToolColumns.Z_OFFSET, length("zOffset", 0.0)!!)
        if (lathe) {
            put(ToolColumns.INTERNAL, arguments.boolean("internal", false))
            put(ToolColumns.X_OFFSET, length("xOffset", 0.0)!!)
        } else {
            put(ToolColumns.DIAMETER, PopulateJson.number(length("diameter", null)))
            put(ToolColumns.TOOTH_COUNT, arguments.int("toothCount") ?: 0)
        }
    }
}

private fun deleteTools(deps: PopulateDeps) = populateTool(
    name = "delete_tools",
    title = "Delete tools",
    description = "Deletes the tools of the library that a machine uses, by their IDs. An ID that is not in the library is skipped.",
    schema = objectSchema {
        machine()
        array("ids", stringSchema(), required = true, minItems = 1)
        preview()
    },
    idempotent = true,
    interaction = true,
) { call, arguments ->
    val profile = deps.lookup.profile(arguments.requiredString("machine"))
    val ids = arguments.strings("ids") ?: toolError("The argument \"ids\" is required.")
    val library = deps.tools.loadLibrary(profile.id)
    val (found, missing) = ids.distinct().partition { id -> library.any { tool -> tool.id == id } }
    val tools = library.filter { tool -> tool.id in found }
    val preview = PopulateJson.simplePreview(tools.map { tool -> tool.name to "Delete" }, missing.map { id -> id to "Skip" })
    val counts = "${PopulateJson.count(tools.size, "tool")} deleted, ${PopulateJson.count(missing.size, "ID")} not found"
    if (arguments.preview) return@populateTool ToolResult.Ok("Preview: $counts", preview)
    if (tools.isEmpty()) toolError("No tool of the library has one of the IDs.")
    deps.confirm(call, risky = true, "Delete tools", "Delete ${PopulateJson.count(tools.size, "tool")} from ${profile.name}", tools.map(Tool::name))
        ?.let { return@populateTool it }
    deps.tools.deleteTools(found)
    ToolResult.Ok(
        counts,
        buildJsonObject {
            put("deleted", tools.size)
            put("deletedIds", JsonArray(found.map(::JsonPrimitive)))
            put("notFound", JsonArray(missing.map(::JsonPrimitive)))
        },
    )
}

private fun setToolOutline(deps: PopulateDeps) = populateTool(
    name = "set_tool_outline",
    title = "Set tool outline",
    description = "Sets the cutter outline of a lathe tool from an uploaded DXF or wireframe file (fileId), or from an " +
        "inline wireframe document, with drawnIn (mm or inch). Runs the checks of the tool geometry screen and gives the report.",
    schema = objectSchema {
        machine()
        string("tool", required = true, description = "The tool ID or the tool name.")
        fileId()
        document()
        property("drawnIn", enumSchema(listOf("mm", "inch"), "The unit of the drawing. The default is mm."))
        string("fileName", description = "The name of the source file, for the tool record.")
        units()
        preview()
    },
    idempotent = true,
) { call, arguments ->
    val profile = deps.lookup.profile(arguments.requiredString("machine"))
    val tool = deps.lookup.tool(profile.id, arguments.requiredString("tool"))
    if (tool.kind != ToolKind.Lathe) toolError("The tool \"${tool.name}\" is a mill tool. Only a lathe tool has an outline.")
    val units = arguments.units(McpUnits.defaultUnits(profile))
    val drawnIn = when (val text = arguments.string("drawnIn") ?: "mm") {
        "mm" -> ToolGeometryCodec.DRAWN_IN_MM
        "inch" -> ToolGeometryCodec.DRAWN_IN_INCH
        else -> toolError("drawnIn must be mm or inch, not \"$text\".")
    }
    val source = deps.drawingBytes(arguments)
    val result = ToolGeometryImport.import(source.bytes, source.fileName, drawnIn, deps.dxfImportSettings(), now = deps.now())
    val imported = when (result) {
        is ToolGeometryImportResult.Imported -> result
        is ToolGeometryImportResult.Failed -> toolError(
            result.message + if (result.problems.isEmpty()) "" else " " + result.problems.joinToString("; ") { problem -> "${problem.code}: ${problem.message}" },
        )
    }
    val structured = buildJsonObject {
        put("toolId", tool.id)
        put("shapeCount", imported.geometry.shapes.size)
        put("drawnIn", if (drawnIn == ToolGeometryCodec.DRAWN_IN_INCH) "inch" else "mm")
        put("report", ToolJson.report(imported.report, units))
        put("warnings", DrawingJson.problems(imported.warnings))
    }
    val text = "${PopulateJson.count(imported.geometry.shapes.size, "shape")}, ${PopulateJson.count(imported.report.checks.size, "check")}"
    if (arguments.preview) return@populateTool ToolResult.Ok("Preview: $text", structured)
    val risky = deps.activeOnlineToolId(profile) == tool.id
    deps.confirm(call, risky, "Set tool outline", "Set the outline of tool \"${tool.name}\" on ${profile.name}", listOf(text, source.fileName))
        ?.let { return@populateTool it }
    deps.tools.updateTool(tool.copy(geometry = imported.geometry)).valueOrError()
    ToolResult.Ok("Outline set on \"${tool.name}\": $text", structured)
}

private fun clearToolOutline(deps: PopulateDeps) = populateTool(
    name = "clear_tool_outline",
    title = "Clear tool outline",
    description = "Removes the cutter outline of a lathe tool.",
    schema = objectSchema {
        machine()
        string("tool", required = true, description = "The tool ID or the tool name.")
        preview()
    },
    idempotent = true,
    interaction = true,
) { call, arguments ->
    val profile = deps.lookup.profile(arguments.requiredString("machine"))
    val tool = deps.lookup.tool(profile.id, arguments.requiredString("tool"))
    val structured = buildJsonObject {
        put("toolId", tool.id)
        put("hadOutline", tool.geometry != null)
    }
    if (tool.geometry == null) return@populateTool ToolResult.Ok("The tool \"${tool.name}\" has no outline", structured)
    if (arguments.preview) return@populateTool ToolResult.Ok("Preview: the outline of \"${tool.name}\" would be removed", structured)
    deps.confirm(call, risky = true, "Clear tool outline", "Remove the outline of tool \"${tool.name}\" on ${profile.name}", emptyList())
        ?.let { return@populateTool it }
    deps.tools.updateTool(tool.copy(geometry = null)).valueOrError()
    ToolResult.Ok("Outline removed from \"${tool.name}\"", structured)
}

private fun exportTools(deps: PopulateDeps) = populateTool(
    name = "export_tools",
    title = "Export tools",
    description = "Writes the tool library of a machine as the SuperDRO tool CSV file and gives the download path (GET /download/<fileId>).",
    schema = objectSchema {
        machine()
    },
    idempotent = true,
    readOnly = true,
) { _, arguments ->
    val profile = deps.lookup.profile(arguments.requiredString("machine"))
    val library = deps.tools.loadLibrary(profile.id)
    val file = ToolCsvExporter.export(profile, library, ExportContext(deps.appVersion, deps.now()))
    val fileId = deps.files.putDownload(file.fileName, file.mimeType, file.bytes())
    ToolResult.Ok(
        "${PopulateJson.count(library.size, "tool")} exported to /download/$fileId",
        buildJsonObject {
            put("fileId", fileId)
            put("path", "/download/$fileId")
            put("fileName", file.fileName)
            put("mimeType", file.mimeType)
            put("toolCount", library.size)
        },
    )
}
