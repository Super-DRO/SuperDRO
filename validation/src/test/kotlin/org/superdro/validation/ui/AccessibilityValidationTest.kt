package org.superdro.validation.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.superdro.core.model.ReadoutFormat
import org.superdro.core.ui.readout.NoReadoutEvents
import org.superdro.core.ui.readout.ReadoutRow
import org.superdro.core.ui.readout.ReadoutRowUiState
import org.superdro.core.ui.readout.RowKind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.superdro.core.model.AxisButtonMode
import org.superdro.core.model.AxisId
import org.superdro.core.readout.DisplayUnit
import org.superdro.core.ui.adaptive.LocalWindowLayout
import org.superdro.core.ui.adaptive.WidthClass
import org.superdro.core.ui.adaptive.WindowLayout
import org.superdro.core.ui.readout.ADD_POINT_TAG
import org.superdro.core.ui.readout.AxisValueUiState
import org.superdro.core.ui.readout.FUNCTION_STRIP_TAG
import org.superdro.core.ui.readout.FunctionButton
import org.superdro.core.ui.readout.MODE_TAG
import org.superdro.core.ui.readout.ReadoutTexts
import org.superdro.core.ui.readout.UNITS_TAG
import org.superdro.core.ui.readout.ValueState
import org.superdro.core.ui.readout.ZERO_SET_TAG
import org.superdro.core.ui.readout.actionTag
import org.superdro.core.ui.readout.functionTag
import org.superdro.core.ui.readout.keypad.KEYPAD_RESULT_TAG
import org.superdro.core.ui.readout.keypad.KEYPAD_SET_TAG
import org.superdro.core.ui.readout.keypad.KeypadKey
import org.superdro.core.ui.readout.keypad.KeypadSheet
import org.superdro.core.ui.readout.keypad.KeypadUiState
import org.superdro.core.ui.readout.keypad.keyTag
import org.superdro.core.ui.readout.valueTag
import org.superdro.core.ui.theme.AppTheme
import org.superdro.core.ui.theme.TouchTargets

/**
 * The accessibility rules (the touch targets, the status texts, no animation of values, the
 * semantic labels and the system font scale) and the keypad rules. The windows have 1 px for each dp.
 */
@OptIn(ExperimentalTestApi::class)
class AccessibilityValidationTest {

    private fun SkikoComposeUiTest.rectOf(tag: String): Rect = onNodeWithTag(tag).rect()

    private val axisButtons: List<Pair<AxisId, AxisButtonMode>> = listOf(AxisId.X, AxisId.Y, AxisId.Z, AxisId.Angular).flatMap { axis ->
        listOf(AxisButtonMode.SetZero, AxisButtonMode.SetCenter, AxisButtonMode.ToggleIncremental).map { mode -> axis to mode }
    }

    private fun SkikoComposeUiTest.assertTargets(minimum: Float) {
        val tags = axisButtons.map { (axis, mode) -> actionTag(axis, mode) } +
            listOf(ZERO_SET_TAG, MODE_TAG, UNITS_TAG, ADD_POINT_TAG) +
            MainScreenSamples.mill().functions.map { functionTag(it.button) }
        tags.forEach { tag ->
            val node = onNodeWithTag(tag).fetchSemanticsNode()
            assertTrue(node.size.width >= minimum && node.size.height >= minimum, "$tag: ${node.size} < $minimum dp")
        }
    }

    @Test
    fun axis_buttons_and_function_buttons_have_64_dp_landscape() = runWindowTest(1280, 800) {
        setContent { SampleMainScreen(MainScreenSamples.mill(), dark = false) }
        assertTargets(64f)
    }

    @Test
    fun axis_buttons_and_function_buttons_have_64_dp_portrait() = runWindowTest(800, 1280) {
        setContent { SampleMainScreen(MainScreenSamples.mill(), dark = false) }
        assertTargets(64f)
    }

