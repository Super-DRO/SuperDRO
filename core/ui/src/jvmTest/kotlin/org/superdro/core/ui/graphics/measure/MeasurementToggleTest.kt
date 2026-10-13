package org.superdro.core.ui.graphics.measure

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.superdro.core.ui.theme.AppTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/** The mode chip of the measurement mode on the canvas of the graphical view. */
class MeasurementToggleTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theSceneDoesNotShowThroughTheChipWhenTheModeIsOff() = runComposeUiTest {
        // The chip is over the canvas, and over the camera video when the camera is on. A red scene stands for them.
        listOf(false, true).forEach { dark ->
            setContent {
                AppTheme(systemInDarkTheme = dark) {
                    Box(Modifier.background(Color.Red).padding(16.dp)) {
                        MeasurementModeToggle(on = false, onToggle = {})
                    }
                }
            }
            // Capture the whole scene, not only the layer of the chip, so that a transparent chip shows the red.
            val pixels = onRoot().captureToImage().toPixelMap()
            val chip = onNodeWithTag(MEASUREMENT_MODE_TAG).fetchSemanticsNode().boundsInRoot
            // A transparent chip shows the red scene in most of its bounds. A filled chip shows it only at its
            // round corners.
            var red = 0
            var all = 0
            for (x in chip.left.toInt() until chip.right.toInt()) {
                for (y in chip.top.toInt() until chip.bottom.toInt()) {
                    val pixel = pixels[x, y]
                    all++
                    if (pixel.red > 0.8f && pixel.green < 0.2f && pixel.blue < 0.2f) red++
                }
            }
            assertTrue(red * 4 < all, "the red scene shows through $red of $all pixels (dark = $dark)")
        }
    }
}
