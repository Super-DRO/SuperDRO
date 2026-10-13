package org.superdro.core.ui.adapter.calibration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.resources.Res
import org.superdro.core.resources.calibration_apply
import org.superdro.core.resources.calibration_axis
import org.superdro.core.resources.calibration_block_size
import org.superdro.core.resources.calibration_block_text
import org.superdro.core.resources.calibration_block_title
import org.superdro.core.resources.calibration_continue
import org.superdro.core.resources.calibration_discard
import org.superdro.core.resources.calibration_edge_text
import org.superdro.core.resources.calibration_first_edge_title
import org.superdro.core.resources.calibration_first_position_text
import org.superdro.core.resources.calibration_first_position_title
import org.superdro.core.resources.calibration_live_count
import org.superdro.core.resources.calibration_no_axis
import org.superdro.core.resources.calibration_procedure_angular
import org.superdro.core.resources.calibration_procedure_linear
import org.superdro.core.resources.calibration_progress_description
import org.superdro.core.resources.calibration_result_angular
import org.superdro.core.resources.calibration_result_apply_note
import org.superdro.core.resources.calibration_result_direction_normal
import org.superdro.core.resources.calibration_result_direction_reversed
import org.superdro.core.resources.calibration_result_linear
import org.superdro.core.resources.calibration_result_title
import org.superdro.core.resources.calibration_retry
import org.superdro.core.resources.calibration_sampling_progress
import org.superdro.core.resources.calibration_sampling_text
import org.superdro.core.resources.calibration_sampling_title
import org.superdro.core.resources.calibration_second_edge_title
import org.superdro.core.resources.calibration_second_position_text
import org.superdro.core.resources.calibration_second_position_title
import org.superdro.core.resources.calibration_title
import org.superdro.core.resources.calibration_unit
import org.superdro.core.resources.calibration_unit_inch
import org.superdro.core.resources.calibration_unit_mm
import org.superdro.core.resources.frame_cancel
import org.superdro.core.resources.frame_close
import org.superdro.core.resources.ic_error
import org.superdro.core.resources.ic_warning
import org.superdro.core.readout.calibration.CalibrationMath
import org.superdro.core.ui.adapter.common.ChoiceRow
import org.superdro.core.ui.adapter.common.NumberField
import org.superdro.core.ui.adapter.common.StatusKind
import org.superdro.core.ui.adapter.common.StatusLine
import org.superdro.core.ui.adapter.common.ValueRow
import org.superdro.core.ui.navigation.ScreenScaffold
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.LocalTouchTargets
import org.superdro.core.ui.theme.accentColor

/** The test tag of the "Continue" button of a step. */
const val CALIBRATION_CONTINUE_TAG: String = "calibration-continue"

/** The test tag of the block size field. */
const val CALIBRATION_BLOCK_TAG: String = "calibration-block"

/** The test tag of the unit choice of the block size. */
const val CALIBRATION_UNIT_TAG: String = "calibration-unit"

/** The test tag of the text of the current step. */
const val CALIBRATION_STEP_TAG: String = "calibration-step"

/** The test tag of the sample progress. */
const val CALIBRATION_PROGRESS_TAG: String = "calibration-progress"

/** The test tag of the "Apply" button of the result. */
const val CALIBRATION_APPLY_TAG: String = "calibration-apply"

/** The test tag of the "Discard" button of the result. */
const val CALIBRATION_DISCARD_TAG: String = "calibration-discard"

/** The test tag of the resolution text of the result. */
const val CALIBRATION_RESULT_TAG: String = "calibration-result"

/** The test tag of the "Retry" button of a problem dialog. */
const val CALIBRATION_RETRY_TAG: String = "calibration-retry"

/** The test tag of the "Continue" button of a problem dialog. */
const val CALIBRATION_PROBLEM_CONTINUE_TAG: String = "calibration-problem-continue"

/** The events of the calibration wizard. */
interface CalibrationEvents {
    fun onBlockTextChange(text: String)
    fun onBlockUnitChange(metric: Boolean)
    fun onContinue()
    fun onCancel()
    fun onApply()
    fun onDiscard()
    fun onProblemRetry()
    fun onProblemContinue()
    fun onProblemDismiss()
}

