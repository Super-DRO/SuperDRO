package org.superdro.core.ui.mcp

import com.toxicbakery.logging.Arbor
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.superdro.core.connection.NetworkInterfaces
import org.superdro.core.dataoutput.BindAddresses
import org.superdro.core.dataoutput.ListenSocketFactory
import org.superdro.core.dataoutput.PortRegistry
import org.superdro.core.dataoutput.WallClock
import org.superdro.core.mcp.McpCallRecord
import org.superdro.core.mcp.McpError
import org.superdro.core.mcp.McpFileStore
import org.superdro.core.mcp.McpRules
import org.superdro.core.mcp.McpSecrets
import org.superdro.core.mcp.McpServer
import org.superdro.core.mcp.McpServerState
import org.superdro.core.mcp.McpToolSet
import org.superdro.core.model.McpConfig
import org.superdro.core.store.json.AppSettingsStore
import org.superdro.core.store.repository.McpCallRepository
import org.superdro.core.store.repository.McpCallRow
import org.superdro.core.util.Clock

private val log = Arbor.tag("McpRunner")

/**
 * Runs the one MCP server of the app while the process lives.
 *
 * - The runner reads `AppSettings.mcp` and applies [McpRules.runs]: the server runs only when the
 *   switch is on, the activation is "Always on" or the user tapped the chip ([requestStart]), the
 *   plain HTTP warning was confirmed (or the interface is Localhost), and the local network
 *   permission is granted where the platform needs it.
 * - A change of the port, the interface, or the addresses of the interface starts a new server.
 *   A change of the switch to off stops the server and closes each client at once.
 * - The token comes from [McpSecrets.tokenOrCreate] on [secretsDispatcher] at the first start.
 *   [newToken] replaces it and starts the server again, so each client with the old token goes away.
 * - [onCall] writes a row with the wall clock time of the call into [calls]. The rows older than
 *   [retentionMs] go away at the start.
 *
 * All server calls run on [dispatcher], which runs one task at a time. The functions of this
 * class are safe on each thread.
 *
 * @param toolSets Gives the tool sets of the server at each request.
 * @param localNetworkGranted The local network permission, or null when the platform needs none.
 * @param dispatcher A dispatcher that runs one task at a time. A test gives its test dispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class McpRunner(
    private val settings: AppSettingsStore,
    private val networkInterfaces: NetworkInterfaces,
    private val secrets: McpSecrets,
    private val secretsDispatcher: CoroutineDispatcher,
    private val sockets: ListenSocketFactory,
    private val files: McpFileStore,
    private val toolSets: () -> List<McpToolSet>,
    private val calls: McpCallRepository,
    private val clock: Clock,
    private val wallClock: WallClock,
    private val ports: PortRegistry? = null,
    private val appVersion: String = "dev",
    private val localNetworkGranted: StateFlow<Boolean>? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val retentionMs: Long = RETENTION_MS,
) {
    /** One server with its key and the job of its state collectors. */
    private class Run(val key: ServerKey, val server: McpServer, val job: Job)

    /** The values that need a new server when they change. */
    private data class ServerKey(val port: Int, val hosts: List<String>)

    private var scope: CoroutineScope? = null
    private var current: Run? = null

    @Volatile
    private var tokenBytes: ByteArray? = null

    private val startedOnDemand = MutableStateFlow(false)

    private val _state = MutableStateFlow<McpServerState>(McpServerState.Stopped)

    /** The state of the server. */
    val state: StateFlow<McpServerState> = _state.asStateFlow()

    private val _listening = MutableStateFlow<List<String>>(emptyList())

    /** The bound addresses as `host:port` while the server listens. */
    val listening: StateFlow<List<String>> = _listening.asStateFlow()

    private val _clients = MutableStateFlow<List<String>>(emptyList())

    /** The addresses of the clients with a request in the last 60 s. */
    val clients: StateFlow<List<String>> = _clients.asStateFlow()

    private val _lastError = MutableStateFlow<McpError?>(null)

    /** The last error of the server, or null. A start clears it. */
    val lastError: StateFlow<McpError?> = _lastError.asStateFlow()

    /** True after a tap on the chip with the activation "On demand", until the stop or the switch off. */
    val requested: StateFlow<Boolean> = startedOnDemand.asStateFlow()

    /** Starts the runner in a child scope of [parent]. A second call does nothing. */
    fun start(parent: CoroutineScope) {
        if (scope != null) return
        val runnerScope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext.job) + dispatcher)
        scope = runnerScope
        runnerScope.launch {
            try {
                calls.prune(wallClock.nowEpochMs() - retentionMs)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                log.w(exception) { "The prune of the MCP audit log failed" }
            }
        }
        runnerScope.launch {
            val config = settings.settings.map { value -> value.mcp }.distinctUntilChanged()
            val granted = localNetworkGranted ?: flowOf(true)
            combine(config, startedOnDemand, granted, networkInterfaces.interfaces) { stored, onDemand, permission, interfaces ->
                Decision(stored, McpRules.runs(stored, onDemand, permission, localNetworkGranted != null), BindAddresses.hosts(interfaces, stored.bindInterface))
            }.distinctUntilChanged().collect { decision -> reconcile(runnerScope, decision) }
        }
    }

    /** Stops the server and the runner. */
    fun stop() {
        val running = scope ?: return
        scope = null
        running.launch(dispatcher) { closeCurrent() }.invokeOnCompletion { running.coroutineContext.job.cancel() }
    }

    /** Starts the server with the activation "On demand" (the chip or the start button). */
    fun requestStart() {
        startedOnDemand.value = true
    }

    /** Stops the server with the activation "On demand". */
    fun requestStop() {
        startedOnDemand.value = false
    }

    /** Does what a tap on the start and stop button does. */
    fun toggle() {
        startedOnDemand.value = !startedOnDemand.value
    }

    /** Gives the token. [create] makes a token when the store has none. */
    suspend fun token(create: Boolean): String? = withContext(secretsDispatcher) {
        val token = if (create) secrets.tokenOrCreate() else secrets.token()
        token?.let { value -> tokenBytes = value.encodeToByteArray() }
        token
    }

    /** Makes a new token ("New token"). The old token stops: a server that runs starts again. */
    suspend fun newToken(): String {
        val token = withContext(secretsDispatcher) { secrets.replaceToken() }
        tokenBytes = token.encodeToByteArray()
        withContext(dispatcher) { current?.server?.restart() }
        return token
    }

    /** The monotonic time of the clock of the server, for the ages of the diagnostics. */
    fun nowMs(): Long = clock.nowMs()

    /** The configuration, the run rule, and the addresses of the selected interface. */
    private data class Decision(val config: McpConfig, val runs: Boolean, val hosts: List<String>)

    private suspend fun reconcile(runnerScope: CoroutineScope, decision: Decision) {
        if (!decision.config.enabled && startedOnDemand.value) startedOnDemand.value = false
        if (!decision.runs) {
            closeCurrent()
            return
        }
        val key = ServerKey(decision.config.port, decision.hosts)
        if (current?.key == key) return
        closeCurrent()
        if (tokenBytes == null) {
            try {
                token(create = true)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                log.e(exception) { "The MCP token cannot be read. The server starts with no token, and each request gets 401." }
            }
        }
        _lastError.value = null
        val server = McpServer(
            scope = runnerScope,
            sockets = sockets,
            bindHosts = { BindAddresses.hosts(networkInterfaces.interfaces.value, settings.settings.value.mcp.bindInterface) },
            port = key.port,
            token = { tokenBytes },
            toolSets = toolSets,
            scopes = { settings.settings.value.mcp.scopes },
            files = files,
            clock = clock,
            onCall = ::record,
            onError = { error -> _lastError.value = error },
            ports = ports,
            appVersion = appVersion,
        )
        val job = SupervisorJob(runnerScope.coroutineContext.job)
        val mirror = CoroutineScope(runnerScope.coroutineContext + job)
        mirror.launch { server.state.collect { value -> _state.value = value } }
        mirror.launch { server.listening.collect { value -> _listening.value = value } }
        mirror.launch { server.clients.collect { value -> _clients.value = value } }
        current = Run(key, server, job)
        server.start()
    }

    private fun closeCurrent() {
        val run = current ?: return
        current = null
        run.server.stop()
        run.job.cancel()
        _state.value = McpServerState.Stopped
        _listening.value = emptyList()
        _clients.value = emptyList()
    }

    /** Writes [record] into the audit log with the wall clock time of this moment. */
    private fun record(record: McpCallRecord) {
        val row = McpCallRow(
            id = 0L,
            atMs = wallClock.nowEpochMs(),
            client = record.client.host,
            era = record.client.era.name,
            tool = record.tool,
            method = record.method,
            argumentsJson = record.argumentsJson,
            result = record.result.name.lowercase(),
            durationMs = record.durationMs,
            previousJson = null,
        )
        val running = scope ?: return
        running.launch {
            try {
                calls.insert(row)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                log.w(exception) { "The MCP audit row was not written" }
            }
        }
    }

    companion object {
        /** The time that an audit row stays: 30 days. */
        const val RETENTION_MS: Long = 30L * 24 * 60 * 60 * 1000
    }
}
