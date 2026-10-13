package org.superdro.core.ui.readout

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.model.SidePanelMode
import org.superdro.core.resources.Res
import org.superdro.core.resources.ic_add
import org.superdro.core.resources.readout_add_sub_datum
import org.superdro.core.resources.route_machine_datums
import org.superdro.core.resources.route_sub_datums
import org.superdro.core.resources.route_tools
import org.superdro.core.ui.theme.LocalTouchTargets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow

/** The test tag of the "+" button. */
const val ADD_POINT_TAG: String = "readout-add-point"

/** The test tag of a side panel tab. */
fun sidePanelTag(mode: SidePanelMode): String = "readout-side-panel-${mode.name}"

/**
 * The tabs of the side panel (`PanelModes`): the sub-datums, the tools,
 * and the machine datums. A tap on the open tab closes the panel. The tool tab is enabled only
 * when the tool library is not empty.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SidePanelTabs(state: SidePanelUiState, events: ReadoutEvents, modifier: Modifier = Modifier) {
    val targets = LocalTouchTargets.current
    // The tabs wrap to a second row in a narrow pane, so that no tab is clipped at the edge.
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(targets.gap / 2),
        verticalArrangement = Arrangement.spacedBy(targets.gap / 2),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(
            SidePanelMode.SubDatums to Res.string.route_sub_datums,
            SidePanelMode.Tools to Res.string.route_tools,
            SidePanelMode.MachineDatums to Res.string.route_machine_datums,
        ).forEach { (mode, title) ->
            FilterChip(
                selected = state.mode == mode,
                onClick = { events.onSidePanelTab(mode) },
                enabled = mode != SidePanelMode.Tools || state.toolsEnabled,
                label = { Text(stringResource(title)) },
                modifier = Modifier.heightIn(min = targets.button).testTag(sidePanelTag(mode)),
            )
        }
    }
}

/**
 * The "+" button: a tap adds a sub-datum at the current position, a
 * second tap in 300 ms opens the custom point sheet, and a long press opens the shape menu. The
 * presenter tells the single tap from the double tap.
 */
@Composable
fun AddPointButton(events: ReadoutEvents, modifier: Modifier = Modifier) {
    val feedback = LocalReadoutFeedback.current
    val description = stringResource(Res.string.readout_add_sub_datum)
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .size(LocalTouchTargets.current.button)
            .clip(CircleShape)
            .combinedClickable(
                role = Role.Button,
                onLongClick = {
                    feedback?.longPress()
                    events.onAddLongPress()
                },
                onClick = {
                    feedback?.tap()
                    events.onAddTap()
                },
            )
            .semantics { contentDescription = description }
            .testTag(ADD_POINT_TAG),
        shape = CircleShape,
        color = colors.primaryContainer,
        contentColor = colors.onPrimaryContainer,
        shadowElevation = 3.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(Res.drawable.ic_add), contentDescription = null, modifier = Modifier.size(32.dp))
        }
    }
}
