package org.superdro.app.adapterprobe.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButtonColors
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// The Snapshot colors of SuperDRO (a copy of core:ui, theme/Color.kt). The probe app does not
// depend on core:ui, so it has its own copy of the two color schemes.

private val primaryLight = Color(0xFFFF6D00)
private val onPrimaryLight = Color.White
private val primaryContainerLight = Color(0xFFFFDBCF)
private val onPrimaryContainerLight = Color(0xFF72351F)
private val secondaryLight = Color(0xFF77574C)
private val onSecondaryLight = Color(0xFFFFFFFF)
private val secondaryContainerLight = Color(0xFFFFDBCF)
private val onSecondaryContainerLight = Color(0xFF5D4036)
private val tertiaryLight = Color(0xFF7A4F80)
private val onTertiaryLight = Color(0xFFFFFFFF)
private val tertiaryContainerLight = Color(0xFFFED6FF)
private val onTertiaryContainerLight = Color(0xFF603767)
private val errorLight = Color(0xFFBA1A1A)
private val onErrorLight = Color(0xFFFFFFFF)
private val errorContainerLight = Color(0xFFFFDAD6)
private val onErrorContainerLight = Color(0xFF93000A)
private val backgroundLight = Color(0xFFF3F4F6)
private val onBackgroundLight = Color(0xFF231A16)
private val surfaceLight = Color.White
private val onSurfaceLight = Color(0xFF1F1F1F)
private val surfaceVariantLight = Color(0xFFE5E7EB)
private val onSurfaceVariantLight = Color(0xFF53433E)
private val outlineLight = Color(0xFF85736D)
private val outlineVariantLight = Color(0xFFD8C2BB)
private val scrimLight = Color(0xFF000000)
private val inverseSurfaceLight = Color(0xFF392E2B)
private val inverseOnSurfaceLight = Color(0xFFFFEDE8)
private val inversePrimaryLight = Color(0xFFFFB59C)
private val surfaceDimLight = Color(0xFFE8D6D1)
private val surfaceBrightLight = Color(0xFFFFF8F6)
private val surfaceContainerLowestLight = Color(0xFFFFFFFF)
private val surfaceContainerLowLight = Color(0xFFFFF1EC)
private val surfaceContainerLight = Color(0xFFFCEAE5)
private val surfaceContainerHighLight = Color(0xFFF7E4DF)
private val surfaceContainerHighestLight = Color.White
private val primaryDark = Color(0xFF42A5F5)
private val onPrimaryDark = Color(0xFF003258)
private val primaryContainerDark = Color(0xFF00497D)
private val onPrimaryContainerDark = Color(0xFFD1E4FF)
private val secondaryDark = Color(0xFF80CBC4)
private val onSecondaryDark = Color(0xFF003733)
private val secondaryContainerDark = Color(0xFF005049)
private val onSecondaryContainerDark = Color(0xFF9DF1E9)
private val tertiaryDark = Color(0xFFB39DDB)
private val onTertiaryDark = Color(0xFF261642)
private val tertiaryContainerDark = Color(0xFF3F2C5D)
private val onTertiaryContainerDark = Color(0xFFEADDFF)
private val errorDark = Color(0xFFFFB4AB)
private val onErrorDark = Color(0xFF690005)
private val errorContainerDark = Color(0xFF93000A)
private val onErrorContainerDark = Color(0xFFFFDAD6)
private val backgroundDark = Color(0xFF101418)
private val onBackgroundDark = Color(0xFFECF0F1)
private val surfaceDark = Color(0xFF1E252D)
private val onSurfaceDark = Color(0xFFECF0F1)
private val surfaceVariantDark = Color(0xFF2C353D)
private val onSurfaceVariantDark = Color(0xFFA3AAB2)
private val outlineDark = Color(0xFF454F5B)
private val outlineVariantDark = Color(0xFF2C353D)
private val scrimDark = Color(0xFF000000)
private val inverseSurfaceDark = Color(0xFFECF0F1)
private val inverseOnSurfaceDark = Color(0xFF101418)
private val inversePrimaryDark = Color(0xFF1960A5)
private val surfaceDimDark = Color(0xFF101418)
private val surfaceBrightDark = Color(0xFF36414D)
private val surfaceContainerLowestDark = Color(0xFF0C0E11)
private val surfaceContainerLowDark = Color(0xFF161B21)
private val surfaceContainerDark = Color(0xFF1E252D)
private val surfaceContainerHighDark = Color(0xFF29323C)
private val surfaceContainerHighestDark = Color(0xFF36414D)

