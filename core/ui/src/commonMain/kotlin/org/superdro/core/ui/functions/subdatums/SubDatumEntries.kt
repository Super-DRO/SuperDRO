package org.superdro.core.ui.functions.subdatums

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.superdro.core.model.SidePanelMode
import org.superdro.core.resources.Res
import org.superdro.core.resources.sd_select_first
import org.superdro.core.ui.functions.common.AttachUndoHost
import org.superdro.core.ui.functions.common.BlockedDialog
import org.superdro.core.ui.functions.common.FunctionServices
import org.superdro.core.ui.functions.common.functionTab
import org.superdro.core.ui.functions.common.rememberFunctionServices
import org.superdro.core.ui.functions.importing.ImportKind
import org.superdro.core.ui.functions.importing.startImport
import org.superdro.core.ui.navigation.AddCurrentPointRoute
import org.superdro.core.ui.navigation.ArbitraryHoleCircleRoute
import org.superdro.core.ui.navigation.ArcCutRoute
import org.superdro.core.ui.navigation.EntryKind
import org.superdro.core.ui.navigation.EntryRegistry
import org.superdro.core.ui.navigation.EquidistantArrayRoute
import org.superdro.core.ui.navigation.HoleCircleRoute
import org.superdro.core.ui.navigation.HoleGridRoute
import org.superdro.core.ui.navigation.RouteArea
import org.superdro.core.ui.navigation.ShapeMenuRoute
import org.superdro.core.ui.navigation.StepAndRepeatRoute
import org.superdro.core.ui.navigation.SubDatumCommand
import org.superdro.core.ui.navigation.SubDatumCommandRoute
import org.superdro.core.ui.navigation.SubDatumEditRoute
import org.superdro.core.ui.navigation.TabEntryScope
import org.superdro.core.ui.navigation.TabRoute
import org.superdro.core.ui.navigation.TagSubDatumsRoute
import org.superdro.core.ui.navigation.WorkspaceCommandsRoute
import org.superdro.core.ui.navigation.WorkspaceTransformKind
import org.superdro.core.ui.navigation.WorkspaceTransformRoute
import org.superdro.core.ui.navigation.WorkspacesRoute
import org.superdro.core.ui.presenter.Presenter
import org.superdro.core.ui.presenter.rememberPresenter
import org.superdro.core.ui.text.UiText

/**
 * The entries of the sub-datum panel, the shape menu, the sub-datum edit sheet, and the bulk tag
 * sheet. [org.superdro.core.ui.functions.functionEntries]
 * calls this function.
 */
internal fun EntryRegistry.subDatumEntries() {
    tab<ShapeMenuRoute>(EntryKind.Sheet, RouteArea.Functions) { route -> ShapeMenuEntry(this, route) }
    tab<SubDatumEditRoute>(EntryKind.Sheet, RouteArea.Functions) { route -> SubDatumEditEntry(this, route) }
    tab<TagSubDatumsRoute>(EntryKind.Sheet, RouteArea.Functions) { route -> TagEntry(this, route) }
    tab<AddCurrentPointRoute>(EntryKind.Dialog, RouteArea.Functions) { route -> AddPointEntry(this, route) }
    sidePanel(SidePanelMode.SubDatums) { SubDatumPanelEntry(this) }
    // The single tap of "+": the entry of AddCurrentPointRoute adds a sub-datum at the current position.
    addSubDatumTap { navigator.push(AddCurrentPointRoute) }
}

/** Gives the sub-datum panel presenter of the tab of this scope. The tab owns it. */
fun TabEntryScope.subDatumPanel(services: FunctionServices): SubDatumPanelPresenter =
    tabPresenter(SubDatumPanelPresenter.NAME) { SubDatumPanelPresenter(deviceId.value, services, readout, functionTab(services).undo) }

