package org.superdro.core.ui.graphics.view

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import org.superdro.core.functions.graphics.ScreenMetrics

/** The pixels for each inch of one dp: 160 (the Android base density). */
private const val DPI_PER_DENSITY: Double = 160.0

@Composable
actual fun rememberScreenMetrics(): ScreenMetrics {
    val density = LocalDensity.current.density.toDouble()
    return remember(density) { ScreenMetrics(xdpi = density * DPI_PER_DENSITY, ydpi = density * DPI_PER_DENSITY, density = density) }
}
