package org.superdro.core.ui.settings.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.resources.Res
import org.superdro.core.resources.settings_cancel
import org.superdro.core.resources.settings_delete
import org.superdro.core.resources.settings_profiles_delete_text
import org.superdro.core.resources.settings_profiles_delete_title
import org.superdro.core.resources.settings_profiles_delete_tools
import org.superdro.core.resources.settings_profiles_keep_tools
import org.superdro.core.resources.settings_profiles_keep_tools_note
import org.superdro.core.resources.settings_profiles_tool_count
import org.superdro.core.store.DELETE_PROFILE_TOOLS_QUESTION
import org.superdro.core.store.DELETE_PROFILE_TOOLS_QUESTION_KEY
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.LocalTouchTargets

/** The test tags of the delete question. */
object DeleteProfileTags {
    const val DIALOG = "delete-profile"
    const val DELETE_TOOLS = "delete-profile-tools"
    const val KEEP_TOOLS = "delete-profile-keep-tools"
    const val DELETE = "delete-profile-confirm"
    const val QUESTION = "delete-profile-question"
}

/** The delete question: "Delete the tools of this profile too?", with the text of `core:store`. */
val DELETE_TOOLS_QUESTION_TEXT: UiText = UiText.Key(DELETE_PROFILE_TOOLS_QUESTION_KEY, DELETE_PROFILE_TOOLS_QUESTION)

/**
 * The delete question of a profile. With tools in the profile library, the dialog asks
 * "Delete the tools of this profile too?" and has the answers "Delete the tools", "Keep the tools"
 * (they go to the shared library), and "Cancel". With no tool, it has "Delete" and "Cancel".
 */
@Composable
fun DeleteProfileDialog(
    state: DeleteProfileUiState,
    onDelete: (deleteTools: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val target = LocalTouchTargets.current.button
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(DeleteProfileTags.DIALOG),
        title = { Text(stringResource(Res.string.settings_profiles_delete_title, state.name)) },
        text = {
            Column {
                Text(stringResource(Res.string.settings_profiles_delete_text))
                if (state.toolCount > 0) {
                    Text(
                        pluralStringResource(Res.plurals.settings_profiles_tool_count, state.toolCount, state.toolCount),
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Text(
                        DELETE_TOOLS_QUESTION_TEXT.resolve(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 4.dp).testTag(DeleteProfileTags.QUESTION),
                    )
                    Text(
                        stringResource(Res.string.settings_profiles_keep_tools_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.toolCount > 0) {
                    TextButton(
                        onClick = { onDelete(false) },
                        enabled = state.loaded && !state.busy,
                        colors = AccentButtons.text(),
                        modifier = Modifier.heightIn(min = target).testTag(DeleteProfileTags.KEEP_TOOLS),
                    ) { Text(stringResource(Res.string.settings_profiles_keep_tools)) }
                    TextButton(
                        onClick = { onDelete(true) },
                        enabled = state.loaded && !state.busy,
                        colors = AccentButtons.text(),
                        modifier = Modifier.heightIn(min = target).testTag(DeleteProfileTags.DELETE_TOOLS),
                    ) { Text(stringResource(Res.string.settings_profiles_delete_tools)) }
                } else {
                    TextButton(
                        onClick = { onDelete(false) },
                        enabled = state.loaded && !state.busy,
                        colors = AccentButtons.text(),
                        modifier = Modifier.heightIn(min = target).testTag(DeleteProfileTags.DELETE),
                    ) { Text(stringResource(Res.string.settings_delete)) }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = AccentButtons.text(), modifier = Modifier.heightIn(min = target)) {
                Text(stringResource(Res.string.settings_cancel))
            }
        },
    )
}
