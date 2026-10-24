package org.superdro.core.connection

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Makes the [KeepAliveSummary] of the connection service from the sessions. The controller is
 * common code. The platform contract
 * [ConnectionKeepAlive] gets each new value.
 *
 * - The summary is not null while one or more sessions are in `Connecting`, `AwaitingData`,
 *   `Connected`, `NoData`, `Reconnecting`, or `WaitingForDevice` ([SessionState.keepsAlive]), or
 *   while the MCP server runs ([mcpServer] is not null).
 * - The controller removes repeated values.
 * - A change waits 300 ms (the debounce), so that a quick state change does not cause many
 *   notification updates.
 *
 * The controller starts when it is made, and runs until [scope] stops.
 *
 * @param sessions The sessions of the connection manager.
 * @param keepAlive The platform contract that gets each new value.
 * @param scope The scope of the collector.
 * @param debounceMs The wait before a change goes out.
 * @param mcpServer The MCP server: null while it does not run. `core:connection` does not see
 *   the runner, so the DI graph gives this flow. The default is a server that never runs.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class KeepAliveController(
    private val sessions: Flow<Map<DeviceId, DeviceSession>>,
    private val keepAlive: ConnectionKeepAlive,
    private val scope: CoroutineScope,
    val debounceMs: Long = DEBOUNCE_MS,
    private val mcpServer: Flow<McpServerLine?> = flowOf(null),
) {

    /** Makes a controller for the sessions of [manager]. */
    constructor(
        manager: ConnectionManager,
        keepAlive: ConnectionKeepAlive,
        scope: CoroutineScope,
        debounceMs: Long = DEBOUNCE_MS,
        mcpServer: Flow<McpServerLine?> = flowOf(null),
    ) : this(manager.sessions, keepAlive, scope, debounceMs, mcpServer)

    private val _summary = MutableStateFlow<KeepAliveSummary?>(null)

    /** The last summary that went to [keepAlive]. Null: nothing keeps the process alive. */
    val summary: StateFlow<KeepAliveSummary?> = _summary.asStateFlow()

    init {
        scope.launch {
            val lines = sessions.flatMapLatest { map -> linesOf(map.values.toList()) }
            combine(lines, mcpServer, ::summaryOf)
                .debounce(debounceMs)
                .distinctUntilChanged()
                .collect { value ->
                    if (value != _summary.value) {
                        _summary.value = value
                        keepAlive.update(value)
                    }
                }
        }
    }

    private fun linesOf(sessions: List<DeviceSession>): Flow<List<DeviceLine>> =
        if (sessions.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(
                sessions.map { session ->
                    combine(session.device, session.state) { device, state ->
                        DeviceLine(session.id, device.name, device.transport, state)
                    }
                },
            ) { lines -> lines.toList() }
        }

    companion object {
        /** The debounce of the summary. */
        const val DEBOUNCE_MS: Long = 300L

        /**
         * Gives the summary of the sessions with their states and of the MCP server: null when no
         * session has [SessionState.keepsAlive] and the server does not run ([mcpServer] is
         * null). The lines have the order of [lines]. [KeepAliveSummary.mcpAddress] is the first
         * address of the server.
         */
        fun summaryOf(lines: List<DeviceLine>, mcpServer: McpServerLine? = null): KeepAliveSummary? {
            val kept = lines.filter { line -> line.state.keepsAlive }
            if (kept.isEmpty() && mcpServer == null) return null
            return KeepAliveSummary(
                devices = kept,
                offlineRememberedCount = lines.count { line -> !line.state.isOnline },
                mcpServer = mcpServer != null,
                mcpAddress = mcpServer?.addresses?.firstOrNull(),
            )
        }
    }
}
