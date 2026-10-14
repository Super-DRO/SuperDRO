package org.superdro.core.ui.graphics.view

import androidx.compose.runtime.Immutable
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.superdro.core.connection.DeviceId
import org.superdro.core.functions.PointUm
import org.superdro.core.functions.compensation.CompensationResult
import org.superdro.core.functions.compensation.ToolCompensation
import org.superdro.core.functions.compensation.ToolPosition
import org.superdro.core.functions.graphics.AutoGuideActions
import org.superdro.core.functions.graphics.AutoPlungeActions
import org.superdro.core.functions.graphics.AutoPointActions
import org.superdro.core.functions.graphics.GuideOrientation
import org.superdro.core.functions.graphics.GuideRules
import org.superdro.core.functions.graphics.GuideSnapper
import org.superdro.core.functions.graphics.PlaneAxes
import org.superdro.core.functions.graphics.PlungeWatcher
import org.superdro.core.functions.graphics.SnapToPoint
import org.superdro.core.model.AxisId
import org.superdro.core.model.Drawing
import org.superdro.core.model.GraphicalDisplay
import org.superdro.core.model.GraphicalSwitches
import org.superdro.core.model.Guide
import org.superdro.core.model.Plane
import org.superdro.core.model.ProximityTarget
import org.superdro.core.model.SubDatum
import org.superdro.core.model.ThreadSpec
import org.superdro.core.model.Tool
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.readout.operations.AxisOperations
import org.superdro.core.readout.operations.SubDatumOperations
import org.superdro.core.resources.Res
import org.superdro.core.resources.graphics_undo_auto_zero
import org.superdro.core.resources.graphics_undo_clear_radius
import org.superdro.core.resources.graphics_undo_delete_guide
import org.superdro.core.resources.graphics_undo_delete_guides
import org.superdro.core.resources.graphics_undo_delete_sub_datum
import org.superdro.core.resources.graphics_undo_direction
import org.superdro.core.resources.graphics_undo_set_origin
import org.superdro.core.resources.graphics_undo_tool_offset
import org.superdro.core.store.valueOrNull
import org.superdro.core.ui.graphics.GraphicsDependencies
import org.superdro.core.ui.graphics.thread.ThreadCutController
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.readout.TabReadout
import org.superdro.core.ui.sound.UiSound
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.toUiText

/** One automatic function switch of the graphical view. */
enum class GraphicalSwitch { SnapToPoint, SnapToGuide, AutomaticToolOffset, AutomaticProximityWarning, AutomaticZero, AutomaticSubDatum }

/** The undo of a delete of the graphical view: the snackbar shows [label] with "Undo" for 10 s. */
@Immutable
data class GraphicsUndo(val id: Long, val label: UiText)

/**
 * The controls of the graphical view: the plane selector, the automatic function switches, the
 * display pane, and the buttons of the top strip.
 *
 * @property planes The planes of the selector: `XY`, `XZ`, `YZ` on a mill, only `ZX` on a lathe.
 * @property switches The switches that show: the tool offset switch on a mill in the plane `XY`,
 *   the plunge switches on a mill.
 * @property measurement True in the measurement mode.
 * @property partRotationDegrees The angle of an active part rotation, or null.
 * @property alignmentDegrees The angle of an active axis alignment, or null.
 */
@Immutable
data class GraphicalControlsUiState(
    val machineType: org.superdro.core.model.MachineType = org.superdro.core.model.MachineType.VerticalMill,
    val plane: Plane = Plane.XY,
    val planes: List<Plane> = listOf(Plane.XY, Plane.XZ, Plane.YZ),
    val switches: List<Pair<GraphicalSwitch, Boolean>> = emptyList(),
    val display: GraphicalDisplay = GraphicalDisplay(),
    val measurement: Boolean = false,
    val workspaceName: String = "",
    val hasDrawing: Boolean = false,
    val drawingLayers: List<org.superdro.core.model.DrawingLayer> = emptyList(),
    val referenceAlpha: Int? = null,
    val partRotationDegrees: Double? = null,
    val alignmentDegrees: Double? = null,
    val threadArmed: Boolean = false,
    val edgeFinderVisible: Boolean = false,
)

