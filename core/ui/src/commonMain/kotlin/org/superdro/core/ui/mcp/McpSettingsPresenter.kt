package org.superdro.core.ui.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.superdro.core.connection.NetworkInterfaceInfo
import org.superdro.core.dataoutput.BindAddresses
import org.superdro.core.mcp.McpError
import org.superdro.core.mcp.McpRules
import org.superdro.core.mcp.McpServerState
import org.superdro.core.model.BindInterface
import org.superdro.core.model.McpActivation
import org.superdro.core.model.McpConfig
import org.superdro.core.model.McpConfirmation
import org.superdro.core.model.McpScope
import org.superdro.core.resources.Res
import org.superdro.core.resources.mcp_command_copied
import org.superdro.core.resources.mcp_port_range
import org.superdro.core.resources.mcp_token_copied
import org.superdro.core.resources.mcp_token_error
import org.superdro.core.resources.mcp_token_new_done
import org.superdro.core.store.json.AppSettingsStore
import org.superdro.core.store.repository.McpCallRepository
import org.superdro.core.store.repository.McpCallRow
import org.superdro.core.ui.presenter.PresenterContext
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.tabs.RequestSender
import org.superdro.core.ui.text.UiText

/** The events of the MCP settings screen. [McpSettingsPresenter] implements them. */
interface McpSettingsEvents {
    fun onEnabledChange(enabled: Boolean)
    fun onStartStop()
    fun onActivation(activation: McpActivation)
    fun onScope(scope: McpScope, on: Boolean)
    fun onConfirmation(confirmation: McpConfirmation)
    fun onInterface(bind: BindInterface)
    fun onPortText(text: String)
    fun onConfirmPlainHttp()
    fun onConfirmDialog()
    fun onDismissDialog()
    fun onNewToken()
    fun onHideToken()
    fun onTokenCopied()
    fun onCommandCopied()
    fun onShowAudit()
    fun onHideAudit()
    fun onMessageShown(id: Long)
}

/**
 * The presenter of the screen `Settings > MCP server`. Each change writes at once into
 * `AppSettings.mcp`; the runner applies it.
 *
 * Rules:
 * - The switch is off by default. No server socket is open while it is off. The switch on sends
 *   the local network permission request.
 * - A LAN interface needs the confirmation of the warning "Use only on trusted networks" before
 *   the first start (`plainHttpConfirmed`). A change to a different LAN interface after a
 *   confirmation needs the confirmation again. Localhost needs no confirmation.
 * - The scope Populate and the scope Control go on only after their warning. The confirmation
 *   Off goes on only after its warning.
 * - The token stays in the `SecretStore`. The screen shows it one time after "New token".
 * - The diagnostics show the last 200 rows of the audit log in the screen, not in a dialog.
 */
