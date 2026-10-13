package org.superdro.core.ui.adapter.probe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.superdro.core.ui.adapter.common.IMPORT_REPORT_TAG
import org.superdro.core.functions.tipcal.TipCalibrationError
import org.superdro.core.functions.tipcal.TipCalibrationReadiness
import org.superdro.core.model.ProbeType
import org.superdro.core.model.ThemeMode
import org.superdro.core.ui.adaptive.LocalWindowLayout
import org.superdro.core.ui.adaptive.WindowLayout
import org.superdro.core.ui.theme.AppTheme

/** The Compose UI tests of the probe screens. */
@OptIn(ExperimentalTestApi::class)
class ProbeScreensUiTest {

    @Composable
    private fun Themed(content: @Composable () -> Unit) {
        AppTheme(mode = ThemeMode.Dark, systemInDarkTheme = true) {
            CompositionLocalProvider(LocalWindowLayout provides WindowLayout.of(1280.dp, 800.dp)) { content() }
        }
    }

    @Test
    fun theLibraryOpensARowAndAsksBeforeADelete() = runComposeUiTest {
        val events = RecordingProbeEvents()
        setContent { Themed { ProbeLibraryScreen(ProbeSamples.library, emptyList(), events) } }
        onAllNodesWithText("Standard tip, read only", substring = true).assertCountEquals(2)
        onNodeWithTag(probeRowTag("own-1")).performClick()
        onNodeWithTag(probeDeleteTag("own-2")).assertHeightIsAtLeast(48.dp).performClick()
        onNodeWithTag(PROBE_NEW_TAG).assertHeightIsAtLeast(64.dp).performClick()
        onNodeWithContentDescription("Import probe profiles").performClick()
        assertEquals(listOf("open own-1", "askDelete own-2", "new", "import"), events.calls)
    }

    @Test
    fun theDeleteQuestionOfAStandardTipTellsThatItGoesForAllProfiles() = runComposeUiTest {
        val events = RecordingProbeEvents()
        val state = ProbeSamples.library.copy(confirmDelete = ProbeSamples.rows[2])
        setContent { Themed { ProbeLibraryScreen(state, emptyList(), events) } }
        onNodeWithText("Delete the standard tip?").assertExists()
        onNodeWithText("goes from the library of all machine profiles", substring = true).assertExists()
        onNodeWithTag(PROBE_CONFIRM_DELETE_TAG).performClick()
        assertEquals(listOf("confirmDelete"), events.calls)
    }

