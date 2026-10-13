package org.superdro.core.ui.adapter.encoder

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.superdro.core.model.BacklashMode
import org.superdro.core.model.EncoderKind
import org.superdro.core.model.EncoderType
import org.superdro.core.ui.adapter.encoder.map.MapEditorEvents
import org.superdro.core.ui.adapter.encoder.map.MapEditorScreen
import org.superdro.core.ui.adapter.encoder.map.MapEditorTab
import org.superdro.core.ui.adapter.encoder.map.MapEditorTags
import org.superdro.core.ui.adapter.encoder.wizard.ErrorMapDiagnosticsScreen
import org.superdro.core.ui.adapter.encoder.wizard.ErrorMapReportScreen
import org.superdro.core.ui.adapter.encoder.wizard.ErrorMapWizardEvents
import org.superdro.core.ui.adapter.encoder.wizard.ErrorMapWizardScreen
import org.superdro.core.ui.adapter.encoder.wizard.ReportTags
import org.superdro.core.ui.adapter.encoder.wizard.WizardTags

/** The Compose UI tests of the screens of the encoder area. */
@OptIn(ExperimentalTestApi::class)
class EncoderScreensUiTest {

    /** The events of the encoder settings: each call adds its name. */
    private class RecordingEncoderEvents : EncoderSettingsEvents {
        val calls = mutableListOf<String>()
        override fun onType(type: EncoderType) { calls += "type $type" }
        override fun onKind(kind: EncoderKind) { calls += "kind $kind" }
        override fun onResolution(text: String) { calls += "resolution $text" }
        override fun onCountsPerScrewRev(text: String) { calls += "screw $text" }
        override fun onReversed(reversed: Boolean) { calls += "reversed $reversed" }
        override fun onFilter(filter: Boolean) { calls += "filter $filter" }
        override fun onHysteresis(text: String) { calls += "hysteresis $text" }
        override fun onBacklashMode(mode: BacklashMode) { calls += "mode $mode" }
        override fun onBacklashConstant(text: String) { calls += "constant $text" }
        override fun onCyclic(cyclic: Boolean) { calls += "cyclic $cyclic" }
        override fun onConfirmDeleteBacklashMap() { calls += "confirm delete" }
        override fun onCancelDeleteBacklashMap() { calls += "cancel delete" }
        override fun onCalibrate() { calls += "calibrate" }
        override fun onEditErrorMap() { calls += "edit error map" }
        override fun onEditBacklashMap() { calls += "edit backlash map" }
    }

    private class RecordingMapEvents : MapEditorEvents {
        val calls = mutableListOf<String>()
        override fun onTab(tab: MapEditorTab) { calls += "tab $tab" }
        override fun onAddRow() { calls += "add" }
        override fun onEditRow(index: Int) { calls += "edit $index" }
        override fun onRowPosition(text: String) { calls += "position $text" }
        override fun onRowValue(text: String) { calls += "value $text" }
        override fun onSaveRow() { calls += "save row" }
        override fun onDeleteRow() { calls += "delete row" }
        override fun onCancelRow() { calls += "cancel row" }
        override fun onImport() { calls += "import" }
        override fun onPaste() { calls += "paste" }
        override fun onExport() { calls += "export" }
        override fun onClear() { calls += "clear" }
        override fun onSave() { calls += "save" }
        override fun onConfirmReplace() { calls += "replace" }
        override fun onCancelReplace() { calls += "cancel replace" }
        override fun onConfirmClear() { calls += "confirm clear" }
        override fun onCancelClear() { calls += "cancel clear" }
        override fun onWizard() { calls += "wizard" }
        override fun onConfirmWizard() { calls += "confirm wizard" }
        override fun onCancelWizard() { calls += "cancel wizard" }
    }

    private class RecordingWizardEvents : ErrorMapWizardEvents {
        val calls = mutableListOf<String>()
        override fun onReference(reference: Char) { calls += "reference $reference" }
        override fun onStartPass() { calls += "start pass" }
        override fun onStartVerification() { calls += "start verification" }
        override fun onStop() { calls += "stop" }
        override fun onClearPass(index: Int) { calls += "clear $index" }
        override fun onClearVerification() { calls += "clear verification" }
        override fun onReview() { calls += "review" }
        override fun onDiscard() { calls += "discard" }
        override fun onConfirmDiscard() { calls += "confirm discard" }
        override fun onCancelDiscard() { calls += "cancel discard" }
        override fun onExit() { calls += "exit" }
        override fun onRecover() { calls += "recover" }
        override fun onDropRecovery() { calls += "drop" }
    }

