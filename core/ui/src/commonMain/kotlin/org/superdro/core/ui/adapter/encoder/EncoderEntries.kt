package org.superdro.core.ui.adapter.encoder

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalClipboardManager
import org.superdro.core.functions.errormap.MapKind
import org.superdro.core.ui.adapter.AdapterUiDeps
import org.superdro.core.ui.adapter.encoder.map.MapEditorPresenter
import org.superdro.core.ui.adapter.encoder.map.MapEditorScreen
import org.superdro.core.ui.adapter.encoder.map.PresenterMapEditorEvents
import org.superdro.core.ui.adapter.encoder.wizard.DIAGNOSTICS_COPIED
import org.superdro.core.ui.adapter.encoder.wizard.ErrorMapDiagnosticsPresenter
import org.superdro.core.ui.adapter.encoder.wizard.ErrorMapDiagnosticsScreen
import org.superdro.core.ui.adapter.encoder.wizard.ErrorMapReportPresenter
import org.superdro.core.ui.adapter.encoder.wizard.ErrorMapReportScreen
import org.superdro.core.ui.adapter.encoder.wizard.ErrorMapWizardPresenter
import org.superdro.core.ui.adapter.encoder.wizard.ErrorMapWizardScreen
import org.superdro.core.ui.adapter.encoder.wizard.PresenterWizardEvents
import org.superdro.core.ui.navigation.EncoderSettingsRoute
import org.superdro.core.ui.navigation.EntryKind
import org.superdro.core.ui.navigation.EntryRegistry
import org.superdro.core.ui.navigation.ErrorMapDiagnosticsRoute
import org.superdro.core.ui.navigation.ErrorMapReportRoute
import org.superdro.core.ui.navigation.ErrorMapWizardRoute
import org.superdro.core.ui.navigation.InputCalibrationRoute
import org.superdro.core.ui.navigation.MapEditorRoute
import org.superdro.core.ui.navigation.OfflineEncoderSettingsRoute
import org.superdro.core.ui.navigation.OfflineMapEditorRoute
import org.superdro.core.ui.navigation.RouteArea
import org.superdro.core.ui.presenter.rememberPresenter
import org.superdro.core.ui.rememberInstance
import org.superdro.core.ui.text.UiText

/**
 * The entries of the encoder settings, the map editor, and the error map wizard.
 * [org.superdro.core.ui.adapter.adapterEntries] calls it.
 *
 * - Online (a tab): the encoder settings, the map editors, the wizard, the report, and the
 *   diagnostics. "Calibrate" opens [InputCalibrationRoute]. The route of an editor gives its map
 *   kind.
 * - Offline (global): the encoder settings and the map editors of a stored adapter profile.
 */
