package org.superdro.core.ui.graphics.view

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.superdro.core.ui.graphics.measure.MEASUREMENT_LOG_TAG
import org.superdro.core.ui.graphics.measure.MEASUREMENT_PANE_TAG
import org.superdro.core.ui.graphics.measure.MeasurementPane
import org.superdro.core.ui.graphics.measure.MeasurementSamples
import org.superdro.core.ui.graphics.measure.RecordingMeasurementEvents

/**
 * The panels of a short graphical view, for example the pane below the readout in portrait
 * (issue 7). All content of the display pane and of the measurement pane must be visible and
 * usable.
 */
@OptIn(ExperimentalTestApi::class)
class GraphicalViewShortUiTest {

    @Composable
    private fun ShortView(events: RecordingViewEvents, displayPaneOpen: Boolean = false, leftPanel: (@Composable () -> Unit)? = null) {
        val (view, controls) = ViewStates.millView(SHORT_WIDTH.toDouble(), SHORT_HEIGHT.toDouble())
        ViewThemed(dark = false, widthDp = SHORT_WIDTH, heightDp = SHORT_HEIGHT) {
            Box(Modifier.size(SHORT_WIDTH.dp, SHORT_HEIGHT.dp)) {
                GraphicalView(
                    view = view,
                    controls = controls,
                    metrics = ViewStates.metrics,
                    events = events,
                    showFullScreen = true,
                    displayPaneOpen = displayPaneOpen,
                    leftPanel = leftPanel,
                    displayPane = {
                        DisplayPane(display = controls.display, hasDrawing = true, layers = controls.drawingLayers, referenceAlpha = 255, events = events)
                    },
                )
            }
        }
    }

    @Test
    fun aShortViewOpensTheDisplayPaneAsASheetWithAllRows() = runComposeUiTest {
        val events = RecordingViewEvents()
        setContent { ShortView(events, displayPaneOpen = true) }
        onNodeWithTag(DISPLAY_SHEET_TAG).assertExists()
        onNodeWithText("Import").performScrollTo().assertIsDisplayed().performClick()
        onNodeWithTag("graphical-display-reference-dim").performScrollTo().assertIsDisplayed().performClick()
        assertTrue(events.calls.contains("import"))
        assertTrue(events.calls.contains("alpha 127"))
    }

    @Test
    fun aTallViewShowsTheDisplayPaneInTheView() = runComposeUiTest {
        val events = RecordingViewEvents()
        val (view, controls) = ViewStates.millView(1280.0, 800.0)
        setContent {
            ViewThemed(dark = false, widthDp = 1280, heightDp = 800) {
                GraphicalView(
                    view = view,
                    controls = controls,
                    metrics = ViewStates.metrics,
                    events = events,
                    displayPaneOpen = true,
                    displayPane = {
                        DisplayPane(display = controls.display, hasDrawing = true, layers = controls.drawingLayers, referenceAlpha = 255, events = events)
                    },
                )
            }
        }
        onNodeWithTag(DISPLAY_SHEET_TAG).assertDoesNotExist()
        onNodeWithTag(DISPLAY_PANE_TAG).assertIsDisplayed()
    }

    @Test
    fun theMeasurementPaneOfAShortViewGetsTheFullHeightAndScrolls() = runComposeUiTest {
        val events = RecordingViewEvents()
        setContent {
            ShortView(events, leftPanel = { MeasurementPane(MeasurementSamples.circle.copy(helpOpen = true), RecordingMeasurementEvents()) })
        }
        // The automatic functions go to the right edge, not below the pane.
        val pane = onNodeWithTag(MEASUREMENT_PANE_TAG).getBoundsInRoot()
        val menu = onNodeWithTag(GRAPHICAL_SWITCH_MENU_TAG).getBoundsInRoot()
        assertTrue(menu.left >= pane.right, "menu $menu, pane $pane")
        assertEquals(SHORT_HEIGHT.dp - 8.dp, pane.bottom)
        // The log row is at the end of the pane. A drag on the pane shows it.
        onNodeWithTag(MEASUREMENT_LOG_TAG).assertIsNotDisplayed()
        repeat(3) { onNodeWithTag(MEASUREMENT_PANE_TAG).performTouchInput { swipeUp() } }
        onNodeWithTag(MEASUREMENT_LOG_TAG).assertIsDisplayed()
    }

    private companion object {
        /** The size of the graphical view below the readout on a portrait tablet of 800 × 1200 dp. */
        const val SHORT_WIDTH: Int = 800
        const val SHORT_HEIGHT: Int = 400
    }
}
