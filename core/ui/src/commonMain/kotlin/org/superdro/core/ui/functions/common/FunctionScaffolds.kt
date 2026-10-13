package org.superdro.core.ui.functions.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.resources.Res
import org.superdro.core.resources.fn_cancel
import org.superdro.core.resources.ic_error
import org.superdro.core.ui.adaptive.AdaptiveSheet
import org.superdro.core.ui.adaptive.LocalWindowLayout
import org.superdro.core.ui.navigation.ScreenScaffold
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.LocalTouchTargets

/** The test tag of the primary button ("Create", "Apply") of a function screen or sheet. */
const val PRIMARY_BUTTON_TAG: String = "functions-primary"

/** The test tag of the "Cancel" button of a function screen or sheet. */
const val CANCEL_BUTTON_TAG: String = "functions-cancel"

/** The test tag of the error box of a function screen or sheet. */
const val ERROR_BOX_TAG: String = "functions-error"

/**
 * The errors of a function at "Create": the header, then one line for
 * each error. Null [header] shows only the lines.
 */
data class ErrorBlock(val header: UiText?, val lines: List<UiText>) {
    /** True when the block has no line. */
    val isEmpty: Boolean get() = header == null && lines.isEmpty()
}

/**
 * The error box: the `errorContainer` role with `onErrorContainer` text, an icon, and the lines
 * (an icon and a text, not only a color). A screen reader reads a new error.
 */
@Composable
fun ErrorBox(block: ErrorBlock, modifier: Modifier = Modifier) {
    if (block.isEmpty) return
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().testTag(ERROR_BOX_TAG).semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(Res.drawable.ic_error), contentDescription = null, modifier = Modifier.size(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                block.header?.let { header -> Text(header.resolve(), style = MaterialTheme.typography.bodyMedium) }
                block.lines.forEach { line -> Text("• " + line.resolve(), style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

/**
 * The buttons at the end of a function screen or sheet: "Cancel" (a text button) and the
 * primary button (a filled tonal button). The buttons have the minimum touch targets.
 */
@Composable
fun FunctionActions(
    primaryLabel: String,
    onPrimary: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    primaryEnabled: Boolean = true,
    cancelLabel: String = stringResource(Res.string.fn_cancel),
) {
    val targets = LocalTouchTargets.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(targets.gap, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = onCancel,
            colors = AccentButtons.text(),
            modifier = Modifier.heightIn(min = targets.button).testTag(CANCEL_BUTTON_TAG),
        ) { Text(cancelLabel) }
        FilledTonalButton(
            onClick = onPrimary,
            enabled = primaryEnabled,
            modifier = Modifier.heightIn(min = targets.button).testTag(PRIMARY_BUTTON_TAG),
        ) { Text(primaryLabel) }
    }
}

/**
 * The frame of a function screen. The form scrolls. In landscape the preview is at the right of the
 * form. In
 * portrait the preview is above the form. The error box and the buttons are at the end of the
 * form.
 *
 * @param preview The point preview, or null for a screen with no preview.
 */
@Composable
fun FunctionScreen(
    title: String,
    onBack: () -> Unit,
    primaryLabel: String,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    primaryEnabled: Boolean = true,
    errors: ErrorBlock? = null,
    actions: @Composable () -> Unit = {},
    preview: (@Composable () -> Unit)? = null,
    form: @Composable ColumnScope.() -> Unit,
) {
    val landscape = LocalWindowLayout.current.landscape
    ScreenScaffold(title = title, onBack = onBack, modifier = modifier, actions = actions) {
        val formColumn: @Composable (Modifier) -> Unit = { columnModifier ->
            Column(
                modifier = columnModifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                form()
                if (errors != null) ErrorBox(errors)
                Spacer(Modifier.height(4.dp))
                FunctionActions(primaryLabel = primaryLabel, onPrimary = onPrimary, onCancel = onBack, primaryEnabled = primaryEnabled)
            }
        }
        if (preview == null) {
            formColumn(Modifier.fillMaxSize())
        } else if (landscape) {
            Row(Modifier.fillMaxSize()) {
                formColumn(Modifier.weight(0.55f).fillMaxHeight())
                VerticalDivider(Modifier.fillMaxHeight())
                Box(Modifier.weight(0.45f).fillMaxHeight().padding(16.dp)) { preview() }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(PORTRAIT_PREVIEW_HEIGHT).padding(horizontal = 16.dp, vertical = 8.dp)) { preview() }
                HorizontalDivider()
                formColumn(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

/** The height of the preview above the form in portrait. */
private val PORTRAIT_PREVIEW_HEIGHT = 300.dp

/**
 * The frame of a function sheet: the title, the content, the error box, and the buttons. The back
 * gesture
 * closes it.
 */
@Composable
fun FunctionSheet(
    title: String,
    onDismiss: () -> Unit,
    primaryLabel: String,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    primaryEnabled: Boolean = true,
    errors: ErrorBlock? = null,
    cancelLabel: String = stringResource(Res.string.fn_cancel),
    content: @Composable ColumnScope.() -> Unit,
) {
    AdaptiveSheet(onDismissRequest = onDismiss, modifier = modifier, title = title) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content()
            if (errors != null) ErrorBox(errors)
            FunctionActions(
                primaryLabel = primaryLabel,
                onPrimary = onPrimary,
                onCancel = onDismiss,
                primaryEnabled = primaryEnabled,
                cancelLabel = cancelLabel,
            )
        }
    }
}
