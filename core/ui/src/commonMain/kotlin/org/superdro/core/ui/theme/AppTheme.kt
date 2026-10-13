package org.superdro.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import org.superdro.core.model.ReadoutFont
import org.superdro.core.model.ThemeMode

/**
 * The one theme of SuperDRO. It uses [Theme.lightScheme] or
 * [Theme.darkScheme] of the SnapshotPB `Color.kt` with no change. It has no dynamic color.
 *
 * The theme mode: [ThemeMode.System] follows the system.
 * [ThemeMode.Dark] forces the dark scheme.
 *
 * @param systemInDarkTheme The dark mode of the system. A test gives a fixed value.
 * @param readoutFont The font of the readout digits, from the display settings of the profile.
 * @param largeButtons The app setting "Large buttons": 25 % larger touch targets.
 */
@Composable
fun AppTheme(
    mode: ThemeMode = ThemeMode.System,
    systemInDarkTheme: Boolean = isSystemInDarkTheme(),
    readoutFont: ReadoutFont = ReadoutFont.Dseg7,
    largeButtons: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.System -> systemInDarkTheme
        ThemeMode.Dark -> true
    }
    MaterialTheme(
        colorScheme = if (dark) Theme.darkScheme else Theme.lightScheme,
        typography = AppTypography,
    ) {
        CompositionLocalProvider(
            LocalReadoutTypography provides readoutTypography(readoutFont),
            LocalTouchTargets provides TouchTargets.of(largeButtons),
        ) {
            content()
        }
    }
}
