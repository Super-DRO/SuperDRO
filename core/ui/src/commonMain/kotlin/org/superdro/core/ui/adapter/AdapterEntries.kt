package org.superdro.core.ui.adapter

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import org.superdro.core.model.DeclaredInput
import org.superdro.core.protocol.InputChannel
import org.superdro.core.protocol.InputMode
import org.superdro.core.ui.adapter.calibration.calibrationEntries
import org.superdro.core.ui.adapter.details.AdapterDetailsEvents
import org.superdro.core.ui.adapter.details.AdapterDetailsPresenter
import org.superdro.core.ui.adapter.details.AdapterDetailsScreen
import org.superdro.core.ui.adapter.details.GearTarget
import org.superdro.core.ui.adapter.encoder.encoderEntries
import org.superdro.core.ui.adapter.identity.AdapterIdentityEffect
import org.superdro.core.ui.adapter.identity.NewAdapterEntry
import org.superdro.core.ui.adapter.identity.identityPresenter
import org.superdro.core.ui.adapter.inputs.AdapterSource
import org.superdro.core.ui.adapter.inputs.ProbeInputPresenter
import org.superdro.core.ui.adapter.inputs.ProbeInputSheet
import org.superdro.core.ui.adapter.inputs.TachPulsesPresenter
import org.superdro.core.ui.adapter.inputs.TachPulsesSheet
import org.superdro.core.ui.adapter.list.AdapterRowTarget
import org.superdro.core.ui.adapter.list.AdapterRowUi
import org.superdro.core.ui.adapter.list.AdaptersEvents
import org.superdro.core.ui.adapter.list.AdaptersPresenter
import org.superdro.core.ui.adapter.list.AdaptersScreen
import org.superdro.core.ui.adapter.list.OfflineAdapterEvents
import org.superdro.core.ui.adapter.list.OfflineAdapterPresenter
import org.superdro.core.ui.adapter.list.OfflineAdapterScreen
import org.superdro.core.ui.adapter.list.OfflineRowTarget
import org.superdro.core.ui.adapter.probe.probeEntries
import org.superdro.core.ui.navigation.AdapterDetailsRoute
import org.superdro.core.ui.navigation.AdaptersRoute
import org.superdro.core.ui.navigation.EncoderSettingsRoute
import org.superdro.core.ui.navigation.EntryKind
import org.superdro.core.ui.navigation.EntryRegistry
import org.superdro.core.ui.navigation.GlobalEntryScope
import org.superdro.core.ui.navigation.NewAdapterPromptRoute
import org.superdro.core.ui.navigation.OfflineAdapterDetailsRoute
import org.superdro.core.ui.navigation.OfflineEncoderSettingsRoute
import org.superdro.core.ui.navigation.OfflineProbeInputRoute
import org.superdro.core.ui.navigation.OfflineTachPulsesRoute
import org.superdro.core.ui.navigation.ProbeInputRoute
import org.superdro.core.ui.navigation.RouteArea
import org.superdro.core.ui.navigation.TabEntryScope
import org.superdro.core.ui.navigation.TachPulsesRoute
import org.superdro.core.ui.presenter.rememberPresenter
import org.superdro.core.ui.rememberInstance

/**
 * The entries of the adapter area. The central provider
 * ([org.superdro.core.ui.navigation.appEntryRegistry]) calls this function. Each part of the area
 * registers its own entries:
 *
 * - This file: the adapter details, the adapters, the offline adapter details, the "new adapter"
 *   prompt, the tach pulses, and the probe input. The tab effect of the
 *   prompt is in [adapterTabEffects].
 * - [encoderEntries]: the encoder settings, the map editor, and the error map wizard.
 * - [calibrationEntries]: the calibration wizard of an adapter input.
 * - [probeEntries]: the probe library, the probe profile, and the tip calibration.
 */
