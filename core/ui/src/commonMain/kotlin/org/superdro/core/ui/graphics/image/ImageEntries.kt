package org.superdro.core.ui.graphics.image

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.superdro.core.ui.graphics.GraphicsDependencies
import org.superdro.core.ui.navigation.EntryKind
import org.superdro.core.ui.navigation.EntryRegistry
import org.superdro.core.ui.navigation.ReferenceImageSetupRoute
import org.superdro.core.ui.navigation.RouteArea
import org.superdro.core.ui.navigation.TabEntryScope
import org.superdro.core.ui.presenter.rememberPresenter
import org.superdro.core.ui.rememberInstance

/** The entries of the reference image. */
fun EntryRegistry.imageEntries() {
    tab<ReferenceImageSetupRoute>(EntryKind.Screen, RouteArea.Graphics) { route -> ReferenceImageSetupEntry(this, route) }
}

/**
 * The setup screen of a new reference image. The picture comes from the controller of the tab
 * ([ReferenceImageController.takePendingImage]). After a successful "Apply", the screen closes.
 */
@Composable
private fun ReferenceImageSetupEntry(scope: TabEntryScope, route: ReferenceImageSetupRoute) {
    val deps = rememberInstance<GraphicsDependencies>()
    val controller = scope.referenceImageController(deps)
    val presenter = rememberPresenter {
        ReferenceImageSetupPresenter(scope.deviceId, scope.readout, deps, controller.takePendingImage())
    }
    val state by presenter.uiState.collectAsState()
    val bitmap by presenter.bitmap.collectAsState()
    val close = { scope.navigator.pop(route) }
    LaunchedEffect(state.closed) { if (state.closed) close() }
    ReferenceImageSetupScreen(state = state, bitmap = bitmap, events = presenter, onCancel = close)
}