    @Test
    fun theStandardTipNoticeShowsBelowItsRowAndNotInADialog() = runComposeUiTest {
        // The dialog rule: the notice is an explanation. It shows in the list, below the tapped row.
        val events = RecordingProbeEvents()
        var state by mutableStateOf(ProbeSamples.library)
        setContent { Themed { ProbeLibraryScreen(state, emptyList(), events) } }
        assertEquals(0, onAllNodesWithTag(PROBE_STANDARD_TIP_NOTICE_TAG).fetchSemanticsNodes().size)
        onNodeWithTag(probeRowTag("tip-1")).assert(hasStateDescription("Closed")).performClick()
        assertEquals(listOf("open tip-1"), events.calls)
        state = state.copy(standardTipNotice = true, standardTipNoticeId = "tip-1")
        waitForIdle()
        onNodeWithTag(PROBE_STANDARD_TIP_NOTICE_TAG).performScrollTo().assertIsDisplayed()
        onNodeWithText("You cannot change a standard tip. It is for all machine profiles. You can delete it, or make a new probe profile with your values.")
            .assertIsDisplayed()
        onNodeWithTag(probeRowTag("tip-1")).assert(hasStateDescription("Open"))
        val row = onNodeWithTag(probeRowTag("tip-1")).fetchSemanticsNode().boundsInRoot
        val notice = onNodeWithTag(PROBE_STANDARD_TIP_NOTICE_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue(notice.top >= row.bottom, "The notice shows below the row: row $row, notice $notice.")
        assertEquals(0, onAllNodes(isDialog()).fetchSemanticsNodes().size)
    }

    @Test
    fun theReportOfAnImportShowsAtTheTopOfTheListAndNotInADialog() = runComposeUiTest {
        // The dialog rule: a report is general information. It is the first item of the list.
        val events = RecordingProbeEvents()
        setContent { Themed { ProbeLibraryScreen(ProbeSamples.library.copy(report = ProbeSamples.report), emptyList(), events) } }
        onNodeWithTag(IMPORT_REPORT_TAG).assertIsDisplayed()
        onNodeWithText("Import report").assertIsDisplayed()
        onNodeWithText("Imported: 2. Not imported: 0. Warnings: 1.").assertIsDisplayed()
        onNodeWithText("Other warnings").assertIsDisplayed()
        onNodeWithText("The profiles come from “Bridgeport”. You import them into “Mill”.").assertIsDisplayed()
        assertEquals(0, onAllNodes(isDialog()).fetchSemanticsNodes().size)
        assertEquals(emptyList(), events.calls)
    }

    @Test
    fun theCalibrationRecordShowsInTheScreenAndNotInADialog() = runComposeUiTest {
        // The dialog rule: the record is a details text. Its button opens and closes it in the screen.
        val events = RecordingProbeEvents()
        var state by mutableStateOf(ProbeSamples.calibrated)
        setContent { Themed { ProbeProfileScreen(state, events) } }
        assertEquals(0, onAllNodesWithTag(PROBE_RECORD_TAG).fetchSemanticsNodes().size)
        onNodeWithTag(PROBE_RECORD_BUTTON_TAG).performScrollTo().assertHeightIsAtLeast(64.dp).assert(hasStateDescription("Closed")).performClick()
        assertEquals(listOf("record"), events.calls)
        state = ProbeSamples.calibratedWithRecord
        waitForIdle()
        onNodeWithTag(PROBE_RECORD_TAG).performScrollTo().assertIsDisplayed()
        onNodeWithText("Tip diameter: 1.987 mm").assertIsDisplayed()
        onNodeWithText("Repeatability: ±0.0011 mm").assertExists()
        onNodeWithText("Ring gauge: 25.000 mm").assertExists()
        onNodeWithText("Date: 2026-10-11 13:58").assertExists()
        onNodeWithText("Across X: 1.987, across Y: 1.988 mm").assertExists()
        onNodeWithText("9 o'clock: 3 touches, spread 0.0004 mm").assertExists()
        onNodeWithText("3 o'clock: 3 touches, spread 0.0006 mm").assertExists()
        assertEquals(0, onAllNodes(isDialog()).fetchSemanticsNodes().size)
        onNodeWithTag(PROBE_RECORD_BUTTON_TAG).performScrollTo().assert(hasStateDescription("Open")).performClick()
        assertEquals(listOf("record", "dismissRecord"), events.calls)
    }

    @Test
    fun theSelectorListsTheProfilesAndNewProfile() = runComposeUiTest {
        val calls = mutableListOf<String>()
        val state = ProbeSamples.selectors[1]
        setContent {
            Themed {
                ProbeSelector(state, onSelect = { id -> calls += "select $id" }, onNewProfile = { calls += "new" }, onEdit = { id -> calls += "edit $id" })
            }
        }
        onNodeWithText("No probe selected").assertExists()
        onNodeWithTag(probeSelectorTag(ProbeFunction.ZReference)).performClick()
        onNodeWithTag(probeOptionTag(ProbeFunction.ZReference, "own-2")).performClick()
        onNodeWithTag(probeSelectorTag(ProbeFunction.ZReference)).performClick()
        onNodeWithTag(probeOptionTag(ProbeFunction.ZReference, null)).performClick()
        assertEquals(listOf("select own-2", "new"), calls)
    }

    @Test
    fun theProfileScreenSavesWithTheAutomaticName() = runComposeUiTest {
        val events = RecordingProbeEvents()
        setContent { Themed { ProbeProfileScreen(ProbeSamples.newProfile, events) } }
        onNodeWithText("Empty: the name is “Touch probe 2.000 mm”.").assertExists()
        onNodeWithTag("probe-type-${ProbeType.ToolHeightSetter.name}").performClick()
        onNodeWithTag(PROBE_CALIBRATE_TAG).performScrollTo().performClick()
        onNodeWithTag(PROBE_SAVE_TAG).performScrollTo().performClick()
        assertEquals(listOf("type ToolHeightSetter", "calibrate Touch probe 2.000 mm", "save Touch probe 2.000 mm"), events.calls)
    }

    @Test
    fun aToolHeightSetterHasASetterHeightAndNoDiameter() = runComposeUiTest {
        val events = RecordingProbeEvents()
        setContent { Themed { ProbeProfileScreen(ProbeSamples.setter, events) } }
        onNodeWithText("Setter height").assertExists()
        onNodeWithText("Tip diameter").assertDoesNotExist()
        onNodeWithText("X offset").assertDoesNotExist()
        onNodeWithTag(PROBE_CALIBRATE_TAG).assertDoesNotExist()
        onNodeWithTag("probe-sync-Z").assertIsNotEnabled()
    }

    @Test
    fun twoTouchesDoNotEnableContinue() = runComposeUiTest {
        val events = RecordingProbeEvents()
        setContent { Themed { TipCalibrationScreen(ProbeSamples.tipTouches, events) } }
        onNodeWithText("2 touches recorded. The minimum is 3. More touches give a better result.").assertExists()
        onNodeWithTag(TIP_CONTINUE_TAG).assertIsNotEnabled()
        onNodeWithTag("tip-refused").assertExists()
    }

    @Test
    fun threeTouchesEnableContinue() = runComposeUiTest {
        val events = RecordingProbeEvents()
        setContent { Themed { TipCalibrationScreen(ProbeSamples.tipTouches.copy(touches = 3, touchRefused = false), events) } }
        onNodeWithTag(TIP_CONTINUE_TAG).assertIsEnabled().performClick()
        assertEquals(listOf("continueTouches"), events.calls)
    }

    @Test
    fun theErrorDialogOffersRetryAndTheResultOffersApply() = runComposeUiTest {
        val events = RecordingProbeEvents()
        val state = ProbeSamples.tipResult.copy(error = TipErrorUi(TipCalibrationError.PositionScattered, spreadUm = 40.0, limitUm = 20.0))
        setContent { Themed { TipCalibrationScreen(state, events) } }
        onNodeWithText("The spread of the touches is 0.0400. The limit is 0.0200 mm. Touch this position again.").assertExists()
        onNodeWithTag(TIP_RETRY_TAG).performClick()
        assertEquals(listOf("retry"), events.calls)
    }

    @Test
    fun theReadinessDialogTellsWhatIsMissing() = runComposeUiTest {
        val events = RecordingProbeEvents()
        setContent { Themed { TipCalibrationScreen(ProbeSamples.tipGauge.copy(readiness = TipCalibrationReadiness.NotConnected), events) } }
        onNodeWithText("Not connected").assertExists()
        onNodeWithText("Close").performClick()
        assertEquals(listOf("cancel"), events.calls)
    }

    @Test
    fun theResultAppliesTheTipDiameter() = runComposeUiTest {
        val events = RecordingProbeEvents()
        setContent { Themed { TipCalibrationScreen(ProbeSamples.tipResult, events) } }
        onNodeWithTag("tip-result-diameter").assertExists()
        onNodeWithText("1.987 mm").assertExists()
        onNodeWithTag(TIP_APPLY_TAG).performClick()
        onNodeWithText("Discard").performClick()
        assertEquals(listOf("apply", "discard"), events.calls)
    }
}
