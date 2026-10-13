package org.superdro.core.ui.functions.workspace

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.superdro.core.functions.graphics.PlaneAxes
import org.superdro.core.functions.graphics.Vec2
import org.superdro.core.functions.transform.AlignAToBResult
import org.superdro.core.functions.transform.AlignSpindleResult
import org.superdro.core.functions.transform.GuidePolicy
import org.superdro.core.functions.transform.PairCommands
import org.superdro.core.functions.transform.PairGeometry
import org.superdro.core.functions.transform.RotateTransform
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.SubDatum
import org.superdro.core.model.Workspace
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.resources.Res
import org.superdro.core.resources.ws_length
import org.superdro.core.resources.ws_pair_deleted
import org.superdro.core.resources.ws_pair_missing
import org.superdro.core.resources.ws_pair_move_noop
import org.superdro.core.resources.ws_pair_no_direction
import org.superdro.core.resources.ws_pair_spindle_noop
import org.superdro.core.resources.ws_pair_undo_move
import org.superdro.core.resources.ws_transform_failed
import org.superdro.core.resources.ws_undo_rotate
import org.superdro.core.ui.functions.common.FieldMath
import org.superdro.core.ui.functions.common.FunctionServices
import org.superdro.core.ui.functions.common.LengthTexts
import org.superdro.core.ui.functions.common.UndoOffers
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.readout.TabReadout
import org.superdro.core.ui.text.UiText

/** The question of the pair sheet that shows. */
sealed interface PairQuestion {
    /** "Align A to B": the shift of the two plane axes. */
    data class Move(val shifts: List<UiText>, val shiftU: Double, val shiftV: Double) : PairQuestion

    /** "Delete <B>?" after "Align A to B": B marks nothing now. */
    data object DeleteB : PairQuestion

    /** "Align B to Spindle (Pivot A)": the angle, where B lands, and the caution of a short baseline. */
    data class Rotate(val thetaDegrees: String, val land: UiText, val uncertainDegrees: String?, val transform: RotateTransform) : PairQuestion

    /** "Guides Will Be Deleted" before the rotation. */
    data class Guides(val count: Int, val transform: RotateTransform) : PairQuestion
}

/**
 * The state of the pair sheet.
 *
 * @property spindleVisible "Align B to Spindle" shows: not a lathe, and the plane `XY`.
 * @property spindleEnabled The distance is 1 µm or more.
 * @property tooClose The tolerance angle is more than 0.1°: the points are close together.
 */
@Immutable
data class PairUiState(
    val loaded: Boolean = false,
    val nameA: String = "",
    val nameB: String = "",
    val distance: UiText? = null,
    val angleDegrees: String = "",
    val toleranceDegrees: String? = null,
    val tooClose: Boolean = false,
    val spindleVisible: Boolean = false,
    val spindleEnabled: Boolean = false,
    val question: PairQuestion? = null,
    val error: UiText? = null,
    val busy: Boolean = false,
    val done: Boolean = false,
)

/**
 * The presenter of the sub-datum pair commands. A is the selected
 * point, B is the point of the long press. (u, v) are the two axes of the plane of the workspace.
 * All positions are in the workspace frame.
 *
 * - "Align A to B": `Abs_c_new = Abs_c − d_c` on the two plane axes, with the undo. The
 *   stored rows do not change, so A now marks the place where B was. Then the sheet asks to delete B.
 * - "Align B to Spindle (Pivot A)": the rotation about A with the snap to 90° in the tolerance,
 *   through the transform pipeline with the undo of the last transform.
 */
