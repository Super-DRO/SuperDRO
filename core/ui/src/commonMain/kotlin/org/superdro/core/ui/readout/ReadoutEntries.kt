package org.superdro.core.ui.readout

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.keepScreenOn
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.model.AxisButtonMode
import org.superdro.core.model.AxisId
import org.superdro.core.model.SidePanelMode
import org.superdro.core.resources.Res
import org.superdro.core.resources.frame_function_under_construction
import org.superdro.core.resources.readout_undo
import org.superdro.core.resources.route_graphical_view
import org.superdro.core.resources.route_machine_datums
import org.superdro.core.resources.route_sub_datums
import org.superdro.core.resources.route_tools
import org.superdro.core.ui.LocalAppSnackbarHostState
import org.superdro.core.ui.navigation.AxisDetailsRoute
import org.superdro.core.ui.navigation.CalibrationWizardRoute
import org.superdro.core.ui.navigation.DivisionsRoute
import org.superdro.core.ui.navigation.EntryKind
import org.superdro.core.ui.navigation.EntryRegistry
import org.superdro.core.ui.navigation.KeypadRoute
import org.superdro.core.ui.navigation.MachineSettingsRoute
import org.superdro.core.ui.navigation.ReadoutRoute
import org.superdro.core.ui.navigation.RouteArea
import org.superdro.core.ui.navigation.ShapeMenuRoute
import org.superdro.core.ui.navigation.SidePanelPlaceholder
import org.superdro.core.ui.navigation.SubDatumEditRoute
import org.superdro.core.ui.navigation.TabEntryScope
import org.superdro.core.ui.navigation.TabNavigator
import org.superdro.core.ui.navigation.TabRoute
import org.superdro.core.ui.navigation.TachometerPresetRoute
import org.superdro.core.ui.presenter.collectLiveState
import org.superdro.core.ui.presenter.rememberPresenter
import org.superdro.core.ui.readout.details.AxisDetailsPresenter
import org.superdro.core.ui.readout.details.AxisDetailsSheet
import org.superdro.core.ui.readout.details.DivisionsPresenter
import org.superdro.core.ui.readout.details.DivisionsSheet
import org.superdro.core.ui.readout.details.PresetSheet
import org.superdro.core.ui.readout.keypad.KeypadPresenter
import org.superdro.core.ui.readout.keypad.KeypadSheet
import org.superdro.core.ui.settings.keys.readoutHardwareKeys
import org.superdro.core.ui.sound.rememberKeyFeedback
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.resolve

/**
 * The entries of the readout screen and its sheets: the root of
 * each tab and the keypad, the axis details, the divisions keypad, and the preset sheet. Each
 * entry uses the readout presenter of its tab ([TabEntryScope.readout]).
 */
fun EntryRegistry.readoutEntries() {
    val registry = this
    tab<ReadoutRoute>(EntryKind.Screen, RouteArea.Frame) { _ -> ReadoutRoot(this, registry) }
    tab<KeypadRoute>(EntryKind.Sheet, RouteArea.Frame) { route -> KeypadEntry(this, route) }
    tab<AxisDetailsRoute>(EntryKind.Sheet, RouteArea.Frame) { route -> AxisDetailsEntry(this, route) }
    tab<DivisionsRoute>(EntryKind.Sheet, RouteArea.Frame) { _ -> DivisionsEntry(this) }
    tab<TachometerPresetRoute>(EntryKind.Sheet, RouteArea.Frame) { _ -> PresetEntry(this) }
}

/** Gives the readout presenter of the tab of [scope]. */
private val TabEntryScope.readoutPresenter: ReadoutPresenter get() = readout as ReadoutPresenter

/**
 * The root entry of a tab: the readout screen with the snackbar of the undo, the action of
 * the "+" button, the key feedback, and "keep the screen on" while the session is
 * `Connected`.
 */