/** The [CalibrationEvents] of a presenter. */
fun InputCalibrationPresenter.events(): CalibrationEvents = object : CalibrationEvents {
    override fun onBlockTextChange(text: String) = this@events.onBlockTextChange(text)
    override fun onBlockUnitChange(metric: Boolean) = this@events.onBlockUnitChange(metric)
    override fun onContinue() = this@events.onContinue()
    override fun onCancel() = this@events.onCancel()
    override fun onApply() = this@events.onApply()
    override fun onDiscard() = this@events.onDiscard()
    override fun onProblemRetry() = this@events.onProblemRetry()
    override fun onProblemContinue() = this@events.onProblemContinue()
    override fun onProblemDismiss() = this@events.onProblemDismiss()
}

/**
 * The calibration wizard of one adapter input: a screen of the tab back
 * stack. The composable is stateless: [state] comes from [InputCalibrationPresenter].
 */
@Composable
fun InputCalibrationScreen(state: CalibrationUiState, events: CalibrationEvents) {
    ScreenScaffold(title = stringResource(Res.string.calibration_title, state.inputName.resolve()), onBack = events::onCancel) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = 760.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val blocked = state.blocked
                if (blocked != null) {
                    StatusLine(StatusKind.Error, blocked.resolve(), tag = CALIBRATION_STEP_TAG)
                    Buttons(onCancel = events::onCancel, cancelText = stringResource(Res.string.frame_close))
                } else {
                    Header(state)
                    state.unavailable?.let { text -> StatusLine(StatusKind.Warning, text.resolve()) }
                    StepCard(state, events)
                }
            }
        }
    }
    state.problem?.let { problem -> ProblemDialog(problem, events) }
}

@Composable
private fun Header(state: CalibrationUiState) {
    val axis = state.axisLabel?.let { label -> stringResource(Res.string.calibration_axis, label) } ?: stringResource(Res.string.calibration_no_axis)
    val procedure = stringResource(if (state.angular) Res.string.calibration_procedure_angular else Res.string.calibration_procedure_linear)
    Text("$axis · $procedure", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    state.liveCount?.let { count -> ValueRow(stringResource(Res.string.calibration_live_count), count.toString()) }
}

@Composable
private fun StepCard(state: CalibrationUiState, events: CalibrationEvents) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (val step = state.step) {
                CalibrationStep.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = accentColor())
                CalibrationStep.BlockSize -> BlockStep(state, events)
                is CalibrationStep.Indicate -> IndicateStep(state, step, events)
                is CalibrationStep.Sampling -> SamplingStep(step, events)
                is CalibrationStep.Result -> ResultStep(state, step, events)
            }
        }
    }
}

@Composable
private fun StepTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.semantics { heading() })
}

@Composable
private fun BlockStep(state: CalibrationUiState, events: CalibrationEvents) {
    StepTitle(stringResource(Res.string.calibration_block_title))
    Text(stringResource(Res.string.calibration_block_text), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(CALIBRATION_STEP_TAG))
    val mm = stringResource(Res.string.calibration_unit_mm)
    val inch = stringResource(Res.string.calibration_unit_inch)
    NumberField(
        label = stringResource(Res.string.calibration_block_size),
        value = state.blockText,
        onValueChange = events::onBlockTextChange,
        suffix = if (state.metric) mm else inch,
        tag = CALIBRATION_BLOCK_TAG,
    )
    ChoiceRow(
        label = stringResource(Res.string.calibration_unit),
        options = listOf(true, false),
        selected = state.metric,
        optionLabel = { metric -> if (metric) mm else inch },
        onSelect = events::onBlockUnitChange,
        tag = CALIBRATION_UNIT_TAG,
    )
    Buttons(onCancel = events::onCancel, continueEnabled = state.canContinue, onContinue = events::onContinue)
}

@Composable
private fun IndicateStep(state: CalibrationUiState, step: CalibrationStep.Indicate, events: CalibrationEvents) {
    val first = step.position == CalibrationPosition.First
    val edge = step.edge
    if (edge == null) {
        StepTitle(stringResource(if (first) Res.string.calibration_first_position_title else Res.string.calibration_second_position_title))
        Text(
            stringResource(if (first) Res.string.calibration_first_position_text else Res.string.calibration_second_position_text),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag(CALIBRATION_STEP_TAG),
        )
    } else {
        StepTitle(stringResource(if (first) Res.string.calibration_first_edge_title else Res.string.calibration_second_edge_title))
        Text(stringResource(Res.string.calibration_edge_text, edge.resolve()), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(CALIBRATION_STEP_TAG))
    }
    Buttons(onCancel = events::onCancel, continueEnabled = state.canContinue, onContinue = events::onContinue)
}

