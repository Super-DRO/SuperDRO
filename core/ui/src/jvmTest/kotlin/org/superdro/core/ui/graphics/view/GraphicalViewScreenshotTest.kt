package org.superdro.core.ui.graphics.view

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue
import org.superdro.core.functions.compensation.ToolPosition
import org.superdro.core.functions.graphics.GuideButtons
import org.superdro.core.functions.graphics.Vec2
import org.superdro.core.functions.thread.ThreadCutStatus
import org.superdro.core.model.GraphicalDisplay
import org.superdro.core.model.GraphicalViewPlacement
import org.superdro.core.resources.Res
import org.superdro.core.resources.route_sub_datums
import org.superdro.core.ui.ScreenshotRenderer
import org.superdro.core.ui.adaptive.WindowLayout
import org.superdro.core.ui.graphics.measure.MeasurementModeToggle
import org.superdro.core.ui.graphics.measure.MeasurementPane
import org.superdro.core.ui.graphics.measure.MeasurementSamples
import org.superdro.core.ui.graphics.measure.RecordingMeasurementEvents
import org.superdro.core.ui.graphics.thread.ThreadChoice
import org.superdro.core.ui.graphics.thread.ThreadPanelUiState
import org.superdro.core.ui.navigation.SidePanelPlaceholder
import org.superdro.core.ui.readout.NoReadoutEvents
import org.superdro.core.ui.readout.ReadoutSamples
import org.superdro.core.ui.readout.ReadoutScreen

/**
 * Draws the graphical view to PNG files (`core/ui/build/screenshots/graphics-view-*.png`) in light
 * and dark mode.
 */
class GraphicalViewScreenshotTest {

    private fun both(name: String, width: Int, height: Int, content: @androidx.compose.runtime.Composable (Boolean) -> Unit) {
        listOf(false, true).forEach { dark ->
            val file = ScreenshotRenderer.render("$name-${if (dark) "dark" else "light"}", widthDp = width, heightDp = height, frames = 30) {
                ViewThemed(dark, width, height) { content(dark) }
            }
            assertTrue(file.length() > 0, name)
        }
    }

    @Test
    fun theMillViewWithTheDisplayPane() {
        both("graphics-view-mill-landscape", 1280, 800) {
            val (view, controls) = ViewStates.millView(1280.0, 800.0)
            val events = RecordingViewEvents()
            GraphicalView(
                view = view,
                controls = controls,
                metrics = ViewStates.metrics,
                events = events,
                showFullScreen = true,
                displayPaneOpen = true,
                displayPane = {
                    DisplayPane(display = controls.display, hasDrawing = true, layers = controls.drawingLayers, referenceAlpha = 255, events = events)
                },
                measurementMarks = listOf(MeasurementMark(org.superdro.core.functions.PointUm(-30_000.0, -20_000.0), suspect = false)),
            )
        }
    }

    @Test
    fun theMillViewInThePortraitPaneOutOfFollowMode() {
        both("graphics-view-mill-pane", 800, 500) {
            val (view, controls) = ViewStates.millView(800.0, 500.0, followMode = false, display = GraphicalDisplay(drawingDimmed = true, crosshairsDimmed = true))
            GraphicalView(view = view, controls = controls, metrics = ViewStates.metrics, events = RecordingViewEvents(), showFullScreen = true)
        }
    }

    @Test
    fun theLatheViewWithTheArmedThread() {
        both("graphics-view-lathe-thread", 1280, 800) {
            val (view, controls) = ViewStates.latheView(1280.0, 800.0)
            GraphicalView(
                view = view,
                controls = controls,
                metrics = ViewStates.metrics,
                events = RecordingViewEvents(),
                threadPanel = ThreadPanelUiState.Armed(
                    thread = ThreadChoice("t", "Thread 1", "M10 × 1.5", internal = false),
                    status = ThreadCutStatus.InTheCut,
                    lastPass = "0.736",
                    infeed = "0.100",
                    cutLoad = "0.7",
                    metric = true,
                ),
            )
        }
    }

