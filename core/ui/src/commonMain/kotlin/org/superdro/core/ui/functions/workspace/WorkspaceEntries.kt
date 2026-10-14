package org.superdro.core.ui.functions.workspace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import org.superdro.core.functions.transform.FlipAxis
import org.superdro.core.functions.transform.GuidePolicy
import org.superdro.core.model.AxisId
import org.superdro.core.model.SidePanelMode
import org.superdro.core.model.Workspace
import org.superdro.core.ui.functions.common.AttachUndoHost
import org.superdro.core.ui.functions.common.functionTab
import org.superdro.core.ui.functions.common.rememberFunctionServices
import org.superdro.core.ui.navigation.DeleteWorkspaceRoute
import org.superdro.core.ui.navigation.EntryKind
import org.superdro.core.ui.navigation.EntryRegistry
import org.superdro.core.ui.navigation.MachineDatumEditRoute
import org.superdro.core.ui.navigation.RouteArea
import org.superdro.core.ui.navigation.SubDatumCommand
import org.superdro.core.ui.navigation.SubDatumCommandRoute
import org.superdro.core.ui.navigation.TabEntryScope
import org.superdro.core.ui.navigation.WorkspaceCommandsRoute
import org.superdro.core.ui.navigation.WorkspaceTransformKind
import org.superdro.core.ui.navigation.WorkspaceTransformRoute
import org.superdro.core.ui.navigation.WorkspacesRoute
import org.superdro.core.ui.presenter.rememberPresenter

/**
 * The entries of the workspaces sheet, the machine datums, and the workspace transforms.
 * [org.superdro.core.ui.functions.functionEntries] calls this function.
 */
internal fun EntryRegistry.workspaceEntries() {
    tab<WorkspacesRoute>(EntryKind.Sheet, RouteArea.Functions) { _ -> WorkspacesEntry(this) }
    tab<DeleteWorkspaceRoute>(EntryKind.Dialog, RouteArea.Functions) { route -> DeleteWorkspaceEntry(this, route) }
    tab<MachineDatumEditRoute>(EntryKind.Sheet, RouteArea.Functions) { route -> MachineDatumEditEntry(this, route) }
    tab<WorkspaceTransformRoute>(EntryKind.Sheet, RouteArea.Functions) { route -> WorkspaceTransformEntry(this, route) }
    tab<WorkspaceCommandsRoute>(EntryKind.Dialog, RouteArea.Functions) { _ -> WorkspaceCommandsEntry(this) }
    tab<SubDatumCommandRoute>(EntryKind.Dialog, RouteArea.Functions) { route -> SubDatumCommandEntry(this, route) }
    sidePanel(SidePanelMode.MachineDatums) { MachineDatumPanelEntry(this) }
}

@Composable
private fun WorkspacesEntry(scope: TabEntryScope) {
    val services = rememberFunctionServices()
    val presenter = rememberPresenter { WorkspacesPresenter(scope.deviceId.value, services, scope.readout, scope.readout::showMessage) }
    val state by presenter.uiState.collectAsState()
    val fields by presenter.fields.collectAsState()
    val coroutines = rememberCoroutineScope()
    val events = remember(presenter, scope) {
        object : WorkspacesEvents {
            override fun onSelect(id: String) {
                coroutines.launch { if (presenter.select(id)) scope.navigator.pop(WorkspacesRoute) }
            }
            override fun onToggleSelected(id: String) = presenter.onToggleSelected(id)
            override fun onClearSelection() = presenter.onClearSelection()
            override fun onDeleteSelectedRequest() = presenter.onDeleteSelectedRequest()
            override fun onDeleteSelectedConfirm() = presenter.onDeleteSelectedConfirm()
            override fun onDeleteSelectedCancel() = presenter.onDeleteSelectedCancel()
            override fun onDelete(id: String) = scope.navigator.push(DeleteWorkspaceRoute(id))
            override fun onRenameStart(id: String) = presenter.onRenameStart(id)
            override fun onRenameText(text: String) = presenter.onRenameText(text)
            override fun onRenameConfirm() = presenter.onRenameConfirm()
            override fun onRenameCancel() = presenter.onRenameCancel()
            override fun onNewName(text: String) = presenter.onNewName(text)
            override fun onCreate() = presenter.onCreate()
            override fun onUndoLastTransform() = presenter.onUndoLastTransform()
            override fun onDismiss() = scope.navigator.pop(WorkspacesRoute)
        }
    }
    WorkspacesSheet(state, fields, events)
}

