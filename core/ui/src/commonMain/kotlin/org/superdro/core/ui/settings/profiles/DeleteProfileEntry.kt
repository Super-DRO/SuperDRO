package org.superdro.core.ui.settings.profiles

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.kodein.di.direct
import org.kodein.di.instance
import org.superdro.core.ui.LocalDI
import org.superdro.core.ui.navigation.DeleteProfileRoute
import org.superdro.core.ui.navigation.GlobalEntryScope
import org.superdro.core.ui.presenter.rememberPresenter

/**
 * The entry of the delete question of a profile (global [DeleteProfileRoute], a dialog). After
 * the delete, the dialog closes and the snackbar tells the result.
 */
@Composable
fun DeleteProfileEntry(scope: GlobalEntryScope, route: DeleteProfileRoute) {
    val di = LocalDI.current
    val presenter = rememberPresenter { di.direct.instance<String, DeleteProfilePresenter>(arg = route.profileId) }
    val state by presenter.uiState.collectAsState()
    LaunchedEffect(state.done) {
        if (state.done) {
            state.result?.let(scope::showMessage)
            scope.navigator.pop(route)
        }
    }
    DeleteProfileDialog(state = state, onDelete = presenter::delete, onDismiss = { scope.navigator.pop(route) })
}
