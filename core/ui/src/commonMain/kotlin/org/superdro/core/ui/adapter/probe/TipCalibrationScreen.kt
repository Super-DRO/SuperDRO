package org.superdro.core.ui.adapter.probe

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.functions.tipcal.ClockPosition
import org.superdro.core.functions.tipcal.ResultCheckKind
import org.superdro.core.functions.tipcal.TipCalibrationError
import org.superdro.core.functions.tipcal.TipCalibrationReadiness
import org.superdro.core.functions.tipcal.TouchCollection
import org.superdro.core.functions.tipcal.TouchPrompt
import org.superdro.core.resources.Res
import org.superdro.core.resources.frame_cancel
import org.superdro.core.resources.frame_close
import org.superdro.core.resources.ic_check
import org.superdro.core.resources.ic_radio_unchecked
import org.superdro.core.resources.probelib_field_units
import org.superdro.core.resources.probelib_tip_apply
import org.superdro.core.resources.probelib_tip_asymmetry
import org.superdro.core.resources.probelib_tip_continue
import org.superdro.core.resources.probelib_tip_discard
import org.superdro.core.resources.probelib_tip_error_fit_text
import org.superdro.core.resources.probelib_tip_error_fit_title
import org.superdro.core.resources.probelib_tip_error_gauge_text
import org.superdro.core.resources.probelib_tip_error_gauge_title
import org.superdro.core.resources.probelib_tip_error_no_size_text
import org.superdro.core.resources.probelib_tip_error_no_size_title
import org.superdro.core.resources.probelib_tip_error_scattered_text
import org.superdro.core.resources.probelib_tip_error_scattered_title
import org.superdro.core.resources.probelib_tip_error_unlikely_text
import org.superdro.core.resources.probelib_tip_error_unlikely_title
import org.superdro.core.resources.probelib_tip_gauge_field
import org.superdro.core.resources.probelib_tip_gauge_text
import org.superdro.core.resources.probelib_tip_gauge_title
import org.superdro.core.resources.probelib_tip_interrupted
import org.superdro.core.resources.probelib_tip_marker
import org.superdro.core.resources.probelib_tip_nominal_warning
import org.superdro.core.resources.probelib_tip_not_calibratable_text
import org.superdro.core.resources.probelib_tip_not_calibratable_title
import org.superdro.core.resources.probelib_tip_not_connected_text
import org.superdro.core.resources.probelib_tip_not_connected_title
import org.superdro.core.resources.probelib_tip_no_xy_text
import org.superdro.core.resources.probelib_tip_no_xy_title
import org.superdro.core.resources.probelib_tip_profile_gone_text
import org.superdro.core.resources.probelib_tip_profile_gone_title
import org.superdro.core.resources.probelib_tip_progress
import org.superdro.core.resources.probelib_tip_prompt_first
import org.superdro.core.resources.probelib_tip_prompt_next
import org.superdro.core.resources.probelib_tip_recenter_text
import org.superdro.core.resources.probelib_tip_recenter_title
import org.superdro.core.resources.probelib_tip_refused_touch
import org.superdro.core.resources.probelib_tip_result_gauge
import org.superdro.core.resources.probelib_tip_result_repeatability
import org.superdro.core.resources.probelib_tip_result_tip
import org.superdro.core.resources.probelib_tip_result_title
import org.superdro.core.resources.probelib_tip_result_touches
import org.superdro.core.resources.probelib_tip_resume_start_again
import org.superdro.core.resources.probelib_tip_resume_text
import org.superdro.core.resources.probelib_tip_resume_title
import org.superdro.core.resources.probelib_tip_resume_yes
import org.superdro.core.resources.probelib_tip_retry
import org.superdro.core.resources.probelib_tip_steps
import org.superdro.core.resources.probelib_tip_touch_title
import org.superdro.core.resources.route_tip_calibration
import org.superdro.core.ui.adapter.common.ChoiceRow
import org.superdro.core.ui.adapter.common.NumberField
import org.superdro.core.ui.adapter.common.SectionTitle
import org.superdro.core.ui.adapter.common.StatusKind
import org.superdro.core.ui.adapter.common.StatusLine
import org.superdro.core.ui.adapter.common.ValueRow
import org.superdro.core.ui.navigation.ScreenScaffold
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.LocalTouchTargets
import org.superdro.core.ui.theme.accentColor

