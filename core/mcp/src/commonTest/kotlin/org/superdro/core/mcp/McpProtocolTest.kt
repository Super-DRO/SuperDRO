package org.superdro.core.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.superdro.core.model.McpScope

/** The MCP methods in the two eras, with no socket. */
class McpProtocolTest {

    private var scopes = setOf(McpScope.Read)
    private var sets: List<McpToolSet> = listOf(sampleToolSet())
    private val protocol = McpProtocol("1.2.3", { sets }, { scopes })

    private suspend fun legacy(method: String, params: JsonObject? = null, headers: McpHeaders = McpHeaders(), id: Int? = 1): ProtocolOutcome =
        protocol.handle(requestJson(method, id, params), headers, "192.168.1.30")

    private suspend fun modern(method: String, params: JsonObject = JsonObject(emptyMap()), headers: McpHeaders = modernHeaders(method), meta: JsonObject = modernMeta()): ProtocolOutcome =
        protocol.handle(requestJson(method, 1, JsonObject(params + ("_meta" to meta))), headers, "192.168.1.30")

    private fun json(outcome: ProtocolOutcome): ProtocolReply.Json = assertIs(outcome.reply)

    private fun result(outcome: ProtocolOutcome): JsonObject {
        val reply = json(outcome)
        assertEquals(200, reply.status, reply.body.toString())
        return JsonRpc.result(reply.body) ?: error("No result in ${reply.body}")
    }

    private fun errorCode(outcome: ProtocolOutcome): Int = JsonRpc.errorCode(json(outcome).body) ?: error("No error in ${json(outcome).body}")

    // Era selection

    @Test
    fun initializeIsLegacyAndAnswersTheKnownClientVersion() = runTest {
        val outcome = legacy("initialize", buildJsonObject { put("protocolVersion", "2025-06-18") }, McpHeaders(protocolVersion = "2025-06-18"))
        assertEquals(McpEra.Legacy, outcome.era)
        val result = result(outcome)
        assertEquals("2025-06-18", result["protocolVersion"]?.jsonPrimitive?.content)
        assertEquals("superdro", result["serverInfo"]?.jsonObject?.get("name")?.jsonPrimitive?.content)
        assertEquals("1.2.3", result["serverInfo"]?.jsonObject?.get("version")?.jsonPrimitive?.content)
        assertEquals(false, result["capabilities"]?.jsonObject?.get("tools")?.jsonObject?.get("listChanged")?.jsonPrimitive?.boolean)
        assertEquals(false, result["capabilities"]?.jsonObject?.get("resources")?.jsonObject?.get("subscribe")?.jsonPrimitive?.boolean)
        assertNull(result["resultType"])
    }

    @Test
    fun initializeAnswers20251125ForAnUnknownClientVersion() = runTest {
        val result = result(legacy("initialize", buildJsonObject { put("protocolVersion", "2030-01-01") }))
        assertEquals("2025-11-25", result["protocolVersion"]?.jsonPrimitive?.content)
        assertEquals("2025-11-25", result(legacy("initialize"))["protocolVersion"]?.jsonPrimitive?.content)
    }

    @Test
    fun theInitializedNotificationIsAccepted() = runTest {
        assertEquals(ProtocolReply.Accepted, legacy("notifications/initialized", id = null).reply)
        assertEquals(ProtocolReply.Accepted, legacy("notifications/cancelled", buildJsonObject { put("requestId", 1) }, id = null).reply)
    }

    @Test
    fun aRequestWithTheMetaVersionIsModernAndGetsTheResultTypeAndTheServerInfo() = runTest {
        val outcome = modern("ping")
        assertEquals(McpEra.Modern, outcome.era)
        val result = result(outcome)
        assertEquals("complete", result["resultType"]?.jsonPrimitive?.content)
        assertEquals("1.2.3", result["_meta"]?.jsonObject?.get(McpProtocol.META_SERVER_INFO)?.jsonObject?.get("version")?.jsonPrimitive?.content)
    }

    @Test
    fun aRequestWithOnlyTheVersionHeaderIsLegacyAndStateless() = runTest {
        val outcome = legacy("tools/list", headers = McpHeaders(protocolVersion = "2025-11-25"))
        assertEquals(McpEra.Legacy, outcome.era)
        assertEquals(2, result(outcome)["tools"]?.jsonArray?.size)
    }

