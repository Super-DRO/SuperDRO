package org.superdro.core.ui.graphics.measure

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.superdro.core.ui.graphics.GraphicsDependencies
import org.superdro.core.ui.navigation.AlignAxesRoute
import org.superdro.core.ui.navigation.EntryKind
import org.superdro.core.ui.navigation.EntryRegistry
import org.superdro.core.ui.navigation.InstrumentRoute
import org.superdro.core.ui.navigation.MeasurementLogRoute
import org.superdro.core.ui.navigation.ProbeProfileRoute
import org.superdro.core.ui.navigation.RouteArea
import org.superdro.core.ui.navigation.TabEntryScope
import org.superdro.core.ui.presenter.rememberPresenter
import org.superdro.core.ui.rememberInstance

/**
 * The entries of the measurement mode: the reference instrument sheet,
 * the align axes sheet, and the inspection log screen. The graphics area
 * registers them after the functions area, so they replace the placeholders of that area.
 */
fun EntryRegistry.measureEntries() {
    tab<InstrumentRoute>(EntryKind.Sheet, RouteArea.Functions) { _ -> InstrumentEntry(this) }
    tab<AlignAxesRoute>(EntryKind.Sheet, RouteArea.Functions) { _ -> AlignAxesEntry(this) }
    tab<MeasurementLogRoute>(EntryKind.Screen, RouteArea.Graphics) { _ -> MeasurementLogEntry(this) }
}

/** The reference instrument sheet of the tab of [scope]. */
@Composable
private fun InstrumentEntry(scope: TabEntryScope) {
    val deps = rememberInstance<GraphicsDependencies>()
    val presenter = rememberPresenter { InstrumentPresenter(scope.deviceId, scope.readout, deps) }
    val state by presenter.uiState.collectAsState()
    InstrumentSheet(
        state = state,
        onSelect = presenter::onSelect,
        onNew = {
            presenter.onNew()
            scope.global.push(ProbeProfileRoute(null))
        },
        onClear = presenter::onClear,
        onDismiss = { scope.navigator.pop(InstrumentRoute) },
        onEdit = { id -> scope.global.push(ProbeProfileRoute(id)) },
    )
}

/** The align axes sheet of the tab of [scope]. */
@Composable
private fun AlignAxesEntry(scope: TabEntryScope) {
    val deps = rememberInstance<GraphicsDependencies>()
    val presenter = rememberPresenter { AlignAxesPresenter(scope.readout, deps.context) }
    val state by presenter.uiState.collectAsState()
    AlignAxesSheet(state = state, presenter = presenter, onDismiss = { scope.navigator.pop(AlignAxesRoute) })
}

/** The inspection log screen of the tab of [scope]. */
@Composable
private fun MeasurementLogEntry(scope: TabEntryScope) {
    val deps = rememberInstance<GraphicsDependencies>()
    val presenter = rememberPresenter { MeasurementLogPresenter(scope.readout, deps) }
    val state by presenter.uiState.collectAsState()
    LaunchedEffect(presenter) { presenter.notices.collect { text -> scope.showMessage(text) } }
    MeasurementLogScreen(
        state = state,
        onBack = { scope.navigator.pop(MeasurementLogRoute) },
        onExportCsv = presenter::onExportCsv,
        onExportSheet = presenter::onExportSheet,
        onClearAsk = presenter::onClearAsk,
        onClearConfirm = presenter::onClearConfirm,
    )
}
