package org.superdro.core.ui.mcp

import kotlin.math.roundToLong
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.superdro.core.mcp.ListToolSet
import org.superdro.core.mcp.McpLimits
import org.superdro.core.mcp.McpProtocol
import org.superdro.core.mcp.McpResourceReader
import org.superdro.core.mcp.McpResourceSpec
import org.superdro.core.mcp.McpTool
import org.superdro.core.mcp.McpToolHandler
import org.superdro.core.mcp.McpToolSet
import org.superdro.core.mcp.McpToolSpec
import org.superdro.core.mcp.ResourceContent
import org.superdro.core.mcp.ToolAnnotations
import org.superdro.core.mcp.ToolCall
import org.superdro.core.mcp.ToolResult
import org.superdro.core.mcp.objectSchema
import org.superdro.core.model.AxisId
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.McpConfig
import org.superdro.core.model.McpScope
import org.superdro.core.model.SubDatum
import org.superdro.core.model.Tool
import org.superdro.core.model.Workspace
import org.superdro.core.store.files.MachineNames
import org.superdro.core.store.files.settings.ProfileFile
import org.superdro.core.store.repository.DrawingRepository
import org.superdro.core.store.repository.MachineRepository
import org.superdro.core.store.repository.ToolRepository

/**
 * The sources of the read tools.
 *
 * @property onlineMachines Gives the IDs of the profiles with an online session.
 * @property selectedProfileId Gives the ID of the profile of the selected tab, or null.
 * @property config Gives the configuration of the server.
 * @property appVersion The version of the app for `get_app_info`.
 */
class ReadToolDeps(
    val machines: MachineRepository,
    val tools: ToolRepository,
    val drawings: DrawingRepository,
    val onlineMachines: () -> Set<String>,
    val selectedProfileId: () -> String?,
    val config: () -> McpConfig,
    val appVersion: String,
)

/** The URI templates of the read resources (plan section 3.9). */
object ReadResourceUris {
    const val MACHINES: String = "superdro://machines"
    const val PROFILE_SETTINGS: String = "superdro://machines/{machine}/profile-settings"
    const val WORKSPACES: String = "superdro://machines/{machine}/workspaces"
    const val WORKSPACE: String = "superdro://machines/{machine}/workspaces/{workspace}"
    const val TOOLS: String = "superdro://machines/{machine}/tools"
}

/**
 * The read tools and the read resources: `list_machines`, `list_workspaces`, `get_workspace`,
 * `list_tools`, `get_profile_settings`, and `get_app_info`, with the scope [McpScope.Read] and
 * `readOnlyHint`. Lengths in the results are in the `units` argument (`mm`, `inch`, or `um`; the
 * default is `mm` for a metric profile, else `inch`). Angles are degrees.
 *
 * `{machine}` is a profile ID or a profile name. `{workspace}` is a workspace ID or a name.
 */
