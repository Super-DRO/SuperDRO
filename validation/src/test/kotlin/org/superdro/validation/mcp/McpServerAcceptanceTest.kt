package org.superdro.validation.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.superdro.core.appversion.AppVersion
import org.superdro.core.mcp.JsonRpcCodes
import org.superdro.core.mcp.McpProtocol
import org.superdro.core.mcp.McpServerState
import org.superdro.core.model.BindInterface
import org.superdro.core.model.McpActivation
import org.superdro.core.model.McpConfig
import org.superdro.core.model.McpScope
import org.superdro.core.ui.mcp.McpDialog

/**
 * The acceptance tests of the MCP server through the full DI graph on a loopback socket
 * ([McpAcceptanceGraph]): the opt-in rules of the proposal sections 1, 4.6, and 6.2, the era
 * selection of section 4.4, the authentication of section 4.5, and the scopes of the issue
 * comment section 1. The requirement that each test checks is in its KDoc.
 */
class McpServerAcceptanceTest {

    /** Proposal 1.1 and 6.1: off by default. No server, no socket, no token use. */
    @Test
    fun theServerIsOffByDefaultAndOpensNoSocketAndMakesNoToken() {
        McpAcceptanceGraph().use { graph ->
            assertEquals(McpConfig(), graph.config, "The stored configuration is the default.")
            assertFalse(graph.config.enabled)
            // The port of the harness, with the switch off: the runner does not bind it.
            graph.configure { config -> config.copy(port = graph.port) }
            Thread.sleep(300)
            assertEquals(McpServerState.Stopped, graph.runner.state.value)
            assertEquals(emptyList(), graph.runner.listening.value)
            assertFalse(graph.portOpen(), "No socket on the port while the server is off.")
            assertNull(runBlocking { graph.runner.token(create = false) }, "The token exists only after the first start.")
        }
    }

    /** Proposal 6.2 (rule 2): with the activation On demand, the server runs only after the tap on the chip. */
    @Test
    fun onDemandRunsOnlyAfterTheRequestAndStopsOnTheSecondTap() {
        McpAcceptanceGraph().use { graph ->
            graph.configure { config -> config.copy(enabled = true, activation = McpActivation.OnDemand, bindInterface = BindInterface.Localhost, port = graph.port) }
            Thread.sleep(300)
            assertEquals(McpServerState.Stopped, graph.runner.state.value, "On demand: no server before the tap.")
            assertFalse(graph.portOpen())

            graph.runner.requestStart()
            graph.awaitReady()
            assertEquals(listOf("127.0.0.1:${graph.port}"), graph.runner.listening.value)
            assertEquals(200, graph.legacy("ping").status)

            graph.runner.requestStop()
            graph.awaitStopped()
            assertFalse(graph.portOpen(), "The stop closes the socket.")
        }
    }

    /** Proposal 6.2 (rule 1): the switch off stops the server and closes each socket at once. */
    @Test
    fun theSwitchOffClosesTheSocketAtOnce() {
        McpAcceptanceGraph().use { graph ->
            graph.turnOn()
            assertTrue(graph.portOpen())
            graph.configure { config -> config.copy(enabled = false) }
            graph.awaitStopped()
            assertFalse(graph.portOpen())
        }
    }

