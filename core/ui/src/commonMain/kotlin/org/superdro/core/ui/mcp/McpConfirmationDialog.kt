package org.superdro.core.ui.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.direct
import org.kodein.di.instanceOrNull
import org.superdro.core.mcp.WriteRequest
import org.superdro.core.resources.Res
import org.superdro.core.resources.ic_warning
import org.superdro.core.resources.mcp_confirm_allow
import org.superdro.core.resources.mcp_confirm_client
import org.superdro.core.resources.mcp_confirm_refuse
import org.superdro.core.resources.mcp_confirm_title
import org.superdro.core.resources.mcp_confirm_waiting
import org.superdro.core.ui.LocalDI
import org.superdro.core.ui.theme.AccentButtons

/** The test tag of the confirmation dialog of an MCP write. */
const val MCP_CONFIRM_TAG: String = "mcp-confirm"

/** The test tag of the Allow button. */
const val MCP_CONFIRM_ALLOW_TAG: String = "mcp-confirm-allow"

/** The test tag of the Refuse button. */
const val MCP_CONFIRM_REFUSE_TAG: String = "mcp-confirm-refuse"

/**
 * Shows the pending write request of the [McpGate] of the graph in the frame of the app. A graph
 * with no gate (a test) shows nothing.
 */
@Composable
fun McpConfirmationHost() {
    val di = LocalDI.current
    val gate = remember(di) { di.direct.instanceOrNull<McpGate>() } ?: return
    val pending by gate.pending.collectAsState()
    val waiting by gate.waiting.collectAsState()
    val request = pending ?: return
    McpConfirmationDialog(request = request, waiting = waiting, onAllow = gate::allow, onRefuse = gate::refuse)
}

/**
 * The confirmation of one MCP write on the tablet: the tool title, the summary, the details, the
 * client host, and the buttons Allow and Refuse. A tap outside the dialog does not answer: the
 * request waits for its timeout.
 *
 * @param waiting The number of requests that wait behind this one.
 */
@Composable
fun McpConfirmationDialog(request: WriteRequest, waiting: Int, onAllow: () -> Unit, onRefuse: () -> Unit, modifier: Modifier = Modifier) {
    AlertDialog(
        onDismissRequest = {},
        modifier = modifier.testTag(MCP_CONFIRM_TAG),
        icon = { Icon(painterResource(Res.drawable.ic_warning), contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(Res.string.mcp_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(request.toolTitle, style = MaterialTheme.typography.titleMedium)
                Text(request.summary, style = MaterialTheme.typography.bodyLarge)
                request.details.forEach { line -> Text(line, style = MaterialTheme.typography.bodyMedium) }
                Text(stringResource(Res.string.mcp_confirm_client, request.client.host), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (waiting > 0) {
                    Text(pluralStringResource(Res.plurals.mcp_confirm_waiting, waiting, waiting), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(colors = AccentButtons.text(), onClick = onAllow, modifier = Modifier.testTag(MCP_CONFIRM_ALLOW_TAG)) {
                Text(stringResource(Res.string.mcp_confirm_allow))
            }
        },
        dismissButton = {
            TextButton(colors = AccentButtons.text(), onClick = onRefuse, modifier = Modifier.testTag(MCP_CONFIRM_REFUSE_TAG)) {
                Text(stringResource(Res.string.mcp_confirm_refuse))
            }
        },
    )
}