    @Test
    fun theDirectionPickerAndTheSubDatumMenu() {
        both("graphics-view-direction", 1280, 800) {
            val (view, controls) = ViewStates.millView(1280.0, 800.0)
            GraphicalView(view = view.copy(overlay = ViewOverlay.DirectionPicker(ToolPosition.MiddleLeft)), controls = controls, metrics = ViewStates.metrics, events = RecordingViewEvents())
        }
        both("graphics-view-menu", 1280, 800) {
            val (view, controls) = ViewStates.millView(1280.0, 800.0)
            val menu = ViewOverlay.SubDatumMenu("p1", "Point 1", GuideButtons(5_000.0, null), Vec2(700.0, 300.0))
            GraphicalView(view = view.copy(overlay = menu), controls = controls, metrics = ViewStates.metrics, events = RecordingViewEvents())
        }
    }

    @Test
    fun theAddGuideDialogAndTheRotatePartSheet() {
        both("graphics-view-add-guide", 1280, 800) {
            AddGuideDialog(AddGuideUiState(text = "12.345"), {}, {}, {}, {}, {})
        }
        both("graphics-view-rotate-part", 800, 1280) {
            val readout = FakeTabReadout(ViewSamples.frame(ViewSamples.mill))
            val presenter = RotatePartPresenter(readout)
            RotatePartSheet(state = presenter.uiState.value.copy(pivot = PivotSource.Custom, pivotXText = "10.000", pivotYText = "-5.000"), presenter = presenter, onClose = {})
        }
    }

    @Test
    fun theDisplaySheetAndTheMeasurementPaneBelowTheReadoutInPortrait() {
        listOf(false, true).forEach { measure ->
            both(if (measure) "graphics-view-readout-portrait-measure" else "graphics-view-readout-portrait-display", 800, 1200) {
                val sample = ReadoutSamples.mill()
                ReadoutScreen(
                    state = sample.state.copy(graphicalViewPlacement = GraphicalViewPlacement.BelowReadout),
                    axisValue = sample::flow,
                    events = NoReadoutEvents,
                    layout = WindowLayout.of(800.dp, 1200.dp),
                    sidePanelContent = { SidePanelPlaceholder(Res.string.route_sub_datums) },
                    graphicalViewPane = {
                        val (view, controls) = ViewStates.millView(800.0, 400.0)
                        val events = RecordingViewEvents()
                        GraphicalView(
                            view = view,
                            controls = controls,
                            metrics = ViewStates.metrics,
                            events = events,
                            showFullScreen = true,
                            displayPaneOpen = !measure,
                            extraTopStrip = { if (measure) MeasurementModeToggle(on = true, onToggle = {}) },
                            leftPanel = if (measure) ({ MeasurementPane(MeasurementSamples.circle, RecordingMeasurementEvents()) }) else null,
                            displayPane = {
                                DisplayPane(display = controls.display, hasDrawing = true, layers = controls.drawingLayers, referenceAlpha = 255, events = events)
                            },
                        )
                    },
                )
            }
        }
    }

    @Test
    fun theReadoutWithTheGraphicalViewBelowItInPortrait() {
        both("graphics-view-readout-portrait", 800, 1280) {
            val sample = ReadoutSamples.mill()
            ReadoutScreen(
                state = sample.state.copy(graphicalViewPlacement = GraphicalViewPlacement.BelowReadout),
                axisValue = sample::flow,
                events = NoReadoutEvents,
                layout = WindowLayout.of(800.dp, 1280.dp),
                sidePanelContent = { SidePanelPlaceholder(Res.string.route_sub_datums) },
                graphicalViewPane = {
                    val (view, controls) = ViewStates.millView(800.0, 400.0)
                    GraphicalView(view = view, controls = controls, metrics = ViewStates.metrics, events = RecordingViewEvents(), showFullScreen = true)
                },
            )
        }
    }
}