@Composable
private fun SubDatumPanelEntry(scope: TabEntryScope) {
    val services = rememberFunctionServices()
    val tab = scope.functionTab(services)
    AttachUndoHost(tab.undo)
    val panel = scope.subDatumPanel(services)
    val state by panel.uiState.collectAsState()
    val events = remember(panel, scope) { PanelEvents(panel, scope, services) }
    SubDatumPanel(state, events)
}

/** The events of the panel: the presenter, and the routes of the other parts. */
private class PanelEvents(
    private val panel: SubDatumPanelPresenter,
    private val scope: TabEntryScope,
    private val services: FunctionServices,
) : SubDatumPanelEvents {
    private fun open(route: TabRoute) {
        panel.onMenuDismiss()
        scope.navigator.push(route)
    }

    override fun onTap(id: String) = panel.onTap(id)
    override fun onLongPress(id: String) = panel.onLongPress(id)
    override fun onMenuDismiss() = panel.onMenuDismiss()
    override fun onEdit(id: String) = open(SubDatumEditRoute(id))
    override fun onSelectPoint(id: String) = panel.onToggleSelected(id)
    override fun onDelete(id: String) = panel.onDelete(id)
    override fun onSetOrigin(id: String) = panel.onSetOrigin(id)
    override fun onAlignSpindle(id: String) = open(SubDatumCommandRoute(id, SubDatumCommand.AlignToSpindle))
    override fun onAlignOrigin(id: String) = open(SubDatumCommandRoute(id, SubDatumCommand.AlignOriginToSubDatum))
    override fun onPair(pointA: String, pointB: String) = open(WorkspaceTransformRoute(WorkspaceTransformKind.Pair, pointA, pointB))
    override fun onWorkspaces() = open(WorkspacesRoute)
    override fun onSelecting(on: Boolean) = panel.onSelecting(on)
    override fun onSelectAll() = panel.onSelectAll()
    override fun onDeselect() = panel.onDeselect()
    override fun onTagSelected() {
        if (panel.selectedPoints().isEmpty()) scope.readout.showMessage(UiText.of(Res.string.sd_select_first)) else open(TagSubDatumsRoute)
    }
    override fun onCopy() = open(StepAndRepeatRoute(panel.selectedPoints().map { it.id }))
    override fun onFilterToggle() = panel.onFilterToggle()
    override fun onFilterLongPress() = panel.onFilterLongPress()
    override fun onFilterRow(tag: String?) = panel.onFilterRow(tag)
    override fun onToggle3d() = panel.onToggle3d()
    override fun onDeleteSelectedOrAll() = if (panel.selection().isEmpty()) panel.onDeleteAll() else panel.onDeleteSelected()
    override fun onDeleteAll() = panel.onDeleteAll()
    override fun onFirst() = panel.onFirst()
    override fun onPrevious() = panel.onPrevious()
    override fun onNext() = panel.onNext()
    override fun onLast() = panel.onLast()
    override fun onImport() = scope.startImport(services, ImportKind.SubDatumCsv)
    override fun onExport() = panel.onExport(scope.readout::showMessage)
    override fun onWorkspaceCommands() = open(WorkspaceCommandsRoute)
    override fun onUndoTransform() = panel.onUndoLastTransform(scope.readout::showMessage)
}

/**
 * The run of one "+" tap. The add runs in the scope of the panel presenter of the tab, so
 * the pop of the entry does not stop it.
 */
private class AddPointRun(panel: SubDatumPanelPresenter, onUnavailable: () -> Unit) : Presenter {
    private val mutable = MutableStateFlow<AddPointOutcome?>(null)
    val outcome: StateFlow<AddPointOutcome?> = mutable.asStateFlow()

    init {
        panel.scope.launch {
            val result = panel.addCurrentPoint()
            if (result is AddPointOutcome.Unavailable) onUnavailable()
            mutable.value = result
        }
    }

    override fun close() = Unit
}

