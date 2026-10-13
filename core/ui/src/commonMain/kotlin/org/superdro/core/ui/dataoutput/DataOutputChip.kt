package org.superdro.core.ui.dataoutput

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
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.direct
import org.kodein.di.factoryOrNull
import org.superdro.core.dataoutput.DataOutputStatus
import org.superdro.core.model.DataOutputActivation
import org.superdro.core.resources.Res
import org.superdro.core.resources.dataoutput_chip
import org.superdro.core.resources.dataoutput_chip_always_description
import org.superdro.core.resources.dataoutput_chip_description
import org.superdro.core.ui.LocalDI
import org.superdro.core.ui.navigation.DataOutputDetailsRoute
import org.superdro.core.ui.navigation.DataOutputRoute
import org.superdro.core.ui.navigation.TabEntryScope

/** The test tag of the status chip of the data output. */
const val DATA_OUTPUT_CHIP_TAG: String = "data-output-chip"

/** The name of the chip presenter in the presenters of the tab. */
internal const val CHIP_PRESENTER: String = "dataOutputChip"

/**
 * The item of the data output in the status strip of the readout. The graph of
 * the app binds the factory of [DataOutputChipPresenter]. A graph with no such binding (a test)
 * shows no chip.
 */
@Composable
internal fun DataOutputStatusItem(scope: TabEntryScope) {
    val di = LocalDI.current
    val factory = remember(di) { di.direct.factoryOrNull<String, DataOutputChipPresenter>() } ?: return
    val presenter = scope.tabPresenter(CHIP_PRESENTER) { factory(scope.deviceId.value) }
    val state by presenter.uiState.collectAsState()
    DataOutputStatusChip(state, onTap = presenter::onTap, onLongPress = { scope.global.push(DataOutputRoute) })
    // The dialog rule: the diagnostics show in the data output screen, not in a dialog.
    LaunchedEffect(state.openDetails) {
        if (state.openDetails) {
            presenter.detailsOpened()
            scope.global.push(DataOutputDetailsRoute)
        }
    }
}

/**
 * The status chip of the data output: an icon and a text for each status. It shows only
 * when the output of the machine is on. A tap starts or stops the output (or opens the data
 * output screen with the diagnostics), and a long press opens the data output screen.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DataOutputStatusChip(state: DataOutputChipUiState, onTap: () -> Unit, onLongPress: () -> Unit, modifier: Modifier = Modifier) {
    if (!state.visible) return
    val status = stringResource(DataOutputTexts.status(state.status))
    val description = stringResource(
        if (state.activation == DataOutputActivation.AlwaysOn) Res.string.dataoutput_chip_always_description else Res.string.dataoutput_chip_description,
        status,
    )
    val (container, content) = chipColors(state.status)
    val (icon, _) = statusIcon(state.status)
    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.small,
        modifier = modifier
            .minimumInteractiveComponentSize()
            .testTag(DATA_OUTPUT_CHIP_TAG)
            .combinedClickable(role = Role.Button, onClick = onTap, onLongClick = onLongPress)
            .semantics { contentDescription = description },
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(Res.string.dataoutput_chip, status), style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** The container roles of the status. */
@Composable
private fun chipColors(status: DataOutputStatus): Pair<Color, Color> {
    val colors = MaterialTheme.colorScheme
    return when (status) {
        DataOutputStatus.Sending -> colors.primaryContainer to colors.onPrimaryContainer
        DataOutputStatus.Ready, DataOutputStatus.Starting -> colors.secondaryContainer to colors.onSecondaryContainer
        DataOutputStatus.Warning -> colors.errorContainer to colors.onErrorContainer
        DataOutputStatus.Stopped -> colors.surfaceContainerHigh to colors.onSurface
    }
}
