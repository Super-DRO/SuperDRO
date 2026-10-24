package org.superdro.core.connection

import kotlinx.coroutines.flow.StateFlow
import org.superdro.core.model.TransportKind

/**
 * Keeps the app process in the foreground while one or more sessions want a link. The Android
 * implementation `AndroidConnectionKeepAlive` starts and stops `ConnectionService`.
 *
 * `KeepAliveController` (common) collects the sessions and calls [update]. It removes repeated
 * values and waits 300 ms before it sends a change.
 */
interface ConnectionKeepAlive {

    /**
     * Gives the new summary. A null value means that no session wants a link: the service stops.
     * The first value that is not null starts the service.
     */
    fun update(summary: KeepAliveSummary?)

    /**
     * The state of the keep-alive. When [KeepAliveStatus.linksCanStopInBackground] is true, the tab
     * shows a warning: the links can stop while the app is in the background.
     * The default is [KeepAliveStatus.Idle] for a platform that does not report a state.
     */
    val status: StateFlow<KeepAliveStatus> get() = KeepAliveStatus.idleFlow
}

/**
 * The content of the one notification for all machines and the MCP server. It is not null while
 * one or more sessions have [SessionState.keepsAlive], or while the MCP server runs.
 *
 * @property devices One line for each session that has [SessionState.keepsAlive], in the order of
 *   the sessions. The notification shows the lines of the online machines.
 * @property offlineRememberedCount The number of sessions that are not online. The notification
 *   shows this number of offline remembered machines.
 * @property mcpServer True while the MCP server runs. The service then runs also with no online
 *   machine, and holds the Wi-Fi lock.
 * @property mcpAddress The first bound address of the MCP server as `host:port`, for the
 *   notification line "MCP server on <host:port>". Null while the server has no address.
 */
data class KeepAliveSummary(
    val devices: List<DeviceLine>,
    val offlineRememberedCount: Int = 0,
    val mcpServer: Boolean = false,
    val mcpAddress: String? = null,
) {
    /** The number of devices that are online. */
    val onlineCount: Int get() = devices.count { line -> line.state.isOnline }
}

/**
 * The MCP server as the keep-alive sees it. `KeepAliveController` takes a flow of this value:
 * null while the server does not run.
 *
 * @property addresses The bound addresses as `host:port`. Empty while the server starts, or while
 *   it waits for a free port.
 */
data class McpServerLine(val addresses: List<String> = emptyList())

/** One line of the notification: the name, the link type, and the state of one device. */
data class DeviceLine(
    val id: DeviceId,
    val name: String,
    val transport: TransportKind,
    val state: SessionState,
)
