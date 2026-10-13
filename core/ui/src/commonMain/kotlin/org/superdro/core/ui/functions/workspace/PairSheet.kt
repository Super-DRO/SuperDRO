package org.superdro.core.ui.functions.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.functions.transform.GuidePolicy
import org.superdro.core.resources.Res
import org.superdro.core.resources.fn_cancel
import org.superdro.core.resources.fn_close
import org.superdro.core.resources.fn_delete
import org.superdro.core.resources.ic_delete
import org.superdro.core.resources.ic_open_with
import org.superdro.core.resources.ic_rotate_right
import org.superdro.core.resources.ic_warning
import org.superdro.core.resources.ws_pair_angle
import org.superdro.core.resources.ws_pair_delete_text
import org.superdro.core.resources.ws_pair_delete_title
import org.superdro.core.resources.ws_pair_distance
import org.superdro.core.resources.ws_pair_keep
import org.superdro.core.resources.ws_pair_move
import org.superdro.core.resources.ws_pair_move_text
import org.superdro.core.resources.ws_pair_points
import org.superdro.core.resources.ws_pair_rotate
import org.superdro.core.resources.ws_pair_spindle
import org.superdro.core.resources.ws_pair_spindle_text
import org.superdro.core.resources.ws_pair_spindle_uncertain
import org.superdro.core.resources.ws_pair_title
import org.superdro.core.resources.ws_pair_too_close
import org.superdro.core.resources.ws_pair_yes
import org.superdro.core.ui.adaptive.AdaptiveSheet
import org.superdro.core.ui.functions.common.ErrorBlock
import org.superdro.core.ui.functions.common.ErrorBox
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.LocalTouchTargets

/** The test tags of the pair sheet. */
object PairTags {
    const val MOVE = "ws-pair-move"
    const val SPINDLE = "ws-pair-spindle"
    const val CONFIRM = "ws-pair-confirm"
    const val DELETE_B = "ws-pair-delete-b"
    const val KEEP_B = "ws-pair-keep-b"
}

/** The events of the pair sheet. */
interface PairEvents {
    fun onMove()
    fun onMoveConfirm()
    fun onDeleteB(delete: Boolean)
    fun onSpindle()
    fun onRotateConfirm()
    fun onGuideAnswer(policy: GuidePolicy?)
    fun onQuestionCancel()
    fun onDismiss()
}

/**
 * The sheet of the sub-datum pair commands:
 * the distance and the line angle of A and B, "Align A to B", and "Align B to Spindle (Pivot A)".
 */
@Composable
fun PairSheet(state: PairUiState, events: PairEvents) {
    val targets = LocalTouchTargets.current
    AdaptiveSheet(onDismissRequest = events::onDismiss, title = stringResource(Res.string.ws_pair_title)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.nameA.isNotEmpty()) {
                Text(stringResource(Res.string.ws_pair_points, state.nameA, state.nameB), style = MaterialTheme.typography.titleMedium)
                state.distance?.let { Text(stringResource(Res.string.ws_pair_distance, it.resolve()), style = MaterialTheme.typography.bodyLarge) }
                Text(stringResource(Res.string.ws_pair_angle, state.angleDegrees), style = MaterialTheme.typography.bodyLarge)
                if (state.tooClose) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(painterResource(Res.drawable.ic_warning), contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                        Text(
                            stringResource(Res.string.ws_pair_too_close, state.toleranceDegrees ?: "—"),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            state.error?.let { ErrorBox(ErrorBlock(null, listOf(it))) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(targets.gap, Alignment.End)) {
                TextButton(onClick = events::onDismiss, colors = AccentButtons.text(), modifier = Modifier.heightIn(min = targets.button)) {
                    Text(stringResource(Res.string.fn_close))
                }
                if (state.spindleVisible) {
                    FilledTonalButton(
                        onClick = events::onSpindle,
                        enabled = state.spindleEnabled && state.error == null && !state.busy,
                        modifier = Modifier.heightIn(min = targets.button).testTag(PairTags.SPINDLE),
                    ) {
                        Icon(painterResource(Res.drawable.ic_rotate_right), contentDescription = null)
                        Text("  " + stringResource(Res.string.ws_pair_spindle))
                    }
                }
                FilledTonalButton(
                    onClick = events::onMove,
                    enabled = state.nameA.isNotEmpty() && state.error == null && !state.busy,
                    modifier = Modifier.heightIn(min = targets.button).testTag(PairTags.MOVE),
                ) {
                    Icon(painterResource(Res.drawable.ic_open_with), contentDescription = null)
                    Text("  " + stringResource(Res.string.ws_pair_move))
                }
            }
        }
    }
    when (val question = state.question) {
        is PairQuestion.Move -> AlertDialog(
            onDismissRequest = events::onQuestionCancel,
            title = { Text(stringResource(Res.string.ws_pair_move)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(Res.string.ws_pair_move_text, state.nameA, state.nameB))
                    question.shifts.forEach { line -> Text(line.resolve(), style = MaterialTheme.typography.bodyLarge) }
                }
            },
            confirmButton = {
                TextButton(onClick = events::onMoveConfirm, colors = AccentButtons.text(), modifier = Modifier.testTag(PairTags.CONFIRM)) {
                    Text(stringResource(Res.string.ws_pair_yes))
                }
            },
            dismissButton = { TextButton(onClick = events::onQuestionCancel, colors = AccentButtons.text()) { Text(stringResource(Res.string.fn_cancel)) } },
        )
        PairQuestion.DeleteB -> AlertDialog(
            onDismissRequest = { events.onDeleteB(false) },
            icon = { Icon(painterResource(Res.drawable.ic_delete), contentDescription = null) },
            title = { Text(stringResource(Res.string.ws_pair_delete_title, state.nameB)) },
            text = { Text(stringResource(Res.string.ws_pair_delete_text, state.nameB)) },
            confirmButton = {
                TextButton(onClick = { events.onDeleteB(true) }, colors = AccentButtons.text(), modifier = Modifier.testTag(PairTags.DELETE_B)) {
                    Text(stringResource(Res.string.fn_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { events.onDeleteB(false) }, colors = AccentButtons.text(), modifier = Modifier.testTag(PairTags.KEEP_B)) {
                    Text(stringResource(Res.string.ws_pair_keep))
                }
            },
        )
        is PairQuestion.Rotate -> AlertDialog(
            onDismissRequest = events::onQuestionCancel,
            title = { Text(stringResource(Res.string.ws_pair_spindle)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(Res.string.ws_pair_spindle_text, question.thetaDegrees, state.nameA, state.nameB, question.land.resolve()))
                    question.uncertainDegrees?.let { degrees ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(painterResource(Res.drawable.ic_warning), contentDescription = null, modifier = Modifier.size(20.dp))
                            Text(stringResource(Res.string.ws_pair_spindle_uncertain, degrees))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = events::onRotateConfirm, colors = AccentButtons.text(), modifier = Modifier.testTag(PairTags.CONFIRM)) {
                    Text(stringResource(Res.string.ws_pair_rotate))
                }
            },
            dismissButton = { TextButton(onClick = events::onQuestionCancel, colors = AccentButtons.text()) { Text(stringResource(Res.string.fn_cancel)) } },
        )
        is PairQuestion.Guides -> GuideQuestion(question.count, events::onGuideAnswer)
        null -> Unit
    }
}
