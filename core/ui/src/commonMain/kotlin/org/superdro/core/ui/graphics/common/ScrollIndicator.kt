package org.superdro.core.ui.graphics.common

import androidx.compose.foundation.ScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Draws a thin bar at the right edge of a panel that scrolls vertically. The bar shows that the
 * panel has more content, and which part of the content shows. Put this modifier before
 * `verticalScroll(state)`, so that it draws in the visible area. The bar does not show when the
 * content fits.
 */
@Composable
fun Modifier.verticalScrollIndicator(state: ScrollState, color: Color = MaterialTheme.colorScheme.onSurfaceVariant): Modifier {
    val barColor = color.copy(alpha = INDICATOR_ALPHA)
    return drawWithContent {
        drawContent()
        val max = state.maxValue
        if (max <= 0 || max == Int.MAX_VALUE) return@drawWithContent
        val width = INDICATOR_WIDTH.toPx()
        val inset = INDICATOR_INSET.toPx()
        val track = size.height - 2 * inset
        if (track <= 0f) return@drawWithContent
        val length = (track * size.height / (size.height + max)).coerceAtLeast(INDICATOR_MIN_LENGTH.toPx()).coerceAtMost(track)
        val top = inset + (track - length) * state.value / max
        drawRoundRect(
            color = barColor,
            topLeft = Offset(size.width - inset - width, top),
            size = Size(width, length),
            cornerRadius = CornerRadius(width / 2, width / 2),
        )
    }
}

private val INDICATOR_WIDTH = 4.dp
private val INDICATOR_INSET = 3.dp
private val INDICATOR_MIN_LENGTH = 24.dp
private const val INDICATOR_ALPHA: Float = 0.6f