    /**
     * Proposal 4.6 and 6.2 (rule 3): a LAN interface needs the plain HTTP warning. The server
     * does not start while `plainHttpConfirmed` is false. Localhost needs no confirmation. The
     * settings screen shows the warning at the switch on, and the confirmation starts the server.
     */
    @Test
    fun aLanInterfaceNeedsThePlainHttpConfirmationAndLocalhostDoesNot() {
        McpAcceptanceGraph().use { graph ->
            val presenter = graph.presenter()
            graph.waitUntil("The screen loaded") { !presenter.uiState.value.loading }
            presenter.onPortText(graph.port.toString())
            presenter.onActivation(McpActivation.AlwaysOn)
            presenter.onInterface(BindInterface.WiFi)
            presenter.onEnabledChange(true)
            graph.waitUntil("The warning shows") { presenter.uiState.value.dialog == McpDialog.PlainHttpWarning }
            Thread.sleep(300)
            assertFalse(graph.config.plainHttpConfirmed)
            assertEquals(McpServerState.Stopped, graph.runner.state.value, "No server before the confirmation.")
            assertFalse(graph.portOpen())

            presenter.onConfirmDialog()
            graph.waitUntil("The warning was confirmed") { graph.config.plainHttpConfirmed }
            graph.awaitReady()
            assertTrue(graph.portOpen())

            // A different LAN interface after the confirmation needs it again. Localhost does not.
            presenter.onInterface(BindInterface.UsbEthernet)
            graph.waitUntil("The confirmation is reset") { !graph.config.plainHttpConfirmed && graph.config.bindInterface == BindInterface.UsbEthernet }
            graph.awaitStopped()
            presenter.onInterface(BindInterface.Localhost)
            graph.waitUntil("Localhost is selected") { graph.config.bindInterface == BindInterface.Localhost }
            graph.awaitReady()
            assertFalse(graph.config.plainHttpConfirmed, "Localhost runs with no confirmation.")
        }
    }

    /**
     * Proposal 4.4: the modern era. A request with the `_meta` protocol version 2026-07-28 and
     * the three headers gets a stateless answer with `resultType`, the server info in `_meta`,
     * and the cache hints. A header that does not match the body gives 400 with `-32020`. An
     * unknown version gives 400 with `-32022` and the supported list.
     */
    @Test
    fun theModernEraAnswersStatelessWithResultTypeAndChecksTheHeaders() {
        McpAcceptanceGraph().use { graph ->
            graph.turnOn()
            val list = graph.modern("tools/list")
            assertEquals(200, list.status, list.text)
            assertEquals("complete", list.result.text("resultType"))
            assertEquals(60000, list.result.text("ttlMs").toInt())
            assertEquals("private", list.result.text("cacheScope"))
            val serverInfo = list.result.getValue("_meta").jsonObject.getValue(McpProtocol.META_SERVER_INFO).jsonObject
            assertEquals("superdro", serverInfo.text("name"))
            assertEquals(AppVersion.NAME, serverInfo.text("version"))
            assertTrue("list_machines" in list.toolNames())

            val mismatch = graph.modern("tools/list", headerMethod = "ping")
            assertEquals(400, mismatch.status, mismatch.text)
            assertEquals(JsonRpcCodes.HEADER_MISMATCH, mismatch.error.text("code").toInt())
            assertEquals(-32020, JsonRpcCodes.HEADER_MISMATCH)

            val noHeader = graph.modern("tools/list", headerVersion = null)
            assertEquals(400, noHeader.status, noHeader.text)
            assertEquals(-32020, noHeader.error.text("code").toInt())

            val unknown = graph.modern("tools/list", headerVersion = "2027-01-01", metaVersion = "2027-01-01")
            assertEquals(400, unknown.status, unknown.text)
            assertEquals(JsonRpcCodes.UNSUPPORTED_PROTOCOL_VERSION, unknown.error.text("code").toInt())
            val supported = unknown.error.getValue("data").jsonObject.array("supported").map { version -> (version as JsonPrimitive).content }
            assertEquals(McpProtocol.SUPPORTED_VERSIONS, supported)

            // A tool call needs the Mcp-Name header. A call with it gets the result.
            val call = graph.callModern("get_app_info")
            assertEquals(200, call.status, call.text)
            assertFalse(call.isError)
            assertEquals("Read", call.structured.array("scopes").single().let { (it as JsonPrimitive).content })
            val noName = graph.modern("tools/call", json { put("name", "get_app_info"); put("arguments", json {}) })
            assertEquals(400, noName.status, noName.text)
            assertEquals(-32020, noName.error.text("code").toInt())

            // An unknown method gives 404 in the modern era.
            val unknownMethod = graph.modern("prompts/list")
            assertEquals(404, unknownMethod.status, unknownMethod.text)
            assertEquals(JsonRpcCodes.METHOD_NOT_FOUND, unknownMethod.error.text("code").toInt())
        }
    }

