package org.superdro.core.ui.functions.sheets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import org.superdro.core.functions.compensation.ToolPosition
import org.superdro.core.ui.functions.common.rememberFunctionServices
import org.superdro.core.ui.navigation.AngleMeasureRoute
import org.superdro.core.ui.navigation.CompoundRoute
import org.superdro.core.ui.navigation.DimensionMeasureRoute
import org.superdro.core.ui.navigation.EntryKind
import org.superdro.core.ui.navigation.EntryRegistry
import org.superdro.core.ui.navigation.HeadTiltRoute
import org.superdro.core.ui.navigation.HeightCompensationRoute
import org.superdro.core.ui.navigation.LatheToolOffsetRoute
import org.superdro.core.ui.navigation.RadiusCompensationRoute
import org.superdro.core.ui.navigation.RouteArea
import org.superdro.core.ui.navigation.TabEntryScope
import org.superdro.core.ui.navigation.TabRoute
import org.superdro.core.ui.navigation.ToolLibraryRoute
import org.superdro.core.ui.navigation.ZReferenceRoute
import org.superdro.core.ui.presenter.rememberPresenter
import androidx.compose.runtime.LaunchedEffect
import org.superdro.core.ui.functions.common.LengthFieldState
import org.superdro.core.ui.graphics.GraphicsDependencies
import org.superdro.core.ui.graphics.probe.ZReferenceAutoPresenter
import org.superdro.core.ui.graphics.probe.ZReferenceAutoSection
import org.superdro.core.ui.navigation.ProbeProfileRoute
import org.superdro.core.ui.rememberInstance

/**
 * The entries of the measure sheets, the compensation sheets, the Z reference, and the projection
 * sheets.
 * [org.superdro.core.ui.functions.functionEntries] calls this function.
 */
internal fun EntryRegistry.sheetEntries() {
    tab<AngleMeasureRoute>(EntryKind.Sheet, RouteArea.Functions) { route -> AngleMeasureEntry(this, route) }
    tab<DimensionMeasureRoute>(EntryKind.Sheet, RouteArea.Functions) { route -> DimensionMeasureEntry(this, route) }
    tab<RadiusCompensationRoute>(EntryKind.Sheet, RouteArea.Functions) { route -> RadiusCompensationEntry(this, route) }
    tab<HeightCompensationRoute>(EntryKind.Sheet, RouteArea.Functions) { route -> HeightCompensationEntry(this, route) }
    tab<LatheToolOffsetRoute>(EntryKind.Sheet, RouteArea.Functions) { route -> LatheToolOffsetEntry(this, route) }
    tab<ZReferenceRoute>(EntryKind.Sheet, RouteArea.Functions) { route -> ZReferenceEntry(this, route) }
    tab<CompoundRoute>(EntryKind.Sheet, RouteArea.Functions) { route -> CompoundEntry(this, route) }
    tab<HeadTiltRoute>(EntryKind.Sheet, RouteArea.Functions) { route -> HeadTiltEntry(this, route) }
}

/** Closes the sheet of [route] when [done] is true. */
private fun TabEntryScope.closeIf(route: TabRoute, done: Boolean) {
    if (done) navigator.pop(route)
}

/** Opens the tool library above the sheet of [route]: the sheet closes first. */
private fun TabEntryScope.openToolLibrary(route: TabRoute) {
    navigator.pop(route)
    navigator.push(ToolLibraryRoute)
}

@Composable
private fun AngleMeasureEntry(scope: TabEntryScope, route: AngleMeasureRoute) {
    val services = rememberFunctionServices()
    val presenter = rememberPresenter { AngleMeasurePresenter(scope.readout, services) }
    val state by presenter.uiState.collectAsState()
    AngleMeasureSheet(state, onUnit = presenter::onUnit, onDismiss = { scope.navigator.pop(route) })
}

