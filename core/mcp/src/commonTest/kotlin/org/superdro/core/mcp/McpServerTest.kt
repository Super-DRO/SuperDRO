package org.superdro.core.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.superdro.core.dataoutput.PortRegistry
import org.superdro.core.model.McpScope
import org.superdro.core.util.Clock

/** The MCP server with fake sockets and virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class McpServerTest {

    private class Setup(
        val server: McpServer,
        val sockets: FakeSocketFactory,
        val files: FakeFileStore,
        val errors: MutableList<McpError>,
        val calls: MutableList<McpCallRecord>,
        var hosts: List<String>,
        var token: ByteArray?,
        var sets: List<McpToolSet>,
        var scopes: Set<McpScope>,
    ) {
        /** Connects a client to the first open socket and gives its test client. */
        fun connect(remoteHost: String = "192.168.1.30"): TestClient = TestClient(sockets.open.first().connect(remoteHost))
    }

    private fun TestScope.setup(
        hosts: List<String> = listOf("192.168.1.20"),
        ports: PortRegistry? = null,
        sets: List<McpToolSet> = listOf(sampleToolSet()),
    ): Setup {
        val sockets = FakeSocketFactory()
        val files = FakeFileStore()
        val errors = mutableListOf<McpError>()
        val calls = mutableListOf<McpCallRecord>()
        lateinit var setup: Setup
        val server = McpServer(
            scope = backgroundScope,
            sockets = sockets,
            bindHosts = { setup.hosts },
            port = 5810,
            token = { setup.token },
            toolSets = { setup.sets },
            scopes = { setup.scopes },
            files = files,
            clock = Clock { testScheduler.currentTime },
            onCall = { record -> calls += record },
            onError = { error -> errors += error },
            ports = ports,
            appVersion = "26.43.1",
        )
        setup = Setup(server, sockets, files, errors, calls, hosts, TOKEN.encodeToByteArray(), sets, setOf(McpScope.Read))
        return setup
    }

    private fun TestScope.started(hosts: List<String> = listOf("192.168.1.20"), ports: PortRegistry? = null): Setup {
        val setup = setup(hosts, ports)
        setup.server.start()
        runCurrent()
        assertEquals(McpServerState.Ready, setup.server.state.value)
        return setup
    }

    // Start, stop, failures

    @Test
    fun theServerHasNoSocketBeforeTheStartAndAfterTheStop() = runTest {
        val setup = setup()
        runCurrent()
        assertTrue(setup.sockets.sockets.isEmpty())
        assertEquals(McpServerState.Stopped, setup.server.state.value)
        setup.server.start()
        runCurrent()
        val socket = setup.sockets.open.single()
        assertEquals("192.168.1.20", socket.host)
        assertEquals(5810, socket.port)
        assertEquals(McpServerState.Ready, setup.server.state.value)
        assertEquals(listOf("192.168.1.20:5810"), setup.server.listening.value)
        setup.server.stop()
        assertTrue(socket.closed)
        assertEquals(McpServerState.Stopped, setup.server.state.value)
        assertTrue(setup.server.listening.value.isEmpty())
    }

    @Test
    fun theStopClosesTheServerAndAllConnectionsAtOnce() = runTest {
        val setup = started(hosts = listOf("192.168.1.20", "fe80::1%wlan0"))
        assertEquals(listOf("192.168.1.20:5810", "[fe80::1%wlan0]:5810"), setup.server.listening.value)
        val clients = listOf(setup.connect("192.168.1.30"), setup.connect("192.168.1.31"))
        runCurrent()
        setup.server.stop()
        assertTrue(setup.sockets.sockets.all { socket -> socket.closed })
        assertTrue(clients.all { client -> client.link.closed })
        assertTrue(setup.server.clients.value.isEmpty())
    }

    @Test
    fun aBindErrorGivesFailedAndARetryAfter5Seconds() = runTest {
        val setup = setup()
        setup.sockets.bindFailure = { _, port -> IllegalStateException("Address already in use: $port") }
        setup.server.start()
        runCurrent()
        val state = assertIs<McpServerState.Failed>(setup.server.state.value)
        assertEquals(McpErrorType.PortBindFailed, state.error.type)
        assertEquals("192.168.1.20", state.error.host)
        assertEquals(5810, state.error.port)
        assertEquals("Address already in use: 5810", state.error.detail)
        assertEquals(state.error.atMs + 5000L, state.retryAtMs)
        assertEquals(1, setup.errors.size)
        setup.sockets.bindFailure = { _, _ -> null }
        advanceTimeBy(4999L)
        runCurrent()
        assertIs<McpServerState.Failed>(setup.server.state.value)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(McpServerState.Ready, setup.server.state.value)
    }

    @Test
    fun anAcceptErrorClosesTheConnectionsAndANewServerStartsAfter5Seconds() = runTest {
        val setup = started()
        val socket = setup.sockets.open.single()
        val client = setup.connect()
        runCurrent()
        socket.failAccept(IllegalStateException("Too many open files"))
        runCurrent()
        val state = assertIs<McpServerState.Failed>(setup.server.state.value)
        assertEquals(McpErrorType.AcceptFailed, state.error.type)
        assertTrue(socket.closed)
        assertTrue(client.link.closed)
        advanceTimeBy(5000L)
        runCurrent()
        assertEquals(McpServerState.Ready, setup.server.state.value)
        assertEquals(1, setup.sockets.open.size)
    }

    @Test
    fun noAddressGivesFailedUntilTheInterfaceHasAnAddress() = runTest {
        val setup = setup(hosts = emptyList())
        setup.server.start()
        runCurrent()
        assertEquals(McpErrorType.NoAddress, assertIs<McpServerState.Failed>(setup.server.state.value).error.type)
        setup.hosts = listOf("127.0.0.1")
        advanceTimeBy(5000L)
        runCurrent()
        assertEquals(McpServerState.Ready, setup.server.state.value)
    }

    @Test
    fun aPortOfADataOutputGivesPortTakenAndTheServerReleasesItsPortAtTheStop() = runTest {
        val ports = PortRegistry()
        ports.claim(5810, "profile-1")
        val setup = setup(ports = ports)
        setup.server.start()
        runCurrent()
        val state = assertIs<McpServerState.Failed>(setup.server.state.value)
        assertEquals(McpErrorType.PortTaken, state.error.type)
        assertEquals("profile-1", state.error.detail)
        ports.release(5810, "profile-1")
        advanceTimeBy(5000L)
        runCurrent()
        assertEquals(McpServerState.Ready, setup.server.state.value)
        assertEquals("mcp", ports.owners.value[5810])
        setup.server.stop()
        assertNull(ports.owners.value[5810])
    }

    @Test
    fun twoToolsWithOneNameGiveFailedAtTheStart() = runTest {
        val setup = setup(sets = listOf(ListToolSet(listOf(tool("a"))), ListToolSet(listOf(tool("a")))))
        setup.server.start()
        runCurrent()
        val state = assertIs<McpServerState.Failed>(setup.server.state.value)
        assertEquals(McpErrorType.DuplicateName, state.error.type)
        assertTrue(setup.sockets.sockets.isEmpty())
    }

    // Routes

    @Test
    fun theRootGivesTheVersionLineWithNoToken() = runTest {
        val setup = started()
        val client = setup.connect()
        client.send("GET", "/")
        runCurrent()
        val response = client.response()
        assertEquals(200, response.status)
        assertEquals("SuperDRO 26.43.1 MCP server\n", response.body)
        assertFalse(client.link.closed)
    }

    @Test
    fun getOnTheMcpPathGives405AndAnUnknownPathGives404() = runTest {
        val setup = started()
        val client = setup.connect()
        client.send("GET", "/mcp", headers = listOf("Authorization" to "Bearer $TOKEN"))
        runCurrent()
        val response = client.response()
        assertEquals(405, response.status)
        assertEquals("POST", response.headers["allow"])
        client.send("GET", "/other")
        runCurrent()
        assertEquals(404, client.response().status)
        client.send("DELETE", "/mcp")
        runCurrent()
        assertEquals(405, client.response().status)
    }

    @Test
    fun aPingGoesThroughThePostAndTheConnectionStaysOpen() = runTest {
        val setup = started()
        val client = setup.connect()
        client.mcp(requestJson("ping"))
        runCurrent()
        val response = client.response()
        assertEquals(200, response.status)
        assertEquals("application/json", response.headers["content-type"])
        assertEquals("""{"jsonrpc":"2.0","id":1,"result":{}}""", response.body)
        assertFalse(client.link.closed)
        client.mcp(requestJson("tools/list", 2))
        runCurrent()
        assertEquals(2, client.response().json["result"]?.jsonObject?.get("tools")?.jsonArray?.size)
        val record = setup.calls.single { call -> call.method == "ping" }
        assertEquals(McpCallOutcome.Ok, record.result)
        assertEquals(McpClient("192.168.1.30", McpEra.Legacy), record.client)
    }

    @Test
    fun aNotificationGives202WithNoBody() = runTest {
        val setup = started()
        val client = setup.connect()
        client.mcp(requestJson("notifications/initialized", id = null))
        runCurrent()
        val response = client.response()
        assertEquals(202, response.status)
        assertEquals("", response.body)
        assertTrue(setup.calls.isEmpty())
    }

    @Test
    fun aModernRequestPassesTheHeadersToTheProtocol() = runTest {
        val setup = started()
        val client = setup.connect()
        val params = buildJsonObject { put("name", "list_machines"); put("_meta", modernMeta()) }
        client.mcp(requestJson("tools/call", 1, params), extra = listOf("MCP-Protocol-Version" to "2026-07-28", "Mcp-Method" to "tools/call", "Mcp-Name" to "list_machines"))
        runCurrent()
        val response = client.response()
        assertEquals(200, response.status)
        assertEquals("complete", response.json["result"]?.jsonObject?.get("resultType")?.jsonPrimitive?.content)
        client.mcp(requestJson("tools/call", 2, params), extra = listOf("MCP-Protocol-Version" to "2026-07-28", "Mcp-Method" to "tools/call"))
        runCurrent()
        val mismatch = client.response()
        assertEquals(400, mismatch.status)
        assertEquals(JsonRpcCodes.HEADER_MISMATCH, JsonRpc.errorCode(mismatch.json))
        client.mcp(requestJson("prompts/list", 3, buildJsonObject { put("_meta", modernMeta()) }), extra = listOf("MCP-Protocol-Version" to "2026-07-28", "Mcp-Method" to "prompts/list"))
        runCurrent()
        assertEquals(404, client.response().status)
        assertEquals(McpEra.Modern, setup.calls.first().client.era)
    }

    // Auth

    @Test
    fun aMissingOrWrongTokenGives401WithTheChallenge() = runTest {
        val setup = started()
        val client = setup.connect()
        client.mcp(requestJson("ping"), token = null)
        runCurrent()
        val missing = client.response()
        assertEquals(401, missing.status)
        assertEquals("Bearer realm=\"SuperDRO\"", missing.headers["www-authenticate"])
        client.mcp(requestJson("ping"), token = "B".repeat(43))
        runCurrent()
        assertEquals(401, client.response().status)
        client.mcp(requestJson("ping"), token = TOKEN.dropLast(1))
        runCurrent()
        assertEquals(401, client.response().status)
        assertEquals(3, setup.calls.count { call -> call.result == McpCallOutcome.Unauthorized })
        assertEquals("POST /mcp", setup.calls.first().method)
    }

    @Test
    fun noTokenInTheStoreGives401ForEachRequest() = runTest {
        val setup = started()
        setup.token = null
        val client = setup.connect()
        client.mcp(requestJson("ping"))
        runCurrent()
        assertEquals(401, client.response().status)
    }

    @Test
    fun tenFailedTokenChecksInOneMinuteLockTheAddressForOneMinute() = runTest {
        val setup = started()
        val client = setup.connect()
        repeat(10) { index ->
            advanceTimeBy(100L)
            client.mcp(requestJson("ping", index), token = "wrong")
            runCurrent()
            assertEquals(401, client.response().status)
        }
        client.mcp(requestJson("ping"))
        runCurrent()
        val locked = client.response()
        assertEquals(429, locked.status)
        // A different address is not locked.
        val other = setup.connect("192.168.1.31")
        other.mcp(requestJson("ping"))
        runCurrent()
        assertEquals(200, other.response().status)
        advanceTimeBy(59_000L)
        client.mcp(requestJson("ping"))
        runCurrent()
        assertEquals(429, client.response().status)
        advanceTimeBy(2000L)
        client.mcp(requestJson("ping"))
        runCurrent()
        assertEquals(200, client.response().status)
    }

    // Origin and Host

    @Test
    fun anOriginThatIsNotTheServerGives403() = runTest {
        val setup = started()
        val client = setup.connect()
        client.mcp(requestJson("ping"), extra = listOf("Origin" to "http://evil.example"))
        runCurrent()
        assertEquals(403, client.response().status)
        client.mcp(requestJson("ping"), extra = listOf("Origin" to "http://192.168.1.20:5811"))
        runCurrent()
        assertEquals(403, client.response().status)
        listOf("http://192.168.1.20:5810", "http://localhost:5810", "http://127.0.0.1:5810", "null").forEachIndexed { index, origin ->
            client.mcp(requestJson("ping", index), extra = listOf("Origin" to origin))
            runCurrent()
            assertEquals(200, client.response().status, origin)
        }
    }

    @Test
    fun aHostThatIsNotABoundAddressGives403() = runTest {
        val setup = started(hosts = listOf("192.168.1.20", "fe80::1%wlan0"))
        val client = setup.connect()
        client.mcp(requestJson("ping"), host = "evil.example:5810")
        runCurrent()
        assertEquals(403, client.response().status)
        client.mcp(requestJson("ping"), host = "10.0.0.5:5810")
        runCurrent()
        assertEquals(403, client.response().status)
        client.mcp(requestJson("ping"), host = null)
        runCurrent()
        assertEquals(400, client.response().status)
        listOf("192.168.1.20:5810", "192.168.1.20", "[fe80::1]:5810", "localhost:5810", "127.0.0.1:5810").forEachIndexed { index, host ->
            client.mcp(requestJson("ping", index), host = host)
            runCurrent()
            assertEquals(200, client.response().status, host)
        }
    }

    // Rate

    @Test
    fun theTwentyFirstRequestInOneSecondGives429() = runTest {
        val setup = started()
        val client = setup.connect()
        repeat(20) { index ->
            client.mcp(requestJson("ping", index))
            runCurrent()
            assertEquals(200, client.response().status, "request $index")
        }
        client.mcp(requestJson("ping", 20))
        runCurrent()
        val limited = client.response()
        assertEquals(429, limited.status)
        assertEquals("1", limited.headers["retry-after"])
        advanceTimeBy(1000L)
        client.mcp(requestJson("ping", 21))
        runCurrent()
        assertEquals(200, client.response().status)
    }

    // Body rules

    @Test
    fun aBodyThatIsNotJsonGives415AndABodyAboveTheLimitGives413() = runTest {
        val setup = started()
        val client = setup.connect()
        client.mcp(requestJson("ping"), contentType = "text/plain")
        runCurrent()
        assertEquals(415, client.response().status)
        val big = setup.connect("192.168.1.31")
        big.send("POST", "/mcp", headers = listOf("Content-Length" to "${McpLimits.MCP_BODY_BYTES + 1}", "Content-Type" to "application/json"))
        runCurrent()
        assertEquals(413, big.response().status)
        assertTrue(big.link.closed)
    }

    @Test
    fun aChunkedRequestGives411AndClosesTheConnection() = runTest {
        val setup = started()
        val client = setup.connect()
        client.send("POST", "/mcp", headers = listOf("Transfer-Encoding" to "chunked"))
        runCurrent()
        assertEquals(411, client.response().status)
        assertTrue(client.link.closed)
    }

    @Test
    fun aBodyThatIsNotJsonRpcGives400WithTheParseError() = runTest {
        val setup = started()
        val client = setup.connect()
        client.mcp("{oops")
        runCurrent()
        val response = client.response()
        assertEquals(400, response.status)
        assertEquals(JsonRpcCodes.PARSE_ERROR, JsonRpc.errorCode(response.json))
    }

    // Files

    @Test
    fun anUploadGoesToTheFileStoreAndGivesTheReceipt() = runTest {
        val setup = started()
        val client = setup.connect()
        client.send("POST", "/upload", body = "0\nSECTION", headers = listOf("Authorization" to "Bearer $TOKEN", "Content-Type" to "application/dxf"))
        runCurrent()
        val response = client.response()
        assertEquals(200, response.status)
        assertEquals("up1", response.json["fileId"]?.jsonPrimitive?.content)
        assertEquals(9, response.json["bytes"]?.jsonPrimitive?.content?.toInt())
        assertEquals("sha-up1", response.json["sha256Hex"]?.jsonPrimitive?.content)
        assertEquals("application/dxf", setup.files.uploads["up1"]?.contentType)
        assertEquals("0\nSECTION", setup.files.uploads["up1"]?.bytes?.decodeToString())
        assertEquals("POST /upload", setup.calls.single().method)
        client.send("POST", "/upload", body = "x", headers = listOf("Content-Type" to "text/plain"))
        runCurrent()
        assertEquals(401, client.response().status)
        client.send("GET", "/upload", headers = listOf("Authorization" to "Bearer $TOKEN"))
        runCurrent()
        assertEquals(405, client.response().status)
    }

    @Test
    fun aDownloadGivesTheFileOr404() = runTest {
        val setup = started()
        val id = setup.files.putDownload("tools.csv", "text/csv", "id,name\n".encodeToByteArray())
        val client = setup.connect()
        client.send("GET", "/download/$id", headers = listOf("Authorization" to "Bearer $TOKEN"))
        runCurrent()
        val response = client.response()
        assertEquals(200, response.status)
        assertEquals("text/csv", response.headers["content-type"])
        assertEquals("id,name\n", response.body)
        client.send("GET", "/download/nothing", headers = listOf("Authorization" to "Bearer $TOKEN"))
        runCurrent()
        assertEquals(404, client.response().status)
        client.send("GET", "/download/$id")
        runCurrent()
        assertEquals(401, client.response().status)
        assertEquals(listOf(McpCallOutcome.Ok, McpCallOutcome.Error, McpCallOutcome.Unauthorized), setup.calls.map { call -> call.result })
    }

    // Streams, state, clients

    @Test
    fun aProgressTokenGivesAnSseStreamWithAKeepAliveEach15Seconds() = runTest {
        val setup = setup(sets = emptyList())
        val release = CompletableDeferred<Unit>()
        setup.sets = listOf(
            ListToolSet(
                listOf(
                    tool("slow") { call ->
                        call.progress(1.0, 2.0, "half")
                        release.await()
                        ToolResult.Ok("done")
                    },
                ),
            ),
        )
        setup.server.start()
        runCurrent()
        val client = setup.connect()
        val params = buildJsonObject { put("name", "slow"); put("_meta", buildJsonObject { put("progressToken", "t1") }) }
        client.mcp(requestJson("tools/call", 1, params))
        runCurrent()
        assertEquals(McpServerState.Active, setup.server.state.value)
        var text = client.pending()
        assertTrue(text.startsWith("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"), text)
        assertTrue("event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{\"progressToken\":\"t1\",\"progress\":1.0,\"total\":2.0,\"message\":\"half\"}}\n\n" in text, text)
        assertFalse(": keep-alive" in text)
        advanceTimeBy(15_000L)
        runCurrent()
        text = client.pending()
        assertEquals(1, Regex(": keep-alive").findAll(text).count())
        release.complete(Unit)
        runCurrent()
        text = client.pending()
        assertTrue(text.endsWith("event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"done\"}],\"isError\":false}}\n\n"), text)
        assertTrue(client.link.closed)
        assertEquals(McpServerState.Ready, setup.server.state.value)
        assertEquals(McpCallOutcome.Ok, setup.calls.single().result)
        assertEquals("slow", setup.calls.single().tool)
    }

    @Test
    fun aClientThatClosesTheStreamCancelsTheCall() = runTest {
        val setup = setup(sets = emptyList())
        var cancelled = false
        setup.sets = listOf(
            ListToolSet(
                listOf(
                    tool("wait") {
                        try {
                            CompletableDeferred<Unit>().await()
                        } catch (exception: kotlinx.coroutines.CancellationException) {
                            cancelled = true
                            throw exception
                        }
                        ToolResult.Ok("never")
                    },
                ),
            ),
        )
        setup.server.start()
        runCurrent()
        val client = setup.connect()
        client.mcp(requestJson("tools/call", 1, buildJsonObject { put("name", "wait"); put("_meta", buildJsonObject { put("progressToken", 5) }) }))
        runCurrent()
        client.link.disconnect()
        runCurrent()
        assertTrue(cancelled)
        assertTrue(client.link.closed)
        assertEquals(McpServerState.Ready, setup.server.state.value)
        assertEquals(McpCallOutcome.Error, setup.calls.single().result)
    }

    @Test
    fun theStateIsActiveWhileACallRunsAndTheClientListKeepsAHostFor60Seconds() = runTest {
        val setup = setup(sets = emptyList())
        val release = CompletableDeferred<Unit>()
        setup.sets = listOf(ListToolSet(listOf(tool("slow") { release.await(); ToolResult.Ok("done") })))
        setup.server.start()
        runCurrent()
        val client = setup.connect("192.168.1.30")
        client.mcp(requestJson("tools/call", 1, buildJsonObject { put("name", "slow") }))
        runCurrent()
        assertEquals(McpServerState.Active, setup.server.state.value)
        assertEquals(listOf("192.168.1.30"), setup.server.clients.value)
        release.complete(Unit)
        runCurrent()
        assertEquals(McpServerState.Ready, setup.server.state.value)
        assertEquals(200, client.response().status)
        advanceTimeBy(59_000L)
        runCurrent()
        assertEquals(listOf("192.168.1.30"), setup.server.clients.value)
        advanceTimeBy(31_000L)
        runCurrent()
        assertTrue(setup.server.clients.value.isEmpty())
    }

    @Test
    fun theRecordOfAWriteToolHasTheArgumentsAndTheRecordOfAReadToolDoesNot() = runTest {
        val setup = started()
        setup.scopes = setOf(McpScope.Read, McpScope.Populate)
        val client = setup.connect()
        client.mcp(requestJson("tools/call", 1, buildJsonObject { put("name", "put_tools"); put("arguments", buildJsonObject { put("machine", "Mill") }) }))
        runCurrent()
        assertEquals(200, client.response().status)
        client.mcp(requestJson("tools/call", 2, buildJsonObject { put("name", "list_machines"); put("arguments", buildJsonObject { put("machine", "Mill") }) }))
        runCurrent()
        assertEquals(200, client.response().status)
        val write = setup.calls[0]
        assertEquals("put_tools", write.tool)
        assertEquals("""{"machine":"Mill"}""", write.argumentsJson)
        assertEquals(McpCallOutcome.Ok, write.result)
        assertNull(setup.calls[1].argumentsJson)
    }

    @Test
    fun theOutcomeOfARefusedWriteIsRefused() = runTest {
        val setup = setup(sets = listOf(ListToolSet(listOf(tool("refuse") { ToolResult.Error(GateTexts.REFUSED) }, tool("late") { ToolResult.Error(GateTexts.TIMEOUT) }))))
        setup.server.start()
        runCurrent()
        val client = setup.connect()
        client.mcp(requestJson("tools/call", 1, buildJsonObject { put("name", "refuse") }))
        runCurrent()
        client.response()
        client.mcp(requestJson("tools/call", 2, buildJsonObject { put("name", "late") }))
        runCurrent()
        client.response()
        client.mcp(requestJson("tools/call", 3, buildJsonObject { put("name", "none") }))
        runCurrent()
        client.response()
        assertEquals(listOf(McpCallOutcome.Refused, McpCallOutcome.Timeout, McpCallOutcome.Error), setup.calls.map { call -> call.result })
    }

    @Test
    fun aRestartGivesANewSocket() = runTest {
        val setup = started()
        val first = setup.sockets.open.single()
        setup.server.restart()
        runCurrent()
        assertTrue(first.closed)
        assertEquals(1, setup.sockets.open.size)
        assertEquals(McpServerState.Ready, setup.server.state.value)
    }
}
