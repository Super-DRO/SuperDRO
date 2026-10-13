package org.superdro.core.ui.graphics.measure

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import org.superdro.core.functions.measurement.MeasurementVerb
import org.superdro.core.functions.measurement.ResultKey
import org.superdro.core.functions.measurement.SlotState
import org.superdro.core.model.MachineMode
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.ui.graphics.GraphicsHarness
import org.superdro.core.ui.adapter.probe.ProbeFunction
import org.superdro.core.ui.adapter.probe.probeOptionTag
import org.superdro.core.ui.adapter.probe.probeSelectorTag

/** The Compose UI tests of the measurement pane and the measurement sheets. */
@OptIn(ExperimentalTestApi::class)
class MeasurementUiTest {

    @Test
    fun theCompleteCircleShowsTheResultsAndTheButtons() = runComposeUiTest {
        val events = RecordingMeasurementEvents()
        setContent { MeasurementSamples.Themed(dark = false) { MeasurementPane(MeasurementSamples.circle, events) } }
        onNodeWithTag(resultTag(ResultKey.Diameter)).assertTextEquals("Ø 52.000 mm")
        onNodeWithTag(resultTag(ResultKey.Center)).assertTextEquals("20.000, 15.000 mm")
        // Only the open function shows in the strip.
        assertEquals(1, onAllNodesWithTag(verbTag(MeasurementVerb.Circle)).fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithTag(verbTag(MeasurementVerb.Point)).fetchSemanticsNodes().size)
        onNodeWithTag(addTag(ResultKey.Center)).performClick()
        onNodeWithTag(addTag(ResultKey.Diameter)).performClick()
        onNodeWithTag(MEASUREMENT_LOG_TAG).performClick()
        onNodeWithTag(slotTag(1)).performTouchInput { longClick() }
        onNodeWithTag(MEASUREMENT_RESET_TAG).performClick()
        onNodeWithTag(verbTag(MeasurementVerb.Circle)).performClick()
        assertEquals(
            listOf("add center Fitted center", "add diameter ", "log Circle from 3 points", "slotLong 1", "reset", "verb circle_from_3_points"),
            events.calls,
        )
    }

    @Test
    fun theHelpTextShowsInThePaneAndNotInADialog() = runComposeUiTest {
        // The dialog rule: the help text is general information. It shows in the pane, below the title.
        val events = RecordingMeasurementEvents()
        var state by mutableStateOf(MeasurementSamples.circle)
        setContent { MeasurementSamples.Themed(dark = false) { MeasurementPane(state, events) } }
        assertEquals(0, onAllNodesWithTag(MEASUREMENT_HELP_TAG).fetchSemanticsNodes().size)
        onNodeWithTag(MEASUREMENT_HELP_BUTTON_TAG).assertHeightIsAtLeast(64.dp).assert(hasStateDescription("Closed")).performClick()
        assertEquals(listOf("help true"), events.calls)
        state = state.copy(helpOpen = true)
        waitForIdle()
        onNodeWithTag(MEASUREMENT_HELP_TAG).assertIsDisplayed()
        onNodeWithText(
            "Locate three points on a bore or a boss, well apart. The panel shows the center and the diameter. " +
                "With a probe, the app compensates the tip radius from the approach directions.",
        ).assertIsDisplayed()
        assertEquals(0, onAllNodes(isDialog()).fetchSemanticsNodes().size)
        onNodeWithTag(MEASUREMENT_HELP_BUTTON_TAG).assert(hasStateDescription("Open")).performClick()
        assertEquals(listOf("help true", "help false"), events.calls)
    }

    @Test
    fun aPartialSetShowsTheStepAndThePendingResults() = runComposeUiTest {
        val events = RecordingMeasurementEvents()
        setContent { MeasurementSamples.Themed(dark = false) { MeasurementPane(MeasurementSamples.partial, events) } }
        onNodeWithText("Locate point #1 on the second line").assertExists()
        onNodeWithTag(resultTag(ResultKey.IncludedAngle)).assertTextEquals("—")
        onNodeWithTag(addTag(ResultKey.Corner)).assertIsNotEnabled()
        onNodeWithTag(slotTag(2)).performClick()
        assertEquals(listOf("slot 2"), events.calls)
    }

