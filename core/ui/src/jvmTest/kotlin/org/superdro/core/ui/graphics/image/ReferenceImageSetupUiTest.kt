package org.superdro.core.ui.graphics.image

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.superdro.core.functions.graphics.ImageReference
import org.superdro.core.functions.graphics.Vec2
import org.superdro.core.model.ThemeMode
import org.superdro.core.resources.Res
import org.superdro.core.resources.refimage_fit_three
import org.superdro.core.ui.adaptive.LocalWindowLayout
import org.superdro.core.ui.adaptive.WindowLayout
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.theme.AppTheme

/** The events that a UI test saw. */
class RecordingSetupEvents : ReferenceImageSetupEvents {
    val calls = mutableListOf<String>()
    override fun onViewSize(widthPx: Double, heightPx: Double) = Unit
    override fun onPan(dxPx: Double, dyPx: Double) { calls += "pan" }
    override fun onZoom(factor: Double) { calls += "zoom" }
    override fun onResetView() { calls += "reset" }
    override fun onNudgeStep(step: Int) { calls += "step $step" }
    override fun onNudge(dx: Int, dy: Int) { calls += "nudge $dx $dy" }
    override fun onGray() { calls += "gray" }
    override fun onInvert() { calls += "invert" }
    override fun onRotateLeft() { calls += "rotate left" }
    override fun onRotateRight() { calls += "rotate right" }
    override fun onFlipHorizontal() { calls += "flip horizontal" }
    override fun onFlipVertical() { calls += "flip vertical" }
    override fun onHorizontalText(index: Int, text: String) { calls += "h $index $text" }
    override fun onVerticalText(index: Int, text: String) { calls += "v $index $text" }
    override fun onSyncHorizontal(index: Int) { calls += "sync h $index" }
    override fun onSyncVertical(index: Int) { calls += "sync v $index" }
    override fun onSubDatum(index: Int, subDatumId: String) { calls += "sub-datum $index $subDatumId" }
    override fun onSetOrClear(index: Int) { calls += "set $index" }
    override fun onClearAll() { calls += "clear all" }
    override fun onZero() { calls += "zero" }
    override fun onPreview() { calls += "preview" }
    override fun onApply() { calls += "apply" }
}

/** The Compose UI tests of the reference image setup screen. */
@OptIn(ExperimentalTestApi::class)
class ReferenceImageSetupUiTest {

    private val layout = WindowLayout.of(1280.dp, 800.dp)

    @Composable
    private fun Themed(content: @Composable () -> Unit) {
        AppTheme(mode = ThemeMode.Dark, systemInDarkTheme = true) {
            CompositionLocalProvider(LocalWindowLayout provides layout) { content() }
        }
    }

    private fun setState(): ReferenceImageSetupUiState {
        val cards = List(ReferenceImageSetupUiState.CARD_COUNT) { index ->
            when (index) {
                0 -> ReferenceCardUiState(0, horizontalText = "10.000", verticalText = "20.000", reference = ImageReference(Vec2(100.0, 50.0), Vec2(10000.0, 20000.0)))
                1 -> ReferenceCardUiState(1, horizontalText = "50.000", verticalText = "0.000", reference = ImageReference(Vec2(300.0, 250.0), Vec2(50000.0, 0.0)))
                else -> ReferenceCardUiState(index, visible = true, active = index == 2)
            }
        }
        return ReferenceImageSetupUiState(hasImage = true, imageWidth = 400, imageHeight = 300, cards = cards, canApply = true, reticlePixel = Vec2(200.0, 150.0))
    }

    @Test
    fun theButtonsOfTheSetupFlowCallThePresenter() = runComposeUiTest {
        val events = RecordingSetupEvents()
        var cancelled = 0
        setContent {
            Themed { ReferenceImageSetupScreen(state = setState(), bitmap = null, events = events, onCancel = { cancelled++ }) }
        }
        onNodeWithTag(referenceSetTag(2)).performScrollTo().performClick()
        onNodeWithTag(REFERENCE_SETUP_PREVIEW_TAG).assertIsEnabled().performClick()
        onNodeWithTag(REFERENCE_SETUP_APPLY_TAG).assertIsEnabled().performClick()
        onNodeWithText("Gray").performClick()
        onNodeWithText("Flip horizontal").performClick()
        onNodeWithTag(REFERENCE_SETUP_CANCEL_TAG).performTouchInput { longClick() }
        onNodeWithTag(REFERENCE_SETUP_CANCEL_TAG).performClick()
        assertEquals(listOf("set 2", "preview", "apply", "gray", "flip horizontal", "clear all"), events.calls)
        assertEquals(1, cancelled)
    }

    @Test
    fun aRefusedFitDisablesApplyAndShowsTheText() = runComposeUiTest {
        val events = RecordingSetupEvents()
        var state by mutableStateOf(setState())
        setContent { Themed { ReferenceImageSetupScreen(state = state, bitmap = null, events = events, onCancel = {}) } }
        state = state.copy(canApply = false, fitMessage = UiText.of(Res.string.refimage_fit_three))
        waitForIdle()
        onNodeWithTag(REFERENCE_SETUP_APPLY_TAG).assertIsNotEnabled()
        onNodeWithTag(REFERENCE_SETUP_PREVIEW_TAG).assertIsNotEnabled()
        onNodeWithText("Set two or four reference points — three cannot be fitted").assertExists()
        assertTrue(events.calls.isEmpty())
    }
}
