package org.superdro.validation.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.superdro.core.model.AppSettings
import org.superdro.core.model.ThemeMode
import org.superdro.core.ui.theme.AppTheme
import org.superdro.core.ui.theme.Theme

/**
 * The Snapshot color schemes and the contrast of the roles.
 * The expected values are the hex values of the table of the 35 roles, and the ratios of
 * the contrast tables. The test computes each ratio with the
 * WCAG formula from the colors of `Theme.lightScheme` and `Theme.darkScheme`.
 */
class ThemeValidationTest {

    /** The main roles of the role table: role, light, dark. */
    private val mainRoles: List<Triple<String, String, String>> = listOf(
        Triple("primary", "#FF6D00", "#42A5F5"),
        Triple("onPrimary", "#FFFFFF", "#003258"),
        Triple("primaryContainer", "#FFDBCF", "#00497D"),
        Triple("onPrimaryContainer", "#72351F", "#D1E4FF"),
        Triple("secondary", "#77574C", "#80CBC4"),
        Triple("tertiary", "#7A4F80", "#B39DDB"),
        Triple("tertiaryContainer", "#FED6FF", "#3F2C5D"),
        Triple("onTertiaryContainer", "#603767", "#EADDFF"),
        Triple("error", "#BA1A1A", "#FFB4AB"),
        Triple("errorContainer", "#FFDAD6", "#93000A"),
        Triple("onErrorContainer", "#93000A", "#FFDAD6"),
        Triple("surface", "#FFFFFF", "#1E252D"),
        Triple("onSurface", "#1F1F1F", "#ECF0F1"),
        Triple("onSurfaceVariant", "#53433E", "#A3AAB2"),
        Triple("outline", "#85736D", "#454F5B"),
        Triple("surfaceContainerLowest", "#FFFFFF", "#0C0E11"),
    )

    /** The other roles of the role table (35 roles in all). */
    private val otherRoles: List<Triple<String, String, String>> = listOf(
        Triple("onSecondary", "#FFFFFF", "#003733"),
        Triple("secondaryContainer", "#FFDBCF", "#005049"),
        Triple("onSecondaryContainer", "#5D4036", "#9DF1E9"),
        Triple("onTertiary", "#FFFFFF", "#261642"),
        Triple("onError", "#FFFFFF", "#690005"),
        Triple("background", "#F3F4F6", "#101418"),
        Triple("onBackground", "#231A16", "#ECF0F1"),
        Triple("surfaceVariant", "#E5E7EB", "#2C353D"),
        Triple("outlineVariant", "#D8C2BB", "#2C353D"),
        Triple("inverseSurface", "#392E2B", "#ECF0F1"),
        Triple("inverseOnSurface", "#FFEDE8", "#101418"),
        Triple("inversePrimary", "#FFB59C", "#1960A5"),
        Triple("surfaceDim", "#E8D6D1", "#101418"),
        Triple("surfaceBright", "#FFF8F6", "#36414D"),
        Triple("surfaceContainerLow", "#FFF1EC", "#161B21"),
        Triple("surfaceContainer", "#FCEAE5", "#1E252D"),
        Triple("surfaceContainerHigh", "#F7E4DF", "#29323C"),
        Triple("surfaceContainerHighest", "#FFFFFF", "#36414D"),
        Triple("scrim", "#000000", "#000000"),
    )

    private fun ColorScheme.role(name: String): String = when (name) {
        "primary" -> primary
        "onPrimary" -> onPrimary
        "primaryContainer" -> primaryContainer
        "onPrimaryContainer" -> onPrimaryContainer
        "secondary" -> secondary
        "onSecondary" -> onSecondary
        "secondaryContainer" -> secondaryContainer
        "onSecondaryContainer" -> onSecondaryContainer
        "tertiary" -> tertiary
        "onTertiary" -> onTertiary
        "tertiaryContainer" -> tertiaryContainer
        "onTertiaryContainer" -> onTertiaryContainer
        "error" -> error
        "onError" -> onError
        "errorContainer" -> errorContainer
        "onErrorContainer" -> onErrorContainer
        "background" -> background
        "onBackground" -> onBackground
        "surface" -> surface
        "onSurface" -> onSurface
        "surfaceVariant" -> surfaceVariant
        "onSurfaceVariant" -> onSurfaceVariant
        "outline" -> outline
        "outlineVariant" -> outlineVariant
        "inverseSurface" -> inverseSurface
        "inverseOnSurface" -> inverseOnSurface
        "inversePrimary" -> inversePrimary
        "surfaceDim" -> surfaceDim
        "surfaceBright" -> surfaceBright
        "surfaceContainerLowest" -> surfaceContainerLowest
        "surfaceContainerLow" -> surfaceContainerLow
        "surfaceContainer" -> surfaceContainer
        "surfaceContainerHigh" -> surfaceContainerHigh
        "surfaceContainerHighest" -> surfaceContainerHighest
        "scrim" -> scrim
        else -> error("Unknown role $name")
    }.hex()

    @Test
    fun light_and_dark_schemes_have_the_values_of_the_table_of_7_6_1() {
        mainRoles.forEach { (role, light, dark) ->
            assertEquals(light, Theme.lightScheme.role(role), "light $role")
            assertEquals(dark, Theme.darkScheme.role(role), "dark $role")
        }
    }

