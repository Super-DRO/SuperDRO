package org.superdro.core.ui.readout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue
import org.superdro.core.model.ThemeMode
import org.superdro.core.resources.Res
import org.superdro.core.resources.readout_hint_zero
import org.superdro.core.resources.route_graphical_view
import org.superdro.core.resources.route_sub_datums
import org.superdro.core.ui.ScreenshotRenderer
import org.superdro.core.ui.adaptive.LocalWindowLayout
import org.superdro.core.ui.adaptive.WindowLayout
import org.superdro.core.ui.navigation.SidePanelPlaceholder
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.theme.AppTheme

/**
 * Draws the readout screen to PNG files (`core/ui/build/screenshots/readout-*.png`) in light and
 * dark mode at the widths 600 dp, 840 dp, and 1200 dp, in landscape (height = width × 10 / 16)
 * and portrait (height = width × 16 / 10), for a mill and a lathe. The
 * states come from [ReadoutSamples], not from the fake adapter.
 */
class ReadoutScreenshotTest {

    private val widths = listOf(600, 840, 1200)

    @Test
    fun theMillAtEachWidthAndOrientation() {
        renderAll("mill") { ReadoutSamples.mill(hint = UiText.of(Res.string.readout_hint_zero)) }
    }

    @Test
    fun theLatheAtEachWidthAndOrientation() {
        renderAll("lathe") { ReadoutSamples.lathe() }
    }

    @Test
    fun theMillWithTheSidePanelOpen() {
        val sample = ReadoutSamples.mill()
        val open = ReadoutSamples.Sample(sample.state.copy(sidePanel = SidePanelUiState(org.superdro.core.model.SidePanelMode.SubDatums, toolsEnabled = true)), sample.values)
        val file = render("readout-mill-1200-landscape-panel-dark", open, 1200, 750, dark = true)
        assertTrue(file.length() > 0)
    }

    private fun renderAll(machine: String, sample: () -> ReadoutSamples.Sample) {
        widths.forEach { width ->
            listOf("landscape" to width * 10 / 16, "portrait" to width * 16 / 10).forEach { (orientation, height) ->
                listOf(false, true).forEach { dark ->
                    val name = "readout-$machine-$width-$orientation-${if (dark) "dark" else "light"}"
                    val file = render(name, sample(), width, height, dark)
                    assertTrue(file.length() > 0, name)
                }
            }
        }
    }

    private fun render(name: String, sample: ReadoutSamples.Sample, width: Int, height: Int, dark: Boolean) =
        ScreenshotRenderer.render(name, widthDp = width, heightDp = height, frames = 30) {
            Screen(sample, WindowLayout.of(width.dp, height.dp), dark)
        }

    @Composable
    private fun Screen(sample: ReadoutSamples.Sample, layout: WindowLayout, dark: Boolean) {
        AppTheme(mode = if (dark) ThemeMode.Dark else ThemeMode.System, systemInDarkTheme = false) {
            CompositionLocalProvider(LocalWindowLayout provides layout) {
                ReadoutScreen(
                    state = sample.state,
                    axisValue = sample::flow,
                    events = NoReadoutEvents,
                    layout = layout,
                    sidePanelContent = { SidePanelPlaceholder(Res.string.route_sub_datums) },
                    graphicalViewPane = { SidePanelPlaceholder(Res.string.route_graphical_view) },
                )
            }
        }
    }
}