/** The events of the tip calibration screen. */
interface TipCalibrationEvents {
    fun onBack()
    fun onGauge(text: String)
    fun onGaugeMetric(metric: Boolean)
    fun onContinueGauge()
    fun onContinueTouches()
    fun onContinueRecenter()
    fun onRetry()
    fun onCancel()
    fun onApply()
    fun onDiscard()
    fun onResume()
    fun onStartAgain()
}

/** The test tag of the "Continue" button of each step. */
const val TIP_CONTINUE_TAG: String = "tip-continue"

/** The test tag of the "Apply" button. */
const val TIP_APPLY_TAG: String = "tip-apply"

/** The test tag of the "Retry" button of the error dialog. */
const val TIP_RETRY_TAG: String = "tip-retry"

/**
 * The tip calibration screen: the ring gauge, the touches
 * at 9, 3, 12, and 6 o'clock with "Back to the middle" before 12 o'clock, and the result with
 * "Apply" and "Discard".
 */
@Composable
fun TipCalibrationScreen(state: TipCalibrationUiState, events: TipCalibrationEvents) {
    ScreenScaffold(title = stringResource(Res.string.route_tip_calibration), onBack = events::onBack) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 720.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
            ) {
                if (!state.loaded) return@Column
                Text(state.profileName, style = MaterialTheme.typography.titleMedium)
                StepLine(state)
                if (state.resumeRefused) StatusLine(StatusKind.Info, stringResource(Res.string.probelib_tip_interrupted))
                when (state.step) {
                    TipStep.Gauge -> GaugeStep(state, events)
                    TipStep.Touches -> TouchStep(state, events)
                    TipStep.Recenter -> RecenterStep(state, events)
                    TipStep.Result -> ResultStep(state, events)
                }
                Spacer(Modifier.size(24.dp))
            }
        }
    }
    state.readiness?.let { readiness -> ReadinessDialog(readiness, events::onCancel) }
    state.resumePositions?.let { count ->
        AlertDialog(
            onDismissRequest = events::onStartAgain,
            title = { Text(stringResource(Res.string.probelib_tip_resume_title)) },
            text = { Text(stringResource(Res.string.probelib_tip_resume_text, count)) },
            confirmButton = { TextButton(colors = AccentButtons.text(), onClick = events::onResume) { Text(stringResource(Res.string.probelib_tip_resume_yes)) } },
            dismissButton = { TextButton(colors = AccentButtons.text(), onClick = events::onStartAgain) { Text(stringResource(Res.string.probelib_tip_resume_start_again)) } },
        )
    }
    state.error?.let { error -> ErrorDialog(error, state.metric, events) }
}