@Composable
private fun ReadoutRoot(scope: TabEntryScope, registry: EntryRegistry) {
    val presenter = scope.readoutPresenter
    val state by presenter.uiState.collectAsState()
    val events = remember(presenter, scope) { PresenterReadoutEvents(presenter, scope.navigator) }
    val feedback = rememberKeyFeedback(scope.deviceId)
    UndoSnackbar(state.undo, LocalAppSnackbarHostState.current, presenter)
    AddPointEffect(state.addPointRequest, presenter, scope, registry)
    val graphicalPane = registry.graphicalViewPaneContent
    val sidePanelContent = remember<@Composable (SidePanelMode) -> Unit>(scope, registry) {
        { mode ->
            val content = registry.sidePanelContent(mode)
            if (content != null) content(scope) else SidePanelPlaceholder(sidePanelTitle(mode))
        }
    }
    val graphicalViewPane = remember<@Composable () -> Unit>(scope, graphicalPane) {
        {
            if (graphicalPane != null) graphicalPane(scope) else SidePanelPlaceholder(Res.string.route_graphical_view)
        }
    }
    val statusItems = remember<@Composable () -> Unit>(scope, registry) {
        { registry.statusStripItems.forEach { item -> item(scope) } }
    }
    CompositionLocalProvider(LocalReadoutFeedback provides feedback) {
        ReadoutScreen(
            state = state,
            axisValue = presenter::axisValue,
            events = events,
            // The hardware keys of the selected tab.
            modifier = readoutHardwareKeys(presenter, scope.navigator).then(if (state.keepScreenOn) Modifier.keepScreenOn() else Modifier),
            sidePanelContent = sidePanelContent,
            graphicalViewPane = graphicalViewPane,
            statusItems = statusItems,
            rpmText = presenter.rpmText,
        )
    }
}

/**
 * The snackbar of the undo. The presenter clears [undo] after 10 s: the effect then
 * stops, and the snackbar goes away.
 */
@Composable
internal fun UndoSnackbar(undo: UndoUiState?, host: SnackbarHostState, presenter: ReadoutPresenter) {
    val label = undo?.label?.resolve()
    val action = stringResource(Res.string.readout_undo)
    LaunchedEffect(undo?.id, label) {
        if (undo == null || label == null) return@LaunchedEffect
        val result = host.showSnackbar(message = label, actionLabel = action, withDismissAction = false, duration = SnackbarDuration.Indefinite)
        if (result == SnackbarResult.ActionPerformed) presenter.onUndo(undo.id) else presenter.onUndoDismissed(undo.id)
    }
}

/** Does the action of the "+" button. */
@Composable
private fun AddPointEffect(request: AddPointRequest?, presenter: ReadoutPresenter, scope: TabEntryScope, registry: EntryRegistry) {
    LaunchedEffect(request?.id) {
        if (request == null) return@LaunchedEffect
        when (request.kind) {
            AddPointKind.Single -> {
                val action = registry.addSubDatumTapAction
                if (action != null) action(scope) else presenter.showMessage(UiText.of(Res.string.frame_function_under_construction))
            }
            AddPointKind.Double -> scope.navigator.push(SubDatumEditRoute(null))
            AddPointKind.Long -> scope.navigator.push(ShapeMenuRoute)
        }
        presenter.onAddRequestHandled(request.id)
    }
}

private fun sidePanelTitle(mode: SidePanelMode): StringResource = when (mode) {
    SidePanelMode.Tools -> Res.string.route_tools
    SidePanelMode.MachineDatums -> Res.string.route_machine_datums
    else -> Res.string.route_sub_datums
}

/** The keypad sheet of one axis. */
@Composable
private fun KeypadEntry(scope: TabEntryScope, route: KeypadRoute) {
    val readout = scope.readoutPresenter
    val keypad = rememberPresenter { KeypadPresenter(route.axis, readout) }
    val state by keypad.uiState.collectAsState()
    val value by readout.axisValue(route.axis).collectLiveState()
    val readoutState by readout.uiState.collectAsState()
    val label = readoutState.rows.firstOrNull { row -> row.axis == route.axis }?.label ?: route.axis.name
    val close = { scope.navigator.pop(route) }
    CompositionLocalProvider(LocalReadoutFeedback provides rememberKeyFeedback(scope.deviceId)) {
        KeypadSheet(
            state = state,
            label = label,
            value = value,
            onTextChange = keypad::onTextChange,
            onKey = keypad::onKey,
            onSet = { if (keypad.onSet()) close() },
            onDismiss = close,
        )
    }
}

