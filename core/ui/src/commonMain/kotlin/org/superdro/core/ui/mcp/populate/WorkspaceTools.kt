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
import org.superdro.core.mcp.McpTool
import org.superdro.core.mcp.ToolResult
import org.superdro.core.mcp.booleanSchema
import org.superdro.core.mcp.enumSchema
import org.superdro.core.mcp.numberSchema
import org.superdro.core.mcp.objectSchema
import org.superdro.core.mcp.stringSchema
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.GraphicalSwitches
import org.superdro.core.model.Guide
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.Plane
import org.superdro.core.model.SubDatum
import org.superdro.core.model.Workspace
import org.superdro.core.model.newId
import org.superdro.core.store.files.ImportOperation
import org.superdro.core.store.files.subdatum.SubDatumImportContext
import org.superdro.core.store.files.subdatum.SubDatumItem
import org.superdro.core.store.files.subdatum.SubDatumJsonImporter
import org.superdro.core.ui.mcp.populate.PopulateSchemas.machine
import org.superdro.core.ui.mcp.populate.PopulateSchemas.operation
import org.superdro.core.ui.mcp.populate.PopulateSchemas.preview
import org.superdro.core.ui.mcp.populate.PopulateSchemas.units
import org.superdro.core.ui.mcp.populate.PopulateSchemas.workspace

/** The axis names of the tools: `X`, `Y`, `Z`, and `A` (the angular axis). */
internal object AxisNames {
    val ALL: List<String> = listOf("X", "Y", "Z", "A")

    fun of(axis: AxisId): String? = when (axis) {
        AxisId.X -> "X"
        AxisId.Y -> "Y"
        AxisId.Z -> "Z"
        AxisId.Angular -> "A"
        else -> null
    }

    fun parse(name: String): AxisId = when (name.trim().uppercase()) {
        "X" -> AxisId.X
        "Y" -> AxisId.Y
        "Z" -> AxisId.Z
        "A" -> AxisId.Angular
        else -> toolError("The axis must be X, Y, Z, or A, not \"$name\".")
    }
}

/** The JSON of the workspace models in the results. */
internal object WorkspaceJson {

    /** The planes of a machine type. */
    fun planes(profile: MachineProfile): List<Plane> =
        if (profile.machineType.isLathe) listOf(Plane.ZX) else listOf(Plane.XY, Plane.XZ, Plane.YZ)

    fun plane(profile: MachineProfile, text: String): Plane {
        val plane = Plane.entries.firstOrNull { plane -> plane.name.equals(text.trim(), ignoreCase = true) }
            ?: toolError("The plane must be one of ${Plane.entries.joinToString()}, not \"$text\".")
        if (plane !in planes(profile)) toolError("The plane $plane is not valid for a ${profile.machineType}. Use ${planes(profile).joinToString()}.")
        return plane
    }

    fun subDatum(point: SubDatum, units: String): JsonObject = buildJsonObject {
        put("id", point.id)
        put("name", point.name)
        if (point.note.isNotEmpty()) put("note", point.note)
        if (point.tags.isNotEmpty()) put("tags", JsonArray(point.tags.map(::JsonPrimitive)))
        put("x", PopulateJson.number(point.x?.let { McpUnits.lengthFromUm(it, units) }))
        put("y", PopulateJson.number(point.y?.let { McpUnits.lengthFromUm(it, units) }))
        put("z", PopulateJson.number(point.z?.let { McpUnits.lengthFromUm(it, units) }))
        put("a", PopulateJson.number(point.a?.let(McpUnits::angleFromArcSec)))
    }

    fun guide(guide: Guide, units: String): JsonObject = buildJsonObject {
        put("id", guide.id)
        put("axis", AxisNames.of(guide.axis) ?: guide.axis.name)
        put("value", McpUnits.lengthFromUm(guide.valueUm, units))
    }

