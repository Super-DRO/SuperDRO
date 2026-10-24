package org.superdro.core.ui.mcp

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
import org.superdro.core.ui.dataoutput.keyClipEntry
import org.superdro.core.ui.navigation.EntryKind
import org.superdro.core.ui.navigation.EntryRegistry
import org.superdro.core.ui.navigation.GlobalEntryScope
import org.superdro.core.ui.navigation.GlobalRoute
import org.superdro.core.ui.navigation.McpDiagnosticsRoute
import org.superdro.core.ui.navigation.McpSettingsRoute
import org.superdro.core.ui.navigation.RouteArea
import org.superdro.core.ui.presenter.rememberPresenter

/**
 * The entries of the MCP server: the settings screen `Settings > MCP server`
 * (`McpSettingsRoute`), the same screen with the audit log open (`McpDiagnosticsRoute`; the
 * dialog rule), and the status chip with start and stop in the status strip of the readout. The
 * central provider ([org.superdro.core.ui.navigation.appEntryRegistry]) calls this function after
 * the entries of the data output, so the chip comes next to the data output chip.
 */
fun EntryRegistry.mcpEntries() {
    global<McpSettingsRoute>(EntryKind.Screen, RouteArea.Settings) { route -> McpSettingsEntry(this, route) }
    global<McpDiagnosticsRoute>(EntryKind.Screen, RouteArea.Settings) { route -> McpSettingsEntry(this, route, diagnostics = true) }
    statusStripItem { McpStatusItem(this) }
}

/** The MCP settings screen. With [diagnostics], the audit log shows open in the diagnostics section. */
@Composable
private fun McpSettingsEntry(scope: GlobalEntryScope, route: GlobalRoute, diagnostics: Boolean = false) {
    val di = LocalDI.current
    val presenter = rememberPresenter { di.direct.instance<McpSettingsPresenter>() }
    LaunchedEffect(presenter) {
        if (diagnostics) presenter.onShowAudit()
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
    McpSettingsScreen(
        state = state,
        events = presenter,
        onBack = { scope.navigator.pop(route) },
        onCopy = { text -> coroutines.launch { clipboard.setClipEntry(keyClipEntry(text)) } },
    )
}
