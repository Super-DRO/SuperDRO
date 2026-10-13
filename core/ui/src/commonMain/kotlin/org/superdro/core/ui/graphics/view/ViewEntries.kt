package org.superdro.core.ui.graphics.view

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.functions.compensation.ToolPosition
import org.superdro.core.functions.graphics.GuideOrientation
import org.superdro.core.functions.graphics.ScreenMetrics
import org.superdro.core.functions.graphics.Vec2
import org.superdro.core.functions.graphics.ZoomPreset
import org.superdro.core.model.GraphicalDisplay
import org.superdro.core.model.Plane
import org.superdro.core.resources.Res
import org.superdro.core.resources.frame_function_under_construction
import org.superdro.core.resources.readout_undo
import org.superdro.core.resources.route_graphical_view
import org.superdro.core.ui.LocalAppSnackbarHostState
import org.superdro.core.ui.graphics.GraphicsDependencies
import org.superdro.core.ui.graphics.thread.CreateThreadQuestion
import org.superdro.core.ui.navigation.AddGuideRoute
import org.superdro.core.ui.navigation.CameraSetupRoute
import org.superdro.core.ui.navigation.EdgeFinderRoute
import org.superdro.core.ui.navigation.EntryKind
import org.superdro.core.ui.navigation.EntryRegistry
import org.superdro.core.ui.navigation.GraphicalViewRoute
import org.superdro.core.ui.navigation.RotatePartRoute
import org.superdro.core.ui.navigation.RouteArea
import org.superdro.core.ui.navigation.ScreenScaffold
import org.superdro.core.ui.navigation.SubDatumEditRoute
import org.superdro.core.ui.navigation.TabEntryScope
import org.superdro.core.ui.navigation.TabRoute
import org.superdro.core.ui.navigation.ThreadSetupRoute
import org.superdro.core.ui.presenter.rememberPresenter
import org.superdro.core.ui.rememberInstance
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.resolve
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * The entries of the graphical view:
 * the pane of the readout screen (below or above the readout in portrait, the supporting pane in
 * landscape), the full-screen entry, the "Add guide" dialog, and the rotate part sheet.
 */
fun EntryRegistry.viewEntries(parts: GraphicalPartsProvider = { _, _ -> GraphicalViewParts() }) {
    tab<GraphicalViewRoute>(EntryKind.Screen, RouteArea.Graphics) { _ -> GraphicalViewScreenEntry(this, parts) }
    tab<AddGuideRoute>(EntryKind.Dialog, RouteArea.Graphics) { _ -> AddGuideEntry(this) }
    tab<RotatePartRoute>(EntryKind.Sheet, RouteArea.Functions) { _ -> RotatePartEntry(this) }
    graphicalViewPane {
        val deps = rememberInstance<GraphicsDependencies>()
        GraphicalViewHost(this, fullScreen = false, parts = parts(this, deps))
    }
}

/** Gives the [GraphicalViewParts] of a tab. The graphics area gives the parts of its other packages. */
typealias GraphicalPartsProvider = @Composable (TabEntryScope, GraphicsDependencies) -> GraphicalViewParts

/** Gives the graphics presenter of the tab of [this] scope. The tab owns it. */
fun TabEntryScope.graphicalTab(deps: GraphicsDependencies): GraphicalTabPresenter =
    tabPresenter(GraphicalTabPresenter.NAME) { GraphicalTabPresenter(deviceId, readout, deps) }

/**
 * The extra parts of the graphical view that the other parts of the graphics area give: the
 * measurement pane, the reference image, the drawing actions, and the dialogs.
 */
class GraphicalViewParts(
    val pointTapInterceptor: (String) -> Boolean = { false },
    val extraTopStrip: @Composable () -> Unit = {},
    val leftPanel: (@Composable () -> Unit)? = null,
    val referenceImage: ReferenceLayer? = null,
    val measurementMarks: List<MeasurementMark> = emptyList(),
    val displayEvents: (DisplayPaneEvents) -> DisplayPaneEvents = { base -> base },
    val dialogs: @Composable () -> Unit = {},
    val theme: @Composable (@Composable () -> Unit) -> Unit = { content -> content() },
    val deleteShapeDialog: (@Composable (Int, org.superdro.core.model.ShapeType, () -> Unit, () -> Unit) -> Unit)? = null,
    val layerList: (@Composable (List<org.superdro.core.model.DrawingLayer>) -> Unit)? = null,
    val edgeFinderHidden: Boolean = false,
    val backdrop: (@Composable () -> Unit)? = null,
    val camera: CameraSceneOverlay = CameraSceneOverlay(),
)

