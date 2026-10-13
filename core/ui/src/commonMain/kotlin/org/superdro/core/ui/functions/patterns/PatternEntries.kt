package org.superdro.core.ui.functions.patterns

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import org.superdro.core.resources.Res
import org.superdro.core.resources.fnp_created
import org.superdro.core.ui.functions.common.rememberFunctionServices
import org.superdro.core.ui.navigation.ArbitraryHoleCircleRoute
import org.superdro.core.ui.navigation.ArcCutRoute
import org.superdro.core.ui.navigation.EntryKind
import org.superdro.core.ui.navigation.EntryRegistry
import org.superdro.core.ui.navigation.EquidistantArrayRoute
import org.superdro.core.ui.navigation.HoleCircleRoute
import org.superdro.core.ui.navigation.HoleGridRoute
import org.superdro.core.ui.navigation.RouteArea
import org.superdro.core.ui.navigation.StepAndRepeatRoute
import org.superdro.core.ui.navigation.TabEntryScope
import org.superdro.core.ui.navigation.TabRoute
import org.superdro.core.ui.presenter.rememberPresenter
import org.superdro.core.ui.text.UiText

/**
 * The entries of the hole patterns and the arc cut.
 * [org.superdro.core.ui.functions.functionEntries] calls this function. Each entry is a screen
 * of the tab back stack with a point preview. The arbitrary hole circle has one
 * mode: the flag `advanced` of its route has no effect.
 */
internal fun EntryRegistry.patternEntries() {
    tab<HoleCircleRoute>(EntryKind.Screen, RouteArea.Functions) { route -> PatternEntry(this, route, PatternKind.HoleCircle) }
    tab<ArbitraryHoleCircleRoute>(EntryKind.Screen, RouteArea.Functions) { route ->
        PatternEntry(this, route, PatternKind.ArbitraryHoleCircle)
    }
    tab<HoleGridRoute>(EntryKind.Screen, RouteArea.Functions) { route -> PatternEntry(this, route, PatternKind.HoleGrid) }
    tab<EquidistantArrayRoute>(EntryKind.Screen, RouteArea.Functions) { route ->
        PatternEntry(this, route, PatternKind.EquidistantArray)
    }
    tab<ArcCutRoute>(EntryKind.Screen, RouteArea.Functions) { route -> PatternEntry(this, route, PatternKind.ArcCut) }
    tab<StepAndRepeatRoute>(EntryKind.Screen, RouteArea.Functions) { route ->
        PatternEntry(this, route, PatternKind.StepAndRepeat, route.subDatumIds)
    }
}

/**
 * One pattern screen. The entry owns the presenter, so the field values stay while the tab
 * changes. After a good "Create" the screen closes and the snackbar of the frame tells the
 * number of the new sub-datums.
 */
@Composable
private fun PatternEntry(scope: TabEntryScope, route: TabRoute, kind: PatternKind, sourceIds: List<String> = emptyList()) {
    val services = rememberFunctionServices()
    val presenter = rememberPresenter { PatternPresenter(kind, scope.readout, services, sourceIds) }
    val state by presenter.uiState.collectAsState()
    val events = remember(presenter, scope, route) { PresenterPatternEvents(presenter) { scope.navigator.pop(route) } }
    LaunchedEffect(state.created) {
        val count = state.created ?: return@LaunchedEffect
        scope.showMessage(UiText.of(Res.string.fnp_created, count))
        scope.navigator.pop(route)
    }
    PatternScreen(state, events)
}
