package org.superdro.core.ui.functions.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.model.Workspace
import org.superdro.core.resources.Res
import org.superdro.core.resources.fn_blocked_message
import org.superdro.core.resources.fn_blocker_no_workspace
import org.superdro.core.resources.fn_blocker_thread
import org.superdro.core.resources.fn_close
import org.superdro.core.resources.fn_not_available_title
import org.superdro.core.resources.ic_info
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.theme.AccentButtons

/** The test tag of the dialog "Not Available Right Now". */
const val BLOCKED_DIALOG_TAG: String = "functions-blocked-dialog"

/**
 * The sub-datum blocker: no new sub-datum while a
 * thread is armed. The "+" button and the pattern functions (hole circle, arbitrary hole circle,
 * hole grid, equidistant array, arc cut) check it first. A blocked function shows "Not Available
 * Right Now" and "New sub-datums cannot be added while thread cutting is active."
 */
object SubDatumBlocker {

    /** The title of the dialog: "Not Available Right Now". */
    val title: UiText = UiText.of(Res.string.fn_not_available_title)

    /**
     * Gives the message of the blocker of [workspace], or null when new sub-datums are permitted.
     * No workspace gives "no workspace". An armed thread gives "thread cutting".
     */
    fun message(workspace: Workspace?): UiText? = when {
        workspace == null -> UiText.of(Res.string.fn_blocked_message, UiText.of(Res.string.fn_blocker_no_workspace))
        workspace.armedThreadId != null -> UiText.of(Res.string.fn_blocked_message, UiText.of(Res.string.fn_blocker_thread))
        else -> null
    }

    /** True when [workspace] permits new sub-datums. */
    fun permits(workspace: Workspace?): Boolean = message(workspace) == null
}

/** The dialog "Not Available Right Now" with [message]. The icon and the text tell the state. */
@Composable
fun BlockedDialog(message: UiText, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(Res.drawable.ic_info), contentDescription = null) },
        title = { Text(SubDatumBlocker.title.resolve()) },
        text = { Text(message.resolve()) },
        confirmButton = {
            TextButton(onClick = onDismiss, colors = AccentButtons.text()) { Text(stringResource(Res.string.fn_close)) }
        },
        modifier = Modifier.testTag(BLOCKED_DIALOG_TAG),
    )
}
