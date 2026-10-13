package org.superdro.core.ui.graphics.view

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToLong
import org.superdro.core.functions.PointUm
import org.superdro.core.functions.compensation.ToolPosition
import org.superdro.core.functions.drawing.ShapeGeometry
import org.superdro.core.functions.graphics.GraphActionContext
import org.superdro.core.functions.graphics.GraphHit
import org.superdro.core.functions.graphics.GraphHitTest
import org.superdro.core.functions.graphics.GraphLongPressAction
import org.superdro.core.functions.graphics.GraphSelection
import org.superdro.core.functions.graphics.GraphTapAction
import org.superdro.core.functions.graphics.GraphicalViewport
import org.superdro.core.functions.graphics.GuideButtons
import org.superdro.core.functions.graphics.GuideRules
import org.superdro.core.functions.graphics.ScreenMetrics
import org.superdro.core.functions.graphics.Vec2
import org.superdro.core.functions.graphics.ZoomPreset
import org.superdro.core.drawing.DrawingHitTest
import org.superdro.core.model.AxisId
import org.superdro.core.model.ShapeType
import org.superdro.core.ui.navigation.AddGuideRoute
import org.superdro.core.ui.navigation.RadiusCompensationRoute
import org.superdro.core.ui.navigation.TabRoute
import org.superdro.core.ui.navigation.WorkspaceTransformKind
import org.superdro.core.ui.navigation.WorkspaceTransformRoute
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.sound.UiSound

/** A dialog or a menu that the graphical view shows above the canvas. */
@Immutable
sealed interface ViewOverlay {
    /** The tool offset direction picker. [current] is the button of the signs of the X and Y tool offsets. */
    data class DirectionPicker(val current: ToolPosition) : ViewOverlay

    /** The sub-datum context menu of the point [subDatumId] at the screen position [anchor]. */
    data class SubDatumMenu(val subDatumId: String, val name: String, val buttons: GuideButtons, val anchor: Vec2) : ViewOverlay

    /** "Delete Geometry?" of the drawing shape [index]. */
    data class DeleteShape(val index: Int, val type: ShapeType) : ViewOverlay
}

/**
 * The UI state of one graphical view: the scene of the tab and the pan and zoom of this view.
 *
 * @property viewport The pan and zoom, or null before the first size of the canvas.
 */
@Immutable
data class GraphicalViewUiState(
    val scene: GraphicalScene? = null,
    val viewport: GraphicalViewport? = null,
    val overlay: ViewOverlay? = null,
) {
    /** True when "Re-center" shows: follow mode is off. */
    val recenterVisible: Boolean get() = viewport != null && !viewport.followMode

    /** True when the view is locked to the crosshairs. */
    val locked: Boolean get() = scene?.display?.viewLocked == true
}

/**
 * The presenter of one graphical view: the pane below or above the readout, the supporting pane,
 * or the full-screen entry. Each
 * view has its own pan and zoom. The scene, the selection, and the automatic functions are in the
 * [GraphicalTabPresenter] of the tab.
 *
 * Gestures:
 * - Tap: the hit test in the order point, drawing shape, guide, tool, and the tap action.
 * - Long press: the hit test and the long-press action.
 * - Double tap: zoom to fit with the origin and the tool inside, clamped.
 * - Drag: pan, and follow mode stops. No pan when the view is locked.
 * - Pinch: the zoom about the touch point, clamped to 0.01 to 10.0.
 *
 * The view draws only when its state changes: the state is a `StateFlow` of immutable
 * values, and the canvas reads only that state.
 *
 * @param pointTapInterceptor The measurement function gets the tap on a point first. It
 *   gives true when it used the tap.
 */