    /**
     * Proposal 4.4: the legacy era. `initialize` answers with the version of the client when
     * the server knows it, else 2025-11-25, and mints no session. `notifications/initialized`
     * gets 202. A `tools/list` with no version has no `resultType` and no cache hints.
     */
    @Test
    fun theLegacyEraAnswersInitializeThenToolsListWithNoSession() {
        McpAcceptanceGraph().use { graph ->
            graph.turnOn()
            val initialize = graph.legacy(
                "initialize",
                json {
                    put("protocolVersion", "2025-11-25")
                    put("capabilities", json {})
                    put("clientInfo", json { put("name", "test"); put("version", "1") })
                },
            )
            assertEquals(200, initialize.status, initialize.text)
            assertEquals("2025-11-25", initialize.result.text("protocolVersion"))
            assertEquals("superdro", initialize.result.getValue("serverInfo").jsonObject.text("name"))
            assertNull(initialize.headers["mcp-session-id"], "A stateless legacy server mints no session.")
            assertNull(initialize.result["resultType"])

            val old = graph.legacy("initialize", json { put("protocolVersion", "2025-06-18") }, id = 2)
            assertEquals("2025-06-18", old.result.text("protocolVersion"))
            val future = graph.legacy("initialize", json { put("protocolVersion", "2030-01-01") }, id = 3)
            assertEquals("2025-11-25", future.result.text("protocolVersion"))

            val initialized = graph.legacy("notifications/initialized", id = null)
            assertEquals(202, initialized.status, initialized.text)
            assertEquals(0, initialized.bytes.size)

            val list = graph.legacy("tools/list", id = 4)
            assertEquals(200, list.status, list.text)
            assertNull(list.result["resultType"])
            assertNull(list.result["ttlMs"])
            assertTrue("get_app_info" in list.toolNames())

            val ping = graph.legacy("ping", id = 5)
            assertEquals(200, ping.status)
            assertEquals(0, ping.result.size)

            // An unknown method gives 200 with the JSON-RPC error in the legacy era.
            val unknownMethod = graph.legacy("prompts/list", id = 6)
            assertEquals(200, unknownMethod.status, unknownMethod.text)
            assertEquals(JsonRpcCodes.METHOD_NOT_FOUND, unknownMethod.error.text("code").toInt())
        }
    }

    /**
     * Proposal 4.5: a missing or wrong token gives 401 with `WWW-Authenticate`. An `Origin`
     * that is not a URL of the server gives 403. A `Host` that is not a bound address gives 403.
     * `GET /mcp` gives 405. `GET /` names the app and the version, and nothing more.
     */
    @Test
    fun theTokenTheOriginAndTheHostProtectEachRoute() {
        McpAcceptanceGraph().use { graph ->
            graph.turnOn()
            val missing = graph.legacy("ping", token = null)
            assertEquals(401, missing.status, missing.text)
            assertEquals("Bearer realm=\"SuperDRO\"", missing.headers["www-authenticate"])
            val wrong = graph.legacy("ping", token = "A".repeat(43))
            assertEquals(401, wrong.status)
            val upload = graph.request("POST", "/upload", "x".encodeToByteArray(), token = null, contentType = "text/plain")
            assertEquals(401, upload.status, "The upload route needs the token too.")

            val badOrigin = graph.request("POST", "/mcp", """{"jsonrpc":"2.0","id":1,"method":"ping"}""".encodeToByteArray(), headers = listOf("Origin" to "http://evil.example"))
            assertEquals(403, badOrigin.status, badOrigin.text)
            val ownOrigin = graph.request("POST", "/mcp", """{"jsonrpc":"2.0","id":1,"method":"ping"}""".encodeToByteArray(), headers = listOf("Origin" to "http://127.0.0.1:${graph.port}"))
            assertEquals(200, ownOrigin.status, ownOrigin.text)
            val badHost = graph.request("POST", "/mcp", """{"jsonrpc":"2.0","id":1,"method":"ping"}""".encodeToByteArray(), host = "superdro.evil.example:${graph.port}")
            assertEquals(403, badHost.status, badHost.text)

            val get = graph.request("GET", "/mcp")
            assertEquals(405, get.status)
            val root = graph.request("GET", "/", token = null)
            assertEquals(200, root.status)
            assertEquals("SuperDRO ${AppVersion.NAME} MCP server", root.text.trim())
            val other = graph.request("GET", "/admin")
            assertEquals(404, other.status)
        }
    }

