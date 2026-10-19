package org.superdro.core.ui.tabs

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.kodein.di.direct
import org.kodein.di.instance
import org.kodein.di.instanceOrNull
import org.superdro.core.store.json.AppSettingsStore
import org.superdro.core.connection.ConnectionRequest
import org.superdro.core.connection.DeviceId
import org.superdro.core.ui.LocalDI
import org.superdro.core.ui.navigation.AddDeviceRoute
import org.superdro.core.ui.navigation.BatteryOptimizationRoute
import org.superdro.core.ui.navigation.EditMachineRoute
import org.superdro.core.ui.navigation.EntryKind
import org.superdro.core.ui.navigation.EntryRegistry
import org.superdro.core.ui.navigation.RememberedMachinesRoute
import org.superdro.core.ui.navigation.RenameMachineRoute
import org.superdro.core.ui.navigation.RouteArea
import org.superdro.core.ui.presenter.rememberPresenter
import org.superdro.core.ui.tabs.adddevice.AddDevicePresenter
import org.superdro.core.ui.tabs.adddevice.AddDeviceSheet
import org.superdro.core.ui.tabs.remembered.RememberedMachinesEvents
import org.superdro.core.ui.tabs.remembered.RememberedMachinesPane
import org.superdro.core.ui.tabs.remembered.RememberedMachinesPresenter

/**
 * The global entries of the app frame. The presenters come from the DI
 * graph: `AddDevicePresenter` and `RememberedMachinesPresenter` as providers, `EditMachinePresenter`
 * as a factory with the profile ID, and `RequestSender` as a singleton.
 */
fun EntryRegistry.tabsEntries() {
    global<AddDeviceRoute>(EntryKind.Sheet, RouteArea.Frame) { route ->
        val di = LocalDI.current
        val presenter = rememberPresenter { di.direct.instance<AddDevicePresenter>() }
        val state by presenter.uiState.collectAsState()
        val result = state.result
        LaunchedEffect(result) {
            if (result != null) navigator.pop(route)
        }
        AddDeviceSheet(state = state, presenter = presenter, onDismiss = { navigator.pop(route) })
    }
    global<RememberedMachinesRoute>(EntryKind.Screen, RouteArea.Frame) { route ->
        val di = LocalDI.current
        val presenter = rememberPresenter { di.direct.instance<RememberedMachinesPresenter>() }
        val tabs = di.direct.instanceOrNull<DeviceTabsPresenter>()
        val state by presenter.uiState.collectAsState()
        RememberedMachinesPane(
            state = state,
            events = paneEvents(
                presenter = presenter,
                edit = { id -> navigator.push(EditMachineRoute(id)) },
                open = { id ->
                    tabs?.onOpen(DeviceId(id))
                    navigator.pop(route)
                },
            ),
            onBack = { navigator.pop(route) },
        )
    }
    global<RenameMachineRoute>(EntryKind.Dialog, RouteArea.Frame) { route ->
        val di = LocalDI.current
        val presenter = rememberPresenter { di.direct.instance<String, EditMachinePresenter>(arg = route.profileId) }
        val state by presenter.uiState.collectAsState()
        LaunchedEffect(state.done) { if (state.done) navigator.pop(route) }
        EditMachineDialog(state = state, presenter = presenter, editLink = false, onDismiss = { navigator.pop(route) })
    }
    global<EditMachineRoute>(EntryKind.Dialog, RouteArea.Frame) { route ->
        val di = LocalDI.current
        val presenter = rememberPresenter { di.direct.instance<String, EditMachinePresenter>(arg = route.profileId) }
        val state by presenter.uiState.collectAsState()
        LaunchedEffect(state.done) { if (state.done) navigator.pop(route) }
        EditMachineDialog(state = state, presenter = presenter, editLink = true, onDismiss = { navigator.pop(route) })
    }
    // The battery request. The activity opens this dialog at the first
    // connect, after the notification permission, and a settings screen can open it on demand.
    // The app has only this battery dialog.
    global<BatteryOptimizationRoute>(EntryKind.Dialog, RouteArea.Frame) { route ->
        val di = LocalDI.current
        BatteryOptimizationDialog(
            onContinue = {
                di.direct.instance<AppSettingsStore>().update { settings -> settings.copy(batteryExemptionAsked = true) }
                di.direct.instance<RequestSender>().send(ConnectionRequest.RequestIgnoreBatteryOptimizations)
                navigator.pop(route)
            },
            onDismiss = {
                di.direct.instance<AppSettingsStore>().update { settings -> settings.copy(batteryExemptionAsked = true) }
                navigator.pop(route)
            },
        )
    }
}

/** The events of the pane: the presenter, the edit dialog through [edit], and the tab open through [open]. */
fun paneEvents(
    presenter: RememberedMachinesPresenter,
    edit: (String) -> Unit,
    open: (String) -> Unit = {},
): RememberedMachinesEvents =
    object : RememberedMachinesEvents {
        override fun onOpen(profileId: String) = open(profileId)
        override fun onConnect(profileId: String) = presenter.connect(profileId)
        override fun onPause(profileId: String) = presenter.pause(profileId)
        override fun onEdit(profileId: String) = edit(profileId)
        override fun onAskRemove(profileId: String) = presenter.askRemove(profileId)
        override fun onCancelRemove() = presenter.cancelRemove()
        override fun onConfirmRemove() = presenter.confirmRemove()
    }
