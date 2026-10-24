package org.superdro.core.ui.mcp

import androidx.compose.runtime.Immutable
import org.superdro.core.mcp.McpServerState
import org.superdro.core.model.BindInterface
import org.superdro.core.model.McpActivation
import org.superdro.core.model.McpConfig
import org.superdro.core.ui.text.UiText

/** One choice of the network interface list with the addresses that it has now. */
@Immutable
data class McpInterfaceChoice(val bind: BindInterface, val addresses: List<String>)

/**
 * The status of the server in the status section of the screen.
 *
 * @property urls The URLs `http://host:port/mcp` of the bound addresses while the server runs,
 *   else the URLs that the selected interface gives now.
 * @property blockText The reason why the server does not run, or null while it runs.
 * @property errorText The last error of the server, or null.
 * @property canStartStop True when the start and stop button shows: the switch is on and the
 *   activation is "On demand".
 */
@Immutable
data class McpStatusUiState(
    val state: McpServerState = McpServerState.Stopped,
    val running: Boolean = false,
    val enabled: Boolean = false,
    val blockText: UiText? = null,
    val errorText: UiText? = null,
    val urls: List<String> = emptyList(),
    val clients: Int = 0,
    val activation: McpActivation = McpActivation.OnDemand,
    val canStartStop: Boolean = false,
)

/** The dialogs of the MCP settings screen. */
sealed interface McpDialog {
    /** WARNING: the plain HTTP link has no encryption. */
    data object PlainHttpWarning : McpDialog

    /** The warning before the scope Populate goes on. */
    data object PopulateWarning : McpDialog

    /** The warning before the scope Control goes on. */
    data object ControlWarning : McpDialog

    /** The warning before the confirmation goes off. */
    data object ConfirmationOffWarning : McpDialog

    /** The question before "New token". */
    data object NewToken : McpDialog
}

/**
 * One line of the audit log.
 *
 * @property name The tool name, or the method of a request that is not a tool call.
 */
@Immutable
data class McpAuditLine(
    val id: Long,
    val time: String,
    val client: String,
    val name: String,
    val result: String,
    val durationMs: Long,
)

/** A message for the snackbar. [id] keeps two equal texts apart. */
@Immutable
data class McpMessage(val id: Long, val text: UiText)

/**
 * The UI state of the MCP settings screen. Each change writes at once into the app settings.
 *
 * @property config The stored configuration.
 * @property portText The text of the port field.
 * @property portProblem The problem of the port text, or null.
 * @property token The token, only after "New token" and until the user hides it or leaves.
 * @property command The Claude Code command with the token, while [token] shows.
 * @property auditOpen True while the diagnostics section shows the audit log.
 */
@Immutable
data class McpSettingsUiState(
    val loading: Boolean = true,
    val config: McpConfig = McpConfig(),
    val portText: String = McpConfig.DEFAULT_PORT.toString(),
    val portProblem: UiText? = null,
    val interfaces: List<McpInterfaceChoice> = emptyList(),
    val status: McpStatusUiState = McpStatusUiState(),
    val token: String? = null,
    val command: String? = null,
    val dialog: McpDialog? = null,
    val auditOpen: Boolean = false,
    val audit: List<McpAuditLine> = emptyList(),
    val message: McpMessage? = null,
)

/**
 * The state of the status chip of the MCP server in the status strip.
 *
 * @property visible True when the switch of the server is on.
 * @property openDetails True after a tap that opens the diagnostics, until the entry opens the
 *   settings screen with the diagnostics ([McpChipPresenter.detailsOpened]).
 */
@Immutable
data class McpChipUiState(
    val visible: Boolean = false,
    val state: McpServerState = McpServerState.Stopped,
    val running: Boolean = false,
    val activation: McpActivation = McpActivation.OnDemand,
    val openDetails: Boolean = false,
)