@Composable
private fun DeleteWorkspaceEntry(scope: TabEntryScope, route: DeleteWorkspaceRoute) {
    val services = rememberFunctionServices()
    val tab = scope.functionTab(services)
    val workspace by produceState<Workspace?>(null, route.workspaceId) { value = services.machines.loadWorkspace(route.workspaceId) }
    DeleteWorkspaceDialog(
        name = workspace?.name.orEmpty(),
        onConfirm = {
            workspace?.let { target ->
                tab.scope.launch { scope.readout.showMessage(WorkspacesPresenter.delete(services, target)) }
            }
            scope.navigator.pop(route)
        },
        onDismiss = { scope.navigator.pop(route) },
    )
}

@Composable
private fun MachineDatumPanelEntry(scope: TabEntryScope) {
    val services = rememberFunctionServices()
    val tab = scope.functionTab(services)
    AttachUndoHost(tab.undo)
    val presenter = rememberPresenter("functions.machineDatums") {
        MachineDatumPanelPresenter(scope.deviceId.value, services, scope.readout, tab.undo, scope.readout::showMessage)
    }
    val state by presenter.uiState.collectAsState()
    val events = remember(presenter, scope) {
        object : MachineDatumPanelEvents {
            override fun onTap(id: String) = presenter.onTap(id)
            override fun onEdit(id: String) = scope.navigator.push(MachineDatumEditRoute(id))
            override fun onDelete(id: String) = presenter.onDelete(id)
            override fun onAdd() {
                if (presenter.onAdd()) scope.navigator.push(MachineDatumEditRoute(null))
            }
            override fun onQuickCapture() = presenter.onQuickCapture()
            override fun onApplyConfirm() = presenter.onApplyConfirm()
            override fun onApplyCancel() = presenter.onApplyCancel()
            override fun onProblemShown() = presenter.onProblemShown()
        }
    }
    MachineDatumPanel(state, events)
}

@Composable
private fun MachineDatumEditEntry(scope: TabEntryScope, route: MachineDatumEditRoute) {
    val services = rememberFunctionServices()
    val presenter = rememberPresenter {
        MachineDatumEditPresenter(scope.deviceId.value, route.datumId, services, scope.readout.frame, scope.readout::positionUnavailable, scope.readout::latestFrame)
    }
    val state by presenter.uiState.collectAsState()
    val coroutines = rememberCoroutineScope()
    val message = state.message
    LaunchedEffect(message) {
        if (message != null) {
            scope.readout.showMessage(message)
            presenter.onMessageShown()
        }
    }
    val events = remember(presenter, scope) {
        object : MachineDatumEditEvents {
            override fun onName(text: String) = presenter.onName(text)
            override fun onNote(text: String) = presenter.onNote(text)
            override fun onText(axis: AxisId, text: String) = presenter.onText(axis, text)
            override fun onUnit(axis: AxisId, metric: Boolean) = presenter.onUnit(axis, metric)
            override fun onSync(axis: AxisId) = presenter.onSync(axis)
            override fun onSave() {
                coroutines.launch {
                    if (presenter.save()) {
                        presenter.uiState.value.message?.let(scope.readout::showMessage)
                        scope.navigator.pop(route)
                    }
                }
            }
            override fun onDismiss() = scope.navigator.pop(route)
        }
    }
    MachineDatumEditSheet(state, events)
}

