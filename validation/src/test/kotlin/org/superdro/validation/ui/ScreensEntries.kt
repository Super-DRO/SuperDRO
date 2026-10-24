package org.superdro.validation.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
import org.superdro.core.ui.readout.READOUT_SCREEN_TAG

/** The entries of the screen table that the screens tests open, and the IDs that some routes name. */
internal object ScreensEntries {

    /** One SuperDRO entry of the screen table, or a global screen. */
    data class Entry(val route: Any, val kind: EntryKind?, val device: String = "mill", val needsPendingData: Boolean = false)

    /** The IDs of the stored data that some routes name. */
    data class Ids(val tool: String, val workspace: String, val probe: String, val adapter: String)

    fun entries(ids: Ids): List<Entry> = listOf(
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

    /** Placeholder IDs for a check that needs no stored data. */
    val noIds = Ids("t", "w", "p", "a")
}

@OptIn(ExperimentalTestApi::class)
internal fun SkikoComposeUiTest.open(graph: AppGraph, entry: ScreensEntries.Entry) = runOnUiThread {
    when (val route = entry.route) {
        is TabRoute -> if (route != ReadoutRoute) graph.holder.tab(DeviceId(entry.device)).push(route)
        is GlobalRoute -> graph.holder.push(route)
    }
}

@OptIn(ExperimentalTestApi::class)
internal fun SkikoComposeUiTest.closeAll(graph: AppGraph) = runOnUiThread {
    graph.holder.clear()
    graph.holder.tabIds.forEach { id -> graph.holder.tab(id).popToRoot() }
}

@OptIn(ExperimentalTestApi::class)
internal fun SkikoComposeUiTest.selectTab(graph: AppGraph, device: String) {
    onNodeWithTag(org.superdro.core.ui.tabTag(DeviceId(device))).performClick()
    waitForIdle()
}

/**
 * Opens each entry of [ScreensEntries.entries] whose index [select] accepts in `App()`, and fails
 * when one shows a placeholder, covers the readout wrongly, or shows no sheet or dialog. Two test
 * classes share the entries, so that the two halves run on two forks.
 */
@OptIn(ExperimentalTestApi::class)
internal fun openEntriesWithNoPlaceholder(select: (index: Int) -> Boolean) = AppGraph().use { graph ->
    graph.connect("mill", "Mill") { profile -> profile.copy(caliper = profile.caliper.copy(enabled = true)) }
    graph.connect("lathe", "Lathe", MachineType.Lathe, scenario = "commands")
    val ids = runBlocking {
        val tool = assertNotNull(graph.di.direct.instance<ToolRepository>().addTool("mill", Tool(name = "T1", diameterUm = 6_000.0)).valueOrNull())
        val profile = assertNotNull(graph.machines.loadProfile("mill"))
        val probe = assertNotNull(graph.di.direct.instance<ProbeRepository>().saveProbeProfile(ProbeProfile(profileId = "mill", name = "Probe 1")).valueOrNull())
        ScreensEntries.Ids(tool.id, profile.activeWorkspaceId, probe.id, assertNotNull(profile.adapterProfileId))
    }
    runWindowTest(1280, 800) {
        setContent { ProvideDI(graph.di) { App() } }
        waitUntil("two tabs", 10_000L) {
            onAllNodesWithTag(org.superdro.core.ui.tabTag(DeviceId("lathe"))).fetchSemanticsNodes().isNotEmpty() &&
                onAllNodesWithTag(org.superdro.core.ui.tabTag(DeviceId("mill"))).fetchSemanticsNodes().isNotEmpty()
        }
        val problems = ArrayList<String>()
        ScreensEntries.entries(ids).filterIndexed { index, _ -> select(index) }.forEach { entry ->
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

