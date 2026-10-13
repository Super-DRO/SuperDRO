package org.superdro.core.ui.graphics.drawing

import androidx.compose.runtime.Immutable
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.superdro.core.drawing.DrawingConversion
import org.superdro.core.functions.drawing.DrawingPlacement
import org.superdro.core.functions.drawing.ShapeGeometry
import org.superdro.core.functions.drawing.SnapGroup
import org.superdro.core.functions.drawing.SnapKind
import org.superdro.core.functions.drawing.SnapPoint
import org.superdro.core.functions.drawing.SnapPoints
import org.superdro.core.functions.drawing.UnitCheck
import org.superdro.core.functions.drawing.UnitFactor
import org.superdro.core.functions.graphics.AngleMath
import org.superdro.core.functions.graphics.PlaneAxes
import org.superdro.core.functions.graphics.Vec2
import org.superdro.core.model.AxisId
import org.superdro.core.model.Drawing
import org.superdro.core.model.DrawingShape
import org.superdro.core.model.DrawingSource
import org.superdro.core.model.Plane
import org.superdro.core.model.ReadoutFormat
import org.superdro.core.readout.NumberPattern
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.resources.Res
import org.superdro.core.resources.drawingui_apply_failed
import org.superdro.core.resources.drawingui_applied
import org.superdro.core.resources.drawingui_group_arcs
import org.superdro.core.resources.drawingui_group_corners
import org.superdro.core.resources.drawingui_group_holes
import org.superdro.core.resources.drawingui_group_points
import org.superdro.core.resources.drawingui_mismatch_closer
import org.superdro.core.resources.drawingui_mismatch_further
import org.superdro.core.resources.drawingui_needs_pick
import org.superdro.core.resources.drawingui_needs_values
import org.superdro.core.resources.drawingui_reset_done
import org.superdro.core.resources.drawingui_status_count
import org.superdro.core.resources.drawingui_status_extents
import org.superdro.core.resources.drawingui_status_picked
import org.superdro.core.resources.drawingui_status_rotated
import org.superdro.core.resources.drawingui_status_warning
import org.superdro.core.resources.drawingui_sub_datums_failed
import org.superdro.core.resources.drawingui_unit_inch_as_mm
import org.superdro.core.resources.drawingui_unit_mm_as_inch
import org.superdro.core.resources.drawingui_unit_mm_as_nm
import org.superdro.core.resources.drawingui_unit_nm_as_mm
import org.superdro.core.resources.drawingui_unit_nm_as_um
import org.superdro.core.resources.drawingui_unit_um_as_nm
import org.superdro.core.store.StoreResult
import org.superdro.core.ui.graphics.GraphicsDependencies
import org.superdro.core.ui.graphics.common.LengthInput
import org.superdro.core.ui.graphics.common.LengthTexts
import org.superdro.core.ui.graphics.common.evaluateLength
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.readout.TabReadout
import org.superdro.core.ui.text.UiText

/**
 * One reference card of "Set Up Drawing".
 *
 * @property xText The value of the horizontal plane axis (X, or Z on a lathe) in the display unit.
 * @property yText The value of the vertical plane axis (Y, or X on a lathe) in the display unit.
 * @property set True when the card is a reference of the placement.
 * @property hasPick True when a picked drawing point belongs to the card.
 */
@Immutable
data class ReferenceCardUiState(
    val index: Int,
    val xText: String = "",
    val yText: String = "",
    val set: Boolean = false,
    val hasPick: Boolean = false,
    val error: UiText? = null,
)

/** One snap point group with its two controls. */
@Immutable
data class SnapGroupUiState(val kind: SnapKind, val label: UiText, val shown: Boolean, val create: Boolean)

/** One snap point mark of the preview at the model position ([h], [v]) µm. */
@Immutable
data class SnapMark(val h: Double, val v: Double, val picked: Boolean)

