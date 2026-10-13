package org.superdro.core.ui.graphics.probe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import org.superdro.core.functions.probe.CoordinateMode
import org.superdro.core.functions.probe.EdgeSide
import org.superdro.core.functions.probe.FeatureType
import org.superdro.core.functions.probe.OriginOption
import org.superdro.core.model.AxisId
import org.superdro.core.ui.graphics.GraphicsDependencies
import org.superdro.core.ui.navigation.EdgeFinderRoute
import org.superdro.core.ui.navigation.EntryKind
import org.superdro.core.ui.navigation.EntryRegistry
import org.superdro.core.ui.navigation.ProbeProfileRoute
import org.superdro.core.ui.navigation.RouteArea
import org.superdro.core.ui.navigation.TabEntryScope
import org.superdro.core.ui.presenter.rememberPresenter
import org.superdro.core.ui.readout.LocalReadoutFeedback
import org.superdro.core.ui.rememberInstance
import org.superdro.core.ui.sound.rememberKeyFeedback

/**
 * The entries of the probe functions of the graphics area: the
 * edge finder screen. The graphics area registers it after the functions area, so it replaces the
 * placeholder of the functions area. The automatic Z reference is a part of the Z reference sheet of
 * the functions area ([ZReferenceAutoPresenter], [ZReferenceAutoSection]).
 */
fun EntryRegistry.probeEntries() {
    tab<EdgeFinderRoute>(EntryKind.Screen, RouteArea.Functions) { _ -> EdgeFinderEntry(this) }
}

/** The edge finder screen of the tab of [scope]. */
@Composable
fun EdgeFinderEntry(scope: TabEntryScope) {
    val deps = rememberInstance<GraphicsDependencies>()
    val presenter = rememberPresenter { EdgeFinderPresenter(scope.deviceId, scope.readout, deps) }
    val state by presenter.uiState.collectAsState()
    val events = remember(presenter, scope) { PresenterEdgeFinderEvents(presenter, scope) }
    CompositionLocalProvider(LocalReadoutFeedback provides rememberKeyFeedback(scope.deviceId)) {
        EdgeFinderScreen(state, events)
    }
}

/** The [EdgeFinderEvents] of a presenter: "Set Origin" and "Cancel" close the screen. */
class PresenterEdgeFinderEvents(private val presenter: EdgeFinderPresenter, private val scope: TabEntryScope) : EdgeFinderEvents {
    override fun onEdgeTap(side: EdgeSide) = presenter.onEdgeTap(side)
    override fun onEdgeLongPress(side: EdgeSide) = presenter.onEdgeLongPress(side)
    override fun onAuto() = presenter.onAutoToggle()
    override fun onCameraCenter() = presenter.onCameraCenter()
    override fun onFeature(axis: AxisId, feature: FeatureType) = presenter.onFeature(axis, feature)
    override fun onOption(axis: AxisId, option: OriginOption) = presenter.onOption(axis, option)
    override fun onMode(mode: CoordinateMode) = presenter.onMode(mode)
    override fun onZArm() = presenter.onZArm()
    override fun onZText(text: String) = presenter.onZText(text)
    override fun onZSync() = presenter.onZSync()
    override fun onSetOrigin() {
        if (presenter.onSetOrigin()) scope.navigator.pop(EdgeFinderRoute)
    }
    override fun onAddSubDatum(keepEdges: Boolean) = presenter.onAddSubDatum(keepEdges)
    override fun onCancel() {
        if (presenter.onCancel()) scope.navigator.pop(EdgeFinderRoute)
    }
    override fun onSelectProbe(id: String?) = presenter.onSelectProbe(id)
    override fun onNewProbe() {
        presenter.onNewProbe()
        scope.global.push(ProbeProfileRoute(null))
    }
    override fun onEditProbe(id: String) = scope.global.push(ProbeProfileRoute(id))
}