fun EntryRegistry.adapterEntries() {
    tab<AdapterDetailsRoute>(EntryKind.Screen, RouteArea.Adapter) { route -> AdapterDetailsEntry(this, route) }
    tab<TachPulsesRoute>(EntryKind.Sheet, RouteArea.Adapter) { route ->
        TachPulsesEntry(AdapterSource.Tab(deviceId), this) { navigator.pop(route) }
    }
    tab<ProbeInputRoute>(EntryKind.Sheet, RouteArea.Adapter) { route -> ProbeInputEntry(AdapterSource.Tab(deviceId)) { navigator.pop(route) } }
    tab<NewAdapterPromptRoute>(EntryKind.Dialog, RouteArea.Adapter) { route -> NewAdapterEntry(this, route) }
    global<AdaptersRoute>(EntryKind.Screen, RouteArea.Adapter) { route -> AdaptersEntry(this, route) }
    global<OfflineAdapterDetailsRoute>(EntryKind.Screen, RouteArea.Adapter) { route -> OfflineAdapterEntry(this, route) }
    global<OfflineTachPulsesRoute>(EntryKind.Sheet, RouteArea.Adapter) { route ->
        TachPulsesEntry(AdapterSource.Stored(route.adapterProfileId), null) { navigator.pop(route) }
    }
    global<OfflineProbeInputRoute>(EntryKind.Sheet, RouteArea.Adapter) { route ->
        ProbeInputEntry(AdapterSource.Stored(route.adapterProfileId)) { navigator.pop(route) }
    }
    encoderEntries()
    calibrationEntries()
    probeEntries()
}

/**
 * Registers the tab effects of the adapter area: the identity rules and the "new adapter" prompt
 * of each tab. The effects need the DI graph, so `core:di` calls this function on the one
 * registry of the app. A registry with no DI graph (a test of the frame) has no effect.
 */
fun EntryRegistry.adapterTabEffects() {
    tabEffect { AdapterIdentityEffect(this) }
}

/** The Adapter Details screen of a tab. */
@Composable
private fun AdapterDetailsEntry(scope: TabEntryScope, route: AdapterDetailsRoute) {
    val deps = rememberInstance<AdapterUiDeps>()
    val presenter = rememberPresenter { AdapterDetailsPresenter(scope.deviceId, deps) }
    val state by presenter.uiState.collectAsState()
    val actionsState by presenter.actions.state.collectAsState()
    LaunchedEffect(presenter) { presenter.messages.collect { text -> scope.showMessage(text) } }
    val events = remember(presenter, scope) {
        object : AdapterDetailsEvents {
            override fun onSelectMode(channel: InputChannel, mode: InputMode) = presenter.onSelectMode(channel, mode)
            override fun onCancel(channel: InputChannel) = presenter.onCancel(channel)
            override fun onSave(channel: InputChannel) = presenter.onSave(channel)
            override fun onReference(channel: InputChannel) = presenter.onReference(channel)
            override fun onClear(channel: InputChannel) = presenter.onClear(channel)
            override fun onGear(target: GearTarget) = when (target) {
                is GearTarget.Encoder -> scope.navigator.push(EncoderSettingsRoute(target.input))
                GearTarget.Tach -> scope.navigator.push(TachPulsesRoute)
                GearTarget.Probe -> scope.navigator.push(ProbeInputRoute)
            }
            override fun onRestart() = presenter.onRestart()
            override fun onRestartDialogDismissed() = presenter.onRestartDialogDismissed()
            override fun onCreateProfile() = identityPresenter(scope, deps).openManual()
        }
    }
    AdapterDetailsScreen(state, actionsState, presenter.actions, events, onBack = { scope.navigator.pop(route) })
}

/** The tach pulses sheet of the tab or of a stored adapter profile. */
@Composable
private fun TachPulsesEntry(source: AdapterSource, scope: TabEntryScope?, close: () -> Unit) {
    val deps = rememberInstance<AdapterUiDeps>()
    val presenter = rememberPresenter { TachPulsesPresenter(source, deps, scope?.readout) }
    val state by presenter.uiState.collectAsState()
    LaunchedEffect(state.done) { if (state.done) close() }
    TachPulsesSheet(state, onText = presenter::onText, onSet = presenter::onSet, onDismiss = close)
}

