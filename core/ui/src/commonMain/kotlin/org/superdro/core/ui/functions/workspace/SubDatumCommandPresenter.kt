package org.superdro.core.ui.functions.workspace

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.superdro.core.functions.transform.GuidePolicy
import org.superdro.core.functions.transform.OriginCommands
import org.superdro.core.functions.transform.SpindleAlignResult
import org.superdro.core.model.AxisId
import org.superdro.core.model.SubDatum
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.resources.Res
import org.superdro.core.resources.ws_align_origin_failed
import org.superdro.core.resources.ws_align_origin_noop
import org.superdro.core.resources.ws_align_spindle_noop
import org.superdro.core.resources.ws_align_spindle_noop_lathe
import org.superdro.core.resources.ws_sub_datum_missing
import org.superdro.core.resources.ws_undo_align_origin
import org.superdro.core.resources.ws_undo_align_spindle
import org.superdro.core.resources.ws_undo_align_tool
import org.superdro.core.ui.functions.common.FunctionServices
import org.superdro.core.ui.functions.common.UndoOffers
import org.superdro.core.ui.navigation.SubDatumCommand
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.readout.TabReadout
import org.superdro.core.ui.text.UiText

/**
 * The state of the dialog of an origin command of a sub-datum.
 *
 * @property shifts "Align Sub-Datum to Spindle": the shift of each axis.
 * @property lathe True on a lathe: "Align Sub-Datum to Tool".
 * @property error A refusal: the dialog shows it and closes.
 * @property done True when the dialog can close.
 */
@Immutable
data class SubDatumCommandUiState(
    val loaded: Boolean = false,
    val command: SubDatumCommand = SubDatumCommand.AlignToSpindle,
    val name: String = "",
    val lathe: Boolean = false,
    val shifts: List<UiText> = emptyList(),
    val error: UiText? = null,
    val busy: Boolean = false,
    val done: Boolean = false,
)

/**
 * The presenter of the origin commands of one sub-datum S. The positions are in the workspace frame.
 *
 * - "Align Sub-Datum to Spindle" (on a lathe "Align Sub-Datum to Tool"): for each axis c
 *   that S has (Y, Z, A only when the axis is on), `Abs_c_new = S_c`. The stored rows do not
 *   change. The session must be `Connected`. The undo restores the offsets.
 * - "Align Absolute Origin to Sub-Datum": the translate `(−S_x, −S_y, −S_z, −S_a)`
 *   of all stored geometry and the new absolute offsets `A_c − S_c` in one transaction of the store,
 *   so that `Abs_c_new = Abs_c − S_c`. Nothing moves physically. "Undo" for 10 s.
 */