fun readToolSet(deps: ReadToolDeps): McpToolSet {
    val tools = ReadTools(deps)
    val units = ReadUnits.UNITS
    return ListToolSet(
        tools = listOf(
            tool(
                name = "list_machines",
                title = "List machines",
                description = "Lists the machine profiles with the ID, the name, the machine type, the link type, the online state, and the selected tab.",
                schema = objectSchema(),
                idempotent = true,
            ) { tools.listMachines() },
            tool(
                name = "list_workspaces",
                title = "List workspaces",
                description = "Lists the workspaces of a machine with the ID, the name, the plane, the active flag, and the counts.",
                schema = objectSchema { string("machine", required = true, description = "The profile ID or the profile name.") },
                idempotent = true,
            ) { call -> tools.listWorkspaces(call.text("machine")) },
            tool(
                name = "get_workspace",
                title = "Get workspace",
                description = "Gives one workspace: the axis states, the sub-datums, the guides, the drawing summary, and the reference image placement.",
                schema = objectSchema {
                    string("machine", required = true, description = "The profile ID or the profile name.")
                    string("workspace", required = true, description = "The workspace ID or the workspace name.")
                    enumString("units", units, description = "The unit of the lengths in the result. The default is the unit of the profile.")
                },
                idempotent = true,
            ) { call -> tools.getWorkspace(call.text("machine"), call.text("workspace"), call.text("units")) },
            tool(
                name = "list_tools",
                title = "List tools",
                description = "Lists the tool library of a machine (its own library or the shared library) with the IDs, the names, the offsets, and a flag for a tool with an outline.",
                schema = objectSchema {
                    string("machine", required = true, description = "The profile ID or the profile name.")
                    enumString("units", units, description = "The unit of the lengths in the result. The default is the unit of the profile.")
                },
                idempotent = true,
            ) { call -> tools.listTools(call.text("machine"), call.text("units")) },
            tool(
                name = "get_profile_settings",
                title = "Get profile settings",
                description = "Gives the settings object of the SuperDRO profile file: each field of the profile except the identity, the workspaces, the tools, the datums, and the references.",
                schema = objectSchema { string("machine", required = true, description = "The profile ID or the profile name.") },
                idempotent = true,
            ) { call -> tools.getProfileSettings(call.text("machine")) },
            tool(
                name = "get_app_info",
                title = "Get app info",
                description = "Gives the app version, the protocol versions, the scopes that are on, the confirmation setting, and the file limits.",
                schema = objectSchema(),
                idempotent = true,
            ) { tools.getAppInfo() },
        ),
        resources = listOf(
            resource(ReadResourceUris.MACHINES, "machines", "Machines", "The machine profiles, as list_machines.") { tools.listMachines() },
            resource(ReadResourceUris.PROFILE_SETTINGS, "profile-settings", "Profile settings", "The settings of a profile, as get_profile_settings.") { variables ->
                tools.getProfileSettings(variables["machine"])
            },
            resource(ReadResourceUris.WORKSPACES, "workspaces", "Workspaces", "The workspaces of a machine, as list_workspaces.") { variables ->
                tools.listWorkspaces(variables["machine"])
            },
            resource(ReadResourceUris.WORKSPACE, "workspace", "Workspace", "One workspace of a machine, as get_workspace.") { variables ->
                tools.getWorkspace(variables["machine"], variables["workspace"], null)
            },
            resource(ReadResourceUris.TOOLS, "tools", "Tools", "The tool library of a machine, as list_tools.") { variables ->
                tools.listTools(variables["machine"], null)
            },
        ),
    )
}

private fun ToolCall.text(name: String): String? = (arguments[name] as? JsonPrimitive)?.contentOrNull

private fun tool(
    name: String,
    title: String,
    description: String,
    schema: JsonObject,
    idempotent: Boolean,
    handler: suspend (ToolCall) -> ReadResult,
): McpTool = McpTool(
    spec = McpToolSpec(
        name = name,
        title = title,
        description = description,
        inputSchema = schema,
        scope = McpScope.Read,
        annotations = ToolAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = idempotent, openWorldHint = false),
    ),
    handler = McpToolHandler { call -> handler(call).toToolResult() },
)

private fun resource(
    uri: String,
    name: String,
    title: String,
    description: String,
    read: suspend (Map<String, String>) -> ReadResult,
): Pair<McpResourceSpec, McpResourceReader> {
    val spec = McpResourceSpec(uri = uri, name = name, title = title, description = description, isTemplate = '{' in uri)
    val reader = McpResourceReader { candidate ->
        if (!spec.matches(candidate)) return@McpResourceReader null
        when (val result = read(spec.variables(candidate))) {
            is ReadResult.Ok -> ResourceContent(candidate, spec.mimeType, McpJsonText.encode(result.structured))
            is ReadResult.Error -> null
        }
    }
    return spec to reader
}

/** The result of one read: the text and the structured JSON, or an error text. */
internal sealed interface ReadResult {
    data class Ok(val text: String, val structured: JsonObject) : ReadResult
    data class Error(val message: String) : ReadResult

    fun toToolResult(): ToolResult = when (this) {
        is Ok -> ToolResult.Ok(text, structured)
        is Error -> ToolResult.Error(message)
    }
}

/** The JSON text of a resource. */
internal object McpJsonText {
    private val json = kotlinx.serialization.json.Json { prettyPrint = true; prettyPrintIndent = "  " }

    fun encode(element: JsonElement): String = json.encodeToString(JsonElement.serializer(), element)
}

/** The unit conversion at the edge of the read tools. */
internal object ReadUnits {
    /** The valid values of the `units` argument. */
    val UNITS: List<String> = listOf("mm", "inch", "um")

    /** Gives the units of a call: [requested] when it is valid, else the default of [profile]. Null for a bad value. */
    fun of(requested: String?, profile: MachineProfile): String? = when {
        requested == null -> if (profile.metricDefault) "mm" else "inch"
        requested in UNITS -> requested
        else -> null
    }

