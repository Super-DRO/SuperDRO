package org.superdro.core.ui.mcp.populate

import kotlin.time.Instant
import kotlinx.serialization.json.JsonObject
import org.superdro.core.dataoutput.WallClock
import org.superdro.core.mcp.ListToolSet
import org.superdro.core.mcp.McpRules
import org.superdro.core.mcp.McpTool
import org.superdro.core.mcp.McpToolHandler
import org.superdro.core.mcp.McpToolSet
import org.superdro.core.mcp.McpToolSpec
import org.superdro.core.mcp.ToolAnnotations
import org.superdro.core.mcp.ToolCall
import org.superdro.core.mcp.ToolResult
import org.superdro.core.mcp.WriteGate
import org.superdro.core.mcp.WriteRequest
import org.superdro.core.mcp.McpFileStore
import org.superdro.core.mcp.toolError
import org.superdro.core.model.DxfImportLimits
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.McpConfig
import org.superdro.core.model.McpScope
import org.superdro.core.store.StoreIOProvider
import org.superdro.core.store.StoreResult
import org.superdro.core.store.repository.DrawingRepository
import org.superdro.core.store.repository.MachineRepository
import org.superdro.core.store.repository.ToolRepository

/**
 * The dependencies of the populate tools.
 *
 * @property files The uploads of the clients and the downloads of the tools.
 * @property gate The confirmation on the tablet.
 * @property config The current MCP configuration: the confirmation rule.
 * @property onlineMachines The IDs of the profiles with a connected adapter.
 * @property appVersion The app version in the export files.
 * @property clock The wall clock of the stored times and the file names.
 * @property storeIO The files of the app: the reference image files `images/<uuid>.jpg`.
 * @property dxfLimits The DXF import limits of the app settings.
 */
class PopulateDeps(
    val machines: MachineRepository,
    val tools: ToolRepository,
    val drawings: DrawingRepository,
    val files: McpFileStore,
    val gate: WriteGate,
    val config: () -> McpConfig,
    val onlineMachines: () -> Set<String>,
    val appVersion: String,
    val clock: WallClock,
    val storeIO: StoreIOProvider,
    val dxfLimits: () -> DxfImportLimits = { DxfImportLimits() },
) {
    val lookup: MachineLookup = MachineLookup(machines, tools)

    /** The time now as an [Instant]. */
    fun now(): Instant = Instant.fromEpochMilliseconds(clock.nowEpochMs())

    /** True when the profile has a connected adapter. */
    fun isOnline(profile: MachineProfile): Boolean = profile.id in onlineMachines()

    /** True when [workspaceId] is the active workspace of an online machine: a write to it can change what the operator sees. */
    fun isActiveOnline(profile: MachineProfile, workspaceId: String): Boolean =
        isOnline(profile) && (profile.activeWorkspace?.id ?: profile.activeWorkspaceId) == workspaceId

    /**
     * Asks the operator when the rule needs it: [McpRules.needsConfirmation] with the
     * confirmation of [config] and [risky]. Gives the tool error of a refused or unanswered
     * request, or null when the write can go on. The caller calls it before the write.
     */
    suspend fun confirm(call: ToolCall, risky: Boolean, title: String, summary: String, details: List<String>): ToolResult.Error? {
        if (!McpRules.needsConfirmation(config().confirmation, risky)) return null
        val request = WriteRequest(title, summary, details + "Client ${call.client.host}", call.client)
        return gate.confirm(request).toolError()
    }
}

/** The populate tool set: the workspace, drawing, image, tool library, and profile tools. */
fun populateToolSet(deps: PopulateDeps): McpToolSet = ListToolSet(
    tools = workspaceTools(deps) + drawingTools(deps) + imageTools(deps) + toolLibraryTools(deps) + profileTools(deps),
)

/**
 * Makes one populate tool. The handler gets the arguments; a [ToolError] gives [ToolResult.Error].
 *
 * @param readOnly True for a tool that writes no stored data (an export).
 * @param idempotent The `idempotentHint` of the tool.
 * @param interaction The `requiresUserInteraction` flag: the deletes, `clear_*`, `REPLACE_ALL`, and the profile tools.
 */
internal fun populateTool(
    name: String,
    title: String,
    description: String,
    schema: JsonObject,
    idempotent: Boolean,
    interaction: Boolean = false,
    readOnly: Boolean = false,
    handler: suspend (call: ToolCall, arguments: Arguments) -> ToolResult,
): McpTool = McpTool(
    spec = McpToolSpec(
        name = name,
        title = title,
        description = description,
        inputSchema = schema,
        scope = McpScope.Populate,
        annotations = ToolAnnotations(readOnlyHint = readOnly, destructiveHint = !readOnly, idempotentHint = idempotent),
        requiresUserInteraction = interaction,
    ),
    handler = McpToolHandler { call ->
        try {
            handler(call, Arguments(call.arguments))
        } catch (error: ToolError) {
            ToolResult.Error(error.message ?: "The tool failed.")
        } catch (error: IllegalArgumentException) {
            ToolResult.Error(error.message ?: "The tool failed.")
        }
    },
)

/** The value of a store result, or a [ToolError] with the refusal text. */
internal fun <T> StoreResult<T>.valueOrError(): T = when (this) {
    is StoreResult.Ok -> value
    is StoreResult.Refused -> toolError(refusal.message)
}
