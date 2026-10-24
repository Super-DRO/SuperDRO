package org.superdro.core.ui.mcp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.superdro.core.mcp.McpRules
import org.superdro.core.mcp.McpServerState
import org.superdro.core.model.McpActivation
import org.superdro.core.model.McpConfig
import org.superdro.core.store.json.AppSettingsStore
import org.superdro.core.ui.presenter.PresenterContext
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.tabs.RequestSender

/**
 * The presenter of the status chip of the MCP server in the status strip of the readout.
 *
 * - The chip shows only when the switch of the server is on.
 * - A tap with the activation "On demand" starts or stops the server. The start asks for the
 *   local network permission.
 * - A tap in the problem state, with the activation "Always on", or while the plain HTTP
 *   warning waits for its confirmation, opens the diagnostics: the MCP settings screen with the
 *   audit log open. The diagnostics are general information, so they do not show in a dialog.
 */
class McpChipPresenter(
    private val runner: McpRunner,
    private val settings: AppSettingsStore,
    private val requests: RequestSender,
    context: PresenterContext,
) : ScopedPresenter(context) {

    private val _uiState = MutableStateFlow(McpChipUiState())

    /** The state of the chip. */
    val uiState: StateFlow<McpChipUiState> = _uiState.asStateFlow()

    private var config: McpConfig = McpConfig()

    init {
        scope.launch {
            combine(runner.state, runner.requested, settings.settings.map { value -> value.mcp }.distinctUntilChanged()) { state, requested, stored ->
                Triple(state, requested, stored)
            }.collect { (state, requested, stored) ->
                config = stored
                _uiState.update { current ->
                    current.copy(
                        visible = stored.enabled,
                        state = state,
                        running = state != McpServerState.Stopped || requested,
                        activation = stored.activation,
                    )
                }
            }
        }
    }

    /** A tap on the chip. */
    fun onTap() {
        val state = _uiState.value
        when {
            state.state is McpServerState.Failed || state.activation == McpActivation.AlwaysOn || McpRules.needsPlainHttpWarning(config) ->
                _uiState.update { current -> current.copy(openDetails = true) }
            state.running -> runner.requestStop()
            else -> {
                McpRules.localNetworkRequest(config)?.let(requests::send)
                runner.requestStart()
            }
        }
    }

    /** The entry opened the MCP settings screen with the diagnostics. */
    fun detailsOpened() = _uiState.update { state -> state.copy(openDetails = false) }
}
