package org.superdro.validation.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.kodein.di.direct
import org.kodein.di.instance
import org.superdro.core.connection.DeviceId
import org.superdro.core.model.AxisId
import org.superdro.core.model.MachineType
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.Tool
import org.superdro.core.store.repository.ProbeRepository
import org.superdro.core.store.repository.ToolRepository
import org.superdro.core.store.valueOrNull
import org.superdro.core.ui.App
import org.superdro.core.ui.ProvideDI
import org.superdro.core.ui.navigation.AboutRoute
import org.superdro.core.ui.navigation.AdapterDetailsRoute
import org.superdro.core.ui.navigation.AdaptersRoute
import org.superdro.core.ui.navigation.AddDeviceRoute
import org.superdro.core.ui.navigation.AddGuideRoute
import org.superdro.core.ui.navigation.AlignAxesRoute
import org.superdro.core.ui.navigation.AngleMeasureRoute
import org.superdro.core.ui.navigation.AppSettingsRoute
import org.superdro.core.ui.navigation.ArbitraryHoleCircleRoute
import org.superdro.core.ui.navigation.ArcCutRoute
import org.superdro.core.ui.navigation.AxisDetailsRoute
import org.superdro.core.ui.navigation.CalibrationWizardRoute
import org.superdro.core.ui.navigation.CameraSetupRoute
import org.superdro.core.ui.navigation.CompoundRoute
import org.superdro.core.ui.navigation.DataOutputDetailsRoute
import org.superdro.core.ui.navigation.DataOutputRoute
import org.superdro.core.ui.navigation.DeleteProfileRoute
import org.superdro.core.ui.navigation.DeleteToolRoute
import org.superdro.core.ui.navigation.DeleteWorkspaceRoute
import org.superdro.core.ui.navigation.DimensionMeasureRoute
import org.superdro.core.ui.navigation.DivisionsRoute
import org.superdro.core.ui.navigation.DrawingProblemsRoute
import org.superdro.core.ui.navigation.DrawingSetupRoute
import org.superdro.core.ui.navigation.EdgeFinderRoute
import org.superdro.core.ui.navigation.EncoderSettingsRoute
import org.superdro.core.ui.navigation.EntryKind
import org.superdro.core.ui.navigation.EquidistantArrayRoute
import org.superdro.core.ui.navigation.ErrorMapDiagnosticsRoute
import org.superdro.core.ui.navigation.ErrorMapReportRoute
import org.superdro.core.ui.navigation.ErrorMapWizardRoute
import org.superdro.core.ui.navigation.GlobalRoute
import org.superdro.core.ui.navigation.GraphicalViewRoute
import org.superdro.core.ui.navigation.HeadTiltRoute
import org.superdro.core.ui.navigation.HeightCompensationRoute
import org.superdro.core.ui.navigation.HoleCircleRoute
import org.superdro.core.ui.navigation.HoleGridRoute
import org.superdro.core.ui.navigation.ImportPreviewRoute
import org.superdro.core.ui.navigation.KeyMappingRoute
import org.superdro.core.ui.navigation.KeypadRoute
import org.superdro.core.ui.navigation.LatheToolOffsetRoute
import org.superdro.core.ui.navigation.LogsRoute
import org.superdro.core.ui.navigation.MachineDatumEditRoute
import org.superdro.core.ui.navigation.MachineProfilesRoute
import org.superdro.core.ui.navigation.MachineSettingsRoute
import org.superdro.core.ui.navigation.MapEditorRoute
import org.superdro.core.ui.navigation.MeasurementLogRoute
import org.superdro.core.ui.navigation.NewAdapterPromptRoute
import org.superdro.core.ui.navigation.OfflineAdapterDetailsRoute
import org.superdro.core.ui.navigation.PLACEHOLDER_TAG
import org.superdro.core.ui.navigation.ProbeInputRoute
import org.superdro.core.ui.navigation.ProbeLibraryRoute
import org.superdro.core.ui.navigation.ProbeProfileRoute
import org.superdro.core.ui.navigation.ProfileFileRoute
import org.superdro.core.ui.navigation.RadiusCompensationRoute
import org.superdro.core.ui.navigation.ReadoutRoute
import org.superdro.core.ui.navigation.ReferenceImageSetupRoute
import org.superdro.core.ui.navigation.RememberedMachinesRoute
import org.superdro.core.ui.navigation.ReportRoute
import org.superdro.core.ui.navigation.RotatePartRoute
import org.superdro.core.ui.navigation.ShapeMenuRoute
import org.superdro.core.ui.navigation.SubDatumEditRoute
import org.superdro.core.ui.navigation.TabRoute
import org.superdro.core.ui.navigation.TachPulsesRoute
import org.superdro.core.ui.navigation.TachometerPresetRoute
import org.superdro.core.ui.navigation.TagSubDatumsRoute
import org.superdro.core.ui.navigation.ThreadSetupRoute
import org.superdro.core.ui.navigation.TipCalibrationRoute
import org.superdro.core.ui.navigation.ToolEditRoute
import org.superdro.core.ui.navigation.ToolGeometryRoute
import org.superdro.core.ui.navigation.ToolLibraryRoute
import org.superdro.core.ui.navigation.WorkspaceTransformKind
import org.superdro.core.ui.navigation.WorkspaceTransformRoute
import org.superdro.core.ui.navigation.WorkspacesRoute
import org.superdro.core.ui.navigation.ZReferenceRoute
import org.superdro.core.ui.navigation.appEntryRegistry
import org.superdro.core.ui.readout.READOUT_SCREEN_TAG
import org.superdro.core.ui.readout.keypad.KEYPAD_FIELD_TAG

