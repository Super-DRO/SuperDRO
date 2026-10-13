package org.superdro.core.ui.settings.keys

import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.model.AxisId
import org.superdro.core.model.KeyAction
import org.superdro.core.resources.Res
import org.superdro.core.resources.ic_delete
import org.superdro.core.resources.ic_keyboard
import org.superdro.core.resources.route_key_mapping
import org.superdro.core.resources.settings_cancel
import org.superdro.core.resources.settings_keys_action
import org.superdro.core.resources.settings_keys_add
import org.superdro.core.resources.settings_keys_add_title
import org.superdro.core.resources.settings_keys_axis
import org.superdro.core.resources.settings_keys_change
import org.superdro.core.resources.settings_keys_custom_active
import org.superdro.core.resources.settings_keys_default_active
import org.superdro.core.resources.settings_keys_empty
import org.superdro.core.resources.settings_keys_intro
import org.superdro.core.resources.settings_keys_next
import org.superdro.core.resources.settings_keys_note_device
import org.superdro.core.resources.settings_keys_press
import org.superdro.core.resources.settings_keys_press_hint
import org.superdro.core.resources.settings_keys_remove
import org.superdro.core.resources.settings_keys_reset
import org.superdro.core.resources.settings_keys_section_map
import org.superdro.core.ui.navigation.ScreenScaffold
import org.superdro.core.ui.settings.components.NoticeKind
import org.superdro.core.ui.settings.components.SettingsButtons
import org.superdro.core.ui.settings.components.SettingsColumn
import org.superdro.core.ui.settings.components.SettingsDropdown
import org.superdro.core.ui.settings.components.SettingsNote
import org.superdro.core.ui.settings.components.SettingsNotice
import org.superdro.core.ui.settings.components.SettingsSection
import org.superdro.core.ui.settings.components.SettingsSegmented
import org.superdro.core.ui.settings.help.HelpButton
import org.superdro.core.ui.settings.help.HelpTopic
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.LocalTouchTargets

/** The test tag of the row [index] of the key map. */
fun keyRowTag(index: Int): String = "key-row:$index"

/** The test tag of the "Add a key" button. */
const val KEY_ADD_TAG: String = "key-add"

/** The test tag of the "Reset to the default" button. */
const val KEY_RESET_TAG: String = "key-reset"

/** The test tag of the "Next" button of the add dialog. */
const val KEY_NEXT_TAG: String = "key-next"

/** The test tag of the focused element of the "Press a key" dialog. */
const val KEY_CAPTURE_TAG: String = "key-capture"

/** The events of the key mapping screen. [KeyMappingPresenter] has each function. */
interface KeyMappingEvents {
    fun startAdd()
    fun setDraftAction(action: KeyAction)
    fun setDraftAxis(axis: AxisId)
    fun startCapture()
    fun startChangeKey(index: Int)
    fun cancelDraft()
    fun onKeyCaptured(keyCode: Long)
    fun remove(index: Int)
    fun resetToDefault()
}

/**
 * The key mapping screen: the list of the keys with their actions,
 * "Add a key" with the capture of the key, the change of the key of a row, the remove with undo,
 * and "Reset to the default".
 */
