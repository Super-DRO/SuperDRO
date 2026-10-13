package org.superdro.core.ui.settings.logs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.kodein.di.direct
import org.kodein.di.instance
import org.superdro.core.ui.LocalDI
import org.superdro.core.ui.navigation.GlobalEntryScope
import org.superdro.core.ui.navigation.LogsRoute
import org.superdro.core.ui.presenter.rememberPresenter

/**
 * The entry of [LogsRoute]: the log screen with its presenter from the DI
 * graph (`SettingsLogsModule` of `core:di`). The messages of the presenter go to the snackbar of
 * the frame.
 */
@Composable
fun LogsEntry(scope: GlobalEntryScope, route: LogsRoute) {
    val di = LocalDI.current
    val presenter = rememberPresenter { di.direct.instance<LogsPresenter>() }
    val state by presenter.uiState.collectAsState()
    val message = state.message
    LaunchedEffect(message?.id) {
        if (message != null) {
            scope.showMessage(message.text)
            presenter.onMessageShown(message.id)
        }
    }
    LogsScreen(state = state, events = presenter, onBack = { scope.navigator.pop(route) })
}