@Composable
private fun DimensionMeasureEntry(scope: TabEntryScope, route: DimensionMeasureRoute) {
    val services = rememberFunctionServices()
    val presenter = rememberPresenter { DimensionMeasurePresenter(scope.readout, services) }
    val state by presenter.uiState.collectAsState()
    val events = remember(presenter, scope) {
        object : DimensionMeasureEvents {
            override fun onZero() {
                presenter.onZero()?.let(scope.readout::showMessage)
            }
            override fun onZeroLongPress() {
                presenter.onZeroLongPress()?.let(scope.readout::showMessage)
            }
            override fun onToggleMode() = presenter.onToggleMode()
            override fun onToggleUnits() = presenter.onToggleUnits()
            override fun onCopyToX() {
                presenter.onCopyToX()?.let(scope.readout::showMessage)
            }
            override fun onDismiss() = scope.navigator.pop(route)
        }
    }
    DimensionMeasureSheet(state, events)
}

@Composable
private fun RadiusCompensationEntry(scope: TabEntryScope, route: RadiusCompensationRoute) {
    val services = rememberFunctionServices()
    val presenter = rememberPresenter { RadiusCompensationPresenter(scope.readout, services) }
    val state by presenter.uiState.collectAsState()
    val events = remember(presenter, scope) {
        object : RadiusCompensationEvents {
            override fun onTool(id: String?) = presenter.onTool(id)
            override fun onDiameter(text: String) = presenter.onDiameter(text)
            override fun onDiameterUnit(metric: Boolean) = presenter.onDiameterUnit(metric)
            override fun onPosition(position: ToolPosition) = presenter.onPosition(position)
            override fun onApply() = scope.closeIf(route, presenter.onApply())
            override fun onClear() = scope.closeIf(route, presenter.onClear())
            override fun onToolLibrary() = scope.openToolLibrary(route)
            override fun onDismiss() = scope.navigator.pop(route)
        }
    }
    RadiusCompensationSheet(state, events)
}

@Composable
private fun HeightCompensationEntry(scope: TabEntryScope, route: HeightCompensationRoute) {
    val services = rememberFunctionServices()
    val presenter = rememberPresenter { HeightCompensationPresenter(scope.readout, services) }
    val state by presenter.uiState.collectAsState()
    val events = remember(presenter, scope) {
        object : HeightCompensationEvents {
            override fun onTool(id: String?) = presenter.onTool(id)
            override fun onZOffset(text: String) = presenter.onZOffset(text)
            override fun onZOffsetUnit(metric: Boolean) = presenter.onZOffsetUnit(metric)
            override fun onApply() = scope.closeIf(route, presenter.onApply())
            override fun onClear() = scope.closeIf(route, presenter.onClear())
            override fun onToolLibrary() = scope.openToolLibrary(route)
            override fun onDismiss() = scope.navigator.pop(route)
        }
    }
    HeightCompensationSheet(state, events)
}

@Composable
private fun LatheToolOffsetEntry(scope: TabEntryScope, route: LatheToolOffsetRoute) {
    val services = rememberFunctionServices()
    val presenter = rememberPresenter { LatheToolOffsetPresenter(scope.readout, services) }
    val state by presenter.uiState.collectAsState()
    val events = remember(presenter, scope) {
        object : LatheToolOffsetEvents {
            override fun onTool(id: String?) = presenter.onTool(id)
            override fun onXOffset(text: String) = presenter.onXOffset(text)
            override fun onZOffset(text: String) = presenter.onZOffset(text)
            override fun onXUnit(metric: Boolean) = presenter.onXUnit(metric)
            override fun onZUnit(metric: Boolean) = presenter.onZUnit(metric)
            override fun onApply() = scope.closeIf(route, presenter.onApply())
            override fun onSelectTool() = scope.closeIf(route, presenter.onSelectTool())
            override fun onClear() = scope.closeIf(route, presenter.onClear())
            override fun onToolLibrary() = scope.openToolLibrary(route)
            override fun onDismiss() = scope.navigator.pop(route)
        }
    }
    LatheToolOffsetSheet(state, events)
}