/**
 * Each SuperDRO screen, sheet, and dialog of the table exists with the kind
 * of the table ("Screen" is an entry of the tab back stack, "Sheet" is a `ModalBottomSheet` in
 * portrait and a dialog in landscape, "Dialog" is an `AlertDialog`), and opens in `App()` with no
 * placeholder. The back gesture closes each sheet and screen.
 */
@OptIn(ExperimentalTestApi::class)
class ScreensValidationTest {

    /** One SuperDRO entry of the screen table, or a global screen. */
    private data class Entry(val route: Any, val kind: EntryKind?, val device: String = "mill", val needsPendingData: Boolean = false)

    /** The IDs of the stored data that some routes name. */
    private data class Ids(val tool: String, val workspace: String, val probe: String, val adapter: String)

    private fun entries(ids: Ids): List<Entry> = listOf(
        // The readout, the settings, the keypads, and the axis details.
        Entry(ReadoutRoute, EntryKind.Screen),
        Entry(MachineSettingsRoute, EntryKind.Screen),
        Entry(AppSettingsRoute, EntryKind.Screen),
        Entry(MachineProfilesRoute, EntryKind.Screen),
        Entry(AddDeviceRoute, EntryKind.Sheet),
        Entry(KeypadRoute(AxisId.X), EntryKind.Sheet),
        Entry(AxisDetailsRoute(AxisId.X), EntryKind.Sheet),
        Entry(KeypadRoute(AxisId.Tachometer), EntryKind.Sheet, device = "lathe"),
        Entry(TachometerPresetRoute, EntryKind.Sheet, device = "lathe"),
        Entry(DivisionsRoute, EntryKind.Sheet),
        Entry(AdapterDetailsRoute, EntryKind.Screen),
        // The function screens with a point preview and the sheets of the functions.
        Entry(HoleCircleRoute, EntryKind.Screen),
        Entry(ArbitraryHoleCircleRoute(), EntryKind.Screen),
        Entry(HoleGridRoute, EntryKind.Screen),
        Entry(EquidistantArrayRoute, EntryKind.Screen),
        Entry(ArcCutRoute, EntryKind.Screen),
        Entry(RadiusCompensationRoute, EntryKind.Sheet),
        Entry(HeightCompensationRoute, EntryKind.Sheet),
        Entry(LatheToolOffsetRoute, EntryKind.Sheet, device = "lathe"),
        Entry(ZReferenceRoute, EntryKind.Sheet),
        Entry(AngleMeasureRoute, EntryKind.Sheet),
        Entry(DimensionMeasureRoute, EntryKind.Sheet),
        // Workspaces, tools, and sub-datums.
        Entry(WorkspacesRoute, EntryKind.Sheet),
        Entry(ToolLibraryRoute, EntryKind.Screen),
        Entry(ToolEditRoute(), EntryKind.Sheet),
        Entry(ShapeMenuRoute, EntryKind.Sheet),
        Entry(SubDatumEditRoute(), EntryKind.Sheet),
        // The import preview shows a picked file; with no file it closes at once.
        Entry(ImportPreviewRoute, EntryKind.Screen, needsPendingData = true),
        Entry(DeleteToolRoute(ids.tool), EntryKind.Dialog),
        Entry(DeleteWorkspaceRoute(ids.workspace), EntryKind.Dialog),
        Entry(DeleteProfileRoute("lathe"), EntryKind.Dialog),
        // The graphical view and the edge finder.
        Entry(GraphicalViewRoute, EntryKind.Screen),
        Entry(AddGuideRoute, EntryKind.Dialog),
        Entry(ReferenceImageSetupRoute, EntryKind.Screen),
        Entry(EdgeFinderRoute, EntryKind.Screen),
        // The adapter screens and the other screens.
        Entry(AdaptersRoute, EntryKind.Screen),
        Entry(OfflineAdapterDetailsRoute(ids.adapter), EntryKind.Screen),
        // The prompt shows for an adapter with no stored profile; with no such adapter it closes at once.
        Entry(NewAdapterPromptRoute, EntryKind.Dialog, needsPendingData = true),
        Entry(EncoderSettingsRoute('x'), EntryKind.Screen),
        Entry(MapEditorRoute('x'), EntryKind.Screen),
        Entry(ErrorMapWizardRoute('x'), EntryKind.Screen),
        Entry(ErrorMapReportRoute('x'), EntryKind.Screen),
        Entry(ErrorMapDiagnosticsRoute('x'), EntryKind.Screen),
        Entry(ProbeInputRoute, EntryKind.Sheet),
        Entry(TachPulsesRoute, EntryKind.Sheet, device = "lathe"),
        Entry(ProbeLibraryRoute, EntryKind.Screen),
        Entry(ProbeProfileRoute(), EntryKind.Screen),
        Entry(TipCalibrationRoute(ids.probe), EntryKind.Screen),
        Entry(DataOutputRoute, EntryKind.Screen),
        // "The dialog rule": the same screen with the diagnostics open. The diagnostics are not a dialog.
        Entry(DataOutputDetailsRoute, EntryKind.Screen),
        Entry(CameraSetupRoute, EntryKind.Screen),
        Entry(ThreadSetupRoute, EntryKind.Screen, device = "lathe"),
        Entry(CompoundRoute, EntryKind.Sheet, device = "lathe"),
        Entry(HeadTiltRoute, EntryKind.Sheet),
        Entry(RotatePartRoute, EntryKind.Sheet),
        Entry(AlignAxesRoute, EntryKind.Sheet),
        Entry(MachineDatumEditRoute(), EntryKind.Sheet),
        Entry(WorkspaceTransformRoute(WorkspaceTransformKind.Flip), EntryKind.Sheet),
        Entry(WorkspaceTransformRoute(WorkspaceTransformKind.Rotate), EntryKind.Sheet),
        Entry(WorkspaceTransformRoute(WorkspaceTransformKind.Scale), EntryKind.Sheet),
        Entry(WorkspaceTransformRoute(WorkspaceTransformKind.Pair), EntryKind.Sheet),
        Entry(TagSubDatumsRoute, EntryKind.Sheet),
        Entry(MeasurementLogRoute, EntryKind.Screen),
        // The drawing setup shows a picked DXF file; with no file it closes at once.
        Entry(DrawingSetupRoute, EntryKind.Screen, needsPendingData = true),
        Entry(DrawingProblemsRoute, EntryKind.Screen),
        Entry(ToolGeometryRoute(ids.tool), EntryKind.Screen),
        Entry(ProfileFileRoute, EntryKind.Screen),
        Entry(ReportRoute, EntryKind.Screen),
        // The global screens (no row of the screen table: no kind to compare).
        Entry(RememberedMachinesRoute, null),
        Entry(AboutRoute, null),
        Entry(LogsRoute, null),
        Entry(KeyMappingRoute, null),
    )