    @Test
    fun aRequestWithNoVersionAtAllIsLegacy() = runTest {
        val outcome = legacy("ping")
        assertEquals(McpEra.Legacy, outcome.era)
        assertEquals(JsonObject(emptyMap()), result(outcome))
    }

    // The modern checks

    @Test
    fun anUnknownMetaVersionGives400And32022WithTheSupportedList() = runTest {
        val outcome = modern("ping", meta = modernMeta(version = "1900-01-01"), headers = McpHeaders("1900-01-01", "ping"))
        val reply = json(outcome)
        assertEquals(400, reply.status)
        assertEquals(JsonRpcCodes.UNSUPPORTED_PROTOCOL_VERSION, JsonRpc.errorCode(reply.body))
        val data = reply.body["error"]?.jsonObject?.get("data")?.jsonObject!!
        assertEquals(listOf("2026-07-28", "2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05"), data["supported"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertEquals("1900-01-01", data["requested"]?.jsonPrimitive?.content)
    }

    @Test
    fun aMissingOrDifferentVersionHeaderGives400And32020() = runTest {
        val missing = json(modern("ping", headers = McpHeaders(method = "ping")))
        assertEquals(400, missing.status)
        assertEquals(JsonRpcCodes.HEADER_MISMATCH, JsonRpc.errorCode(missing.body))
        val different = json(modern("ping", headers = McpHeaders("2025-11-25", "ping")))
        assertEquals(JsonRpcCodes.HEADER_MISMATCH, JsonRpc.errorCode(different.body))
    }

    @Test
    fun aMissingOrDifferentMethodHeaderGives32020() = runTest {
        assertEquals(JsonRpcCodes.HEADER_MISMATCH, errorCode(modern("ping", headers = McpHeaders(protocolVersion = "2026-07-28"))))
        assertEquals(JsonRpcCodes.HEADER_MISMATCH, errorCode(modern("ping", headers = modernHeaders("tools/list"))))
    }

    @Test
    fun theNameHeaderMustMatchTheToolNameOrTheUri() = runTest {
        val params = buildJsonObject { put("name", "list_machines") }
        assertEquals(JsonRpcCodes.HEADER_MISMATCH, errorCode(modern("tools/call", params, modernHeaders("tools/call"))))
        assertEquals(JsonRpcCodes.HEADER_MISMATCH, errorCode(modern("tools/call", params, modernHeaders("tools/call", "get_app_info"))))
        assertEquals(false, result(modern("tools/call", params, modernHeaders("tools/call", "list_machines")))["isError"]?.jsonPrimitive?.boolean)
        // The sentinel form decodes before the comparison.
        val encoded = "=?base64?" + Base64Text.encodeStandard("list_machines".encodeToByteArray()) + "?="
        assertEquals(false, result(modern("tools/call", params, modernHeaders("tools/call", encoded)))["isError"]?.jsonPrimitive?.boolean)
        val read = buildJsonObject { put("uri", "superdro://machines") }
        assertEquals(JsonRpcCodes.HEADER_MISMATCH, errorCode(modern("resources/read", read, modernHeaders("resources/read", "superdro://other"))))
        assertEquals(1, result(modern("resources/read", read, modernHeaders("resources/read", "superdro://machines")))["contents"]?.jsonArray?.size)
    }

    @Test
    fun anUnknownMethodGives404InTheModernEraAnd200InTheLegacyEra() = runTest {
        val modern = json(modern("prompts/list"))
        assertEquals(404, modern.status)
        assertEquals(JsonRpcCodes.METHOD_NOT_FOUND, JsonRpc.errorCode(modern.body))
        val legacy = json(legacy("prompts/list"))
        assertEquals(200, legacy.status)
        assertEquals(JsonRpcCodes.METHOD_NOT_FOUND, JsonRpc.errorCode(legacy.body))
    }

    @Test
    fun aBodyThatIsNotJsonRpcGives400() = runTest {
        val parse = assertIs<ProtocolReply.Json>(protocol.handle("{", McpHeaders(), "h").reply)
        assertEquals(400, parse.status)
        assertEquals(JsonRpcCodes.PARSE_ERROR, JsonRpc.errorCode(parse.body))
        val invalid = assertIs<ProtocolReply.Json>(protocol.handle("[]", McpHeaders(), "h").reply)
        assertEquals(400, invalid.status)
        assertEquals(JsonRpcCodes.INVALID_REQUEST, JsonRpc.errorCode(invalid.body))
    }

    // server/discover

    @Test
    fun serverDiscoverIsModernOnly() = runTest {
        val result = result(modern("server/discover"))
        assertEquals(McpProtocol.SUPPORTED_VERSIONS, result["supportedVersions"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertTrue(result["capabilities"]?.jsonObject?.containsKey("tools") == true)
        assertEquals(60000, result["ttlMs"]?.jsonPrimitive?.content?.toInt())
        assertEquals("private", result["cacheScope"]?.jsonPrimitive?.content)
        assertEquals(JsonRpcCodes.METHOD_NOT_FOUND, errorCode(legacy("server/discover")))
    }

    // tools/list

    @Test
    fun toolsListGivesTheOfferedToolsInAFixedOrderWithTheAnnotations() = runTest {
        val tools = result(legacy("tools/list"))["tools"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("list_machines", "get_app_info"), tools.map { it["name"]?.jsonPrimitive?.content })
        val first = tools[0]
        assertEquals("list machines", first["title"]?.jsonPrimitive?.content)
        assertEquals("The tool list_machines.", first["description"]?.jsonPrimitive?.content)
        assertEquals("object", first["inputSchema"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        val annotations = first["annotations"]!!.jsonObject
        assertEquals(true, annotations["readOnlyHint"]?.jsonPrimitive?.boolean)
        assertEquals(false, annotations["destructiveHint"]?.jsonPrimitive?.boolean)
        assertEquals(false, annotations["idempotentHint"]?.jsonPrimitive?.boolean)
        assertEquals(false, annotations["openWorldHint"]?.jsonPrimitive?.boolean)
        assertNull(first["_meta"])
    }

    @Test
    fun toolsListInTheModernEraHasTheCacheHints() = runTest {
        val result = result(modern("tools/list"))
        assertEquals("complete", result["resultType"]?.jsonPrimitive?.content)
        assertEquals(60000, result["ttlMs"]?.jsonPrimitive?.content?.toInt())
        assertEquals("private", result["cacheScope"]?.jsonPrimitive?.content)
        assertNull(result(legacy("tools/list"))["ttlMs"])
    }

    @Test
    fun aToolOfAScopeThatIsOnIsOfferedWithTheUserInteractionMeta() = runTest {
        scopes = setOf(McpScope.Read, McpScope.Populate)
        val tools = result(legacy("tools/list"))["tools"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("list_machines", "get_app_info", "put_tools"), tools.map { it["name"]?.jsonPrimitive?.content })
        assertEquals(true, tools[2]["_meta"]?.jsonObject?.get("anthropic/requiresUserInteraction")?.jsonPrimitive?.boolean)
    }

    // tools/call

    @Test
    fun toolsCallGivesTheContentAndTheStructuredContent() = runTest {
        val result = result(legacy("tools/call", buildJsonObject { put("name", "get_app_info") }))
        assertEquals("SuperDRO", result["content"]?.jsonArray?.single()?.jsonObject?.get("text")?.jsonPrimitive?.content)
        assertEquals("text", result["content"]?.jsonArray?.single()?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("192.168.1.30", result["structuredContent"]?.jsonObject?.get("client")?.jsonPrimitive?.content)
        assertEquals(false, result["isError"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun aToolThatIsNotOfferedGives32602() = runTest {
        val outcome = legacy("tools/call", buildJsonObject { put("name", "put_tools") })
        val reply = json(outcome)
        assertEquals(200, reply.status)
        assertEquals(JsonRpcCodes.INVALID_PARAMS, JsonRpc.errorCode(reply.body))
        assertEquals("The tool is not available", reply.body["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content)
        assertEquals(JsonRpcCodes.INVALID_PARAMS, errorCode(legacy("tools/call", buildJsonObject { put("name", "no_such_tool") })))
        assertEquals(JsonRpcCodes.INVALID_PARAMS, errorCode(legacy("tools/call", buildJsonObject { put("arguments", buildJsonObject {}) })))
        assertEquals(JsonRpcCodes.INVALID_PARAMS, errorCode(legacy("tools/call", buildJsonObject { put("name", "list_machines"); put("arguments", 5) })))
    }

    @Test
    fun aHandlerExceptionGivesIsErrorWithTheMessageAndNoServerError() = runTest {
        sets = listOf(ListToolSet(listOf(tool("boom") { throw IllegalStateException("The machine is offline") })))
        val result = result(legacy("tools/call", buildJsonObject { put("name", "boom") }))
        assertEquals(true, result["isError"]?.jsonPrimitive?.boolean)
        assertEquals("The machine is offline", result["content"]?.jsonArray?.single()?.jsonObject?.get("text")?.jsonPrimitive?.content)
    }

    @Test
    fun aToolErrorResultGivesIsError() = runTest {
        sets = listOf(ListToolSet(listOf(tool("refuse") { ToolResult.Error(GateTexts.REFUSED) })))
        val result = result(legacy("tools/call", buildJsonObject { put("name", "refuse") }))
        assertEquals(true, result["isError"]?.jsonPrimitive?.boolean)
        assertEquals("The operator refused the call", result["content"]?.jsonArray?.single()?.jsonObject?.get("text")?.jsonPrimitive?.content)
    }

    @Test
    fun theHandlerGetsTheArgumentsAndTheClient() = runTest {
        var seen: ToolCall? = null
        sets = listOf(ListToolSet(listOf(tool("echo") { call -> seen = call; ToolResult.Ok("ok") })))
        val params = buildJsonObject { put("name", "echo"); put("arguments", buildJsonObject { put("x", 1.5) }) }
        result(modern("tools/call", params, modernHeaders("tools/call", "echo")))
        assertEquals("echo", seen?.name)
        assertEquals(1.5, seen?.arguments?.get("x")?.jsonPrimitive?.content?.toDouble())
        assertEquals(McpClient("192.168.1.30", McpEra.Modern), seen?.client)
    }

    @Test
    fun aProgressTokenGivesAStreamWithTheProgressNotificationsAndTheFinalResponse() = runTest {
        sets = listOf(
            ListToolSet(
                listOf(
                    tool("slow") { call ->
                        call.progress(1.0, 3.0, "one")
                        call.progress(2.0, null, null)
                        ToolResult.Ok("done")
                    },
                ),
            ),
        )
        val params = buildJsonObject { put("name", "slow"); put("_meta", buildJsonObject { put("progressToken", "abc") }) }
        val stream = assertIs<ProtocolReply.Stream>(legacy("tools/call", params).reply)
        val events = mutableListOf<JsonObject>()
        val response = stream.run { event -> events += event }
        assertEquals(2, events.size)
        assertEquals("notifications/progress", events[0]["method"]?.jsonPrimitive?.content)
        val first = events[0]["params"]!!.jsonObject
        assertEquals("abc", first["progressToken"]?.jsonPrimitive?.content)
        assertEquals(1.0, first["progress"]?.jsonPrimitive?.content?.toDouble())
        assertEquals(3.0, first["total"]?.jsonPrimitive?.content?.toDouble())
        assertEquals("one", first["message"]?.jsonPrimitive?.content)
        assertNull(events[1]["params"]!!.jsonObject["total"])
        assertEquals("done", JsonRpc.result(response)?.get("content")?.jsonArray?.single()?.jsonObject?.get("text")?.jsonPrimitive?.content)
        assertEquals(JsonPrimitive(1), response["id"])
    }

    @Test
    fun theOutcomeNamesTheToolAndTheArgumentsOfAWriteToolOnly() = runTest {
        scopes = setOf(McpScope.Read, McpScope.Populate)
        val read = legacy("tools/call", buildJsonObject { put("name", "list_machines"); put("arguments", buildJsonObject { put("machine", "Mill") }) })
        assertEquals("tools/call", read.method)
        assertEquals("list_machines", read.tool)
        assertNull(read.argumentsJson)
        val write = legacy("tools/call", buildJsonObject { put("name", "put_tools"); put("arguments", buildJsonObject { put("machine", "Mill") }) })
        assertEquals("put_tools", write.tool)
        assertEquals("""{"machine":"Mill"}""", write.argumentsJson)
        assertNull(legacy("ping").tool)
    }

    // Resources

    @Test
    fun resourcesListGivesTheFixedResourcesAndTheTemplatesListGivesTheTemplates() = runTest {
        val resources = result(legacy("resources/list"))["resources"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("superdro://machines"), resources.map { it["uri"]?.jsonPrimitive?.content })
        assertEquals("application/json", resources[0]["mimeType"]?.jsonPrimitive?.content)
        assertEquals("machines", resources[0]["name"]?.jsonPrimitive?.content)
        val templates = result(modern("resources/templates/list"))["resourceTemplates"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("superdro://machines/{machine}/tools"), templates.map { it["uriTemplate"]?.jsonPrimitive?.content })
        assertNull(templates[0]["uri"])
    }

    @Test
    fun resourcesReadGivesTheContentsOfAResourceOrOfATemplateInstance() = runTest {
        val fixed = result(legacy("resources/read", buildJsonObject { put("uri", "superdro://machines") }))["contents"]!!.jsonArray.single().jsonObject
        assertEquals("superdro://machines", fixed["uri"]?.jsonPrimitive?.content)
        assertEquals("""{"uri":"superdro://machines"}""", fixed["text"]?.jsonPrimitive?.content)
        val instance = result(modern("resources/read", buildJsonObject { put("uri", "superdro://machines/mill-1/tools") }, modernHeaders("resources/read", "superdro://machines/mill-1/tools")))
        assertEquals("""{"tools":[]}""", instance["contents"]!!.jsonArray.single().jsonObject["text"]?.jsonPrimitive?.content)
        assertEquals(0, instance["ttlMs"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun aResourceThatIsNotThereGives32602InTheModernEraAnd32002InTheLegacyEra() = runTest {
        val legacyMissing = json(legacy("resources/read", buildJsonObject { put("uri", "superdro://nothing") }))
        assertEquals(-32002, JsonRpc.errorCode(legacyMissing.body))
        assertEquals("superdro://nothing", legacyMissing.body["error"]?.jsonObject?.get("data")?.jsonObject?.get("uri")?.jsonPrimitive?.content)
        val readerNull = json(legacy("resources/read", buildJsonObject { put("uri", "superdro://machines/missing/tools") }))
        assertEquals(-32002, JsonRpc.errorCode(readerNull.body))
        val modernMissing = json(modern("resources/read", buildJsonObject { put("uri", "superdro://nothing") }, modernHeaders("resources/read", "superdro://nothing")))
        assertEquals(200, modernMissing.status)
        assertEquals(JsonRpcCodes.INVALID_PARAMS, JsonRpc.errorCode(modernMissing.body))
        assertEquals(JsonRpcCodes.INVALID_PARAMS, errorCode(legacy("resources/read")))
    }

    @Test
    fun aTemplateMatchesOnlyAFullInstance() {
        val spec = McpResourceSpec("superdro://machines/{machine}/workspaces/{workspace}", "w", "w", "w", isTemplate = true)
        assertTrue(spec.matches("superdro://machines/a/workspaces/b"))
        assertFalse(spec.matches("superdro://machines/a/workspaces"))
        assertFalse(spec.matches("superdro://machines//workspaces/b"))
        assertFalse(spec.matches("superdro://machines/a/tools/b"))
        assertEquals(mapOf("machine" to "a", "workspace" to "b"), spec.variables("superdro://machines/a/workspaces/b"))
        assertTrue(McpResourceSpec("superdro://machines", "m", "m", "m").matches("superdro://machines"))
    }

    // Names

    @Test
    fun twoToolsWithOneNameAreAnError() {
        sets = listOf(ListToolSet(listOf(tool("a"))), ListToolSet(listOf(tool("a"))))
        val exception = kotlin.test.assertFailsWith<IllegalArgumentException> { protocol.allTools() }
        assertTrue("a" in exception.message!!)
        sets = listOf(ListToolSet(emptyList(), listOf(resource("superdro://x"))), ListToolSet(emptyList(), listOf(resource("superdro://x"))))
        kotlin.test.assertFailsWith<IllegalArgumentException> { protocol.allTools() }
    }

    @Test
    fun aToolNameMustBeSnakeCase() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { tool("Bad-Name") }
        kotlin.test.assertFailsWith<IllegalArgumentException> { tool("") }
        kotlin.test.assertFailsWith<IllegalArgumentException> { tool("a".repeat(65)) }
        tool("a".repeat(64))
    }
}