    @Test
    fun theControllerRunsTheFlowOfThePane() = runComposeUiTest {
        val scope = TestScope()
        val harness = GraphicsHarness(scope)
        scope.launch {
            harness.start(MachineProfile.create(MachineType.VerticalMill, "Mill").copy(metricDefault = true))
            harness.updateProfile { profile -> profile.copy(machineMode = MachineMode.Measurement) }
            harness.moveTo(0L, x = 3000L, y = 4000L, z = 0L)
        }
        scope.testScheduler.advanceUntilIdle()
        val controller = MeasurementController(harness.deviceId, harness.readout, harness.deps)
        scope.testScheduler.advanceUntilIdle()
        setContent {
            val state by controller.uiState.collectAsState()
            MeasurementSamples.Themed(dark = false) { MeasurementPane(state, ControllerMeasurementEvents(controller)) }
        }
        assertEquals(5, MeasurementVerb.entries.count { verb -> onAllNodesWithTag(verbTag(verb)).fetchSemanticsNodes().isNotEmpty() })
        onNodeWithTag(verbTag(MeasurementVerb.Point)).performClick()
        scope.testScheduler.advanceUntilIdle()
        waitForIdle()
        assertEquals(SlotState.Armed, controller.uiState.value.slots.single().state)
        onNodeWithTag(slotTag(0)).performClick()
        scope.testScheduler.advanceUntilIdle()
        waitForIdle()
        onNodeWithTag(resultTag(ResultKey.Position)).assertTextEquals("3.000, 4.000 mm")
        controller.close()
        harness.close()
    }

    @Test
    fun theInstrumentSheetSelectsAnInstrument() = runComposeUiTest {
        val selected = mutableListOf<String?>()
        var cleared = false
        var created = 0
        setContent {
            MeasurementSamples.Themed(dark = false, landscape = false) {
                InstrumentSheet(
                    state = MeasurementSamples.instrument,
                    onSelect = { id -> selected += id },
                    onNew = { created++ },
                    onClear = { cleared = true },
                    onDismiss = {},
                )
            }
        }
        onNodeWithText("12.700").assertExists()
        onNodeWithText("Distance from instrument to spindle center.").assertExists()
        onNodeWithTag(INSTRUMENT_CLEAR_TAG).performClick()
        assertTrue(cleared)
        // The probe selector of the adapter area.
        onNodeWithTag(probeSelectorTag(ProbeFunction.MeasurementInstrument)).performClick()
        onNodeWithTag(probeOptionTag(ProbeFunction.MeasurementInstrument, "scope")).performClick()
        assertEquals(listOf<String?>("scope"), selected)
        onNodeWithTag(probeSelectorTag(ProbeFunction.MeasurementInstrument)).performClick()
        onNodeWithTag(probeOptionTag(ProbeFunction.MeasurementInstrument, null)).performClick()
        assertEquals(1, created)
    }

    @Test
    fun theLogScreenAsksBeforeItClears() = runComposeUiTest {
        var asked = false
        var confirmed = false
        var csv = 0
        setContent {
            MeasurementSamples.Themed(dark = false) {
                MeasurementLogScreen(
                    state = MeasurementSamples.log.copy(clearAsked = asked),
                    onBack = {},
                    onExportCsv = { csv++ },
                    onExportSheet = {},
                    onClearAsk = { show -> asked = show },
                    onClearConfirm = { confirmed = true },
                )
            }
        }
        onNodeWithText("#1").assertExists()
        onNodeWithText("Check").assertExists()
        onNodeWithTag(LOG_EXPORT_CSV_TAG).performClick()
        assertEquals(1, csv)
        onNodeWithTag(LOG_CLEAR_TAG).performClick()
        assertTrue(asked)
    }

    @Test
    fun theClearQuestionConfirms() = runComposeUiTest {
        var confirmed = false
        setContent {
            MeasurementSamples.Themed(dark = false) {
                MeasurementLogScreen(
                    state = MeasurementSamples.log.copy(clearAsked = true),
                    onBack = {},
                    onExportCsv = {},
                    onExportSheet = {},
                    onClearAsk = {},
                    onClearConfirm = { confirmed = true },
                )
            }
        }
        onNodeWithText("Clear the log?").assertExists()
        onNodeWithTag(LOG_CLEAR_CONFIRM_TAG).performClick()
        assertTrue(confirmed)
    }
}