    fun axisState(axis: AxisId, state: AxisState, units: String): JsonObject = buildJsonObject {
        val angular = axis == AxisId.Angular
        fun length(um: Double) = if (angular) McpUnits.angleFromArcSec(um) else McpUnits.lengthFromUm(um, units)
        put("absoluteOffset", length(state.absoluteOffset))
        put("incrementalOffset", length(state.incrementalOffset))
        put("relative", state.relative)
        put("toolOffset", length(state.toolOffset))
        put("toolOffsetActive", state.toolOffsetActive)
        put("metric", state.metric?.let(::JsonPrimitive) ?: JsonNull)
        put("diameterMode", state.diameterMode)
        put("referenced", state.referenced)
    }

    fun graphical(switches: GraphicalSwitches): JsonObject = buildJsonObject {
        put("snapToPoint", switches.snapToPoint)
        put("snapToGuide", switches.snapToGuide)
        put("automaticToolOffset", switches.automaticToolOffset)
        put("automaticProximityWarning", switches.automaticProximityWarning)
        put("automaticZero", switches.automaticZero)
        put("automaticSubDatum", switches.automaticSubDatum)
    }

    /** The summary of [workspace]: the identity, the plane, the active items, the counts, and the axis states. */
    fun summary(profile: MachineProfile, workspace: Workspace, units: String): JsonObject = buildJsonObject {
        put("id", workspace.id)
        put("name", workspace.name)
        put("plane", workspace.plane.name)
        put("active", (profile.activeWorkspace?.id ?: profile.activeWorkspaceId) == workspace.id)
        put("activeSubDatumId", workspace.activeSubDatumId?.let(::JsonPrimitive) ?: JsonNull)
        put("activeToolId", workspace.activeToolId?.let(::JsonPrimitive) ?: JsonNull)
        put("subDatumCount", workspace.subDatums.size)
        put("guideCount", workspace.guides.size)
        put("hasReferenceImage", workspace.referenceImage != null)
        put("graphical", graphical(workspace.graphical))
        put(
            "axisStates",
            buildJsonObject {
                AxisNames.ALL.forEach { name ->
                    val axis = AxisNames.parse(name)
                    put(name, axisState(axis, workspace.axisStates[axis] ?: AxisState(), units))
                }
            },
        )
        put("units", units)
    }
}

private val axisStateSchema = objectSchema(description = "The display state of one axis. Lengths in units; A in degrees.") {
    number("absoluteOffset")
    number("incrementalOffset")
    boolean("relative")
    number("toolOffset")
    boolean("toolOffsetActive")
    property("metric", buildJsonObject { put("type", JsonArray(listOf(JsonPrimitive("boolean"), JsonPrimitive("null")))); put("description", "The unit of the axis, or null for the metric default of the profile.") })
    boolean("diameterMode")
    boolean("referenced")
}

private val graphicalSchema = objectSchema(description = "The automatic functions of the graphical view.") {
    boolean("snapToPoint")
    boolean("snapToGuide")
    boolean("automaticToolOffset")
    boolean("automaticProximityWarning")
    boolean("automaticZero")
    boolean("automaticSubDatum")
}

private val pointSchema = objectSchema(description = "One point. A row with the name \"Guide\" and one coordinate is a guide.") {
    string("id", description = "The ID of a stored point to update (UPDATE_MATCHING).")
    string("name", required = true)
    string("note")
    property("tags", buildJsonObject { put("type", "array"); put("items", stringSchema()) })
    number("x", description = "The X value in units. A lathe X is a radius.")
    number("y", description = "The Y value in units. A lathe has no Y.")
    number("z")
    number("a", description = "The angle in degrees.")
}

private val guideSchema = objectSchema(description = "One guide line.") {
    property("axis", enumSchema(listOf("X", "Y", "Z")), required = true)
    number("value", required = true, description = "The position in units.")
}

/** The workspace tools: `create_workspace`, `update_workspace`, `delete_workspace`, `put_sub_datums`, `delete_sub_datums`, `put_guides`. */
internal fun workspaceTools(deps: PopulateDeps): List<McpTool> = listOf(
    createWorkspace(deps),
    updateWorkspace(deps),
    deleteWorkspace(deps),
    putSubDatums(deps),
    deleteSubDatums(deps),
    putGuides(deps),
)