/** The full-screen entry of the graphical view. */
@Composable
private fun GraphicalViewScreenEntry(scope: TabEntryScope, provider: GraphicalPartsProvider) {
    val deps = rememberInstance<GraphicsDependencies>()
    val parts = provider(scope, deps)
    parts.theme {
        ScreenScaffold(title = stringResource(Res.string.route_graphical_view), onBack = { scope.navigator.pop(GraphicalViewRoute) }) {
            GraphicalViewHost(scope, fullScreen = true, parts = parts, themed = true)
        }
    }
}

/**
 * The graphical view of a tab with its presenters, its events, the undo snackbar, and its
 * dialogs. [fullScreen] is false for the pane of the readout screen: the pane has the button
 * "Full screen".
 */
@Composable
fun GraphicalViewHost(
    scope: TabEntryScope,
    fullScreen: Boolean,
    modifier: Modifier = Modifier,
    parts: GraphicalViewParts = GraphicalViewParts(),
    themed: Boolean = false,
) {
    if (!themed) {
        parts.theme { GraphicalViewHost(scope, fullScreen, modifier, parts, themed = true) }
        return
    }
    val deps = rememberInstance<GraphicsDependencies>()
    val tab = scope.graphicalTab(deps)
    val presenter = rememberPresenter(if (fullScreen) "graphical-view-screen" else "graphical-view-pane") {
        GraphicalViewPresenter(tab, parts.pointTapInterceptor)
    }
    val metrics = rememberScreenMetrics()
    val view by presenter.uiState.collectAsState()
    val controls by tab.controls.collectAsState()
    val paneOpen by presenter.displayPaneOpen.collectAsState()
    val threadPanel by tab.threadCut.panel.collectAsState()
    val createQuestion by tab.threadCut.createQuestion.collectAsState()
    val events = remember(presenter, scope, metrics) { HostEvents(presenter, scope, metrics) }
    val displayEvents = remember(presenter, scope, parts) { parts.displayEvents(HostDisplayEvents(presenter.tab, scope)) }
    LaunchedEffect(presenter, parts.camera) { presenter.setCamera(parts.camera) }
    val themeCursor = MaterialTheme.colorScheme.secondary
    GraphicsUndoSnackbar(tab)
    val shownControls = if (parts.edgeFinderHidden) controls.copy(edgeFinderVisible = false) else controls
    GraphicalView(
        view = view,
        controls = shownControls,
        metrics = metrics,
        events = events,
        modifier = modifier,
        showFullScreen = !fullScreen,
        displayPaneOpen = paneOpen,
        threadPanel = threadPanel,
        referenceImage = parts.referenceImage,
        measurementMarks = parts.measurementMarks,
        extraTopStrip = parts.extraTopStrip,
        leftPanel = parts.leftPanel,
        displayPane = {
            val layerList = parts.layerList
            if (layerList != null) {
                DisplayPane(
                    display = controls.display,
                    hasDrawing = controls.hasDrawing,
                    layers = controls.drawingLayers,
                    referenceAlpha = controls.referenceAlpha,
                    events = displayEvents,
                    layerList = layerList,
                )
            } else {
                DisplayPane(
                    display = controls.display,
                    hasDrawing = controls.hasDrawing,
                    layers = controls.drawingLayers,
                    referenceAlpha = controls.referenceAlpha,
                    events = displayEvents,
                )
            }
        },
        deleteShapeDialog = parts.deleteShapeDialog ?: { _, _, onConfirm, onDismiss -> DefaultDeleteShapeDialog(onConfirm, onDismiss) },
        backdrop = parts.backdrop,
        transparentBackground = parts.camera.transparentBackground,
        cursorColor = if (parts.camera.active) Color(parts.camera.cursorArgb(themeCursor.toArgb().toLong() and 0xFFFFFFFFL)) else null,
    )
    parts.dialogs()
    if (createQuestion) {
        CreateThreadQuestion(
            onCreate = {
                tab.threadCut.onCreateQuestionClosed()
                scope.navigator.push(ThreadSetupRoute)
            },
            onDismiss = tab.threadCut::onCreateQuestionClosed,
        )
    }
}

/** The undo snackbar of a delete of the graphical view. */
@Composable
private fun GraphicsUndoSnackbar(tab: GraphicalTabPresenter) {
    val undo by tab.undo.collectAsState()
    val host = LocalAppSnackbarHostState.current
    val label = undo?.label?.resolve()
    val action = stringResource(Res.string.readout_undo)
    LaunchedEffect(undo?.id, label) {
        val current = undo ?: return@LaunchedEffect
        if (label == null) return@LaunchedEffect
        val result = host.showSnackbar(message = label, actionLabel = action, withDismissAction = false, duration = SnackbarDuration.Indefinite)
        if (result == SnackbarResult.ActionPerformed) tab.onUndo(current.id) else tab.onUndoDismissed(current.id)
    }
}