class PairPresenter(
    private val pointA: String,
    private val pointB: String,
    private val services: FunctionServices,
    private val readout: TabReadout,
    private val undo: UndoOffers,
    private val messages: (UiText) -> Unit,
) : ScopedPresenter(services.context) {

    private val runner = TransformRunner(services, undo)
    private val mutable = MutableStateFlow(PairUiState())

    /** The UI state of the sheet. */
    val uiState: StateFlow<PairUiState> = mutable.asStateFlow()

    init {
        readout.frame.value?.let(::start)
        scope.launch { readout.frame.collect { next -> if (next != null && !mutable.value.loaded) start(next) } }
    }

    private fun axes(workspace: Workspace): Pair<AxisId, AxisId> = PlaneAxes.horizontal(workspace.plane) to PlaneAxes.vertical(workspace.plane)

    private fun points(frame: ReadoutFrame): Pair<SubDatum, SubDatum>? {
        val a = frame.workspace.subDatums.firstOrNull { it.id == pointA } ?: return null
        val b = frame.workspace.subDatums.firstOrNull { it.id == pointB } ?: return null
        return a to b
    }

    private fun vec(point: SubDatum, axes: Pair<AxisId, AxisId>): Vec2? {
        val u = point.valueOf(axes.first) ?: return null
        val v = point.valueOf(axes.second) ?: return null
        return Vec2(u, v)
    }

    private fun start(frame: ReadoutFrame) {
        val pair = points(frame)
        val axes = axes(frame.workspace)
        val a = pair?.first?.let { vec(it, axes) }
        val b = pair?.second?.let { vec(it, axes) }
        if (pair == null || a == null || b == null) {
            mutable.update { it.copy(loaded = true, error = UiText.of(Res.string.ws_pair_missing)) }
            return
        }
        val geometry: PairGeometry = PairCommands.geometry(a, b)
        mutable.update {
            it.copy(
                loaded = true,
                nameA = pair.first.name,
                nameB = pair.second.name,
                distance = UiText.of(
                    Res.string.ws_length,
                    LengthTexts.format(geometry.distanceUm, frame.metric(axes.first), frame.profile.display.readoutFormat),
                    WorkspaceTexts.unit(frame, axes.first),
                ),
                angleDegrees = degreesText(geometry.lineAngleDegrees),
                toleranceDegrees = geometry.toleranceDegrees.takeIf { value -> value.isFinite() }?.let { value -> degreesText(value, 3) },
                tooClose = geometry.shortBaseline,
                spindleVisible = PairCommands.alignSpindleVisible(frame.profile.machineType, frame.workspace.plane),
                spindleEnabled = !geometry.degenerate,
            )
        }
    }

    /** "Align A to B": the confirmation with the shift of the plane axes, or the no-op message. */
    fun onMove() {
        val frame = readout.frame.value ?: return
        workspaceOperationsBlocked(frame.workspace)?.let { blocked -> return mutable.update { it.copy(error = blocked) } }
        val axes = axes(frame.workspace)
        val (pa, pb) = points(frame) ?: return
        val a = vec(pa, axes) ?: return
        val b = vec(pb, axes) ?: return
        val absolute = Vec2(frame.unrotatedAbsolute(axes.first).orZero(), frame.unrotatedAbsolute(axes.second).orZero())
        when (val result = PairCommands.alignAToB(a, b, absolute)) {
            AlignAToBResult.NoOp -> messages(UiText.of(Res.string.ws_pair_move_noop))
            is AlignAToBResult.Apply -> mutable.update {
                it.copy(
                    question = PairQuestion.Move(
                        shifts = listOf(WorkspaceTexts.shiftLine(frame, axes.first, result.shiftU), WorkspaceTexts.shiftLine(frame, axes.second, result.shiftV)),
                        shiftU = result.shiftU,
                        shiftV = result.shiftV,
                    ),
                )
            }
        }
    }

    /** "Yes" of "Align A to B": the absolute offsets of the plane axes change with undo. Then "Delete <B>?". */
    fun onMoveConfirm() {
        val question = mutable.value.question as? PairQuestion.Move ?: return
        val frame = readout.frame.value ?: return
        val axes = axes(frame.workspace)
        readout.applyWorkspace(UiText.of(Res.string.ws_pair_undo_move, mutable.value.nameA, mutable.value.nameB)) { workspace ->
            workspace.copy(axisStates = workspace.axisStates.shift(axes.first, question.shiftU).shift(axes.second, question.shiftV))
        }
        mutable.update { it.copy(question = PairQuestion.DeleteB) }
    }

    /** The answer to "Delete <B>?": a delete of B with undo for 10 s, or no delete. Then the sheet closes. */
    fun onDeleteB(delete: Boolean) {
        val workspaceId = readout.frame.value?.workspace?.id
        if (!delete || workspaceId == null) {
            mutable.update { it.copy(question = null, done = true) }
            return
        }
        mutable.update { it.copy(question = null, busy = true) }
        // The sheet closes after the delete: a close of the sheet cancels the scope of the presenter.
        scope.launch {
            val deleted = services.machines.deleteSubDatums(workspaceId, listOf(pointB))
            if (deleted.isNotEmpty()) {
                undo.offer(UiText.of(Res.string.ws_pair_deleted, mutable.value.nameB)) { services.machines.insertSubDatums(workspaceId, deleted) }
            }
            mutable.update { it.copy(busy = false, done = true) }
        }
    }

    /** "Align B to Spindle (Pivot A)": the confirmation with the angle, or a message. */
    fun onSpindle() {
        readout.positionUnavailable()?.let { refusal ->
            messages(refusal)
            return
        }
        val frame = readout.frame.value ?: return
        workspaceOperationsBlocked(frame.workspace)?.let { blocked -> return mutable.update { it.copy(error = blocked) } }
        val axes = axes(frame.workspace)
        val (pa, pb) = points(frame) ?: return
        val a = vec(pa, axes) ?: return
        val b = vec(pb, axes) ?: return
        val spindle = Vec2(frame.unrotatedAbsolute(axes.first), frame.unrotatedAbsolute(axes.second))
        if (!spindle.x.isFinite() || !spindle.y.isFinite()) {
            messages(UiText.of(Res.string.ws_pair_no_direction))
            return
        }
        when (val result = PairCommands.alignBToSpindle(a, b, spindle)) {
            AlignSpindleResult.NoDirection -> messages(UiText.of(Res.string.ws_pair_no_direction))
            AlignSpindleResult.NoOp -> messages(UiText.of(Res.string.ws_pair_spindle_noop))
            is AlignSpindleResult.Rotate -> mutable.update {
                it.copy(
                    question = PairQuestion.Rotate(
                        thetaDegrees = degreesText(result.thetaDegrees),
                        land = UiText.of(
                            Res.string.ws_length,
                            LengthTexts.format(result.landUm, frame.metric(axes.first), frame.profile.display.readoutFormat),
                            WorkspaceTexts.unit(frame, axes.first),
                        ),
                        uncertainDegrees = if (result.uncertain) degreesText(result.toleranceDegrees, 3) else null,
                        transform = result.transform,
                    ),
                )
            }
        }
    }

    /** "Yes" of the rotation: the guide question when guides break, else the transform. */
    fun onRotateConfirm() {
        val question = mutable.value.question as? PairQuestion.Rotate ?: return
        mutable.update { it.copy(question = null) }
        val workspaceId = readout.frame.value?.workspace?.id ?: return
        scope.launch {
            val broken = runner.brokenGuides(workspaceId, question.transform)
            if (broken > 0) {
                mutable.update { it.copy(question = PairQuestion.Guides(broken, question.transform)) }
            } else {
                rotate(question.transform, GuidePolicy.Delete)
            }
        }
    }

    /** The answer to "Guides Will Be Deleted": [policy], or null for "Cancel". */
    fun onGuideAnswer(policy: GuidePolicy?) {
        val question = mutable.value.question as? PairQuestion.Guides ?: return
        mutable.update { it.copy(question = null) }
        if (policy != null) scope.launch { rotate(question.transform, policy) }
    }

    /** Closes the question that shows. */
    fun onQuestionCancel() = mutable.update { it.copy(question = null) }

    private suspend fun rotate(transform: RotateTransform, policy: GuidePolicy) {
        val workspaceId = readout.frame.value?.workspace?.id ?: return
        mutable.update { it.copy(busy = true) }
        val result = runner.run(workspaceId, transform, policy, "Rotate ${transform.label}", UiText.of(Res.string.ws_undo_rotate, transform.label))
        mutable.update { state ->
            when (result) {
                TransformRun.Applied, TransformRun.NoChange -> state.copy(busy = false, done = true)
                TransformRun.Busy -> state.copy(busy = false)
                TransformRun.Failed -> state.copy(busy = false, error = UiText.of(Res.string.ws_transform_failed))
            }
        }
    }

    private fun degreesText(value: Double, decimals: Int = 2): String =
        FieldMath.format(value, decimals) + "°"
}

/** Adds [shift] to the absolute offset of [axis] and sets the absolute mode of the axis. */
internal fun Map<AxisId, AxisState>.shift(axis: AxisId, shift: Double): Map<AxisId, AxisState> {
    val state = this[axis] ?: AxisState()
    return this + (axis to state.copy(absoluteOffset = state.absoluteOffset + shift, relative = false))
}

private fun Double.orZero(): Double = if (isFinite()) this else 0.0
