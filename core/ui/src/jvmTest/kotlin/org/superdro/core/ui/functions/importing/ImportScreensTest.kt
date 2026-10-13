package org.superdro.core.ui.functions.importing

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.superdro.core.store.files.ImportOperation
import org.superdro.core.store.files.MessageKey
import org.superdro.core.store.files.Report
import org.superdro.core.store.files.ReportSection
import org.superdro.core.store.files.RowAction
import org.superdro.core.ui.functions.common.PRIMARY_BUTTON_TAG
import org.superdro.core.ui.functions.renderFunctionScreenshot
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.toUiText
import org.superdro.core.ui.theme.AppTheme

/** The Compose UI tests and the screenshots of the import preview, the report, and the profile file screen. */
@OptIn(ExperimentalTestApi::class)
class ImportScreensTest {

    private val preview = ImportUiState(
        stage = ImportStage.Preview,
        kind = ImportKind.SubDatumCsv,
        fileName = "superdro-sub_datums-Bridgeport-mill-20261011_143005.csv",
        kindText = UiText.Plain("Sub-datum CSV file"),
        provenance = UiText.Plain("SuperDRO file of the profile “Bridgeport”, exported 2026-10-11 14:30:05"),
        toImport = listOf(
            ImportRowUi(2, "Circle - 1", UiText.Plain("X 100.000 • Y 100.000 • Z — mm"), RowAction.Add),
            ImportRowUi(3, "Circle - 2", UiText.Plain("X 143.301 • Y 75.000 • Z — mm"), RowAction.Add),
            ImportRowUi(4, "Guide", UiText.Plain("Guide X = 10.000 mm"), RowAction.Add),
        ),
        skipped = listOf(
            ImportRowUi(5, "", null, RowAction.Skip, listOf(UiText.Plain("Row 5: Name is blank"))),
        ),
        warnings = listOf(MessageKey.UnitsUndeclared.toUiText("mm")),
        operations = listOf(ImportOperation.AddAsNew, ImportOperation.AddNewOnly, ImportOperation.MergeMatching),
        operation = ImportOperation.AddAsNew,
        metric = true,
        usable = true,
    )

    private class RecordingEvents : ImportPreviewEvents {
        val calls = mutableListOf<String>()
        override fun onOperation(operation: ImportOperation) { calls += "op $operation" }
        override fun onUnits(metric: Boolean) { calls += "units $metric" }
        override fun onTarget(newProfile: Boolean) { calls += "target $newProfile" }
        override fun onName(name: String) { calls += "name $name" }
        override fun onConfirm() { calls += "confirm" }
        override fun onCancel() { calls += "cancel" }
    }

    @Test
    fun thePreviewShowsTheThreeListsAndTheOperations() = runComposeUiTest {
        val events = RecordingEvents()
        setContent { AppTheme { ImportPreviewScreen(preview, events) } }
        onNodeWithText("Circle - 2").assertExists()
        onNodeWithTag(ImportPreviewTags.tab(1)).performClick()
        onNodeWithText("Row 5: Name is blank").assertExists()
        onNodeWithTag(ImportPreviewTags.tab(2)).performClick()
        onNodeWithTag(ImportPreviewTags.operation(ImportOperation.MergeMatching)).performClick()
        onNodeWithTag(PRIMARY_BUTTON_TAG).assertIsEnabled().performClick()
        assertEquals(listOf("op MergeMatching", "confirm"), events.calls)
    }

    @Test
    fun aProfileFileNeedsAName() = runComposeUiTest {
        val state = preview.copy(kind = ImportKind.Profile, operations = emptyList(), metric = null, name = "")
        setContent { AppTheme { ImportPreviewScreen(state, RecordingEvents()) } }
        onNodeWithTag(PRIMARY_BUTTON_TAG).assertIsNotEnabled()
    }

    @Test
    fun theImportScreensRenderInLightAndDarkMode() {
        val settings = preview.copy(
            kind = ImportKind.Profile,
            fileName = "Bridgeport.tdset",
            kindText = UiText.Plain("TouchDRO settings file (.tdset)"),
            provenance = UiText.Plain("TouchDRO file of the profile “Bridgeport”, exported 2026-10-11 14:30:05"),
            toImport = listOf(
                ImportRowUi(1, "use_metric_mode", UiText.Plain("false → true"), RowAction.Update),
                ImportRowUi(2, "display_format", UiText.Plain("3 → 3"), RowAction.Unchanged),
            ),
            operations = emptyList(),
            metric = null,
            newProfile = true,
            name = "Bridgeport",
        )
        val report = Report(
            title = "Import report",
            summary = "Imported: 12. Not imported: 2. Warnings: 1.",
            sections = listOf(
                ReportSection("Items that the import did not map", "“show_calculators” is not a setting that SuperDRO can import.\n“c_state_x” is internal state, not a setting. The import ignores it."),
                ReportSection("Where the probe values go", "“probe_debounce_seconds” = 0.2 goes to the probe settings of the adapter profile."),
            ),
        )
        listOf(false, true).forEach { dark ->
            val mode = if (dark) "dark" else "light"
            renderFunctionScreenshot("functions-import-preview-$mode", dark) { ImportPreviewScreen(preview, RecordingEvents()) }
            renderFunctionScreenshot("functions-import-preview-settings-$mode", dark, widthDp = 800, heightDp = 1280) {
                ImportPreviewScreen(settings, RecordingEvents())
            }
            renderFunctionScreenshot("functions-import-report-$mode", dark) { ReportScreen(report, onShare = {}, onBack = {}) }
            renderFunctionScreenshot("functions-import-profile-file-$mode", dark) {
                ProfileFileScreen(ProfileFileUiState(true, "Bridgeport"), onExport = {}, onImport = {}, onBack = {})
            }
        }
    }
}
