package org.superdro.core.ui.graphics.measure

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.functions.graphics.AngleMath
import org.superdro.core.functions.graphics.AxisAlignmentTransform
import org.superdro.core.functions.graphics.Vec2
import org.superdro.core.functions.measurement.MeasurementFormat
import org.superdro.core.model.AxisAlignment
import org.superdro.core.model.AxisAlignmentSource
import org.superdro.core.model.AxisId
import org.superdro.core.model.Plane
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.readout.expression.ExpressionEvaluator
import org.superdro.core.readout.expression.valueOrNull
import org.superdro.core.resources.Res
import org.superdro.core.resources.measure_align_align
import org.superdro.core.resources.measure_align_angle
import org.superdro.core.resources.measure_align_clear
import org.superdro.core.resources.measure_align_encoder
import org.superdro.core.resources.measure_align_measured
import org.superdro.core.resources.measure_align_measured_angle
import org.superdro.core.resources.measure_align_offset
import org.superdro.core.resources.measure_align_offset_range
import org.superdro.core.resources.measure_align_pick_current
import org.superdro.core.resources.measure_align_pick_custom
import org.superdro.core.resources.measure_align_pick_origin
import org.superdro.core.resources.measure_align_point_a
import org.superdro.core.resources.measure_align_point_b
import org.superdro.core.resources.measure_align_same_point
import org.superdro.core.resources.measure_align_source
import org.superdro.core.resources.measure_align_title
import org.superdro.core.resources.measure_align_typed
import org.superdro.core.resources.measure_align_typed_range
import org.superdro.core.resources.measure_align_value_required
import org.superdro.core.resources.measure_align_xy_only
import org.superdro.core.resources.measure_cancel
import org.superdro.core.ui.adaptive.AdaptiveSheet
import org.superdro.core.ui.graphics.common.LengthInput
import org.superdro.core.ui.graphics.common.ValueField
import org.superdro.core.ui.graphics.common.evaluateLength
import org.superdro.core.ui.graphics.common.unitText
import org.superdro.core.ui.presenter.PresenterContext
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.readout.TabReadout
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.LocalTouchTargets

/** The test tag of "Align". */
const val ALIGN_APPLY_TAG: String = "measure-align-apply"

/** The test tag of "Clear". */
const val ALIGN_CLEAR_TAG: String = "measure-align-clear"

/** The test tag of the error text. */
const val ALIGN_ERROR_TAG: String = "measure-align-error"

/** Gives the test tag of the source button [source]. */
fun alignSourceTag(source: AxisAlignmentSource): String = "measure-align-source-${source.name}"

/** The source of a point of the measured line. */
enum class PointPick { Current, Origin, Custom }

/** One point of the measured line. The texts are in the display unit. */
@Immutable
data class AlignPointUiState(val pick: PointPick, val xText: String = "", val yText: String = "")

/** The two points of the measured line. */
enum class AlignPoint { A, B }

/**
 * The state of the align axes sheet.
 *
 * @property angularEnabled True when the angular axis is on: the source "Angular encoder" shows.
 * @property active True when the stored alignment is on: "Clear" shows.
 * @property measuredAngleText The angle of the measured line with 4 decimals, or null.
 */
@Immutable
data class AlignAxesUiState(
    val source: AxisAlignmentSource = AxisAlignmentSource.Typed,
    val angleText: String = "",
    val offsetText: String = "",
    val pointA: AlignPointUiState = AlignPointUiState(PointPick.Origin),
    val pointB: AlignPointUiState = AlignPointUiState(PointPick.Current),
    val angularEnabled: Boolean = false,
    val active: Boolean = false,
    val metric: Boolean = true,
    val error: UiText? = null,
    val measuredAngleText: String? = null,
)

