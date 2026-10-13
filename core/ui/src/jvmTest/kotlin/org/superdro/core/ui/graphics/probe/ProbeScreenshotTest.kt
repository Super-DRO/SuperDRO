package org.superdro.core.ui.graphics.probe

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue
import org.superdro.core.model.ThemeMode
import org.superdro.core.ui.ScreenshotRenderer
import org.superdro.core.ui.adaptive.LocalWindowLayout
import org.superdro.core.ui.adaptive.WindowLayout
import org.superdro.core.ui.theme.AppTheme

/**
 * Draws the edge finder screen and the automatic Z reference to PNG files
 * (`core/ui/build/screenshots/graphics-probe-*.png`) in light and dark mode.
 */
class ProbeScreenshotTest {

    @Composable
    private fun Themed(dark: Boolean, width: Int, height: Int, content: @Composable () -> Unit) {
        AppTheme(mode = if (dark) ThemeMode.Dark else ThemeMode.System, systemInDarkTheme = false) {
            CompositionLocalProvider(LocalWindowLayout provides WindowLayout.of(width.dp, height.dp), content = content)
        }
    }

    @Test
    fun theEdgeFinderInLandscapeAndPortrait() {
        listOf("landscape" to (1280 to 800), "portrait" to (800 to 1280)).forEach { (orientation, size) ->
            listOf(false, true).forEach { dark ->
                val name = "graphics-probe-edgefinder-$orientation-${if (dark) "dark" else "light"}"
                val file = ScreenshotRenderer.render(name, widthDp = size.first, heightDp = size.second, frames = 30) {
                    Themed(dark, size.first, size.second) { EdgeFinderScreen(ProbeSamples.edgeFinder(), ProbeSamples.RecordingEvents()) }
                }
                assertTrue(file.length() > 0, name)
            }
        }
    }

    @Test
    fun theAutomaticZReference() {
        listOf(false, true).forEach { dark ->
            val name = "graphics-probe-zauto-${if (dark) "dark" else "light"}"
            val file = ScreenshotRenderer.render(name, widthDp = 640, heightDp = 480, frames = 30) {
                Themed(dark, 640, 480) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        ZReferenceAutoSection(ProbeSamples.zAuto(), onToggle = {}, onSelectProbe = {}, onNewProbe = {}, modifier = Modifier.padding(24.dp))
                    }
                }
            }
            assertTrue(file.length() > 0, name)
        }
    }
}