    @Test
    fun theEncoderSettingsSendEachChange() = runComposeUiTest {
        val events = RecordingEncoderEvents()
        setContent { EncoderSamples.Themed(dark = true) { EncoderSettingsScreen(EncoderSamples.settings(), events, onBack = {}) } }
        onNodeWithText("Encoder settings: Input X").assertExists()
        onNodeWithTag(EncoderTags.RESOLUTION).performTextReplacement("5333.865")
        onNodeWithTag(EncoderTags.REVERSED).performClick()
        onNodeWithText("Leadscrew encoder").performClick()
        onNodeWithTag(EncoderTags.CALIBRATE).performScrollTo().performClick()
        onNodeWithTag(EncoderTags.EDIT_ERROR_MAP).performScrollTo().performClick()
        // The state of the test does not change, so the field can give its old text again when it loses the focus.
        assertEquals(
            listOf("resolution 5333.865", "reversed false", "type LeadscrewEncoder", "calibrate", "edit error map"),
            events.calls.filterNot { it == "resolution 5080" },
        )
        onNodeWithText("Calibrated").assertExists()
        onNodeWithText("Machine: Vertical mill, axis X").assertExists()
        onNodeWithText("10 intervals (0 to 100000)").assertExists()
        onNodeWithTag(EncoderTags.CYCLIC).assertDoesNotExist()
    }

    @Test
    fun aLeadscrewEncoderShowsTheBacklashMapAndTheErrors() = runComposeUiTest {
        val events = RecordingEncoderEvents()
        setContent { EncoderSamples.Themed(dark = false) { EncoderSettingsScreen(EncoderSamples.leadscrewSettings(), events, onBack = {}) } }
        onNodeWithText("The resolution must be more than 0.").assertExists()
        onNodeWithText("The corrected position goes back between positions 10000 and 20000.").assertExists()
        onNodeWithTag(EncoderTags.HYSTERESIS).assertDoesNotExist()
        onNodeWithTag(EncoderTags.SCREW_REV).assertExists()
        onNodeWithTag(EncoderTags.EDIT_BACKLASH_MAP).performScrollTo().performClick()
        assertEquals(listOf("edit backlash map"), events.calls)
    }

    @Test
    fun anAngularInputShowsTheCyclicSwitch() = runComposeUiTest {
        val events = RecordingEncoderEvents()
        setContent { EncoderSamples.Themed(dark = true) { EncoderSettingsScreen(EncoderSamples.angularSettings(), events, onBack = {}) } }
        onNodeWithText("counts/rev").assertExists()
        onNodeWithTag(EncoderTags.CYCLIC).performScrollTo().performClick()
        assertEquals(listOf("cyclic false"), events.calls)
    }

    @Test
    fun theQuestionOfTheBacklashMapHasDeleteAndCancel() = runComposeUiTest {
        val events = RecordingEncoderEvents()
        setContent {
            EncoderSamples.Themed(dark = true) {
                EncoderSettingsScreen(EncoderSamples.leadscrewSettings().copy(confirmDeleteBacklashMap = true), events, onBack = {})
            }
        }
        onNodeWithText("Delete the backlash map?").assertExists()
        onNodeWithText("Delete").performClick()
        assertEquals(listOf("confirm delete"), events.calls)
    }

    @Test
    fun theMapEditorHasItsActionsAndNoCloudButton() = runComposeUiTest {
        val events = RecordingMapEvents()
        setContent { EncoderSamples.Themed(dark = true) { MapEditorScreen(EncoderSamples.mapEditor(), events, onBack = {}) } }
        listOf("Error map wizard", "Import a CSV file", "Export a CSV file", "Paste", "Clear").forEach { label ->
            onNodeWithContentDescription(label).performClick()
        }
        assertEquals(0, onAllNodesWithContentDescription("cloud", substring = true, ignoreCase = true).fetchSemanticsNodes().size)
        onNodeWithTag(MapEditorTags.row(2)).performClick()
        onNodeWithTag(MapEditorTags.SAVE).performClick()
        onNodeWithText("Curve").performClick()
        assertEquals(listOf("wizard", "import", "export", "paste", "clear", "edit 2", "save", "tab Curve"), events.calls)
    }