    private val noIds = Ids("t", "w", "p", "a")

    @Test
    fun each_entry_is_registered_with_the_kind_of_the_table() {
        val registry = appEntryRegistry()
        val problems = (entries(noIds) + Entry(CalibrationWizardRoute(AxisId.X), EntryKind.Screen)).mapNotNull { entry ->
            val spec = when (val route = entry.route) {
                is TabRoute -> registry.spec(route)
                is GlobalRoute -> registry.spec(route)
                else -> null
            }
            when {
                spec == null -> "${entry.route}: no entry"
                entry.kind != null && registry.kindOf(entry.route) != entry.kind -> "${entry.route}: ${registry.kindOf(entry.route)}, the table gives ${entry.kind}"
                else -> null
            }
        }
        assertEquals(emptyList(), problems)
    }

    private fun SkikoComposeUiTest.open(graph: AppGraph, entry: Entry) = runOnUiThread {
        when (val route = entry.route) {
            is TabRoute -> if (route != ReadoutRoute) graph.holder.tab(DeviceId(entry.device)).push(route)
            is GlobalRoute -> graph.holder.push(route)
        }
    }

    private fun SkikoComposeUiTest.closeAll(graph: AppGraph) = runOnUiThread {
        graph.holder.clear()
        graph.holder.tabIds.forEach { id -> graph.holder.tab(id).popToRoot() }
    }