    @Test
    fun all_35_roles_have_the_snapshot_values() {
        val all = mainRoles + otherRoles
        assertEquals(35, all.map { it.first }.toSet().size)
        all.forEach { (role, light, dark) ->
            assertEquals(light, Theme.lightScheme.role(role), "light $role")
            assertEquals(dark, Theme.darkScheme.role(role), "dark $role")
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theme_follows_the_system_and_the_setting_forces_dark() = runComposeUiTest {
        val seen = HashMap<String, ColorScheme>()
        setContent {
            AppTheme(mode = ThemeMode.System, systemInDarkTheme = false) { seen["system-light"] = MaterialTheme.colorScheme }
            AppTheme(mode = ThemeMode.System, systemInDarkTheme = true) { seen["system-dark"] = MaterialTheme.colorScheme }
            AppTheme(mode = ThemeMode.Dark, systemInDarkTheme = false) { seen["forced-dark"] = MaterialTheme.colorScheme }
        }
        waitForIdle()
        assertEquals("#FF6D00", assertNotNull(seen["system-light"]).primary.hex())
        assertEquals("#42A5F5", assertNotNull(seen["system-dark"]).primary.hex())
        assertEquals("#42A5F5", assertNotNull(seen["forced-dark"]).primary.hex())
        // The theme mode has the values System and Dark, default System.
        assertEquals(ThemeMode.System, AppSettings().themeMode)
        assertEquals(listOf("System", "Dark"), ThemeMode.entries.map { it.name })
    }

    /** The contrast table: role, ratio on `surface` in light mode, in dark mode (× 10). */
    private val contrastTable: List<Triple<String, Int, Int>> = listOf(
        Triple("onSurface", 165, 135),
        Triple("onSurfaceVariant", 94, 66),
        Triple("secondary", 65, 83),
        Triple("tertiary", 64, 65),
        Triple("error", 65, 91),
        Triple("outline", 45, 19),
        Triple("primary", 28, 58),
    )

    private fun tenths(ratio: Double): Int = (ratio * 10.0).roundToInt()

    @Test
    fun contrast_of_each_role_on_surface_agrees_with_the_table() {
        contrastTable.forEach { (role, light, dark) ->
            val lightRatio = Wcag.ratio(Theme.lightScheme.role(role), Theme.lightScheme.role("surface"))
            val darkRatio = Wcag.ratio(Theme.darkScheme.role(role), Theme.darkScheme.role("surface"))
            assertEquals(light, tenths(lightRatio), "light $role: $lightRatio")
            assertEquals(dark, tenths(darkRatio), "dark $role: $darkRatio")
        }
    }

    @Test
    fun surfaceContainerLowest_gives_equal_or_higher_values() {
        listOf(Theme.lightScheme, Theme.darkScheme).forEach { scheme ->
            contrastTable.forEach { (role, _, _) ->
                val onSurface = Wcag.ratio(scheme.role(role), scheme.role("surface"))
                val onLowest = Wcag.ratio(scheme.role(role), scheme.role("surfaceContainerLowest"))
                assertTrue(onLowest >= onSurface - 1e-9, "$role: $onLowest < $onSurface")
            }
        }
    }

    @Test
    fun readout_digits_have_more_than_7_to_1_in_the_two_modes() {
        assertTrue(Wcag.ratio(Theme.lightScheme.role("onSurface"), Theme.lightScheme.role("surface")) > 7.0)
        assertTrue(Wcag.ratio(Theme.darkScheme.role("onSurface"), Theme.darkScheme.role("surface")) > 7.0)
    }

    @Test
    fun primary_in_light_mode_has_2_8_to_1() {
        // onPrimary on primary and primary on surface have only 2.8 : 1 in light
        // mode, and 5.0 : 1 and 5.8 : 1 in dark mode.
        assertEquals(28, tenths(Wcag.ratio(Theme.lightScheme.role("onPrimary"), Theme.lightScheme.role("primary"))))
        assertEquals(28, tenths(Wcag.ratio(Theme.lightScheme.role("primary"), Theme.lightScheme.role("surface"))))
        assertEquals(50, tenths(Wcag.ratio(Theme.darkScheme.role("onPrimary"), Theme.darkScheme.role("primary"))))
        assertEquals(58, tenths(Wcag.ratio(Theme.darkScheme.role("primary"), Theme.darkScheme.role("surface"))))
    }

    @Test
    fun large_buttons_pair_has_7_3_light_and_7_2_dark() {
        assertEquals(73, tenths(Wcag.ratio(Theme.lightScheme.role("onPrimaryContainer"), Theme.lightScheme.role("primaryContainer"))))
        assertEquals(72, tenths(Wcag.ratio(Theme.darkScheme.role("onPrimaryContainer"), Theme.darkScheme.role("primaryContainer"))))
    }

    @Test
    fun each_link_status_pair_has_more_than_7_to_1() {
        // The contrast rule: "Each pair has more than 7 : 1 in the two modes."
        val pairs = listOf(
            "onPrimaryContainer" to "primaryContainer",
            "onTertiaryContainer" to "tertiaryContainer",
            "onErrorContainer" to "errorContainer",
        )
        listOf(Theme.lightScheme, Theme.darkScheme).forEach { scheme ->
            pairs.forEach { (text, container) ->
                val ratio = Wcag.ratio(scheme.role(text), scheme.role(container))
                assertTrue(ratio > 7.0, "$text on $container: $ratio")
            }
        }
    }

    @Test
    fun onBackground_on_background_has_15_5_light_and_16_1_dark() {
        assertEquals(155, tenths(Wcag.ratio(Theme.lightScheme.role("onBackground"), Theme.lightScheme.role("background"))))
        assertEquals(161, tenths(Wcag.ratio(Theme.darkScheme.role("onBackground"), Theme.darkScheme.role("background"))))
    }
}
