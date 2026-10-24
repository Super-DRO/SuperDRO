package org.superdro.core.mcp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.superdro.core.model.McpScope

/** A tool that gives [result], or runs [body]. */
fun tool(
    name: String,
    scope: McpScope = McpScope.Read,
    requiresUserInteraction: Boolean = false,
    body: suspend (ToolCall) -> ToolResult = { ToolResult.Ok("$name ok") },
): McpTool = McpTool(
    McpToolSpec(
        name = name,
        title = name.replace('_', ' '),
        description = "The tool $name.",
        inputSchema = objectSchema { string("machine") },
        scope = scope,
        annotations = ToolAnnotations(readOnlyHint = scope == McpScope.Read, destructiveHint = scope != McpScope.Read),
        requiresUserInteraction = requiresUserInteraction,
    ),
    McpToolHandler { call -> body(call) },
)

/** A resource with a fixed text, or a template whose text is the URI. */
fun resource(uri: String, isTemplate: Boolean = false, text: (String) -> String? = { candidate -> "{\"uri\":\"$candidate\"}" }): Pair<McpResourceSpec, McpResourceReader> =
    McpResourceSpec(uri, uri.substringAfterLast('/'), uri, "The resource $uri.", isTemplate = isTemplate) to
        McpResourceReader { candidate -> text(candidate)?.let { content -> ResourceContent(candidate, "application/json", content) } }

/** The default tool set of the tests: two read tools, one populate tool, one resource, one template. */
fun sampleToolSet(): McpToolSet = ListToolSet(
    tools = listOf(
        tool("list_machines"),
        tool("get_app_info") { call -> ToolResult.Ok("SuperDRO", buildJsonObject { put("client", call.client.host) }) },
        tool("put_tools", McpScope.Populate, requiresUserInteraction = true),
    ),
    resources = listOf(
        resource("superdro://machines"),
        resource("superdro://machines/{machine}/tools", isTemplate = true) { uri -> if (uri.contains("missing")) null else "{\"tools\":[]}" },
    ),
)

/** The `_meta` of a modern request. */
fun modernMeta(version: String = McpProtocol.MODERN_VERSION, progressToken: String? = null): JsonObject = buildJsonObject {
    put(McpProtocol.META_PROTOCOL_VERSION, version)
    put("io.modelcontextprotocol/clientInfo", buildJsonObject { put("name", "test"); put("version", "1") })
    put("io.modelcontextprotocol/clientCapabilities", buildJsonObject {})
    if (progressToken != null) put("progressToken", progressToken)
}

/** The JSON text of a request. */
fun requestJson(method: String, id: Int? = 1, params: JsonObject? = null): String = McpJson.encodeToString(
    JsonElement.serializer(),
    buildJsonObject {
        put("jsonrpc", "2.0")
        if (id != null) put("id", id)
        put("method", method)
        if (params != null) put("params", params)
    },
)

/** The headers of a modern request. */
fun modernHeaders(method: String, name: String? = null): McpHeaders = McpHeaders(McpProtocol.MODERN_VERSION, method, name)

/** A file store in memory. */
class FakeFileStore : McpFileStore {
    val uploads = mutableMapOf<String, StoredFile>()
    val downloads = mutableMapOf<String, StoredFile>()
    private var next = 1

    override suspend fun putUpload(contentType: String, bytes: ByteArray): UploadReceipt {
        val id = "up${next++}"
        uploads[id] = StoredFile(id, contentType, bytes)
        return UploadReceipt(id, bytes.size.toLong(), "sha-$id")
    }

    override suspend fun upload(fileId: String): StoredFile? = uploads[fileId]

    override suspend fun putDownload(fileName: String, contentType: String, bytes: ByteArray): String {
        val id = "down${next++}"
        downloads[id] = StoredFile(id, contentType, bytes)
        return id
    }

    override suspend fun download(fileId: String): StoredFile? = downloads[fileId]
}

/** One HTTP response of the fake client. */
data class TestResponse(val status: Int, val headers: Map<String, String>, val body: String) {
    val json: JsonObject get() = McpJson.parseToJsonElement(body) as JsonObject
}

/** Reads the HTTP responses of a [FakeLink], one after the other. */
class TestClient(val link: FakeLink) {
    private var consumed = 0

    /** Sends one request. The body gets `Content-Length`. */
    fun send(
        method: String,
        path: String,
        body: String? = null,
        headers: List<Pair<String, String>> = emptyList(),
        host: String? = "192.168.1.20:5810",
        contentLength: Boolean = true,
    ) {
        val text = buildString {
            append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
            if (host != null) append("Host: ").append(host).append("\r\n")
            headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            if (body != null && contentLength) append("Content-Length: ").append(body.encodeToByteArray().size).append("\r\n")
            append("\r\n")
            if (body != null) append(body)
        }
        link.send(text.encodeToByteArray())
    }

    /** All bytes that the server wrote after the last [response]. */
    fun pending(): String {
        val all = link.receivedText
        return all.substring(minOf(consumed, all.length))
    }

    /** The next full response. A response with `Content-Length` ends after the body. */
    fun response(): TestResponse {
        val text = pending()
        val headEnd = text.indexOf("\r\n\r\n")
        require(headEnd >= 0) { "No response head in: $text" }
        val lines = text.substring(0, headEnd).split("\r\n")
        val status = lines[0].split(' ')[1].toInt()
        val headers = lines.drop(1).associate { line -> line.substringBefore(':').trim().lowercase() to line.substringAfter(':').trim() }
        val length = headers["content-length"]?.toInt()
        val bodyStart = headEnd + 4
        val body = if (length != null) text.substring(bodyStart, bodyStart + length) else text.substring(bodyStart)
        consumed += bodyStart + body.length
        return TestResponse(status, headers, body)
    }
}

/** The JSON-RPC request with the headers of a full HTTP request to `/mcp`. */
fun TestClient.mcp(
    json: String,
    token: String? = TOKEN,
    extra: List<Pair<String, String>> = emptyList(),
    host: String? = "192.168.1.20:5810",
    contentType: String? = "application/json",
) {
    val headers = buildList {
        if (contentType != null) add("Content-Type" to contentType)
        if (token != null) add("Authorization" to "Bearer $token")
        addAll(extra)
    }
    send("POST", "/mcp", json, headers, host)
}

const val TOKEN: String = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
