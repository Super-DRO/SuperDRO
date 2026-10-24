package org.superdro.core.mcp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.superdro.core.model.McpScope

/**
 * The behavior hints of a tool (MCP `ToolAnnotations`). A client treats them as hints, not as
 * rules.
 */
data class ToolAnnotations(
    val readOnlyHint: Boolean = false,
    val destructiveHint: Boolean = true,
    val idempotentHint: Boolean = false,
    val openWorldHint: Boolean = false,
)

/**
 * The description of one tool.
 *
 * @property name The stable name in `snake_case`, 1 to 64 characters.
 * @property inputSchema The JSON Schema (draft 2020-12 subset) of the arguments. See [objectSchema].
 * @property scope The server offers the tool only when this scope is on.
 * @property requiresUserInteraction Sets `_meta["anthropic/requiresUserInteraction"] = true` on the tool.
 */
data class McpToolSpec(
    val name: String,
    val title: String,
    val description: String,
    val inputSchema: JsonObject,
    val scope: McpScope,
    val annotations: ToolAnnotations,
    val requiresUserInteraction: Boolean = false,
) {
    init {
        require(isValidName(name)) { "The tool name \"$name\" is not 1 to 64 characters of a-z, 0-9, and _." }
    }

    companion object {
        /** Gives true when [name] has 1 to 64 characters of `a-z`, `0-9`, and `_`. */
        fun isValidName(name: String): Boolean =
            name.length in 1..64 && name.all { char -> char in 'a'..'z' || char in '0'..'9' || char == '_' }
    }
}

/** The protocol era of one request. */
enum class McpEra {
    /** Revision 2026-07-28: stateless, with the `_meta` protocol fields. */
    Modern,

    /** Revision 2025-11-25 and earlier: the `initialize` handshake. */
    Legacy,
}

/** The caller of one tool call. */
data class McpClient(val host: String, val era: McpEra)

/**
 * One call of a tool.
 *
 * @property progress Sends `notifications/progress` when the client gave a `progressToken`. Without
 *   a token, the call has no effect.
 */
class ToolCall(
    val name: String,
    val arguments: JsonObject,
    val client: McpClient,
    val progress: suspend (progress: Double, total: Double?, message: String?) -> Unit,
)

/** The result of one tool call. */
sealed interface ToolResult {
    /** A success. [text] is the content block; [structured] is `structuredContent` or null. */
    data class Ok(val text: String, val structured: JsonElement? = null) : ToolResult

    /** `isError: true` with [message]. */
    data class Error(val message: String) : ToolResult
}

/** Runs one tool. An exception gives [ToolResult.Error] with the message of the exception. */
fun interface McpToolHandler {
    suspend fun call(call: ToolCall): ToolResult
}

/** One tool: its description and its handler. */
data class McpTool(val spec: McpToolSpec, val handler: McpToolHandler)

/**
 * The description of one resource.
 *
 * @property uri `superdro://...`. A template has `{name}` parts, for example
 *   `superdro://machines/{machine}/tools`.
 * @property isTemplate True for a URI template. The server lists it in `resources/templates/list`.
 */
data class McpResourceSpec(
    val uri: String,
    val name: String,
    val title: String,
    val description: String,
    val mimeType: String = "application/json",
    val isTemplate: Boolean = false,
) {
    /** Gives true when [candidate] is this URI, or an instance of this template. */
    fun matches(candidate: String): Boolean {
        if (!isTemplate) return candidate == uri
        val pattern = uri.split('/')
        val parts = candidate.split('/')
        if (pattern.size != parts.size) return false
        return pattern.indices.all { index ->
            val expected = pattern[index]
            val isVariable = expected.length > 2 && expected.startsWith("{") && expected.endsWith("}")
            if (isVariable) parts[index].isNotEmpty() else parts[index] == expected
        }
    }

    /**
     * Gives the values of the `{name}` parts of this template in [candidate], by name, or an empty
     * map for a resource that is not a template. [candidate] must match ([matches]).
     */
    fun variables(candidate: String): Map<String, String> {
        if (!isTemplate) return emptyMap()
        val pattern = uri.split('/')
        val parts = candidate.split('/')
        return pattern.indices.mapNotNull { index ->
            val expected = pattern[index]
            if (expected.length > 2 && expected.startsWith("{") && expected.endsWith("}")) {
                expected.substring(1, expected.length - 1) to parts[index]
            } else {
                null
            }
        }.toMap()
    }
}

/** The content of one resource. */
data class ResourceContent(val uri: String, val mimeType: String, val text: String)

/** Reads one resource. Null means that the resource is not there. */
fun interface McpResourceReader {
    suspend fun read(uri: String): ResourceContent?
}

/**
 * One group of tools and resources. The server merges the sets. A duplicate tool name or a
 * duplicate resource URI is an error at the start.
 */
interface McpToolSet {
    val tools: List<McpTool>
    val resources: List<Pair<McpResourceSpec, McpResourceReader>>
}

/** A [McpToolSet] from two lists. */
class ListToolSet(
    override val tools: List<McpTool>,
    override val resources: List<Pair<McpResourceSpec, McpResourceReader>> = emptyList(),
) : McpToolSet
