package org.superdro.core.ui.graphics.view

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import org.superdro.core.functions.graphics.ScreenMetrics

@Composable
actual fun rememberScreenMetrics(): ScreenMetrics {
    val metrics = LocalContext.current.resources.displayMetrics
    return remember(metrics.xdpi, metrics.ydpi, metrics.densityDpi, metrics.density) {
        // Some devices report no physical density, or a wrong value for one axis.
        ScreenMetrics.fromReported(
            xdpi = metrics.xdpi.toDouble(),
            ydpi = metrics.ydpi.toDouble(),
            densityDpi = metrics.densityDpi.toDouble(),
            density = metrics.density.toDouble(),
        )
    }
}
