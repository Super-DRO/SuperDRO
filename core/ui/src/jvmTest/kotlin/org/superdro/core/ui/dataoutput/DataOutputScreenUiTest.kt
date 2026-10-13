package org.superdro.core.ui.dataoutput

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.superdro.core.model.DataOutputConfig
import org.superdro.core.model.DataOutputFieldId
import org.superdro.core.model.DataOutputSecurity
import org.superdro.core.model.MachineProfile
import org.superdro.core.ui.theme.AppTheme

/** The main flows of the data output screen with the real presenter. */
@OptIn(ExperimentalTestApi::class)
class DataOutputScreenUiTest {

    private class Graph(scope: TestScope) {
        val harness = DataOutputHarness(scope)
        lateinit var presenter: DataOutputPresenter
        lateinit var profile: MachineProfile

        fun start(prepare: suspend DataOutputHarness.() -> MachineProfile) {
            var made: MachineProfile? = null
            harness.scope.launch { made = harness.prepare() }
            harness.scope.runCurrent()
            profile = checkNotNull(made)
            harness.session(profile)
            harness.start()
            presenter = harness.presenter()
            presenter.selectProfile(profile.id)
            harness.settle()
        }

        fun close() {
            presenter.close()
            harness.close()
        }
    }

    private fun ComposeUiTest.show(graph: Graph) {
        setContent {
            AppTheme(systemInDarkTheme = false) {
                val state by graph.presenter.uiState.collectAsState()
                DataOutputScreen(state = state, events = graph.presenter, onBack = {}, onCopyKey = {})
            }
        }
        settle(graph)
    }

    private fun ComposeUiTest.settle(graph: Graph) {
        repeat(3) {
            waitForIdle()
            graph.harness.settle()
        }
        waitForIdle()
    }

    @Test
    fun thePlainTextModeShowsTheWarningBeforeItApplies() = runComposeUiTest {
        val graph = Graph(TestScope())
        graph.start { profile() }
        show(graph)
        onNode(hasScrollAction()).performScrollToNode(hasTestTag(dataOutputSecurityTag(DataOutputSecurity.PlainText)))
        onNodeWithTag(dataOutputSecurityTag(DataOutputSecurity.PlainText)).performClick()
        settle(graph)
        onNodeWithTag(DATA_OUTPUT_WARNING_TAG).assertIsDisplayed()
        onNodeWithText("Use only on trusted networks").assertIsDisplayed()
        assertEquals(DataOutputSecurity.Psk, graph.presenter.uiState.value.draft.security)
        onNodeWithTag(DATA_OUTPUT_WARNING_CONFIRM_TAG).performClick()
        settle(graph)
        assertEquals(DataOutputSecurity.PlainText, graph.presenter.uiState.value.draft.security)
        onNodeWithText(
            "The plain text mode has no encryption and no access control. Each device that can reach the tablet can read the machine positions.",
        ).assertIsDisplayed()
        graph.close()
    }

    @Test
    fun theDiagnosticsShowInTheStatusSectionAndNotInADialog() = runComposeUiTest {
        // The dialog rule: the diagnostics are general information. The button of the status section
        // opens and closes them in the screen.
        val graph = Graph(TestScope())
        graph.start {
            profile { stored -> stored.copy(dataOutput = DataOutputConfig(enabled = true, security = DataOutputSecurity.PlainText, plainTextConfirmed = true)) }
        }
        graph.presenter.onStartStop()
        graph.harness.settle()
        show(graph)
        assertEquals(0, onAllNodesWithTag(DATA_OUTPUT_DIAGNOSTICS_TAG).fetchSemanticsNodes().size)
        onNodeWithTag(DATA_OUTPUT_DETAILS_TAG).assertIsDisplayed().assert(hasStateDescription("Closed"))
        onNodeWithText("Show the diagnostics").performClick()
        settle(graph)
        onNodeWithTag(DATA_OUTPUT_DIAGNOSTICS_TAG).assertIsDisplayed()
        onNodeWithText("Listening on 192.168.1.20:5800 (0 clients)").assertIsDisplayed()
        onNodeWithText("No record was delivered since the start.").assertIsDisplayed()
        assertEquals(0, onAllNodes(isDialog()).fetchSemanticsNodes().size)
        onNodeWithTag(DATA_OUTPUT_DETAILS_TAG).assert(hasStateDescription("Open"))
        onNodeWithText("Hide the diagnostics").performClick()
        settle(graph)
        assertEquals(0, onAllNodesWithTag(DATA_OUTPUT_DIAGNOSTICS_TAG).fetchSemanticsNodes().size)
        assertEquals(null, graph.presenter.uiState.value.dialog)
        graph.close()
    }

