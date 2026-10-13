package org.superdro.core.ui.functions.sheets

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import org.superdro.core.functions.compensation.ToolPosition
import org.superdro.core.model.Tool
import org.superdro.core.ui.adaptive.LocalWindowLayout
import org.superdro.core.ui.adaptive.WindowLayout
import org.superdro.core.ui.functions.common.LengthFieldState
import org.superdro.core.ui.functions.common.NumberFieldState
import org.superdro.core.ui.functions.common.PRIMARY_BUTTON_TAG
import org.superdro.core.ui.functions.common.fieldTextTag
import org.superdro.core.ui.functions.renderFunctionScreenshot
import org.superdro.core.ui.functions.tools.ToolTexts
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.theme.AppTheme

/** The Compose UI tests and the screenshots of the measure, compensation, Z reference, and projection sheets. */
@OptIn(ExperimentalTestApi::class)
class SheetScreensTest {

    private val tool = Tool(id = "t1", name = "10 mm End Mill", metric = true, diameterUm = 10_000.0, toothCount = 4, zOffsetUm = -12_500.0)
    private val choices = listOf(ToolChoice(tool.id, tool.name, ToolTexts.row(tool, profileMetric = true)))

    private class RadiusEvents : RadiusCompensationEvents {
        val calls = mutableListOf<String>()
        var onText: (String) -> Unit = {}
        override fun onTool(id: String?) { calls += "tool $id" }
        override fun onDiameter(text: String) {
            onText(text)
            calls += "diameter $text"
        }
        override fun onDiameterUnit(metric: Boolean) { calls += "unit $metric" }
        override fun onPosition(position: ToolPosition) { calls += "position $position" }
        override fun onApply() { calls += "apply" }
        override fun onClear() { calls += "clear" }
        override fun onToolLibrary() { calls += "library" }
        override fun onDismiss() { calls += "dismiss" }
    }