@Composable
private fun ZReferenceEntry(scope: TabEntryScope, route: ZReferenceRoute) {
    val services = rememberFunctionServices()
    val presenter = rememberPresenter { ZReferencePresenter(scope.readout, services) }
    val state by presenter.uiState.collectAsState()
    val coroutines = rememberCoroutineScope()
    val events = remember(presenter, scope) {
        object : ZReferenceEvents {
            override fun onSetterHeight(text: String) = presenter.onSetterHeight(text)
            override fun onSetterHeightUnit(metric: Boolean) = presenter.onSetterHeightUnit(metric)
            override fun onDesiredZ(text: String) = presenter.onDesiredZ(text)
            override fun onDesiredZUnit(metric: Boolean) = presenter.onDesiredZUnit(metric)
            override fun onApply() {
                coroutines.launch { scope.closeIf(route, presenter.onApply()) }
            }
            override fun onDismiss() = scope.navigator.pop(route)
        }
    }
    // The automatic mode (the "Auto" button) is a part of the graphics area. It goes into
    // the `automatic` slot of ZReferenceSheet. It gets the typed values A and B of the manual
    // fields, and a successful automatic set closes the sheet.
    val graphics = rememberInstance<GraphicsDependencies>()
    val auto = rememberPresenter(Z_AUTO_PRESENTER) { ZReferenceAutoPresenter(scope.deviceId, scope.readout, graphics) }
    val autoState by auto.uiState.collectAsState()
    LaunchedEffect(auto, state.setterHeight, state.desiredZ) {
        auto.setManualValues(state.setterHeight.lengthOrNull(), state.desiredZ.lengthOrNull(), state.setterHeight.metric)
    }
    LaunchedEffect(auto, autoState.closeRequests) {
        if (autoState.closeRequests > 0) scope.navigator.pop(route)
    }
    ZReferenceSheet(state, events) {
        ZReferenceAutoSection(
            state = autoState,
            onToggle = auto::toggle,
            onSelectProbe = auto::onSelectProbe,
            onNewProbe = {
                auto.onNewProbe()
                scope.global.push(ProbeProfileRoute(null))
            },
            onEditProbe = { id -> scope.global.push(ProbeProfileRoute(id)) },
        )
    }
}

/** The name of the presenter of the automatic Z reference in the entry of the Z reference sheet. */
private const val Z_AUTO_PRESENTER: String = "z-reference-auto"

/** The length of a field in µm, or null for an empty field or a text that the field refuses. */
private fun LengthFieldState.lengthOrNull(): Double? = valueUm?.takeIf { value -> !value.isNaN() }

@Composable
private fun CompoundEntry(scope: TabEntryScope, route: CompoundRoute) {
    val services = rememberFunctionServices()
    val presenter = rememberPresenter { CompoundPresenter(scope.readout, services) }
    val state by presenter.uiState.collectAsState()
    val events = remember(presenter, scope) {
        object : CompoundEvents {
            override fun onAngle(text: String) = presenter.onAngle(text)
            override fun onApplyX(on: Boolean) = presenter.onApplyX(on)
            override fun onApplyZ(on: Boolean) = presenter.onApplyZ(on)
            override fun onApply() = scope.closeIf(route, presenter.onApply())
            override fun onClear() = scope.closeIf(route, presenter.onClear())
            override fun onDismiss() = scope.navigator.pop(route)
        }
    }
    CompoundSheet(state, events)
}

@Composable
private fun HeadTiltEntry(scope: TabEntryScope, route: HeadTiltRoute) {
    val services = rememberFunctionServices()
    val presenter = rememberPresenter { HeadTiltPresenter(scope.readout, services) }
    val state by presenter.uiState.collectAsState()
    val events = remember(presenter, scope) {
        object : HeadTiltEvents {
            override fun onTilt(text: String) = presenter.onTilt(text)
            override fun onNod(text: String) = presenter.onNod(text)
            override fun onApplyX(on: Boolean) = presenter.onApplyX(on)
            override fun onApplyY(on: Boolean) = presenter.onApplyY(on)
            override fun onApply() = scope.closeIf(route, presenter.onApply())
            override fun onClear() = scope.closeIf(route, presenter.onClear())
            override fun onDismiss() = scope.navigator.pop(route)
        }
    }
    HeadTiltSheet(state, events)
}
