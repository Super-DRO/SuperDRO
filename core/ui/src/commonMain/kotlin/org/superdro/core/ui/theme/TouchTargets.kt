package org.superdro.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The touch targets of the shop floor: the axis buttons and the
 * function buttons have 64 dp or more, the keypad keys have 72 dp, and the gaps have 12 dp. The
 * app setting "Large buttons" adds 25 % to each value.
 *
 * @property button The minimum size of an axis button and of a function button.
 * @property keypadKey The minimum size of a keypad key.
 * @property gap The gap between two buttons.
 */
@Immutable
data class TouchTargets(
    val button: Dp = BUTTON,
    val keypadKey: Dp = KEYPAD_KEY,
    val gap: Dp = GAP,
) {
    companion object {
        val BUTTON: Dp = 64.dp
        val KEYPAD_KEY: Dp = 72.dp
        val GAP: Dp = 12.dp

        /** The factor of "Large buttons": +25 %. */
        const val LARGE_FACTOR: Float = 1.25f

        /** The normal values. */
        val Normal: TouchTargets = TouchTargets()

        /** The values with "Large buttons" on: 80 dp, 90 dp, and 15 dp. */
        val Large: TouchTargets = TouchTargets(BUTTON * LARGE_FACTOR, KEYPAD_KEY * LARGE_FACTOR, GAP * LARGE_FACTOR)

        /** Gives the values of the setting [largeButtons]. */
        fun of(largeButtons: Boolean): TouchTargets = if (largeButtons) Large else Normal
    }
}

/** The touch targets of the theme. [AppTheme] gives the value of the "Large buttons" setting. */
val LocalTouchTargets = staticCompositionLocalOf { TouchTargets.Normal }
