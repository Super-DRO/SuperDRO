package org.superdro.core.ui.tabs

import androidx.compose.runtime.Immutable
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.KeepAliveStatus
import org.superdro.core.connection.SessionState
import org.superdro.core.model.TransportKind
import org.superdro.core.ui.text.UiText

/**
 * One tab of a machine that is online, or that connects.
 *
 * @property transport The link type: the tab shows its icon (Bluetooth, USB, Wi-Fi, demo).
 * @property state The state of the session.
 * @property inGrace True while the machine is offline in the grace time of 10 s. The tab
 *   then shows the status of the session and the last values.
 */
@Immutable
data class DeviceTabUiState(
    val id: DeviceId,
    val name: String,
    val transport: TransportKind,
    val state: SessionState,
    val status: TabStatus,
    val inGrace: Boolean = false,
) {
    /** True when the tab menu can open the adapter details: the session is `Connected`. */
    val adapterDetailsEnabled: Boolean get() = state is SessionState.Connected
}

/** One message of the snackbar of the frame. [id] tells two equal texts apart. */
@Immutable
data class TabMessage(val id: Long, val text: UiText)

/**
 * The state of the frame: the tabs of the online machines, the selected
 * tab, the number of remembered machines, the open menus, and the messages of the snackbar.
 *
 * @property menuFor The machine whose long-press menu is open, or null.
 * @property overflowOpen True while the overflow menu of the global actions is open.
 * @property messages The messages that wait for the snackbar, oldest first.
 */
@Immutable
data class DeviceTabsUiState(
    val tabs: List<DeviceTabUiState> = emptyList(),
    val selected: DeviceId? = null,
    val rememberedCount: Int = 0,
    val menuFor: DeviceId? = null,
    val overflowOpen: Boolean = false,
    val messages: List<TabMessage> = emptyList(),
    val backgroundWarning: Boolean = false,
    val keepAliveStatus: KeepAliveStatus = if (backgroundWarning) KeepAliveStatus.PrerequisiteMissing else KeepAliveStatus.Idle,
) {
    /** The index of the selected tab in [tabs], or -1. */
    val selectedIndex: Int get() = tabs.indexOfFirst { tab -> tab.id == selected }

    /** The selected tab, or null. */
    val selectedTab: DeviceTabUiState? get() = tabs.firstOrNull { tab -> tab.id == selected }
}

/** The events of the frame. [DeviceTabsPresenter] handles them. The navigation goes through the frame. */
interface DeviceTabsEvents {
    /** One tap on a tab selects it. */
    fun onSelect(id: DeviceId)

    /** A long press on a tab opens its menu. */
    fun onOpenMenu(id: DeviceId)

    /** The tab menu closes with no action. */
    fun onCloseMenu()

    /** The menu item "Disconnect": the pause of the machine. */
    fun onPause(id: DeviceId)

    /** The overflow menu opens. */
    fun onOpenOverflow()

    /** The overflow menu closes. */
    fun onCloseOverflow()

    /** The overflow item "Disconnect all". */
    fun onDisconnectAll()

    /** The snackbar showed the message [id]. */
    fun onMessageShown(id: Long)

    /** Requests the runtime permission needed to keep machine connections active in the background. */
    fun onRequestBackgroundPermission() = Unit

    /** Opens the system app settings so the user can allow background machine connections. */
    fun onOpenBackgroundAppSettings() = Unit
}
