package org.superdro.core.ui.graphics.drawing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue
import org.superdro.core.model.ThemeMode
import org.superdro.core.ui.ScreenshotRenderer
import org.superdro.core.ui.adaptive.LocalWindowLayout
import org.superdro.core.ui.adaptive.WindowLayout
import org.superdro.core.ui.theme.AppTheme

/**
 * Draws the drawing screens to PNG files in light and dark mode
 * (`core/ui/build/screenshots/graphics-drawing-*.png`).
 */
class DrawingScreenshotTest {

    @Composable
    private fun Themed(width: Int, height: Int, dark: Boolean, content: @Composable () -> Unit) {
        AppTheme(mode = if (dark) ThemeMode.Dark else ThemeMode.System, systemInDarkTheme = false) {
            CompositionLocalProvider(LocalWindowLayout provides WindowLayout.of(width.dp, height.dp)) { content() }
        }
    }

    private fun modes(block: (Boolean, String) -> Unit) {
        block(false, "light")
        block(true, "dark")
    }

    @Test
    fun theSetupScreen() {
        val sample = DrawingSamples.setupState()
        listOf("landscape" to (1280 to 800), "portrait" to (800 to 1280)).forEach { (orientation, size) ->
            val (width, height) = size
            modes { dark, mode ->
                val file = ScreenshotRenderer.render("graphics-drawing-setup-$orientation-$mode", widthDp = width, heightDp = height, frames = 30) {
                    Themed(width, height, dark) {
                        var state by remember { mutableStateOf(sample) }
                        val events = remember {
                            RecordingSetupEvents().apply { onSize = { w, h -> state = DrawingSamples.fitted(state, w, h) } }
                        }
                        DrawingSetupScreen(state, events)
                    }
                }
                assertTrue(file.length() > 0)
            }
        }
    }

    @Test
    fun theProblemScreen() {
        modes { dark, mode ->
            val file = ScreenshotRenderer.render("graphics-drawing-problems-$mode", widthDp = 1280, heightDp = 800, frames = 30) {
                Themed(1280, 800, dark) {
                    DrawingProblemsScreen(DrawingSamples.problems, onDone = {}, detailsShown = true)
                }
            }
            assertTrue(file.length() > 0)
        }
    }

    @Test
    fun theToolGeometryScreen() {
        val sample = DrawingSamples.toolGeometryState()
        modes { dark, mode ->
            val file = ScreenshotRenderer.render("graphics-drawing-toolgeometry-$mode", widthDp = 1280, heightDp = 800, frames = 30) {
                Themed(1280, 800, dark) {
                    ToolGeometryScreen(sample, object : ToolGeometryEvents {
                        override fun onImport() = Unit
                        override fun onDrawnIn(inch: Boolean) = Unit
                        override fun onAttach() = Unit
                        override fun onCancelPreview() = Unit
                        override fun onRemove() = Unit
                        override fun onDone() = Unit
                    })
                }
            }
            assertTrue(file.length() > 0)
        }
    }
}
