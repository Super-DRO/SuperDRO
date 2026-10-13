package org.superdro.core.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.resources.Res
import org.superdro.core.resources.frame_back
import org.superdro.core.resources.frame_close
import org.superdro.core.resources.frame_done
import org.superdro.core.resources.frame_under_construction
import org.superdro.core.resources.ic_arrow_back
import org.superdro.core.resources.ic_build
import org.superdro.core.ui.adaptive.AdaptiveSheet
import org.superdro.core.ui.theme.LocalTouchTargets
import org.superdro.core.ui.theme.AccentButtons

/** The test tag of the text of a placeholder entry. */
const val PLACEHOLDER_TAG: String = "placeholder"

/**
 * The entry of a route whose area did not register its real entry yet: the title and "This
 * screen is under construction". A screen has a top app bar with a back button, a sheet has a
 * "Close" button, and a dialog has "Done".
 */
@Composable
fun PlaceholderEntry(kind: EntryKind, title: StringResource, onClose: () -> Unit) {
    val titleText = stringResource(title)
    when (kind) {
        EntryKind.Screen -> ScreenScaffold(title = titleText, onBack = onClose) {
            PlaceholderBody(Modifier.fillMaxSize())
        }
        EntryKind.Sheet -> AdaptiveSheet(onDismissRequest = onClose, title = titleText) {
            PlaceholderBody(Modifier.fillMaxWidth())
            Spacer(Modifier.size(24.dp))
            FilledTonalButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.End).heightIn(min = LocalTouchTargets.current.button),
            ) { Text(stringResource(Res.string.frame_close)) }
        }
        EntryKind.Dialog -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text(titleText) },
            text = { Text(stringResource(Res.string.frame_under_construction), modifier = Modifier.testTag(PLACEHOLDER_TAG)) },
            confirmButton = { TextButton(colors = AccentButtons.text(), onClick = onClose) { Text(stringResource(Res.string.frame_done)) } },
        )
    }
}

@Composable
private fun PlaceholderBody(modifier: Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(Res.drawable.ic_build),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.size(16.dp))
        Text(
            text = stringResource(Res.string.frame_under_construction),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag(PLACEHOLDER_TAG),
        )
    }
}

/**
 * The frame of a screen entry: a `TopAppBar` with the back button (the back gesture also
 * works) and the content below it.
 */
@Composable
fun ScreenScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(Res.drawable.ic_arrow_back), contentDescription = stringResource(Res.string.frame_back))
                    }
                },
                actions = { actions() },
            )
            content()
        }
    }
}

/**
 * The placeholder of a slot of the readout screen (the side panel or the graphical view pane):
 * the title and "This screen is under construction" in the pane.
 */
@Composable
fun SidePanelPlaceholder(title: StringResource, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            PlaceholderBody(Modifier.fillMaxSize())
        }
    }
}