/** The light scheme of the Snapshot colors. */
private val lightScheme: ColorScheme = lightColorScheme(
    primary = primaryLight,
    onPrimary = onPrimaryLight,
    primaryContainer = primaryContainerLight,
    onPrimaryContainer = onPrimaryContainerLight,
    secondary = secondaryLight,
    onSecondary = onSecondaryLight,
    secondaryContainer = secondaryContainerLight,
    onSecondaryContainer = onSecondaryContainerLight,
    tertiary = tertiaryLight,
    onTertiary = onTertiaryLight,
    tertiaryContainer = tertiaryContainerLight,
    onTertiaryContainer = onTertiaryContainerLight,
    error = errorLight,
    onError = onErrorLight,
    errorContainer = errorContainerLight,
    onErrorContainer = onErrorContainerLight,
    background = backgroundLight,
    onBackground = onBackgroundLight,
    surface = surfaceLight,
    onSurface = onSurfaceLight,
    surfaceVariant = surfaceVariantLight,
    onSurfaceVariant = onSurfaceVariantLight,
    outline = outlineLight,
    outlineVariant = outlineVariantLight,
    scrim = scrimLight,
    inverseSurface = inverseSurfaceLight,
    inverseOnSurface = inverseOnSurfaceLight,
    inversePrimary = inversePrimaryLight,
    surfaceDim = surfaceDimLight,
    surfaceBright = surfaceBrightLight,
    surfaceContainerLowest = surfaceContainerLowestLight,
    surfaceContainerLow = surfaceContainerLowLight,
    surfaceContainer = surfaceContainerLight,
    surfaceContainerHigh = surfaceContainerHighLight,
    surfaceContainerHighest = surfaceContainerHighestLight,
)

/** The dark scheme of the Snapshot colors. */
private val darkScheme: ColorScheme = darkColorScheme(
    primary = primaryDark,
    onPrimary = onPrimaryDark,
    primaryContainer = primaryContainerDark,
    onPrimaryContainer = onPrimaryContainerDark,
    secondary = secondaryDark,
    onSecondary = onSecondaryDark,
    secondaryContainer = secondaryContainerDark,
    onSecondaryContainer = onSecondaryContainerDark,
    tertiary = tertiaryDark,
    onTertiary = onTertiaryDark,
    tertiaryContainer = tertiaryContainerDark,
    onTertiaryContainer = onTertiaryContainerDark,
    error = errorDark,
    onError = onErrorDark,
    errorContainer = errorContainerDark,
    onErrorContainer = onErrorContainerDark,
    background = backgroundDark,
    onBackground = onBackgroundDark,
    surface = surfaceDark,
    onSurface = onSurfaceDark,
    surfaceVariant = surfaceVariantDark,
    onSurfaceVariant = onSurfaceVariantDark,
    outline = outlineDark,
    outlineVariant = outlineVariantDark,
    scrim = scrimDark,
    inverseSurface = inverseSurfaceDark,
    inverseOnSurface = inverseOnSurfaceDark,
    inversePrimary = inversePrimaryDark,
    surfaceDim = surfaceDimDark,
    surfaceBright = surfaceBrightDark,
    surfaceContainerLowest = surfaceContainerLowestDark,
    surfaceContainerLow = surfaceContainerLowDark,
    surfaceContainer = surfaceContainerDark,
    surfaceContainerHigh = surfaceContainerHighDark,
    surfaceContainerHighest = surfaceContainerHighestDark,
)

/** The theme of the probe app: Material 3 with the Snapshot colors in light and dark mode. */
@Composable
fun ProbeTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) darkScheme else lightScheme, content = content)
}

/**
 * The color of a text that the Material 3 defaults give in `primary`: the text of a tab, a text
 * button, and an outlined button. In light mode `primary` has only 2.8 : 1 on `surface`, so the
 * text uses `onSurface`. In dark mode `primary` has 5.8 : 1 and stays. The Snapshot color values do
 * not change: only the role changes.
 */
@Composable
fun probeAccentText(): Color {
    val scheme = MaterialTheme.colorScheme
    return if (scheme.primary == primaryLight) scheme.onSurface else scheme.primary
}

/**
 * The color of a thin line that the Material 3 defaults give in `primary`: the tab indicator, the
 * border and the cursor of a focused text field, and a selected radio button. In light mode it is
 * `secondary` (6.5 : 1), as in `core:ui`.
 */
@Composable
fun probeAccentLine(): Color {
    val scheme = MaterialTheme.colorScheme
    return if (scheme.primary == primaryLight) scheme.secondary else scheme.primary
}

/** The button colors of the probe app. */
object ProbeButtons {

    /** A `TextButton`: the text in [probeAccentText]. */
    @Composable
    fun text(): ButtonColors = ButtonDefaults.textButtonColors(contentColor = probeAccentText())

    /** An `OutlinedButton`: the text in [probeAccentText]. */
    @Composable
    fun outlined(): ButtonColors = ButtonDefaults.outlinedButtonColors(contentColor = probeAccentText())

    /** A filled `Button`: `primaryContainer` with `onPrimaryContainer` (7.3 : 1 light, 7.2 : 1 dark). */
    @Composable
    fun filled(): ButtonColors = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    )
}

/** The colors of an `OutlinedTextField`: the focused label in [probeAccentText], the border and the cursor in [probeAccentLine]. */
@Composable
fun probeTextFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = probeAccentLine(),
    focusedLabelColor = probeAccentText(),
    cursorColor = probeAccentLine(),
)

/** The colors of a `RadioButton`: the selected color in [probeAccentLine]. */
@Composable
fun probeRadioColors(): RadioButtonColors = RadioButtonDefaults.colors(selectedColor = probeAccentLine())
