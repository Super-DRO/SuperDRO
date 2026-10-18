package org.superdro.core.ui.graphics.thread

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.functions.thread.PitchCheckResult
import org.superdro.core.functions.thread.ThreadCutStatus
import org.superdro.core.functions.thread.ThreadCutWarning
import org.superdro.core.functions.thread.ThreadDialRule
import org.superdro.core.resources.Res
import org.superdro.core.resources.graphics_cancel
import org.superdro.core.resources.graphics_status_clear
import org.superdro.core.resources.graphics_status_in_cut
import org.superdro.core.resources.graphics_status_past_limit
import org.superdro.core.resources.graphics_status_past_root
import org.superdro.core.resources.graphics_status_select_corner
import org.superdro.core.resources.graphics_thread_close
import org.superdro.core.resources.graphics_thread_compound_step
import org.superdro.core.resources.graphics_thread_create
import org.superdro.core.resources.graphics_thread_create_question
import org.superdro.core.resources.graphics_thread_cut_load
import org.superdro.core.resources.graphics_thread_cutting
import org.superdro.core.resources.graphics_thread_cutting_name
import org.superdro.core.resources.graphics_thread_designation
import org.superdro.core.resources.graphics_thread_dial_any_line
import org.superdro.core.resources.graphics_thread_dial_any_numbered
import org.superdro.core.resources.graphics_thread_dial_any_point
import org.superdro.core.resources.graphics_thread_dial_keep_closed
import org.superdro.core.resources.graphics_thread_dial_opposite_numbered
import org.superdro.core.resources.graphics_thread_dial_same_line
import org.superdro.core.resources.graphics_thread_external
import org.superdro.core.resources.graphics_thread_infeed
import org.superdro.core.resources.graphics_thread_internal
import org.superdro.core.resources.graphics_thread_last_pass
import org.superdro.core.resources.graphics_thread_max_rpm
import org.superdro.core.resources.graphics_thread_none
import org.superdro.core.resources.graphics_thread_pass_done
import org.superdro.core.resources.graphics_thread_pass_target
import org.superdro.core.resources.graphics_thread_pending
import org.superdro.core.resources.graphics_thread_pitch_mismatch
import org.superdro.core.resources.graphics_thread_pitch_ok
import org.superdro.core.resources.graphics_thread_ratio
import org.superdro.core.resources.graphics_thread_reset_passes
import org.superdro.core.resources.graphics_thread_row
import org.superdro.core.resources.graphics_thread_select
import org.superdro.core.resources.graphics_thread_speed_too_fast
import org.superdro.core.resources.graphics_thread_start_chip
import org.superdro.core.resources.graphics_thread_start_label
import org.superdro.core.resources.graphics_thread_start_offset
import org.superdro.core.resources.graphics_thread_target_diameter
import org.superdro.core.resources.graphics_thread_warn_x_not_referenced
import org.superdro.core.resources.ic_check
import org.superdro.core.resources.ic_close
import org.superdro.core.resources.ic_error
import org.superdro.core.resources.ic_info
import org.superdro.core.resources.ic_warning
import org.superdro.core.ui.theme.AccentButtons

/** The test tag of the thread cut panel. */
const val THREAD_PANEL_TAG: String = "graphical-thread-panel"

/** The test tag of the reset passes button in the thread cut panel. */
const val THREAD_RESET_PASSES_TAG: String = "graphical-thread-reset-passes"

/** The test tag of a thread row of the chooser: `graphical-thread-<id>`. */
fun threadRowTag(id: String): String = "graphical-thread-$id"

/** The test tag of a start selector chip in the thread cut panel: `graphical-thread-start-<startNumber>`. */
fun threadStartTag(startNumber: Int): String = "graphical-thread-start-$startNumber"

/** The events of the thread cut panel. */
interface ThreadPanelEvents {
    /** A tap on a thread row of the chooser: arm it. */
    fun onThreadSelected(id: String)

    /** "Close thread cutting". */
    fun onThreadClose()