    /** Converts [um] to [units], rounded to six decimals. */
    fun length(um: Double, units: String): Double = round6(
        when (units) {
            "mm" -> um / 1000.0
            "inch" -> um / 25_400.0
            else -> um
        },
    )

    /** Converts [arcSec] to degrees, rounded to six decimals. */
    fun angle(arcSec: Double): Double = round6(arcSec / 3600.0)

    private fun round6(value: Double): Double = (value * 1_000_000.0).roundToLong() / 1_000_000.0
}

/** The handlers of the read tools. */
internal class ReadTools(private val deps: ReadToolDeps) {

    suspend fun listMachines(): ReadResult {
        val profiles = deps.machines.profiles().first()
        val online = deps.onlineMachines()
        val selected = deps.selectedProfileId()
        val structured = buildJsonObject {
            put("machines", JsonArray(profiles.map { profile -> machineJson(profile, profile.id in online, profile.id == selected) }))
        }
        val text = if (profiles.isEmpty()) {
            "No machine profile."
        } else {
            profiles.joinToString(prefix = "${profiles.size} machine(s): ", separator = ", ") { profile ->
                "${profile.name} (${profile.id}, ${if (profile.id in online) "online" else "offline"}${if (profile.id == selected) ", selected" else ""})"
            }
        }
        return ReadResult.Ok(text, structured)
    }

    suspend fun listWorkspaces(machine: String?): ReadResult {
        val profile = findFullMachine(machine) ?: return noMachine(machine)
        val structured = buildJsonObject {
            put("machine", machineRef(profile))
            put(
                "workspaces",
                JsonArray(
                    profile.workspaces.map { workspace ->
                        buildJsonObject {
                            put("id", workspace.id)
                            put("name", workspace.name)
                            put("plane", workspace.plane.name)
                            put("active", workspace.id == profile.activeWorkspaceId)
                            put("subDatumCount", workspace.subDatums.size)
                            put("guideCount", workspace.guides.size)
                            put("hasDrawing", deps.drawings.loadDrawing(workspace.id) != null)
                            put("hasReferenceImage", workspace.referenceImage != null)
                        }
                    },
                ),
            )
        }
        val text = profile.workspaces.joinToString(prefix = "${profile.workspaces.size} workspace(s) of ${profile.name}: ", separator = ", ") { workspace ->
            "${workspace.name} (${workspace.id}${if (workspace.id == profile.activeWorkspaceId) ", active" else ""})"
        }
        return ReadResult.Ok(text, structured)
    }

    suspend fun getWorkspace(machine: String?, workspaceKey: String?, requestedUnits: String?): ReadResult {
        val profile = findFullMachine(machine) ?: return noMachine(machine)
        val units = ReadUnits.of(requestedUnits, profile) ?: return badUnits(requestedUnits)
        val workspace = findWorkspace(profile, workspaceKey) ?: return ReadResult.Error("No workspace with the ID or the name \"${workspaceKey.orEmpty()}\" on ${profile.name}")
        val drawing = deps.drawings.loadDrawing(workspace.id)
        val structured = buildJsonObject {
            put("machine", machineRef(profile))
            put("units", units)
            put("id", workspace.id)
            put("name", workspace.name)
            put("plane", workspace.plane.name)
            put("active", workspace.id == profile.activeWorkspaceId)
            put("activeSubDatumId", workspace.activeSubDatumId?.let(::JsonPrimitive) ?: JsonNull)
            put("activeToolId", workspace.activeToolId?.let(::JsonPrimitive) ?: JsonNull)
            put("toolX", ReadUnits.length(workspace.toolXUm, units))
            put("toolZ", ReadUnits.length(workspace.toolZUm, units))
            put("toothCount", workspace.toothCount)
            put(
                "graphical",
                buildJsonObject {
                    put("snapToPoint", workspace.graphical.snapToPoint)
                    put("snapToGuide", workspace.graphical.snapToGuide)
                    put("automaticToolOffset", workspace.graphical.automaticToolOffset)
                    put("automaticProximityWarning", workspace.graphical.automaticProximityWarning)
                    put("automaticZero", workspace.graphical.automaticZero)
                    put("automaticSubDatum", workspace.graphical.automaticSubDatum)
                },
            )
            put("axisStates", JsonArray(POSITION_AXES.mapNotNull { axis -> workspace.axisStates[axis]?.let { state -> axisStateJson(axis, state, units) } }))
            put("subDatums", JsonArray(workspace.subDatums.map { point -> subDatumJson(point, units) }))
            put(
                "guides",
                JsonArray(
                    workspace.guides.map { guide ->
                        buildJsonObject {
                            put("id", guide.id)
                            put("axis", guide.axis.name)
                            put("value", ReadUnits.length(guide.valueUm, units))
                        }
                    },
                ),
            )
            put(
                "drawing",
                drawing?.let {
                    buildJsonObject {
                        put("layers", JsonArray(it.layers.map { layer -> JsonPrimitive(layer.name) }))
                        put("shapeCount", it.shapes.size)
                        put("source", it.source?.toString()?.let(::JsonPrimitive) ?: JsonNull)
                    }
                } ?: JsonNull,
            )
            put(
                "referenceImage",
                workspace.referenceImage?.let { image ->
                    buildJsonObject {
                        put("fileName", image.fileName)
                        put("matrix", JsonArray(image.matrix.map(::JsonPrimitive)))
                        put("alpha", image.alpha)
                    }
                } ?: JsonNull,
            )
        }
        val text = "Workspace ${workspace.name} of ${profile.name}: ${workspace.subDatums.size} sub-datum(s), ${workspace.guides.size} guide(s), " +
            "${if (drawing != null) "a drawing with ${drawing.shapes.size} shape(s)" else "no drawing"}, " +
            "${if (workspace.referenceImage != null) "a reference image" else "no reference image"}. Lengths in $units."
        return ReadResult.Ok(text, structured)
    }