/**
 * The presenter of the align axes sheet (on both mill types). The angle sources:
 *
 * | Source | Angle a |
 * |---|---|
 * | Typed | the typed angle, −180° to 180° |
 * | Angular encoder | `a = −(A − offset)`, the offset −360° to 360° |
 * | Measured line | `a = atan2(yB − yA, xB − xA)` in the machine frame |
 *
 * A point of the measured line is the current position, the origin, or typed values. The values
 * are in the frame of the readout, so the inverse of the current alignment gives the machine
 * frame first.
 */
class AlignAxesPresenter(private val readout: TabReadout, context: PresenterContext) : ScopedPresenter(context) {

    private val edit = MutableStateFlow(AlignAxesUiState())

    /** The state of the sheet. */
    val uiState: StateFlow<AlignAxesUiState> = combine(edit, readout.frame) { state, frame ->
        if (frame == null) state else state.copy(
            angularEnabled = frame.profile.angular.enabled,
            active = frame.workspace.axisAlignment.active,
            metric = frame.metric(AxisId.X),
            measuredAngleText = measuredAngle(state, frame)?.let { arcSec -> MeasurementFormat.fixed(AngleMath.arcSecToDegrees(arcSec), 4) },
        )
    }.stateIn(scope, SharingStarted.Eagerly, AlignAxesUiState())

    init {
        scope.launch {
            val frame = readout.frame.filterNotNull().first()
            val stored = frame.workspace.axisAlignment
            edit.update { state ->
                state.copy(
                    source = if (stored.active) stored.source else AxisAlignmentSource.Typed,
                    angleText = if (stored.active && stored.source == AxisAlignmentSource.Typed) degreesText(stored.angleArcSec) else "",
                    offsetText = if (stored.active && stored.source == AxisAlignmentSource.AngularEncoder) degreesText(stored.encoderOffsetArcSec) else "",
                )
            }
        }
    }

    /** Selects the angle source. */
    fun onSource(source: AxisAlignmentSource) = edit.update { state -> state.copy(source = source, error = null) }

    /** The typed angle changes. */
    fun onAngleText(text: String) = edit.update { state -> state.copy(angleText = text, error = null) }

    /** The angle offset of the angular encoder source changes. */
    fun onOffsetText(text: String) = edit.update { state -> state.copy(offsetText = text, error = null) }

    /** The source of the point [which] of the measured line changes. */
    fun onPick(which: AlignPoint, pick: PointPick) = editPoint(which) { point -> point.copy(pick = pick) }

    /** The typed X value of the point [which] changes. */
    fun onPointX(which: AlignPoint, text: String) = editPoint(which) { point -> point.copy(xText = text) }

    /** The typed Y value of the point [which] changes. */
    fun onPointY(which: AlignPoint, text: String) = editPoint(which) { point -> point.copy(yText = text) }