    @Test
    fun aReadOnlyMapHasNoAddButtonAndShowsItsSource() = runComposeUiTest {
        val events = RecordingMapEvents()
        setContent { EncoderSamples.Themed(dark = false) { MapEditorScreen(EncoderSamples.mapEditor(MapEditorTab.Summary, readOnly = true), events, onBack = {}) } }
        onNodeWithTag(MapEditorTags.ADD).assertIsNotEnabled()
        onNodeWithTag(MapEditorTags.SAVE).assertIsNotEnabled()
        onNodeWithText("Made by SuperDRO Error Map Wizard").assertExists()
        onNodeWithText("From 3 passes").assertExists()
        onNodeWithText("±11.5 → ±2 counts").assertExists()
    }

    @Test
    fun theCurveTabDrawsTheMap() = runComposeUiTest {
        setContent { EncoderSamples.Themed(dark = true) { MapEditorScreen(EncoderSamples.mapEditor(MapEditorTab.Curve), RecordingMapEvents(), onBack = {}) } }
        onNodeWithContentDescription("The curve of the map with 21 points").assertExists()
    }

    @Test
    fun theWizardShowsThePassesAndStopsTheRecording() = runComposeUiTest {
        val events = RecordingWizardEvents()
        setContent { EncoderSamples.Themed(dark = true) { ErrorMapWizardScreen(EncoderSamples.wizard(), events, onBack = {}) } }
        onNodeWithText("Pass 2 records: 1240 samples").assertExists()
        onNodeWithText("1903 samples · 0 – 95000 – 0").assertExists()
        onNodeWithTag(WizardTags.STOP).performClick()
        onNodeWithTag(WizardTags.REVIEW).performScrollTo().performClick()
        onNodeWithTag(WizardTags.EXIT).performScrollTo().performClick()
        assertEquals(listOf("stop", "review", "exit"), events.calls)
    }

    @Test
    fun startNeedsAReferenceInput() = runComposeUiTest {
        val events = RecordingWizardEvents()
        val state = EncoderSamples.wizard().copy(reference = null, recording = null, canStartPass = false, references = listOf('w'))
        setContent { EncoderSamples.Themed(dark = false) { ErrorMapWizardScreen(state, events, onBack = {}) } }
        onNodeWithTag(WizardTags.START_PASS).assertIsNotEnabled()
        onNodeWithTag(WizardTags.reference('w')).performClick()
        assertEquals(listOf("reference w"), events.calls)
    }

    @Test
    fun saveMapIsActiveOnlyWhenTheReportPermitsIt() = runComposeUiTest {
        var saves = 0
        var diagnostics = 0
        setContent {
            EncoderSamples.Themed(dark = true) {
                ErrorMapReportScreen(EncoderSamples.reportState(), onSave = { saves++ }, onDiagnostics = { diagnostics++ }, onBack = {})
            }
        }
        onNodeWithTag(ReportTags.CHART).assertExists()
        onNodeWithText("Raw error").assertExists()
        onNodeWithTag(ReportTags.SAVE).performScrollTo().assertIsEnabled().performClick()
        onNodeWithTag(ReportTags.DIAGNOSTICS).performScrollTo().performClick()
        assertEquals(1, saves)
        assertEquals(1, diagnostics)
    }

    @Test
    fun aReportWithOnePassCannotBeSaved() = runComposeUiTest {
        val one = org.superdro.core.ui.adapter.encoder.wizard.WizardTestData.onePassSession("a")
        val build = org.superdro.core.functions.errormap.ErrorMapBuilder.build(one.passes.map { it.toPass() })
        val report = (build as org.superdro.core.functions.errormap.ErrorMapBuild.Built).report
        setContent {
            EncoderSamples.Themed(dark = false) {
                ErrorMapReportScreen(EncoderSamples.reportState().copy(report = report), onSave = {}, onDiagnostics = {}, onBack = {})
            }
        }
        onNodeWithTag(ReportTags.SAVE).performScrollTo().assertIsNotEnabled()
        onNodeWithText("Expected error (from the same data)").assertExists()
    }

    @Test
    fun theDiagnosticsCopyTheTable() = runComposeUiTest {
        var copied = 0
        setContent { EncoderSamples.Themed(dark = true) { ErrorMapDiagnosticsScreen(EncoderSamples.diagnosticsState(), onCopied = { copied++ }, onBack = {}) } }
        onNodeWithText("Bridgeport adapter").assertExists()
        onNodeWithTag(ReportTags.COPY).performClick()
        assertEquals(1, copied)
    }
}