private fun createWorkspace(deps: PopulateDeps) = populateTool(
    name = "create_workspace",
    title = "Create workspace",
    description = "Makes a new workspace in a machine profile with a name and an optional plane. Gives the ID. " +
        "The new workspace does not become the active workspace.",
    schema = objectSchema {
        machine()
        string("name", required = true, description = "The name of the new workspace. It must be new in the profile.")
        property("plane", enumSchema(Plane.entries.map(Plane::name), "The plane of the graphical view. A mill has XY, XZ, YZ. A lathe has ZX."))
        units()
        preview()
    },
    idempotent = false,
) { call, arguments ->
    val profile = deps.lookup.profile(arguments.requiredString("machine"))
    val name = arguments.requiredString("name")
    val plane = arguments.string("plane")?.let { text -> WorkspaceJson.plane(profile, text) }
    val units = arguments.units(McpUnits.defaultUnits(profile))
    if (arguments.preview) {
        return@populateTool ToolResult.Ok(
            "Preview: workspace \"$name\" would be created in ${profile.name}",
            PopulateJson.simplePreview(listOf(name to "Add")),
        )
    }
    deps.confirm(call, risky = false, "Create workspace", "Create workspace \"$name\" on ${profile.name}", emptyList())?.let { return@populateTool it }
    var workspace = deps.machines.createWorkspace(profile.id, name).valueOrError()
    if (plane != null && plane != workspace.plane) {
        workspace = deps.machines.updateWorkspace(workspace.id) { current -> current.copy(plane = plane) }.valueOrError()
    }
    ToolResult.Ok("Workspace \"${workspace.name}\" created with ID ${workspace.id}", WorkspaceJson.summary(profile, workspace, units))
}

private fun updateWorkspace(deps: PopulateDeps) = populateTool(
    name = "update_workspace",
    title = "Update workspace",
    description = "Changes the name, the plane, the graphical switches, the axis states, the active sub-datum, or the " +
        "active tool of a workspace. Only the given fields change. An empty text clears activeSubDatumId or activeToolId.",
    schema = objectSchema {
        machine()
        workspace()
        string("name")
        property("plane", enumSchema(Plane.entries.map(Plane::name)))
        property("graphical", graphicalSchema)
        obj("axisStates", description = "The axis states to change, by axis name X, Y, Z, A.") {
            AxisNames.ALL.forEach { name -> property(name, axisStateSchema) }
        }
        string("activeSubDatumId")
        string("activeToolId")
        units()
        preview()
    },
    idempotent = true,
) { call, arguments ->
    val profile = deps.lookup.profile(arguments.requiredString("machine"))
    val workspace = deps.lookup.workspace(profile.id, arguments.requiredString("workspace"))
    val units = arguments.units(McpUnits.defaultUnits(profile))
    val changes = mutableListOf<Pair<String, String>>()

    val name = arguments.string("name")?.trim()?.also { value ->
        if (value.isEmpty()) toolError("The name must not be blank.")
        if (value != workspace.name) changes += "name" to "Update"
    }
    val plane = arguments.string("plane")?.let { text -> WorkspaceJson.plane(profile, text) }?.also { value ->
        if (value != workspace.plane) changes += "plane" to "Update"
    }
    val graphical = arguments.obj("graphical")?.let { json -> readGraphical(json, workspace.graphical) }?.also { value ->
        if (value != workspace.graphical) changes += "graphical" to "Update"
    }
    val axisStates = arguments.obj("axisStates")?.let { json ->
        json.mapNotNull { (key, element) ->
            val axis = AxisNames.parse(key)
            val current = workspace.axisStates[axis] ?: AxisState()
            val next = readAxisState(element as? JsonObject ?: toolError("The axis state of $key must be an object."), current, axis, units)
            if (next != current) changes += "axisStates.$key" to "Update"
            axis to next
        }.toMap()
    }
    val activeSubDatumId = arguments.string("activeSubDatumId")?.let { id ->
        val value = id.trim().ifEmpty { null }
        if (value != null && workspace.subDatums.none { point -> point.id == value }) toolError("The workspace has no sub-datum with the ID \"$value\".")
        if (value != workspace.activeSubDatumId) changes += "activeSubDatumId" to "Update"
        Optional(value)
    }
    val activeToolId = arguments.string("activeToolId")?.let { id ->
        val value = id.trim().ifEmpty { null }
        if (value != null && deps.tools.loadLibrary(profile.id).none { tool -> tool.id == value }) toolError("The library has no tool with the ID \"$value\".")
        if (value != workspace.activeToolId) changes += "activeToolId" to "Update"
        Optional(value)
    }
    if (name == null && plane == null && graphical == null && axisStates == null && activeSubDatumId == null && activeToolId == null) {
        toolError("Give one or more fields to change.")
    }
    if (arguments.preview) {
        return@populateTool ToolResult.Ok(
            "Preview: ${PopulateJson.count(changes.size, "field")} would change on \"${workspace.name}\"",
            PopulateJson.simplePreview(changes),
        )
    }
    val risky = deps.isActiveOnline(profile, workspace.id) && (axisStates != null || activeSubDatumId != null || activeToolId != null)
    deps.confirm(call, risky, "Update workspace", "Update workspace \"${workspace.name}\" on ${profile.name}", changes.map { (field, _) -> field })
        ?.let { return@populateTool it }

    if (name != null && name != workspace.name) deps.machines.renameWorkspace(workspace.id, name).valueOrError()
    val updated = deps.machines.updateWorkspace(workspace.id) { current ->
        var next = current
        if (plane != null) next = next.copy(plane = plane)
        if (graphical != null) next = next.copy(graphical = graphical)
        if (axisStates != null) next = next.copy(axisStates = next.axisStates + axisStates)
        if (activeSubDatumId != null) next = next.copy(activeSubDatumId = activeSubDatumId.value)
        if (activeToolId != null) next = next.copy(activeToolId = activeToolId.value)
        next
    }.valueOrError()
    ToolResult.Ok(
        "${PopulateJson.count(changes.size, "field")} updated on \"${updated.name}\"",
        WorkspaceJson.summary(deps.machines.loadProfile(profile.id) ?: profile, updated, units),
    )
}

