package org.superdro.core.ui.functions.sheets

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.superdro.core.functions.measure.MeasureDistanceAngle
import org.superdro.core.model.AxisId
import org.superdro.core.readout.DisplayUnit
import org.superdro.core.readout.PostProcessor
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.readout.operations.ActionButtons
import org.superdro.core.readout.operations.AxisOperations
import org.superdro.core.readout.operations.CaliperOperations
import org.superdro.core.readout.operations.ZeroButtons
import org.superdro.core.resources.Res
import org.superdro.core.resources.fns_undo_caliper_absolute_zero
import org.superdro.core.resources.fns_undo_caliper_zero
import org.superdro.core.resources.fns_undo_copy_to_x
import org.superdro.core.ui.functions.common.FunctionServices
import org.superdro.core.ui.functions.common.LengthTexts
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.readout.ReadoutPresenter
import org.superdro.core.ui.readout.TabReadout
import org.superdro.core.ui.text.UiText

/**
 * The values of the measure sheet.
 *
 * @property aLabel The label of the first axis: X on a mill, Z on a lathe.
 * @property bLabel The label of the second axis: Y on a mill, X on a lathe.
 * @property lineAngle The line angle `alpha`, −90° to 90°, with 2 decimals.
 * @property direction The direction from the zero to the current position, 0° to 360°.
 * @property complement `theta = 90 − alpha`.
 */
@Immutable
data class AngleMeasureUiState(
    val loaded: Boolean = false,
    val metric: Boolean = false,
    val aLabel: String = "X",
    val bLabel: String = "Y",
    val aText: String = "",
    val bText: String = "",
    val distance: String = "",
    val lineAngle: String = "",
    val direction: String = "",
    val complement: String = "",
    val unavailable: UiText? = null,
)

/**
 * The presenter of the measure sheet "Measure Distance/Angle". The values are the shown
 * positions `a` and `b` of two axes: (X, Y) on a mill, (Z, X) on a
 * lathe. They follow the ABS or INC mode of each axis. The sheet shows live values and stores
 * nothing. The lengths show in the unit of the sheet; the default is the metric default of the
 * profile.
 */
class AngleMeasurePresenter(
    private val readout: TabReadout,
    services: FunctionServices,
) : ScopedPresenter(services.context) {

    private val mutable = MutableStateFlow(AngleMeasureUiState())
    private var metric: Boolean? = null

    /** The UI state of the sheet. */
    val uiState: StateFlow<AngleMeasureUiState> = mutable.asStateFlow()

    init {
        scope.launch { readout.frame.collect { frame -> refresh(frame) } }
    }

    /** Sets the unit of the lengths of the sheet. */
    fun onUnit(metric: Boolean) {
        this.metric = metric
        refresh(readout.frame.value)
    }

    private fun refresh(frame: ReadoutFrame?) {
        if (frame == null) return
        val unit = metric ?: frame.profile.metricDefault
        val (a, b) = MeasureDistanceAngle.axes(frame.profile.machineType)
        val aReading = frame.reading(a)
        val bReading = frame.reading(b)
        val format = frame.profile.display.readoutFormat
        if (!aReading.hasValue || !bReading.hasValue) {
            mutable.value = AngleMeasureUiState(
                loaded = true,
                metric = unit,
                aLabel = ReadoutPresenter.labelOf(frame.profile, a),
                bLabel = ReadoutPresenter.labelOf(frame.profile, b),
                unavailable = readout.positionUnavailable(),
            )
            return
        }
        val values = MeasureDistanceAngle.measure(aReading.shown, bReading.shown)
        mutable.value = AngleMeasureUiState(
            loaded = true,
            metric = unit,
            aLabel = ReadoutPresenter.labelOf(frame.profile, a),
            bLabel = ReadoutPresenter.labelOf(frame.profile, b),
            aText = LengthTexts.format(aReading.shown, unit, format),
            bText = LengthTexts.format(bReading.shown, unit, format),
            distance = LengthTexts.format(values.distanceUm, unit, format),
            lineAngle = LengthTexts.degrees(values.lineAngleArcSec),
            direction = LengthTexts.degrees(values.directionArcSec),
            complement = LengthTexts.degrees(values.complementArcSec),
            unavailable = readout.positionUnavailable(),
        )
    }
}

