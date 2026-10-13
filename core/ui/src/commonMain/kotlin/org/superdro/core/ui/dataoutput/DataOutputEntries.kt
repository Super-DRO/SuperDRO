package org.superdro.core.ui.dataoutput

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalClipboard
import kotlinx.coroutines.launch
import org.kodein.di.direct
import org.kodein.di.instance
import org.superdro.core.ui.LocalDI
import org.superdro.core.ui.navigation.DataOutputDetailsRoute
import org.superdro.core.ui.navigation.DataOutputRoute
import org.superdro.core.ui.navigation.EntryKind
import org.superdro.core.ui.navigation.EntryRegistry
import org.superdro.core.ui.navigation.GlobalEntryScope
import org.superdro.core.ui.navigation.GlobalRoute
import org.superdro.core.ui.navigation.RouteArea
import org.superdro.core.ui.presenter.rememberPresenter

/**
 * The entries of the data output: the data output screen (`DataOutputRoute`), the same screen
 * with the diagnostics open (`DataOutputDetailsRoute`; the dialog rule), and the status chip with
 * start and stop in the status strip of the readout. The central provider
 * ([org.superdro.core.ui.navigation.appEntryRegistry]) calls this function after the entries of
 * the settings area, so that this entry replaces the placeholder of the settings area.
 */
fun EntryRegistry.dataOutputEntries() {
    global<DataOutputRoute>(EntryKind.Screen, RouteArea.Settings) { route -> DataOutputEntry(this, route) }
    global<DataOutputDetailsRoute>(EntryKind.Screen, RouteArea.Settings) { route -> DataOutputEntry(this, route, details = true) }
    statusStripItem { DataOutputStatusItem(this) }
}

/**
 * The data output screen. It starts with the machine of the selected tab. With [details], the
 * diagnostics show open in the status section.
 */
@Composable
private fun DataOutputEntry(scope: GlobalEntryScope, route: GlobalRoute, details: Boolean = false) {
    val di = LocalDI.current
    val presenter = rememberPresenter { di.direct.instance<DataOutputPresenter>() }
    LaunchedEffect(presenter) {
        presenter.selectProfile(scope.selectedDevice?.value)
        if (details) presenter.onShowDiagnostics()
    }
    val state by presenter.uiState.collectAsState()
    state.message?.let { message ->
        LaunchedEffect(message.id) {
            scope.showMessage(message.text)
            presenter.onMessageShown(message.id)
        }
    }
    val clipboard = LocalClipboard.current
    val coroutines = rememberCoroutineScope()
    DataOutputScreen(
        state = state,
        events = presenter,
        onBack = { scope.navigator.pop(route) },
        onCopyKey = { text -> coroutines.launch { clipboard.setClipEntry(keyClipEntry(text)) } },
    )
}
