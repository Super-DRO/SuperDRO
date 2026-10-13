package org.superdro.core.ui.integration

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue
import org.superdro.core.model.AxisId
import org.superdro.core.model.SidePanelMode
import org.superdro.core.model.ToolKind
import org.superdro.core.ui.ScreenshotRenderer
import org.superdro.core.ui.adaptive.WindowLayout
import org.superdro.core.ui.functions.common.LengthFieldState
import org.superdro.core.ui.functions.sheets.ZReferenceEvents
import org.superdro.core.ui.functions.sheets.ZReferenceSheet
import org.superdro.core.ui.functions.sheets.ZReferenceUiState
import org.superdro.core.ui.functions.tools.ToolEditEvents
import org.superdro.core.ui.functions.tools.ToolEditSheet
import org.superdro.core.ui.functions.tools.ToolEditUiState
import org.superdro.core.ui.graphics.measure.MeasurementModeToggle
import org.superdro.core.ui.graphics.probe.ProbeSamples
import org.superdro.core.ui.graphics.probe.ZReferenceAutoSection
import org.superdro.core.ui.graphics.view.GraphicalView
import org.superdro.core.ui.graphics.view.RecordingViewEvents
import org.superdro.core.ui.graphics.view.ViewStates
import org.superdro.core.ui.graphics.view.ViewThemed
import org.superdro.core.ui.readout.NoReadoutEvents
import org.superdro.core.ui.readout.ReadoutSamples
import org.superdro.core.ui.readout.ReadoutScreen

/** The screenshots of the screens that the links between the areas change, in light and dark mode. */
class IntegrationScreenshotTest {

    private fun both(name: String, width: Int, height: Int, content: @Composable () -> Unit) {
        listOf(false, true).forEach { dark ->
            val file = ScreenshotRenderer.render("integration-$name-${if (dark) "dark" else "light"}", widthDp = width, heightDp = height, frames = 30) {
                ViewThemed(dark, width, height) { content() }
            }
            assertTrue(file.length() > 0, name)
        }
    }

    private object NoZReferenceEvents : ZReferenceEvents {
        override fun onSetterHeight(text: String) = Unit
        override fun onSetterHeightUnit(metric: Boolean) = Unit
        override fun onDesiredZ(text: String) = Unit
        override fun onDesiredZUnit(metric: Boolean) = Unit
        override fun onApply() = Unit
        override fun onDismiss() = Unit
    }

    private object NoToolEvents : ToolEditEvents {
        override fun onName(text: String) = Unit
        override fun onDescription(text: String) = Unit
        override fun onMetric(metric: Boolean) = Unit
        override fun onDiameter(text: String) = Unit
        override fun onToothCount(text: String) = Unit
        override fun onZOffset(text: String) = Unit
        override fun onXOffset(text: String) = Unit
        override fun onInternal(internal: Boolean) = Unit
        override fun onSync(axis: AxisId) = Unit
        override fun onSave() = Unit
        override fun onDismiss() = Unit
    }

    @Test
    fun theLandscapeReadoutWithTheGraphicalViewInTheSupportingPane() {
        both("readout-landscape-graphical", 1280, 800) {
            val sample = ReadoutSamples.mill()
            ReadoutScreen(
                state = sample.state.copy(sidePanel = sample.state.sidePanel.copy(mode = SidePanelMode.Off)),
                axisValue = sample::flow,
                events = NoReadoutEvents,
                layout = WindowLayout.of(1280.dp, 800.dp),
                sidePanelContent = {},
                graphicalViewPane = {
                    val (view, controls) = ViewStates.millView(420.0, 700.0)
                    GraphicalView(
                        view = view,
                        controls = controls,
                        metrics = ViewStates.metrics,
                        events = RecordingViewEvents(),
                        showFullScreen = true,
                        extraTopStrip = { MeasurementModeToggle(on = false, onToggle = {}) },
                    )
                },
            )
        }
    }

    @Test
    fun theZReferenceSheetWithTheAutomaticMode() {
        both("z-reference-auto", 800, 1280) {
            ZReferenceSheet(
                ZReferenceUiState(loaded = true, setterHeight = LengthFieldState.of(25_000.0, true), desiredZ = LengthFieldState(metric = true)),
                NoZReferenceEvents,
            ) { ZReferenceAutoSection(ProbeSamples.zAuto(armed = false), onToggle = {}, onSelectProbe = {}, onNewProbe = {}) }
        }
    }

    @Test
    fun theLatheToolEditSheetWithTheCutterGeometry() {
        val lathe = ToolEditUiState(
            loaded = true, newTool = false, kind = ToolKind.Lathe, name = "OD Turning", description = "CCMT insert", metric = true,
            xOffset = LengthFieldState.of(0.0, true), zOffset = LengthFieldState.of(0.0, true), toolId = "5",
        )
        both("tool-edit-lathe-geometry", 1280, 800) { ToolEditSheet(lathe, NoToolEvents) }
        both("tool-edit-lathe-new", 800, 1280) { Box(Modifier.width(800.dp)) { ToolEditSheet(lathe.copy(newTool = true, toolId = null), NoToolEvents) } }
    }
}