    private fun SkikoComposeUiTest.selectTab(graph: AppGraph, device: String) {
        onNodeWithTag(org.superdro.core.ui.tabTag(DeviceId(device))).performClick()
        waitForIdle()
    }

    @Test
    fun each_entry_opens_in_App_with_no_placeholder() = AppGraph().use { graph ->
        graph.connect("mill", "Mill") { profile -> profile.copy(caliper = profile.caliper.copy(enabled = true)) }
        graph.connect("lathe", "Lathe", MachineType.Lathe)
        val ids = runBlocking {
            val tool = assertNotNull(graph.di.direct.instance<ToolRepository>().addTool("mill", Tool(name = "T1", diameterUm = 6_000.0)).valueOrNull())
            val profile = assertNotNull(graph.machines.loadProfile("mill"))
            val probe = assertNotNull(graph.di.direct.instance<ProbeRepository>().saveProbeProfile(ProbeProfile(profileId = "mill", name = "Probe 1")).valueOrNull())
            Ids(tool.id, profile.activeWorkspaceId, probe.id, assertNotNull(profile.adapterProfileId))
        }
        runWindowTest(1280, 800) {
            setContent { ProvideDI(graph.di) { App() } }
            waitUntil("two tabs", 10_000L) {
                onAllNodesWithTag(org.superdro.core.ui.tabTag(DeviceId("lathe"))).fetchSemanticsNodes().isNotEmpty() &&
                    onAllNodesWithTag(org.superdro.core.ui.tabTag(DeviceId("mill"))).fetchSemanticsNodes().isNotEmpty()
            }
            val problems = ArrayList<String>()
            entries(ids).forEach { entry ->
                selectTab(graph, entry.device)
                open(graph, entry)
                waitForIdle()
                mainClock.advanceTimeBy(1_500L)
                waitForIdle()
                val placeholders = onAllNodesWithTag(PLACEHOLDER_TAG, useUnmergedTree = true).fetchSemanticsNodes().size +
                    onAllNodes(hasText("This screen is under construction", substring = true), useUnmergedTree = true).fetchSemanticsNodes().size
                val readoutShows = onAllNodesWithTag(READOUT_SCREEN_TAG).fetchSemanticsNodes().isNotEmpty()
                val roots = onAllNodes(isRoot()).fetchSemanticsNodes().size
                val stack = (entry.route as? TabRoute)?.let { graph.holder.tab(DeviceId(entry.device)).routes } ?: graph.holder.globalEntries.map { it.route }
                when {
                    placeholders > 0 -> problems += "${entry.route}: a placeholder"
                    entry.needsPendingData -> if (entry.route in stack) problems += "${entry.route}: shows with no pending data"
                    entry.route == ReadoutRoute -> if (!readoutShows) problems += "the readout does not show"
                    entry.kind == EntryKind.Screen && readoutShows -> problems += "${entry.route}: the screen does not cover the readout"
                    (entry.kind == EntryKind.Sheet || entry.kind == EntryKind.Dialog) && roots < 2 -> problems += "${entry.route}: no sheet or dialog shows"
                }
                closeAll(graph)
                waitForIdle()
                mainClock.advanceTimeBy(1_000L)
            }
            assertEquals(emptyList(), problems)
        }
    }