@Composable
fun KeyMappingScreen(state: KeyMappingUiState, events: KeyMappingEvents, onBack: () -> Unit) {
    ScreenScaffold(
        title = stringResource(Res.string.route_key_mapping),
        onBack = onBack,
        actions = { HelpButton(HelpTopic.KeyMapping) },
    ) {
        SettingsColumn {
            SettingsNote(stringResource(Res.string.settings_keys_intro))
            SettingsNotice(
                text = stringResource(if (state.customized) Res.string.settings_keys_custom_active else Res.string.settings_keys_default_active),
                kind = NoticeKind.Info,
                tag = "key-map-kind",
            )
            SettingsButtons {
                OutlinedButton(
                    onClick = events::resetToDefault,
                    enabled = state.customized,
                    colors = AccentButtons.outlined(),
                    modifier = Modifier.heightIn(min = LocalTouchTargets.current.button).testTag(KEY_RESET_TAG),
                ) { Text(stringResource(Res.string.settings_keys_reset)) }
                FilledTonalButton(
                    onClick = events::startAdd,
                    modifier = Modifier.heightIn(min = LocalTouchTargets.current.button).testTag(KEY_ADD_TAG),
                ) { Text(stringResource(Res.string.settings_keys_add)) }
            }
            SettingsSection(stringResource(Res.string.settings_keys_section_map)) {
                if (state.rows.isEmpty()) SettingsNote(stringResource(Res.string.settings_keys_empty))
                state.rows.forEachIndexed { position, row ->
                    if (position > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    KeyRow(row, events)
                }
            }
            SettingsNote(stringResource(Res.string.settings_keys_note_device))
        }
    }
    val draft = state.draft
    if (draft != null) {
        if (draft.capturing) KeyCaptureDialog(draft, events) else AddKeyDialog(draft, events)
    }
}

@Composable
private fun KeyRow(row: KeyMappingRow, events: KeyMappingEvents) {
    val target = LocalTouchTargets.current.button
    val key = row.key.resolve()
    val action = row.action.resolve()
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = target).testTag(keyRowTag(row.index)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KeyCap(key, Modifier.widthIn(min = 120.dp))
        Spacer(Modifier.width(16.dp))
        Text(action, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        IconButton(onClick = { events.startChangeKey(row.index) }, modifier = Modifier.size(target)) {
            Icon(painterResource(Res.drawable.ic_keyboard), contentDescription = stringResource(Res.string.settings_keys_change, action))
        }
        IconButton(onClick = { events.remove(row.index) }, modifier = Modifier.size(target).testTag("key-remove:${row.index}")) {
            Icon(painterResource(Res.drawable.ic_delete), contentDescription = stringResource(Res.string.settings_keys_remove, key))
        }
    }
}

/** The name of a key on a key cap: `secondaryContainer` with `onSecondaryContainer`. */
@Composable
private fun KeyCap(name: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small),
    ) {
        Text(
            name,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/** The "Add a key" dialog: the action and, for an axis action, the axis. "Next" opens the capture. */
@Composable
private fun AddKeyDialog(draft: KeyDraft, events: KeyMappingEvents) {
    val target = LocalTouchTargets.current.button
    AlertDialog(
        onDismissRequest = events::cancelDraft,
        title = { Text(stringResource(Res.string.settings_keys_add_title)) },
        text = {
            Column(Modifier.widthIn(min = 360.dp)) {
                SettingsDropdown(
                    title = stringResource(Res.string.settings_keys_action),
                    options = KeyMaps.SELECTABLE,
                    selected = draft.action,
                    label = { action -> KeyMaps.pickText(action).resolve() },
                    onSelect = events::setDraftAction,
                    tag = "key-action",
                )
                if (draft.needsAxis) {
                    SettingsSegmented(
                        title = stringResource(Res.string.settings_keys_axis),
                        options = KeyMaps.AXES,
                        selected = draft.axis,
                        label = { axis -> if (axis == AxisId.Angular) "α" else axis.name },
                        onSelect = events::setDraftAxis,
                        tag = "key-axis",
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = events::startCapture, colors = AccentButtons.text(), modifier = Modifier.heightIn(min = target).testTag(KEY_NEXT_TAG)) {
                Text(stringResource(Res.string.settings_keys_next))
            }
        },
        dismissButton = {
            TextButton(onClick = events::cancelDraft, colors = AccentButtons.text(), modifier = Modifier.heightIn(min = target)) {
                Text(stringResource(Res.string.settings_cancel))
            }
        },
    )
}

/**
 * The "Press a key" dialog: a focused element takes the next key down with the Compose key
 * events. Escape or Back cancels ([KeyMaps.CANCEL_KEYS]).
 */
@Composable
private fun KeyCaptureDialog(draft: KeyDraft, events: KeyMappingEvents) {
    val requester = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = events::cancelDraft,
        title = { Text(stringResource(Res.string.settings_keys_press)) },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(KEY_CAPTURE_TAG)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) events.onKeyCaptured(event.key.keyCode)
                        true
                    }
                    .focusRequester(requester)
                    .focusable(),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Icon(
                        painterResource(Res.drawable.ic_keyboard),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.size(12.dp))
                    Text(
                        KeyMaps.actionText(draft.action, if (draft.needsAxis) draft.axis else null).resolve(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        stringResource(Res.string.settings_keys_press_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = events::cancelDraft, colors = AccentButtons.text(), modifier = Modifier.heightIn(min = LocalTouchTargets.current.button)) {
                Text(stringResource(Res.string.settings_cancel))
            }
        },
    )
    LaunchedEffect(requester) {
        try {
            requester.requestFocus()
        } catch (error: IllegalStateException) {
            // The dialog is not in the layout yet.
        }
    }
}
