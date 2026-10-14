package org.superdro.core.ui.functions.tools

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import org.superdro.core.model.AxisId
import org.superdro.core.model.SidePanelMode
import org.superdro.core.model.Tool
import org.superdro.core.resources.Res
import org.superdro.core.resources.tools_deleted
import org.superdro.core.ui.functions.common.AttachUndoHost
import org.superdro.core.ui.functions.common.FunctionTabPresenter
import org.superdro.core.ui.functions.common.functionTab
import org.superdro.core.ui.functions.common.rememberFunctionServices
import org.superdro.core.ui.functions.importing.ImportKind
import org.superdro.core.ui.functions.importing.startImport
import org.superdro.core.ui.navigation.DeleteToolRoute
import org.superdro.core.ui.navigation.EntryKind
import org.superdro.core.ui.navigation.EntryRegistry
import org.superdro.core.ui.navigation.RouteArea
import org.superdro.core.ui.navigation.TabEntryScope
import org.superdro.core.ui.navigation.ToolEditRoute
import org.superdro.core.ui.navigation.ToolLibraryRoute
import org.superdro.core.ui.presenter.rememberPresenter
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.navigation.ToolGeometryRoute

/**
 * The entries of the tool library, the tool edit sheet, and the tool panel.
 * [org.superdro.core.ui.functions.functionEntries] calls this function.
 */
internal fun EntryRegistry.toolEntries() {
    tab<ToolLibraryRoute>(EntryKind.Screen, RouteArea.Functions) { _ -> ToolLibraryEntry(this) }
    tab<ToolEditRoute>(EntryKind.Sheet, RouteArea.Functions) { route -> ToolEditEntry(this, route) }
    tab<DeleteToolRoute>(EntryKind.Dialog, RouteArea.Functions) { route -> DeleteToolEntry(this, route) }
    sidePanel(SidePanelMode.Tools) { ToolPanelEntry(this) }
}

@Composable
private fun ToolLibraryEntry(scope: TabEntryScope) {
    val services = rememberFunctionServices()
    val tab = scope.functionTab(services)
    AttachUndoHost(tab.undo)
    val presenter = rememberPresenter { ToolLibraryPresenter(scope.deviceId.value, services, tab.undo, scope::showMessage) }
    val state by presenter.uiState.collectAsState()
    val events = remember(presenter, scope) {
        object : ToolLibraryEvents {
            override fun onBack() = scope.navigator.pop(ToolLibraryRoute)
            override fun onAdd() = scope.navigator.push(ToolEditRoute(null))
            override fun onEdit(id: String) = scope.navigator.push(ToolEditRoute(id))
            override fun onDelete(id: String) = scope.navigator.push(DeleteToolRoute(id))
            override fun onToggleSelected(id: String) = presenter.onToggleSelected(id)
            override fun onSelectAll() = presenter.onSelectAll()
            override fun onClearSelection() = presenter.onClearSelection()
            override fun onDeleteSelected() = presenter.onDeleteSelected()
            override fun onSort(sort: ToolSort) = presenter.onSort(sort)
            override fun onSharedLibrary(on: Boolean) = presenter.onSharedLibrary(on)
            override fun onImport() = scope.startImport(services, ImportKind.ToolCsv)
            override fun onExport() = presenter.onExport()
        }
    }
    ToolLibraryScreen(state, events)
}

@Composable
private fun ToolEditEntry(scope: TabEntryScope, route: ToolEditRoute) {
    val services = rememberFunctionServices()
    val presenter = rememberPresenter {
        ToolEditPresenter(scope.deviceId.value, route.toolId, services, scope.readout.frame, scope.readout::positionUnavailable, scope.readout::latestFrame)
    }
    val state by presenter.uiState.collectAsState()
    val coroutines = rememberCoroutineScope()
    val message = state.message
    LaunchedEffect(message) {
        if (message != null) {
            scope.showMessage(message)
            presenter.onMessageShown()
        }
    }
    val events = remember(presenter, scope) {
        object : ToolEditEvents {
            override fun onName(text: String) = presenter.onName(text)
            override fun onDescription(text: String) = presenter.onDescription(text)
            override fun onMetric(metric: Boolean) = presenter.onMetric(metric)
            override fun onDiameter(text: String) = presenter.onDiameter(text)
            override fun onToothCount(text: String) = presenter.onToothCount(text)
            override fun onZOffset(text: String) = presenter.onZOffset(text)
            override fun onXOffset(text: String) = presenter.onXOffset(text)
            override fun onInternal(internal: Boolean) = presenter.onInternal(internal)
            override fun onSync(axis: AxisId) = presenter.onSync(axis)
            override fun onSave() {
                coroutines.launch { if (presenter.save()) scope.navigator.pop(route) }
            }
            override fun onDismiss() = scope.navigator.pop(route)
            override fun onGeometry() {
                // The cutter geometry screen of the stored lathe tool.
                val id = presenter.uiState.value.toolId ?: return
                scope.navigator.push(ToolGeometryRoute(id))
            }
        }
    }
    ToolEditSheet(state, events)
}

@Composable
private fun DeleteToolEntry(scope: TabEntryScope, route: DeleteToolRoute) {
    val services = rememberFunctionServices()
    val tab = scope.functionTab(services)
    AttachUndoHost(tab.undo)
    val tool by produceState<Tool?>(null, route.toolId) {
        value = services.tools.loadLibrary(scope.deviceId.value).firstOrNull { it.id == route.toolId }
    }
    DeleteToolDialog(
        name = tool?.name.orEmpty(),
        onConfirm = {
            tool?.let { current -> deleteWithUndo(tab, current) }
            scope.navigator.pop(route)
        },
        onDismiss = { scope.navigator.pop(route) },
    )
}

/** Deletes [tool] in the scope of the tab presenter, with undo for 10 s. */
internal fun deleteWithUndo(tab: FunctionTabPresenter, tool: Tool) {
    val services = tab.services
    tab.scope.launch {
        services.tools.deleteTools(listOf(tool.id))
        tab.undo.offer(UiText.of(Res.string.tools_deleted, 1)) { services.tools.saveTools(tab.profileId, listOf(tool)) }
    }
}

@Composable
private fun ToolPanelEntry(scope: TabEntryScope) {
    val services = rememberFunctionServices()
    val presenter = rememberPresenter("functions.toolPanel") { ToolPanelPresenter(scope.deviceId.value, services, scope.readout) }
    val state by presenter.uiState.collectAsState()
    ToolPanel(state, onTap = presenter::onTap, onOpenLibrary = { scope.navigator.push(ToolLibraryRoute) })
}
