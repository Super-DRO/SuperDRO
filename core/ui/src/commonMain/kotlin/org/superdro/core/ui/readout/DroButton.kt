package org.superdro.core.ui.readout

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.superdro.core.ui.sound.KeyFeedback
import org.superdro.core.ui.theme.LocalTouchTargets

/**
 * The key feedback of the readout of the tab: haptic feedback and the short or the
 * long sound of the machine. The readout entry gives it with
 * [rememberKeyFeedback][org.superdro.core.ui.sound.rememberKeyFeedback]. Null in a preview.
 */
val LocalReadoutFeedback = staticCompositionLocalOf<KeyFeedback?> { null }

/** The look of a [DroButton]. */
enum class DroButtonStyle {
    /** A large button: `primaryContainer` with `onPrimaryContainer`. */
    Primary,

    /** A button of the function strip: `surfaceVariant` with `onSurfaceVariant` (visible on `surface` in the two modes). */
    Neutral,

    /** An active or a toggled button: `tertiaryContainer` with `onTertiaryContainer`. */
    Active,
}

/**
 * A shop-floor button with a tap and an optional long press. The minimum
 * size is the touch target: 64 dp, or 80 dp with "Large buttons". A tap gives the short
 * feedback and an accepted long press gives the long feedback.
 *
 * @param contentDescription The text of the button for the accessibility service.
 * @param stateDescription The state of the button, for example "Active".
 */
@Composable
fun DroButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    contentDescription: String? = null,
    stateDescription: String? = null,
    style: DroButtonStyle = DroButtonStyle.Primary,
    minWidth: Dp = LocalTouchTargets.current.button,
    minHeight: Dp = LocalTouchTargets.current.button,
    content: @Composable RowScope.() -> Unit,
) {
    val feedback = LocalReadoutFeedback.current
    val colors = MaterialTheme.colorScheme
    val (container, onContainer) = when (style) {
        DroButtonStyle.Primary -> colors.primaryContainer to colors.onPrimaryContainer
        DroButtonStyle.Neutral -> colors.surfaceVariant to colors.onSurface
        DroButtonStyle.Active -> colors.tertiaryContainer to colors.onTertiaryContainer
    }
    val shape = MaterialTheme.shapes.medium
    Surface(
        modifier = modifier
            .defaultMinSize(minWidth = minWidth, minHeight = minHeight)
            .clip(shape)
            .combinedClickable(
                role = Role.Button,
                onLongClick = onLongClick?.let { action ->
                    {
                        feedback?.longPress()
                        action()
                    }
                },
                onClick = {
                    feedback?.tap()
                    onClick()
                },
            )
            .semantics {
                if (contentDescription != null) this.contentDescription = contentDescription
                if (stateDescription != null) this.stateDescription = stateDescription
            },
        shape = shape,
        color = container,
        contentColor = onContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}