    @Test
    fun a_sheet_is_a_bottom_sheet_in_portrait_and_a_dialog_in_landscape() = AppGraph().use { graph ->
        graph.connect("mill", "Mill")
        listOf(1280 to 800, 800 to 1280).forEach { (width, height) ->
            runWindowTest(width, height) {
                setContent { ProvideDI(graph.di) { App() } }
                waitUntil("tab", 10_000L) { onAllNodesWithTag(org.superdro.core.ui.tabTag(DeviceId("mill"))).fetchSemanticsNodes().isNotEmpty() }
                open(graph, Entry(KeypadRoute(AxisId.X), EntryKind.Sheet))
                waitForIdle()
                mainClock.advanceTimeBy(1_500L)
                val field = onNodeWithTag(KEYPAD_FIELD_TAG).rect()
                if (width > height) {
                    // A dialog: centered, with space at the left and the right.
                    assertTrue(field.left > 100f && field.right < width - 100f, "dialog in landscape: $field")
                } else {
                    // A bottom sheet: the content is in the bottom part of the window.
                    assertTrue(field.top > height * 0.3f, "bottom sheet in portrait: $field")
                }
                closeAll(graph)
                waitForIdle()
            }
        }
    }

    @Test
    fun each_sheet_can_be_closed_with_no_choice() = AppGraph().use { graph ->
        graph.connect("mill", "Mill")
        // The back action closes each sheet.
        // The JVM test has no back key of the system: the test uses the other dismiss of the same
        // sheet, a tap outside it (the dialog in landscape, the scrim of the bottom sheet in portrait).
        listOf(Triple(1280, 800, androidx.compose.ui.geometry.Offset(10f, 790f)), Triple(800, 1280, androidx.compose.ui.geometry.Offset(400f, 120f))).forEach { (width, height, outside) ->
            runWindowTest(width, height) {
                setContent { ProvideDI(graph.di) { App() } }
                waitUntil("tab", 10_000L) { onAllNodesWithTag(org.superdro.core.ui.tabTag(DeviceId("mill"))).fetchSemanticsNodes().isNotEmpty() }
                listOf<TabRoute>(AxisDetailsRoute(AxisId.X), ShapeMenuRoute, KeypadRoute(AxisId.X)).forEach { route ->
                    open(graph, Entry(route, EntryKind.Sheet))
                    waitForIdle()
                    mainClock.advanceTimeBy(1_500L)
                    assertTrue(onAllNodes(isRoot()).fetchSemanticsNodes().size >= 2, "$route shows")
                    onAllNodes(isRoot())[0].performTouchInput { click(outside) }
                    mainClock.advanceTimeBy(1_500L)
                    waitUntil("$route closes in $width x $height", 5_000L) { graph.holder.tab(DeviceId("mill")).routes == listOf(ReadoutRoute) }
                }
                closeAll(graph)
            }
        }
    }

    @Test
    fun Calibrate_of_the_setup_reminder_opens_the_calibration_wizard() = AppGraph().use { graph ->
        graph.connect("mill", "Mill")
        runWindowTest(1280, 800) {
            setContent { ProvideDI(graph.di) { App() } }
            waitUntil("tab", 10_000L) { onAllNodesWithTag(org.superdro.core.ui.tabTag(DeviceId("mill"))).fetchSemanticsNodes().isNotEmpty() }
            // The route of "Calibrate" (PresenterReadoutEvents.onSetupCalibrate): the screen table gives the
            // calibration wizard (steps) as a screen.
            open(graph, Entry(CalibrationWizardRoute(AxisId.X), EntryKind.Screen))
            waitForIdle()
            mainClock.advanceTimeBy(1_500L)
            val placeholders = onAllNodesWithTag(PLACEHOLDER_TAG, useUnmergedTree = true).fetchSemanticsNodes().size
            assertEquals(0, placeholders, "the calibration wizard is a placeholder")
        }
    }
}