    /**
     * "Align": writes the alignment of the workspace. Gives true when the sheet closes. Gives
     * false with an error text when the plane is not `XY`, a value is not valid, or the two points
     * are in the same place.
     */
    fun onAlign(): Boolean {
        val frame = readout.latestFrame() ?: return false
        val state = edit.value
        if (frame.workspace.plane != Plane.XY) return fail(UiText.of(Res.string.measure_align_xy_only))
        val alignment = when (state.source) {
            AxisAlignmentSource.Typed -> {
                val degrees = ExpressionEvaluator.evaluate(state.angleText).valueOrNull()
                    ?: return fail(UiText.of(Res.string.measure_align_value_required))
                if (degrees < -AxisAlignmentTransform.TYPED_LIMIT_DEGREES || degrees > AxisAlignmentTransform.TYPED_LIMIT_DEGREES) {
                    return fail(UiText.of(Res.string.measure_align_typed_range))
                }
                AxisAlignment(active = true, angleArcSec = AngleMath.degreesToArcSec(degrees), source = AxisAlignmentSource.Typed)
            }
            AxisAlignmentSource.AngularEncoder -> {
                val text = state.offsetText.ifBlank { "0" }
                val degrees = ExpressionEvaluator.evaluate(text).valueOrNull()
                    ?: return fail(UiText.of(Res.string.measure_align_value_required))
                if (degrees < -AxisAlignmentTransform.OFFSET_LIMIT_DEGREES || degrees > AxisAlignmentTransform.OFFSET_LIMIT_DEGREES) {
                    return fail(UiText.of(Res.string.measure_align_offset_range))
                }
                val offset = AngleMath.degreesToArcSec(degrees)
                val angular = frame.reading(AxisId.Angular).absolute
                AxisAlignment(
                    active = true,
                    angleArcSec = if (angular.isFinite()) AxisAlignmentTransform.linkedAngleArcSec(angular, offset) else 0.0,
                    source = AxisAlignmentSource.AngularEncoder,
                    encoderOffsetArcSec = offset,
                )
            }
            AxisAlignmentSource.MeasuredLine -> {
                val a = machinePoint(state.pointA, frame) ?: return fail(UiText.of(Res.string.measure_align_value_required))
                val b = machinePoint(state.pointB, frame) ?: return fail(UiText.of(Res.string.measure_align_value_required))
                val angle = AxisAlignmentTransform.measuredAngleArcSec(a, b) ?: return fail(UiText.of(Res.string.measure_align_same_point))
                AxisAlignment(active = true, angleArcSec = angle, source = AxisAlignmentSource.MeasuredLine)
            }
        }
        readout.applyWorkspace(null) { workspace -> workspace.copy(axisAlignment = alignment) }
        return true
    }

    /** "Clear": the readouts go back to the machine axes. Points already recorded do not change. */
    fun onClear() {
        readout.applyWorkspace(null) { workspace -> workspace.copy(axisAlignment = AxisAlignment()) }
    }

    private fun fail(error: UiText): Boolean {
        edit.update { state -> state.copy(error = error) }
        return false
    }

    private fun editPoint(which: AlignPoint, change: (AlignPointUiState) -> AlignPointUiState) = edit.update { state ->
        when (which) {
            AlignPoint.A -> state.copy(pointA = change(state.pointA), error = null)
            AlignPoint.B -> state.copy(pointB = change(state.pointB), error = null)
        }
    }

    private fun measuredAngle(state: AlignAxesUiState, frame: ReadoutFrame): Double? {
        if (state.source != AxisAlignmentSource.MeasuredLine) return null
        val a = machinePoint(state.pointA, frame) ?: return null
        val b = machinePoint(state.pointB, frame) ?: return null
        return AxisAlignmentTransform.measuredAngleArcSec(a, b)
    }

    companion object {
        /**
         * Gives the machine-frame position of [point], or null when a value is not known. The
         * values of the readout frame go back through the inverse of the alignment.
         */
        fun machinePoint(point: AlignPointUiState, frame: ReadoutFrame): Vec2? {
            val metric = frame.metric(AxisId.X)
            val shown = when (point.pick) {
                PointPick.Origin -> Vec2(0.0, 0.0)
                PointPick.Current -> {
                    val x = frame.reading(AxisId.X)
                    val y = frame.reading(AxisId.Y)
                    if (!x.hasValue || !y.hasValue) return null
                    Vec2(x.absolute, y.absolute)
                }
                PointPick.Custom -> {
                    val x = (evaluateLength(point.xText, metric) as? LengthInput.Value)?.um ?: return null
                    val y = (evaluateLength(point.yText, metric) as? LengthInput.Value)?.um ?: return null
                    Vec2(x, y)
                }
            }
            val rotation = frame.alignment ?: return shown
            val machine = rotation.inverse(shown.x, shown.y)
            return Vec2(machine.x, machine.y)
        }

        private fun degreesText(arcSec: Double): String = MeasurementFormat.fixed(AngleMath.arcSecToDegrees(arcSec), 4)
    }
}

