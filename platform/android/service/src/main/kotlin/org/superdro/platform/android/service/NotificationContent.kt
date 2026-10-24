package org.superdro.platform.android.service

import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.KeepAliveSummary
import org.superdro.core.connection.SessionState
import org.superdro.core.model.TransportKind

/**
 * Gives the texts of the connection notification in the language of the device. The Android
 * implementation [ComposeServiceTexts] reads the string files of `core:resources`.
 */
interface ServiceTexts {

    /** The name of the notification channel: "Connections". */
    suspend fun channelName(): String

    /** The action "Disconnect all". */
    suspend fun disconnectAll(): String

    /** The title for [onlineCount] online machines, for example "2 machines online". */
    suspend fun title(onlineCount: Int): String

    /** The line for [count] offline remembered machines, for example "1 remembered machine offline". */
    suspend fun offlineRemembered(count: Int): String

    /** The line of one machine, for example "Mill: Connected (Bluetooth)". */
    suspend fun deviceLine(name: String, state: SessionState, transport: TransportKind): String

    /**
     * The text of the start at boot that Android refused: "Open SuperDRO to connect your machines".
     */
    suspend fun bootOpenApp(): String

    /**
     * The line of the MCP server, for example "MCP server on 192.168.1.5:5810". A null [address]
     * gives the line of a server with no address.
     */
    suspend fun mcpServer(address: String?): String
}

/**
 * The content of the one notification for all machines and the MCP server: one line for each
 * online machine, the number of the offline remembered machines, and the line of the server.
 *
 * @property title For example "2 machines online", or "No machine is online".
 * @property lines The line of each online machine, then the line of the offline machines when
 *   there are offline machines, then the line of the MCP server while it runs.
 * @property tapDevice The device of the tab that a tap on the notification opens: the first online
 *   device, else the first device of the summary.
 */
data class NotificationContent(
    val title: String,
    val lines: List<String>,
    val tapDevice: DeviceId?,
) {
    /** The text of the collapsed notification: the first line, or no text. */
    val text: String? get() = lines.firstOrNull()

    companion object {

        /** Gives the content of [summary] with the texts of [texts]. */
        suspend fun of(summary: KeepAliveSummary, texts: ServiceTexts): NotificationContent {
            val online = summary.devices.filter { line -> line.state.isOnline }
            val lines = buildList {
                online.forEach { line -> add(texts.deviceLine(line.name, line.state, line.transport)) }
                if (summary.offlineRememberedCount > 0) add(texts.offlineRemembered(summary.offlineRememberedCount))
                if (summary.mcpServer) add(texts.mcpServer(summary.mcpAddress))
            }
            return NotificationContent(
                title = texts.title(online.size),
                lines = lines,
                tapDevice = (online.firstOrNull() ?: summary.devices.firstOrNull())?.id,
            )
        }
    }
}