    /** Clears the recorded passes of the armed thread. */
    fun onResetPasses() = Unit

    /** Selects the active start [startNumber] (1-based) of a multi-start thread. */
    fun onSelectStart(startNumber: Int) = Unit
}

/**
 * The thread cut panel of the graphical view: the
 * chooser, or the live values of the armed thread with the retract light. Each state has an icon
 * and a text.
 */
@Composable
fun ThreadPanel(state: ThreadPanelUiState, events: ThreadPanelEvents, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.testTag(THREAD_PANEL_TAG),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f),
        tonalElevation = 3.dp,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(titleOf(state), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                IconButton(onClick = events::onThreadClose) {
                    Icon(painterResource(Res.drawable.ic_close), contentDescription = stringResource(Res.string.graphics_thread_close))
                }
            }
            when (state) {
                ThreadPanelUiState.NoThreads -> Text(
                    stringResource(Res.string.graphics_thread_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                is ThreadPanelUiState.Chooser -> state.threads.forEach { thread ->
                    Text(
                        rowOf(thread),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { events.onThreadSelected(thread.id) }
                            .padding(vertical = 12.dp).testTag(threadRowTag(thread.id)),
                    )
                }
                is ThreadPanelUiState.Armed -> ArmedContent(state, events)
            }
        }
    }
}

