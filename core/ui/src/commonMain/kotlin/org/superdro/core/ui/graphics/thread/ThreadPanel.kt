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
import org.superdro.core.functions.thread.ThreadCutStatus
import org.superdro.core.resources.Res
import org.superdro.core.resources.graphics_cancel
import org.superdro.core.resources.graphics_status_clear
import org.superdro.core.resources.graphics_status_in_cut
import org.superdro.core.resources.graphics_status_past_limit
import org.superdro.core.resources.graphics_status_past_root
import org.superdro.core.resources.graphics_status_select_corner
import org.superdro.core.resources.graphics_thread_close
import org.superdro.core.resources.graphics_thread_create
import org.superdro.core.resources.graphics_thread_create_question
import org.superdro.core.resources.graphics_thread_cut_load
import org.superdro.core.resources.graphics_thread_cutting
import org.superdro.core.resources.graphics_thread_cutting_name
import org.superdro.core.resources.graphics_thread_designation
import org.superdro.core.resources.graphics_thread_external
import org.superdro.core.resources.graphics_thread_infeed
import org.superdro.core.resources.graphics_thread_internal
import org.superdro.core.resources.graphics_thread_last_pass
import org.superdro.core.resources.graphics_thread_none
import org.superdro.core.resources.graphics_thread_pending
import org.superdro.core.resources.graphics_thread_ratio
import org.superdro.core.resources.graphics_thread_row
import org.superdro.core.resources.graphics_thread_select
import org.superdro.core.resources.ic_check
import org.superdro.core.resources.ic_close
import org.superdro.core.resources.ic_error
import org.superdro.core.resources.ic_info
import org.superdro.core.resources.ic_warning
import org.superdro.core.ui.theme.AccentButtons

/** The test tag of the thread cut panel. */
const val THREAD_PANEL_TAG: String = "graphical-thread-panel"

/** The test tag of a thread row of the chooser: `graphical-thread-<id>`. */
fun threadRowTag(id: String): String = "graphical-thread-$id"

/** The events of the thread cut panel. */
interface ThreadPanelEvents {
    /** A tap on a thread row of the chooser: arm it. */
    fun onThreadSelected(id: String)

    /** "Close thread cutting". */
    fun onThreadClose()
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
                is ThreadPanelUiState.Armed -> ArmedContent(state)
            }
        }
    }
}

@Composable
private fun ArmedContent(state: ThreadPanelUiState.Armed) {
    val (icon, color) = statusLook(state.status)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(statusText(state.status)), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
    }
    val pending = stringResource(Res.string.graphics_thread_pending)
    ValueLine(stringResource(Res.string.graphics_thread_last_pass), state.lastPass ?: pending)
    ValueLine(stringResource(Res.string.graphics_thread_infeed), state.infeed ?: pending)
    ValueLine(
        stringResource(Res.string.graphics_thread_cut_load),
        state.cutLoad?.let { ratio -> stringResource(Res.string.graphics_thread_ratio, ratio) } ?: pending,
    )
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
