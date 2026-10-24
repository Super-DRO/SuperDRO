package org.superdro.core.ui.mcp

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.StringResource
import org.superdro.core.mcp.McpError
import org.superdro.core.mcp.McpErrorType
import org.superdro.core.mcp.McpRules
import org.superdro.core.mcp.McpServerState
import org.superdro.core.model.BindInterface
import org.superdro.core.model.McpActivation
import org.superdro.core.model.McpConfig
import org.superdro.core.resources.Res
import org.superdro.core.resources.mcp_block_interface
import org.superdro.core.resources.mcp_block_not_requested
import org.superdro.core.resources.mcp_block_off
import org.superdro.core.resources.mcp_block_permission
import org.superdro.core.resources.mcp_block_plain_http
import org.superdro.core.resources.mcp_error_accept
import org.superdro.core.resources.mcp_error_bind
import org.superdro.core.resources.mcp_error_duplicate
import org.superdro.core.resources.mcp_error_no_address
import org.superdro.core.resources.mcp_error_port_taken
import org.superdro.core.resources.mcp_interface_localhost
import org.superdro.core.resources.mcp_interface_usb_ethernet
import org.superdro.core.resources.mcp_interface_wifi
import org.superdro.core.resources.mcp_no_detail
import org.superdro.core.resources.mcp_status_active
import org.superdro.core.resources.mcp_status_failed
import org.superdro.core.resources.mcp_status_ready
import org.superdro.core.resources.mcp_status_starting
import org.superdro.core.resources.mcp_status_stopped
import org.superdro.core.ui.settings.logs.LogFormat
import org.superdro.core.ui.text.UiText

/** The texts of the MCP server screen and chip. */
object McpTexts {

    /** The text of a server state. */
    fun status(state: McpServerState): StringResource = when (state) {
        McpServerState.Stopped -> Res.string.mcp_status_stopped
        McpServerState.Starting -> Res.string.mcp_status_starting
        McpServerState.Ready -> Res.string.mcp_status_ready
        McpServerState.Active -> Res.string.mcp_status_active
        is McpServerState.Failed -> Res.string.mcp_status_failed
    }

    /** The name of an interface of [McpRules.ALLOWED_INTERFACES]. */
    fun bindInterface(bind: BindInterface): StringResource = when (bind) {
        BindInterface.Localhost -> Res.string.mcp_interface_localhost
        BindInterface.UsbEthernet -> Res.string.mcp_interface_usb_ethernet
        else -> Res.string.mcp_interface_wifi
    }

    /**
     * Gives the reason why the server does not run, or null when [McpRules.runs] is true.
     *
     * @param permissionGranted The local network permission, or null when the platform needs none.
     */
    fun block(config: McpConfig, requested: Boolean, permissionGranted: Boolean?): UiText? = when {
        !config.enabled -> UiText.of(Res.string.mcp_block_off)
        config.bindInterface !in McpRules.ALLOWED_INTERFACES -> UiText.of(Res.string.mcp_block_interface)
        config.activation == McpActivation.OnDemand && !requested -> UiText.of(Res.string.mcp_block_not_requested)
        McpRules.needsPlainHttpWarning(config) -> UiText.of(Res.string.mcp_block_plain_http)
        permissionGranted == false && config.bindInterface != BindInterface.Localhost -> UiText.of(Res.string.mcp_block_permission)
        else -> null
    }

    /** The text of an error of the server. */
    fun error(error: McpError): UiText {
        val detail = error.detail ?: ""
        return when (error.type) {
            McpErrorType.PortBindFailed -> UiText.of(Res.string.mcp_error_bind, "${error.host ?: "?"}:${error.port ?: 0}", detail.ifEmpty { noDetail() })
            McpErrorType.AcceptFailed -> UiText.of(Res.string.mcp_error_accept, detail.ifEmpty { noDetail() })
            McpErrorType.NoAddress -> UiText.of(Res.string.mcp_error_no_address)
            McpErrorType.PortTaken -> UiText.of(Res.string.mcp_error_port_taken, error.port ?: 0, detail)
            McpErrorType.DuplicateName -> UiText.of(Res.string.mcp_error_duplicate, detail)
        }
    }

    private fun noDetail(): UiText = UiText.of(Res.string.mcp_no_detail)

    /** The time of an audit row as `yyyy-MM-dd HH:mm:ss` in the zone of the tablet. */
    fun auditTime(epochMs: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String =
        LogFormat.dateTime(Instant.fromEpochMilliseconds(epochMs), zone)
}
