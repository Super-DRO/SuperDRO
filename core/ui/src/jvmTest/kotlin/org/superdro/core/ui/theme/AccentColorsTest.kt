package org.superdro.core.ui.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * In light mode no text is on `primary`, and `primary` is not a text color or the color of a thin
 * line. A large button uses `primaryContainer` with `onPrimaryContainer`.
 */
@OptIn(ExperimentalTestApi::class)
class AccentColorsTest {

    @Test
    fun eachFilledButtonOfCoreUiHasExplicitColors() {
        val offenders = scan(listOf("Button")) { arguments -> !COLORS.containsMatchIn(arguments) }
        assertEquals(emptyList(), offenders, "A filled Button with the default colors has white text on primary (2.8 : 1).")
    }

    @Test
    fun eachTextButtonOutlinedButtonSwitchCheckboxRadioButtonAndOutlinedTextFieldHasAccentColors() {
        val names = listOf("TextButton", "OutlinedButton", "Switch", "Checkbox", "RadioButton", "OutlinedTextField")
        val offenders = scan(names) { arguments -> !COLORS.containsMatchIn(arguments) || EMPTY_DEFAULTS.containsMatchIn(arguments) }
        assertEquals(emptyList(), offenders, "The default colors of these components use primary for a text or a thin line.")
    }

    @Test
    fun eachChipWithAnIconThatAlwaysShowsHasAnAccentIconColor() {
        // The default icon color of FilterChip and AssistChip is primary: an outline icon is a thin line.
        val names = listOf("FilterChip", "AssistChip", "InputChip")
        val offenders = scan(names) { arguments ->
            val icon = Regex("""\bleadingIcon\s*=\s*\{\s*Icon\(""").containsMatchIn(arguments)
            icon && !COLORS.containsMatchIn(arguments) && !Regex("""\btint\s*=""").containsMatchIn(arguments)
        }
        assertEquals(emptyList(), offenders)
    }

    @Test
    fun eachProgressIndicatorHasAnExplicitColor() {
        val names = listOf("CircularProgressIndicator", "LinearProgressIndicator")
        val offenders = scan(names) { arguments -> !Regex("""\bcolor\s*=""").containsMatchIn(arguments) }
        assertEquals(emptyList(), offenders, "The default color of a progress indicator is primary, a thin line.")
    }

    @Test
    fun aFilledButtonHasTheContentColorOnPrimaryContainerInTheLightScheme() = runComposeUiTest {
        var accent = Color.Unspecified
        var default = Color.Unspecified
        setContent {
            AppTheme(systemInDarkTheme = false) {
                Column {
                    Button(onClick = {}, colors = AccentButtons.filled(), modifier = Modifier.testTag("filled")) {
                        accent = LocalContentColor.current
                        Text("Save")
                    }
                    DefaultButton { color -> default = color }
                }
            }
        }
        waitForIdle()
        val light = Theme.lightScheme
        assertEquals(light.onPrimaryContainer, accent)
        assertEquals(Color(0xFF72351F), accent)
        assertTrue(contrast(accent, light.primaryContainer) >= 7.0, "The contrast is 7.3 : 1.")
        // The default colors that the theme does not permit: white text on primary.
        assertEquals(light.onPrimary, default)
        assertTrue(contrast(default, light.primary) < 3.0, "The default colors give 2.8 : 1.")
        // The button shows the container color primaryContainer, not primary.
        val pixels = onNodeWithTag("filled").captureToImage().toPixelMap()
        val center = pixels[pixels.width / 2, 4]
        assertEquals(light.primaryContainer.toArgbHex(), center.toArgbHex())
    }

    @Test
    fun theAccentOfALineIsSecondaryInLightModeAndPrimaryInDarkMode() = runComposeUiTest {
        val colors = HashMap<Boolean, Pair<Color, Color>>()
        setContent {
            AppTheme(systemInDarkTheme = false) { colors[false] = accentColor() to onAccentColor() }
            AppTheme(systemInDarkTheme = true) { colors[true] = accentColor() to onAccentColor() }
        }
        waitForIdle()
        assertEquals(Theme.lightScheme.secondary to Theme.lightScheme.onSecondary, colors[false])
        assertEquals(Theme.darkScheme.primary to Theme.darkScheme.onPrimary, colors[true])
        assertTrue(contrast(Theme.lightScheme.secondary, Theme.lightScheme.surface) >= 4.5)
        assertTrue(contrast(Theme.lightScheme.onSecondary, Theme.lightScheme.secondary) >= 4.5)
    }

    @Composable
    private fun DefaultButton(onColor: (Color) -> Unit) {
        Button(onClick = {}, colors = ButtonDefaults.buttonColors()) {
            onColor(LocalContentColor.current)
            Text("Default")
        }
    }

    /**
     * Gives "file:line name" for each call of a component in [names] in the common sources of
     * `core:ui` whose argument text matches [bad]. A file that declares its own function with
     * the name of the component (a local wrapper) is not scanned for that name.
     */
    private fun scan(names: List<String>, bad: (String) -> Boolean): List<String> {
        val root = File("src/commonMain/kotlin")
        assertTrue(root.isDirectory, "The test runs in the directory of core:ui.")
        val result = ArrayList<String>()
        root.walkTopDown().filter { file -> file.isFile && file.extension == "kt" }.sortedBy { it.path }.forEach { file ->
            val text = file.readText()
            names.forEach { name ->
                if (Regex("""\bfun\s+$name\s*\(""").containsMatchIn(text)) return@forEach
                Regex("""(?<![A-Za-z0-9_.])$name\(""").findAll(text).forEach { match ->
                    val arguments = arguments(text, match.range.last + 1)
                    if (bad(arguments)) {
                        val line = text.substring(0, match.range.first).count { it == '\n' } + 1
                        result += "${file.relativeTo(root).path}:$line $name"
                    }
                }
            }
        }
        return result
    }

    /** Gives the text between the open parenthesis before [start] and its closing parenthesis. */
    private fun arguments(text: String, start: Int): String {
        var depth = 1
        var index = start
        var quote = false
        while (index < text.length && depth > 0) {
            val char = text[index]
            when {
                quote && char == '\\' -> index++
                char == '"' -> quote = !quote
                !quote && char == '(' -> depth++
                !quote && char == ')' -> depth--
            }
            index++
        }
        return text.substring(start, (index - 1).coerceAtLeast(start))
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun Color.toArgbHex(): String {
        fun channel(value: Float) = (value * 255f + 0.5f).toInt().coerceIn(0, 255)
        return "#%02X%02X%02X".format(channel(red), channel(green), channel(blue))
    }

    private companion object {
        val COLORS = Regex("""\bcolors\s*=""")
        val EMPTY_DEFAULTS = Regex("""\bcolors\s*=\s*\w+Defaults\.\w*[cC]olors\(\s*\)""")
    }
}
