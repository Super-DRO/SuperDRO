package org.superdro.core.ui.graphics.image

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.ThemeMode
import org.superdro.core.ui.ScreenshotRenderer
import org.superdro.core.ui.adaptive.LocalWindowLayout
import org.superdro.core.ui.adaptive.WindowLayout
import org.superdro.core.ui.graphics.GraphicsHarness
import org.superdro.core.ui.theme.AppTheme

/**
 * Draws the reference image setup screen to PNG files
 * (`core/ui/build/screenshots/graphics-image-setup-<landscape|portrait>-<light|dark>.png`) with a
 * grid picture and two set references, and the opacity button with the delete question.
 */
class ReferenceImageScreenshotTest {

    @Test
    fun theSetupScreenInLandscapeAndPortrait() = runTest {
        val harness = GraphicsHarness(this)
        harness.start(MachineProfile.create(MachineType.VerticalMill, "Mill").copy(metricDefault = true))
        harness.moveTo(100L, x = 12700L, y = 6350L, z = 0L)
        val image = PlatformImages.decode(TestImages.gridPng(800, 600, cell = 40))!!
        val presenter = ReferenceImageSetupPresenter(harness.deviceId, harness.readout, harness.deps, image)
        try {
            listOf(
                Triple("landscape", 1280 to 800, 879.0 to 560.0),
                Triple("portrait", 800 to 1280, 800.0 to 450.0),
            ).forEach { (orientation, window, view) ->
                presenter.onClearAll()
                presenter.onViewSize(view.first, view.second)
                presenter.onResetView()
                advanceUntilIdle()
                val s = presenter.uiState.value.scale
                // Reference 1 on the dot at (200, 150), reference 2 on the dot at (600, 450).
                presenter.onPan((400.0 - 200.0) * s, (300.0 - 150.0) * s)
                presenter.onHorizontalText(0, "10")
                presenter.onVerticalText(0, "20")
                presenter.onSetOrClear(0)
                presenter.onResetView()
                presenter.onPan((400.0 - 600.0) * s, (300.0 - 450.0) * s)
                presenter.onHorizontalText(1, "50")
                presenter.onVerticalText(1, "0")
                presenter.onSetOrClear(1)
                presenter.onResetView()
                advanceUntilIdle()
                val state = presenter.uiState.value
                val bitmap = presenter.bitmap.value
                listOf(false, true).forEach { dark ->
                    val name = "graphics-image-setup-$orientation-${if (dark) "dark" else "light"}"
                    val file = ScreenshotRenderer.render(name, widthDp = window.first, heightDp = window.second, frames = 30) {
                        AppTheme(mode = if (dark) ThemeMode.Dark else ThemeMode.System, systemInDarkTheme = false) {
                            CompositionLocalProvider(LocalWindowLayout provides WindowLayout.of(window.first.dp, window.second.dp)) {
                                ReferenceImageSetupScreen(state = state, bitmap = bitmap, events = RecordingSetupEvents(), onCancel = {})
                            }
                        }
                    }
                    assertTrue(file.length() > 0, name)
                }
            }
        } finally {
            presenter.close()
            harness.close()
        }
    }

    @Test
    fun theOpacityButtonAndTheDeleteQuestion() {
        listOf(false, true).forEach { dark ->
            val name = "graphics-image-opacity-${if (dark) "dark" else "light"}"
            val file = ScreenshotRenderer.render(name, widthDp = 700, heightDp = 420, frames = 20) {
                AppTheme(mode = if (dark) ThemeMode.Dark else ThemeMode.System, systemInDarkTheme = false) {
                    Surface(color = MaterialTheme.colorScheme.surface) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ReferenceImageOpacityButton(alpha = 255, hasImage = true, onTap = {}, onLongPress = {})
                            ReferenceImageOpacityButton(alpha = 127, hasImage = true, onTap = {}, onLongPress = {})
                            ReferenceImageOpacityButton(alpha = 0, hasImage = true, onTap = {}, onLongPress = {})
                            ReferenceImageOpacityButton(alpha = 255, hasImage = false, onTap = {}, onLongPress = {})
                        }
                        DeleteReferenceImageDialog(onConfirm = {}, onDismiss = {})
                    }
                }
            }
            assertTrue(file.length() > 0, name)
        }
    }
}
