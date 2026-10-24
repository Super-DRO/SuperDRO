package org.superdro.core.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.superdro.core.model.McpScope

/** The MCP headers of one `POST /mcp`, or null for each header that the request does not have. */
data class McpHeaders(
    val protocolVersion: String? = null,
    val method: String? = null,
    val name: String? = null,
)

/** The answer of the protocol layer to one `POST /mcp`. */
sealed interface ProtocolReply {
    /** `202 Accepted` with no body: the message was a notification. */
    data object Accepted : ProtocolReply

    /** One JSON-RPC response with the HTTP [status]. */
    data class Json(val status: Int, val body: JsonObject) : ProtocolReply

    /**
     * An SSE stream: [run] sends the notifications through `notify` and gives the final
     * response as the last event.
     */
    class Stream(val run: suspend (notify: suspend (JsonObject) -> Unit) -> JsonObject) : ProtocolReply
}

/**
 * The outcome of one `POST /mcp`, for the reply and for the audit record.
 *
 * @property method The JSON-RPC method, or null when the body was not readable.
 * @property tool The tool of a `tools/call`, or null.
 * @property argumentsJson The arguments of a `tools/call` of a tool that is not a read tool, or null.
 */
data class ProtocolOutcome(
    val reply: ProtocolReply,
    val era: McpEra,
    val method: String?,
    val tool: String?,
    val argumentsJson: String?,
)

/**
 * The MCP methods on JSON-RPC, in the two eras:
 * - Modern (2026-07-28): stateless, with the `_meta` protocol fields and the header checks.
 * - Legacy (2025-11-25 and earlier): the `initialize` handshake with no session.
 *
 * The layer has no socket. [handle] takes the body and the headers of one `POST /mcp` and gives
 * the reply.
 *
 * @param toolSets Gives the tool sets at each request. The tools come in the order of the sets.
 * @param scopes Gives the scopes that are on. A tool of a scope that is off is not offered.
 */
