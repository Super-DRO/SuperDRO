package org.superdro.core.ui.adapter.probe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import org.superdro.core.model.AxisId
import org.superdro.core.model.ProbeType
import org.superdro.core.ui.adapter.AdapterUiDeps
import org.superdro.core.ui.navigation.EntryKind
import org.superdro.core.ui.navigation.EntryRegistry
import org.superdro.core.ui.navigation.GlobalEntryScope
import org.superdro.core.ui.navigation.ProbeLibraryRoute
import org.superdro.core.ui.navigation.ProbeProfileRoute
import org.superdro.core.ui.navigation.RouteArea
import org.superdro.core.ui.navigation.TipCalibrationRoute
import org.superdro.core.ui.presenter.rememberPresenter
import org.superdro.core.ui.rememberInstance

/**
 * The entries of the probe library, the probe profile, and the tip calibration.
 * [org.superdro.core.ui.adapter.adapterEntries] calls it. Each entry is a screen of the global
 * back stack. The machine profile is the profile of the selected tab.
 */
internal fun EntryRegistry.probeEntries() {
    global<ProbeLibraryRoute>(EntryKind.Screen, RouteArea.Adapter) { route -> ProbeLibraryEntry(this, route) }
    global<ProbeProfileRoute>(EntryKind.Screen, RouteArea.Adapter) { route -> ProbeProfileEntry(this, route) }
    global<TipCalibrationRoute>(EntryKind.Screen, RouteArea.Adapter) { route -> TipCalibrationEntry(this, route) }
}

@Composable
private fun ProbeLibraryEntry(scope: GlobalEntryScope, route: ProbeLibraryRoute) {
    val deps = rememberInstance<AdapterUiDeps>()
    val presenter = rememberPresenter { ProbeLibraryPresenter(deps, scope.selectedDevice) }
    val state by presenter.uiState.collectAsState()
    val selectors = presenter.selectors.map { selector -> selector.state.collectAsState().value }
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        scope.showMessage(message)
        presenter.messageShown()
    }
    val events = remember(presenter, scope) {
        object : ProbeLibraryEvents {
            override fun onBack() = scope.navigator.pop(route)
            override fun onOpen(row: ProbeLibraryRow) {
                if (row.standardTip) presenter.toggleStandardTipNotice(row.id) else scope.navigator.push(ProbeProfileRoute(row.id))
            }
            override fun onNew() = scope.navigator.push(ProbeProfileRoute(null))
            override fun onAskDelete(id: String) = presenter.askDelete(id)
            override fun onCancelDelete() = presenter.cancelDelete()
            override fun onConfirmDelete() = presenter.confirmDelete()
            override fun onDismissNotice() = presenter.dismissStandardTipNotice()
            override fun onExport(form: ProbeExportForm) = presenter.export(form)
            override fun onImport() = presenter.startImport()
            override fun onSelectOperation(index: Int) = presenter.selectOperation(index)
            override fun onConfirmImport() = presenter.confirmImport()
            override fun onCancelImport() = presenter.cancelImport()
            override fun onDismissReport() = presenter.dismissReport()
            override fun onSelect(function: ProbeFunction, id: String?) {
                presenter.selectors.firstOrNull { selector -> selector.function == function }?.select(id)
            }
            override fun onNewForFunction(function: ProbeFunction) {
                presenter.selectors.firstOrNull { selector -> selector.function == function }?.requestNewProfile()
                scope.navigator.push(ProbeProfileRoute(null))
            }
            override fun onEditProfile(id: String) = scope.navigator.push(ProbeProfileRoute(id))
        }
    }
    ProbeLibraryScreen(state, selectors, events)
}

@Composable
private fun ProbeProfileEntry(scope: GlobalEntryScope, route: ProbeProfileRoute) {
    val deps = rememberInstance<AdapterUiDeps>()
    val presenter = rememberPresenter { ProbeProfilePresenter(deps, route.probeProfileId, scope.selectedDevice) }
    val state by presenter.uiState.collectAsState()
    LaunchedEffect(state.done) { if (state.done) scope.navigator.pop(route) }
    LaunchedEffect(state.openCalibration) {
        val id = state.openCalibration ?: return@LaunchedEffect
        presenter.calibrationOpened()
        scope.navigator.push(TipCalibrationRoute(id))
    }
    val events = remember(presenter, scope) {
        object : ProbeProfileEvents {
            override fun onBack() = scope.navigator.pop(route)
            override fun onType(type: ProbeType) = presenter.setType(type)
            override fun onName(text: String) = presenter.setName(text)
            override fun onMetric(metric: Boolean) = presenter.setMetric(metric)
            override fun onPort(port: Char?) = presenter.setPort(port)
            override fun onDiameter(text: String) = presenter.setDiameter(text)
            override fun onSampleCount(text: String) = presenter.setSampleCount(text)
            override fun onMaxSpread(text: String) = presenter.setMaxSpread(text)
            override fun onXOffset(text: String) = presenter.setXOffset(text)
            override fun onYOffset(text: String) = presenter.setYOffset(text)
            override fun onZOffset(text: String) = presenter.setZOffset(text)
            override fun onSync(axis: AxisId) = presenter.sync(axis)
            override fun onSave(autoName: String) = presenter.save(autoName)
            override fun onCalibrate(autoName: String) = presenter.calibrate(autoName)
            override fun onShowRecord() = presenter.showRecord()
            override fun onDismissRecord() = presenter.dismissRecord()
            override fun onDismissRefusal() = presenter.dismissRefusal()
        }
    }
    ProbeProfileScreen(state, events)
}

@Composable
private fun TipCalibrationEntry(scope: GlobalEntryScope, route: TipCalibrationRoute) {
    val deps = rememberInstance<AdapterUiDeps>()
    val presenter = rememberPresenter { TipCalibrationPresenter(deps, route.probeProfileId, scope.selectedDevice) }
    val state by presenter.uiState.collectAsState()
    LaunchedEffect(state.done) { if (state.done) scope.navigator.pop(route) }
    val events = remember(presenter, scope) {
        object : TipCalibrationEvents {
            override fun onBack() = presenter.cancel()
            override fun onGauge(text: String) = presenter.setGauge(text)
            override fun onGaugeMetric(metric: Boolean) = presenter.setGaugeMetric(metric)
            override fun onContinueGauge() = presenter.continueGauge()
            override fun onContinueTouches() = presenter.continueTouches()
            override fun onContinueRecenter() = presenter.continueRecenter()
            override fun onRetry() = presenter.retry()
            override fun onCancel() = presenter.cancel()
            override fun onApply() = presenter.apply()
            override fun onDiscard() = presenter.discard()
            override fun onResume() = presenter.resume()
            override fun onStartAgain() = presenter.startAgain()
        }
    }
    TipCalibrationScreen(state, events)
}