    @Test
    fun aRefusedLabelCharacterShowsItsMessage() = runComposeUiTest {
        val graph = Graph(TestScope())
        graph.start { profile() }
        show(graph)
        val tag = dataOutputLabelTag(DataOutputFieldId.XAbsolute)
        onNode(hasScrollAction()).performScrollToNode(hasTestTag(tag))
        onNodeWithTag(tag).performTextReplacement("x=1")
        settle(graph)
        onNodeWithText("A label cannot contain the character \"=\".").assertIsDisplayed()
        assertEquals("x_absolute", graph.presenter.uiState.value.draft.fields.first().label)
        graph.close()
    }

    @Test
    fun thePortOfADifferentOutputBlocksTheSaveAndTheSwitch() = runComposeUiTest {
        val graph = Graph(TestScope())
        graph.start {
            profile(name = "Lathe") { stored -> stored.copy(dataOutput = DataOutputConfig(enabled = true)) }
            profile(name = "Mill")
        }
        show(graph)
        onNode(hasScrollAction()).performScrollToNode(hasTestTag(DATA_OUTPUT_PORT_TAG))
        onNodeWithText("The output of Lathe uses port 5800. Select a different port.").assertIsDisplayed()
        onNode(hasScrollAction()).performScrollToNode(hasTestTag(DATA_OUTPUT_SWITCH_TAG))
        onNodeWithTag(DATA_OUTPUT_SWITCH_TAG).performClick()
        settle(graph)
        assertFalse(graph.presenter.uiState.value.stored.enabled)
        onNodeWithTag(DATA_OUTPUT_SAVE_TAG).assertIsNotEnabled()
        onNode(hasScrollAction()).performScrollToNode(hasTestTag(DATA_OUTPUT_PORT_TAG))
        onNodeWithTag(DATA_OUTPUT_PORT_TAG).performTextReplacement("5801")
        settle(graph)
        onNode(hasScrollAction()).performScrollToNode(hasTestTag(DATA_OUTPUT_SWITCH_TAG))
        onNodeWithTag(DATA_OUTPUT_SWITCH_TAG).performClick()
        settle(graph)
        assertTrue(graph.presenter.uiState.value.stored.enabled)
        assertEquals(5801, graph.presenter.uiState.value.stored.port)
        assertTrue(graph.harness.sockets.sockets.isEmpty(), "On demand: no server before the start.")
        graph.close()
    }
}

/** The status chip of the data output. */
@OptIn(ExperimentalTestApi::class)
class DataOutputChipUiTest {

    @Test
    fun theChipHasADescriptionATapAndALongPress() = runComposeUiTest {
        var taps = 0
        var longPresses = 0
        var state by androidx.compose.runtime.mutableStateOf(DataOutputChipUiState(visible = false))
        setContent {
            AppTheme(systemInDarkTheme = false) {
                DataOutputStatusChip(state, onTap = { taps += 1 }, onLongPress = { longPresses += 1 })
            }
        }
        onAllNodes(hasTestTag(DATA_OUTPUT_CHIP_TAG)).assertCountEquals(0)
        state = DataOutputChipUiState(visible = true, status = org.superdro.core.dataoutput.DataOutputStatus.Sending)
        waitForIdle()
        onNodeWithText("Output: Sending").assertIsDisplayed()
        val chip = onNode(androidx.compose.ui.test.hasContentDescription("Data output: Sending. Tap to start or stop. Touch and hold for the settings."))
        chip.assertHasClickAction()
        chip.performClick()
        chip.performTouchInput { longClick() }
        waitForIdle()
        assertEquals(1, taps)
        assertEquals(1, longPresses)
        val touch = chip.fetchSemanticsNode().touchBoundsInRoot
        assertTrue(touch.height >= 48f * density.density - 0.5f, "The touch target of the chip is 48 dp or more: ${touch.height} px.")
    }
}
