package org.superdro.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.resources.Res
import org.superdro.core.resources.ic_expand_less
import org.superdro.core.resources.ic_expand_more
import org.superdro.core.resources.info_section_closed
import org.superdro.core.resources.info_section_open

// The dialog rule: a dialog is only for the attention of the user. General information (a help
// text, a details text, a report, diagnostics) shows in the screen. The helpers of this file show
// such a text in a section of the screen. The control that opens the section also closes it, so
// the section has no close button.

/**
 * Gives the state "Open" or "Closed" of the control that opens and closes a section to TalkBack.
 * The text and the icon of the control tell the action.
 */
@Composable
fun Modifier.openState(open: Boolean): Modifier {
    val state = stringResource(if (open) Res.string.info_section_open else Res.string.info_section_closed)
    return this.semantics { stateDescription = state }
}

/**
 * The mark of the control that opens and closes a section: "expand more" while the section is
 * closed and "expand less" while it is open. The mark has no content description: [openState]
 * gives the state.
 */
@Composable
fun OpenCloseIcon(open: Boolean, modifier: Modifier = Modifier) {
    Icon(
        painterResource(if (open) Res.drawable.ic_expand_less else Res.drawable.ic_expand_more),
        contentDescription = null,
        modifier = modifier.size(20.dp),
    )
}

/**
 * The content of a section that shows general information in the screen (the dialog rule): a
 * surface with a thin border, below the control that opened it. It has no close button.
 *
 * @param color The color of the surface. The default is `surfaceContainerLow`. A pane that already
 *   uses a container color can give a different role.
 * @param tag The test tag of the section, or null.
 */
@Composable
fun InfoPanel(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    tag: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = color,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth().then(if (tag != null) Modifier.testTag(tag) else Modifier),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}