/**
 * The state of "Set Up Drawing".
 *
 * @property horizontalAxis The axis on the horizontal of the plane (X, or Z on a lathe).
 * @property horizontalValue The live absolute value of [horizontalAxis] in the display unit.
 * @property previewShapes The shapes with the placement, in nm.
 * @property banner The unit message of the unit test, or null.
 * @property status The status line.
 * @property message A message of an action, for example after "Reset", or null.
 * @property done True when the screen closes.
 */
@Immutable
data class DrawingSetupUiState(
    val fileName: String = "",
    val horizontalAxis: AxisId = AxisId.X,
    val verticalAxis: AxisId = AxisId.Y,
    val metric: Boolean = true,
    val horizontalValue: String = "",
    val verticalValue: String = "",
    val previewShapes: List<DrawingShape> = emptyList(),
    val marks: List<SnapMark> = emptyList(),
    val view: PreviewViewport = PreviewViewport(),
    val cards: List<ReferenceCardUiState> = listOf(ReferenceCardUiState(0), ReferenceCardUiState(1)),
    val groups: List<SnapGroupUiState> = emptyList(),
    val banner: UiText? = null,
    val status: UiText? = null,
    val message: UiText? = null,
    val busy: Boolean = false,
    val done: Boolean = false,
)

/**
 * The presenter of "Set Up Drawing". The math is in [DrawingPlacement] and
 * [SnapPoints].
 *
 * Rules:
 * - A tap on the preview picks the nearest snap point of the shown groups within
 *   [SnapPoints.PICK_TOLERANCE_DP]. The pick fills the next free card with the predicted
 *   machine position. No point in the tolerance clears the pick.
 * - "Set" makes the card a reference: the machine point is a view position with the vertical
 *   sign of the plane ([PlaneAxes.toView]).
 * - A mirror or rotate step keeps the set references on their machine points.
 * - "Apply" stores the placed drawing in one transaction and adds the sub-datums of the groups
 *   with "Create as sub-datums". Then the screen closes.
 *
 * @param controller The drawing import of the tab: "Apply" and "Cancel" clear its pending drawing.
 */