/** The axis details sheet. */
@Composable
private fun AxisDetailsEntry(scope: TabEntryScope, route: AxisDetailsRoute) {
    val readout = scope.readoutPresenter
    val details = rememberPresenter { AxisDetailsPresenter(route.axis, readout) }
    val feed by details.feed.collectAsState()
    val value by readout.axisValue(route.axis).collectLiveState()
    val readoutState by readout.uiState.collectAsState()
    val row = readoutState.rows.firstOrNull { item -> item.axis == route.axis }
        ?: ReadoutRowUiState(route.axis, route.axis.name, if (route.axis == AxisId.Angular) RowKind.Angular else RowKind.Linear)
    val events = remember(readout, scope) { PresenterReadoutEvents(readout, scope.navigator) }
    val proximityAxes by readout.proximityAxes.collectAsState()
    CompositionLocalProvider(LocalReadoutFeedback provides rememberKeyFeedback(scope.deviceId)) {
        AxisDetailsSheet(
            row = row,
            value = value,
            feed = if (row.kind == RowKind.Linear) feed else null,
            events = events,
            onCenterAbsolute = { readout.onCenterAbsolute(route.axis) },
            onDismiss = { scope.navigator.pop(route) },
            proximityOn = if (row.kind == RowKind.Linear) route.axis in proximityAxes else null,
            onProximity = details::onProximityToggle,
        )
    }
}

/** The divisions keypad. */
@Composable
private fun DivisionsEntry(scope: TabEntryScope) {
    val readout = scope.readoutPresenter
    val divisions = rememberPresenter { DivisionsPresenter(readout) }
    val state by divisions.uiState.collectAsState()
    val close = { scope.navigator.pop(DivisionsRoute) }
    CompositionLocalProvider(LocalReadoutFeedback provides rememberKeyFeedback(scope.deviceId)) {
        DivisionsSheet(
            state = state,
            onTextChange = divisions::onTextChange,
            onKey = divisions::onKey,
            onSet = { if (divisions.onSet()) close() },
            onDismiss = close,
        )
    }
}

/** The preset sheet of the tachometer. */
@Composable
private fun PresetEntry(scope: TabEntryScope) {
    val readout = scope.readoutPresenter
    val close = { scope.navigator.pop(TachometerPresetRoute) }
    CompositionLocalProvider(LocalReadoutFeedback provides rememberKeyFeedback(scope.deviceId)) {
        PresetSheet(
            presets = readout.presets(),
            onSelect = { rpm ->
                readout.onPresetSelected(rpm)
                close()
            },
            onDismiss = close,
        )
    }
}

/**
 * The [ReadoutEvents] of a readout presenter: each event calls the presenter, and a route that the
 * presenter gives goes on the back stack of the tab.
 */
class PresenterReadoutEvents(private val presenter: ReadoutPresenter, private val navigator: TabNavigator) : ReadoutEvents {
    override fun onValueTap(axis: AxisId) = open(presenter.onValueTap(axis))
    override fun onLabelTap(axis: AxisId) = open(presenter.onLabelTap(axis))
    override fun onActionTap(axis: AxisId, mode: AxisButtonMode) = open(presenter.onActionTap(axis, mode))
    override fun onActionLongPress(axis: AxisId, mode: AxisButtonMode) = presenter.onActionLongPress(axis, mode)
    override fun onTachometerToggle() = presenter.onTachometerToggle()
    override fun onZeroSetTap() = presenter.onZeroSetTap()
    override fun onZeroSetLongPress() = presenter.onZeroSetLongPress()
    override fun onModeTap() = presenter.onModeTap()
    override fun onModeLongPress() = presenter.onModeLongPress()
    override fun onUnitsTap() = presenter.onUnitsTap()
    override fun onFunctionTap(button: FunctionButton) {
        presenter.onFunctionSheet(false)
        open(presenter.onFunctionTap(button))
    }
    override fun onFunctionLongPress(button: FunctionButton) = open(presenter.onFunctionLongPress(button))
    override fun onSidePanelTab(mode: SidePanelMode) = presenter.onSidePanelTab(mode)
    override fun onSidePanelClose() = presenter.onSidePanelClose()
    override fun onAddTap() = presenter.onAddTap()
    override fun onAddLongPress() = presenter.onAddLongPress()
    override fun onFunctionSheet(open: Boolean) = presenter.onFunctionSheet(open)
    override fun onSetupSettings() = navigator.push(MachineSettingsRoute)
    override fun onSetupCalibrate(axis: AxisId) = navigator.push(CalibrationWizardRoute(axis))
    override fun onReadoutPaneFractionChange(fraction: Float) = presenter.onReadoutPaneFractionChange(fraction)
    override fun onGraphicalPaneWeightChange(weight: Float) = presenter.onGraphicalPaneWeightChange(weight)
    override fun onDismissMessage() = presenter.onDismissMessage()

    private fun open(route: TabRoute?) {
        if (route != null) navigator.push(route)
    }
}
