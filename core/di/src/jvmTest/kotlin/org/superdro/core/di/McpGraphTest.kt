package org.superdro.core.di

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.kodein.di.DI
import org.kodein.di.bindProvider
import org.kodein.di.bindSingleton
import org.kodein.di.direct
import org.kodein.di.instance
import org.superdro.core.connection.NetworkInterfaceInfo
import org.superdro.core.connection.NetworkInterfaces
import org.superdro.core.mcp.McpFileStore
import org.superdro.core.mcp.McpServerState
import org.superdro.core.mcp.McpToolSet
import org.superdro.core.mcp.WriteGate
import org.superdro.core.model.BindInterface
import org.superdro.core.model.McpActivation
import org.superdro.core.model.McpScope
import org.superdro.core.store.StoreIOProvider
import org.superdro.core.store.StoreIOProviderJvm
import org.superdro.core.store.json.AppSettingsStore
import org.superdro.core.store.db.SuperDroDatabase
import org.superdro.core.ui.mcp.McpChipPresenter
import org.superdro.core.ui.mcp.McpFiles
import org.superdro.core.ui.mcp.McpGate
import org.superdro.core.ui.mcp.McpRunner
import org.superdro.core.ui.mcp.McpSettingsPresenter

/**
 * The MCP server with the full common graph and a real socket on the loopback address. The
 * settings screen turns on the server, and a client with the token calls `tools/list`.
 */
class McpGraphTest {

    private val directory = kotlin.io.path.createTempDirectory("superdro-mcp").toFile()

    private val loopback = object : NetworkInterfaces {
        override val interfaces = MutableStateFlow(listOf(NetworkInterfaceInfo("lo", BindInterface.Localhost, listOf("127.0.0.1"))))
    }