class GraphicalViewPresenter(
    val tab: GraphicalTabPresenter,
    private val pointTapInterceptor: (String) -> Boolean = { false },
) : ScopedPresenter(tab.deps.context) {

    private val viewport = MutableStateFlow<GraphicalViewport?>(null)
    private val overlayState = MutableStateFlow<ViewOverlay?>(null)
    private val paneOpen = MutableStateFlow(false)
    private val camera = MutableStateFlow(CameraSceneOverlay())

    /** True while the display pane of this view is open. */
    val displayPaneOpen: StateFlow<Boolean> = paneOpen.asStateFlow()
    private val openedOn = tab.deps.wallClock.now()

    /** The UI state of this view. */
    val uiState: StateFlow<GraphicalViewUiState> = combine(tab.scene, viewport, overlayState, camera) { stored, port, overlay, video ->
        val scene = stored?.let(video::apply)
        GraphicalViewUiState(scene, port?.let { value -> followed(value, scene) }, overlay)
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, GraphicalViewUiState())

    /**
     * The state of the camera video behind this view. While the video plays, the
     * overlay rules of [CameraSceneOverlay] change the scene: the view is locked to the crosshairs.
     */
    fun setCamera(overlay: CameraSceneOverlay) {
        camera.value = overlay
    }

    init {
        // A new drawing zooms to its extents, when the view is not locked.
        // A drawing that was stored after this view opened is a new import.
        scope.launch {
            var lastId: String? = null
            tab.drawing.collect { drawing ->
                if (drawing != null && drawing.id != lastId && drawing.createdOn >= openedOn) zoomToDrawing()
                lastId = drawing?.id ?: lastId
            }
        }
    }

    /** The canvas has the size [widthPx] × [heightPx] on a screen with [metrics]. A size change keeps the zoom. */
    fun onSize(widthPx: Double, heightPx: Double, metrics: ScreenMetrics) {
        viewport.update { current ->
            when {
                current == null || current.metrics != metrics -> GraphicalViewport(widthPx, heightPx, metrics, scale = current?.scale ?: 1.0)
                current.widthPx == widthPx && current.heightPx == heightPx -> current
                else -> current.resize(widthPx, heightPx)
            }
        }
    }

    /** A drag of the finger by ([dxPx], [dyPx]): pan, and follow mode stops. No pan when the view is locked. */
    fun onDrag(dxPx: Double, dyPx: Double) {
        if (uiState.value.locked) return
        viewport.update { current -> current?.let { value -> followed(value, tab.scene.value).panBy(dxPx, dyPx) } }
    }

    /** A pinch by [factor] about [focus]. */
    fun onPinch(factor: Double, focus: Vec2) {
        viewport.update { current -> current?.let { value -> followed(value, tab.scene.value).pinch(factor, focus) } }
    }

    /** A zoom preset: "5:1", "1:1", or "1:5". */
    fun onZoomPreset(preset: ZoomPreset) {
        viewport.update { current -> current?.let { value -> followed(value, tab.scene.value).zoomTo(preset) } }
    }

    /** "Re-center": follow mode starts again. */
    fun onRecenter() {
        viewport.update { current -> current?.recenter() }
    }

    /** A double tap: zoom to fit the origin, the points, and the tool. */
    fun onDoubleTap() {
        if (uiState.value.locked) return
        val scene = tab.scene.value ?: return
        val tool = scene.tool ?: PointUm(0.0, 0.0)
        viewport.update { current -> current?.zoomToFit(scene.points.map { point -> point.position }, tool) }
    }

    /**
     * A tap at the screen position [touch]. Gives the route to open, or null. The hit test order
     * is point, drawing shape, guide, tool.
     */
    fun onTap(touch: Vec2): TabRoute? {
        val state = uiState.value
        val scene = state.scene ?: return null
        val port = state.viewport ?: return null
        when (val hit = hitTest(touch, scene, port)) {
            is SceneHit.Shape -> tab.onShapeTap(hit.index)
            is SceneHit.Graph -> {
                if (hit.hit is GraphHit.Point && pointTapInterceptor(hit.hit.point.id)) return null
                when (val action = GraphHitTest.tapAction(hit.hit, actionContext(scene))) {
                    is GraphTapAction.Select -> when (val selection = action.selection) {
                        is GraphSelection.Point -> tab.onPointTap(selection.id)
                        is GraphSelection.Guide -> tab.onGuideTap(selection.id)
                        GraphSelection.None -> when (val target = hit.hit) {
                            is GraphHit.Point -> tab.onPointTap(target.point.id)
                            is GraphHit.Guide -> tab.onGuideTap(target.guide.id)
                            else -> Unit
                        }
                    }
                    GraphTapAction.OpenDirectionPicker -> overlayState.value = ViewOverlay.DirectionPicker(currentDirection())
                    GraphTapAction.NoAction -> Unit
                }
            }
        }
        return null
    }

    /** A long press at the screen position [touch]. Gives the route to open, or null. */
    fun onLongPress(touch: Vec2): TabRoute? {
        val state = uiState.value
        val scene = state.scene ?: return null
        val port = state.viewport ?: return null
        when (val hit = hitTest(touch, scene, port)) {
            is SceneHit.Shape -> {
                val shape = scene.drawing?.shapes?.getOrNull(hit.index) ?: return null
                overlayState.value = ViewOverlay.DeleteShape(hit.index, shape.type)
            }
            is SceneHit.Graph -> when (val action = GraphHitTest.longPressAction(hit.hit, actionContext(scene))) {
                is GraphLongPressAction.OpenSubDatumMenu -> {
                    val subDatum = tab.subDatum(action.subDatumId) ?: return null
                    overlayState.value = ViewOverlay.SubDatumMenu(subDatum.id, subDatum.name, GuideRules.buttonsFor(scene.plane, subDatum), touch)
                }
                is GraphLongPressAction.OpenPairCommands -> {
                    tab.deps.sounds.play(UiSound.Long, tab.deviceId)
                    return WorkspaceTransformRoute(WorkspaceTransformKind.Pair, pointAId = action.pointAId, pointBId = action.pointBId)
                }
                is GraphLongPressAction.AskDeleteGuide -> tab.deleteGuide(action.guideId)
                GraphLongPressAction.OpenRadiusCompensation -> return RadiusCompensationRoute
                GraphLongPressAction.AskClearRadiusCompensation -> tab.clearRadiusCompensation()
                is GraphLongPressAction.OpenAddGuide -> {
                    val position = scene.displayFrame.takeIf { frame -> !frame.isIdentity }?.inverse(Vec2.of(action.position))?.toPointUm()
                        ?: action.position
                    tab.requestAddGuide(position)
                    return AddGuideRoute
                }
                GraphLongPressAction.NoAction -> Unit
            }
        }
        return null
    }

    /** Opens or closes the display pane. */
    fun onDisplayPaneToggle() {
        paneOpen.update { open -> !open }
    }

    /** The overlay closed with no action. */
    fun onOverlayClosed() {
        overlayState.value = null
    }

    /** A button of the direction picker. */
    fun onDirection(position: ToolPosition) {
        overlayState.value = null
        tab.applyDirection(position)
    }

    /** "Delete" of the "Delete Geometry?" question. */
    fun onDeleteShapeConfirmed(index: Int, delete: (Int) -> Unit) {
        overlayState.value = null
        tab.onShapeDeleted()
        delete(index)
    }

    private fun zoomToDrawing() {
        val scene = tab.scene.value
        if (scene?.display?.viewLocked == true) return
        val box = ShapeGeometry.extents(tab.drawing.value?.shapes.orEmpty()) ?: return
        viewport.update { current -> current?.zoomToBox(box.minX / NM_PER_UM, box.minY / NM_PER_UM, box.maxX / NM_PER_UM, box.maxY / NM_PER_UM) }
    }

    /** In follow mode (and always when the view is locked), the tool is at the view center. */
    private fun followed(port: GraphicalViewport, scene: GraphicalScene?): GraphicalViewport {
        val tool = scene?.tool ?: return port
        val base = if (scene.display.viewLocked && !port.followMode) port.recenter() else port
        return base.follow(tool)
    }

    private fun actionContext(scene: GraphicalScene): GraphActionContext {
        val frame = tab.readout.frame.value
        val toolOffsetActive = frame != null && (frame.toolOffsetInUse(AxisId.X) || frame.toolOffsetInUse(AxisId.Y))
        return GraphActionContext(
            machineType = scene.machineType,
            plane = scene.plane,
            selection = scene.selection,
            selectedPoint = scene.selectedPoint,
            toolDiameterUm = frame?.workspace?.toolXUm ?: 0.0,
            toolOffsetActive = toolOffsetActive,
        )
    }

    private fun currentDirection(): ToolPosition {
        val frame = tab.readout.frame.value ?: return ToolPosition.Center
        fun sign(axis: AxisId): Int {
            val state = frame.state(axis)
            if (!state.toolOffsetActive || state.toolOffset == 0.0) return 0
            return if (state.toolOffset > 0.0) 1 else -1
        }
        val x = sign(AxisId.X)
        val y = sign(AxisId.Y)
        return ToolPosition.entries.firstOrNull { position -> position.dirX == x && position.dirY == y } ?: ToolPosition.Center
    }

    private sealed interface SceneHit {
        data class Graph(val hit: GraphHit) : SceneHit
        data class Shape(val index: Int) : SceneHit
    }

    private fun hitTest(touch: Vec2, scene: GraphicalScene, port: GraphicalViewport): SceneHit {
        val transform = port.transform
        val points = if (scene.display.subDatumsShown) scene.points else emptyList()
        GraphHitTest.hitPoint(touch, transform, points)?.let { point -> return SceneHit.Graph(GraphHit.Point(point)) }
        val drawing = scene.drawing
        if (drawing != null && scene.drawingSelectable && scene.drawingAlpha > 0.0) {
            val model = transform.toModel(touch)
            val stored = if (scene.displayFrame.isIdentity) model else scene.displayFrame.inverse(Vec2.of(model)).toPointUm()
            val toleranceNm = transform.pxToUmX(GraphHitTest.GUIDE_HIT_INCH * transform.metrics.xdpi) * NM_PER_UM
            DrawingHitTest.nearestShape(drawing, (stored.h * NM_PER_UM).roundToLong(), (stored.v * NM_PER_UM).roundToLong(), toleranceNm)
                ?.let { index -> return SceneHit.Shape(index) }
        }
        val guides = if (scene.display.guidesShown) scene.guides else emptyList()
        val cutter = scene.cutter.takeIf { scene.display.toolShown }
        return SceneHit.Graph(GraphHitTest.hit(touch, transform, emptyList(), guides, cutter))
    }

    private companion object {
        const val NM_PER_UM: Double = 1000.0
    }
}
