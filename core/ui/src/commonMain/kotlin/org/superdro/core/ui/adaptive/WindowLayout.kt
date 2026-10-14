package org.superdro.core.ui.adaptive

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass

/**
 * The window width classes. Large and extra-large use the expanded layout.
 */
enum class WidthClass {
    /** Less than 600 dp: a phone (later). */
    Compact,

    /** 600 dp to 839 dp: a portrait tablet. */
    Medium,

    /** 840 dp and more: a landscape tablet, and the large and extra-large classes. */
    Expanded,
}

/**
 * The layout of the window.
 *
 * | Window class | Readout | Function strip | Side panel | Graphical view |
 * |---|---|---|---|---|
 * | Expanded and more | Left column, about 45 % | Grid below the readout | Supporting pane | Supporting pane, or full screen |
 * | Medium | Top, at the left of the rail | Vertical rail at the right of the readout | Bottom sheet | Below or above the readout (about 60 %), or full screen |
 * | Compact | Full width, smaller rows | Bottom sheet from a button | Bottom sheet | Full-screen entry |
 *
 * @property landscape True when the window is wider than tall. A sheet is a dialog in landscape
 *   and a `ModalBottomSheet` in portrait.
 */
@Immutable
data class WindowLayout(val widthClass: WidthClass, val landscape: Boolean) {

    /**
     * True for the expanded layout: the readout pane, the supporting pane, and the rail. The
     * window class comes from the width only: a portrait window of 840 dp or
     * more (for example a large tablet in portrait) also uses the expanded layout. The SM-X230
     * (800 dp wide in portrait) uses the medium layout in portrait.
     */
    val expanded: Boolean get() = widthClass == WidthClass.Expanded

    companion object {
        /** The breakpoint of the medium class. */
        val MEDIUM_MIN: Dp = WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND.dp

        /** The breakpoint of the expanded class. */
        val EXPANDED_MIN: Dp = WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND.dp

        /** Gives the layout of a window of [width] × [height]. */
        fun of(width: Dp, height: Dp): WindowLayout = WindowLayout(
            widthClass = when {
                width >= EXPANDED_MIN -> WidthClass.Expanded
                width >= MEDIUM_MIN -> WidthClass.Medium
                else -> WidthClass.Compact
            },
            landscape = width > height,
        )

        /** Gives the layout of a window size class of the adaptive library. */
        fun of(sizeClass: WindowSizeClass, landscape: Boolean): WindowLayout = WindowLayout(
            widthClass = when {
                sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND) -> WidthClass.Expanded
                sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) -> WidthClass.Medium
                else -> WidthClass.Compact
            },
            landscape = landscape,
        )
    }
}

/** The layout of the window. [App][org.superdro.core.ui.App] gives it. A test can give a fixed value. */
val LocalWindowLayout = compositionLocalOf { WindowLayout(WidthClass.Expanded, landscape = true) }

/**
 * Gives the layout of the current window with the window size classes of the Material 3 adaptive
 * library. Version 1.3.0-alpha02 has no `currentWindowAdaptiveInfoV2()`; its
 * `currentWindowAdaptiveInfo(supportLargeAndXLargeWidth = true)` gives the same V2 classes.
 */
@Composable
fun currentWindowLayout(): WindowLayout {
    val info = currentWindowAdaptiveInfo(supportLargeAndXLargeWidth = true)
    val size = LocalWindowInfo.current.containerSize
    return WindowLayout.of(info.windowSizeClass, landscape = size.width > size.height)
}
