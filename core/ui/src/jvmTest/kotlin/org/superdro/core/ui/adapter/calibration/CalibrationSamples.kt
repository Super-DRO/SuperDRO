package org.superdro.core.ui.adapter.calibration

import org.superdro.core.model.AxisId
import org.superdro.core.readout.calibration.CalibrationMath
import org.superdro.core.resources.Res
import org.superdro.core.resources.calibration_no_adapter
import org.superdro.core.resources.readout_waiting_for_adapter
import org.superdro.core.ui.text.UiText

/** The UI states of the calibration wizard for the UI tests and the screenshots. */
internal object CalibrationSamples {

    private val inputX = UiText.Key("adapter_input_x", "Input X")
    private val left = UiText.Key("readout_core_edge_left", "left")
    private val base = CalibrationUiState(input = 'x', inputName = inputX, axis = AxisId.X, axisLabel = "X", liveCount = 1250L, metric = false)

    val block = base.copy(step = CalibrationStep.BlockSize, blockText = "2")

    val blockTooSmall = base.copy(
        step = CalibrationStep.BlockSize,
        blockText = "1",
        problem = CalibrationProblem(
            CalibrationProblemKind.BlockTooSmall,
            UiText.Key("readout_core_calibration_block_too_small", "Block Too Small"),
            UiText.Key("calibration_block_too_small_text", "", listOf(UiText.Key("readout_core_calibration_block_too_small_detail", ""))),
            warning = true,
        ),
    )

    val firstEdge = base.copy(step = CalibrationStep.Indicate(CalibrationPosition.First, left))

    val waiting = firstEdge.copy(unavailable = UiText.of(Res.string.readout_waiting_for_adapter))

    val sampling = base.copy(step = CalibrationStep.Sampling(CalibrationPosition.First, 6_000L, 300))

    val jitter = base.copy(
        step = CalibrationStep.Sampling(CalibrationPosition.Second, 15_000L, 740),
        problem = CalibrationProblem(
            CalibrationProblemKind.Jitter,
            UiText.Key("readout_core_calibration_jitter", "Excessive Jitter Detected"),
            UiText.Key("calibration_jitter_detail", "", listOf("2")),
            warning = true,
            position = CalibrationPosition.Second,
        ),
    )

    val result = CalibrationMath.linear(1250.0, 11_410.0, 50_800.0).let { result ->
        base.copy(liveCount = 11_410L, step = CalibrationStep.Result(result, result.resolutionText, result.umPerCountText!!))
    }

    val angularFirst = base.copy(input = 'w', inputName = UiText.Key("adapter_input_w", "Input W"), axis = AxisId.Angular, axisLabel = "α", angular = true, step = CalibrationStep.Indicate(CalibrationPosition.First, null))

    val angularResult = CalibrationMath.angular(120.0, -35_880.0).let { result ->
        angularFirst.copy(liveCount = -35_880L, step = CalibrationStep.Result(result, result.resolutionText, result.degreesPerCountText!!))
    }

    val blocked = base.copy(liveCount = null, blocked = UiText.of(Res.string.calibration_no_adapter))
}
