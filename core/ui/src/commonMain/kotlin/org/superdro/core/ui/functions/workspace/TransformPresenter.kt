package org.superdro.core.ui.functions.workspace

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.superdro.core.functions.transform.FlipAxis
import org.superdro.core.functions.transform.FlipTransform
import org.superdro.core.functions.transform.GuidePolicy
import org.superdro.core.functions.transform.RotateTransform
import org.superdro.core.functions.transform.ScaleTransform
import org.superdro.core.functions.transform.WorkspaceTransform
import org.superdro.core.model.AxisId
import org.superdro.core.model.SubDatum
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.resources.Res
import org.superdro.core.resources.ws_datum_position_axis
import org.superdro.core.resources.ws_error_axis_missing
import org.superdro.core.resources.ws_error_lathe
import org.superdro.core.resources.ws_error_number
import org.superdro.core.resources.ws_error_position
import org.superdro.core.resources.ws_error_scale
import org.superdro.core.resources.ws_error_sub_datum
import org.superdro.core.resources.ws_transform_failed
import org.superdro.core.resources.ws_transform_noop
import org.superdro.core.resources.ws_undo_flip
import org.superdro.core.resources.ws_undo_rotate
import org.superdro.core.resources.ws_undo_scale
import org.superdro.core.ui.functions.common.FieldMath
import org.superdro.core.ui.functions.common.FunctionServices
import org.superdro.core.ui.functions.common.LengthFieldState
import org.superdro.core.ui.functions.common.NumberFieldState
import org.superdro.core.ui.functions.common.UndoOffers
import org.superdro.core.ui.navigation.WorkspaceTransformKind
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.text.UiText

/** The source of the pivot or of the line of a transform. */
enum class PivotSource {
    /** The default: the current position of each axis in the workspace frame. */
    Current,

    /** 0 on each axis. */
    Origin,

    /** The coordinates of a sub-datum. */
    SubDatum,

    /** The typed values. */
    Custom,
}

/** One sub-datum of the source list: the name and the coordinates of the axes of the sheet. */
@Immutable
data class PivotChoice(val id: String, val name: String, val coordinates: List<UiText>)

/**
 * The state of a transform sheet: flip, rotate, or scale.
 *
 * @property axes The axes of the pivot or the line: flip across X → Y; flip across Y → X;
 *   rotate → X and Y; scale → X, Y, and Z when [scaleZ] is on.
 * @property guideQuestion The number of the guides that the rotation cannot keep, while the
 *   question "delete or convert" shows, or null.
 * @property done True when the sheet can close.
 */
@Immutable
data class TransformUiState(
    val loaded: Boolean = false,
    val kind: WorkspaceTransformKind = WorkspaceTransformKind.Flip,
    val flipAxis: FlipAxis = FlipAxis.AcrossX,
    val angle: NumberFieldState = NumberFieldState.of(0.0),
    val percent: NumberFieldState = NumberFieldState.of(100.0),
    val scaleZ: Boolean = true,
    val source: PivotSource = PivotSource.Current,
    val subDatums: List<PivotChoice> = emptyList(),
    val subDatumId: String? = null,
    val fields: Map<AxisId, LengthFieldState> = emptyMap(),
    val labels: Map<AxisId, String> = emptyMap(),
    val axisAlignment: Boolean = false,
    val fieldErrors: Map<AxisId, UiText> = emptyMap(),
    val valueError: UiText? = null,
    val error: UiText? = null,
    val guideQuestion: Int? = null,
    val busy: Boolean = false,
    val done: Boolean = false,
) {
    /** The axes of the pivot or the line. */
    val axes: List<AxisId>
        get() = when (kind) {
            WorkspaceTransformKind.Flip -> listOf(if (flipAxis == FlipAxis.AcrossX) AxisId.Y else AxisId.X)
            WorkspaceTransformKind.Rotate -> listOf(AxisId.X, AxisId.Y)
            WorkspaceTransformKind.Scale -> if (scaleZ) listOf(AxisId.X, AxisId.Y, AxisId.Z) else listOf(AxisId.X, AxisId.Y)
            WorkspaceTransformKind.Pair -> emptyList()
        }
}

/**
 * The presenter of the flip, rotate, and scale sheets. The pivot or
 * the line comes from the
 * current position, the origin, a sub-datum, or the typed values. All positions are in the
 * workspace frame. The apply goes through the [TransformRunner]: one transaction, then
 * "Undo" for 10 s.
 *
 * @param latestFrame Gives the frame with the newest inputs (`TabReadout.latestFrame`). The
 *   current position of a press uses it, because [frame] can be one display frame older.
 */
