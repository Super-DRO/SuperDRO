package org.superdro.core.ui.graphics.view

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import org.superdro.core.functions.compensation.ToolPosition
import org.superdro.core.functions.graphics.GraphicalViewport
import org.superdro.core.functions.graphics.GuideOrientation
import org.superdro.core.functions.graphics.ScreenMetrics
import org.superdro.core.functions.graphics.Vec2
import org.superdro.core.functions.graphics.ZoomPreset
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.GraphicalDisplay
import org.superdro.core.model.GraphicalSwitches
import org.superdro.core.model.Plane
import org.superdro.core.model.ThemeMode
import org.superdro.core.ui.adaptive.LocalWindowLayout
import org.superdro.core.ui.adaptive.WindowLayout
import org.superdro.core.ui.theme.AppTheme

/** The events that a UI test of the graphical view saw. */
class RecordingViewEvents : GraphicalViewEvents, DisplayPaneEvents {
    val calls = mutableListOf<String>()
    var display = GraphicalDisplay()

    override fun onSize(widthPx: Double, heightPx: Double) = Unit
    override fun onTap(position: Vec2) { calls += "tap" }
    override fun onDoubleTap() { calls += "doubleTap" }
    override fun onLongPress(position: Vec2) { calls += "longPress" }
    override fun onDrag(dxPx: Double, dyPx: Double) { calls += "drag" }
    override fun onPinch(factor: Double, focus: Vec2) { calls += "pinch" }
    override fun onSwitch(switch: GraphicalSwitch, on: Boolean) { calls += "switch ${switch.name} $on" }
    override fun onPlane(plane: Plane) { calls += "plane ${plane.name}" }
    override fun onDisplayPaneToggle() { calls += "display" }
    override fun onRecenter() { calls += "recenter" }
    override fun onZoomPreset(preset: ZoomPreset) { calls += "zoom ${preset.label}" }
    override fun onFullScreen() { calls += "fullScreen" }
    override fun onRotatePart() { calls += "rotatePart" }
    override fun onEdgeFinder() { calls += "edgeFinder" }
    override fun onThreadArm() { calls += "threadArm" }
    override fun onDirection(position: ToolPosition) { calls += "direction ${position.name}" }
    override fun onOverlayDismiss() { calls += "dismiss" }
    override fun onEditSubDatum(id: String) { calls += "edit $id" }
    override fun onDeleteSubDatum(id: String) { calls += "delete $id" }
    override fun onSetOrigin(id: String) { calls += "origin $id" }
    override fun onAddGuideAtPoint(id: String, orientation: GuideOrientation) { calls += "guide $id ${orientation.name}" }
    override fun onDeleteShapeConfirmed(index: Int) { calls += "deleteShape $index" }
    override fun onThreadSelected(id: String) { calls += "thread $id" }
    override fun onThreadClose() { calls += "threadClose" }

    override fun onDisplay(change: (GraphicalDisplay) -> GraphicalDisplay) {
        display = change(display)
        calls += "displayChange"
    }
    override fun onClearGuides() { calls += "clearGuides" }
    override fun onImportDrawing() { calls += "import" }
    override fun onExportDrawing() { calls += "export" }
    override fun onClearDrawing() { calls += "clearDrawing" }
    override fun onLayerHidden(index: Int, hidden: Boolean) { calls += "layer $index $hidden" }
    override fun onCameraSetup() { calls += "camera" }
    override fun onReferenceAlpha(alpha: Int) { calls += "alpha $alpha" }
    override fun onClearReference() { calls += "clearReference" }
    override fun onTakePhoto() { calls += "photo" }
    override fun onSelectImage() { calls += "select" }
}

/** The sample states of the graphical view for the UI tests and the screenshots. */
object ViewStates {

    /** 160 dpi for each dp: the JVM metrics of [rememberScreenMetrics]. */
    val metrics: ScreenMetrics = ScreenMetrics(xdpi = 160.0, ydpi = 160.0, density = 1.0)

    /** The mill workspace with points, guides, an active point, and a cutter of 10 mm with a tool offset. */
    fun millView(widthPx: Double, heightPx: Double, followMode: Boolean = true, display: GraphicalDisplay = GraphicalDisplay()): Pair<GraphicalViewUiState, GraphicalControlsUiState> {
        val workspace = ViewSamples.millWorkspace().copy(
            axisStates = mapOf(AxisId.X to AxisState(toolOffset = 5_000.0, toolOffsetActive = true)),
            graphical = GraphicalSwitches(snapToPoint = true, automaticProximityWarning = true),
        )
        val profile = ViewSamples.mill.copy(graphicalDisplay = display)
        val frame = ViewSamples.frame(profile, workspace, x = 3_000.0, y = 2_000.0)
        val scene = GraphicalSceneBuilder.build(frame, ViewSamples.drawing, selectedShape = 2)
        val port = GraphicalViewport(widthPx, heightPx, metrics, followMode = followMode).follow(scene.tool!!)
        val controls = GraphicalControlsUiState(
            machineType = profile.machineType,
            plane = Plane.XY,
            switches = listOf(
                GraphicalSwitch.SnapToPoint to true,
                GraphicalSwitch.SnapToGuide to false,
                GraphicalSwitch.AutomaticToolOffset to false,
                GraphicalSwitch.AutomaticProximityWarning to true,
                GraphicalSwitch.AutomaticZero to false,
                GraphicalSwitch.AutomaticSubDatum to false,
            ),
            display = display,
            workspaceName = workspace.name,
            hasDrawing = true,
            drawingLayers = ViewSamples.drawing.layers,
            edgeFinderVisible = true,
        )
        return GraphicalViewUiState(scene, if (followMode) port else port.copy(followMode = false)) to controls
    }

    /** The lathe workspace with an armed M10 thread. */
    fun latheView(widthPx: Double, heightPx: Double): Pair<GraphicalViewUiState, GraphicalControlsUiState> {
        val spec = ViewSamples.m10Thread()
        val workspace = ViewSamples.lathe.workspaces.first().copy(
            subDatums = org.superdro.core.functions.thread.ThreadPoints.toSubDatums(spec) +
                org.superdro.core.model.SubDatum(id = "free", x = 9_000.0, z = 6_000.0),
            armedThreadId = spec.id,
        )
        val frame = ViewSamples.frame(ViewSamples.lathe, workspace, x = 4_700.0, z = -6_000.0)
        val scene = GraphicalSceneBuilder.build(frame, threads = listOf(spec))
        val port = GraphicalViewport(widthPx, heightPx, metrics, scale = 3.0).follow(scene.tool!!)
        val controls = GraphicalControlsUiState(
            machineType = ViewSamples.lathe.machineType,
            plane = Plane.ZX,
            planes = listOf(Plane.ZX),
            switches = listOf(
                GraphicalSwitch.SnapToPoint to false,
                GraphicalSwitch.SnapToGuide to false,
                GraphicalSwitch.AutomaticProximityWarning to false,
            ),
            workspaceName = workspace.name,
            threadArmed = true,
        )
        return GraphicalViewUiState(scene, port) to controls
    }
}

/** The theme and the window layout of a test of the graphical view. */
@Composable
fun ViewThemed(dark: Boolean, widthDp: Int, heightDp: Int, content: @Composable () -> Unit) {
    AppTheme(mode = if (dark) ThemeMode.Dark else ThemeMode.System, systemInDarkTheme = false) {
        CompositionLocalProvider(LocalWindowLayout provides WindowLayout.of(widthDp.dp, heightDp.dp)) { content() }
    }
}