class DrawingSetupPresenter(
    private val pending: PendingDrawing,
    private val readout: TabReadout,
    private val deps: GraphicsDependencies,
    private val controller: DrawingImportController? = null,
) : ScopedPresenter(deps.context) {

    private data class Card(val xText: String = "", val yText: String = "", val drawingPoint: Vec2? = null, val error: UiText? = null)

    private data class Local(
        val placement: DrawingPlacement,
        val preview: List<DrawingShape>,
        val groups: List<SnapGroup>,
        val cards: List<Card> = listOf(Card(), Card()),
        val picked: SnapPoint? = null,
        val message: UiText? = null,
        val busy: Boolean = false,
        val done: Boolean = false,
        val view: PreviewViewport = PreviewViewport(),
    )

    private val fileShapes: List<DrawingShape> = DrawingConversion.toShapes(pending.document)

    private val local = MutableStateFlow(initial(DrawingPlacement.load(fileShapes)))

    /** The state of the screen. */
    val uiState: StateFlow<DrawingSetupUiState> = combine(local, readout.frame) { state, frame -> uiStateOf(state, frame) }
        .stateIn(scope, SharingStarted.Eagerly, uiStateOf(local.value, readout.frame.value))

    // ---------------------------------------------------------------------------------------
    // The preview

    /** The preview has the size [width] × [height] px. The first size fits the drawing. */
    fun onPreviewSize(width: Double, height: Double) {
        local.update { state ->
            val resized = state.view.resize(width, height)
            state.copy(view = if (resized.fitted) resized else fitted(resized, state.preview))
        }
    }

    /** A drag of the preview by ([dx], [dy]) px. */
    fun onPreviewPan(dx: Double, dy: Double) = local.update { state -> state.copy(view = state.view.pan(dx, dy)) }

    /** A pinch of the preview by [factor] about ([focusX], [focusY]). */
    fun onPreviewZoom(factor: Double, focusX: Double, focusY: Double) =
        local.update { state -> state.copy(view = state.view.zoom(factor, focusX, focusY)) }

    /** A double tap: fit the drawing and the workspace origin. */
    fun onPreviewFit() = local.update { state -> state.copy(view = fitted(state.view, state.preview)) }

    /**
     * A tap on the preview at ([x], [y]) px. [density] is the pixels for each dp. The pick fills
     * the next free card with the predicted machine position.
     */
    fun onPreviewTap(x: Double, y: Double, density: Double) {
        local.update { state ->
            val model = state.view.toModel(x, y)
            val placement = state.placement
            val drawingNm = (model.rotated(-placement.rotationRad) - placement.offsetUm) * NM_PER_UM
            val toleranceNm = SnapPoints.PICK_TOLERANCE_DP * density / state.view.scale * NM_PER_UM
            val pick = SnapPoints.pick(state.groups, drawingNm.x, drawingNm.y, toleranceNm)
                ?: return@update state.copy(picked = null)
            val free = placement.references.indexOfFirst { reference -> reference == null }
            if (free < 0) return@update state.copy(picked = pick)
            val point = Vec2(pick.x.toDouble(), pick.y.toDouble())
            val (h, v) = axisValues(placement.predictMachine(point))
            val cards = state.cards.toMutableList()
            cards[free] = Card(format(h), format(v), point)
            state.copy(picked = pick, cards = cards, message = null)
        }
    }

    // ---------------------------------------------------------------------------------------
    // The steps

    /** Rotate 90° counterclockwise. */
    fun onRotateLeft() = step { placement -> placement.rotateLeft() }

    /** Rotate 90° clockwise. */
    fun onRotateRight() = step { placement -> placement.rotateRight() }

    /** Mirror left and right (x → −x). */
    fun onMirrorHorizontal() = step { placement -> placement.mirrorHorizontal() }

    /** Mirror top and bottom (y → −y). */
    fun onMirrorVertical() = step { placement -> placement.mirrorVertical() }

    private fun step(change: (DrawingPlacement) -> DrawingPlacement) {
        local.update { state ->
            if (state.busy) return@update state
            val placement = change(state.placement)
            if (placement == state.placement) return@update state
            // The picks of the free cards belong to the old shapes: they go.
            val cards = state.cards.mapIndexed { index, card ->
                if (placement.references[index] == null) card.copy(drawingPoint = null) else card
            }
            state.copy(
                placement = placement,
                preview = placement.previewShapes(),
                groups = keepFlags(SnapPoints.extract(placement.shapes), state.groups),
                cards = cards,
                picked = null,
                message = null,
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // The cards

    /** The text of the horizontal value of the card [index]. */
    fun onCardXText(index: Int, text: String) = updateCard(index) { card -> card.copy(xText = text, error = null) }

    /** The text of the vertical value of the card [index]. */
    fun onCardYText(index: Int, text: String) = updateCard(index) { card -> card.copy(yText = text, error = null) }

    /** The sync button of the card [index]: the current absolute positions of the two plane axes. */
    fun onCardSync(index: Int) {
        val frame = readout.frame.value ?: return
        val (hAxis, vAxis) = axes(frame)
        val h = frame.reading(hAxis).absolute
        val v = frame.reading(vAxis).absolute
        if (!h.isFinite() || !v.isFinite()) return
        updateCard(index) { card -> card.copy(xText = format(h), yText = format(v), error = null) }
    }

    /** "Set" makes the card [index] a reference. "Clear" on a set card removes the reference. */
    fun onCardSet(index: Int) {
        local.update { state ->
            if (state.busy) return@update state
            val placement = state.placement
            if (placement.references[index] != null) {
                val cards = state.cards.toMutableList()
                cards[index] = Card()
                return@update state.copy(placement = placement.clearReference(index), cards = cards)
            }
            val card = state.cards[index]
            val point = card.drawingPoint
                ?: return@update state.withCardError(index, UiText.of(Res.string.drawingui_needs_pick, index + 1))
            val metric = metric()
            val h = evaluateLength(card.xText, metric)
            val v = evaluateLength(card.yText, metric)
            if (h is LengthInput.Invalid) return@update state.withCardError(index, h.error)
            if (v is LengthInput.Invalid) return@update state.withCardError(index, v.error)
            if (h !is LengthInput.Value || v !is LengthInput.Value) {
                return@update state.withCardError(index, UiText.of(Res.string.drawingui_needs_values, index + 1))
            }
            val plane = plane()
            val view = PlaneAxes.toView(plane, h.um, v.um)
            val next = placement.setReference(index, point, Vec2(view.h, view.v))
            // The pick is now the reference of the card.
            state.copy(placement = next, preview = next.previewShapes(), cards = state.cards, picked = null, message = null)
        }
    }

    // ---------------------------------------------------------------------------------------
    // The groups

    /** "Show on drawing" of the group [index]. */
    fun onGroupShown(index: Int, shown: Boolean) = updateGroup(index) { group -> group.copy(shown = shown) }

    /** "Create as sub-datums" of the group [index]. */
    fun onGroupCreate(index: Int, create: Boolean) = updateGroup(index) { group -> group.copy(createSubDatums = create) }

    // ---------------------------------------------------------------------------------------
    // The buttons

    /** "Reset": the drawing is back to the file as loaded, with the default placement. */
    fun onReset() {
        local.update { state ->
            if (state.busy) return@update state
            initial(state.placement.reset()).copy(view = fitted(state.view, state.placement.reset().previewShapes()), message = UiText.of(Res.string.drawingui_reset_done))
        }
    }

    /** "Cancel": the screen closes and the pending drawing goes. */
    fun onCancel() {
        controller?.clearPending()
        local.update { state -> state.copy(done = true) }
    }

    /**
     * "Apply": the unit factor, the placement, the snap points of the
     * placed shapes, the drawing in one transaction, and the sub-datums of the groups with "Create
     * as sub-datums". The point names stay.
     */
    fun onApply() {
        val state = local.value
        if (state.busy || state.done) return
        val placed = state.placement.apply() ?: return
        val frame = readout.frame.value ?: return
        val workspaceId = frame.workspace.id
        val plane = plane()
        local.update { current -> current.copy(busy = true, message = null) }
        scope.launch {
            val drawing = Drawing(
                workspaceId = workspaceId,
                layers = DrawingConversion.toLayers(pending.document),
                shapes = placed.shapes,
                source = DrawingSource(pending.fileName, pending.format),
                createdOn = deps.wallClock.now(),
            )
            if (deps.drawings.replaceDrawing(drawing) !is StoreResult.Ok) {
                local.update { current -> current.copy(busy = false, message = UiText.of(Res.string.drawingui_apply_failed)) }
                return@launch
            }
            val groups = keepFlags(placed.snapGroups, state.groups)
            val existingTags = deps.machines.loadWorkspace(workspaceId)?.subDatums?.flatMap { point -> point.tags }.orEmpty()
            val subDatums = SnapPoints.toSubDatums(groups, plane, existingTags)
            val added = subDatums.isEmpty() || deps.machines.addSubDatums(workspaceId, subDatums) is StoreResult.Ok
            controller?.clearPending()
            controller?.showMessage(UiText.of(if (added) Res.string.drawingui_applied else Res.string.drawingui_sub_datums_failed))
            local.update { current -> current.copy(busy = false, done = true) }
        }
    }

    /** The screen showed the message. */
    fun onMessageShown() = local.update { state -> state.copy(message = null) }

    // ---------------------------------------------------------------------------------------
    // Private parts

    private fun initial(placement: DrawingPlacement): Local =
        Local(placement = placement, preview = placement.previewShapes(), groups = SnapPoints.extract(placement.shapes))

    private fun Local.withCardError(index: Int, error: UiText): Local {
        val list = cards.toMutableList()
        list[index] = list[index].copy(error = error)
        return copy(cards = list)
    }

    private fun updateCard(index: Int, change: (Card) -> Card) {
        local.update { state ->
            val cards = state.cards.toMutableList()
            cards[index] = change(cards[index])
            state.copy(cards = cards)
        }
    }

    private fun updateGroup(index: Int, change: (SnapGroup) -> SnapGroup) {
        local.update { state ->
            if (index !in state.groups.indices) return@update state
            val groups = state.groups.toMutableList()
            groups[index] = change(groups[index])
            val picked = state.picked?.takeIf { pick -> groups.any { group -> group.shown && pick in group.points } }
            state.copy(groups = groups, picked = picked)
        }
    }

    private fun fitted(view: PreviewViewport, shapes: List<DrawingShape>): PreviewViewport {
        val box = ShapeGeometry.extents(shapes)
        if (box == null) return view.fit(-MIN_FIT_UM, -MIN_FIT_UM, MIN_FIT_UM, MIN_FIT_UM)
        // The box holds the workspace origin, so the user sees where the drawing goes.
        return view.fit(
            minOf(0.0, box.minX / NM_PER_UM),
            minOf(0.0, box.minY / NM_PER_UM),
            maxOf(0.0, box.maxX / NM_PER_UM),
            maxOf(0.0, box.maxY / NM_PER_UM),
        )
    }

    private fun plane(frame: ReadoutFrame? = readout.frame.value): Plane {
        val lathe = frame?.profile?.machineType?.isLathe ?: false
        return PlaneAxes.planeFor(lathe, frame?.workspace?.plane ?: Plane.XY)
    }

    private fun axes(frame: ReadoutFrame?): Pair<AxisId, AxisId> {
        val plane = plane(frame)
        return PlaneAxes.horizontal(plane) to PlaneAxes.vertical(plane)
    }

    private fun metric(frame: ReadoutFrame? = readout.frame.value): Boolean = frame?.metric(axes(frame).first) ?: true

    private fun readoutFormat(frame: ReadoutFrame? = readout.frame.value): ReadoutFormat =
        frame?.profile?.display?.readoutFormat ?: ReadoutFormat.Format3

    /** Gives the axis values (horizontal, vertical) of the view position [view] µm. */
    private fun axisValues(view: Vec2): Pair<Double, Double> {
        val plane = plane()
        return view.x to PlaneAxes.verticalSign(plane) * view.y
    }

    private fun format(um: Double): String = LengthTexts.format(um, metric(), readoutFormat())

    private fun uiStateOf(state: Local, frame: ReadoutFrame?): DrawingSetupUiState {
        val (hAxis, vAxis) = axes(frame)
        val metric = metric(frame)
        val format = readoutFormat(frame)
        fun live(axis: AxisId): String {
            val reading = frame?.reading(axis) ?: return ""
            return LengthTexts.format(reading.absolute, metric, format)
        }
        val placement = state.placement
        val marks = state.groups.filter { group -> group.shown }.flatMap { group ->
            group.points.map { point ->
                val machine = placement.predictMachine(Vec2(point.x.toDouble(), point.y.toDouble()))
                SnapMark(machine.x, machine.y, picked = point == state.picked)
            }
        }
        return DrawingSetupUiState(
            fileName = pending.fileName,
            horizontalAxis = hAxis,
            verticalAxis = vAxis,
            metric = metric,
            horizontalValue = live(hAxis),
            verticalValue = live(vAxis),
            previewShapes = state.preview,
            marks = marks,
            view = state.view,
            cards = state.cards.mapIndexed { index, card ->
                ReferenceCardUiState(
                    index = index,
                    xText = card.xText,
                    yText = card.yText,
                    set = placement.references[index] != null,
                    hasPick = card.drawingPoint != null || placement.references[index] != null,
                    error = card.error,
                )
            },
            groups = state.groups.map { group -> SnapGroupUiState(group.kind, groupLabel(group), group.shown, group.createSubDatums) },
            banner = bannerOf(placement.unitCheck()),
            status = statusOf(state, frame),
            message = state.message,
            busy = state.busy,
            done = state.done,
        )
    }

    private fun statusOf(state: Local, frame: ReadoutFrame?): UiText {
        val shapes = state.preview
        val box = ShapeGeometry.extents(shapes)
        var status = if (box == null) {
            UiText.of(Res.string.drawingui_status_count, shapes.size)
        } else {
            UiText.of(
                Res.string.drawingui_status_extents,
                shapes.size,
                ONE_DECIMAL.format(box.minX / NM_PER_MM),
                ONE_DECIMAL.format(box.maxX / NM_PER_MM),
                ONE_DECIMAL.format(box.minY / NM_PER_MM),
                ONE_DECIMAL.format(box.maxY / NM_PER_MM),
            )
        }
        val rotation = state.placement.rotationRad
        if (rotation != 0.0) {
            status = UiText.of(Res.string.drawingui_status_rotated, status, ONE_DECIMAL.format(AngleMath.radToDegrees(rotation)))
        }
        pending.warnings.firstOrNull()?.let { warning -> status = UiText.of(Res.string.drawingui_status_warning, status, warning.message) }
        state.picked?.let { pick ->
            val machine = state.placement.predictMachine(Vec2(pick.x.toDouble(), pick.y.toDouble()))
            val sign = PlaneAxes.verticalSign(plane(frame))
            status = UiText.of(
                Res.string.drawingui_status_picked,
                status,
                TWO_DECIMALS.format(machine.x / UM_PER_MM),
                TWO_DECIMALS.format(sign * machine.y / UM_PER_MM),
            )
        }
        return status
    }

    companion object {
        private const val NM_PER_UM: Double = 1000.0
        private const val NM_PER_MM: Double = 1_000_000.0
        private const val UM_PER_MM: Double = 1000.0
        private const val MIN_FIT_UM: Double = 10_000.0
        private val ONE_DECIMAL = NumberPattern("0.0")
        private val TWO_DECIMALS = NumberPattern("0.00")

        /** Gives the label of [group]: "Holes - %1$smm (%2$d)", "Arc centers", "Corners", "Named points". */
        fun groupLabel(group: SnapGroup): UiText = when (group.kind) {
            SnapKind.CircleCenter -> UiText.of(Res.string.drawingui_group_holes, ONE_DECIMAL.format((group.diameterUm ?: 0L) / UM_PER_MM), group.points.size)
            SnapKind.ArcCenter -> UiText.of(Res.string.drawingui_group_arcs)
            SnapKind.Corner -> UiText.of(Res.string.drawingui_group_corners)
            SnapKind.NamedPoint -> UiText.of(Res.string.drawingui_group_points)
        }

        /** Gives the unit banner of the unit test [check], or null. */
        fun bannerOf(check: UnitCheck): UiText? = when (check) {
            UnitCheck.Agrees -> null
            is UnitCheck.WrongUnit -> UiText.of(
                when (check.unit) {
                    UnitFactor.InchWrittenAsMm -> Res.string.drawingui_unit_inch_as_mm
                    UnitFactor.MmWrittenAsInch -> Res.string.drawingui_unit_mm_as_inch
                    UnitFactor.UmWrittenAsNm -> Res.string.drawingui_unit_um_as_nm
                    UnitFactor.NmWrittenAsUm -> Res.string.drawingui_unit_nm_as_um
                    UnitFactor.MmWrittenAsNm -> Res.string.drawingui_unit_mm_as_nm
                    UnitFactor.NmWrittenAsMm -> Res.string.drawingui_unit_nm_as_mm
                },
            )
            is UnitCheck.Mismatch -> if (check.differenceMm >= 0.0) {
                UiText.of(Res.string.drawingui_mismatch_further, TWO_DECIMALS.format(check.differenceMm))
            } else {
                UiText.of(Res.string.drawingui_mismatch_closer, TWO_DECIMALS.format(abs(check.differenceMm)))
            }
        }

        /**
         * Gives [groups] with the controls of [old]. A group gets the controls of the group of
         * [old] with the same kind and the same rank among the groups of that kind. A unit factor
         * changes the hole diameters, but not the order of the hole groups.
         */
        fun keepFlags(groups: List<SnapGroup>, old: List<SnapGroup>): List<SnapGroup> = groups.map { group ->
            val rank = groups.filter { other -> other.kind == group.kind }.indexOf(group)
            val match = old.filter { other -> other.kind == group.kind }.getOrNull(rank)
            if (match == null) group else group.copy(shown = match.shown, createSubDatums = match.createSubDatums)
        }
    }
}
