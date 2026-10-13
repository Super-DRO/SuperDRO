package org.superdro.core.ui.graphics.view

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.superdro.core.functions.compensation.ToolPosition
import org.superdro.core.model.GraphicalDisplay
import org.superdro.core.model.Plane
import org.superdro.core.ui.graphics.thread.THREAD_PANEL_TAG
import org.superdro.core.ui.graphics.thread.ThreadChoice
import org.superdro.core.ui.graphics.thread.ThreadPanelUiState
import org.superdro.core.ui.graphics.thread.threadRowTag

/** The Compose UI tests of the graphical view and its dialogs. */
@OptIn(ExperimentalTestApi::class)
class GraphicalViewUiTest {

    @Test
    fun theSwitchesAndThePlaneSelectorSendTheirEvents() = runComposeUiTest {
        val events = RecordingViewEvents()
        val (view, controls) = ViewStates.millView(1280.0, 800.0)
        setContent {
            ViewThemed(dark = true, widthDp = 1280, heightDp = 800) {
                GraphicalView(view = view, controls = controls, metrics = ViewStates.metrics, events = events)
            }
        }
        onNodeWithTag(graphicalSwitchTag(GraphicalSwitch.SnapToGuide)).performClick()
        onNodeWithTag(graphicalSwitchTag(GraphicalSwitch.SnapToPoint)).performClick()
        onNodeWithTag(graphicalPlaneTag(Plane.XZ)).performClick()
        onNodeWithText("1:5").performClick()
        assertEquals(listOf("switch SnapToGuide true", "switch SnapToPoint false", "plane XZ", "zoom 1:5"), events.calls)
    }

    @Test
    fun reCenterShowsOutOfFollowModeAndTheLockBadgeShowsWhenTheViewIsLocked() = runComposeUiTest {
        val events = RecordingViewEvents()
        val (free, controls) = ViewStates.millView(1280.0, 800.0, followMode = false)
        val (locked, _) = ViewStates.millView(1280.0, 800.0, display = GraphicalDisplay(viewLocked = true))
        var state by androidx.compose.runtime.mutableStateOf(free)
        setContent {
            ViewThemed(dark = false, widthDp = 1280, heightDp = 800) {
                GraphicalView(view = state, controls = controls, metrics = ViewStates.metrics, events = events)
            }
        }
        onNodeWithTag(GRAPHICAL_RECENTER_TAG).performClick()
        assertEquals(listOf("recenter"), events.calls)
        state = locked
        waitForIdle()
        onNodeWithTag(GRAPHICAL_RECENTER_TAG).assertDoesNotExist()
        onNodeWithTag(GRAPHICAL_LOCK_BADGE_TAG).assertIsDisplayed()
    }

    @Test
    fun theDirectionPickerShowsTheCurrentDirectionAndSendsTheChoice() = runComposeUiTest {
        val events = RecordingViewEvents()
        val (view, controls) = ViewStates.millView(1280.0, 800.0)
        setContent {
            ViewThemed(dark = true, widthDp = 1280, heightDp = 800) {
                GraphicalView(
                    view = view.copy(overlay = ViewOverlay.DirectionPicker(ToolPosition.MiddleLeft)),
                    controls = controls,
                    metrics = ViewStates.metrics,
                    events = events,
                )
            }
        }
        onNodeWithTag(directionTag(ToolPosition.FrontRight)).performClick()
        assertEquals(listOf("direction FrontRight"), events.calls)
    }

    @Test
    fun theDisplayPaneTogglesTheLayers() = runComposeUiTest {
        val events = RecordingViewEvents()
        setContent {
            ViewThemed(dark = false, widthDp = 600, heightDp = 1000) {
                DisplayPane(display = GraphicalDisplay(), hasDrawing = true, layers = ViewSamples.drawing.layers, referenceAlpha = 255, events = events)
            }
        }
        onNodeWithTag("graphical-display-tool-shown").performClick()
        onNodeWithTag("graphical-display-crosshairs-lock").performClick()
        onNodeWithTag("graphical-display-reference-dim").performScrollTo().performClick()
        assertEquals(GraphicalDisplay(toolShown = false, viewLocked = true), events.display)
        assertTrue(events.calls.contains("alpha 127"))
    }