/** A value that the agent gave, also when it is null (an empty text clears the field). */
private class Optional<T>(val value: T?)

private fun readGraphical(json: JsonObject, current: GraphicalSwitches): GraphicalSwitches {
    fun flag(name: String, value: Boolean): Boolean = when (val element = json[name]) {
        null, JsonNull -> value
        else -> (element as? JsonPrimitive)?.booleanOrNull ?: toolError("The switch \"$name\" must be true or false.")
    }
    return GraphicalSwitches(
        snapToPoint = flag("snapToPoint", current.snapToPoint),
        snapToGuide = flag("snapToGuide", current.snapToGuide),
        automaticToolOffset = flag("automaticToolOffset", current.automaticToolOffset),
        automaticProximityWarning = flag("automaticProximityWarning", current.automaticProximityWarning),
        automaticZero = flag("automaticZero", current.automaticZero),
        automaticSubDatum = flag("automaticSubDatum", current.automaticSubDatum),
    )
}

private fun readAxisState(json: JsonObject, current: AxisState, axis: AxisId, units: String): AxisState {
    val arguments = Arguments(json)
    fun length(name: String, value: Double): Double = arguments.double(name)?.let { number ->
        if (axis == AxisId.Angular) McpUnits.angleToArcSec(number) else McpUnits.lengthToUm(number, units)
    } ?: value
    val metric: Boolean? = when (val element = json["metric"]) {
        null -> current.metric
        JsonNull -> null
        else -> (element as? JsonPrimitive)?.booleanOrNull ?: toolError("The field \"metric\" must be true, false, or null.")
    }
    return current.copy(
        absoluteOffset = length("absoluteOffset", current.absoluteOffset),
        incrementalOffset = length("incrementalOffset", current.incrementalOffset),
        relative = arguments.boolean("relative", current.relative),
        toolOffset = length("toolOffset", current.toolOffset),
        toolOffsetActive = arguments.boolean("toolOffsetActive", current.toolOffsetActive),
        metric = metric,
        diameterMode = arguments.boolean("diameterMode", current.diameterMode),
        referenced = arguments.boolean("referenced", current.referenced),
    )
}

