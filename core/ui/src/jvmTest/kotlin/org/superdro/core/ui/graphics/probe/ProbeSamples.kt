package org.superdro.core.ui.graphics.probe

import org.superdro.core.functions.probe.CoordinateMode
import org.superdro.core.functions.probe.EdgeSide
import org.superdro.core.functions.probe.EdgeStatus
import org.superdro.core.functions.probe.FeatureType
import org.superdro.core.functions.probe.OriginOption
import org.superdro.core.model.AxisId
import org.superdro.core.model.ProbeType
import org.superdro.core.ui.adapter.probe.ProbeFunction
import org.superdro.core.ui.adapter.probe.ProbeOption
import org.superdro.core.ui.adapter.probe.ProbeSelectorUiState
import org.superdro.core.ui.text.UiText

/** The sample states of the probe screens for the UI tests and the screenshots. */
object ProbeSamples {

    /** The probe selector of the edge finder with two own profiles; the touch probe is selected. */
    val edgeSelector: ProbeSelectorUiState = ProbeSelectorUiState(
        function = ProbeFunction.EdgeFinder,
        options = listOf(
            ProbeOption("touch", "Renishaw 2 mm", ProbeType.TouchProbe, editable = true),
            ProbeOption("edge", "Edge finder 0.200\"", ProbeType.EdgeFinder2D, editable = true),
        ),
        selectedId = "touch",
        loaded = true,
    )

    /** The edge finder during a probe session: Left found, Right sampling, AUTO on. */
    fun edgeFinder(): EdgeFinderUiState = EdgeFinderUiState(
        loaded = true,
        edges = listOf(
            EdgeButtonUiState(EdgeSide.Left, EdgeStatus.Located, "-12.004"),
            EdgeButtonUiState(EdgeSide.Right, EdgeStatus.Scanning, null),
            EdgeButtonUiState(EdgeSide.Near, EdgeStatus.Located, "3.250"),
            EdgeButtonUiState(EdgeSide.Far, EdgeStatus.Unknown, null),
        ),
        autoVisible = true,
        autoOn = true,
        xFeature = FeatureType.External,
        yFeature = FeatureType.Internal,
        featureEnabled = false,
        xOption = OriginOption.SideA,
        yOption = OriginOption.SideA,
        xOptions = setOf(OriginOption.SideA),
        yOptions = setOf(OriginOption.SideA),
        originText = "x: -11.004 mm y: 2.250 mm",
        zRowVisible = true,
        zArmed = false,
        zText = "-4.512",
        mode = CoordinateMode.Absolute,
        setOriginEnabled = true,
        addSubDatumEnabled = true,
        connected = true,
        message = UiText.Key(
            "edge_finder_spread_warning",
            "Warning: maximum spread between the samples is %1\$s %2\$s",
            listOf("0.030", "mm"),
        ),
        selector = edgeSelector,
    )

    /** The Z reference with the probe armed. */
    fun zAuto(armed: Boolean = true): ZReferenceAutoUiState = ZReferenceAutoUiState(
        loaded = true,
        visible = true,
        enabled = true,
        armed = armed,
        setterHeightUm = 25_000.0,
        setterHeightText = "25.000",
        selector = ProbeSelectorUiState(
            function = ProbeFunction.ZReference,
            options = listOf(ProbeOption("setter", "Setter 25 mm", ProbeType.ToolHeightSetter, editable = true)),
            selectedId = "setter",
            loaded = true,
        ),
    )

    /** A recorder of the events of the edge finder screen. */
    class RecordingEvents : EdgeFinderEvents {
        val calls = mutableListOf<String>()
        override fun onEdgeTap(side: EdgeSide) { calls += "tap $side" }
        override fun onEdgeLongPress(side: EdgeSide) { calls += "long $side" }
        override fun onAuto() { calls += "auto" }
        override fun onCameraCenter() { calls += "camera" }
        override fun onFeature(axis: AxisId, feature: FeatureType) { calls += "feature $axis $feature" }
        override fun onOption(axis: AxisId, option: OriginOption) { calls += "option $axis $option" }
        override fun onMode(mode: CoordinateMode) { calls += "mode $mode" }
        override fun onZArm() { calls += "zarm" }
        override fun onZText(text: String) { calls += "ztext $text" }
        override fun onZSync() { calls += "zsync" }
        override fun onSetOrigin() { calls += "set origin" }
        override fun onAddSubDatum(keepEdges: Boolean) { calls += "add $keepEdges" }
        override fun onCancel() { calls += "cancel" }
        override fun onSelectProbe(id: String?) { calls += "select $id" }
        override fun onNewProbe() { calls += "new probe" }
        override fun onEditProbe(id: String) { calls += "edit $id" }
    }
}
