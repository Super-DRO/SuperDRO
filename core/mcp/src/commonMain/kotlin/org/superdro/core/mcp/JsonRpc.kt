package org.superdro.core.mcp

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The JSON of the MCP messages. The server reads unknown keys and writes no default. */
val McpJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    prettyPrint = false
}

/** The JSON-RPC 2.0 error codes, and the codes of the MCP specification. */
object JsonRpcCodes {
    const val PARSE_ERROR: Int = -32700
    const val INVALID_REQUEST: Int = -32600
    const val METHOD_NOT_FOUND: Int = -32601
    const val INVALID_PARAMS: Int = -32602
    const val INTERNAL_ERROR: Int = -32603

    /** The HTTP headers do not match the body, or a required header is missing (2026-07-28). */
    const val HEADER_MISMATCH: Int = -32020

    /** The server does not support the protocol version of the request (2026-07-28). */
    const val UNSUPPORTED_PROTOCOL_VERSION: Int = -32022
}

/**
 * One JSON-RPC message from a client: a request (with [id]) or a notification (no [id]).
 *
 * @property id A string or a number, or null for a notification.
 * @property params The params object, or an empty object when the message has none.
 */
data class JsonRpcMessage(
    val id: JsonElement?,
    val method: String,
    val params: JsonObject,
) {
    val isNotification: Boolean get() = id == null

    /** The `_meta` object of the params, or an empty object. */
    val meta: JsonObject get() = params["_meta"] as? JsonObject ?: JsonObject(emptyMap())

    /** The value of `_meta[key]` as text, or null. */
    fun metaText(key: String): String? = (meta[key] as? JsonPrimitive)?.contentOrNull
}

/** A JSON-RPC error with a code, a message, and optional data. */
class JsonRpcException(val code: Int, message: String, val data: JsonElement? = null) : Exception(message)

/** Reads and writes the JSON-RPC 2.0 messages. */
object JsonRpc {

    /**
     * Reads one message from [text].
     *
     * @throws JsonRpcException with [JsonRpcCodes.PARSE_ERROR] for a text that is not JSON, and
     *   with [JsonRpcCodes.INVALID_REQUEST] for a JSON value that is not a request or a
     *   notification. A batch (an array) is not accepted.
     */
    fun parse(text: String): JsonRpcMessage {
        val element = try {
            McpJson.parseToJsonElement(text)
        } catch (exception: SerializationException) {
            throw JsonRpcException(JsonRpcCodes.PARSE_ERROR, "Parse error")
        } catch (exception: IllegalArgumentException) {
            throw JsonRpcException(JsonRpcCodes.PARSE_ERROR, "Parse error")
        }
        return fromElement(element)
    }

    /** Reads one message from [element]. See [parse]. */
    fun fromElement(element: JsonElement): JsonRpcMessage {
        val obj = element as? JsonObject ?: throw JsonRpcException(JsonRpcCodes.INVALID_REQUEST, "The message must be one JSON object")
        val version = (obj["jsonrpc"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (version != "2.0") throw JsonRpcException(JsonRpcCodes.INVALID_REQUEST, "The field jsonrpc must be \"2.0\"")
        val id = obj["id"]
        if (id != null && (id !is JsonPrimitive || id is JsonNull)) {
            throw JsonRpcException(JsonRpcCodes.INVALID_REQUEST, "The id must be a string or a number")
        }
        val method = (obj["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw JsonRpcException(JsonRpcCodes.INVALID_REQUEST, "The method must be a string")
        val params = when (val value = obj["params"]) {
            null, JsonNull -> JsonObject(emptyMap())
            is JsonObject -> value
            else -> throw JsonRpcException(JsonRpcCodes.INVALID_REQUEST, "The params must be an object")
        }
        if (obj.containsKey("result") || obj.containsKey("error")) {
            throw JsonRpcException(JsonRpcCodes.INVALID_REQUEST, "The server does not accept a response")
        }
        return JsonRpcMessage(id, method, params)
    }

    /** The success response with [result] for the request [id]. */
    fun success(id: JsonElement, result: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }

    /** The error response for the request [id] (null for a message with no readable id). */
    fun error(id: JsonElement?, code: Int, message: String, data: JsonElement? = null): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonNull)
        put(
            "error",
            buildJsonObject {
                put("code", code)
                put("message", message)
                if (data != null) put("data", data)
            },
        )
    }

    /** The error response of [exception] for the request [id]. */
    fun error(id: JsonElement?, exception: JsonRpcException): JsonObject =
        error(id, exception.code, exception.message ?: "Error", exception.data)

    /** A notification with [method] and [params]. */
    fun notification(method: String, params: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", method)
        put("params", params)
    }

    /** The code of the error in [response], or null for a success. */
    fun errorCode(response: JsonObject): Int? =
        (response["error"] as? JsonObject)?.get("code")?.jsonPrimitive?.contentOrNull?.toIntOrNull()

    /** The result object of [response], or null for an error. */
    fun result(response: JsonObject): JsonObject? = response["result"]?.jsonObject
}