class McpProtocol(
    private val appVersion: String,
    private val toolSets: () -> List<McpToolSet>,
    private val scopes: () -> Set<McpScope>,
) {

    /** The tools of all sets, in order. Throws when two tools have one name. */
    fun allTools(): List<McpTool> = checkNames(toolSets())

    /** The offered tools: the tools whose scope is on. */
    fun offeredTools(): List<McpTool> {
        val on = scopes()
        return allTools().filter { tool -> tool.spec.scope in on }
    }

    /** The resources of all sets, in order. */
    fun allResources(): List<Pair<McpResourceSpec, McpResourceReader>> = toolSets().flatMap { set -> set.resources }

    /** Handles one `POST /mcp` from [host]. */
    suspend fun handle(body: String, headers: McpHeaders, host: String): ProtocolOutcome {
        val message = try {
            JsonRpc.parse(body)
        } catch (exception: JsonRpcException) {
            return ProtocolOutcome(ProtocolReply.Json(400, JsonRpc.error(null, exception)), McpEra.Legacy, null, null, null)
        }
        val metaVersion = message.metaText(META_PROTOCOL_VERSION)
        val era = if (message.method == METHOD_INITIALIZE || metaVersion == null) McpEra.Legacy else McpEra.Modern
        val client = McpClient(host, era)
        val tool = if (message.method == METHOD_TOOLS_CALL) (message.params["name"] as? JsonPrimitive)?.contentOrNull else null
        val arguments = if (tool != null && recordsArguments(tool)) message.params["arguments"]?.let { McpJson.encodeToString(JsonElement.serializer(), it) } else null
        val reply = try {
            if (era == McpEra.Modern && !message.isNotification) checkModern(message, metaVersion!!, headers)
            dispatch(message, client)
        } catch (exception: JsonRpcException) {
            ProtocolReply.Json(httpStatus(exception.code, era), JsonRpc.error(message.id, exception))
        }
        return ProtocolOutcome(reply, era, message.method, tool, arguments)
    }

    private fun recordsArguments(toolName: String): Boolean =
        allToolsOrEmpty().firstOrNull { tool -> tool.spec.name == toolName }?.spec?.scope != McpScope.Read

    private fun allToolsOrEmpty(): List<McpTool> = runCatching { allTools() }.getOrDefault(emptyList())

    /** The checks of the modern era: the version, and the headers against the body. */
    private fun checkModern(message: JsonRpcMessage, metaVersion: String, headers: McpHeaders) {
        if (metaVersion != MODERN_VERSION) {
            throw JsonRpcException(
                JsonRpcCodes.UNSUPPORTED_PROTOCOL_VERSION,
                "Unsupported protocol version",
                buildJsonObject {
                    put("supported", JsonArray(SUPPORTED_VERSIONS.map(::JsonPrimitive)))
                    put("requested", metaVersion)
                },
            )
        }
        val headerVersion = headers.protocolVersion
            ?: throw mismatch("the header MCP-Protocol-Version is missing")
        if (headerVersion != metaVersion) throw mismatch("MCP-Protocol-Version header value '$headerVersion' does not match body value '$metaVersion'")
        val headerMethod = headers.method ?: throw mismatch("the header Mcp-Method is missing")
        if (headerMethod != message.method) throw mismatch("Mcp-Method header value '$headerMethod' does not match body value '${message.method}'")
        val nameKey = when (message.method) {
            METHOD_TOOLS_CALL -> "name"
            METHOD_RESOURCES_READ -> "uri"
            METHOD_PROMPTS_GET -> "name"
            else -> null
        }
        if (nameKey != null) {
            val bodyName = (message.params[nameKey] as? JsonPrimitive)?.contentOrNull
            val headerName = headers.name ?: throw mismatch("the header Mcp-Name is missing")
            val decoded = Http.decodeHeaderValue(headerName) ?: throw mismatch("the header Mcp-Name is not valid")
            if (bodyName != null && decoded != bodyName) throw mismatch("Mcp-Name header value '$decoded' does not match body value '$bodyName'")
        }
    }

    private fun mismatch(detail: String): JsonRpcException = JsonRpcException(JsonRpcCodes.HEADER_MISMATCH, "Header mismatch: $detail")

    /** The HTTP status of a JSON-RPC error [code] in [era]. */
    private fun httpStatus(code: Int, era: McpEra): Int = when (code) {
        JsonRpcCodes.PARSE_ERROR, JsonRpcCodes.INVALID_REQUEST, JsonRpcCodes.HEADER_MISMATCH, JsonRpcCodes.UNSUPPORTED_PROTOCOL_VERSION -> 400
        JsonRpcCodes.METHOD_NOT_FOUND -> if (era == McpEra.Modern) 404 else 200
        else -> 200
    }

    private suspend fun dispatch(message: JsonRpcMessage, client: McpClient): ProtocolReply {
        if (message.isNotification) return ProtocolReply.Accepted
        val id = message.id!!
        val result: JsonObject = when (message.method) {
            METHOD_INITIALIZE -> initialize(message)
            METHOD_PING -> JsonObject(emptyMap())
            METHOD_SERVER_DISCOVER -> if (client.era == McpEra.Modern) discover() else throw methodNotFound(message.method)
            METHOD_TOOLS_LIST -> toolsList(client.era)
            METHOD_TOOLS_CALL -> return toolsCall(message, client)
            METHOD_RESOURCES_LIST -> resourcesList(client.era)
            METHOD_RESOURCES_TEMPLATES_LIST -> resourceTemplatesList(client.era)
            METHOD_RESOURCES_READ -> resourcesRead(message, client.era)
            else -> throw methodNotFound(message.method)
        }
        return ProtocolReply.Json(200, JsonRpc.success(id, decorate(result, client.era)))
    }

    private fun methodNotFound(method: String) = JsonRpcException(JsonRpcCodes.METHOD_NOT_FOUND, "Method not found: $method")

    /** Adds `resultType` and the server info to a result of the modern era. */
    private fun decorate(result: JsonObject, era: McpEra): JsonObject {
        if (era == McpEra.Legacy) return result
        return JsonObject(
            linkedMapOf<String, JsonElement>("resultType" to JsonPrimitive("complete")) + result +
                ("_meta" to buildJsonObject { put(META_SERVER_INFO, serverInfo()) }),
        )
    }

    private fun serverInfo(): JsonObject = buildJsonObject {
        put("name", SERVER_NAME)
        put("title", SERVER_TITLE)
        put("version", appVersion)
    }

    private fun capabilities(): JsonObject = buildJsonObject {
        put("tools", buildJsonObject { put("listChanged", false) })
        put("resources", buildJsonObject { put("subscribe", false); put("listChanged", false) })
    }

    private fun cacheHints(ttlMs: Long): Map<String, JsonElement> =
        mapOf("ttlMs" to JsonPrimitive(ttlMs), "cacheScope" to JsonPrimitive(CACHE_SCOPE))

    private fun initialize(message: JsonRpcMessage): JsonObject {
        val requested = (message.params["protocolVersion"] as? JsonPrimitive)?.contentOrNull
        val version = if (requested != null && requested in LEGACY_VERSIONS) requested else LEGACY_DEFAULT_VERSION
        return buildJsonObject {
            put("protocolVersion", version)
            put("capabilities", capabilities())
            put("serverInfo", serverInfo())
            put("instructions", INSTRUCTIONS)
        }
    }

    private fun discover(): JsonObject = buildJsonObject {
        put("supportedVersions", JsonArray(SUPPORTED_VERSIONS.map(::JsonPrimitive)))
        put("capabilities", capabilities())
        put("instructions", INSTRUCTIONS)
        cacheHints(LIST_TTL_MS).forEach { (key, value) -> put(key, value) }
    }

    private fun toolsList(era: McpEra): JsonObject = buildJsonObject {
        put("tools", JsonArray(offeredTools().map { tool -> toolJson(tool.spec) }))
        if (era == McpEra.Modern) cacheHints(LIST_TTL_MS).forEach { (key, value) -> put(key, value) }
    }

    private fun toolJson(spec: McpToolSpec): JsonObject = buildJsonObject {
        put("name", spec.name)
        put("title", spec.title)
        put("description", spec.description)
        put("inputSchema", spec.inputSchema)
        put(
            "annotations",
            buildJsonObject {
                put("title", spec.title)
                put("readOnlyHint", spec.annotations.readOnlyHint)
                put("destructiveHint", spec.annotations.destructiveHint)
                put("idempotentHint", spec.annotations.idempotentHint)
                put("openWorldHint", spec.annotations.openWorldHint)
            },
        )
        if (spec.requiresUserInteraction) put("_meta", buildJsonObject { put(META_REQUIRES_USER_INTERACTION, true) })
    }

    private suspend fun toolsCall(message: JsonRpcMessage, client: McpClient): ProtocolReply {
        val id = message.id!!
        val name = (message.params["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw JsonRpcException(JsonRpcCodes.INVALID_PARAMS, "The tool name is missing")
        val arguments = when (val value = message.params["arguments"]) {
            null -> JsonObject(emptyMap())
            is JsonObject -> value
            else -> throw JsonRpcException(JsonRpcCodes.INVALID_PARAMS, "The arguments must be an object")
        }
        val tool = offeredTools().firstOrNull { tool -> tool.spec.name == name }
            ?: throw JsonRpcException(JsonRpcCodes.INVALID_PARAMS, "The tool is not available")
        val progressToken = message.meta["progressToken"] as? JsonPrimitive
        if (progressToken == null) {
            val result = runTool(tool, name, arguments, client) { _, _, _ -> }
            return ProtocolReply.Json(200, JsonRpc.success(id, decorate(result, client.era)))
        }
        return ProtocolReply.Stream { notify ->
            val result = runTool(tool, name, arguments, client) { progress, total, text ->
                notify(
                    JsonRpc.notification(
                        "notifications/progress",
                        buildJsonObject {
                            put("progressToken", progressToken)
                            put("progress", progress)
                            if (total != null) put("total", total)
                            if (text != null) put("message", text)
                        },
                    ),
                )
            }
            JsonRpc.success(id, decorate(result, client.era))
        }
    }

    /** Runs the handler. An exception gives `isError: true` with the message. */
    private suspend fun runTool(
        tool: McpTool,
        name: String,
        arguments: JsonObject,
        client: McpClient,
        progress: suspend (Double, Double?, String?) -> Unit,
    ): JsonObject {
        val result = try {
            tool.handler.call(ToolCall(name, arguments, client, progress))
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            ToolResult.Error(exception.message ?: "The tool failed: ${exception::class.simpleName}")
        }
        return toolResultJson(result)
    }

    private fun resourcesList(era: McpEra): JsonObject = buildJsonObject {
        put(
            "resources",
            JsonArray(
                allResources().map { (spec, _) -> spec }.filter { spec -> !spec.isTemplate }.map { spec ->
                    buildJsonObject {
                        put("uri", spec.uri)
                        put("name", spec.name)
                        put("title", spec.title)
                        put("description", spec.description)
                        put("mimeType", spec.mimeType)
                    }
                },
            ),
        )
        if (era == McpEra.Modern) cacheHints(LIST_TTL_MS).forEach { (key, value) -> put(key, value) }
    }

    private fun resourceTemplatesList(era: McpEra): JsonObject = buildJsonObject {
        put(
            "resourceTemplates",
            JsonArray(
                allResources().map { (spec, _) -> spec }.filter { spec -> spec.isTemplate }.map { spec ->
                    buildJsonObject {
                        put("uriTemplate", spec.uri)
                        put("name", spec.name)
                        put("title", spec.title)
                        put("description", spec.description)
                        put("mimeType", spec.mimeType)
                    }
                },
            ),
        )
        if (era == McpEra.Modern) cacheHints(LIST_TTL_MS).forEach { (key, value) -> put(key, value) }
    }

    private suspend fun resourcesRead(message: JsonRpcMessage, era: McpEra): JsonObject {
        val uri = (message.params["uri"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw JsonRpcException(JsonRpcCodes.INVALID_PARAMS, "The uri is missing")
        val notFound = JsonRpcException(
            if (era == McpEra.Modern) JsonRpcCodes.INVALID_PARAMS else LEGACY_RESOURCE_NOT_FOUND,
            "Resource not found",
            buildJsonObject { put("uri", uri) },
        )
        val entry = allResources().firstOrNull { (spec, _) -> spec.matches(uri) } ?: throw notFound
        val content = entry.second.read(uri) ?: throw notFound
        return buildJsonObject {
            put(
                "contents",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("uri", content.uri)
                            put("mimeType", content.mimeType)
                            put("text", content.text)
                        },
                    ),
                ),
            )
            if (era == McpEra.Modern) cacheHints(0L).forEach { (key, value) -> put(key, value) }
        }
    }

    companion object {
        const val SERVER_NAME: String = "superdro"
        const val SERVER_TITLE: String = "SuperDRO"
        const val MODERN_VERSION: String = "2026-07-28"
        const val LEGACY_DEFAULT_VERSION: String = "2025-11-25"

        /** The legacy versions that `initialize` answers with the version of the client. */
        val LEGACY_VERSIONS: List<String> = listOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")

        /** The versions in the `supported` list of the error `-32022` and of `server/discover`. */
        val SUPPORTED_VERSIONS: List<String> = listOf(MODERN_VERSION) + LEGACY_VERSIONS

        const val META_PROTOCOL_VERSION: String = "io.modelcontextprotocol/protocolVersion"
        const val META_SERVER_INFO: String = "io.modelcontextprotocol/serverInfo"
        const val META_REQUIRES_USER_INTERACTION: String = "anthropic/requiresUserInteraction"

        const val METHOD_INITIALIZE: String = "initialize"
        const val METHOD_PING: String = "ping"
        const val METHOD_SERVER_DISCOVER: String = "server/discover"
        const val METHOD_TOOLS_LIST: String = "tools/list"
        const val METHOD_TOOLS_CALL: String = "tools/call"
        const val METHOD_RESOURCES_LIST: String = "resources/list"
        const val METHOD_RESOURCES_TEMPLATES_LIST: String = "resources/templates/list"
        const val METHOD_RESOURCES_READ: String = "resources/read"
        const val METHOD_PROMPTS_GET: String = "prompts/get"

        /** The `ttlMs` of the list results in the modern era. */
        const val LIST_TTL_MS: Long = 60_000L

        /** The `cacheScope` of the results: the token decides what a client sees. */
        const val CACHE_SCOPE: String = "private"

        /** The code of "Resource not found" in the legacy era (2025-11-25 and earlier). */
        const val LEGACY_RESOURCE_NOT_FOUND: Int = -32002

        const val INSTRUCTIONS: String =
            "SuperDRO is a digital readout for machine tools. The tools read and populate the machine profiles, " +
                "the workspaces, and the tool libraries of the tablet. Lengths are in the units of the call (mm, inch, or um)."

        /** Checks that no two tools have one name. Gives the tools in order. */
        fun checkNames(sets: List<McpToolSet>): List<McpTool> {
            val tools = sets.flatMap { set -> set.tools }
            val duplicates = tools.groupBy { tool -> tool.spec.name }.filter { (_, group) -> group.size > 1 }.keys
            require(duplicates.isEmpty()) { "Two tools have one name: ${duplicates.sorted().joinToString()}" }
            val uris = sets.flatMap { set -> set.resources }.map { (spec, _) -> spec.uri }
            val duplicateUris = uris.groupBy { uri -> uri }.filter { (_, group) -> group.size > 1 }.keys
            require(duplicateUris.isEmpty()) { "Two resources have one URI: ${duplicateUris.sorted().joinToString()}" }
            return tools
        }

        /** The JSON of a [ToolResult]: the content block, the structured content, and `isError`. */
        fun toolResultJson(result: ToolResult): JsonObject = buildJsonObject {
            when (result) {
                is ToolResult.Ok -> {
                    put("content", JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", result.text) })))
                    if (result.structured != null) put("structuredContent", result.structured)
                    put("isError", false)
                }
                is ToolResult.Error -> {
                    put("content", JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", result.message) })))
                    put("isError", true)
                }
            }
        }
    }
}