    suspend fun listTools(machine: String?, requestedUnits: String?): ReadResult {
        val profile = findMachine(machine) ?: return noMachine(machine)
        val units = ReadUnits.of(requestedUnits, profile) ?: return badUnits(requestedUnits)
        val library = deps.tools.loadLibrary(profile.id)
        val structured = buildJsonObject {
            put("machine", machineRef(profile))
            put("shared", profile.useSharedToolLibrary)
            put("units", units)
            put("tools", JsonArray(library.map { tool -> toolJson(tool, units) }))
        }
        val text = "${library.size} tool(s) in the ${if (profile.useSharedToolLibrary) "shared" else "own"} library of ${profile.name}. Lengths in $units."
        return ReadResult.Ok(text, structured)
    }

    suspend fun getProfileSettings(machine: String?): ReadResult {
        val profile = findMachine(machine) ?: return noMachine(machine)
        val structured = buildJsonObject {
            put("machine", machineRef(profile))
            put("settings", ProfileFile.settingsOf(profile))
        }
        return ReadResult.Ok("The profile settings of ${profile.name} (${MachineNames.name(profile.machineType)}).", structured)
    }

    fun getAppInfo(): ReadResult {
        val config = deps.config()
        val structured = buildJsonObject {
            put("app", McpProtocol.SERVER_TITLE)
            put("version", deps.appVersion)
            put("protocolVersions", JsonArray(McpProtocol.SUPPORTED_VERSIONS.map(::JsonPrimitive)))
            put("scopes", JsonArray(McpScope.entries.filter { scope -> scope in config.scopes }.map { scope -> JsonPrimitive(scope.name) }))
            put("confirmation", config.confirmation.name)
            put("uploadLimitBytes", McpLimits.UPLOAD_BYTES)
            put("fileTtlMs", McpLimits.FILE_TTL_MS)
            put("units", JsonArray(ReadUnits.UNITS.map(::JsonPrimitive)))
        }
        val scopes = McpScope.entries.filter { scope -> scope in config.scopes }.joinToString(", ") { scope -> scope.name }
        return ReadResult.Ok("SuperDRO ${deps.appVersion}. Scopes on: $scopes. Confirmation: ${config.confirmation.name}.", structured)
    }

    // -----------------------------------------------------------------------------------------

    private suspend fun findMachine(key: String?): MachineProfile? {
        if (key.isNullOrBlank()) return null
        return deps.machines.profiles().first().let { profiles -> selectMachine(profiles, key) }
    }

    private suspend fun findFullMachine(key: String?): MachineProfile? {
        val profile = findMachine(key) ?: return null
        return deps.machines.loadFullProfile(profile.id)
    }