    /**
     * Issue comment section 1: the scopes. Read is always on. The populate tools are offered
     * only while the Populate scope is on, and a call of a tool of a scope that is off gives the
     * error `-32602` "The tool is not available". The populate tools carry `destructiveHint`
     * and, where the table says so, `_meta["anthropic/requiresUserInteraction"]`.
     */
    @Test
    fun thePopulateToolsAreOfferedOnlyWhileThePopulateScopeIsOn() {
        McpAcceptanceGraph().use { graph ->
            graph.turnOn()
            val readOnly = graph.legacy("tools/list")
            val readNames = readOnly.toolNames()
            assertEquals(listOf("list_machines", "list_workspaces", "get_workspace", "list_tools", "get_profile_settings", "get_app_info"), readNames)
            assertTrue(readOnly.result.array("tools").all { tool -> tool.jsonObject.getValue("annotations").jsonObject.text("readOnlyHint") == "true" })

            val refused = graph.call("put_tools", json { put("machine", "Mill"); put("tools", kotlinx.serialization.json.JsonArray(emptyList())) })
            assertEquals(200, refused.status, refused.text)
            assertEquals(JsonRpcCodes.INVALID_PARAMS, refused.error.text("code").toInt())
            assertEquals(-32602, JsonRpcCodes.INVALID_PARAMS)
            assertEquals("The tool is not available", refused.error.text("message"))

            graph.configure { config -> config.copy(scopes = config.scopes + McpScope.Populate, populateConfirmed = true) }
            graph.waitUntil("The scope is on") { "put_tools" in graph.legacy("tools/list").toolNames() }
            val both = graph.legacy("tools/list")
            val names = both.toolNames()
            assertEquals(readNames, names.take(readNames.size), "The read tools come first, in the same order.")
            val populate = names.drop(readNames.size)
            assertEquals(
                listOf(
                    "create_workspace", "update_workspace", "delete_workspace", "put_sub_datums", "delete_sub_datums", "put_guides",
                    "set_drawing", "clear_drawing", "set_reference_image", "clear_reference_image",
                    "put_tools", "delete_tools", "set_tool_outline", "clear_tool_outline", "export_tools",
                    "put_profile_settings", "create_profile", "export_profile_bundle", "import_profile_bundle",
                ),
                populate,
            )
            val tools = both.result.array("tools").associate { tool -> tool.jsonObject.text("name") to tool.jsonObject }
            val putSubDatums = tools.getValue("put_sub_datums")
            assertEquals("true", putSubDatums.getValue("annotations").jsonObject.text("destructiveHint"))
            assertEquals("true", putSubDatums.getValue("_meta").jsonObject.text(McpProtocol.META_REQUIRES_USER_INTERACTION))
            val exportTools = tools.getValue("export_tools")
            assertEquals("true", exportTools.getValue("annotations").jsonObject.text("readOnlyHint"), "An export writes only a download file.")
            assertNull(tools.getValue("create_workspace")["_meta"], "A new workspace needs no interaction flag.")

            val info = graph.call("get_app_info")
            assertEquals(listOf("Read", "Populate"), info.structured.array("scopes").map { scope -> (scope as JsonPrimitive).content })

            graph.configure { config -> config.copy(scopes = setOf(McpScope.Read)) }
            graph.waitUntil("The scope is off") { "put_tools" !in graph.legacy("tools/list").toolNames() }
        }
    }
}
