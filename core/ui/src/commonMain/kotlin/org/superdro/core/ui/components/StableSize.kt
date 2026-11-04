package org.superdro.core.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.superdro.core.resources.Res
import org.superdro.core.resources.ic_check

// A control must not change its size when the operator pushes it. Thus a control with a text or
// an icon that changes with its state keeps the space of its largest state. The neighbor controls
// then stay in their positions.

/** The size of the check icon of a chip. */
val CheckIconSize: Dp = 18.dp

/**
 * Gives the width in px of the widest text of [texts] in one line with [style].
 */
@Composable
fun rememberMaxTextWidth(texts: List<String>, style: TextStyle): Int {
    val measurer = rememberTextMeasurer()
    return remember(measurer, texts, style) {
        texts.maxOfOrNull { text -> measurer.measure(text, style, maxLines = 1, softWrap = false).size.width } ?: 0
    }
}

/**
 * Gives a modifier that makes the width of the element at least [minWidthPx], in the limit
 * of the parent constraints.
 */
fun Modifier.minWidthPx(minWidthPx: Int): Modifier = layout { measurable, constraints ->
    val minWidth = maxOf(constraints.minWidth, minWidthPx.coerceAtMost(constraints.maxWidth))
    val placeable = measurable.measure(constraints.copy(minWidth = minWidth))
    layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
}

/**
 * A one-line text with the width of the widest text of [alternatives]. Use it for the label of a
 * control that changes its label with its state, for example "Start" and "Stop". The text is
 * at the center of this width.
 *
 * @param alternatives All the texts that the control can show. [text] is added if it is not in
 *   the list.
 */
@Composable
fun StableText(
    text: String,
    alternatives: List<String>,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
) {
    val all = remember(text, alternatives) { if (text in alternatives) alternatives else alternatives + text }
    val widthPx = rememberMaxTextWidth(all, style)
    Text(
        text,
        modifier = modifier.minWidthPx(widthPx),
        style = style,
        color = color,
        textAlign = TextAlign.Center,
        maxLines = 1,
        softWrap = false,
    )
}

/**
 * The leading icon of a chip that shows its "on" state with a check icon. When [shown] is
 * false, an empty space of the same size keeps the width of the chip.
 */
@Composable
fun CheckIconSlot(shown: Boolean, tint: Color = LocalContentColor.current) {
    if (shown) {
        Icon(painterResource(Res.drawable.ic_check), contentDescription = null, tint = tint, modifier = Modifier.size(CheckIconSize))
    } else {
        Spacer(Modifier.size(CheckIconSize))
    }
}
