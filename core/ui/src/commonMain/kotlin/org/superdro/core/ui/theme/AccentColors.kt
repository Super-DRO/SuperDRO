package org.superdro.core.ui.theme

import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CheckboxColors
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButtonColors
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ChipColors
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.SelectableChipColors

/**
 * The accent color of a text or a thin line. In light mode,
 * `primary` has only 2.8 : 1 on `surface`, so a text button, an outlined button, and a selection
 * line use `secondary` (6.5 : 1). In dark mode `primary` has 5.8 : 1, so it stays. The Snapshot
 * color values do not change: only the role changes.
 */
@Composable
fun accentColor(): Color {
    val scheme = MaterialTheme.colorScheme
    return if (isLightAccent()) scheme.secondary else scheme.primary
}

/**
 * The color of a text or a mark on a fill of [accentColor]: `onSecondary` in light mode and
 * `onPrimary` in dark mode.
 */
@Composable
fun onAccentColor(): Color {
    val scheme = MaterialTheme.colorScheme
    return if (isLightAccent()) scheme.onSecondary else scheme.onPrimary
}

/** True when the scheme is the light Snapshot scheme, where `primary` is not an accent. */
@Composable
private fun isLightAccent(): Boolean = MaterialTheme.colorScheme.primary == Theme.lightScheme.primary

/** The button colors with [accentColor] for the text. */
object AccentButtons {

    /**
     * The colors of a filled `Button`: `primaryContainer` with `onPrimaryContainer` (7.3 : 1
     * in light mode and 7.2 : 1 in dark mode). The default colors put a white text on `primary`,
     * with only 2.8 : 1 in light mode. This contrast is too low for a text.
     */
    @Composable
    fun filled(): ButtonColors = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    )

    /** The colors of a `TextButton`. */
    @Composable
    fun text(): ButtonColors = ButtonDefaults.textButtonColors(contentColor = accentColor())

    /** The colors of an `OutlinedButton`. */
    @Composable
    fun outlined(): ButtonColors = ButtonDefaults.outlinedButtonColors(contentColor = accentColor())
}

/** The colors of an `OutlinedTextField` with [accentColor] for the focused label, border, and cursor. */
@Composable
fun accentTextFieldColors(): TextFieldColors {
    val accent = accentColor()
    return OutlinedTextFieldDefaults.colors(
        focusedBorderColor = accent,
        focusedLabelColor = accent,
        cursorColor = accent,
    )
}

/** The colors of a filled `TextField` with [accentColor] for the focused label, indicator line, and cursor. */
@Composable
fun accentFilledTextFieldColors(): TextFieldColors {
    val accent = accentColor()
    return TextFieldDefaults.colors(
        focusedIndicatorColor = accent,
        focusedLabelColor = accent,
        cursorColor = accent,
    )
}

/** The colors of a `RadioButton` with [accentColor]. */
@Composable
fun accentRadioColors(): RadioButtonColors = RadioButtonDefaults.colors(selectedColor = accentColor())

/** The colors of a `Checkbox`: a box of [accentColor] with a mark of [onAccentColor]. */
@Composable
fun accentCheckboxColors(): CheckboxColors =
    CheckboxDefaults.colors(checkedColor = accentColor(), checkmarkColor = onAccentColor())

/**
 * The colors of a `FilterChip` with [accentColor] for the leading icon of an unselected chip. The
 * default is `primary`, and an outline icon is a thin line.
 */
@Composable
fun accentFilterChipColors(containerColor: Color = Color.Transparent): SelectableChipColors =
    FilterChipDefaults.filterChipColors(iconColor = accentColor(), containerColor = containerColor)

/** The colors of an `AssistChip` with [accentColor] for the leading icon. */
@Composable
fun accentAssistChipColors(): ChipColors = AssistChipDefaults.assistChipColors(leadingIconContentColor = accentColor())

/** The colors of a `Switch`: a track of [accentColor] with a thumb of [onAccentColor] when it is on. */
@Composable
fun accentSwitchColors(): SwitchColors = SwitchDefaults.colors(
    checkedTrackColor = accentColor(),
    checkedThumbColor = onAccentColor(),
    checkedIconColor = accentColor(),
)