/** The "Add guide" dialog of a long press on an empty area: the touch position of the model. */
@Immutable
data class AddGuideRequest(val position: PointUm, val plane: Plane)

/**
 * The state of the graphical view of one tab. The tab owns it
 * ([org.superdro.core.ui.navigation.TabEntryScope.tabPresenter] with [NAME]), so the pane below
 * the readout, the full-screen entry, and the dialogs of the view use the same object. The
 * automatic functions run while it lives.
 *
 * Rules:
 * - The selected point is the active sub-datum. A tap on a point activates it, or makes it not
 *   active. The selected guide is state of the view only: a guide is not a sub-datum in
 *   SuperDRO.
 * - Snap to point, snap to guide, the automatic tool offset, the automatic proximity warning, the
 *   automatic zero, and the automatic sub-datum are workspace switches (default off).
 * - Snap to guide, the automatic tool offset, and the automatic proximity warning at a guide act
 *   only when the guides layer shows. Snap to point does not act in the measurement mode.
 * - The automatic proximity warning at a selected point (snap to point or a tap) sets a point
 *   target on the two axes of the plane. The target clears when the point is not the active
 *   sub-datum.
 * - A delete of a guide or a sub-datum has undo for 10 s.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalAtomicApi::class)
class GraphicalTabPresenter(
    val deviceId: DeviceId,
    val readout: TabReadout,
    val deps: GraphicsDependencies,
) : ScopedPresenter(deps.context) {

    private val profileId: String = deviceId.value
    private val ids = AtomicLong(0L)
    private val selectedGuide = MutableStateFlow<String?>(null)
    private val selectedShapeIndex = MutableStateFlow<Int?>(null)
    private val pendingGuide = MutableStateFlow<AddGuideRequest?>(null)
    private val undoState = MutableStateFlow<GraphicsUndo?>(null)
    private var undoAction: (suspend () -> Unit)? = null
    private var undoTimer: Job? = null
    private val snapper = GuideSnapper()
    private val plunges = PlungeWatcher()

    /** The thread outline of the armed thread: a new frame does not build it again. */
    private val threadOutlines = ThreadOutlineCache()

    private var lastTool: PointUm? = null
    private var lastPlunge: Triple<Double, Double, Double>? = null

    private val workspaceId: StateFlow<String?> = readout.frame.map { frame -> frame?.workspace?.id }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, readout.frame.value?.workspace?.id)

    /** The drawing of the active workspace. */
    val drawing: StateFlow<Drawing?> = workspaceId
        .flatMapLatest { id -> if (id == null) flowOf(null) else deps.drawings.drawing(id) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** The threads of the active workspace. */
    val threads: StateFlow<List<ThreadSpec>> = workspaceId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else deps.machines.threads(id) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The tool library of the profile, for the lathe tool outline. */
    val tools: StateFlow<List<Tool>> = deps.tools.library(profileId).stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The scene of the view, or null until the profile and the workspace load. */
    val scene: StateFlow<GraphicalScene?> = combine(
        readout.frame,
        drawing,
        combine(tools, threads) { library, list -> library to list },
        selectedGuide,
        selectedShapeIndex,
    ) { frame, current, (library, list), guide, shape ->
        frame?.let { value -> GraphicalSceneBuilder.build(value, current, library, list, guide, shape, threadOutlines::of) }
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, null)

    /** The controls of the view. */
    val controls: StateFlow<GraphicalControlsUiState> = combine(readout.frame, drawing) { frame, current ->
        if (frame == null) GraphicalControlsUiState() else controlsOf(frame, current)
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, GraphicalControlsUiState())

    /** The thread arm and the thread cut panel. */
    val threadCut: ThreadCutController = ThreadCutController(scope, deps, readout, threads)

    /** The pending "Add guide" dialog, or null. */
    val addGuide: StateFlow<AddGuideRequest?> = pendingGuide.asStateFlow()

    /** The undo of the last delete, or null. */
    val undo: StateFlow<GraphicsUndo?> = undoState.asStateFlow()

    init {
        scope.launch {
            readout.frame.filterNotNull().collect { frame -> runAutomaticFunctions(frame) }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Selection

    /** A tap on the point [id]: select it (activate the sub-datum) or clear it. Snap to point turns off. */
    fun onPointTap(id: String) {
        val frame = readout.frame.value ?: return
        selectedGuide.value = null
        val deactivate = frame.workspace.activeSubDatumId == id
        val result = if (deactivate) SubDatumOperations.deactivate(frame) else SubDatumOperations.activate(frame, id)
        readout.apply(result, null)
        if (deactivate) clearPointTarget() else onPointSelected(frame, id)
        if (frame.workspace.graphical.snapToPoint) setSwitch(GraphicalSwitch.SnapToPoint, false)
        deps.sounds.play(UiSound.Short, deviceId)
    }

    /** A tap on the guide [id]: select it or clear it. Snap to guide turns off. */
    fun onGuideTap(id: String) {
        val frame = readout.frame.value ?: return
        if (frame.workspace.graphical.snapToGuide) setSwitch(GraphicalSwitch.SnapToGuide, false)
        if (selectedGuide.value == id) {
            selectedGuide.value = null
        } else {
            onGuideSelected(frame, id)
        }
        deps.sounds.play(UiSound.Short, deviceId)
    }

    /** A tap on the drawing shape [index]: select it or clear it. */
    fun onShapeTap(index: Int) {
        selectedShapeIndex.update { current -> if (current == index) null else index }
        deps.sounds.play(UiSound.Short, deviceId)
    }

    /** The drawing shape [index] went away: clear the shape selection. */
    fun onShapeDeleted() {
        selectedShapeIndex.value = null
    }

    /** "Delete" of "Delete Geometry?": deletes the drawing shape [index]. */
    fun deleteShape(index: Int) {
        val id = workspaceId.value ?: return
        selectedShapeIndex.value = null
        scope.launch { deps.drawings.deleteShape(id, index) }
    }

    /** Shows or hides the drawing layer [index]. */
    fun setLayerHidden(index: Int, hidden: Boolean) {
        val id = workspaceId.value ?: return
        scope.launch { deps.drawings.setLayerHidden(id, index, hidden) }
    }

    /** Sets the alpha of the reference image: 255, 127, or 0. */
    fun setReferenceAlpha(alpha: Int) {
        val frame = readout.frame.value ?: return
        val image = frame.workspace.referenceImage ?: return
        scope.launch { deps.machines.setReferenceImage(frame.workspace.id, image.copy(alpha = alpha)) }
    }

    // ---------------------------------------------------------------------------------------
    // Switches, plane, display

    /** Sets the automatic function [switch] of the workspace. */
    fun setSwitch(switch: GraphicalSwitch, on: Boolean) {
        readout.applyWorkspace(null) { workspace -> workspace.copy(graphical = switchesWith(workspace.graphical, switch, on)) }
        if (switch == GraphicalSwitch.SnapToGuide) snapper.reset()
        if (switch == GraphicalSwitch.AutomaticZero || switch == GraphicalSwitch.AutomaticSubDatum) plunges.reset()
    }

    /** Selects the plane [plane] of a mill. A lathe always uses `ZX`. */
    fun setPlane(plane: Plane) {
        val frame = readout.frame.value ?: return
        if (frame.profile.machineType.isLathe || plane == Plane.ZX) return
        selectedGuide.value = null
        readout.applyWorkspace(null) { workspace -> workspace.copy(plane = plane) }
    }

    /** Changes the display pane state of the profile. */
    fun updateDisplay(change: (GraphicalDisplay) -> GraphicalDisplay) {
        scope.launch { deps.machines.updateProfile(profileId) { profile -> profile.copy(graphicalDisplay = change(profile.graphicalDisplay)) } }
    }

    // ---------------------------------------------------------------------------------------
    // Tool

    /** The direction picker: applies the radius compensation with the direction [position]. */
    fun applyDirection(position: ToolPosition) {
        val frame = readout.frame.value ?: return
        val tool = tools.value.firstOrNull { item -> item.id == frame.workspace.activeToolId }
        val workspace = frame.workspace
        when (val result = ToolCompensation.radius(workspace, position, workspace.toolXUm, tool)) {
            is CompensationResult.Applied -> {
                // The picker keeps the tooth count of a diameter that is not a library tool.
                val next = if (tool == null) result.workspace.copy(toothCount = workspace.toothCount) else result.workspace
                readout.applyWorkspace(UiText.of(Res.string.graphics_undo_direction)) { stored ->
                    stored.copy(axisStates = next.axisStates, toolXUm = next.toolXUm, toothCount = next.toothCount, activeToolId = next.activeToolId)
                }
            }
            is CompensationResult.Failure -> readout.showMessage(result.message.toUiText())
            CompensationResult.NoChange -> Unit
        }
    }

    /** The long press on the tool with an active radius compensation: clear it with undo. */
    fun clearRadiusCompensation() {
        readout.applyWorkspace(UiText.of(Res.string.graphics_undo_clear_radius)) { workspace -> ToolCompensation.clearRadius(workspace) }
        deps.sounds.play(UiSound.Long, deviceId)
    }

    // ---------------------------------------------------------------------------------------
    // Guides

    /** A long press on an empty area: the "Add guide" dialog at [position]. */
    fun requestAddGuide(position: PointUm) {
        val frame = readout.frame.value ?: return
        pendingGuide.value = AddGuideRequest(position, PlaneAxes.planeFor(frame.profile.machineType.isLathe, frame.workspace.plane))
    }

    /** The "Add guide" dialog closed. */
    fun onAddGuideClosed() {
        pendingGuide.value = null
    }

    /**
     * Adds a guide of [orientation] at the axis value [valueUm] (internal units of the axis).
     * Gives false when the workspace has the same guide: the app does not add a duplicate guide.
     */
    fun addGuide(orientation: GuideOrientation, valueUm: Double): Boolean {
        val frame = readout.frame.value ?: return false
        val plane = PlaneAxes.planeFor(frame.profile.machineType.isLathe, frame.workspace.plane)
        val guide = GuideRules.newGuide(frame.workspace.guides, plane, orientation, valueUm) ?: return false
        scope.launch { deps.machines.saveGuide(frame.workspace.id, guide) }
        return true
    }

    /** The long press on a guide: deletes it with undo. */
    fun deleteGuide(id: String) {
        val frame = readout.frame.value ?: return
        val guide = frame.workspace.guides.firstOrNull { item -> item.id == id } ?: return
        if (selectedGuide.value == id) selectedGuide.value = null
        val workspaceId = frame.workspace.id
        scope.launch { deps.machines.deleteGuides(listOf(id)) }
        showUndo(UiText.of(Res.string.graphics_undo_delete_guide)) { deps.machines.saveGuide(workspaceId, guide) }
        deps.sounds.play(UiSound.Long, deviceId)
    }

    /** "Clear" of the guides row of the display pane: deletes all guides with undo. */
    fun deleteAllGuides() {
        val frame = readout.frame.value ?: return
        val guides = frame.workspace.guides
        if (guides.isEmpty()) return
        selectedGuide.value = null
        val workspaceId = frame.workspace.id
        scope.launch { deps.machines.deleteGuides(guides.map { guide -> guide.id }) }
        showUndo(UiText.of(Res.string.graphics_undo_delete_guides)) { guides.forEach { guide -> deps.machines.saveGuide(workspaceId, guide) } }
    }

    // ---------------------------------------------------------------------------------------
    // Sub-datum context menu

    /** "Delete Sub-Datum": deletes the point with undo. */
    fun deleteSubDatum(id: String) {
        val frame = readout.frame.value ?: return
        val workspaceId = frame.workspace.id
        scope.launch {
            val deleted = deps.machines.deleteSubDatums(workspaceId, listOf(id))
            if (deleted.isNotEmpty()) {
                showUndo(UiText.of(Res.string.graphics_undo_delete_sub_datum, deleted.first().name)) {
                    deps.machines.insertSubDatums(workspaceId, deleted)
                }
            }
        }
    }

    /** "Set Absolute Origin Here": the absolute origin moves to the point, with undo. */
    fun setOriginAt(id: String) {
        val frame = readout.frame.value ?: return
        val name = frame.workspace.subDatums.firstOrNull { point -> point.id == id }?.name.orEmpty()
        val unavailable = readout.positionUnavailable()
        if (unavailable != null) {
            readout.showMessage(unavailable)
            deps.sounds.play(UiSound.Error, deviceId)
            return
        }
        readout.apply(SubDatumOperations.setOriginAt(frame, id), UiText.of(Res.string.graphics_undo_set_origin, name))
    }

    /**
     * "Add Horizontal Guide Here" or "Add Vertical Guide Here": a guide at the coordinate of
     * the point that the guide stores. Gives false when the point has no such coordinate.
     */
    fun addGuideAtPoint(id: String, orientation: GuideOrientation): Boolean {
        val frame = readout.frame.value ?: return false
        val subDatum = frame.workspace.subDatums.firstOrNull { point -> point.id == id } ?: return false
        val plane = PlaneAxes.planeFor(frame.profile.machineType.isLathe, frame.workspace.plane)
        val buttons = GuideRules.buttonsFor(plane, subDatum)
        val value = when (orientation) {
            GuideOrientation.Horizontal -> buttons.horizontalGuideValue
            GuideOrientation.Vertical -> buttons.verticalGuideValue
        } ?: return false
        return addGuide(orientation, value)
    }

    /** Gives the sub-datum [id] of the active workspace, or null. */
    fun subDatum(id: String): SubDatum? = readout.frame.value?.workspace?.subDatums?.firstOrNull { point -> point.id == id }

    // ---------------------------------------------------------------------------------------
    // Undo

    /** The user tapped "Undo" of [id]. */
    fun onUndo(id: Long) {
        val action = undoAction
        if (undoState.value?.id != id || action == null) return
        clearUndo()
        scope.launch { action() }
    }

    /** The snackbar of [id] went away with no "Undo". */
    fun onUndoDismissed(id: Long) {
        if (undoState.value?.id == id) clearUndo()
    }

    private fun showUndo(label: UiText, action: suspend () -> Unit) {
        val id = ids.incrementAndFetch()
        undoState.value = GraphicsUndo(id, label)
        undoAction = action
        undoTimer?.cancel()
        undoTimer = scope.launch {
            delay(UNDO_MS)
            if (undoState.value?.id == id) clearUndo()
        }
    }

    private fun clearUndo() {
        undoState.value = null
        undoAction = null
    }

    // ---------------------------------------------------------------------------------------
    // Automatic functions

    private fun runAutomaticFunctions(frame: ReadoutFrame) {
        val plane = PlaneAxes.planeFor(frame.profile.machineType.isLathe, frame.workspace.plane)
        val switches = frame.workspace.graphical
        frame.workspace.proximity?.pointId?.let { pointId ->
            if (pointId != frame.workspace.activeSubDatumId) clearPointTarget()
        }
        runPlungeWatcher(frame, switches)
        val tool = GraphicalSceneBuilder.toolPosition(frame, plane) ?: return
        if (tool == lastTool) return
        lastTool = tool
        val display = frame.profile.graphicalDisplay
        if (switches.snapToPoint && !frame.measurementActive) {
            val points = scene.value?.points ?: GraphicalSceneBuilder.viewPoints(frame.workspace, plane, GraphicalSceneBuilder.displayFrameOf(frame, plane))
            SnapToPoint.select(tool, points, frame.workspace.activeSubDatumId)?.let { id ->
                selectedGuide.value = null
                readout.apply(SubDatumOperations.activate(frame, id), null)
                onPointSelected(frame, id)
            }
        }
        if (switches.snapToGuide && display.guidesShown) {
            val guides = scene.value?.guides ?: GuideRules.viewsOf(plane, frame.workspace.guides)
            snapper.update(clock.nowMs(), tool, guides)?.let { id ->
                if (id != selectedGuide.value) onGuideSelected(frame, id)
            }
        }
    }

    /** The Z plunge detector of the automatic zero and the automatic sub-datum. */
    private fun runPlungeWatcher(frame: ReadoutFrame, switches: GraphicalSwitches) {
        if (!PlungeWatcher.isActive(frame.profile.machineType, frame.profile.machineMode, switches.automaticZero, switches.automaticSubDatum)) return
        val x = frame.reading(AxisId.X)
        val y = frame.reading(AxisId.Y)
        val z = frame.reading(AxisId.Z)
        if (!x.hasValue || !y.hasValue || !z.hasValue) return
        val sample = Triple(x.absolute, y.absolute, z.absolute)
        if (sample == lastPlunge) return
        lastPlunge = sample
        plunges.update(z.absolute, x.absolute, y.absolute)?.let { plunge -> onPlunge(frame, plunge, x.absolute, y.absolute, switches) }
    }

    private fun onPlunge(frame: ReadoutFrame, plunge: org.superdro.core.functions.graphics.Plunge, x: Double, y: Double, switches: GraphicalSwitches) {
        if (switches.automaticZero) {
            readout.applyWorkspace(UiText.of(Res.string.graphics_undo_auto_zero)) { stored ->
                var current = frame.with(workspace = stored.copy(subDatums = frame.workspace.subDatums))
                AutoPlungeActions.AUTO_ZERO_AXES.forEach { axis ->
                    current = current.with(workspace = AxisOperations.setIncremental(current, axis, 0.0).workspace)
                }
                current.workspace.copy(subDatums = stored.subDatums)
            }
        }
        if (switches.automaticSubDatum) {
            val zApplies = AutoPlungeActions.zApplies(frame.profile.z.enabled, frame.profile.machineType, frame.profile.use3dSubDatums)
            val point = AutoPlungeActions.autoSubDatum(plunge, x, y, zApplies, frame.workspace.subDatums) ?: return
            val workspaceId = frame.workspace.id
            val activate = frame.profile.display.activateNewSubDatum
            scope.launch {
                val stored = deps.machines.addSubDatum(workspaceId, SubDatum(x = point.xUm, y = point.yUm, z = point.zUm)).valueOrNull() ?: return@launch
                if (activate) activateWhenLoaded(stored.id)
            }
        }
        deps.sounds.play(UiSound.Short, deviceId)
    }

    private suspend fun activateWhenLoaded(id: String) {
        val frame = withTimeoutOrNull(ThreadCutController.CORNER_WAIT_MS) {
            readout.frame.first { current -> current?.workspace?.subDatums?.any { point -> point.id == id } == true }
        } ?: return
        readout.apply(SubDatumOperations.activate(frame, id), null)
    }

    /**
     * The guide [id] becomes the selected item: the automatic tool offset (mill, plane `XY`) and
     * the automatic proximity warning act when the guides layer shows.
     */
    private fun onGuideSelected(frame: ReadoutFrame, id: String) {
        selectedGuide.value = id
        val guide = frame.workspace.guides.firstOrNull { item -> item.id == id } ?: return
        val switches = frame.workspace.graphical
        if (!frame.profile.graphicalDisplay.guidesShown) return
        val plane = PlaneAxes.planeFor(frame.profile.machineType.isLathe, frame.workspace.plane)
        if (switches.automaticToolOffset) {
            val reading = frame.reading(guide.axis)
            val position = reading.absolute - reading.toolOffset
            AutoGuideActions.toolOffset(guide, plane, frame.profile.machineType, position, frame.workspace.toolXUm)?.let { change ->
                readout.applyWorkspace(UiText.of(Res.string.graphics_undo_tool_offset)) { workspace ->
                    val state = workspace.axisStates[change.axis] ?: frame.state(change.axis)
                    workspace.copy(
                        axisStates = workspace.axisStates + (change.axis to state.copy(toolOffset = change.offsetUm, toolOffsetActive = change.offsetUm != 0.0)),
                    )
                }
            }
        }
        if (switches.automaticProximityWarning) {
            readout.applyWorkspace(null) { workspace -> workspace.copy(proximity = proximityAt(guide)) }
        }
    }

    /** The point [id] becomes the selected item: the automatic proximity warning sets a point target. */
    private fun onPointSelected(frame: ReadoutFrame, id: String) {
        if (!frame.workspace.graphical.automaticProximityWarning) return
        val plane = PlaneAxes.planeFor(frame.profile.machineType.isLathe, frame.workspace.plane)
        readout.applyWorkspace(null) { workspace -> workspace.copy(proximity = AutoPointActions.proximityTarget(plane, id)) }
    }

    /** Clears a point target whose point is not the active sub-datum. Other targets do not change. */
    private fun clearPointTarget() {
        readout.applyWorkspace(null) { workspace ->
            val pointId = workspace.proximity?.pointId
            if (pointId != null && pointId != workspace.activeSubDatumId) workspace.copy(proximity = null) else workspace
        }
    }

    private fun controlsOf(frame: ReadoutFrame, current: Drawing?): GraphicalControlsUiState {
        val type = frame.profile.machineType
        val plane = PlaneAxes.planeFor(type.isLathe, frame.workspace.plane)
        val switches = frame.workspace.graphical
        val shown = buildList {
            add(GraphicalSwitch.SnapToPoint)
            add(GraphicalSwitch.SnapToGuide)
            if (!type.isLathe && plane == Plane.XY) add(GraphicalSwitch.AutomaticToolOffset)
            add(GraphicalSwitch.AutomaticProximityWarning)
            if (!type.isLathe) {
                add(GraphicalSwitch.AutomaticZero)
                add(GraphicalSwitch.AutomaticSubDatum)
            }
        }
        val displayFrame = GraphicalSceneBuilder.displayFrameOf(frame, plane)
        return GraphicalControlsUiState(
            machineType = type,
            plane = plane,
            planes = if (type.isLathe) listOf(Plane.ZX) else listOf(Plane.XY, Plane.XZ, Plane.YZ),
            switches = shown.map { switch -> switch to isOn(switches, switch) },
            display = frame.profile.graphicalDisplay,
            measurement = frame.measurementActive,
            workspaceName = frame.workspace.name,
            hasDrawing = current != null && current.shapes.isNotEmpty(),
            drawingLayers = current?.layers.orEmpty(),
            referenceAlpha = frame.workspace.referenceImage?.takeIf { image -> image.fileName.isNotEmpty() }?.alpha,
            partRotationDegrees = displayFrame.partRotation?.let { rotation -> -rotation.phiArcSec / 3600.0 },
            alignmentDegrees = displayFrame.axisAlignment?.let { alignment -> alignment.angleArcSec / 3600.0 },
            threadArmed = frame.workspace.armedThreadId != null,
            edgeFinderVisible = !type.isLathe && frame.profile.functionButtons.edgeFinder,
        )
    }

    companion object {
        /** The name of the presenter in the presenters of the tab. */
        const val NAME: String = "graphics-view"

        /** The time of the undo snackbar. */
        const val UNDO_MS: Long = 10_000L

        /** Gives [switches] with [switch] set to [on]. */
        fun switchesWith(switches: GraphicalSwitches, switch: GraphicalSwitch, on: Boolean): GraphicalSwitches = when (switch) {
            GraphicalSwitch.SnapToPoint -> switches.copy(snapToPoint = on)
            GraphicalSwitch.SnapToGuide -> switches.copy(snapToGuide = on)
            GraphicalSwitch.AutomaticToolOffset -> switches.copy(automaticToolOffset = on)
            GraphicalSwitch.AutomaticProximityWarning -> switches.copy(automaticProximityWarning = on)
            GraphicalSwitch.AutomaticZero -> switches.copy(automaticZero = on)
            GraphicalSwitch.AutomaticSubDatum -> switches.copy(automaticSubDatum = on)
        }

        /** Gives the value of [switch] in [switches]. */
        fun isOn(switches: GraphicalSwitches, switch: GraphicalSwitch): Boolean = when (switch) {
            GraphicalSwitch.SnapToPoint -> switches.snapToPoint
            GraphicalSwitch.SnapToGuide -> switches.snapToGuide
            GraphicalSwitch.AutomaticToolOffset -> switches.automaticToolOffset
            GraphicalSwitch.AutomaticProximityWarning -> switches.automaticProximityWarning
            GraphicalSwitch.AutomaticZero -> switches.automaticZero
            GraphicalSwitch.AutomaticSubDatum -> switches.automaticSubDatum
        }

        /**
         * The proximity target of the automatic warning at [guide]. A guide is not a
         * sub-datum in SuperDRO, so the incremental value of the axis is not the distance to the
         * guide. The target is thus the guide value as an absolute position: the warning gives the
         * same tones as a target 0 in the incremental mode.
         */
        fun proximityAt(guide: Guide): ProximityTarget = AutoGuideActions.proximityTarget(guide).copy(targetUm = guide.valueUm, absolute = true)
    }
}
