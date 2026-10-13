package org.superdro.core.ui.graphics.view

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import org.superdro.core.functions.graphics.ScreenMetrics

@Composable
actual fun rememberScreenMetrics(): ScreenMetrics {
    val metrics = LocalContext.current.resources.displayMetrics
    return remember(metrics.xdpi, metrics.ydpi, metrics.density) {
        // Some devices report no physical density. Then the value of the density class applies.
        val fallback = metrics.densityDpi.toDouble()
        ScreenMetrics(
            xdpi = metrics.xdpi.toDouble().takeIf { value -> value > 0.0 } ?: fallback,
            ydpi = metrics.ydpi.toDouble().takeIf { value -> value > 0.0 } ?: fallback,
            density = metrics.density.toDouble(),
        )
    }
}
