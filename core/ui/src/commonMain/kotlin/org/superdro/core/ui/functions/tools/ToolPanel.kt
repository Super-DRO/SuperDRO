package org.superdro.core.ui.functions.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.resources.Res
import org.superdro.core.resources.ic_build
import org.superdro.core.resources.route_tools
import org.superdro.core.resources.tools_active
import org.superdro.core.resources.tools_open_library
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.theme.LocalTouchTargets

/** The test tag of the row of the tool [id] in the tool panel. */
fun toolPanelRowTag(id: String): String = "tools-panel-$id"

/**
 * The tool panel of the side panel: a tap on a tool activates
 * it, a tap on the active tool clears it. The active tool has the `tertiaryContainer` role, an
 * icon, and a text. The button at the top opens the tool library.
 */
@Composable
fun ToolPanel(state: ToolPanelUiState, onTap: (String) -> Unit, onOpenLibrary: () -> Unit, modifier: Modifier = Modifier) {
    val activeText = stringResource(Res.string.tools_active)
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.route_tools), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onOpenLibrary) {
                    Icon(painterResource(Res.drawable.ic_build), contentDescription = stringResource(Res.string.tools_open_library))
                }
            }
            HorizontalDivider()
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(state.rows, key = { it.id }) { row ->
                    ListItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = LocalTouchTargets.current.button)
                            .clickable(role = Role.Button) { onTap(row.id) }
                            .semantics { if (row.active) stateDescription = activeText }
                            .testTag(toolPanelRowTag(row.id)),
                        colors = if (row.active) {
                            ListItemDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                headlineColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                supportingColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        } else {
                            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                        },
                        headlineContent = { Text(row.name, style = MaterialTheme.typography.titleMedium) },
                        supportingContent = {
                            Column {
                                Text(row.detail.resolve(), style = MaterialTheme.typography.bodyMedium)
                                if (row.active) ActiveMark(activeText, MaterialTheme.colorScheme.onTertiaryContainer)
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
