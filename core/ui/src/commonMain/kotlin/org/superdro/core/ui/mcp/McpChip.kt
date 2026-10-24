package org.superdro.core.ui.mcp

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.direct
import org.kodein.di.providerOrNull
import org.superdro.core.mcp.McpServerState
import org.superdro.core.model.McpActivation
import org.superdro.core.resources.Res
import org.superdro.core.resources.ic_check
import org.superdro.core.resources.ic_hourglass
import org.superdro.core.resources.ic_pause
import org.superdro.core.resources.ic_sync
import org.superdro.core.resources.ic_warning
import org.superdro.core.resources.mcp_chip
import org.superdro.core.resources.mcp_chip_always_description
import org.superdro.core.resources.mcp_chip_description
import org.superdro.core.ui.LocalDI
import org.superdro.core.ui.navigation.McpDiagnosticsRoute
import org.superdro.core.ui.navigation.McpSettingsRoute
import org.superdro.core.ui.navigation.TabEntryScope

/** The test tag of the status chip of the MCP server. */
const val MCP_CHIP_TAG: String = "mcp-chip"

/** The name of the chip presenter in the presenters of the tab. */
internal const val MCP_CHIP_PRESENTER: String = "mcpChip"

/**
 * The item of the MCP server in the status strip of the readout. The graph of the app binds a
 * provider of [McpChipPresenter]. A graph with no such binding (a test) shows no chip.
 */
@Composable
internal fun McpStatusItem(scope: TabEntryScope) {
    val di = LocalDI.current
    val provider = remember(di) { di.direct.providerOrNull<McpChipPresenter>() } ?: return
    val presenter = scope.tabPresenter(MCP_CHIP_PRESENTER) { provider() }
    val state by presenter.uiState.collectAsState()
    McpStatusChip(state, onTap = presenter::onTap, onLongPress = { scope.global.push(McpSettingsRoute) })
    // The dialog rule: the diagnostics show in the MCP settings screen, not in a dialog.
    LaunchedEffect(state.openDetails) {
        if (state.openDetails) {
            presenter.detailsOpened()
            scope.global.push(McpDiagnosticsRoute)
        }
    }
}

/**
 * The status chip of the MCP server: an icon and a text for each state. It shows only when the
 * switch of the server is on. A tap starts or stops the server (or opens the settings screen
 * with the diagnostics), and a long press opens the settings screen.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun McpStatusChip(state: McpChipUiState, onTap: () -> Unit, onLongPress: () -> Unit, modifier: Modifier = Modifier) {
    if (!state.visible) return
    val status = stringResource(McpTexts.status(state.state))
    val description = stringResource(
        if (state.activation == McpActivation.AlwaysOn) Res.string.mcp_chip_always_description else Res.string.mcp_chip_description,
        status,
    )
    val (container, content) = chipColors(state.state)
    val (icon, _) = mcpStateIcon(state.state)
    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.small,
        modifier = modifier
            .minimumInteractiveComponentSize()
            .testTag(MCP_CHIP_TAG)
            .combinedClickable(role = Role.Button, onClick = onTap, onLongClick = onLongPress)
            .semantics { contentDescription = description },
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(Res.string.mcp_chip, status), style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** The icon and its color of a server state (each state has an icon and a text). */
@Composable
internal fun mcpStateIcon(state: McpServerState): Pair<DrawableResource, Color> {
    val colors = MaterialTheme.colorScheme
    return when (state) {
        McpServerState.Stopped -> Res.drawable.ic_pause to colors.onSurfaceVariant
        McpServerState.Starting -> Res.drawable.ic_hourglass to colors.onSurfaceVariant
        McpServerState.Ready -> Res.drawable.ic_sync to colors.onSurfaceVariant
        McpServerState.Active -> Res.drawable.ic_check to colors.onSurface
        is McpServerState.Failed -> Res.drawable.ic_warning to colors.error
    }
}

/** The container roles of the state. */
@Composable
private fun chipColors(state: McpServerState): Pair<Color, Color> {
    val colors = MaterialTheme.colorScheme
    return when (state) {
        McpServerState.Active -> colors.primaryContainer to colors.onPrimaryContainer
        McpServerState.Ready, McpServerState.Starting -> colors.secondaryContainer to colors.onSecondaryContainer
        is McpServerState.Failed -> colors.errorContainer to colors.onErrorContainer
        McpServerState.Stopped -> colors.surfaceContainerHigh to colors.onSurface
    }
}
