package org.superdro.core.ui.functions.thread

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.PartialInsertPreset
import org.superdro.core.model.ThreadClass
import org.superdro.core.model.ThreadCutter
import org.superdro.core.model.ThreadForm
import org.superdro.core.model.ThreadInfeedMode
import org.superdro.core.ui.adaptive.LocalWindowLayout
import org.superdro.core.ui.adaptive.WindowLayout
import org.superdro.core.ui.functions.FunctionsHarness
import org.superdro.core.ui.functions.common.CANCEL_BUTTON_TAG
import org.superdro.core.ui.functions.common.LengthFieldState
import org.superdro.core.ui.functions.common.PRIMARY_BUTTON_TAG
import org.superdro.core.ui.functions.common.fieldTextTag
import org.superdro.core.ui.functions.renderFunctionScreenshot
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.theme.AppTheme

/** The Compose UI test and the screenshots of the thread setup screen. */
@OptIn(ExperimentalTestApi::class)
class ThreadSetupScreenTest {

    private class RecordingEvents(val presenter: ThreadSetupPresenter? = null) : ThreadSetupEvents {
        val calls = mutableListOf<String>()
        override fun onRestore(id: String) { calls += "restore $id" }
        override fun onMode(mode: ThreadToolMode) { presenter?.onMode(mode); calls += "mode $mode" }
        override fun onInternal(internal: Boolean) { presenter?.onInternal(internal); calls += "internal $internal" }
        override fun onLeftHand(leftHand: Boolean) { presenter?.onLeftHand(leftHand); calls += "leftHand $leftHand" }
        override fun onStarts(starts: Int) { presenter?.onStarts(starts); calls += "starts $starts" }
        override fun onForm(form: ThreadForm) { presenter?.onForm(form); calls += "form $form" }
        override fun onSize(size: String) { presenter?.onSize(size); calls += "size $size" }
        override fun onClass(threadClass: ThreadClass) { presenter?.onClass(threadClass); calls += "class $threadClass" }
        override fun onCutter(cutter: ThreadCutter) { presenter?.onCutter(cutter); calls += "cutter $cutter" }
        override fun onInsertPreset(preset: PartialInsertPreset) { presenter?.onInsertPreset(preset); calls += "preset $preset" }
        override fun onInfeedMode(mode: ThreadInfeedMode) { presenter?.onInfeedMode(mode); calls += "infeed $mode" }
        override fun onText(field: ThreadField, text: String) { presenter?.onText(field, text); calls += "text $field $text" }
        override fun onSyncStartZ() { calls += "sync" }
        override fun onCreate() { calls += "create" }
        override fun onBack() { calls += "back" }
    }

    @Test
    fun theFormAndTheLiveDiameters() = runComposeUiTest {
        val scope = TestScope()
        val harness = FunctionsHarness(scope)
        try {
            lateinit var presenter: ThreadSetupPresenter
            scope.runTest {
                harness.start(MachineProfile.create(MachineType.Lathe, "Lathe").copy(metricDefault = true))
                presenter = ThreadSetupPresenter(harness.presenter, harness.services)
                advanceUntilIdle()
                presenter.onSize("M10 × 1.5")
            }
            val events = RecordingEvents(presenter)
            setContent {
                val state by presenter.uiState.collectAsState()
                AppTheme {
                    CompositionLocalProvider(LocalWindowLayout provides WindowLayout.of(1280.dp, 800.dp)) { ThreadSetupScreen(state, events) }
                }
            }
            onNodeWithTag("thread-type-1").performClick()
            onNodeWithTag(ThreadTags.PRE_OP).assert(hasText("9.968 mm"))
            onNodeWithTag(ThreadTags.CUT_TO).assert(hasText("7.695 mm"))
            onNodeWithTag(ThreadTags.SCHEDULE).assertExists()
            onNodeWithTag(PRIMARY_BUTTON_TAG).performScrollTo().assertIsEnabled()
            onNodeWithTag(ThreadTags.FORM).performScrollTo().performClick()
            onNodeWithTag(ThreadTags.item(ThreadTags.FORM, ThreadForm.Unc.ordinal)).performClick()
            onNodeWithTag(fieldTextTag(ThreadTags.field(ThreadField.Length))).performScrollTo().performTextInput("1")
            assertEquals(ThreadForm.Unc, presenter.uiState.value.form)
            assertEquals("1", presenter.uiState.value.length.text)

            // Switch to Tap & Drill mode and verify the Tap & Drill results card renders and Create hides.
            onNodeWithTag("${ThreadTags.MODE}-1").performScrollTo().performClick()
            assertEquals(ThreadToolMode.TapAndDrill, presenter.uiState.value.mode)
            onNodeWithTag(ThreadTags.TAP_RESULTS).assertExists()
            onNodeWithTag("thread-cut-tap-closest").assertExists()
            onNodeWithTag(PRIMARY_BUTTON_TAG).assertDoesNotExist()
            onNodeWithTag(CANCEL_BUTTON_TAG).assertDoesNotExist()

            presenter.close()
        } finally {
            harness.close()
        }
    }