/**
 * The values of the dimension measure sheet.
 *
 * @property value The shown caliper value in its unit, or an empty text with no value.
 * @property incremental True when the caliper reads incremental.
 * @property metric True when the caliper shows mm.
 * @property xLabel The label of X, for "Copy to X".
 * @property xDiameter True when X shows the diameter: the copy uses the full value.
 */
@Immutable
data class DimensionMeasureUiState(
    val loaded: Boolean = false,
    val value: String = "",
    val hasValue: Boolean = false,
    val incremental: Boolean = false,
    val metric: Boolean = false,
    val xLabel: String = "X",
    val xDiameter: Boolean = false,
)

/**
 * The presenter of the dimension measure sheet "Measure Dimension": the caliper value
 * with zero, ABS or INC, and units, and "Copy to X". The caliper has its
 * own state, not the state of Z.
 *
 * ```text
 * factor = X diameter mode ? 1.0 : 0.5
 * value  = caliper shown value (µm) × factor
 * X.i(value) if X is incremental, else X.h(value)
 * ```
 */
class DimensionMeasurePresenter(
    private val readout: TabReadout,
    services: FunctionServices,
) : ScopedPresenter(services.context) {

    private val mutable = MutableStateFlow(DimensionMeasureUiState())

    /** The UI state of the sheet. */
    val uiState: StateFlow<DimensionMeasureUiState> = mutable.asStateFlow()

    init {
        scope.launch { readout.frame.collect { frame -> refresh(frame) } }
    }

    /** The zero button: the zero of the caliper with the rule of `short_press_zero_set`. */
    fun onZero(): UiText? {
        val frame = readout.frame.value ?: return null
        readout.positionUnavailable()?.let { return it }
        readout.apply(ZeroButtons.tap(frame, AxisId.Caliper), UiText.of(Res.string.fns_undo_caliper_zero))
        return null
    }

    /** The zero button, long press: the absolute zero of the caliper. */
    fun onZeroLongPress(): UiText? {
        val frame = readout.frame.value ?: return null
        readout.positionUnavailable()?.let { return it }
        readout.apply(ZeroButtons.longPress(frame, AxisId.Caliper), UiText.of(Res.string.fns_undo_caliper_absolute_zero))
        return null
    }

    /** The ABS or INC button of the caliper. */
    fun onToggleMode() {
        val frame = readout.frame.value ?: return
        readout.apply(AxisOperations.toggleMode(frame, AxisId.Caliper), null)
    }

    /** The unit button of the caliper. */
    fun onToggleUnits() {
        val frame = readout.frame.value ?: return
        readout.apply(ActionButtons.toggleUnits(frame, AxisId.Caliper), null)
    }

    /**
     * "Copy to X": the caliper value goes to X. The copy needs a `Connected` session:
     * the function gives the refusal text, or null.
     */
    fun onCopyToX(): UiText? {
        val frame = readout.frame.value ?: return null
        readout.positionUnavailable()?.let { return it }
        if (!frame.reading(AxisId.Caliper).hasValue) return null
        readout.apply(CaliperOperations.copyToX(frame), UiText.of(Res.string.fns_undo_copy_to_x, ReadoutPresenter.labelOf(frame.profile, AxisId.X)))
        return null
    }

    private fun refresh(frame: ReadoutFrame?) {
        if (frame == null) return
        val reading = frame.reading(AxisId.Caliper)
        mutable.value = DimensionMeasureUiState(
            loaded = true,
            value = if (reading.hasValue) reading.text else "",
            hasValue = reading.hasValue,
            incremental = reading.incremental,
            metric = reading.unit == DisplayUnit.Millimeter,
            xLabel = ReadoutPresenter.labelOf(frame.profile, AxisId.X),
            xDiameter = frame.postProcessor(AxisId.X) == PostProcessor.Diameter,
        )
    }
}