private fun deleteWorkspace(deps: PopulateDeps) = populateTool(
    name = "delete_workspace",
    title = "Delete workspace",
    description = "Deletes a workspace with its sub-datums, guides, drawing, and reference image file. " +
        "The last workspace of a profile cannot go.",
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
    val details = listOf(
        PopulateJson.count(workspace.subDatums.size, "sub-datum"),
        PopulateJson.count(workspace.guides.size, "guide"),
    )
    if (arguments.preview) {
        return@populateTool ToolResult.Ok(
            "Preview: workspace \"${workspace.name}\" would be deleted (${details.joinToString()})",
            PopulateJson.simplePreview(listOf(workspace.name to "Delete")),
        )
    }
    deps.confirm(call, risky = true, "Delete workspace", "Delete workspace \"${workspace.name}\" on ${profile.name}", details)
        ?.let { return@populateTool it }
    deps.machines.deleteWorkspace(workspace.id).valueOrError()
    ToolResult.Ok("Workspace \"${workspace.name}\" deleted", buildJsonObject { put("deletedId", workspace.id) })
}

private fun putSubDatums(deps: PopulateDeps) = populateTool(
    name = "put_sub_datums",
    title = "Put sub-datums",
    description = "Adds or updates the points of a workspace from a list. The operations are the operations of the " +
        "CSV import: UPDATE_MATCHING (the default; a row updates the stored point with its id, else with its name), " +
        "ADD_AS_NEW, ADD_NEW_ONLY, MERGE_MATCHING, and REPLACE_ALL (deletes all points and guides first). " +
        "A row with the name \"Guide\" and one coordinate adds a guide.",
    schema = objectSchema {
        machine()
        workspace()
        array("points", pointSchema, required = true, minItems = 1)
        operation("The import operation. The default is UPDATE_MATCHING.")
        units()
        preview()
    },
    idempotent = true,
    interaction = true,
) { call, arguments ->
    val profile = deps.lookup.profile(arguments.requiredString("machine"))
    val workspace = deps.lookup.workspace(profile.id, arguments.requiredString("workspace"))
    val units = arguments.units(McpUnits.defaultUnits(profile))
    val operation = arguments.operation(ImportOperation.UpdateMatching)
    val rows = (arguments.objects("points") ?: toolError("The argument \"points\" is required.")).map { row -> subDatumRow(row, units) }
    val metric = McpUnits.isMetric(units)
    val context = SubDatumImportContext(profile.id, profile.machineType, profile.metricDefault, workspace.subDatums, workspace.guides, metric)
    val read = SubDatumJsonImporter(now = deps::now).read(rows, metric, context)
    if (!read.usable) toolError(read.problems.map(PopulateJson::problemText).ifEmpty { listOf("No row can be imported.") }.joinToString(" "))
    if (operation !in read.operations) {
        toolError("The operation ${operation.wireName} is not available for this list. Use ${read.operations.joinToString { it.wireName }}.")
    }
    val plan = read.plan(operation)
    val previewJson = PopulateJson.preview(plan.preview) { item -> subDatumItem(item, units) }
    val counts = "${PopulateJson.count(plan.addSubDatums.size, "point")} added, ${PopulateJson.count(plan.updateSubDatums.size, "point")} updated, " +
        "${PopulateJson.count(plan.addGuides.size, "guide")} added, ${PopulateJson.count(plan.preview.skipped.size, "row")} skipped"
    val structured = buildJsonObject {
        put("operation", operation.wireName)
        put("added", plan.addSubDatums.size)
        put("updated", plan.updateSubDatums.size)
        put("guidesAdded", plan.addGuides.size)
        put("skippedCount", plan.preview.skipped.size)
        put("units", units)
        previewJson.forEach { (key, element) -> put(key, element) }
    }
    if (arguments.preview) return@populateTool ToolResult.Ok("Preview: $counts", structured)

    val touchesActive = workspace.activeSubDatumId != null && plan.updateSubDatums.any { point -> point.id == workspace.activeSubDatumId }
    val risky = plan.deleteAll || (deps.isActiveOnline(profile, workspace.id) && touchesActive)
    val details = buildList {
        add(counts)
        if (plan.deleteAll) add("Deletes all ${workspace.subDatums.size} points and ${workspace.guides.size} guides first")
    }
    deps.confirm(call, risky, "Put sub-datums", "${operation.wireName} on \"${workspace.name}\" of ${profile.name}", details)
        ?.let { return@populateTool it }
    deps.machines.applySubDatumImport(workspace.id, plan).valueOrError()
    ToolResult.Ok(counts, structured)
}

