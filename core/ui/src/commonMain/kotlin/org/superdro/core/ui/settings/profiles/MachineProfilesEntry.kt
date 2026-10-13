package org.superdro.core.ui.settings.profiles

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.kodein.di.direct
import org.kodein.di.instance
import org.superdro.core.connection.DeviceId
import org.superdro.core.ui.LocalDI
import org.superdro.core.ui.navigation.DeleteProfileRoute
import org.superdro.core.ui.navigation.GlobalEntryScope
import org.superdro.core.ui.navigation.MachineProfilesRoute
import org.superdro.core.ui.navigation.MachineSettingsRoute
import org.superdro.core.ui.presenter.rememberPresenter

/**
 * The entry of the machine profiles screen (global [MachineProfilesRoute], the overflow menu). The
 * delete opens [DeleteProfileRoute]. The machine settings of a
 * profile open in the back stack of its tab, and the global screens close.
 */
@Composable
fun MachineProfilesEntry(scope: GlobalEntryScope, route: MachineProfilesRoute) {
    val di = LocalDI.current
    val presenter = rememberPresenter { di.direct.instance<MachineProfilesPresenter>() }
    val state by presenter.uiState.collectAsState()
    val message = state.message
    LaunchedEffect(message?.id) {
        if (message != null) {
            scope.showMessage(message.text)
            presenter.onMessageShown(message.id)
        }
    }
    MachineProfilesScreen(
        state = state,
        presenter = presenter,
        navigation = MachineProfilesNavigation(
            onDelete = { id -> scope.navigator.push(DeleteProfileRoute(id)) },
            onMachineSettings = { id ->
                val tab = scope.tabNavigator(DeviceId(id))
                if (tab != null) {
                    tab.push(MachineSettingsRoute)
                    scope.navigator.clear()
                }
            },
            hasTab = { id -> scope.tabNavigator(DeviceId(id)) != null },
        ),
        onBack = { scope.navigator.pop(route) },
    )
}
