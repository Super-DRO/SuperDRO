package org.superdro.core.ui.settings.keys

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.direct
import org.kodein.di.instance
import org.superdro.core.resources.Res
import org.superdro.core.resources.settings_undo
import org.superdro.core.ui.LocalAppSnackbarHostState
import org.superdro.core.ui.LocalDI
import org.superdro.core.ui.navigation.GlobalEntryScope
import org.superdro.core.ui.navigation.KeyMappingRoute
import org.superdro.core.ui.presenter.rememberPresenter
import org.superdro.core.ui.text.resolve

/**
 * The entry of the key mapping screen (global back stack). The presenter comes from the DI
 * graph (`SettingsKeysModule`). The messages show in the snackbar of the frame; the message of a
 * remove has "Undo".
 */
@Composable
fun KeyMappingEntry(scope: GlobalEntryScope, route: KeyMappingRoute) {
    val di = LocalDI.current
    val presenter = rememberPresenter { di.direct.instance<KeyMappingPresenter>() }
    val state by presenter.uiState.collectAsState()
    KeyMessageEffect(state.message, presenter)
    KeyMappingScreen(state = state, events = presenter, onBack = { scope.navigator.pop(route) })
}

/** Shows [message] in the snackbar of the frame. An undo message has the action "Undo". */
@Composable
internal fun KeyMessageEffect(message: KeyMessage?, presenter: KeyMappingPresenter) {
    val host = LocalAppSnackbarHostState.current
    val text = message?.text?.resolve()
    val undo = stringResource(Res.string.settings_undo)
    LaunchedEffect(message?.id) {
        if (message == null || text == null) return@LaunchedEffect
        val result = host.showSnackbar(
            message = text,
            actionLabel = if (message.undo) undo else null,
            withDismissAction = !message.undo,
            duration = if (message.undo) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed && message.undo) presenter.undoRemove(message.id)
        presenter.onMessageShown(message.id)
    }
}
