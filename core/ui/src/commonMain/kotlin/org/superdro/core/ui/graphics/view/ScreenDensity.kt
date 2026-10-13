package org.superdro.core.ui.graphics.view

import androidx.compose.runtime.Composable
import org.superdro.core.functions.graphics.ScreenMetrics

/**
 * Gives the physical density of the screen for the graphical view: at
 * the zoom scale 1, one model mm is one physical mm on the screen. Android reads
 * `DisplayMetrics.xdpi` and `ydpi`. The JVM uses 160 dpi for each dp.
 */
@Composable
expect fun rememberScreenMetrics(): ScreenMetrics