class McpSettingsPresenter(
    private val runner: McpRunner,
    private val settings: AppSettingsStore,
    private val interfaces: Flow<List<NetworkInterfaceInfo>>,
    private val calls: McpCallRepository,
    private val requests: RequestSender,
    context: PresenterContext,
    private val permissionGranted: StateFlow<Boolean>? = null,
) : ScopedPresenter(context), McpSettingsEvents {

    private val _uiState = MutableStateFlow(McpSettingsUiState())

    /** The UI state of the screen. */
    val uiState: StateFlow<McpSettingsUiState> = _uiState.asStateFlow()

    private var hosts: List<NetworkInterfaceInfo> = emptyList()
    private var messageIds = 0L
    private var portEdited = false

    private class Runtime(val state: McpServerState, val listening: List<String>, val clients: Int, val error: McpError?, val requested: Boolean)

    private var runtime = Runtime(McpServerState.Stopped, emptyList(), 0, null, false)

    init {
        scope.launch {
            settings.settings.map { value -> value.mcp }.distinctUntilChanged().collect { config ->
                setState { state ->
                    state.copy(
                        loading = false,
                        config = config,
                        portText = if (portEdited) state.portText else config.port.toString(),
                        portProblem = if (portEdited) state.portProblem else null,
                    )
                }
            }
        }
        scope.launch {
            interfaces.collect { list ->
                hosts = list
                setState { state ->
                    state.copy(interfaces = INTERFACES.map { bind -> McpInterfaceChoice(bind, BindAddresses.hosts(list, bind)) })
                }
            }
        }
        scope.launch {
            combine(runner.state, runner.listening, runner.clients, runner.lastError, runner.requested) { state, listening, clients, error, requested ->
                Runtime(state, listening, clients.size, error, requested)
            }.collect { runtime ->
                this@McpSettingsPresenter.runtime = runtime
                refresh()
            }
        }
        scope.launch {
            calls.last(AUDIT_ROWS).collect { rows -> setState { state -> state.copy(audit = rows.map(::auditLine)) } }
        }
    }

    // ---------------------------------------------------------------------------------------
    // The switch, the start and stop, and the activation

    /** The switch of the server. On asks for the local network permission; off stops the server at once. */
    override fun onEnabledChange(enabled: Boolean) {
        val config = _uiState.value.config
        write { current -> current.copy(enabled = enabled) }
        if (enabled) {
            McpRules.localNetworkRequest(config)?.let(requests::send)
            if (McpRules.needsPlainHttpWarning(config)) setState { state -> state.copy(dialog = McpDialog.PlainHttpWarning) }
        }
    }

    /** The start and stop button with the activation "On demand". */
    override fun onStartStop() {
        val state = _uiState.value
        if (!state.status.canStartStop) return
        if (state.status.running || runtime.requested) {
            runner.requestStop()
            return
        }
        if (McpRules.needsPlainHttpWarning(state.config)) {
            setState { current -> current.copy(dialog = McpDialog.PlainHttpWarning) }
            return
        }
        McpRules.localNetworkRequest(state.config)?.let(requests::send)
        runner.requestStart()
    }

    override fun onActivation(activation: McpActivation) = write { current -> current.copy(activation = activation) }

    // ---------------------------------------------------------------------------------------
    // The access

    /** A scope switch. Populate and Control show their warning before the first time on. */
    override fun onScope(scope: McpScope, on: Boolean) {
        if (scope == McpScope.Read) return
        val config = _uiState.value.config
        if (!on) {
            write { current -> current.copy(scopes = current.scopes - scope) }
            return
        }
        when {
            scope == McpScope.Populate && !config.populateConfirmed -> setState { state -> state.copy(dialog = McpDialog.PopulateWarning) }
            scope == McpScope.Control && !config.controlConfirmed -> setState { state -> state.copy(dialog = McpDialog.ControlWarning) }
            else -> write { current -> current.copy(scopes = current.scopes + scope) }
        }
    }

    /** The confirmation setting. Off shows its warning first. */
    override fun onConfirmation(confirmation: McpConfirmation) {
        if (confirmation == McpConfirmation.Off && _uiState.value.config.confirmation != McpConfirmation.Off) {
            setState { state -> state.copy(dialog = McpDialog.ConfirmationOffWarning) }
            return
        }
        write { current -> current.copy(confirmation = confirmation) }
    }

    // ---------------------------------------------------------------------------------------
    // The network

    /**
     * Selects the interface. A different LAN interface after a confirmation on another LAN
     * interface needs the confirmation again (the plain HTTP rule).
     */
    override fun onInterface(bind: BindInterface) {
        if (bind !in McpRules.ALLOWED_INTERFACES) return
        val config = _uiState.value.config
        if (bind == config.bindInterface) return
        val confirmedStays = bind == BindInterface.Localhost
        write { current -> current.copy(bindInterface = bind, plainHttpConfirmed = confirmedStays && current.plainHttpConfirmed) }
    }

    /** The text of the port. A valid port writes at once. */
    override fun onPortText(text: String) {
        val port = text.trim().toIntOrNull()
        val valid = port != null && port in McpConfig.MIN_PORT..McpConfig.MAX_PORT
        portEdited = !valid || text.trim() != text
        setState { state -> state.copy(portText = text, portProblem = if (valid) null else UiText.of(Res.string.mcp_port_range)) }
        if (valid) write { current -> current.copy(port = port!!) }
    }

    /** The user confirmed the warning "Use only on trusted networks" (the button of the Network section or the dialog). */
    override fun onConfirmPlainHttp() {
        setState { state -> if (state.dialog == McpDialog.PlainHttpWarning) state.copy(dialog = null) else state }
        write { current -> current.copy(plainHttpConfirmed = true) }
    }

    // ---------------------------------------------------------------------------------------
    // The dialogs

    /** The confirm button of the open dialog. */
    override fun onConfirmDialog() {
        when (_uiState.value.dialog) {
            McpDialog.PlainHttpWarning -> onConfirmPlainHttp()
            McpDialog.PopulateWarning -> write { current -> current.copy(scopes = current.scopes + McpScope.Populate, populateConfirmed = true) }
            McpDialog.ControlWarning -> write { current -> current.copy(scopes = current.scopes + McpScope.Control, controlConfirmed = true) }
            McpDialog.ConfirmationOffWarning -> write { current -> current.copy(confirmation = McpConfirmation.Off) }
            McpDialog.NewToken -> onConfirmNewToken()
            null -> return
        }
        setState { state -> state.copy(dialog = null) }
    }

    /** Closes the open dialog. A warning that the user does not confirm changes nothing. */
    override fun onDismissDialog() = setState { state -> state.copy(dialog = null) }

    // ---------------------------------------------------------------------------------------
    // The token

    /** "New token": the question first. */
    override fun onNewToken() = setState { state -> state.copy(dialog = McpDialog.NewToken) }

    private fun onConfirmNewToken() {
        scope.launch {
            try {
                val token = runner.newToken()
                setState { state -> state.copy(token = token, command = commandOf(state, token)) }
                showMessage(UiText.of(Res.string.mcp_token_new_done))
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                showMessage(UiText.of(Res.string.mcp_token_error))
            }
        }
    }

    override fun onHideToken() = setState { state -> state.copy(token = null, command = null) }

    override fun onTokenCopied() = showMessage(UiText.of(Res.string.mcp_token_copied))

    override fun onCommandCopied() = showMessage(UiText.of(Res.string.mcp_command_copied))

    // ---------------------------------------------------------------------------------------
    // The diagnostics

    override fun onShowAudit() = setState { state -> state.copy(auditOpen = true) }

    override fun onHideAudit() = setState { state -> state.copy(auditOpen = false) }

    /** The snackbar showed the message [id]. */
    override fun onMessageShown(id: Long) = setState { state -> if (state.message?.id == id) state.copy(message = null) else state }

    // ---------------------------------------------------------------------------------------

    private fun write(transform: (McpConfig) -> McpConfig) {
        settings.update { current -> current.copy(mcp = transform(current.mcp)) }
    }

    private fun showMessage(text: UiText) {
        messageIds += 1
        val id = messageIds
        setState { state -> state.copy(message = McpMessage(id, text)) }
    }

    /** Changes the state with [transform] and calculates the status and the command in the same step. */
    private fun setState(transform: (McpSettingsUiState) -> McpSettingsUiState) {
        _uiState.update { state ->
            val next = transform(state)
            next.copy(status = statusOf(next), command = next.token?.let { token -> commandOf(next, token) })
        }
    }

    private fun refresh() = setState { state -> state }

    private fun statusOf(state: McpSettingsUiState): McpStatusUiState {
        val config = state.config
        val current = runtime
        val running = current.state != McpServerState.Stopped
        val urls = if (running && current.listening.isNotEmpty()) {
            current.listening.map { address -> McpRules.url(address.substringBeforeLast(':'), config.port) }
        } else {
            BindAddresses.hosts(hosts, config.bindInterface).map { host -> McpRules.url(host, config.port) }
        }
        return McpStatusUiState(
            state = current.state,
            running = running,
            enabled = config.enabled,
            blockText = McpTexts.block(config, current.requested, permissionGranted?.value),
            errorText = current.error?.let(McpTexts::error),
            urls = urls,
            clients = current.clients,
            activation = config.activation,
            canStartStop = config.enabled && config.activation == McpActivation.OnDemand,
        )
    }

    private fun commandOf(state: McpSettingsUiState, token: String): String {
        val url = state.status.urls.firstOrNull() ?: McpRules.url("localhost", state.config.port)
        return McpRules.claudeCodeCommand(url, token)
    }

    private fun auditLine(row: McpCallRow): McpAuditLine = McpAuditLine(
        id = row.id,
        time = McpTexts.auditTime(row.atMs),
        client = row.client,
        name = row.tool ?: row.method,
        result = row.result,
        durationMs = row.durationMs,
    )

    companion object {
        /** The number of audit rows that the diagnostics show. */
        const val AUDIT_ROWS: Int = 200

        /** The interfaces of the list, in order. */
        val INTERFACES: List<BindInterface> = listOf(BindInterface.WiFi, BindInterface.Localhost, BindInterface.UsbEthernet)
    }
}