class SubDatumCommandPresenter(
    private val subDatumId: String,
    command: SubDatumCommand,
    private val services: FunctionServices,
    private val readout: TabReadout,
    undo: UndoOffers,
    private val messages: (UiText) -> Unit,
) : ScopedPresenter(services.context) {

    private val runner = TransformRunner(services, undo)
    private val mutable = MutableStateFlow(SubDatumCommandUiState(command = command))

    /** The UI state of the dialog. */
    val uiState: StateFlow<SubDatumCommandUiState> = mutable.asStateFlow()

    init {
        readout.frame.value?.let(::start)
        scope.launch { readout.frame.collect { next -> if (next != null && !mutable.value.loaded) start(next) } }
    }

    private fun subDatum(frame: ReadoutFrame): SubDatum? = frame.workspace.subDatums.firstOrNull { it.id == subDatumId }

    /** The axes that are on: X always, and Y (not on a lathe), Z, A when they are enabled. */
    private fun enabledAxes(frame: ReadoutFrame): Set<AxisId> = buildSet {
        add(AxisId.X)
        if (frame.enabled(AxisId.Y) && !frame.profile.machineType.isLathe) add(AxisId.Y)
        if (frame.enabled(AxisId.Z)) add(AxisId.Z)
        if (frame.enabled(AxisId.Angular)) add(AxisId.Angular)
    }

    private fun start(frame: ReadoutFrame) {
        val point = subDatum(frame)
        val lathe = frame.profile.machineType.isLathe
        if (point == null) {
            mutable.update { it.copy(loaded = true, lathe = lathe, error = UiText.of(Res.string.ws_sub_datum_missing)) }
            return
        }
        workspaceOperationsBlocked(frame.workspace)?.let { blocked ->
            mutable.update { it.copy(loaded = true, name = point.name, lathe = lathe, error = blocked) }
            return
        }
        when (mutable.value.command) {
            SubDatumCommand.AlignToSpindle -> {
                readout.positionUnavailable()?.let { refusal ->
                    mutable.update { it.copy(loaded = true, name = point.name, lathe = lathe, error = refusal) }
                    return
                }
                when (val result = spindle(frame, point)) {
                    SpindleAlignResult.NoOp -> {
                        messages(UiText.of(if (lathe) Res.string.ws_align_spindle_noop_lathe else Res.string.ws_align_spindle_noop, point.name))
                        mutable.update { it.copy(loaded = true, name = point.name, lathe = lathe, done = true) }
                    }
                    is SpindleAlignResult.Apply -> mutable.update {
                        it.copy(
                            loaded = true,
                            name = point.name,
                            lathe = lathe,
                            shifts = result.shifts.map { shift -> WorkspaceTexts.shiftLine(frame, shift.axis, shift.shift) },
                        )
                    }
                }
            }
            SubDatumCommand.AlignOriginToSubDatum -> {
                if (originOffsets(frame, point).isEmpty()) {
                    messages(UiText.of(Res.string.ws_align_origin_noop, point.name))
                    mutable.update { it.copy(loaded = true, name = point.name, lathe = lathe, done = true) }
                } else {
                    mutable.update { it.copy(loaded = true, name = point.name, lathe = lathe) }
                }
            }
        }
    }

    private fun absolute(frame: ReadoutFrame): Map<AxisId, Double> =
        OriginCommands.AXES.associateWith { axis -> frame.unrotatedAbsolute(axis) }.filterValues { it.isFinite() }

    private fun spindle(frame: ReadoutFrame, point: SubDatum): SpindleAlignResult =
        OriginCommands.alignSubDatumToSpindle(point, enabledAxes(frame), absolute(frame))

    /**
     * The new absolute offset of each axis that "Align Absolute Origin to Sub-Datum" changes:
     * `A_c − S_c`. X counts when S has X; Y, Z, and A count when S has the value and the axis is on.
     * An axis with S_c = 0 does not change.
     */
    private fun originOffsets(frame: ReadoutFrame, point: SubDatum): Map<AxisId, Double> {
        val enabled = enabledAxes(frame)
        val offsets = LinkedHashMap<AxisId, Double>()
        OriginCommands.AXES.forEach { axis ->
            if (axis != AxisId.X && axis !in enabled) return@forEach
            val value = point.valueOf(axis) ?: return@forEach
            if (value != 0.0) offsets[axis] = frame.state(axis).absoluteOffset - value
        }
        return offsets
    }

    /** "Yes": runs the command. */
    fun onConfirm() {
        val state = mutable.value
        if (state.busy || state.done || state.error != null) return
        val frame = readout.frame.value ?: return
        val point = subDatum(frame) ?: return
        when (state.command) {
            SubDatumCommand.AlignToSpindle -> {
                val result = spindle(frame, point) as? SpindleAlignResult.Apply
                if (result != null) {
                    val label = UiText.of(if (state.lathe) Res.string.ws_undo_align_tool else Res.string.ws_undo_align_spindle, point.name)
                    readout.applyWorkspace(label) { workspace ->
                        var states = workspace.axisStates
                        result.shifts.forEach { shift -> states = states.shift(shift.axis, shift.shift) }
                        workspace.copy(axisStates = states)
                    }
                }
                mutable.update { it.copy(done = true) }
            }
            SubDatumCommand.AlignOriginToSubDatum -> {
                val enabled = enabledAxes(frame)
                val transform = OriginCommands.alignAbsoluteOriginToSubDatum(point, enabled, absolute(frame)).transform
                val offsets = originOffsets(frame, point)
                mutable.update { it.copy(busy = true) }
                scope.launch {
                    val result = runner.run(
                        workspaceId = frame.workspace.id,
                        transform = transform,
                        policy = GuidePolicy.Delete,
                        label = "Align origin",
                        undoLabel = UiText.of(Res.string.ws_undo_align_origin, point.name),
                        absoluteOffsets = offsets,
                    )
                    mutable.update { current ->
                        when (result) {
                            TransformRun.Applied, TransformRun.NoChange -> current.copy(busy = false, done = true)
                            TransformRun.Busy -> current.copy(busy = false)
                            TransformRun.Failed -> current.copy(busy = false, error = UiText.of(Res.string.ws_align_origin_failed))
                        }
                    }
                }
            }
        }
    }
}
