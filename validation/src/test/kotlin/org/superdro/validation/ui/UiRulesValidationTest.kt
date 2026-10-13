package org.superdro.validation.ui

import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.superdro.core.ui.adaptive.WidthClass
import org.superdro.core.ui.adaptive.WindowLayout
import org.superdro.validation.Traceability

/**
 * The window
 * width classes, and the rules that the source of `core:ui` shows (the components, the
 * insets, the back handling, the screen-on, and the haptic feedback).
 */
class UiRulesValidationTest {

    @Test
    fun window_width_classes() {
        // Compact: less than 600 dp. Medium: 600 dp to 839 dp. Expanded: 840 dp to 1199 dp. Large and
        // extra-large (1200 dp and more): as expanded.
        val expected = listOf(
            599 to WidthClass.Compact,
            600 to WidthClass.Medium,
            839 to WidthClass.Medium,
            840 to WidthClass.Expanded,
            1199 to WidthClass.Expanded,
            1200 to WidthClass.Expanded,
            1600 to WidthClass.Expanded,
        )
        expected.forEach { (width, widthClass) ->
            assertEquals(widthClass, WindowLayout.of(width.dp, 400.dp).widthClass, "$width dp")
        }
        // The tablet sizes.
        assertTrue(WindowLayout.of(1280.dp, 800.dp).expanded, "1280 x 800 dp: the expanded layout")
        assertEquals(WidthClass.Medium, WindowLayout.of(800.dp, 1280.dp).widthClass, "800 x 1280 dp: the medium layout")
        assertTrue(!WindowLayout.of(800.dp, 1280.dp).landscape)
    }

    private val sources: List<File> = Traceability.rootDir.resolve("core/ui/src/commonMain/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()

    private fun used(text: String): Boolean = sources.any { file -> text in file.readText() }

    @Test
    fun the_ui_uses_the_material_3_components() {
        listOf(
            "PrimaryScrollableTabRow", "TopAppBar", "FilledTonalButton", "SingleChoiceSegmentedButtonRow",
            "ModalBottomSheet", "AlertDialog", "ListItem", "Snackbar",
        ).forEach { component -> assertTrue(used(component), "core:ui uses $component") }
    }

    @Test
    fun rules_that_the_source_shows() {
        assertTrue(used("WindowInsets.safeDrawing"), "edge-to-edge with WindowInsets.safeDrawing")
        assertTrue(!used("onBackPressed"), "no onBackPressed()")
        assertTrue(used("LocalHapticFeedback"), "haptic feedback on key presses")
        assertTrue(used("keepScreenOn()"), "Modifier.keepScreenOn()")
        // No dynamic color.
        assertTrue(!used("dynamicLightColorScheme") && !used("dynamicDarkColorScheme"), "no dynamic color")
    }
}
