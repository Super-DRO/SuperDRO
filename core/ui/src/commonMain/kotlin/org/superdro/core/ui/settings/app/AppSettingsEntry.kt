package org.superdro.core.ui.settings.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.kodein.di.direct
import org.kodein.di.instance
import org.superdro.core.ui.LocalDI
import org.superdro.core.ui.navigation.AboutRoute
import org.superdro.core.ui.navigation.AppSettingsRoute
import org.superdro.core.ui.navigation.GlobalEntryScope
import org.superdro.core.ui.navigation.KeyMappingRoute
import org.superdro.core.ui.navigation.LogsRoute
import org.superdro.core.ui.navigation.MachineProfilesRoute
import org.superdro.core.ui.navigation.RememberedMachinesRoute
import org.superdro.core.ui.presenter.rememberPresenter

/**
 * The entry of the app settings (global [AppSettingsRoute], the gear). The presenter comes
 * from the DI graph. The battery optimization state is read again each time the screen resumes,
 * for example after the system setting.
 */
@Composable
fun AppSettingsEntry(scope: GlobalEntryScope, route: AppSettingsRoute) {
    val di = LocalDI.current
    val presenter = rememberPresenter { di.direct.instance<AppSettingsPresenter>() }
    val state by presenter.uiState.collectAsState()
    LifecycleResumeEffect(presenter) {
        presenter.onResume()
        onPauseOrDispose { }
    }
    val message = state.message
    LaunchedEffect(message?.id) {
        if (message != null) {
            scope.showMessage(message.text)
            presenter.onMessageShown(message.id)
        }
    }
    val links = remember(scope) {
        AppSettingsLinks(
            onRememberedMachines = { scope.navigator.push(RememberedMachinesRoute) },
            onMachineProfiles = { scope.navigator.push(MachineProfilesRoute) },
            onKeyMapping = { scope.navigator.push(KeyMappingRoute) },
            onLogs = { scope.navigator.push(LogsRoute) },
            onAbout = { scope.navigator.push(AboutRoute) },
        )
    }
    AppSettingsScreen(state = state, presenter = presenter, links = links, onBack = { scope.navigator.pop(route) })
}