@Composable
private fun SamplingStep(step: CalibrationStep.Sampling, events: CalibrationEvents) {
    StepTitle(stringResource(Res.string.calibration_sampling_title))
    Text(stringResource(Res.string.calibration_sampling_text), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(CALIBRATION_STEP_TAG))
    val seconds = (step.elapsedMs / 1000L).toInt()
    val total = (CalibrationMath.SAMPLE_TIME_MS / 1000L).toInt()
    val description = stringResource(Res.string.calibration_progress_description, seconds, total)
    LinearProgressIndicator(
        progress = { step.progress },
        color = accentColor(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 8.dp).testTag(CALIBRATION_PROGRESS_TAG).semantics { contentDescription = description },
    )
    Text(stringResource(Res.string.calibration_sampling_progress, seconds, total, step.count), style = MaterialTheme.typography.bodyMedium)
    Buttons(onCancel = events::onCancel)
}

@Composable
private fun ResultStep(state: CalibrationUiState, step: CalibrationStep.Result, events: CalibrationEvents) {
    StepTitle(stringResource(Res.string.calibration_result_title))
    val resolution = if (state.angular) {
        stringResource(Res.string.calibration_result_angular, step.resolutionText, step.perCountText)
    } else {
        stringResource(Res.string.calibration_result_linear, step.resolutionText, step.perCountText)
    }
    Text(resolution, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag(CALIBRATION_RESULT_TAG))
    StatusLine(
        if (step.result.reversed) StatusKind.Warning else StatusKind.Success,
        stringResource(if (step.result.reversed) Res.string.calibration_result_direction_reversed else Res.string.calibration_result_direction_normal),
    )
    Text(stringResource(Res.string.calibration_result_apply_note, state.inputName.resolve()), style = MaterialTheme.typography.bodyMedium)
    state.saveError?.let { error -> StatusLine(StatusKind.Error, error.resolve()) }
    val target = LocalTouchTargets.current.button
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
        OutlinedButton(
            onClick = events::onDiscard,
            colors = AccentButtons.outlined(),
            modifier = Modifier.heightIn(min = target).testTag(CALIBRATION_DISCARD_TAG),
        ) { Text(stringResource(Res.string.calibration_discard)) }
        Button(
            onClick = events::onApply,
            enabled = !state.saving,
            modifier = Modifier.heightIn(min = target).testTag(CALIBRATION_APPLY_TAG),
            colors = AccentButtons.filled(),
        ) { Text(stringResource(Res.string.calibration_apply)) }
    }
}

@Composable
private fun Buttons(
    onCancel: () -> Unit,
    cancelText: String = stringResource(Res.string.frame_cancel),
    continueEnabled: Boolean = false,
    onContinue: (() -> Unit)? = null,
) {
    val target = LocalTouchTargets.current.button
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
        TextButton(onClick = onCancel, colors = AccentButtons.text(), modifier = Modifier.heightIn(min = target)) { Text(cancelText) }
        if (onContinue != null) {
            Button(
                onClick = onContinue,
                enabled = continueEnabled,
                modifier = Modifier.heightIn(min = target).testTag(CALIBRATION_CONTINUE_TAG),
                colors = AccentButtons.filled(),
            ) { Text(stringResource(Res.string.calibration_continue)) }
        }
    }
}

@Composable
private fun ProblemDialog(problem: CalibrationProblem, events: CalibrationEvents) {
    val color = if (problem.warning) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
    AlertDialog(
        onDismissRequest = events::onProblemDismiss,
        icon = { Icon(painterResource(if (problem.warning) Res.drawable.ic_warning else Res.drawable.ic_error), contentDescription = null, tint = color) },
        title = { Text(problem.title.resolve()) },
        text = { Text(problem.text.resolve(), style = MaterialTheme.typography.bodyLarge) },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = events::onProblemRetry, colors = AccentButtons.text(), modifier = Modifier.testTag(CALIBRATION_RETRY_TAG)) {
                    Text(stringResource(Res.string.calibration_retry))
                }
                if (problem.canContinue) {
                    TextButton(onClick = events::onProblemContinue, colors = AccentButtons.text(), modifier = Modifier.testTag(CALIBRATION_PROBLEM_CONTINUE_TAG)) {
                        Text(stringResource(Res.string.calibration_continue))
                    }
                }
            }
        },
        dismissButton = if (problem.canCancel) {
            { TextButton(onClick = events::onCancel, colors = AccentButtons.text()) { Text(stringResource(Res.string.frame_cancel)) } }
        } else {
            null
        },
    )
}