    private fun noMachine(key: String?): ReadResult.Error = ReadResult.Error("No machine with the ID or the name \"${key.orEmpty()}\"")

    private fun badUnits(units: String?): ReadResult.Error = ReadResult.Error("The units \"${units.orEmpty()}\" are not mm, inch, or um")

    private fun machineRef(profile: MachineProfile): JsonObject = buildJsonObject {
        put("id", profile.id)
        put("name", profile.name)
    }

    private fun machineJson(profile: MachineProfile, online: Boolean, selected: Boolean): JsonObject = buildJsonObject {
        put("id", profile.id)
        put("name", profile.name)
        put("type", MachineNames.name(profile.machineType))
        put("linkType", profile.adapter?.kind?.name?.let(::JsonPrimitive) ?: JsonNull)
        put("online", online)
        put("selected", selected)
        put("metricDefault", profile.metricDefault)
        put("activeWorkspaceId", profile.activeWorkspaceId)
    }

    private fun axisStateJson(axis: AxisId, state: org.superdro.core.model.AxisState, units: String): JsonObject = buildJsonObject {
        val angular = axis == AxisId.Angular
        fun value(um: Double): Double = if (angular) ReadUnits.angle(um) else ReadUnits.length(um, units)
        put("axis", axis.name)
        put("absoluteOffset", value(state.absoluteOffset))
        put("incrementalOffset", value(state.incrementalOffset))
        put("relative", state.relative)
        put("toolOffset", value(state.toolOffset))
        put("toolOffsetActive", state.toolOffsetActive)
        put("metric", state.metric?.let(::JsonPrimitive) ?: JsonNull)
        put("diameterMode", state.diameterMode)
        put("referenced", state.referenced)
    }

    private fun subDatumJson(point: SubDatum, units: String): JsonObject = buildJsonObject {
        put("id", point.id)
        put("name", point.name)
        put("note", point.note)
        put("tags", JsonArray(point.tags.map(::JsonPrimitive)))
        put("x", point.x?.let { um -> JsonPrimitive(ReadUnits.length(um, units)) } ?: JsonNull)
        put("y", point.y?.let { um -> JsonPrimitive(ReadUnits.length(um, units)) } ?: JsonNull)
        put("z", point.z?.let { um -> JsonPrimitive(ReadUnits.length(um, units)) } ?: JsonNull)
        put("a", point.a?.let { arcSec -> JsonPrimitive(ReadUnits.angle(arcSec)) } ?: JsonNull)
        put("groupKind", point.groupKind?.name?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun toolJson(tool: Tool, units: String): JsonObject = buildJsonObject {
        put("id", tool.id)
        put("kind", tool.kind.name)
        put("name", tool.name)
        put("description", tool.description)
        put("metric", tool.metric)
        put("diameter", ReadUnits.length(tool.diameterUm, units))
        put("toothCount", tool.toothCount)
        put("zOffset", ReadUnits.length(tool.zOffsetUm, units))
        put("xOffset", ReadUnits.length(tool.xOffsetUm, units))
        put("internal", tool.internal)
        put("hasOutline", tool.geometry != null)
    }

    companion object {
        /** The axes of a workspace that the result shows. */
        private val POSITION_AXES: List<AxisId> = listOf(AxisId.X, AxisId.Y, AxisId.Z, AxisId.Angular)

        /** Gives the profile of [key]: the ID first, then the exact name, then the name in any case. */
        fun selectMachine(profiles: List<MachineProfile>, key: String): MachineProfile? {
            val trimmed = key.trim()
            return profiles.firstOrNull { profile -> profile.id == trimmed }
                ?: profiles.firstOrNull { profile -> profile.name == trimmed }
                ?: profiles.firstOrNull { profile -> profile.name.equals(trimmed, ignoreCase = true) }
        }

        /** Gives the workspace of [key] in [profile]: the ID first, then the exact name, then the name in any case. */
        fun findWorkspace(profile: MachineProfile, key: String?): Workspace? {
            if (key.isNullOrBlank()) return null
            val trimmed = key.trim()
            return profile.workspaces.firstOrNull { workspace -> workspace.id == trimmed }
                ?: profile.workspaces.firstOrNull { workspace -> workspace.name == trimmed }
                ?: profile.workspaces.firstOrNull { workspace -> workspace.name.equals(trimmed, ignoreCase = true) }
        }
    }
}

