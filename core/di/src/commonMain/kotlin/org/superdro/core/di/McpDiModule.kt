package org.superdro.core.di

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.kodein.di.DI
import org.kodein.di.bindProvider
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import org.kodein.di.instanceOrNull
import org.superdro.core.appversion.AppVersion
import org.superdro.core.connection.LocalNetworkPermission
import org.superdro.core.connection.McpServerLine
import org.superdro.core.connection.NetworkInterfaces
import org.superdro.core.dataoutput.DataOutputPlatform
import org.superdro.core.dataoutput.WallClock
import org.superdro.core.mcp.McpFileStore
import org.superdro.core.mcp.McpSecrets
import org.superdro.core.mcp.McpServerState
import org.superdro.core.mcp.McpToolSet
import org.superdro.core.mcp.WriteGate
import org.superdro.core.store.files.StagingFiles
import org.superdro.core.store.json.AppSettingsStore
import org.superdro.core.store.json.TabStore
import org.superdro.core.store.repository.McpCallRepository
import org.superdro.core.ui.mcp.McpChipPresenter
import org.superdro.core.ui.mcp.McpFiles
import org.superdro.core.ui.mcp.McpGate
import org.superdro.core.ui.mcp.McpRunner
import org.superdro.core.ui.mcp.McpSettingsPresenter
import org.superdro.core.ui.mcp.ReadToolDeps
import org.superdro.core.ui.mcp.populate.PopulateDeps
import org.superdro.core.ui.mcp.populate.populateToolSet
import org.superdro.core.ui.mcp.readToolSet
import org.superdro.core.ui.session.Connections

/** The tag of the `List<McpToolSet>` binding: the tool sets of the MCP server. */
const val MCP_TOOL_SETS: String = "mcpToolSets"

/** The tag of the `McpToolSet` binding of the read tools. */
const val MCP_READ_TOOL_SET: String = "mcpReadToolSet"

/** The tag of the `McpToolSet` binding of the populate tools. */
const val MCP_POPULATE_TOOL_SET: String = "mcpPopulateToolSet"

/**
 * The bindings of the MCP server: the secrets, the file store, the write gate, the audit
 * repository, the tool sets, the runner, and the presenters of the settings screen and the
 * status chip.
 *
 * The runner starts when the graph is made, in the app scope, in the background
 * ([bindBackgroundStart]). The server itself runs only after the rules of `McpRules.runs`.
 *
 * The tool sets are the binding `List<McpToolSet>` with the tag [MCP_TOOL_SETS]. It holds the
 * read set ([MCP_READ_TOOL_SET]) and the populate set ([MCP_POPULATE_TOOL_SET]). The server
 * offers the tools of a set only while its scope is on.
 *
 * The runner gets the local network permission from the optional binding [LocalNetworkPermission].
 * The Android platform binds it on API 37 and higher. On the JVM the runner gets null.
 */
internal val mcpDiModule = DI.Module("mcpDiModule") {
    bindSingleton { McpSecrets(store = instance()) }
    bindSingleton { McpGate() }
    bindSingleton<WriteGate> { instance<McpGate>() }
    bindSingleton {
        McpFiles(
            uploads = StagingFiles(io = instance(), folder = McpFiles.UPLOADS),
            downloads = StagingFiles(io = instance(), folder = McpFiles.DOWNLOADS),
        )
    }
    bindSingleton<McpFileStore> { instance<McpFiles>() }
    bindSingleton { McpCallRepository(writer = instance()) }
    bindSingleton<McpToolSet>(tag = MCP_READ_TOOL_SET) {
        val connections = instance<Connections>()
        val tabs = instance<TabStore>()
        val settings = instance<AppSettingsStore>()
        readToolSet(
            ReadToolDeps(
                machines = instance(),
                tools = instance(),
                drawings = instance(),
                onlineMachines = { connections.onlineProfileIds() },
                selectedProfileId = { tabs.state.value.selectedProfileId },
                config = { settings.settings.value.mcp },
                appVersion = AppVersion.NAME,
            ),
        )
    }
    bindSingleton<McpToolSet>(tag = MCP_POPULATE_TOOL_SET) {
        val connections = instance<Connections>()
        val settings = instance<AppSettingsStore>()
        populateToolSet(
            PopulateDeps(
                machines = instance(),
                tools = instance(),
                drawings = instance(),
                files = instance<McpFileStore>(),
                gate = instance<WriteGate>(),
                config = { settings.settings.value.mcp },
                onlineMachines = { connections.onlineProfileIds() },
                appVersion = AppVersion.NAME,
                clock = WallClock.System,
                storeIO = instance(),
                dxfLimits = { settings.settings.value.dxfImportLimits },
            ),
        )
    }
    bindSingleton<List<McpToolSet>>(tag = MCP_TOOL_SETS) {
        listOf(instance<McpToolSet>(tag = MCP_READ_TOOL_SET), instance<McpToolSet>(tag = MCP_POPULATE_TOOL_SET))
    }
    bindSingleton {
        val platform = instance<DataOutputPlatform>()
        val toolSets = instance<List<McpToolSet>>(tag = MCP_TOOL_SETS)
        val appScope = instance<kotlinx.coroutines.CoroutineScope>(tag = APP_SCOPE)
        instance<McpFiles>().startExpiry(appScope)
        McpRunner(
            settings = instance(),
            networkInterfaces = instance<NetworkInterfaces>(),
            secrets = instance(),
            secretsDispatcher = Dispatchers.IO,
            sockets = platform.listenSockets,
            files = instance(),
            toolSets = { toolSets },
            calls = instance(),
            clock = platform.clock,
            wallClock = WallClock.System,
            ports = platform.ports,
            appVersion = AppVersion.NAME,
            localNetworkGranted = instanceOrNull<LocalNetworkPermission>()?.granted,
        ).also { runner -> runner.start(appScope) }
    }
    bindBackgroundStart<McpRunner>()
    bindProvider {
        McpSettingsPresenter(
            runner = instance(),
            settings = instance(),
            interfaces = instance<NetworkInterfaces>().interfaces,
            calls = instance(),
            requests = instance(),
            context = instance(),
        )
    }
    bindProvider { McpChipPresenter(runner = instance(), settings = instance(), requests = instance(), context = instance()) }
}

/**
 * Gives the MCP server of [runner] as the keep-alive controller sees it: null while the server is
 * stopped, else the bound addresses. A server that starts, or that waits for a free port, has no
 * address and still keeps the service.
 */
internal fun mcpServerLine(runner: McpRunner): Flow<McpServerLine?> =
    combine(runner.state, runner.listening) { state, listening ->
        if (state == McpServerState.Stopped) null else McpServerLine(listening)
    }

/** The IDs of the profiles with an online session. */
private fun Connections.onlineProfileIds(): Set<String> =
    sessions.value.filterValues { session -> session.state.value.isOnline }.keys.map { id -> id.value }.toSet()