internal fun EntryRegistry.encoderEntries() {
    tab<EncoderSettingsRoute>(EntryKind.Screen, RouteArea.Adapter) { route ->
        EncoderSettingsEntry(
            source = EncoderSource.Online(deviceId),
            input = route.input,
            showMessage = ::showMessage,
            openCalibration = { navigator.push(InputCalibrationRoute(route.input)) },
            openErrorMap = { navigator.push(MapEditorRoute(route.input, MapKind.Error)) },
            openBacklashMap = { navigator.push(MapEditorRoute(route.input, MapKind.Backlash)) },
            onBack = { navigator.pop(route) },
        )
    }
    global<OfflineEncoderSettingsRoute>(EntryKind.Screen, RouteArea.Adapter) { route ->
        EncoderSettingsEntry(
            source = EncoderSource.Offline(route.adapterProfileId),
            input = route.input,
            showMessage = ::showMessage,
            openCalibration = {},
            openErrorMap = { navigator.push(OfflineMapEditorRoute(route.adapterProfileId, route.input, MapKind.Error)) },
            openBacklashMap = { navigator.push(OfflineMapEditorRoute(route.adapterProfileId, route.input, MapKind.Backlash)) },
            onBack = { navigator.pop(route) },
        )
    }
    tab<MapEditorRoute>(EntryKind.Screen, RouteArea.Adapter) { route ->
        MapEditorEntry(EncoderSource.Online(deviceId), route.input, route.kind, ::showMessage, { navigator.push(ErrorMapWizardRoute(route.input)) }) {
            navigator.pop(route)
        }
    }
    global<OfflineMapEditorRoute>(EntryKind.Screen, RouteArea.Adapter) { route ->
        MapEditorEntry(EncoderSource.Offline(route.adapterProfileId), route.input, route.kind, ::showMessage, {}) { navigator.pop(route) }
    }
    tab<ErrorMapWizardRoute>(EntryKind.Screen, RouteArea.Adapter) { route ->
        val deps = rememberInstance<AdapterUiDeps>()
        val presenter = rememberPresenter { ErrorMapWizardPresenter(deviceId, route.input, deps) }
        val state by presenter.uiState.collectAsState()
        LaunchedEffect(state.openReport) {
            if (state.openReport) {
                presenter.onReportOpened()
                navigator.push(ErrorMapReportRoute(route.input))
            }
        }
        LaunchedEffect(state.exited) { if (state.exited) navigator.pop(route) }
        val events = remember(presenter) { PresenterWizardEvents(presenter) }
        ErrorMapWizardScreen(state, events, onBack = { navigator.pop(route) })
    }
    tab<ErrorMapReportRoute>(EntryKind.Screen, RouteArea.Adapter) { route ->
        val deps = rememberInstance<AdapterUiDeps>()
        val presenter = rememberPresenter { ErrorMapReportPresenter(deviceId, route.input, deps) }
        val state by presenter.uiState.collectAsState()
        LaunchedEffect(state.message) {
            state.message?.let { text ->
                showMessage(text)
                presenter.onMessageShown()
            }
        }
        LaunchedEffect(state.saved) {
            if (state.saved) {
                navigator.pop(route)
                navigator.pop(ErrorMapWizardRoute(route.input))
            }
        }
        ErrorMapReportScreen(
            state = state,
            onSave = presenter::onSave,
            onDiagnostics = { navigator.push(ErrorMapDiagnosticsRoute(route.input)) },
            onBack = { navigator.pop(route) },
        )
    }
    tab<ErrorMapDiagnosticsRoute>(EntryKind.Screen, RouteArea.Adapter) { route ->
        val deps = rememberInstance<AdapterUiDeps>()
        val presenter = rememberPresenter { ErrorMapDiagnosticsPresenter(deviceId, route.input, deps) }
        val state by presenter.uiState.collectAsState()
        ErrorMapDiagnosticsScreen(state, onCopied = { showMessage(UiText.of(DIAGNOSTICS_COPIED)) }, onBack = { navigator.pop(route) })
    }
}

@Composable
private fun EncoderSettingsEntry(
    source: EncoderSource,
    input: Char,
    showMessage: (UiText) -> Unit,
    openCalibration: () -> Unit,
    openErrorMap: () -> Unit,
    openBacklashMap: () -> Unit,
    onBack: () -> Unit,
) {
    val deps = rememberInstance<AdapterUiDeps>()
    val presenter = rememberPresenter { EncoderSettingsPresenter(source, input, deps) }
    val state by presenter.uiState.collectAsState()
    LaunchedEffect(state.message) {
        state.message?.let { text ->
            showMessage(text)
            presenter.onMessageShown()
        }
    }
    val events = remember(presenter) { PresenterEncoderEvents(presenter, openCalibration, openErrorMap, openBacklashMap) }
    EncoderSettingsScreen(state, events, onBack)
}

@Composable
private fun MapEditorEntry(
    source: EncoderSource,
    input: Char,
    kind: MapKind,
    showMessage: (UiText) -> Unit,
    openWizard: () -> Unit,
    onBack: () -> Unit,
) {
    val deps = rememberInstance<AdapterUiDeps>()
    val presenter = rememberPresenter { MapEditorPresenter(source, input, kind, deps) }
    val state by presenter.uiState.collectAsState()
    // The common ClipEntry of Compose has no text accessor, so the editor uses the text API of
    // ClipboardManager. It reads only plain text.
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(state.message) {
        state.message?.let { text ->
            showMessage(text)
            presenter.onMessageShown()
        }
    }
    val events = remember(presenter, clipboard) { PresenterMapEditorEvents(presenter, { clipboard.getText()?.text }, openWizard) }
    MapEditorScreen(state, events, onBack)
}