    @Test
    fun theThreadPanelArmsTheSelectedThread() = runComposeUiTest {
        val events = RecordingViewEvents()
        val (view, controls) = ViewStates.latheView(1280.0, 800.0)
        setContent {
            ViewThemed(dark = true, widthDp = 1280, heightDp = 800) {
                GraphicalView(
                    view = view,
                    controls = controls.copy(threadArmed = false),
                    metrics = ViewStates.metrics,
                    events = events,
                    threadPanel = ThreadPanelUiState.Chooser(listOf(ThreadChoice("t1", "Thread 1", "M10 × 1.5", internal = false))),
                )
            }
        }
        onNodeWithTag(THREAD_PANEL_TAG).assertIsDisplayed()
        onNodeWithText("Thread 1 · M10 × 1.5 external").assertIsDisplayed()
        onNodeWithTag(threadRowTag("t1")).performClick()
        assertEquals(listOf("thread t1"), events.calls)
    }

    @Test
    fun theAddGuideDialogSendsTheTypedValue() = runComposeUiTest {
        val calls = mutableListOf<String>()
        var state by androidx.compose.runtime.mutableStateOf(AddGuideUiState(text = "1.000"))
        setContent {
            ViewThemed(dark = false, widthDp = 1280, heightDp = 800) {
                AddGuideDialog(
                    state = state,
                    onText = { text -> state = state.copy(text = text) },
                    onOrientation = { orientation -> calls += orientation.name },
                    onSync = { calls += "sync" },
                    onAdd = { calls += "add ${state.text}" },
                    onCancel = { calls += "cancel" },
                )
            }
        }
        onNodeWithTag(ADD_GUIDE_FIELD_TAG).performTextReplacement("++2.5")
        onNodeWithText("Horizontal").performClick()
        onNodeWithTag(ADD_GUIDE_BUTTON_TAG).performClick()
        assertEquals(listOf("Horizontal", "add ++2.5"), calls)
    }

    @Test
    fun theRotatePartSheetStoresTheTypedAngle() = runComposeUiTest {
        val readout = FakeTabReadout(ViewSamples.frame(ViewSamples.mill, ViewSamples.millWorkspace(), x = 1_000.0, y = 1_000.0))
        val presenter = RotatePartPresenter(readout)
        var closed = false
        setContent {
            ViewThemed(dark = true, widthDp = 800, heightDp = 1280) {
                val state by presenter.uiState.collectAsState()
                RotatePartSheet(state = state, presenter = presenter, onClose = { closed = true })
            }
        }
        onNodeWithTag(ROTATE_ANGLE_TAG).performTextReplacement("-12.5")
        onNodeWithTag(ROTATE_APPLY_TAG).performClick()
        waitForIdle()
        assertTrue(closed)
        assertEquals(-45_000.0, readout.frame.value!!.workspace.partRotation.angleArcSec)
    }
}

/** The left panel of the view can scroll by itself (the measurement pane). */
@OptIn(ExperimentalTestApi::class)
class GraphicalViewLeftPanelUiTest {

    @Test
    fun aLeftPanelThatScrollsByItselfShowsAboveTheCanvas() = runComposeUiTest {
        val (view, controls) = ViewStates.millView(1280.0, 800.0)
        setContent {
            ViewThemed(dark = false, widthDp = 1280, heightDp = 800) {
                GraphicalView(
                    view = view,
                    controls = controls,
                    metrics = ViewStates.metrics,
                    events = RecordingViewEvents(),
                    leftPanel = {
                        androidx.compose.foundation.layout.Column(
                            androidx.compose.ui.Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
                        ) { repeat(40) { index -> androidx.compose.material3.Text("Row $index") } }
                    },
                )
            }
        }
        onNodeWithText("Row 0").assertIsDisplayed()
        onNodeWithTag(GRAPHICAL_SWITCH_MENU_TAG).assertIsDisplayed()
    }
}