/** The events of the view: each event calls the presenters, and a route goes on the back stack of the tab. */
private class HostEvents(
    private val presenter: GraphicalViewPresenter,
    private val scope: TabEntryScope,
    private val metrics: ScreenMetrics,
) : GraphicalViewEvents {
    private val tab: GraphicalTabPresenter get() = presenter.tab

    private fun open(route: TabRoute?) {
        if (route != null) scope.navigator.push(route)
    }

    override fun onSize(widthPx: Double, heightPx: Double) = presenter.onSize(widthPx, heightPx, metrics)
    override fun onTap(position: Vec2) = open(presenter.onTap(position))
    override fun onDoubleTap() = presenter.onDoubleTap()
    override fun onLongPress(position: Vec2) = open(presenter.onLongPress(position))
    override fun onDrag(dxPx: Double, dyPx: Double) = presenter.onDrag(dxPx, dyPx)
    override fun onPinch(factor: Double, focus: Vec2) = presenter.onPinch(factor, focus)
    override fun onSwitch(switch: GraphicalSwitch, on: Boolean) = tab.setSwitch(switch, on)
    override fun onPlane(plane: Plane) = tab.setPlane(plane)
    override fun onDisplayPaneToggle() = presenter.onDisplayPaneToggle()
    override fun onRecenter() = presenter.onRecenter()
    override fun onZoomPreset(preset: ZoomPreset) = presenter.onZoomPreset(preset)
    override fun onFullScreen() = scope.navigator.push(GraphicalViewRoute)
    override fun onRotatePart() = scope.navigator.push(RotatePartRoute)
    override fun onEdgeFinder() = scope.navigator.push(EdgeFinderRoute)
    override fun onThreadArm() = tab.threadCut.onArmButton()
    override fun onDirection(position: ToolPosition) = presenter.onDirection(position)
    override fun onOverlayDismiss() = presenter.onOverlayClosed()

    override fun onEditSubDatum(id: String) {
        presenter.onOverlayClosed()
        scope.navigator.push(SubDatumEditRoute(id))
    }

    override fun onDeleteSubDatum(id: String) {
        presenter.onOverlayClosed()
        tab.deleteSubDatum(id)
    }

    override fun onSetOrigin(id: String) {
        presenter.onOverlayClosed()
        tab.setOriginAt(id)
    }

    override fun onAddGuideAtPoint(id: String, orientation: GuideOrientation) {
        presenter.onOverlayClosed()
        tab.addGuideAtPoint(id, orientation)
    }

    override fun onDeleteShapeConfirmed(index: Int) = presenter.onDeleteShapeConfirmed(index) { shape -> tab.deleteShape(shape) }
    override fun onThreadSelected(id: String) = tab.threadCut.arm(id)
    override fun onThreadClose() = tab.threadCut.disarm()
}

/**
 * The display pane events of the view. The drawing and the reference image parts of the area
 * replace the actions of their rows ([GraphicalViewParts.displayEvents]).
 */
private class HostDisplayEvents(private val tab: GraphicalTabPresenter, private val scope: TabEntryScope) : DisplayPaneEvents {
    private fun notReady() = scope.showMessage(UiText.of(Res.string.frame_function_under_construction))

    override fun onDisplay(change: (GraphicalDisplay) -> GraphicalDisplay) = tab.updateDisplay(change)
    override fun onClearGuides() = tab.deleteAllGuides()
    override fun onImportDrawing() = notReady()
    override fun onExportDrawing() = notReady()
    override fun onClearDrawing() = notReady()
    override fun onLayerHidden(index: Int, hidden: Boolean) = tab.setLayerHidden(index, hidden)
    override fun onCameraSetup() = scope.navigator.push(CameraSetupRoute)
    override fun onReferenceAlpha(alpha: Int) = tab.setReferenceAlpha(alpha)
    override fun onClearReference() = notReady()
    override fun onTakePhoto() = notReady()
    override fun onSelectImage() = notReady()
}

/** The "Add guide" dialog entry. */
@Composable
private fun AddGuideEntry(scope: TabEntryScope) {
    val deps = rememberInstance<GraphicsDependencies>()
    val tab = scope.graphicalTab(deps)
    val presenter = rememberPresenter { AddGuidePresenter(tab) }
    val state by presenter.uiState.collectAsState()
    val close = { scope.navigator.pop(AddGuideRoute) }
    AddGuideDialog(
        state = state,
        onText = presenter::onText,
        onOrientation = presenter::onOrientation,
        onSync = presenter::onSync,
        onAdd = { if (presenter.onAdd()) close() },
        onCancel = {
            presenter.onCancel()
            close()
        },
    )
}

/** The rotate part sheet entry. */
@Composable
private fun RotatePartEntry(scope: TabEntryScope) {
    val presenter = rememberPresenter { RotatePartPresenter(scope.readout) }
    val state by presenter.uiState.collectAsState()
    RotatePartSheet(state = state, presenter = presenter, onClose = { scope.navigator.pop(RotatePartRoute) })
}