    private val di = DI {
        import(coreDiModule)
        import(noOpPlatformModule)
        bindSingleton<SqlDriver> {
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties().apply { setProperty("foreign_keys", "true") })
                .also { driver -> SuperDroDatabase.Schema.create(driver) }
        }
        bindProvider<StoreIOProvider>(overrides = true) { StoreIOProviderJvm(directory) }
        bindSingleton<NetworkInterfaces>(overrides = true) { loopback }
    }

    @AfterTest
    fun close() {
        di.direct.instance<McpRunner>().stop()
        di.closeGraph()
        directory.deleteRecursively()
    }

    private suspend fun waitUntil(what: String, condition: suspend () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!condition()) {
            check(System.currentTimeMillis() - start < 20_000) { "$what. ${detail()}" }
            delay(20)
        }
    }

    /** The state of the runner and the stored configuration, for a failure message. */
    private fun detail(): String {
        val runner = di.direct.instance<McpRunner>()
        val settings = di.direct.instance<AppSettingsStore>()
        return "state=${runner.state.value} error=${runner.lastError.value} listening=${runner.listening.value} config=${settings.settings.value.mcp} loaded=${settings.isLoaded.value}"
    }

    private fun post(port: Int, body: String, token: String?): Pair<Int, String> {
        val connection = URI("http://127.0.0.1:$port/mcp").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 2000
        connection.readTimeout = 5000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
        connection.outputStream.use { stream -> stream.write(body.encodeToByteArray()) }
        val status = connection.responseCode
        val stream = if (status < 400) connection.inputStream else connection.errorStream
        val text = stream?.use { input -> input.readBytes().decodeToString() }.orEmpty()
        connection.disconnect()
        return status to text
    }

    @Test
    fun theGraphBindsTheMcpPartsAsSingletonsAndTheToolSetsHoldTheReadSetAndThePopulateSet() {
        val direct = di.direct
        assertSame(direct.instance<McpRunner>(), direct.instance<McpRunner>())
        assertSame(direct.instance<McpGate>(), direct.instance<WriteGate>())
        assertSame(direct.instance<McpFiles>(), direct.instance<McpFileStore>())
        val sets = direct.instance<List<McpToolSet>>(tag = MCP_TOOL_SETS)
        assertEquals(2, sets.size)
        assertSame(direct.instance<McpToolSet>(tag = MCP_READ_TOOL_SET), sets[0])
        assertSame(direct.instance<McpToolSet>(tag = MCP_POPULATE_TOOL_SET), sets[1])
        assertEquals(listOf("list_machines", "list_workspaces", "get_workspace", "list_tools", "get_profile_settings", "get_app_info"), sets[0].tools.map { tool -> tool.spec.name })
        assertEquals(setOf(McpScope.Read), sets[0].tools.map { tool -> tool.spec.scope }.toSet())
        assertEquals(setOf(McpScope.Populate), sets[1].tools.map { tool -> tool.spec.scope }.toSet())
        assertTrue("put_sub_datums" in sets[1].tools.map { tool -> tool.spec.name })
        val names = sets.flatMap { set -> set.tools }.map { tool -> tool.spec.name }
        assertEquals(names.size, names.toSet().size, "Each tool has its own name: $names")
        direct.instance<McpSettingsPresenter>().close()
        direct.instance<McpChipPresenter>().close()
    }

    @Test
    fun theScreenTurnsOnTheServerAndAClientWithTheTokenListsTheTools() = runBlocking {
        val presenter = di.direct.instance<McpSettingsPresenter>()
        try {
            waitUntil("The screen loaded") { !presenter.uiState.value.loading }
            val port = ServerSocket(0).use { socket -> socket.localPort }
            presenter.onInterface(BindInterface.Localhost)
            presenter.onPortText(port.toString())
            presenter.onActivation(McpActivation.AlwaysOn)
            presenter.onEnabledChange(true)
            waitUntil("The server listens") { presenter.uiState.value.status.state == McpServerState.Ready }
            assertEquals(listOf("http://127.0.0.1:$port/mcp"), presenter.uiState.value.status.urls)
            val token = assertNotNull(di.direct.instance<McpRunner>().token(create = false))

            val (unauthorized, _) = post(port, """{"jsonrpc":"2.0","id":1,"method":"ping"}""", token = null)
            assertEquals(401, unauthorized)
            val (status, text) = post(port, """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""", token)
            assertEquals(200, status, text)
            assertTrue("\"list_machines\"" in text, text)
            assertTrue("\"get_app_info\"" in text, text)
            waitUntil("The audit log has the rows") { presenter.uiState.value.audit.size >= 2 }
            assertEquals(listOf("tools/list", "POST /mcp"), presenter.uiState.value.audit.take(2).map { line -> line.name }, "A request with no valid token is logged with its method and path.")
            assertEquals(listOf("ok", "unauthorized"), presenter.uiState.value.audit.take(2).map { line -> line.result })

            // The populate tools are offered only while the Populate scope is on.
            val (readOnly, readOnlyText) = post(port, """{"jsonrpc":"2.0","id":3,"method":"tools/list"}""", token)
            assertEquals(200, readOnly, readOnlyText)
            assertFalse("\"put_sub_datums\"" in readOnlyText, readOnlyText)
            presenter.onScope(McpScope.Populate, on = true)
            presenter.onConfirmDialog()
            waitUntil("The Populate scope is on") { McpScope.Populate in presenter.uiState.value.config.scopes }
            val (populate, populateText) = post(port, """{"jsonrpc":"2.0","id":4,"method":"tools/list"}""", token)
            assertEquals(200, populate, populateText)
            assertTrue("\"put_sub_datums\"" in populateText, populateText)
            assertTrue("\"list_machines\"" in populateText, populateText)
            presenter.onScope(McpScope.Populate, on = false)
            waitUntil("The Populate scope is off") { McpScope.Populate !in presenter.uiState.value.config.scopes }
            val (readAgain, readAgainText) = post(port, """{"jsonrpc":"2.0","id":5,"method":"tools/list"}""", token)
            assertEquals(200, readAgain, readAgainText)
            assertFalse("\"put_sub_datums\"" in readAgainText, readAgainText)

            presenter.onEnabledChange(false)
            waitUntil("The switch off closes the server at once") {
                try {
                    Socket().use { probe -> probe.connect(InetSocketAddress("127.0.0.1", port), 500) }
                    false
                } catch (exception: ConnectException) {
                    true
                }
            }
        } finally {
            presenter.close()
        }
    }
}