    @Test
    fun gaps_between_buttons_are_12_dp() = runWindowTest(1280, 800) {
        setContent { SampleMainScreen(MainScreenSamples.mill(), dark = false) }
        // The three action buttons of the X row.
        val x = listOf(AxisButtonMode.SetZero, AxisButtonMode.SetCenter, AxisButtonMode.ToggleIncremental).map { rectOf(actionTag(AxisId.X, it)) }.sortedBy { it.left }
        x.zipWithNext().forEach { (a, b) -> assertEquals(12f, b.left - a.right, 0.5f, "gap of the axis buttons") }
        // The buttons of the rail.
        val rail = listOf(ZERO_SET_TAG, MODE_TAG, UNITS_TAG).map { tag -> rectOf(tag) }
        rail.zipWithNext().forEach { (a, b) -> assertEquals(12f, b.top - a.bottom, 0.5f, "gap of the rail") }
    }

    @Test
    fun large_buttons_add_25_percent() {
        assertEquals(64f, TouchTargets.Normal.button.value)
        assertEquals(72f, TouchTargets.Normal.keypadKey.value)
        assertEquals(12f, TouchTargets.Normal.gap.value)
        assertEquals(80f, TouchTargets.Large.button.value, 1e-3f)
        assertEquals(90f, TouchTargets.Large.keypadKey.value, 1e-3f)
        assertEquals(15f, TouchTargets.Large.gap.value, 1e-3f)
        runWindowTest(1600, 1000) {
            setContent { SampleMainScreen(MainScreenSamples.mill(), dark = false, largeButtons = true) }
            assertTargets(80f)
        }
    }

    // ---------------------------------------------------------------------------------------
    // The keypad

    private val keypadState = KeypadUiState(AxisId.X, text = "++0.5", result = "1.7500")
    private val keypadValue = AxisValueUiState(AxisId.X, digits = "1.2500", unit = DisplayUnit.Inch, state = ValueState.Normal, metric = false)

    private fun SkikoComposeUiTest.keypad(landscape: Boolean, large: Boolean = false, onKey: (KeypadKey) -> Unit = {}) = setContent {
        AppTheme(systemInDarkTheme = false, largeButtons = large) {
            CompositionLocalProvider(LocalWindowLayout provides WindowLayout(if (landscape) WidthClass.Expanded else WidthClass.Medium, landscape)) {
                KeypadSheet(keypadState, "X", keypadValue, onTextChange = {}, onKey = onKey, onSet = {}, onDismiss = {})
            }
        }
    }

