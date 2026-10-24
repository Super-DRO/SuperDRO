package org.superdro.core.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The JSON-RPC 2.0 messages and the error codes. */
class JsonRpcTest {

    @Test
    fun aRequestHasAnIdAMethodAndParams() {
        val message = JsonRpc.parse("""{"jsonrpc":"2.0","id":7,"method":"tools/list","params":{"cursor":"a"}}""")
        assertEquals(JsonPrimitive(7), message.id)
        assertEquals("tools/list", message.method)
        assertEquals("a", message.params["cursor"]?.jsonPrimitive?.content)
        assertTrue(!message.isNotification)
    }

    @Test
    fun aNotificationHasNoIdAndNoParams() {
        val message = JsonRpc.parse("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        assertNull(message.id)
        assertTrue(message.isNotification)
        assertTrue(message.params.isEmpty())
        assertTrue(message.meta.isEmpty())
    }

    @Test
    fun aTextThatIsNotJsonGivesTheParseError() {
        val exception = assertFailsWith<JsonRpcException> { JsonRpc.parse("{not json") }
        assertEquals(JsonRpcCodes.PARSE_ERROR, exception.code)
    }

    @Test
    fun aBatchANullIdAWrongVersionAndAResponseGiveInvalidRequest() {
        listOf(
            """[{"jsonrpc":"2.0","id":1,"method":"ping"}]""",
            """{"jsonrpc":"2.0","id":null,"method":"ping"}""",
            """{"jsonrpc":"1.0","id":1,"method":"ping"}""",
            """{"jsonrpc":"2.0","id":1,"result":{}}""",
            """{"jsonrpc":"2.0","id":1}""",
            """{"jsonrpc":"2.0","id":{"a":1},"method":"ping"}""",
            """{"jsonrpc":"2.0","id":1,"method":"ping","params":[1]}""",
        ).forEach { text ->
            val exception = assertFailsWith<JsonRpcException>(text) { JsonRpc.parse(text) }
            assertEquals(JsonRpcCodes.INVALID_REQUEST, exception.code, text)
        }
    }

    @Test
    fun theMetaTextReadsTheProtocolVersion() {
        val message = JsonRpc.parse(
            """{"jsonrpc":"2.0","id":"a","method":"ping","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28"}}}""",
        )
        assertEquals("2026-07-28", message.metaText(McpProtocol.META_PROTOCOL_VERSION))
        assertNull(message.metaText("progressToken"))
    }

    @Test
    fun theResponsesHaveTheFixedForm() {
        val success = JsonRpc.success(JsonPrimitive(1), buildJsonObject { put("a", 1) })
        assertEquals("""{"jsonrpc":"2.0","id":1,"result":{"a":1}}""", success.toString())
        val error = JsonRpc.error(JsonPrimitive("x"), JsonRpcCodes.METHOD_NOT_FOUND, "Method not found")
        assertEquals("""{"jsonrpc":"2.0","id":"x","error":{"code":-32601,"message":"Method not found"}}""", error.toString())
        assertEquals(JsonRpcCodes.METHOD_NOT_FOUND, JsonRpc.errorCode(error))
        assertNull(JsonRpc.errorCode(success))
        assertEquals(1, JsonRpc.result(success)?.get("a")?.jsonPrimitive?.content?.toInt())
        val noId = JsonRpc.error(null, JsonRpcCodes.PARSE_ERROR, "Parse error", buildJsonObject { put("x", 1) })
        assertEquals(JsonNull, noId["id"])
        assertEquals(1, noId["error"]?.jsonObject?.get("data")?.jsonObject?.get("x")?.jsonPrimitive?.content?.toInt())
        val notification = JsonRpc.notification("notifications/progress", buildJsonObject { put("progress", 1) })
        assertEquals("""{"jsonrpc":"2.0","method":"notifications/progress","params":{"progress":1}}""", notification.toString())
    }

    @Test
    fun theCodesAreTheStandardAndTheMcpCodes() {
        assertEquals(-32700, JsonRpcCodes.PARSE_ERROR)
        assertEquals(-32600, JsonRpcCodes.INVALID_REQUEST)
        assertEquals(-32601, JsonRpcCodes.METHOD_NOT_FOUND)
        assertEquals(-32602, JsonRpcCodes.INVALID_PARAMS)
        assertEquals(-32603, JsonRpcCodes.INTERNAL_ERROR)
        assertEquals(-32020, JsonRpcCodes.HEADER_MISMATCH)
        assertEquals(-32022, JsonRpcCodes.UNSUPPORTED_PROTOCOL_VERSION)
    }
}
