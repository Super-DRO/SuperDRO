package org.superdro.validation.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.superdro.core.connection.DeviceId
import org.superdro.core.model.DataOutputActivation
import org.superdro.core.model.DataOutputConfig
import org.superdro.core.model.DataOutputSecurity
import org.superdro.core.ui.App
import org.superdro.core.ui.ProvideDI
import org.superdro.core.ui.dataoutput.DATA_OUTPUT_CHIP_TAG
import org.superdro.core.ui.dataoutput.DATA_OUTPUT_DETAILS_TAG
import org.superdro.core.ui.dataoutput.DATA_OUTPUT_SCREEN_TAG
import org.superdro.core.ui.navigation.DataOutputDetailsRoute
import org.superdro.core.ui.navigation.EntryKind
import org.superdro.core.ui.navigation.RouteArea
import org.superdro.core.ui.navigation.appEntryRegistry
import org.superdro.validation.Traceability

/**
 * A dialog is only for the attention of the
 * user. General information (help, details, reports, diagnostics) shows in the screen.
 */
@OptIn(ExperimentalTestApi::class)
class InfoInScreenValidationTest {

    @Test
    fun the_status_chip_opens_the_diagnostics_in_the_data_output_screen() = AppGraph().use { graph ->
        graph.connect("mill", "Mill") { profile ->
            profile.copy(
                dataOutput = DataOutputConfig(
                    enabled = true,
                    activation = DataOutputActivation.AlwaysOn,
                    security = DataOutputSecurity.PlainText,
                    plainTextConfirmed = true,
                ),
            )
        }
        runWindowTest(1280, 800) {
            setContent { ProvideDI(graph.di) { App() } }
            waitUntil("chip", 10_000L) { onAllNodesWithTag(DATA_OUTPUT_CHIP_TAG).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag(DATA_OUTPUT_CHIP_TAG).performClick()
            waitUntil("data output screen", 10_000L) { onAllNodesWithTag(DATA_OUTPUT_SCREEN_TAG).fetchSemanticsNodes().isNotEmpty() }
            mainClock.advanceTimeBy(1_500L)
            waitForIdle()
            assertEquals(DataOutputDetailsRoute, graph.holder.globalEntries.last().route)
            assertEquals(0, onAllNodes(isDialog()).fetchSemanticsNodes().size, "The diagnostics do not show in a dialog.")
            assertTrue(onAllNodesWithTag(DATA_OUTPUT_DETAILS_TAG).fetchSemanticsNodes().isNotEmpty(), "The status section shows the button of the diagnostics.")
            assertTrue(graph.holder.tab(DeviceId("mill")).routes.size == 1, "The tab stays at the readout.")
        }
    }

    @Test
    fun the_diagnostics_route_is_a_screen() {
        val spec = appEntryRegistry().spec(DataOutputDetailsRoute)
        assertEquals(EntryKind.Screen, spec?.kind)
        assertEquals(RouteArea.Settings, spec?.area)
        assertEquals(false, spec?.placeholder)
    }

    @Test
    fun no_dialog_of_core_ui_shows_general_information() {
        // The composables of the old information dialogs are gone. Their texts show in the screen.
        val root = Traceability.rootDir.resolve("core/ui/src/commonMain/kotlin")
        assertTrue(root.isDirectory, root.absolutePath)
        val gone = listOf("HelpDialog", "ImportReportDialog", "DiagnosticsDialog", "RecordDialog")
        val hits = root.walkTopDown().filter { file -> file.isFile && file.extension == "kt" }
            .flatMap { file -> gone.filter { name -> Regex("""\b$name\(""").containsMatchIn(file.readText()) }.map { name -> "${file.name}: $name" } }
            .toList()
        assertEquals(emptyList(), hits)
    }
}
