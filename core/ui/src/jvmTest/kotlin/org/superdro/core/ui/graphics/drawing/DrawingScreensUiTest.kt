package org.superdro.core.ui.graphics.drawing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.superdro.core.model.DrawingLayer
import org.superdro.core.model.ShapeType
import org.superdro.core.model.ThemeMode
import org.superdro.core.ui.adaptive.LocalWindowLayout
import org.superdro.core.ui.adaptive.WindowLayout
import org.superdro.core.ui.theme.AppTheme

/** The Compose UI tests of the drawing screens. */
@OptIn(ExperimentalTestApi::class)
class DrawingScreensUiTest {

    @Composable
    private fun Themed(content: @Composable () -> Unit) {
        AppTheme(mode = ThemeMode.Dark, systemInDarkTheme = true) {
            CompositionLocalProvider(LocalWindowLayout provides WindowLayout.of(1280.dp, 800.dp)) { content() }
        }
    }

    @Test
    fun theProblemListShowsTheDetailsAndDoneCloses() = runComposeUiTest {
        var done = 0
        setContent { Themed { DrawingProblemsScreen(DrawingSamples.problems) { done++ } } }
        onNodeWithText("SuperDRO couldn't import this drawing.").assertExists()
        onNodeWithTag(DRAWING_PROBLEM_LIST_TAG).assertDoesNotExist()
        onNodeWithTag(DRAWING_DETAILS_TAG).performClick()
        onNodeWithTag(DRAWING_PROBLEM_LIST_TAG).assertExists()
        onNodeWithText("12000 entities exceeds the 10000 cap").assertExists()
        onNodeWithText("ENTITY_LIMIT_EXCEEDED").assertExists()
        onNodeWithTag(DRAWING_PROBLEMS_DONE_TAG).performClick()
        assertEquals(1, done)
    }

    @Test
    fun theSetupScreenSendsItsEvents() = runComposeUiTest {
        val state = DrawingSamples.setupState()
        val events = RecordingSetupEvents()
        setContent { Themed { DrawingSetupScreen(state.copy(banner = DrawingSetupPresenter.bannerOf(org.superdro.core.functions.drawing.UnitCheck.Mismatch(1.25))), events) } }
        onNodeWithTag(DRAWING_BANNER_TAG).assertExists()
        onNodeWithText("These points are 1.25 mm further apart on the machine than in the drawing.").assertExists()
        onNodeWithTag(drawingStepTag("mirror-h")).performClick()
        onNodeWithTag(drawingStepTag("rotate-right")).performClick()
        onNodeWithContentDescription("Mirror top and bottom").performClick()
        onNodeWithTag(DRAWING_PREVIEW_TAG).performTouchInput { click(center) }
        onAllNodes(hasSetTextAction()).onFirst().performTextInput("7")
        onNodeWithTag(drawingCardSetTag(1)).performClick()
        onNodeWithTag(drawingCardSyncTag(1)).performClick()
        onNodeWithTag(drawingGroupCreateTag(0)).performScrollTo().performClick()
        onNodeWithTag(DRAWING_APPLY_TAG).assertIsEnabled().performClick()
        onAllNodesWithText("Reset").onFirst().performClick()
        waitForIdle()
        assertTrue("mirrorHorizontal" in events.calls)
        assertTrue("rotateRight" in events.calls)
        assertTrue("mirrorVertical" in events.calls)
        assertTrue("set1" in events.calls)
        assertTrue("sync1" in events.calls)
        assertTrue("apply" in events.calls)
        assertTrue("reset" in events.calls)
        assertTrue(events.calls.any { call -> call.startsWith("create0=") })
        // The first card is set, so its fields are off: the first text field is the X field of the second card.
        assertTrue(events.calls.any { call -> call.startsWith("x1=") }, events.calls.toString())
        // A tap that is not a double tap comes after the double tap timeout.
        waitUntil(timeoutMillis = 2000) { "tap" in events.calls }
    }

    @Test
    fun theDeleteDialogNamesTheShapeType() = runComposeUiTest {
        var deleted = 0
        setContent { Themed { DeleteShapeDialog(ShapeType.Circle, onConfirm = { deleted++ }, onDismiss = {}) } }
        onNodeWithText("Are you sure you want to remove this circle?").assertExists()
        onNodeWithTag(DRAWING_DELETE_CONFIRM_TAG).performClick()
        assertEquals(1, deleted)
    }

    @Test
    fun theLayerListShowsTheStateAndTogglesALayer() = runComposeUiTest {
        val toggles = mutableListOf<Pair<Int, Boolean>>()
        setContent {
            Themed {
                DrawingLayerList(listOf(DrawingLayer(0, "Outline"), DrawingLayer(1, "", hidden = true)), onToggle = { index, hidden -> toggles += index to hidden })
            }
        }
        onNodeWithText("Outline").assertExists()
        onNodeWithText("Layer 1").assertExists()
        onNodeWithText("Hidden").assertExists()
        onNodeWithTag(drawingLayerTag(0)).performClick()
        onNodeWithTag(drawingLayerTag(1)).performClick()
        assertEquals(listOf(0 to true, 1 to false), toggles)
    }

    @Test
    fun theToolGeometryPreviewAttaches() = runComposeUiTest {
        val state = DrawingSamples.toolGeometryState()
        val calls = mutableListOf<String>()
        val events = object : ToolGeometryEvents {
            override fun onImport() { calls += "import" }
            override fun onDrawnIn(inch: Boolean) { calls += "inch=$inch" }
            override fun onAttach() { calls += "attach" }
            override fun onCancelPreview() { calls += "cancel" }
            override fun onRemove() { calls += "remove" }
            override fun onDone() { calls += "done" }
        }
        setContent { Themed { ToolGeometryScreen(state, events) } }
        onNodeWithText("The outline has no problems.").assertExists()
        onNodeWithText("in").performClick()
        onNodeWithTag(TOOL_GEOMETRY_ATTACH_TAG).performClick()
        assertEquals(listOf("inch=true", "attach"), calls)
    }
}
