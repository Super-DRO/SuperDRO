package org.superdro.core.ui.adapter.calibration

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import org.superdro.core.model.ThemeMode
import org.superdro.core.ui.adaptive.LocalWindowLayout
import org.superdro.core.ui.adaptive.WindowLayout
import org.superdro.core.ui.theme.AppTheme

/** The Compose UI tests of the calibration wizard. */
@OptIn(ExperimentalTestApi::class)
class InputCalibrationUiTest {

    /** Events that record their calls and change the state of the test. */
    private class Recorder(private val onText: (String) -> Unit) : CalibrationEvents {
        val calls = mutableListOf<String>()
        override fun onBlockTextChange(text: String) {
            calls += "text"
            onText(text)
        }
        override fun onBlockUnitChange(metric: Boolean) {
            calls += "unit $metric"
        }
        override fun onContinue() {
            calls += "continue"
        }
        override fun onCancel() {
            calls += "cancel"
        }
        override fun onApply() {
            calls += "apply"
        }
        override fun onDiscard() {
            calls += "discard"
        }
        override fun onProblemRetry() {
            calls += "retry"
        }
        override fun onProblemContinue() {
            calls += "problem continue"
        }
        override fun onProblemDismiss() {
            calls += "dismiss"
        }
    }

    @Composable
    private fun Themed(content: @Composable () -> Unit) {
        AppTheme(mode = ThemeMode.Dark, systemInDarkTheme = true) {
            CompositionLocalProvider(LocalWindowLayout provides WindowLayout.of(1280.dp, 800.dp)) { content() }
        }
    }

    @Test
    fun theLinearFlowFromTheBlockToApply() = runComposeUiTest {
        var state by mutableStateOf(CalibrationSamples.block.copy(blockText = ""))
        val events = Recorder { text -> state = state.copy(blockText = text) }
        setContent { Themed { InputCalibrationScreen(state, events) } }

        onNodeWithText("Calibrate Input X").assertIsDisplayed()
        onNodeWithTag(CALIBRATION_BLOCK_TAG).performTextInput("2")
        assertEquals("2", state.blockText)
        onNodeWithText("mm").performClick()
        onNodeWithTag(CALIBRATION_CONTINUE_TAG).assertHeightIsAtLeast(64.dp).performClick()
        assertEquals(listOf("text", "unit true", "continue"), events.calls)

        state = CalibrationSamples.firstEdge
        onNodeWithTag(CALIBRATION_STEP_TAG).assertTextEquals("Move the axis to the left edge of the block. Then push Continue.")
        onNodeWithTag(CALIBRATION_CONTINUE_TAG).assertIsEnabled()

        state = CalibrationSamples.sampling
        onNodeWithTag(CALIBRATION_STEP_TAG).assertTextEquals("Do not move the axis. The app reads the input for up to 15 seconds.")
        onNodeWithContentDescription("Reading progress: 6 of 15 seconds").assertExists()
        onNodeWithText("6 s of 15 s, 300 readings").assertIsDisplayed()

        state = CalibrationSamples.jitter
        onNodeWithText("Excessive Jitter Detected").assertIsDisplayed()
        onNodeWithTag(CALIBRATION_RETRY_TAG).performClick()
        assertEquals("retry", events.calls.last())

        state = CalibrationSamples.result
        onNodeWithTag(CALIBRATION_RESULT_TAG).assertTextEquals("Resolution: 5080.000 counts per inch (5.00 µm for each count)")
        onNodeWithText("Direction: normal. The count goes up from the first position to the second position.").assertIsDisplayed()
        onNodeWithTag(CALIBRATION_APPLY_TAG).assertHeightIsAtLeast(64.dp).performClick()
        onNodeWithTag(CALIBRATION_DISCARD_TAG).performClick()
        assertEquals(listOf("apply", "discard"), events.calls.takeLast(2))
    }

    @Test
    fun continueIsDisabledWhileTheAdapterIsNotConnected() = runComposeUiTest {
        setContent { Themed { InputCalibrationScreen(CalibrationSamples.waiting, Recorder {}) } }
        onNodeWithText("Waiting for the adapter").assertIsDisplayed()
        onNodeWithTag(CALIBRATION_CONTINUE_TAG).assertIsNotEnabled()
    }

    @Test
    fun blockTooSmallHasRetryAndContinue() = runComposeUiTest {
        val events = Recorder {}
        setContent { Themed { InputCalibrationScreen(CalibrationSamples.blockTooSmall, events) } }
        onNodeWithText("Block Too Small").assertIsDisplayed()
        onNodeWithText("A longer block gives a more accurate result. Use a block at least 50.000 mm / 2.0000 in long. Push Continue to use this block.")
            .assertIsDisplayed()
        onNodeWithTag(CALIBRATION_PROBLEM_CONTINUE_TAG).performClick()
        assertEquals(listOf("problem continue"), events.calls)
    }

    @Test
    fun theAngularResultShowsDegreesForEachCount() = runComposeUiTest {
        setContent { Themed { InputCalibrationScreen(CalibrationSamples.angularResult, Recorder {}) } }
        onNodeWithTag(CALIBRATION_RESULT_TAG).assertTextEquals("Resolution: 7200.000 counts per revolution (0.0500° for each count)")
        onNodeWithText(
            "Direction: reversed. The count goes down from the first position to the second position. Apply sets the reverse direction.",
        ).assertIsDisplayed()
    }
}