    @Test
    fun theRadiusCompensationFlow() = runComposeUiTest {
        val events = RadiusEvents()
        var state by mutableStateOf(RadiusCompensationUiState(loaded = true, tools = choices, diameter = LengthFieldState(metric = true)))
        events.onText = { text -> state = state.copy(diameter = state.diameter.withText(text)) }
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalWindowLayout provides WindowLayout.of(1280.dp, 800.dp)) { RadiusCompensationSheet(state, events) }
            }
        }
        onNodeWithTag(fieldTextTag(SheetFieldTags.DIAMETER)).performTextInput("10")
        onNodeWithTag(SheetTags.position(ToolPosition.BackLeft)).performClick()
        onNodeWithTag(SheetTags.CLEAR).assertIsNotEnabled()
        onNodeWithTag(SheetTags.TOOL_PICKER).performClick()
        onNodeWithTag(SheetTags.toolChoice("t1")).performClick()
        onNodeWithTag(SheetTags.TOOL_LIBRARY).performClick()
        onNodeWithTag(PRIMARY_BUTTON_TAG).performClick()
        assertEquals(listOf("diameter 10", "position BackLeft", "tool t1", "library", "apply"), events.calls)
    }

    @Test
    fun theZReferenceShowsTheBlockAndDisablesSet() = runComposeUiTest {
        val state = ZReferenceUiState(loaded = true, block = UiText.Plain("Z readout must be enabled"))
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalWindowLayout provides WindowLayout.of(1280.dp, 800.dp)) {
                    ZReferenceSheet(state, object : ZReferenceEvents {
                        override fun onSetterHeight(text: String) = Unit
                        override fun onSetterHeightUnit(metric: Boolean) = Unit
                        override fun onDesiredZ(text: String) = Unit
                        override fun onDesiredZUnit(metric: Boolean) = Unit
                        override fun onApply() = Unit
                        override fun onDismiss() = Unit
                    })
                }
            }
        }
        onNodeWithText("Z readout must be enabled").assertExists()
        onNodeWithTag(PRIMARY_BUTTON_TAG).assertIsNotEnabled()
    }

    @Test
    fun theSheetsRenderInLightAndDarkMode() {
        val measure = AngleMeasureUiState(
            loaded = true, metric = true, aText = "30.000", bText = "40.000", distance = "50.000", lineAngle = "53.13", direction = "53.13", complement = "36.87",
        )
        val dimension = DimensionMeasureUiState(loaded = true, value = "25.000", hasValue = true, metric = true)
        val radius = RadiusCompensationUiState(
            loaded = true, tools = choices, diameter = LengthFieldState.of(10_000.0, true), position = ToolPosition.BackLeft, active = true,
        )
        val height = HeightCompensationUiState(loaded = true, tools = choices, zOffset = LengthFieldState.of(-12_500.0, true), error = UiText.Plain("Z offset can't be 0"))
        val lathe = LatheToolOffsetUiState(
            loaded = true, tools = choices, toolId = "t1", xOffset = LengthFieldState.of(500.0, true), zOffset = LengthFieldState.of(-500.0, true), active = true,
        )
        val zReference = ZReferenceUiState(loaded = true, setterHeight = LengthFieldState.of(50_000.0, true), desiredZ = LengthFieldState(metric = true))
        val compound = CompoundUiState(
            loaded = true, angle = NumberFieldState.of(30.0), auxiliaryType = UiText.Plain("Compound slide"),
            reference = UiText.Plain("along the bed, the hand wheel at the tailstock end"), xWeight = "0.50000", zWeight = "0.86603",
        )
        val tilt = HeadTiltUiState(
            loaded = true, tilt = NumberFieldState.of(10.0), nod = NumberFieldState.of(5.0), xWeight = "-0.17365", yWeight = "-0.08716", zWeight = "0.98094",
            problem = null,
        )
        listOf(false, true).forEach { dark ->
            val mode = if (dark) "dark" else "light"
            renderFunctionScreenshot("functions-sheets-angle-measure-$mode", dark) { AngleMeasureSheet(measure, {}, {}) }
            renderFunctionScreenshot("functions-sheets-dimension-measure-$mode", dark) { DimensionMeasureSheet(dimension, NoDimensionEvents) }
            renderFunctionScreenshot("functions-sheets-radius-$mode", dark, heightDp = 1000) { RadiusCompensationSheet(radius, RadiusEvents()) }
            renderFunctionScreenshot("functions-sheets-radius-portrait-$mode", dark, widthDp = 800, heightDp = 1280) { RadiusCompensationSheet(radius, RadiusEvents()) }
            renderFunctionScreenshot("functions-sheets-height-$mode", dark) { HeightCompensationSheet(height, NoHeightEvents) }
            renderFunctionScreenshot("functions-sheets-lathe-offset-$mode", dark) { LatheToolOffsetSheet(lathe, NoLatheEvents) }
            renderFunctionScreenshot("functions-sheets-z-reference-$mode", dark) { ZReferenceSheet(zReference, NoZEvents) }
            renderFunctionScreenshot("functions-sheets-compound-$mode", dark, heightDp = 900) { CompoundSheet(compound, NoCompoundEvents) }
            renderFunctionScreenshot("functions-sheets-head-tilt-$mode", dark, heightDp = 900) { HeadTiltSheet(tilt, NoHeadTiltEvents) }
        }
    }

    private object NoDimensionEvents : DimensionMeasureEvents {
        override fun onZero() = Unit
        override fun onZeroLongPress() = Unit
        override fun onToggleMode() = Unit
        override fun onToggleUnits() = Unit
        override fun onCopyToX() = Unit
        override fun onDismiss() = Unit
    }

    private object NoHeightEvents : HeightCompensationEvents {
        override fun onTool(id: String?) = Unit
        override fun onZOffset(text: String) = Unit
        override fun onZOffsetUnit(metric: Boolean) = Unit
        override fun onApply() = Unit
        override fun onClear() = Unit
        override fun onToolLibrary() = Unit
        override fun onDismiss() = Unit
    }

    private object NoLatheEvents : LatheToolOffsetEvents {
        override fun onTool(id: String?) = Unit
        override fun onXOffset(text: String) = Unit
        override fun onZOffset(text: String) = Unit
        override fun onXUnit(metric: Boolean) = Unit
        override fun onZUnit(metric: Boolean) = Unit
        override fun onApply() = Unit
        override fun onSelectTool() = Unit
        override fun onClear() = Unit
        override fun onToolLibrary() = Unit
        override fun onDismiss() = Unit
    }

    private object NoZEvents : ZReferenceEvents {
        override fun onSetterHeight(text: String) = Unit
        override fun onSetterHeightUnit(metric: Boolean) = Unit
        override fun onDesiredZ(text: String) = Unit
        override fun onDesiredZUnit(metric: Boolean) = Unit
        override fun onApply() = Unit
        override fun onDismiss() = Unit
    }

    private object NoCompoundEvents : CompoundEvents {
        override fun onAngle(text: String) = Unit
        override fun onApplyX(on: Boolean) = Unit
        override fun onApplyZ(on: Boolean) = Unit
        override fun onApply() = Unit
        override fun onClear() = Unit
        override fun onDismiss() = Unit
    }

    private object NoHeadTiltEvents : HeadTiltEvents {
        override fun onTilt(text: String) = Unit
        override fun onNod(text: String) = Unit
        override fun onApplyX(on: Boolean) = Unit
        override fun onApplyY(on: Boolean) = Unit
        override fun onApply() = Unit
        override fun onClear() = Unit
        override fun onDismiss() = Unit
    }
}
