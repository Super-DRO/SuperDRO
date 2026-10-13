package org.superdro.core.ui.functions.patterns

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.superdro.core.functions.patterns.AxisLetter
import org.superdro.core.model.ThemeMode
import org.superdro.core.ui.adaptive.LocalWindowLayout
import org.superdro.core.ui.adaptive.WindowLayout
import org.superdro.core.ui.functions.common.BLOCKED_DIALOG_TAG
import org.superdro.core.ui.functions.common.ERROR_BOX_TAG
import org.superdro.core.ui.functions.common.POINT_PREVIEW_TAG
import org.superdro.core.ui.functions.common.PRIMARY_BUTTON_TAG
import org.superdro.core.ui.functions.common.fieldTextTag
import org.superdro.core.ui.theme.AppTheme

/** The Compose UI tests of the main flows of the pattern screens. */
@OptIn(ExperimentalTestApi::class)
class PatternScreenUiTest {

    @Composable
    private fun Screen(live: LivePattern, onBack: () -> Unit = {}) {
        AppTheme(mode = ThemeMode.System, systemInDarkTheme = false) {
            CompositionLocalProvider(LocalWindowLayout provides WindowLayout.of(1280.dp, 800.dp)) {
                val state by live.presenter.uiState.collectAsState()
                PatternScreen(state, PresenterPatternEvents(live.presenter, onBack))
            }
        }
    }

    @Test
    fun theUserTypesAHoleCircleSeesThePreviewAndCreatesIt() = runComposeUiTest {
        LivePattern(PatternMachines.legacyMill(), PatternKind.HoleCircle).use { live ->
            setContent { Screen(live) }
            onNodeWithTag("$PATTERN_SOURCE_TAG-1").performScrollTo().performClick()
            onNodeWithTag(patternSwitchTag(AxisLetter.Z)).performScrollTo().performClick()
            onNodeWithTag(fieldTextTag(patternFieldTag(PatternInput.Radius))).performScrollTo().performTextInput("50")

            onNodeWithTag(fieldTextTag(patternFieldTag(PatternInput.HoleCount))).performScrollTo().performTextInput("6")
            live.idle()
            waitForIdle()
            onNodeWithTag(POINT_PREVIEW_TAG).assertIsDisplayed()
            onNodeWithText("Circle - 6").performScrollTo().assertIsDisplayed()
            onNodeWithTag(PRIMARY_BUTTON_TAG).performScrollTo().performClick()
            live.idle()
            waitForIdle()
            assertEquals(6, live.presenter.uiState.value.created)
            assertEquals(6, live.subDatums().size)
        }
    }

    @Test
    fun theUserAddsAndRemovesHoleAnglesAndSeesTheError() = runComposeUiTest {
        LivePattern(PatternMachines.legacyMill(), PatternKind.ArbitraryHoleCircle).use { live ->
            setContent { Screen(live) }
            val entry = fieldTextTag(patternFieldTag(PatternInput.AngleEntry))
            listOf("10", "100").forEach { angle ->
                onNodeWithTag(entry).performScrollTo().performTextInput(angle)
                live.idle()
                onNodeWithTag(PATTERN_ADD_ANGLE_TAG).performScrollTo().performClick()
                live.idle()
                waitForIdle()
            }
            assertEquals(listOf(10.0, 100.0), live.presenter.uiState.value.angles)
            onNodeWithTag(patternRemoveAngleTag(1)).performScrollTo().performClick()
            live.idle()
            waitForIdle()
            assertEquals(listOf(10.0), live.presenter.uiState.value.angles)
            onNodeWithTag(PRIMARY_BUTTON_TAG).performScrollTo().performClick()
            live.idle()
            waitForIdle()
            onNodeWithTag(ERROR_BOX_TAG).performScrollTo().assertIsDisplayed()
            onNodeWithText("• At least two hole angles are required.", substring = true).assertIsDisplayed()
        }
    }

    @Test
    fun theLathePlaneButtonIsDisabledAndShowsZx() = runComposeUiTest {
        LivePattern(PatternMachines.lathe(), PatternKind.HoleCircle).use { live ->
            setContent { Screen(live) }
            onNodeWithText("Circular Point Array").assertIsDisplayed()
            onNodeWithTag("$PATTERN_PLANE_TAG-0").assertIsNotEnabled()
            onNodeWithText("ZX").assertIsDisplayed()
            onNodeWithTag("$PATTERN_PLANE_TAG-1").assertDoesNotExist()
            onNodeWithTag(patternSwitchTag(AxisLetter.Y)).assertDoesNotExist()
        }
    }

    @Test
    fun theBlockerDialogClosesTheScreen() = runComposeUiTest {
        LivePattern(PatternMachines.legacyMill { it.copy(armedThreadId = "thread-1") }, PatternKind.HoleGrid).use { live ->
            var closed = false
            setContent { Screen(live) { closed = true } }
            onNodeWithTag(BLOCKED_DIALOG_TAG).assertIsDisplayed()
            onNodeWithText("Not Available Right Now").assertIsDisplayed()
            onNodeWithText("Close").performClick()
            live.idle()
            waitForIdle()
            assertTrue(closed)
        }
    }
}