    @Test
    fun millShowsTapAndDrillWithoutCreateButton() = runComposeUiTest {
        val scope = TestScope()
        val harness = FunctionsHarness(scope)
        try {
            lateinit var presenter: ThreadSetupPresenter
            scope.runTest {
                harness.start(MachineProfile.create(MachineType.VerticalMill, "Mill").copy(metricDefault = true))
                presenter = ThreadSetupPresenter(harness.presenter, harness.services)
                advanceUntilIdle()
                presenter.onSize("M10 × 1.5")
            }
            val events = RecordingEvents(presenter)
            setContent {
                val state by presenter.uiState.collectAsState()
                AppTheme {
                    CompositionLocalProvider(LocalWindowLayout provides WindowLayout.of(1280.dp, 800.dp)) { ThreadSetupScreen(state, events) }
                }
            }
            onNodeWithTag(ThreadTags.MODE).assertDoesNotExist()
            onNodeWithTag(ThreadTags.TAP_RESULTS).assertExists()
            onNodeWithTag("thread-cut-tap-closest").assertExists()
            onNodeWithTag(PRIMARY_BUTTON_TAG).assertDoesNotExist()
            onNodeWithTag(CANCEL_BUTTON_TAG).assertDoesNotExist()

            presenter.close()
        } finally {
            harness.close()
        }
    }

    @Test
    fun theThreadSetupRendersInLightAndDarkMode() {
        val scope = TestScope(StandardTestDispatcher())
        val harness = FunctionsHarness(scope)
        try {
            lateinit var state: ThreadSetupUiState
            scope.runTest {
                harness.start(MachineProfile.create(MachineType.Lathe, "Lathe").copy(metricDefault = true))
                val presenter = ThreadSetupPresenter(harness.presenter, harness.services)
                advanceUntilIdle()
                presenter.onInternal(false)
                presenter.onSize("M10 × 1.5")
                presenter.onText(ThreadField.Length, "20")
                state = presenter.uiState.value
                presenter.close()
            }
            val withError = state.copy(error = UiText.Plain("Thread Length must be greater than zero."), length = LengthFieldState(metric = true))
            listOf(false, true).forEach { dark ->
                val mode = if (dark) "dark" else "light"
                renderFunctionScreenshot("functions-thread-setup-$mode", dark) { ThreadSetupScreen(state, RecordingEvents()) }
                renderFunctionScreenshot("functions-thread-setup-portrait-$mode", dark, widthDp = 800, heightDp = 1280) { ThreadSetupScreen(state, RecordingEvents()) }
                renderFunctionScreenshot("functions-thread-setup-error-$mode", dark) { ThreadSetupScreen(withError, RecordingEvents()) }
            }
        } finally {
            harness.close()
        }
    }
}