    @Test
    fun keypad_keys_have_72_dp_and_gaps_of_12_dp() = runWindowTest(1280, 800) {
        keypad(landscape = true)
        waitForIdle()
        val keys = KeypadKey.entries.filter { key -> onAllNodesWithTag(keyTag(key)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(keys.size >= 20, "keys $keys")
        keys.forEach { key ->
            val node = onNodeWithTag(keyTag(key)).fetchSemanticsNode()
            assertTrue(node.size.width >= 72 && node.size.height >= 72, "$key: ${node.size}")
        }
        val seven = rectOf(keyTag(KeypadKey.Digit7))
        val eight = rectOf(keyTag(KeypadKey.Digit8))
        val four = rectOf(keyTag(KeypadKey.Digit4))
        assertEquals(12f, eight.left - seven.right, 0.5f)
        assertEquals(12f, four.top - seven.bottom, 0.5f)
        val set = onNodeWithTag(KEYPAD_SET_TAG).fetchSemanticsNode()
        assertTrue(set.size.height >= 72, "Set ${set.size}")
    }

    @Test
    fun keypad_keys_with_large_buttons_have_90_dp() = runWindowTest(1600, 1200) {
        keypad(landscape = true, large = true)
        waitForIdle()
        listOf(KeypadKey.Digit1, KeypadKey.Plus, KeypadKey.RelativeAdd).forEach { key ->
            val node = onNodeWithTag(keyTag(key)).fetchSemanticsNode()
            assertTrue(node.size.width >= 90 && node.size.height >= 90, "$key: ${node.size}")
        }
    }

    @Test
    fun keypad_has_the_keys_the_unit_and_shows_the_result_before_Set() = runWindowTest(800, 1280) {
        val keys = ArrayList<KeypadKey>()
        keypad(landscape = false, onKey = { keys += it })
        waitForIdle()
        mainClock.advanceTimeBy(1_000L)
        val required = KeypadKey.digits + listOf(
            KeypadKey.Dot, KeypadKey.Plus, KeypadKey.Minus, KeypadKey.Times, KeypadKey.Divide,
            KeypadKey.OpenParenthesis, KeypadKey.CloseParenthesis, KeypadKey.RelativeAdd, KeypadKey.RelativeSubtract, KeypadKey.Clear,
        )
        required.forEach { key -> onNodeWithTag(keyTag(key)).assertExists("key $key") }
        // The operator keys show + − × ÷.
        listOf("+", "−", "×", "÷", "(", ")", "++", "--").forEach { text ->
            assertTrue(onAllNodesWithTagOrText(text).isNotEmpty(), "key text $text")
        }
        // The axis unit, the expression, and the result before "Set".
        onNodeWithText("in", useUnmergedTree = true).assertExists()
        onNodeWithText("++0.5", useUnmergedTree = true).assertExists()
        val result = onNodeWithTag(KEYPAD_RESULT_TAG, useUnmergedTree = true).fetchSemanticsNode()
        val resultText = result.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString()
        assertTrue("1.7500" in resultText, resultText)
        onNodeWithTag(KEYPAD_SET_TAG).assertExists()
        onNodeWithTag(keyTag(KeypadKey.RelativeSubtract)).performClickSafe()
        assertEquals(listOf(KeypadKey.RelativeSubtract), keys)
    }

    private fun SkikoComposeUiTest.onAllNodesWithTagOrText(text: String): List<SemanticsNode> =
        onAllNodes(androidx.compose.ui.test.hasText(text), useUnmergedTree = true).fetchSemanticsNodes() +
            onAllNodes(androidx.compose.ui.test.hasContentDescription(text), useUnmergedTree = true).fetchSemanticsNodes()

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.performClickSafe() {
        performClick()
    }

    // ---------------------------------------------------------------------------------------
    // The semantic labels, the status texts, and no animation of values

    @Test
    fun semantic_label_of_each_axis_value() = runWindowTest(1280, 800) {
        setContent { SampleMainScreen(MainScreenSamples.mill(), dark = false) }
        fun label(axis: AxisId) = onNodeWithTag(valueTag(axis)).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString()
        // The example of a semantic label.
        assertEquals("X axis, 125.430 millimeters", label(AxisId.X))
        assertEquals("Y axis, -12.005 millimeters", label(AxisId.Y))
        assertEquals("Z axis, 3.100 millimeters", label(AxisId.Z))
        assertTrue(label(AxisId.Angular).startsWith("α axis, 45.00"), label(AxisId.Angular))
    }

    @Test
    fun each_icon_button_and_each_control_has_a_text_or_a_content_description() = runWindowTest(1280, 800) {
        setContent { SampleMainScreen(MainScreenSamples.mill(), dark = false) }
        val clickable = onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertTrue(clickable.size > 20)
        clickable.forEach { node ->
            val text = node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString().trim()
            val description = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString().trim()
            assertTrue(text.isNotEmpty() || description.isNotEmpty(), "a control with no text: ${node.config}")
        }
        // The icon buttons of the frame and the "+" button.
        listOf("Add machine", "Settings", "More options", "Add sub-datum").forEach { description ->
            assertTrue(
                onAllNodes(androidx.compose.ui.test.hasContentDescription(description) and hasClickAction()).fetchSemanticsNodes().isNotEmpty(),
                description,
            )
        }
    }

    @Test
    fun each_value_state_has_a_text_and_an_icon() = runWindowTest(1280, 800) {
        val states = ValueState.entries - ValueState.Normal
        states.forEach { state ->
            assertNotNull(ReadoutTexts.stateText(state), "text of $state")
            assertNotNull(ReadoutTexts.stateIcon(state), "icon of $state")
        }
        setContent { SampleMainScreen(MainScreenSamples.mill(), dark = false) }
        states.forEach { state ->
            MainScreenSamples.set(AxisId.Z, AxisValueUiState(AxisId.Z, digits = "3.100", unit = DisplayUnit.Millimeter, state = state, positionUm = 3_100.0))
            waitForIdle()
            val label = onNodeWithTag(valueTag(AxisId.Z)).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString()
            assertTrue(label.split(", ").size >= 2 && label != "Z axis, 3.100 millimeters", "$state: $label")
        }
        MainScreenSamples.set(AxisId.Z, AxisValueUiState(AxisId.Z, digits = "3.100", unit = DisplayUnit.Millimeter, state = ValueState.Normal, positionUm = 3_100.0))
    }

    @Test
    fun the_digits_change_at_once() = runWindowTest(1280, 800) {
        setContent { SampleMainScreen(MainScreenSamples.mill(), dark = false) }
        waitForIdle()
        mainClock.autoAdvance = false
        MainScreenSamples.set(AxisId.X, AxisValueUiState(AxisId.X, digits = "200.000", unit = DisplayUnit.Millimeter, state = ValueState.Normal, positionUm = 200_000.0))
        mainClock.advanceTimeByFrame()
        val label = onNodeWithTag(valueTag(AxisId.X)).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString()
        assertEquals("X axis, 200.000 millimeters", label, "one frame after the change")
        MainScreenSamples.set(AxisId.X, AxisValueUiState(AxisId.X, digits = "125.430", unit = DisplayUnit.Millimeter, state = ValueState.Normal, positionUm = 125_430.0))
        mainClock.autoAdvance = true
    }

    /** Gives the pixels of [rect] of [image]. */
    private fun crop(image: androidx.compose.ui.graphics.ImageBitmap, rect: Rect): IntArray {
        val all = pixels(image)
        val left = rect.left.toInt()
        val top = rect.top.toInt()
        val width = rect.width.toInt()
        val height = rect.height.toInt()
        return IntArray(width * height) { index -> all[(top + index / width) * image.width + left + index % width] }
    }

    @Test
    fun system_font_scale_changes_the_labels_but_not_the_readout_digits() {
        val result = HashMap<Float, Pair<Float, IntArray>>()
        val row = ReadoutRowUiState(AxisId.X, "X", RowKind.Linear, slider = false)
        listOf(1f, 1.5f).forEach { scale ->
            runWindowTest(1000, 200) {
                setContent {
                    AppTheme(systemInDarkTheme = false) {
                        val density = LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                            Box(Modifier.size(1000.dp, 160.dp)) {
                                ReadoutRow(row, MainScreenSamples.value(AxisId.X), collapsed = false, ReadoutFormat.Format3, NoReadoutEvents, Modifier.fillMaxSize())
                            }
                        }
                    }
                }
                waitForIdle()
                val label = onNodeWithText("X", useUnmergedTree = true).fetchSemanticsNode().size.height.toFloat()
                // The digits: the value box without the state line at its top and the unit column (64 dp) at its right.
                val box = onNodeWithTag(valueTag(AxisId.X)).rect()
                val digits = Rect(box.left, box.top + 30f, box.right - 72f, box.bottom)
                result[scale] = label to crop(captureToImage(), digits)
            }
        }
        val (small, digitsSmall) = assertNotNull(result[1f])
        val (large, digitsLarge) = assertNotNull(result[1.5f])
        assertTrue(large > small * 1.3f, "the axis label follows the font scale: $small -> $large")
        assertEquals(digitsSmall.size, digitsLarge.size)
        val same = digitsSmall.indices.count { index -> digitsSmall[index] == digitsLarge[index] }
        assertTrue(same >= digitsSmall.size * 0.99, "the readout digits do not follow the font scale: $same of ${digitsSmall.size} pixels are equal")
    }

    @Test
    fun function_buttons_have_a_content_description() = runWindowTest(1280, 800) {
        setContent { SampleMainScreen(MainScreenSamples.mill(), dark = false) }
        (listOf(ZERO_SET_TAG, MODE_TAG, UNITS_TAG) + FunctionButton.entries.map(::functionTag)).forEach { tag ->
            onAllNodesWithTag(tag).fetchSemanticsNodes().forEach { node ->
                val description = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString()
                assertTrue(description.isNotBlank(), "$tag has no content description")
            }
        }
        // The strip can scroll with the accessibility actions when the buttons do not fit.
        assertNotNull(onNode(hasTestTag(FUNCTION_STRIP_TAG)).fetchSemanticsNode().config.getOrNull(SemanticsActions.ScrollBy))
        onNode(isRoot()).assertExists()
    }
}
