package org.superdro.core.ui.graphics.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.functions.graphics.PartRotationTransform
import org.superdro.core.model.AxisId
import org.superdro.core.model.PartRotation
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.readout.expression.ExpressionEvaluator
import org.superdro.core.readout.expression.ExpressionResult
import org.superdro.core.resources.Res
import org.superdro.core.resources.graphics_cancel
import org.superdro.core.resources.graphics_clear
import org.superdro.core.resources.graphics_degrees
import org.superdro.core.resources.graphics_rotate_angle
import org.superdro.core.resources.graphics_rotate_angle_limit
import org.superdro.core.resources.graphics_rotate_apply
import org.superdro.core.resources.graphics_rotate_no_angular
import org.superdro.core.resources.graphics_rotate_notice
import org.superdro.core.resources.graphics_rotate_offset
import org.superdro.core.resources.graphics_rotate_pivot
import org.superdro.core.resources.graphics_rotate_pivot_current
import org.superdro.core.resources.graphics_rotate_pivot_custom
import org.superdro.core.resources.graphics_rotate_pivot_locked
import org.superdro.core.resources.graphics_rotate_pivot_origin
import org.superdro.core.resources.graphics_rotate_source_encoder
import org.superdro.core.resources.graphics_rotate_source_typed
import org.superdro.core.resources.graphics_rotate_title
import org.superdro.core.ui.adaptive.AdaptiveSheet
import org.superdro.core.ui.graphics.common.LengthInput
import org.superdro.core.ui.graphics.common.LengthTexts
import org.superdro.core.ui.graphics.common.ValueField
import org.superdro.core.ui.graphics.common.evaluateLength
import org.superdro.core.ui.graphics.common.unitText
import org.superdro.core.ui.presenter.Presenter
import org.superdro.core.ui.readout.TabReadout
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.theme.AccentButtons

/** The source of the angle of Rotate Part. */
enum class RotationSource { Typed, AngularEncoder }

/** The source of the pivot of Rotate Part. */
enum class PivotSource { Current, Origin, Custom }

/**
 * The state of the rotate part sheet.
 *
 * @property encoderAvailable True when the angular axis is on: the linked source shows.
 * @property active True when a rotation is active. The pivot is then the stored pivot and is locked.
 */
@Immutable
data class RotatePartUiState(
    val source: RotationSource = RotationSource.Typed,
    val angleText: String = "0",
    val offsetText: String = "0",
    val pivot: PivotSource = PivotSource.Current,
    val pivotXText: String = "",
    val pivotYText: String = "",
    val metric: Boolean = true,
    val encoderAvailable: Boolean = false,
    val active: Boolean = false,
    val error: UiText? = null,
)

/**
 * The presenter of the rotate part sheet. It
 * turns the drawing, the reference image, and the sub-datums about a pivot for the display only.
 * The readouts and the stored data do not change.
 *
 * - Typed source: φ = −α, α from −360° to 360°.
 * - Linked source: φ = −(A − offset), only with the angular axis on. The angular axis then reads
 *   absolute (the readout frame applies the lock).
 * - "Rotate" stores the rotation, active. "Clear" stores active = false, all values 0, and the typed
 *   source.
 */
class RotatePartPresenter(private val readout: TabReadout) : Presenter {

    private val mutable = MutableStateFlow(initial())

    /** The state of the sheet. */
    val uiState: StateFlow<RotatePartUiState> = mutable.asStateFlow()

    fun onSource(source: RotationSource) = mutable.update { state -> state.copy(source = source, error = null) }

    fun onAngle(text: String) = mutable.update { state -> state.copy(angleText = text, error = null) }

    fun onOffset(text: String) = mutable.update { state -> state.copy(offsetText = text, error = null) }

    fun onPivot(source: PivotSource) = mutable.update { state -> if (state.active) state else state.copy(pivot = source, error = null) }

    fun onPivotX(text: String) = mutable.update { state -> state.copy(pivotXText = text, error = null) }

    fun onPivotY(text: String) = mutable.update { state -> state.copy(pivotYText = text, error = null) }

    /** "Rotate": gives true when the rotation is stored and the sheet closes. */
    fun onRotate(): Boolean {
        val frame = readout.frame.value ?: return false
        val state = mutable.value
        val degrees = degreesOf(if (state.source == RotationSource.Typed) state.angleText else state.offsetText)
        if (degrees == null || abs(degrees) > PartRotationTransform.ANGLE_LIMIT_DEGREES) {
            mutable.update { value -> value.copy(error = UiText.of(Res.string.graphics_rotate_angle_limit)) }
            return false
        }
        if (state.source == RotationSource.AngularEncoder && !(frame.enabled(AxisId.Angular) && frame.reading(AxisId.Angular).hasValue)) {
            mutable.update { value -> value.copy(error = UiText.of(Res.string.graphics_rotate_no_angular)) }
            return false
        }
        val pivot = pivotOf(frame, state) ?: run {
            mutable.update { value -> value.copy(error = UiText.of(Res.string.graphics_rotate_angle_limit)) }
            return false
        }
        val stored = frame.workspace.partRotation
        val rotation = PartRotation(
            active = true,
            angleArcSec = if (state.source == RotationSource.Typed) degrees * ARCSEC_PER_DEGREE else 0.0,
            pivotXUm = if (state.active) stored.pivotXUm else pivot.first,
            pivotYUm = if (state.active) stored.pivotYUm else pivot.second,
            linkedToAngularEncoder = state.source == RotationSource.AngularEncoder,
            encoderOffsetArcSec = if (state.source == RotationSource.AngularEncoder) degrees * ARCSEC_PER_DEGREE else 0.0,
        )
        readout.applyWorkspace(null) { workspace -> workspace.copy(partRotation = rotation) }
        return true
    }