/** The probe input sheet of the tab or of a stored adapter profile. */
@Composable
private fun ProbeInputEntry(source: AdapterSource, close: () -> Unit) {
    val deps = rememberInstance<AdapterUiDeps>()
    val presenter = rememberPresenter { ProbeInputPresenter(source, deps) }
    val state by presenter.uiState.collectAsState()
    LaunchedEffect(state.done) { if (state.done) close() }
    ProbeInputSheet(state, presenter::onContactType, presenter::onDebounce, presenter::onSave, onDismiss = close)
}

/** The adapters screen of the overflow menu. */
@Composable
private fun AdaptersEntry(scope: GlobalEntryScope, route: AdaptersRoute) {
    val deps = rememberInstance<AdapterUiDeps>()
    val presenter = rememberPresenter { AdaptersPresenter(deps, scope.selectedDevice) }
    val state by presenter.uiState.collectAsState()
    val actionsState by presenter.actions.state.collectAsState()
    LaunchedEffect(presenter) { presenter.messages.collect { text -> scope.showMessage(text) } }
    val events = remember(presenter, scope) {
        object : AdaptersEvents {
            override fun onRow(row: AdapterRowUi) {
                when (val target = presenter.targetOf(row)) {
                    is AdapterRowTarget.Online -> {
                        val tab = scope.tabNavigator(target.deviceId)
                        if (tab == null) {
                            scope.navigator.push(OfflineAdapterDetailsRoute(row.id))
                        } else {
                            scope.navigator.clear()
                            tab.push(AdapterDetailsRoute)
                        }
                    }
                    is AdapterRowTarget.Offline -> scope.navigator.push(OfflineAdapterDetailsRoute(target.adapterProfileId))
                }
            }
            override fun onCreate() = presenter.onCreate()
            override fun onCreateName(text: String) = presenter.onCreateName(text)
            override fun onCreateToggle(input: DeclaredInput) = presenter.onCreateToggle(input)
            override fun onCreateConfirm() = presenter.onCreateConfirm()
            override fun onCreateDismiss() = presenter.onCreateDismiss()
        }
    }
    AdaptersScreen(state, actionsState, presenter.actions, events, onBack = { scope.navigator.pop(route) })
}

/** The offline adapter details of a stored adapter profile. */
@Composable
private fun OfflineAdapterEntry(scope: GlobalEntryScope, route: OfflineAdapterDetailsRoute) {
    val deps = rememberInstance<AdapterUiDeps>()
    val presenter = rememberPresenter { OfflineAdapterPresenter(route.adapterProfileId, deps) }
    val state by presenter.uiState.collectAsState()
    val actionsState by presenter.actions.state.collectAsState()
    LaunchedEffect(presenter) { presenter.messages.collect { text -> scope.showMessage(text) } }
    LaunchedEffect(state.gone) { if (state.gone) scope.navigator.pop(route) }
    val events = remember(presenter, scope) {
        object : OfflineAdapterEvents {
            override fun onEditAlias() = presenter.onEditAlias()
            override fun onAliasText(text: String) = presenter.onAliasText(text)
            override fun onSaveAlias() = presenter.onSaveAlias()
            override fun onCancelAlias() = presenter.onCancelAlias()
            override fun onToggleDeclared(input: DeclaredInput) = presenter.onToggleDeclared(input)
            override fun onRow(target: OfflineRowTarget) = when (target) {
                is OfflineRowTarget.Encoder -> scope.navigator.push(OfflineEncoderSettingsRoute(route.adapterProfileId, target.input))
                OfflineRowTarget.Tach -> scope.navigator.push(OfflineTachPulsesRoute(route.adapterProfileId))
                OfflineRowTarget.Probe -> scope.navigator.push(OfflineProbeInputRoute(route.adapterProfileId))
            }
        }
    }
    OfflineAdapterScreen(state, actionsState, presenter.actions, events, onBack = { scope.navigator.pop(route) })
}
