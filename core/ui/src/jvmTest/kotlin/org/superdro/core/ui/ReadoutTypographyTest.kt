package org.superdro.core.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.superdro.core.model.ReadoutFont
import org.superdro.core.ui.theme.AppTheme
import org.superdro.core.ui.theme.LocalReadoutTypography
import org.superdro.core.ui.theme.TABULAR_FIGURES

/**
 * Each readout font renders (one PNG file for each font: `readout-font-<name>.png`), and
 * the readout style has tabular figures. A font of the Compose resources loads after some frames,
 * so each font gets its own scene.
 */
class ReadoutTypographyTest {

    @Test
    fun eachReadoutFontRenders() {
        ReadoutFont.entries.forEach { font ->
            var feature: String? = null
            val file = ScreenshotRenderer.render("readout-font-${font.name}", widthDp = 520, heightDp = 100, frames = 40) {
                AppTheme(systemInDarkTheme = true, readoutFont = font) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                        val style = LocalReadoutTypography.current.digits
                        feature = style.fontFeatureSettings
                        Text("-0125.430 Il1", style = style, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            assertTrue(file.length() > 0, font.name)
            assertEquals(TABULAR_FIGURES, feature, font.name)
        }
    }
}