@Composable
private fun WorkspaceTransformEntry(scope: TabEntryScope, route: WorkspaceTransformRoute) {
    val services = rememberFunctionServices()
    val tab = scope.functionTab(services)
    AttachUndoHost(tab.undo)
    if (route.kind == WorkspaceTransformKind.Pair) {
        val presenter = rememberPresenter {
            PairPresenter(route.pointAId.orEmpty(), route.pointBId.orEmpty(), services, scope.readout, tab.undo, scope.readout::showMessage)
        }
        val state by presenter.uiState.collectAsState()
        LaunchedEffect(state.done) { if (state.done) scope.navigator.pop(route) }
        val events = remember(presenter, scope) {
            object : PairEvents {
                override fun onMove() = presenter.onMove()
                override fun onMoveConfirm() = presenter.onMoveConfirm()
                override fun onDeleteB(delete: Boolean) = presenter.onDeleteB(delete)
                override fun onSpindle() = presenter.onSpindle()
                override fun onRotateConfirm() = presenter.onRotateConfirm()
                override fun onGuideAnswer(policy: GuidePolicy?) = presenter.onGuideAnswer(policy)
                override fun onQuestionCancel() = presenter.onQuestionCancel()
                override fun onDismiss() = scope.navigator.pop(route)
            }
        }
        PairSheet(state, events)
        return
    }
    val presenter = rememberPresenter {
        TransformPresenter(route.kind, services, scope.readout.frame, scope.readout::positionUnavailable, tab.undo, scope.readout::latestFrame)
    }
    val state by presenter.uiState.collectAsState()
    LaunchedEffect(state.done) { if (state.done) scope.navigator.pop(route) }
    val events = remember(presenter, scope) {
        object : TransformEvents {
            override fun onFlipAxis(axis: FlipAxis) = presenter.onFlipAxis(axis)
            override fun onAngle(text: String) = presenter.onAngle(text)
            override fun onPercent(text: String) = presenter.onPercent(text)
            override fun onScaleZ(on: Boolean) = presenter.onScaleZ(on)
            override fun onSource(source: PivotSource) = presenter.onSource(source)
            override fun onSubDatum(id: String) = presenter.onSubDatum(id)
            override fun onText(axis: AxisId, text: String) = presenter.onText(axis, text)
            override fun onUnit(axis: AxisId, metric: Boolean) = presenter.onUnit(axis, metric)
            override fun onApply() = presenter.onApply()
            override fun onGuideAnswer(policy: GuidePolicy?) = presenter.onGuideAnswer(policy)
            override fun onDismiss() = scope.navigator.pop(route)
        }
    }
    TransformSheet(state, events)
}

@Composable
private fun WorkspaceCommandsEntry(scope: TabEntryScope) {
    val services = rememberFunctionServices()
    val presenter = rememberPresenter { WorkspaceCommandsPresenter(services, scope.readout, scope.readout::showMessage) }
    val state by presenter.uiState.collectAsState()
    LaunchedEffect(state.done) { if (state.done) scope.navigator.pop(WorkspaceCommandsRoute) }
    val events = remember(presenter, scope) {
        object : WorkspaceCommandsEvents {
            override fun onAlignOrigin(subDatumId: String) =
                scope.navigator.replaceTop(SubDatumCommandRoute(subDatumId, SubDatumCommand.AlignOriginToSubDatum))
            override fun onFlip() = scope.navigator.replaceTop(WorkspaceTransformRoute(WorkspaceTransformKind.Flip))
            override fun onRotate() = scope.navigator.replaceTop(WorkspaceTransformRoute(WorkspaceTransformKind.Rotate))
            override fun onScale() = scope.navigator.replaceTop(WorkspaceTransformRoute(WorkspaceTransformKind.Scale))
            override fun onUndo() = presenter.onUndo()
            override fun onDismiss() = scope.navigator.pop(WorkspaceCommandsRoute)
        }
    }
    WorkspaceCommandsDialog(state, events)
}

@Composable
private fun SubDatumCommandEntry(scope: TabEntryScope, route: SubDatumCommandRoute) {
    val services = rememberFunctionServices()
    val tab = scope.functionTab(services)
    AttachUndoHost(tab.undo)
    val presenter = rememberPresenter {
        SubDatumCommandPresenter(route.subDatumId, route.command, services, scope.readout, tab.undo, scope.readout::showMessage)
    }
    val state by presenter.uiState.collectAsState()
    LaunchedEffect(state.done) { if (state.done) scope.navigator.pop(route) }
    SubDatumCommandDialog(state, onConfirm = presenter::onConfirm, onDismiss = { scope.navigator.pop(route) })
}
