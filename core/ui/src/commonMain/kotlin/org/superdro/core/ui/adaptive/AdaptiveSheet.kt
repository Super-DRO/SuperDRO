package org.superdro.core.ui.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * A sheet: a `ModalBottomSheet` in portrait and a dialog in landscape.
 * The back gesture and a tap outside close it.
 *
 * @param title The title of the sheet, or null.
 * @param landscape True for the dialog form. The default comes from [LocalWindowLayout].
 * @param scrollable True when the content scrolls when it does not fit.
 */
@Composable
fun AdaptiveSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    landscape: Boolean = LocalWindowLayout.current.landscape,
    maxDialogWidth: Dp = 720.dp,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (landscape) {
        Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(
                modifier = modifier.widthIn(min = 320.dp, max = maxDialogWidth).padding(24.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
            ) {
                SheetBody(title = title, scrollable = scrollable, content = content)
            }
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
                SheetBody(title = title, scrollable = scrollable, content = content)
            }
        }
    }
}

@Composable
private fun SheetBody(title: String?, scrollable: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val scroll = if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier
    Column(Modifier.fillMaxWidth().heightIn(min = 0.dp).then(scroll).padding(horizontal = 24.dp, vertical = 16.dp)) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 16.dp).semantics { heading() },
            )
        }
        content()
    }
}