/** The row of the JSON importer: the CSV column names, the lengths in mm or inch. */
private fun subDatumRow(row: JsonObject, units: String): JsonObject {
    val arguments = Arguments(row)
    val tags = arguments.strings("tags")
    return buildJsonObject {
        arguments.string("id")?.let { put("id", it) }
        put("name", arguments.string("name") ?: "")
        arguments.string("note")?.let { put("note", it) }
        if (tags != null) put("tags", JsonArray(tags.map(::JsonPrimitive)))
        listOf("x", "y", "z").forEach { key -> row[key]?.let { put(key, lengthForImporter(it, key, units)) } }
        row["a"]?.let { put("a", it) }
    }
}

/**
 * The importer reads a length in mm or inch. A value in `um` becomes mm. The other units keep
 * the JSON value, so its decimals give the match tolerance.
 */
private fun lengthForImporter(value: JsonElement, key: String, units: String): JsonElement {
    if (value is JsonNull || units != McpUnits.UM) return value
    val number = (value as? JsonPrimitive)?.doubleOrNull ?: toolError("The value of \"$key\" must be a number.")
    return JsonPrimitive(number / 1000.0)
}

private fun subDatumItem(item: SubDatumItem, units: String): JsonObject = when (item) {
    is SubDatumItem.Point -> WorkspaceJson.subDatum(item.subDatum, units)
    is SubDatumItem.Line -> WorkspaceJson.guide(item.guide, units)
}

private fun deleteSubDatums(deps: PopulateDeps) = populateTool(
    name = "delete_sub_datums",
    title = "Delete sub-datums",
    description = "Deletes the points of a workspace by their IDs. An ID that is not in the workspace is skipped.",
    schema = objectSchema {
        machine()
        workspace()
        array("ids", stringSchema(), required = true, minItems = 1)
        preview()
    },
    idempotent = true,
    interaction = true,
) { call, arguments ->
    val profile = deps.lookup.profile(arguments.requiredString("machine"))
    val workspace = deps.lookup.workspace(profile.id, arguments.requiredString("workspace"))
    val ids = arguments.strings("ids") ?: toolError("The argument \"ids\" is required.")
    val (found, missing) = ids.distinct().partition { id -> workspace.subDatums.any { point -> point.id == id } }
    val points = workspace.subDatums.filter { point -> point.id in found }
    val preview = PopulateJson.simplePreview(points.map { point -> point.name to "Delete" }, missing.map { id -> id to "Skip" })
    val counts = "${PopulateJson.count(points.size, "point")} deleted, ${PopulateJson.count(missing.size, "ID")} not found"
    if (arguments.preview) return@populateTool ToolResult.Ok("Preview: $counts", preview)
    if (points.isEmpty()) toolError("No point of the workspace has one of the IDs.")
    deps.confirm(call, risky = true, "Delete sub-datums", "Delete ${PopulateJson.count(points.size, "point")} from \"${workspace.name}\" of ${profile.name}", points.map(SubDatum::name))
        ?.let { return@populateTool it }
    val deleted = deps.machines.deleteSubDatums(workspace.id, found)
    ToolResult.Ok(
        counts,
        buildJsonObject {
            put("deleted", deleted.size)
            put("deletedIds", JsonArray(deleted.map { point -> JsonPrimitive(point.id) }))
            put("notFound", JsonArray(missing.map(::JsonPrimitive)))
        },
    )
}

