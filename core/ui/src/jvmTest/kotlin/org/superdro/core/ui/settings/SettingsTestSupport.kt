package org.superdro.core.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import java.io.File
import org.superdro.core.model.ThemeMode
import org.superdro.core.ui.ScreenshotRenderer
import org.superdro.core.ui.adaptive.LocalWindowLayout
import org.superdro.core.ui.adaptive.WidthClass
import org.superdro.core.ui.adaptive.WindowLayout
import org.superdro.core.ui.theme.AppTheme

/** The two window sizes of the settings screenshots: a landscape tablet and a portrait tablet. */
enum class SettingsWindow(val widthDp: Int, val heightDp: Int, val layout: WindowLayout) {
    Landscape(1200, 750, WindowLayout(WidthClass.Expanded, landscape = true)),
    Portrait(800, 1280, WindowLayout(WidthClass.Medium, landscape = false)),
}

/** The theme and the window layout of a settings screenshot or a UI test. */
@Composable
fun SettingsThemed(dark: Boolean, window: SettingsWindow = SettingsWindow.Landscape, content: @Composable () -> Unit) {
    AppTheme(mode = if (dark) ThemeMode.Dark else ThemeMode.System, systemInDarkTheme = false) {
        CompositionLocalProvider(LocalWindowLayout provides window.layout) { content() }
    }
}

/**
 * Draws [content] in light and dark mode for each window of [windows] and gives the files. The
 * names are `settings-<name>-<window>-<light|dark>.png` in `core/ui/build/screenshots/`.
 */
fun renderSettings(
    name: String,
    windows: List<SettingsWindow> = SettingsWindow.entries,
    content: @Composable (SettingsWindow) -> Unit,
): List<File> = buildList {
    listOf(false, true).forEach { dark ->
        windows.forEach { window ->
            val theme = if (dark) "dark" else "light"
            add(
                ScreenshotRenderer.render("settings-$name-${window.name.lowercase()}-$theme", window.widthDp, window.heightDp) {
                    SettingsThemed(dark, window) { content(window) }
                },
            )
        }
    }
}