    /** "Clear": the rotation stops. */
    fun onClear() {
        readout.applyWorkspace(null) { workspace -> workspace.copy(partRotation = PartRotation()) }
    }

    override fun close() = Unit

    private fun initial(): RotatePartUiState {
        val frame = readout.frame.value ?: return RotatePartUiState()
        val rotation = frame.workspace.partRotation
        val metric = frame.metric(AxisId.X)
        val format = frame.profile.display.readoutFormat
        return RotatePartUiState(
            source = if (rotation.linkedToAngularEncoder) RotationSource.AngularEncoder else RotationSource.Typed,
            angleText = degreesText(rotation.angleArcSec),
            offsetText = degreesText(rotation.encoderOffsetArcSec),
            pivot = if (rotation.active) PivotSource.Custom else PivotSource.Current,
            pivotXText = if (rotation.active) LengthTexts.format(rotation.pivotXUm, metric, format) else "",
            pivotYText = if (rotation.active) LengthTexts.format(rotation.pivotYUm, metric, format) else "",
            metric = metric,
            encoderAvailable = frame.enabled(AxisId.Angular),
            active = rotation.active,
        )
    }

    private fun pivotOf(frame: ReadoutFrame, state: RotatePartUiState): Pair<Double, Double>? = when (state.pivot) {
        PivotSource.Origin -> 0.0 to 0.0
        PivotSource.Current -> {
            val x = frame.reading(AxisId.X)
            val y = frame.reading(AxisId.Y)
            (if (x.hasValue) x.absolute else 0.0) to (if (y.hasValue) y.absolute else 0.0)
        }
        PivotSource.Custom -> {
            val x = evaluateLength(state.pivotXText, state.metric) as? LengthInput.Value
            val y = evaluateLength(state.pivotYText, state.metric) as? LengthInput.Value
            if (state.active) 0.0 to 0.0 else if (x == null || y == null) null else x.um to y.um
        }
    }

    private fun degreesOf(text: String): Double? {
        if (text.isBlank()) return 0.0
        return (ExpressionEvaluator.evaluate(text.trim()) as? ExpressionResult.Value)?.value?.takeIf { value -> value.isFinite() }
    }

    private fun degreesText(arcSec: Double): String {
        val degrees = arcSec / ARCSEC_PER_DEGREE
        return if (degrees == kotlin.math.floor(degrees)) degrees.toLong().toString() else degrees.toString()
    }

    private companion object {
        const val ARCSEC_PER_DEGREE: Double = 3600.0
    }
}

/** The test tag of the angle field of the rotate part sheet. */
const val ROTATE_ANGLE_TAG: String = "rotate-part-angle"

/** The test tag of the "Rotate" button. */
const val ROTATE_APPLY_TAG: String = "rotate-part-apply"

/** The rotate part sheet: a `ModalBottomSheet` in portrait and a dialog in landscape. */
@Composable
fun RotatePartSheet(
    state: RotatePartUiState,
    presenter: RotatePartPresenter,
    onClose: () -> Unit,
) {
    AdaptiveSheet(onDismissRequest = onClose, title = stringResource(Res.string.graphics_rotate_title)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.graphics_rotate_notice), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.encoderAvailable) {
                Choice(
                    options = listOf(RotationSource.Typed to Res.string.graphics_rotate_source_typed, RotationSource.AngularEncoder to Res.string.graphics_rotate_source_encoder),
                    selected = state.source,
                    onSelect = presenter::onSource,
                )
            }
            val degrees = stringResource(Res.string.graphics_degrees)
            if (state.source == RotationSource.Typed) {
                ValueField(
                    label = stringResource(Res.string.graphics_rotate_angle),
                    text = state.angleText,
                    onTextChange = presenter::onAngle,
                    unit = degrees,
                    error = state.error,
                    fieldModifier = Modifier.testTag(ROTATE_ANGLE_TAG),
                )
            } else {
                ValueField(label = stringResource(Res.string.graphics_rotate_offset), text = state.offsetText, onTextChange = presenter::onOffset, unit = degrees, error = state.error)
            }
            Text(stringResource(Res.string.graphics_rotate_pivot), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            if (state.active) {
                Text(stringResource(Res.string.graphics_rotate_pivot_locked), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Choice(
                    options = listOf(
                        PivotSource.Current to Res.string.graphics_rotate_pivot_current,
                        PivotSource.Origin to Res.string.graphics_rotate_pivot_origin,
                        PivotSource.Custom to Res.string.graphics_rotate_pivot_custom,
                    ),
                    selected = state.pivot,
                    onSelect = presenter::onPivot,
                )
            }
            if (state.pivot == PivotSource.Custom) {
                val unit = unitText(state.metric)
                ValueField(label = "X", text = state.pivotXText, onTextChange = presenter::onPivotX, unit = unit, enabled = !state.active)
                ValueField(label = "Y", text = state.pivotYText, onTextChange = presenter::onPivotY, unit = unit, enabled = !state.active)
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                if (state.active) {
                    OutlinedButton(
                        onClick = {
                            presenter.onClear()
                            onClose()
                        },
                        colors = AccentButtons.outlined(),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(Res.string.graphics_clear)) }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClose, colors = AccentButtons.text(), modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(Res.string.graphics_cancel))
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(
                    onClick = { if (presenter.onRotate()) onClose() },
                    modifier = Modifier.heightIn(min = 48.dp).testTag(ROTATE_APPLY_TAG),
                ) { Text(stringResource(Res.string.graphics_rotate_apply)) }
            }
        }
    }
}

@Composable
private fun <T> Choice(options: List<Pair<T, StringResource>>, selected: T, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = selected == value,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(label)) }
        }
    }
}