@Composable
private fun ArmedContent(state: ThreadPanelUiState.Armed, events: ThreadPanelEvents) {
    val (icon, color) = statusLook(state.status)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(statusText(state.status)), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
    }
    state.warnings.forEach { warning ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(Res.drawable.ic_warning),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(warningText(warning)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    if (state.totalStarts > 1) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(Res.string.graphics_thread_start_label, state.activeStart, state.totalStarts),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (state.startZOffset != null && state.startAngleDeg != null) {
                    Text(
                        stringResource(Res.string.graphics_thread_start_offset, state.startZOffset, state.startAngleDeg),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (start in 1..state.totalStarts) {
                    FilterChip(
                        selected = start == state.activeStart,
                        onClick = {
                            state.onSelectStart(start)
                            events.onSelectStart(start)
                        },
                        label = { Text(stringResource(Res.string.graphics_thread_start_chip, start)) },
                        modifier = Modifier.testTag(threadStartTag(start)),
                    )
                }
            }
        }
    }
    if (state.totalPasses > 0) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val passHeader = state.nextPass?.let { pass ->
                stringResource(Res.string.graphics_thread_pass_target, pass.number, state.totalPasses)
            } ?: stringResource(Res.string.graphics_thread_pass_done, state.totalPasses)
            Text(
                passHeader,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    state.onResetPasses()
                    events.onResetPasses()
                },
                colors = AccentButtons.text(),
                modifier = Modifier.testTag(THREAD_RESET_PASSES_TAG),
            ) {
                Text(stringResource(Res.string.graphics_thread_reset_passes))
            }
        }
        state.nextTargetDiameter?.let { target ->
            ValueLine(stringResource(Res.string.graphics_thread_target_diameter), target)
        }
        state.nextCompoundStep?.let { step ->
            ValueLine(stringResource(Res.string.graphics_thread_compound_step), step)
        }
    }
    val pending = stringResource(Res.string.graphics_thread_pending)
    ValueLine(stringResource(Res.string.graphics_thread_last_pass), state.lastPass ?: pending)
    ValueLine(stringResource(Res.string.graphics_thread_infeed), state.infeed ?: pending)
    ValueLine(
        stringResource(Res.string.graphics_thread_cut_load),
        state.cutLoad?.let { ratio -> stringResource(Res.string.graphics_thread_ratio, ratio) } ?: pending,
    )
    if (state.leadscrewPitchUm != null || state.dialRule != ThreadDialRule.KeepHalfNutClosed) {
        Text(
            stringResource(dialRuleText(state.dialRule)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    state.safeSpeed?.let { speed ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(Res.string.graphics_thread_max_rpm, speed.maxRpm),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = if (speed.reactionTooFast) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (speed.reactionTooFast) {
                Text(
                    stringResource(Res.string.graphics_thread_speed_too_fast),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
    state.pitchCheck?.let { check ->
        val isMatch = check is PitchCheckResult.Match
        Text(
            stringResource(if (isMatch) Res.string.graphics_thread_pitch_ok else Res.string.graphics_thread_pitch_mismatch),
            style = MaterialTheme.typography.bodySmall,
            color = if (isMatch) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun ValueLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun titleOf(state: ThreadPanelUiState): String = when (state) {
    ThreadPanelUiState.NoThreads -> stringResource(Res.string.graphics_thread_cutting)
    is ThreadPanelUiState.Chooser -> stringResource(Res.string.graphics_thread_select)
    is ThreadPanelUiState.Armed -> stringResource(Res.string.graphics_thread_cutting_name, designationOf(state.thread))
}

@Composable
private fun designationOf(thread: ThreadChoice): String =
    stringResource(
        Res.string.graphics_thread_designation,
        thread.designation,
        stringResource(if (thread.internal) Res.string.graphics_thread_internal else Res.string.graphics_thread_external),
    )

@Composable
private fun rowOf(thread: ThreadChoice): String =
    if (thread.tag.isBlank()) designationOf(thread) else stringResource(Res.string.graphics_thread_row, thread.tag, designationOf(thread))

/** Gives the text of the retract light. */
fun statusText(status: ThreadCutStatus): StringResource = when (status) {
    ThreadCutStatus.SelectFinishCorner -> Res.string.graphics_status_select_corner
    ThreadCutStatus.ClearOfCrests -> Res.string.graphics_status_clear
    ThreadCutStatus.InTheCut -> Res.string.graphics_status_in_cut
    ThreadCutStatus.PastTheRoot -> Res.string.graphics_status_past_root
    ThreadCutStatus.PastRetractLimit -> Res.string.graphics_status_past_limit
}

/** Gives the text of a thread cutting warning. */
fun warningText(warning: ThreadCutWarning): StringResource = when (warning) {
    ThreadCutWarning.XNotReferenced -> Res.string.graphics_thread_warn_x_not_referenced
}

/** Gives the text of a threading dial engagement rule. */
fun dialRuleText(rule: ThreadDialRule): StringResource = when (rule) {
    ThreadDialRule.KeepHalfNutClosed -> Res.string.graphics_thread_dial_keep_closed
    ThreadDialRule.AnyPoint -> Res.string.graphics_thread_dial_any_point
    ThreadDialRule.AnyLine -> Res.string.graphics_thread_dial_any_line
    ThreadDialRule.AnyNumberedLine -> Res.string.graphics_thread_dial_any_numbered
    ThreadDialRule.OppositeNumberedLines -> Res.string.graphics_thread_dial_opposite_numbered
    ThreadDialRule.SameLineOnly -> Res.string.graphics_thread_dial_same_line
}

@Composable
private fun statusLook(status: ThreadCutStatus): Pair<DrawableResource, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (status) {
        ThreadCutStatus.SelectFinishCorner -> Res.drawable.ic_info to scheme.onSurfaceVariant
        ThreadCutStatus.ClearOfCrests -> Res.drawable.ic_check to scheme.tertiary
        ThreadCutStatus.InTheCut -> Res.drawable.ic_warning to scheme.secondary
        ThreadCutStatus.PastTheRoot, ThreadCutStatus.PastRetractLimit -> Res.drawable.ic_error to scheme.error
    }
}

/** The question "There are no threads in this workspace. Do you want to create one?". */
@Composable
fun CreateThreadQuestion(onCreate: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(stringResource(Res.string.graphics_thread_create_question)) },
        confirmButton = { TextButton(onClick = onCreate, colors = AccentButtons.text()) { Text(stringResource(Res.string.graphics_thread_create)) } },
        dismissButton = { TextButton(onClick = onDismiss, colors = AccentButtons.text()) { Text(stringResource(Res.string.graphics_cancel)) } },
    )
}