private fun putGuides(deps: PopulateDeps) = populateTool(
    name = "put_guides",
    title = "Put guides",
    description = "Adds guide lines to a workspace (ADD, the default), or replaces all guides (REPLACE_ALL). " +
        "A guide that is already there is skipped.",
    schema = objectSchema {
        machine()
        workspace()
        array("guides", guideSchema, required = true, minItems = 1)
        property("operation", enumSchema(listOf("ADD", "REPLACE_ALL"), "ADD keeps the stored guides. REPLACE_ALL deletes them first."))
        units()
        preview()
    },
    idempotent = true,
    interaction = true,
) { call, arguments ->
    val profile = deps.lookup.profile(arguments.requiredString("machine"))
    val workspace = deps.lookup.workspace(profile.id, arguments.requiredString("workspace"))
    val units = arguments.units(McpUnits.defaultUnits(profile))
    val replace = when (val operation = arguments.string("operation") ?: "ADD") {
        "ADD" -> false
        "REPLACE_ALL" -> true
        else -> toolError("The operation must be ADD or REPLACE_ALL, not \"$operation\".")
    }
    val lathe = profile.machineType.isLathe
    val now = deps.now()
    val wanted = (arguments.objects("guides") ?: toolError("The argument \"guides\" is required.")).map { json ->
        val guideArguments = Arguments(json)
        val axis = AxisNames.parse(guideArguments.requiredString("axis"))
        if (axis == AxisId.Angular || (lathe && axis == AxisId.Y)) toolError("A guide needs the axis ${if (lathe) "X or Z" else "X, Y, or Z"}, not ${AxisNames.of(axis)}.")
        val value = guideArguments.double("value") ?: toolError("The value of a guide is required.")
        Guide(id = newId(), axis = axis, valueUm = McpUnits.lengthToUm(value, units), createdOn = now)
    }
    val kept = if (replace) emptyList() else workspace.guides
    val added = mutableListOf<Guide>()
    val skipped = mutableListOf<Guide>()
    wanted.forEach { guide ->
        val same = (kept + added).any { other -> other.axis == guide.axis && other.valueUm == guide.valueUm }
        if (same) skipped += guide else added += guide
    }
    val deleted = if (replace) workspace.guides else emptyList()
    val counts = "${PopulateJson.count(added.size, "guide")} added, ${PopulateJson.count(deleted.size, "guide")} deleted, ${PopulateJson.count(skipped.size, "guide")} skipped"
    val structured = buildJsonObject {
        put("operation", if (replace) "REPLACE_ALL" else "ADD")
        put("added", added.size)
        put("deleted", deleted.size)
        put("skippedCount", skipped.size)
        put("units", units)
        put("toImport", buildJsonArray { added.forEachIndexed { index, guide -> add(previewGuide(index + 1, guide, "Add", units)) } })
        put("skipped", buildJsonArray { skipped.forEachIndexed { index, guide -> add(previewGuide(added.size + index + 1, guide, "AlreadyHere", units)) } })
        put("warnings", JsonArray(emptyList()))
    }
    if (arguments.preview) return@populateTool ToolResult.Ok("Preview: $counts", structured)
    deps.confirm(call, risky = replace, "Put guides", "${if (replace) "REPLACE_ALL" else "ADD"} guides on \"${workspace.name}\" of ${profile.name}", listOf(counts))
        ?.let { return@populateTool it }
    if (deleted.isNotEmpty()) deps.machines.deleteGuides(deleted.map(Guide::id))
    added.forEach { guide -> deps.machines.saveGuide(workspace.id, guide).valueOrError() }
    ToolResult.Ok(counts, structured)
}

private fun previewGuide(row: Int, guide: Guide, action: String, units: String): JsonObject = buildJsonObject {
    put("row", row)
    put("label", "Guide ${AxisNames.of(guide.axis)}")
    put("action", action)
    WorkspaceJson.guide(guide, units).forEach { (key, element) -> put(key, element) }
}