@Composable
private fun AddPointEntry(scope: TabEntryScope, route: AddCurrentPointRoute) {
    val services = rememberFunctionServices()
    val panel = scope.subDatumPanel(services)
    val run = rememberPresenter { AddPointRun(panel) { services.sounds.error(scope.deviceId) } }
    val outcome by run.outcome.collectAsState()
    when (val current = outcome) {
        null -> Unit
        is AddPointOutcome.Blocked -> BlockedDialog(current.message) { scope.navigator.pop(route) }
        is AddPointOutcome.Unavailable -> LaunchedEffect(current) {
            scope.readout.showMessage(current.message)
            scope.navigator.pop(route)
        }
        is AddPointOutcome.Added -> LaunchedEffect(current) { scope.navigator.pop(route) }
    }
}

@Composable
private fun ShapeMenuEntry(scope: TabEntryScope, route: ShapeMenuRoute) {
    val frame by scope.readout.frame.collectAsState()
    val lathe = frame?.profile?.machineType?.isLathe == true
    ShapeMenuSheet(
        lathe = lathe,
        onSelect = { item ->
            val target: TabRoute = when (item) {
                ShapeItem.CurrentPoint -> AddCurrentPointRoute
                ShapeItem.CustomPoint -> SubDatumEditRoute(null)
                ShapeItem.Arc -> ArcCutRoute
                ShapeItem.HoleCircle -> HoleCircleRoute
                ShapeItem.ArbitraryHoleCircle -> ArbitraryHoleCircleRoute(advanced = false)
                ShapeItem.HoleGrid -> HoleGridRoute
                ShapeItem.Equidistant -> EquidistantArrayRoute
            }
            scope.navigator.replaceTop(target)
        },
        onDismiss = { scope.navigator.pop(route) },
    )
}

@Composable
private fun SubDatumEditEntry(scope: TabEntryScope, route: SubDatumEditRoute) {
    val services = rememberFunctionServices()
    val tab = scope.functionTab(services)
    AttachUndoHost(tab.undo)
    val presenter = rememberPresenter { SubDatumEditPresenter(route.subDatumId, services, scope.readout) }
    val frame by scope.readout.frame.collectAsState()
    LaunchedEffect(frame != null) { frame?.let(presenter::load) }
    val state by presenter.uiState.collectAsState()
    val coroutines = rememberCoroutineScope()
    val events = remember(presenter, scope) {
        object : SubDatumEditEvents {
            override fun onName(text: String) = presenter.onName(text)
            override fun onNote(text: String) = presenter.onNote(text)
            override fun onTags(text: String) = presenter.onTags(text)
            override fun onText(field: SubDatumField, text: String) = presenter.onText(field, text)
            override fun onUnit(field: SubDatumField, metric: Boolean) = presenter.onUnit(field, metric)
            override fun onSave() {
                coroutines.launch { if (presenter.save()) scope.navigator.pop(route) }
            }
            override fun onDelete() {
                route.subDatumId?.let { id -> scope.subDatumPanel(services).onDelete(id) }
                scope.navigator.pop(route)
            }
            override fun onDismiss() = scope.navigator.pop(route)
        }
    }
    SubDatumEditSheet(state, events)
}

@Composable
private fun TagEntry(scope: TabEntryScope, route: TagSubDatumsRoute) {
    val services = rememberFunctionServices()
    val tab = scope.functionTab(services)
    AttachUndoHost(tab.undo)
    val panel = scope.subDatumPanel(services)
    val presenter = rememberPresenter {
        val tags = scope.readout.frame.value?.workspace?.subDatums?.flatMap { it.tags }.orEmpty()
        TagSheetPresenter(panel.selectedPoints(), tags, services, tab.undo)
    }
    val state by presenter.uiState.collectAsState()
    TagSheet(
        state = state,
        events = object : TagSheetEvents {
            override fun onText(text: String) = presenter.onText(text)
            override fun onSuggestion(tag: String) = presenter.onSuggestion(tag)
            override fun onApply() {
                // The write runs in the scope of the tab, so the close of the sheet does not stop it.
                tab.scope.launch { presenter.apply() }
                scope.navigator.pop(route)
            }
            override fun onDismiss() = scope.navigator.pop(route)
        },
    )
}
