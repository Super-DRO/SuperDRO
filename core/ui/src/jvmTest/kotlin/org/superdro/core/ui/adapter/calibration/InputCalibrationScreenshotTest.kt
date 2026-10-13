package org.superdro.core.ui.adapter.calibration

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue
import org.superdro.core.model.ThemeMode
import org.superdro.core.ui.ScreenshotRenderer
import org.superdro.core.ui.adaptive.LocalWindowLayout
import org.superdro.core.ui.adaptive.WindowLayout
import org.superdro.core.ui.theme.AppTheme

/**
 * Draws each step of the calibration wizard to PNG files (`core/ui/build/screenshots/calibration-*.png`)
 * in light and dark mode, in landscape (1280 × 800) and portrait (800 × 1280).
 */
class InputCalibrationScreenshotTest {

    private val states = listOf(
        "block" to CalibrationSamples.block,
        "block-too-small" to CalibrationSamples.blockTooSmall,
        "first-edge" to CalibrationSamples.firstEdge,
        "waiting" to CalibrationSamples.waiting,
        "sampling" to CalibrationSamples.sampling,
        "jitter" to CalibrationSamples.jitter,
        "result" to CalibrationSamples.result,
        "angular-first" to CalibrationSamples.angularFirst,
        "angular-result" to CalibrationSamples.angularResult,
        "blocked" to CalibrationSamples.blocked,
    )

    @Test
    fun eachStepInLightAndDarkMode() {
        states.forEach { (name, state) ->
            listOf(1280 to 800, 800 to 1280).forEach { (width, height) ->
                listOf(false, true).forEach { dark ->
                    val file = ScreenshotRenderer.render("calibration-$name-${width}x$height-${if (dark) "dark" else "light"}", widthDp = width, heightDp = height, frames = 30) {
                        AppTheme(mode = if (dark) ThemeMode.Dark else ThemeMode.System, systemInDarkTheme = false) {
                            CompositionLocalProvider(LocalWindowLayout provides WindowLayout.of(width.dp, height.dp)) {
                                InputCalibrationScreen(state, NoCalibrationEvents)
                            }
                        }
                    }
                    assertTrue(file.length() > 0, name)
                }
            }
        }
    }
}

/** Events that do nothing. */
internal object NoCalibrationEvents : CalibrationEvents {
    override fun onBlockTextChange(text: String) = Unit
    override fun onBlockUnitChange(metric: Boolean) = Unit
    override fun onContinue() = Unit
    override fun onCancel() = Unit
    override fun onApply() = Unit
    override fun onDiscard() = Unit
    override fun onProblemRetry() = Unit
    override fun onProblemContinue() = Unit
    override fun onProblemDismiss() = Unit
}