@Composable
private fun StepLine(state: TipCalibrationUiState) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        ClockPosition.procedureOrder.forEach { clock ->
            val done = clock in state.captured
            val current = clock == state.clock
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(if (done) Res.drawable.ic_check else Res.drawable.ic_radio_unchecked),
                    contentDescription = null,
                    tint = if (done || current) accentColor() else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    stringResource(Res.string.probelib_tip_steps, clock.hour),
                    style = if (current) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                    color = if (current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ContinueButton(enabled: Boolean, onClick: () -> Unit, label: String = stringResource(Res.string.probelib_tip_continue)) {
    Spacer(Modifier.size(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Button(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = LocalTouchTargets.current.button).testTag(TIP_CONTINUE_TAG), colors = AccentButtons.filled()) {
            Text(label)
        }
    }
}

@Composable
private fun GaugeStep(state: TipCalibrationUiState, events: TipCalibrationEvents) {
    SectionTitle(stringResource(Res.string.probelib_tip_gauge_title))
    Text(stringResource(Res.string.probelib_tip_gauge_text), style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.size(8.dp))
    NumberField(
        label = stringResource(Res.string.probelib_tip_gauge_field),
        value = state.gaugeText,
        onValueChange = events::onGauge,
        suffix = stringResource(ProbeLengths.unit(state.gaugeMetric)),
        tag = "tip-gauge",
    )
    ChoiceRow(
        label = stringResource(Res.string.probelib_field_units),
        options = listOf(true, false),
        selected = state.gaugeMetric,
        optionLabel = { metric -> stringResource(ProbeLengths.unit(metric)) },
        onSelect = events::onGaugeMetric,
    )
    ContinueButton(enabled = state.gaugeText.isNotBlank(), onClick = events::onContinueGauge)
}

@Composable
private fun TouchStep(state: TipCalibrationUiState, events: TipCalibrationEvents) {
    val clock = state.clock ?: return
    SectionTitle(stringResource(Res.string.probelib_tip_touch_title, clock.hour))
    Text(
        stringResource(if (state.prompt == TouchPrompt.FirstPosition) Res.string.probelib_tip_prompt_first else Res.string.probelib_tip_prompt_next),
        style = MaterialTheme.typography.bodyLarge,
    )
    Spacer(Modifier.size(12.dp))
    val progress = (state.touches.toFloat() / TouchCollection.PROGRESS_MAXIMUM).coerceIn(0f, 1f)
    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(8.dp), color = accentColor())
    StatusLine(
        if (state.canContinue) StatusKind.Success else StatusKind.Progress,
        stringResource(Res.string.probelib_tip_progress, state.touches, TouchCollection.MINIMUM_TOUCHES),
        tag = "tip-progress",
    )
    if (state.touchRefused) StatusLine(StatusKind.Error, stringResource(Res.string.probelib_tip_refused_touch), tag = "tip-refused")
    ContinueButton(enabled = state.canContinue, onClick = events::onContinueTouches)
}

@Composable
private fun RecenterStep(state: TipCalibrationUiState, events: TipCalibrationEvents) {
    SectionTitle(stringResource(Res.string.probelib_tip_recenter_title))
    Text(stringResource(Res.string.probelib_tip_recenter_text), style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.size(16.dp))
    val marker = state.marker
    val live = state.liveXUm
    if (marker != null) {
        val offset = live?.let(marker::markerOffsetUm) ?: 0.0
        val fraction = if (marker.rangeUm > 0.0) (offset / marker.rangeUm) else 0.0
        val track = MaterialTheme.colorScheme.outline
        val center = MaterialTheme.colorScheme.onSurface
        val dot = accentColor()
        val hashFraction = if (marker.rangeUm > 0.0) marker.hashMarkUm / marker.rangeUm else 0.0
        val description = stringResource(
            Res.string.probelib_tip_marker,
            ProbeLengths.text(offset, state.metric),
            stringResource(ProbeLengths.unit(state.metric)),
        )
        Canvas(Modifier.fillMaxWidth().height(64.dp).semantics { contentDescription = description }) {
            val mid = size.width / 2f
            val y = size.height / 2f
            val half = size.width / 2f - 16.dp.toPx()
            drawLine(track, Offset(mid - half, y), Offset(mid + half, y), strokeWidth = 4.dp.toPx())
            val hash = (half * hashFraction.toFloat())
            drawLine(center, Offset(mid - hash, y - 16.dp.toPx()), Offset(mid - hash, y + 16.dp.toPx()), strokeWidth = 2.dp.toPx())
            drawLine(center, Offset(mid + hash, y - 16.dp.toPx()), Offset(mid + hash, y + 16.dp.toPx()), strokeWidth = 2.dp.toPx())
            drawLine(center, Offset(mid, y - 24.dp.toPx()), Offset(mid, y + 24.dp.toPx()), strokeWidth = 3.dp.toPx())
            drawCircle(dot, radius = 12.dp.toPx(), center = Offset(mid + half * fraction.toFloat(), y))
        }
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    ContinueButton(enabled = true, onClick = events::onContinueRecenter)
}

@Composable
private fun ResultStep(state: TipCalibrationUiState, events: TipCalibrationEvents) {
    val result = state.result ?: return
    val unit = stringResource(ProbeLengths.unit(state.metric))
    fun length(um: Double) = ProbeLengths.text(um, state.metric)
    SectionTitle(stringResource(Res.string.probelib_tip_result_title))
    ValueRow(stringResource(Res.string.probelib_tip_result_tip), "${length(result.effectiveDiameterUm)} $unit", valueTag = "tip-result-diameter")
    ValueRow(stringResource(Res.string.probelib_tip_result_repeatability), "±${ProbeLengths.fineText(result.repeatabilityUm, state.metric)} $unit")
    ValueRow(stringResource(Res.string.probelib_tip_result_touches), result.touchCount.toString())
    ValueRow(stringResource(Res.string.probelib_tip_result_gauge), "${length(result.gaugeUm)} $unit")
    if (state.check == ResultCheckKind.NominalMismatch) {
        StatusLine(StatusKind.Warning, stringResource(Res.string.probelib_tip_nominal_warning, length(result.effectiveDiameterUm), length(state.nominalUm), unit))
    }
    result.asymmetry?.let { note ->
        StatusLine(
            StatusKind.Info,
            stringResource(Res.string.probelib_tip_asymmetry, length(note.diameterXUm), length(note.diameterYUm), ProbeLengths.fineText(note.halfDifferenceUm, state.metric), unit),
        )
    }
    Spacer(Modifier.size(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
        val target = LocalTouchTargets.current
        TextButton(onClick = events::onDiscard, colors = AccentButtons.text(), modifier = Modifier.heightIn(min = target.button)) {
            Text(stringResource(Res.string.probelib_tip_discard))
        }
        Button(onClick = events::onApply, modifier = Modifier.heightIn(min = target.button).testTag(TIP_APPLY_TAG), colors = AccentButtons.filled()) {
            Text(stringResource(Res.string.probelib_tip_apply))
        }
    }
}

@Composable
private fun ReadinessDialog(readiness: TipCalibrationReadiness, onClose: () -> Unit) {
    val (title, text) = when (readiness) {
        TipCalibrationReadiness.ProfileGone -> Res.string.probelib_tip_profile_gone_title to Res.string.probelib_tip_profile_gone_text
        TipCalibrationReadiness.NotCalibratable -> Res.string.probelib_tip_not_calibratable_title to Res.string.probelib_tip_not_calibratable_text
        TipCalibrationReadiness.NotConnected -> Res.string.probelib_tip_not_connected_title to Res.string.probelib_tip_not_connected_text
        TipCalibrationReadiness.NoXyAxes -> Res.string.probelib_tip_no_xy_title to Res.string.probelib_tip_no_xy_text
        TipCalibrationReadiness.Ready -> return
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(text)) },
        confirmButton = { TextButton(colors = AccentButtons.text(), onClick = onClose) { Text(stringResource(Res.string.frame_close)) } },
    )
}

@Composable
private fun ErrorDialog(error: TipErrorUi, metric: Boolean, events: TipCalibrationEvents) {
    val unit = stringResource(ProbeLengths.unit(metric))
    fun length(um: Double?) = um?.let { ProbeLengths.text(it, metric) } ?: "?"
    fun fine(um: Double?) = um?.let { ProbeLengths.fineText(it, metric) } ?: "?"
    val title: String
    val text: String
    when (error.error) {
        TipCalibrationError.GaugeUnusable -> {
            title = stringResource(Res.string.probelib_tip_error_gauge_title)
            text = stringResource(Res.string.probelib_tip_error_gauge_text)
        }
        TipCalibrationError.PositionScattered -> {
            title = stringResource(Res.string.probelib_tip_error_scattered_title)
            text = stringResource(Res.string.probelib_tip_error_scattered_text, fine(error.spreadUm), fine(error.limitUm), unit)
        }
        TipCalibrationError.FitFailed -> {
            title = stringResource(Res.string.probelib_tip_error_fit_title)
            text = stringResource(Res.string.probelib_tip_error_fit_text)
        }
        TipCalibrationError.ResultRefused -> {
            val result = error.result
            if (error.check == ResultCheckKind.TipHasNoSize) {
                title = stringResource(Res.string.probelib_tip_error_no_size_title)
                text = stringResource(Res.string.probelib_tip_error_no_size_text, length(result?.radiusUm?.times(2.0)), length(result?.gaugeUm), unit)
            } else {
                title = stringResource(Res.string.probelib_tip_error_unlikely_title)
                text = stringResource(Res.string.probelib_tip_error_unlikely_text, length(result?.effectiveDiameterUm), unit)
            }
        }
    }
    AlertDialog(
        onDismissRequest = events::onCancel,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(colors = AccentButtons.text(), onClick = events::onRetry, modifier = Modifier.testTag(TIP_RETRY_TAG)) { Text(stringResource(Res.string.probelib_tip_retry)) } },
        dismissButton = { TextButton(colors = AccentButtons.text(), onClick = events::onCancel) { Text(stringResource(Res.string.frame_cancel)) } },
    )
}
