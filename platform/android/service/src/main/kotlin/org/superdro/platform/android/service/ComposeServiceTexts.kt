package org.superdro.platform.android.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.superdro.core.connection.SessionState
import org.superdro.core.model.TransportKind
import org.superdro.core.resources.Res
import org.superdro.core.resources.app_link_bluetooth
import org.superdro.core.resources.app_link_demo
import org.superdro.core.resources.app_link_usb
import org.superdro.core.resources.app_link_wifi
import org.superdro.core.resources.app_status_awaiting_data
import org.superdro.core.resources.app_status_connected
import org.superdro.core.resources.app_status_connecting
import org.superdro.core.resources.app_status_failed
import org.superdro.core.resources.app_status_no_data
import org.superdro.core.resources.app_status_offline
import org.superdro.core.resources.app_status_reconnecting
import org.superdro.core.resources.app_status_waiting_for_device
import org.superdro.core.resources.service_boot_open_app
import org.superdro.core.resources.service_channel_name
import org.superdro.core.resources.service_device_line
import org.superdro.core.resources.service_disconnect_all
import org.superdro.core.resources.service_mcp_server
import org.superdro.core.resources.service_mcp_server_no_address
import org.superdro.core.resources.service_offline_remembered
import org.superdro.core.resources.service_title_none_online
import org.superdro.core.resources.service_title_online

/**
 * The [ServiceTexts] of the string files of `core:resources`: `strings_service.xml`, and the state
 * and link names of `strings_app.xml`. Compose Resources selects the language of the device. The
 * functions work with no composition.
 *
 * All resource lookups run on [Dispatchers.IO] so `org.jetbrains.compose.resources.AsyncCache` mutex
 * acquisitions never suspend on `Dispatchers.Main` while the UI thread blocks in `stringResource`.
 */
object ComposeServiceTexts : ServiceTexts {

    override suspend fun channelName(): String = withContext(Dispatchers.IO) {
        getString(Res.string.service_channel_name)
    }

    override suspend fun disconnectAll(): String = withContext(Dispatchers.IO) {
        getString(Res.string.service_disconnect_all)
    }

    override suspend fun title(onlineCount: Int): String = withContext(Dispatchers.IO) {
        if (onlineCount <= 0) {
            getString(Res.string.service_title_none_online)
        } else {
            getPluralString(Res.plurals.service_title_online, onlineCount, onlineCount)
        }
    }

    override suspend fun offlineRemembered(count: Int): String = withContext(Dispatchers.IO) {
        getPluralString(Res.plurals.service_offline_remembered, count, count)
    }

    override suspend fun deviceLine(name: String, state: SessionState, transport: TransportKind): String =
        withContext(Dispatchers.IO) {
            getString(Res.string.service_device_line, name, stateName(state), getString(linkName(transport)))
        }

    override suspend fun bootOpenApp(): String = withContext(Dispatchers.IO) {
        getString(Res.string.service_boot_open_app)
    }

    override suspend fun mcpServer(address: String?): String = withContext(Dispatchers.IO) {
        if (address == null) getString(Res.string.service_mcp_server_no_address) else getString(Res.string.service_mcp_server, address)
    }

    private suspend fun stateName(state: SessionState): String = when (state) {
        SessionState.Disconnected -> getString(Res.string.app_status_offline)
        is SessionState.Connecting -> getString(Res.string.app_status_connecting)
        SessionState.AwaitingData -> getString(Res.string.app_status_awaiting_data)
        is SessionState.Connected -> getString(Res.string.app_status_connected)
        SessionState.NoData -> getString(Res.string.app_status_no_data)
        is SessionState.Reconnecting -> getString(Res.string.app_status_reconnecting, state.attempt)
        is SessionState.WaitingForDevice -> getString(Res.string.app_status_waiting_for_device)
        is SessionState.Failed -> getString(Res.string.app_status_failed)
    }

    private fun linkName(transport: TransportKind): StringResource = when (transport) {
        TransportKind.BluetoothSpp, TransportKind.Ble -> Res.string.app_link_bluetooth
        TransportKind.UsbSerial -> Res.string.app_link_usb
        TransportKind.Tcp -> Res.string.app_link_wifi
        TransportKind.Fake -> Res.string.app_link_demo
    }
}
