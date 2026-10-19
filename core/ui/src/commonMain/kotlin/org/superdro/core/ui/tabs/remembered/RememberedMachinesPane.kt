package org.superdro.core.ui.tabs.remembered

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.resources.Res
import org.superdro.core.resources.frame_cancel
import org.superdro.core.resources.frame_no_link
import org.superdro.core.resources.frame_no_remembered
import org.superdro.core.resources.frame_remembered_connect
import org.superdro.core.resources.frame_remembered_edit
import org.superdro.core.resources.frame_remembered_last_cause
import org.superdro.core.resources.frame_remembered_open
import org.superdro.core.resources.frame_remembered_pause
import org.superdro.core.resources.frame_remembered_remove
import org.superdro.core.resources.frame_remove_text
import org.superdro.core.resources.frame_remove_title
import org.superdro.core.resources.frame_state_offline
import org.superdro.core.resources.frame_state_online
import org.superdro.core.resources.frame_state_paused
import org.superdro.core.resources.route_remembered_machines
import org.superdro.core.ui.StatusChip
import org.superdro.core.ui.linkName
import org.superdro.core.ui.navigation.ScreenScaffold
import org.superdro.core.ui.tabs.StatusRoles
import org.superdro.core.ui.tabs.TabStatus
import org.superdro.core.ui.tabs.TabStatusIcon
import org.superdro.core.ui.tabs.TabStatusRules
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.theme.LocalTouchTargets
import org.superdro.core.ui.transportIcon
import org.superdro.core.ui.theme.AccentButtons

/** The test tag of the row of [profileId]. */
fun rememberedRowTag(profileId: String): String = "remembered:$profileId"

/** The test tag of the "Open" button of [profileId]. */
fun rememberedOpenTag(profileId: String): String = "remembered-open:$profileId"

/** The events of the remembered machines pane. */
interface RememberedMachinesEvents {
    fun onOpen(profileId: String) = Unit
    fun onConnect(profileId: String)
    fun onPause(profileId: String)
    fun onEdit(profileId: String)
    fun onAskRemove(profileId: String)
    fun onCancelRemove()
    fun onConfirmRemove()
}

/**
 * The remembered machines pane: one row for each remembered
 * machine with its name, link type, address, and state, and the actions Connect, Pause, Open,
 * Edit, and Remove. "Remove" asks first.
 */
@Composable
fun RememberedMachinesPane(state: RememberedMachinesUiState, events: RememberedMachinesEvents, onBack: () -> Unit) {
    ScreenScaffold(title = stringResource(Res.string.route_remembered_machines), onBack = onBack) {
        if (state.rows.isEmpty()) {
            Text(
                text = stringResource(Res.string.frame_no_remembered),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(state.rows, key = { row -> row.profileId }) { row ->
                RememberedRow(row, events)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
    state.confirmRemove?.let { row ->
        AlertDialog(
            onDismissRequest = events::onCancelRemove,
            title = { Text(stringResource(Res.string.frame_remove_title, row.name)) },
            text = { Text(stringResource(Res.string.frame_remove_text)) },
            confirmButton = {
                TextButton(colors = AccentButtons.text(), onClick = events::onConfirmRemove, modifier = Modifier.testTag("confirm-remove")) {
                    Text(stringResource(Res.string.frame_remembered_remove))
                }
            },
            dismissButton = { TextButton(colors = AccentButtons.text(), onClick = events::onCancelRemove) { Text(stringResource(Res.string.frame_cancel)) } },
        )
    }
}

@Composable
private fun RememberedRow(row: RememberedMachineRow, events: RememberedMachinesEvents) {
    val target = LocalTouchTargets.current
    Column(Modifier.fillMaxWidth().testTag(rememberedRowTag(row.profileId))) {
        ListItem(
            headlineContent = { Text(row.name, style = MaterialTheme.typography.titleMedium) },
            supportingContent = {
                val link = row.transport?.let { kind -> stringResource(linkName(kind)) }
                Column {
                    Text(listOfNotNull(link, row.address).joinToString(" · ").ifEmpty { stringResource(Res.string.frame_no_link) })
                    row.lastCause?.let { cause ->
                        Text(stringResource(Res.string.frame_remembered_last_cause, TabStatusRules.causeText(cause).resolve()))
                    }
                }
            },
            leadingContent = row.transport?.let { kind -> { Icon(painterResource(transportIcon(kind)), contentDescription = null) } },
            trailingContent = {
                val link = row.link
                if (link != null) StatusChip(link, link.text.resolve()) else StatusChip(statusOf(row.state), stateText(row.state))
            },
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(target.gap),
            verticalArrangement = Arrangement.spacedBy(target.gap),
        ) {
            if (row.state == MachineLinkState.Online) {
                FilledTonalButton(onClick = { events.onPause(row.profileId) }, modifier = Modifier.heightIn(min = target.button)) {
                    Text(stringResource(Res.string.frame_remembered_pause))
                }
            } else {
                FilledTonalButton(
                    onClick = { events.onConnect(row.profileId) },
                    enabled = row.transport != null,
                    modifier = Modifier.heightIn(min = target.button),
                ) { Text(stringResource(Res.string.frame_remembered_connect)) }
                if (row.state == MachineLinkState.Offline) {
                    OutlinedButton(colors = AccentButtons.outlined(), onClick = { events.onPause(row.profileId) }, modifier = Modifier.heightIn(min = target.button)) {
                        Text(stringResource(Res.string.frame_remembered_pause))
                    }
                }
            }
            OutlinedButton(
                colors = AccentButtons.outlined(),
                onClick = { events.onOpen(row.profileId) },
                modifier = Modifier.heightIn(min = target.button).testTag(rememberedOpenTag(row.profileId)),
            ) {
                Text(stringResource(Res.string.frame_remembered_open))
            }
            OutlinedButton(colors = AccentButtons.outlined(), onClick = { events.onEdit(row.profileId) }, modifier = Modifier.heightIn(min = target.button)) {
                Text(stringResource(Res.string.frame_remembered_edit))
            }
            OutlinedButton(colors = AccentButtons.outlined(), onClick = { events.onAskRemove(row.profileId) }, modifier = Modifier.heightIn(min = target.button)) {
                Text(stringResource(Res.string.frame_remembered_remove))
            }
        }
    }
}

@Composable
private fun stateText(state: MachineLinkState): String = stringResource(
    when (state) {
        MachineLinkState.Online -> Res.string.frame_state_online
        MachineLinkState.Offline -> Res.string.frame_state_offline
        MachineLinkState.Paused -> Res.string.frame_state_paused
    },
)

/** The chip of a state: an icon and a text. The text comes from [stateText]. */
private fun statusOf(state: MachineLinkState): TabStatus = when (state) {
    MachineLinkState.Online -> TabStatus(TabStatusIcon.Connected, UiText.Plain(""), StatusRoles.Connected)
    MachineLinkState.Offline -> TabStatus(TabStatusIcon.Offline, UiText.Plain(""), StatusRoles.Neutral)
    MachineLinkState.Paused -> TabStatus(TabStatusIcon.Offline, UiText.Plain(""), StatusRoles.Neutral)
}