/** The align measurement axes sheet: the angle source, its values, "Align", "Clear", and "Cancel". */
@Composable
fun AlignAxesSheet(state: AlignAxesUiState, presenter: AlignAxesPresenter, onDismiss: () -> Unit) {
    val targets = LocalTouchTargets.current
    AdaptiveSheet(onDismissRequest = onDismiss, title = stringResource(Res.string.measure_align_title)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.measure_align_source), style = MaterialTheme.typography.titleMedium)
            val sources = buildList {
                add(AxisAlignmentSource.Typed to Res.string.measure_align_typed)
                if (state.angularEnabled) add(AxisAlignmentSource.AngularEncoder to Res.string.measure_align_encoder)
                add(AxisAlignmentSource.MeasuredLine to Res.string.measure_align_measured)
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                sources.forEachIndexed { index, (source, label) ->
                    SegmentedButton(
                        selected = state.source == source,
                        onClick = { presenter.onSource(source) },
                        shape = SegmentedButtonDefaults.itemShape(index, sources.size),
                        modifier = Modifier.heightIn(min = targets.button).testTag(alignSourceTag(source)),
                    ) { Text(stringResource(label)) }
                }
            }
            when (state.source) {
                AxisAlignmentSource.Typed ->
                    ValueField(stringResource(Res.string.measure_align_angle), state.angleText, presenter::onAngleText, unit = "°")
                AxisAlignmentSource.AngularEncoder ->
                    ValueField(stringResource(Res.string.measure_align_offset), state.offsetText, presenter::onOffsetText, unit = "°")
                AxisAlignmentSource.MeasuredLine -> {
                    PointEditor(stringResource(Res.string.measure_align_point_a), AlignPoint.A, state.pointA, state.metric, presenter)
                    PointEditor(stringResource(Res.string.measure_align_point_b), AlignPoint.B, state.pointB, state.metric, presenter)
                    state.measuredAngleText?.let { angle ->
                        Text(stringResource(Res.string.measure_align_measured_angle, angle), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            state.error?.let { error ->
                Text(error.resolve(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(ALIGN_ERROR_TAG))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (state.active) {
                    OutlinedButton(
                        onClick = {
                            presenter.onClear()
                            onDismiss()
                        },
                        colors = AccentButtons.outlined(),
                        modifier = Modifier.heightIn(min = targets.button).testTag(ALIGN_CLEAR_TAG),
                    ) { Text(stringResource(Res.string.measure_align_clear)) }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss, colors = AccentButtons.text(), modifier = Modifier.heightIn(min = targets.button)) {
                    Text(stringResource(Res.string.measure_cancel))
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(
                    onClick = { if (presenter.onAlign()) onDismiss() },
                    modifier = Modifier.heightIn(min = targets.button).testTag(ALIGN_APPLY_TAG),
                ) { Text(stringResource(Res.string.measure_align_align)) }
            }
        }
    }
}

@Composable
private fun PointEditor(label: String, which: AlignPoint, point: AlignPointUiState, metric: Boolean, presenter: AlignAxesPresenter) {
    val targets = LocalTouchTargets.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        val picks = listOf(
            PointPick.Current to Res.string.measure_align_pick_current,
            PointPick.Origin to Res.string.measure_align_pick_origin,
            PointPick.Custom to Res.string.measure_align_pick_custom,
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            picks.forEachIndexed { index, (pick, text) ->
                SegmentedButton(
                    selected = point.pick == pick,
                    onClick = { presenter.onPick(which, pick) },
                    shape = SegmentedButtonDefaults.itemShape(index, picks.size),
                    modifier = Modifier.heightIn(min = targets.button).testTag("measure-align-${which.name}-${pick.name}"),
                ) { Text(stringResource(text)) }
            }
        }
        if (point.pick == PointPick.Custom) {
            val unit = unitText(metric)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ValueField("X", point.xText, { text -> presenter.onPointX(which, text) }, unit, Modifier.weight(1f))
                ValueField("Y", point.yText, { text -> presenter.onPointY(which, text) }, unit, Modifier.weight(1f))
            }
        }
    }
}
