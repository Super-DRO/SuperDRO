package org.superdro.core.ui.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.superdro.core.mcp.McpSecrets
import org.superdro.core.mcp.McpServerState
import org.superdro.core.model.BindInterface
import org.superdro.core.model.McpActivation

/** The runner of the one MCP server with test sockets and virtual time. */
class McpRunnerTest {

    @Test
    fun theServerIsOffByDefaultAndHasNoSocketAndNoToken() = runTest {
        val harness = McpHarness(this)
        harness.start()
        assertEquals(McpServerState.Stopped, harness.runner.state.value)
        assertTrue(harness.sockets.sockets.isEmpty())
        assertNull(harness.secretStore.get(McpSecrets(harness.secretStore).tokenName), "No token before the first start.")
        harness.close()
    }

    @Test
    fun alwaysOnWithLocalhostRunsAtOnceAndMakesTheToken() = runTest {
        val harness = McpHarness(this)
        harness.configure { config -> config.copy(enabled = true, activation = McpActivation.AlwaysOn, bindInterface = BindInterface.Localhost) }
        harness.start()
        assertEquals(McpServerState.Ready, harness.runner.state.value)
        assertEquals(listOf("127.0.0.1:5810"), harness.runner.listening.value)
        assertNotNull(harness.secretStore.get("mcp.token"), "The first start makes the token.")
        harness.configure { config -> config.copy(enabled = false) }
        harness.settle()
        assertEquals(McpServerState.Stopped, harness.runner.state.value)
        assertTrue(harness.sockets.open.isEmpty(), "The switch off closes the socket at once.")
        harness.close()
    }

    @Test
    fun onDemandRunsOnlyAfterTheRequestAndALanInterfaceNeedsTheConfirmation() = runTest {
        val harness = McpHarness(this)
        harness.configure { config -> config.copy(enabled = true) }
        harness.start()
        assertEquals(McpServerState.Stopped, harness.runner.state.value)
        harness.runner.requestStart()
        harness.settle()
        assertEquals(McpServerState.Stopped, harness.runner.state.value, "The plain HTTP warning is not confirmed.")
        harness.configure { config -> config.copy(plainHttpConfirmed = true) }
        harness.settle()
        assertEquals(McpServerState.Ready, harness.runner.state.value)
        assertEquals(listOf("192.168.1.20:5810"), harness.runner.listening.value)
        harness.runner.requestStop()
        harness.settle()
        assertEquals(McpServerState.Stopped, harness.runner.state.value)
        assertTrue(harness.sockets.open.isEmpty())
        harness.close()
    }

    @Test
    fun aChangeOfThePortStartsANewServerAndAChangeOfTheScopesDoesNot() = runTest {
        val harness = McpHarness(this)
        harness.configure { config -> config.copy(enabled = true, activation = McpActivation.AlwaysOn, bindInterface = BindInterface.Localhost) }
        harness.start()
        val first = harness.sockets.open.single()
        harness.configure { config -> config.copy(port = 5811) }
        harness.settle()
        assertTrue(first.closed)
        assertEquals(5811, harness.sockets.open.single().port)
        val second = harness.sockets.open.single()
        harness.configure { config -> config.copy(confirmation = org.superdro.core.model.McpConfirmation.All) }
        harness.settle()
        assertTrue(!second.closed, "A change that the server reads at each request needs no new server.")
        harness.close()
    }

    @Test
    fun aCallGoesIntoTheAuditLogWithTheWallClockTime() = runTest {
        val harness = McpHarness(this)
        harness.configure { config -> config.copy(enabled = true, activation = McpActivation.AlwaysOn, bindInterface = BindInterface.Localhost) }
        harness.start()
        val link = harness.connect()
        harness.settle()
        link.request("POST", "/mcp", """{"jsonrpc":"2.0","id":1,"method":"ping"}""", harness.authorization(), host = "127.0.0.1:5810")
        harness.settle()
        assertTrue(link.receivedText.startsWith("HTTP/1.1 200"), link.receivedText)
        val rows = harness.calls.last(10).first()
        val row = rows.single()
        assertEquals("ping", row.method)
        assertEquals("192.168.1.30", row.client)
        assertEquals("ok", row.result)
        assertEquals(McpHarness.WALL_START_MS + 10L + 10L, row.atMs)
        harness.close()
    }

    @Test
    fun aNewTokenStopsTheOldTokenAndStartsTheServerAgain() = runTest {
        val harness = McpHarness(this)
        harness.configure { config -> config.copy(enabled = true, activation = McpActivation.AlwaysOn, bindInterface = BindInterface.Localhost) }
        harness.start()
        val old = assertNotNull(harness.runner.token(create = false))
        val first = harness.sockets.open.single()
        val token = harness.runner.newToken()
        harness.settle()
        assertNotEquals(old, token)
        assertTrue(first.closed, "The server starts again with the new token.")
        assertEquals(McpServerState.Ready, harness.runner.state.value)
        val link = harness.connect()
        harness.settle()
        link.request("POST", "/mcp", """{"jsonrpc":"2.0","id":1,"method":"ping"}""", listOf("Authorization" to "Bearer $old"), host = "127.0.0.1:5810")
        harness.settle()
        assertTrue(link.receivedText.startsWith("HTTP/1.1 401"), link.receivedText)
        harness.close()
    }
}
