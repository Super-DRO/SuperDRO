package org.superdro.core.ui.graphics.measure

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.superdro.core.ui.theme.Theme
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The roles of the measurement mode scheme. */
class MeasurementSchemeTest {

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    @Test
    fun theAccentOfTheMeasurementModeIsTheTertiaryRole() {
        listOf(Theme.lightScheme, Theme.darkScheme).forEach { scheme ->
            val measurement = measurementColorScheme(scheme)
            assertEquals(scheme.tertiary, measurement.primary)
            assertEquals(scheme.tertiaryContainer, measurement.primaryContainer)
            assertEquals(scheme.onTertiaryContainer, measurement.onPrimaryContainer)
        }
    }

    @Test
    fun inLightModeTheSelectionColorIsNotThePrimaryColor() {
        val light = measurementColorScheme(Theme.lightScheme)
        // No thin line in primary (#FF6D00, 2.8 : 1) in light mode. The selection uses the tertiary role.
        assertNotEquals(Theme.lightScheme.primary, light.tertiary)
        assertEquals(Theme.lightScheme.onPrimaryContainer, light.tertiary)
        assertTrue(contrast(light.tertiary, Theme.lightScheme.surface) >= 4.5)
        // In dark mode primary has 5.8 : 1, so it stays the selection color.
        val dark = measurementColorScheme(Theme.darkScheme)
        assertEquals(Theme.darkScheme.primary, dark.tertiary)
        assertTrue(contrast(dark.tertiary, Theme.darkScheme.surface) >= 4.5)
    }
}
