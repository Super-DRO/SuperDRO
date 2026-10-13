package org.superdro.core.ui.graphics.drawing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import org.superdro.core.ui.graphics.GraphicsDependencies
import org.superdro.core.ui.navigation.DrawingProblemsRoute
import org.superdro.core.ui.navigation.DrawingSetupRoute
import org.superdro.core.ui.navigation.EntryKind
import org.superdro.core.ui.navigation.EntryRegistry
import org.superdro.core.ui.navigation.RouteArea
import org.superdro.core.ui.navigation.TabEntryScope
import org.superdro.core.ui.navigation.TabNavigator
import org.superdro.core.ui.navigation.ToolGeometryRoute
import org.superdro.core.ui.presenter.rememberPresenter
import org.superdro.core.ui.rememberInstance
import org.superdro.core.ui.text.UiText

/** The entries of the drawings. */
fun EntryRegistry.drawingEntries() {
    tab<DrawingSetupRoute>(EntryKind.Screen, RouteArea.Graphics) { route -> DrawingSetupEntry(this, route) }
    tab<DrawingProblemsRoute>(EntryKind.Screen, RouteArea.Graphics) { route -> DrawingProblemsEntry(this, route) }
    tab<ToolGeometryRoute>(EntryKind.Screen, RouteArea.Graphics) { route -> ToolGeometryEntry(this, route) }
}

/**
 * Gives the drawing import of the tab of this scope (a presenter of the tab with the name
 * [DrawingImportController.TAB_PRESENTER]). The graphical view and the drawing screens share it.
 */
fun TabEntryScope.drawingController(deps: GraphicsDependencies): DrawingImportController =
    tabPresenter(DrawingImportController.TAB_PRESENTER) { DrawingImportController(deviceId, readout, deps) }

/**
 * Does the events of the drawing import of [controller]: it opens the route of an event on
 * [navigator], and it shows the text of a message with [showMessage]. The graphical view and the
 * drawing screens put this effect in their composition.
 */
@Composable
fun DrawingEventEffect(controller: DrawingImportController, navigator: TabNavigator, showMessage: (UiText) -> Unit) {
    val state by controller.uiState.collectAsState()
    val event = state.event
    LaunchedEffect(event?.id) {
        when (event) {
            null -> Unit
            is DrawingEvent.Navigate -> navigator.push(event.route)
            is DrawingEvent.Message -> showMessage(event.text)
        }
        if (event != null) controller.onEventHandled(event.id)
    }
}

@Composable
private fun DrawingSetupEntry(scope: TabEntryScope, route: DrawingSetupRoute) {
    val deps = rememberInstance<GraphicsDependencies>()
    val controller = remember(scope) { scope.drawingController(deps) }
    val importState by controller.uiState.collectAsState()
    val pending = importState.pending
    if (pending == null) {
        LaunchedEffect(Unit) { scope.navigator.pop(route) }
        return
    }
    val presenter = rememberPresenter { DrawingSetupPresenter(pending, scope.readout, deps, controller) }
    val state by presenter.uiState.collectAsState()
    LaunchedEffect(state.done) { if (state.done) scope.navigator.pop(route) }
    DrawingEventEffect(controller, scope.navigator, scope::showMessage)
    DrawingSetupScreen(state, remember(presenter) { presenter.events() })
}

@Composable
private fun DrawingProblemsEntry(scope: TabEntryScope, route: DrawingProblemsRoute) {
    val deps = rememberInstance<GraphicsDependencies>()
    val controller = remember(scope) { scope.drawingController(deps) }
    val state by controller.uiState.collectAsState()
    DrawingProblemsScreen(state.problems) {
        controller.clearProblems()
        scope.navigator.pop(route)
    }
}

@Composable
private fun ToolGeometryEntry(scope: TabEntryScope, route: ToolGeometryRoute) {
    val deps = rememberInstance<GraphicsDependencies>()
    val presenter = rememberPresenter { ToolGeometryPresenter(route.toolId, scope.readout, deps) }
    val state by presenter.uiState.collectAsState()
    LaunchedEffect(state.done) { if (state.done) scope.navigator.pop(route) }
    ToolGeometryScreen(state, remember(presenter) { presenter.events() })
}