class TransformPresenter(
    kind: WorkspaceTransformKind,
    private val services: FunctionServices,
    private val frame: StateFlow<ReadoutFrame?>,
    private val positionUnavailable: () -> UiText?,
    undo: UndoOffers,
    private val latestFrame: () -> ReadoutFrame? = { frame.value },
) : ScopedPresenter(services.context) {

    private val runner = TransformRunner(services, undo)
    private val mutable = MutableStateFlow(TransformUiState(kind = kind))

    /** The UI state of the sheet. */
    val uiState: StateFlow<TransformUiState> = mutable.asStateFlow()

    init {
        val current = frame.value
        if (current != null) start(current)
        scope.launch {
            frame.collect { next -> if (next != null && !mutable.value.loaded) start(next) }
        }
    }

    private fun start(current: ReadoutFrame) {
        val profile = current.profile
        val workspace = current.workspace
        mutable.update { state ->
            state.copy(
                loaded = true,
                fields = listOf(AxisId.X, AxisId.Y, AxisId.Z).associateWith { axis -> LengthFieldState(metric = current.metric(axis)) },
                labels = AxisId.entries.associateWith { axis -> WorkspaceTexts.label(profile, axis) },
                subDatums = choices(current, workspace.subDatums),
                axisAlignment = current.measurementActive && workspace.axisAlignment.active,
                scaleZ = profile.use3dSubDatums && profile.z.enabled,
            )
        }
    }

    private fun choices(current: ReadoutFrame, points: List<SubDatum>): List<PivotChoice> = points.map { point ->
        PivotChoice(
            id = point.id,
            name = point.name,
            coordinates = listOf(AxisId.X, AxisId.Y, AxisId.Z).mapNotNull { axis ->
                point.valueOf(axis)?.let { value ->
                    UiText.of(Res.string.ws_datum_position_axis, WorkspaceTexts.label(current.profile, axis), WorkspaceTexts.value(current, axis, value))
                }
            },
        )
    }

    fun onFlipAxis(axis: FlipAxis) = mutable.update { it.copy(flipAxis = axis, fieldErrors = emptyMap(), error = null) }

    fun onAngle(text: String) = mutable.update { it.copy(angle = it.angle.withText(text), valueError = null, error = null) }

    fun onPercent(text: String) = mutable.update { it.copy(percent = it.percent.withText(text), valueError = null, error = null) }

    fun onScaleZ(on: Boolean) = mutable.update { it.copy(scaleZ = on, fieldErrors = emptyMap(), error = null) }

    fun onSource(source: PivotSource) = mutable.update { it.copy(source = source, fieldErrors = emptyMap(), error = null) }

    fun onSubDatum(id: String) = mutable.update { it.copy(subDatumId = id, error = null) }

    /** The text of the custom field of [axis]. The base of `++x` and `--x` is the current position. */
    fun onText(axis: AxisId, text: String) = mutable.update { state ->
        val field = state.fields[axis] ?: return@update state
        state.copy(fields = state.fields + (axis to field.withText(text, currentUm(axis) ?: 0.0)), fieldErrors = state.fieldErrors - axis)
    }

    fun onUnit(axis: AxisId, metric: Boolean) = mutable.update { state ->
        val field = state.fields[axis] ?: return@update state
        state.copy(fields = state.fields + (axis to field.withUnit(metric)))
    }

    /** The current position of [axis] in the workspace frame: `R + H + T + A`, or null. */
    private fun currentUm(axis: AxisId): Double? = latestFrame()?.unrotatedAbsolute(axis)?.takeIf { it.isFinite() }

    /** "Flip", "Rotate", or "Scale": checks the inputs, then runs the transform. */
    fun onApply() {
        val state = mutable.value
        if (state.busy || !state.loaded) return
        val current = latestFrame() ?: return
        workspaceOperationsBlocked(current.workspace)?.let { blocked ->
            mutable.update { it.copy(error = blocked) }
            return
        }
        if (current.profile.machineType.isLathe) {
            mutable.update { it.copy(error = UiText.of(Res.string.ws_error_lathe)) }
            return
        }
        val pivot = pivot(state, current) ?: return
        val transform = transformOf(state, pivot) ?: return
        scope.launch {
            val broken = runner.brokenGuides(current.workspace.id, transform)
            if (broken > 0) {
                mutable.update { it.copy(guideQuestion = broken) }
            } else {
                run(transform, GuidePolicy.Delete)
            }
        }
    }

    /** The answer to "Guides Will Be Deleted": [policy], or null for "Cancel" (the sheet stays). */
    fun onGuideAnswer(policy: GuidePolicy?) {
        mutable.update { it.copy(guideQuestion = null) }
        if (policy == null) return
        val state = mutable.value
        val current = latestFrame() ?: return
        val pivot = pivot(state, current) ?: return
        val transform = transformOf(state, pivot) ?: return
        scope.launch { run(transform, policy) }
    }

    private suspend fun run(transform: WorkspaceTransform, policy: GuidePolicy) {
        val current = frame.value ?: return
        mutable.update { it.copy(busy = true) }
        val (label, undoLabel) = labels(transform)
        val result = runner.run(current.workspace.id, transform, policy, label, undoLabel)
        mutable.update { state ->
            when (result) {
                TransformRun.Applied -> state.copy(busy = false, done = true)
                TransformRun.NoChange -> state.copy(busy = false, error = UiText.of(Res.string.ws_transform_noop))
                TransformRun.Busy -> state.copy(busy = false)
                TransformRun.Failed -> state.copy(busy = false, error = UiText.of(Res.string.ws_transform_failed))
            }
        }
    }

    private fun labels(transform: WorkspaceTransform): Pair<String, UiText> = when (transform) {
        is FlipTransform -> "Flip" to UiText.of(Res.string.ws_undo_flip)
        is RotateTransform -> "Rotate ${transform.label}" to UiText.of(Res.string.ws_undo_rotate, transform.label)
        is ScaleTransform -> {
            val percent = FieldMath.format(transform.factor * 100.0, 4)
            "Scale $percent%" to UiText.of(Res.string.ws_undo_scale, "$percent %")
        }
        else -> "Transform" to UiText.of(Res.string.ws_undo_flip)
    }

    /** Gives the pivot (or the line position) of each axis of the sheet, or null with the errors in the state. */
    private fun pivot(state: TransformUiState, current: ReadoutFrame): Map<AxisId, Double>? {
        val axes = state.axes
        val errors = LinkedHashMap<AxisId, UiText>()
        val values = LinkedHashMap<AxisId, Double>()
        when (state.source) {
            PivotSource.Origin -> axes.forEach { axis -> values[axis] = 0.0 }
            PivotSource.Current -> {
                positionUnavailable()?.let { refusal ->
                    mutable.update { it.copy(error = refusal) }
                    return null
                }
                axes.forEach { axis ->
                    val value = current.unrotatedAbsolute(axis)
                    if (value.isFinite()) values[axis] = value else errors[axis] = UiText.of(Res.string.ws_error_position)
                }
            }
            PivotSource.Custom -> axes.forEach { axis ->
                val value = state.fields[axis]?.valueUm
                when {
                    value == null -> errors[axis] = UiText.of(Res.string.ws_error_position)
                    !value.isFinite() -> errors[axis] = UiText.of(Res.string.ws_error_number)
                    else -> values[axis] = value
                }
            }
            PivotSource.SubDatum -> {
                val point = current.workspace.subDatums.firstOrNull { it.id == state.subDatumId }
                if (point == null) {
                    mutable.update { it.copy(error = UiText.of(Res.string.ws_error_sub_datum)) }
                    return null
                }
                val missing = axes.filter { axis -> point.valueOf(axis) == null }
                if (missing.isNotEmpty()) {
                    val names = missing.joinToString("/") { axis -> WorkspaceTexts.label(current.profile, axis) }
                    mutable.update { it.copy(error = UiText.of(Res.string.ws_error_axis_missing, point.name, names)) }
                    return null
                }
                axes.forEach { axis -> values[axis] = point.valueOf(axis)!! }
            }
        }
        if (errors.isNotEmpty()) {
            mutable.update { it.copy(fieldErrors = errors) }
            return null
        }
        return values
    }

    private fun transformOf(state: TransformUiState, pivot: Map<AxisId, Double>): WorkspaceTransform? = when (state.kind) {
        WorkspaceTransformKind.Flip -> FlipTransform(state.flipAxis, pivot.getValue(state.axes.single()))
        WorkspaceTransformKind.Rotate -> {
            val degrees = state.angle.value ?: 0.0
            if (!degrees.isFinite()) {
                mutable.update { it.copy(valueError = UiText.of(Res.string.ws_error_number)) }
                null
            } else {
                RotateTransform.ofDegrees(degrees, pivot.getValue(AxisId.X), pivot.getValue(AxisId.Y))
            }
        }
        WorkspaceTransformKind.Scale -> {
            val percent = state.percent.value ?: 100.0
            if (!percent.isFinite() || percent <= 0.0) {
                mutable.update { it.copy(valueError = UiText.of(Res.string.ws_error_scale)) }
                null
            } else {
                ScaleTransform.ofPercent(percent, pivot.getValue(AxisId.X), pivot.getValue(AxisId.Y), if (state.scaleZ) pivot.getValue(AxisId.Z) else null)
            }
        }
        WorkspaceTransformKind.Pair -> null
    }
}
