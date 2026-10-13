package org.superdro.core.ui.functions.importing

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.MachineDatum
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.ReadoutFormat
import org.superdro.core.model.SubDatum
import org.superdro.core.model.ThreadSpec
import org.superdro.core.model.Tool
import org.superdro.core.model.ToolKind
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.Drawing
import org.superdro.core.model.ProbeContactType
import org.superdro.core.model.ProbeType
import org.superdro.core.store.StoreResult
import org.superdro.core.store.files.ImportOperation
import org.superdro.core.store.files.MessageKey
import org.superdro.core.store.files.RowAction
import org.superdro.core.store.files.settings.ProfileFile
import org.superdro.core.ui.functions.FunctionsHarness
import org.superdro.core.ui.functions.TestEnvironment
import org.superdro.core.ui.functions.common.loadString
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.toUiText

/** The import flow, the import preview, and the report. */
class ImportFlowTest {

    private val scope = TestScope()
    private val harness = FunctionsHarness(scope)

    @AfterTest
    fun close() = harness.close()

    private suspend fun flow(): ImportFlowPresenter =
        ImportFlowPresenter(harness.profile.id, harness.services).also { flow -> flow.environment = TestEnvironment }

    @Test
    fun aTouchDroLatheFileWithEightValuesImports() = scope.runTest {
        harness.start(MachineProfile.create(MachineType.Lathe, "South Bend").copy(metricDefault = true))
        val flow = flow()
        val file = """
            id,name,description,metric,internal,x_offset,z_offset
            4,Boring Bar,3/8 bar,no,yes,-0.125,1,0.375
            1,OD Turning,CCMT insert,NO,No,0.0,1,0.0
        """.trimIndent()
        flow.read(ImportKind.ToolCsv, "tools.csv", file.encodeToByteArray())
        val state = flow.uiState.value
        assertEquals(ImportStage.Preview, state.stage)
        assertTrue(state.usable)
        assertEquals(listOf("Boring Bar", "OD Turning"), state.toImport.map { it.label })
        assertEquals(listOf(RowAction.Add, RowAction.Add), state.toImport.map { it.action })
        assertEquals(listOf(ImportOperation.UpdateMatching, ImportOperation.AddAsNew, ImportOperation.ReplaceAll), state.operations)
        flow.confirm()
        advanceUntilIdle()
        val done = flow.uiState.value
        assertEquals(ImportStage.Closed, done.stage)
        assertEquals("Tool import finished: 2 tools added, 0 tools updated.", done.done!!.loadString(TestEnvironment))
        val tools = harness.store.tools.loadLibrary(harness.profile.id).sortedBy { it.name }
        assertEquals(listOf("Boring Bar", "OD Turning"), tools.map { it.name })
        val bar = tools.first()
        assertEquals(ToolKind.Lathe, bar.kind)
        assertTrue(bar.internal)
        assertFalse(bar.metric)
        assertEquals(-3_175.0, bar.xOffsetUm, 1e-9)
        assertEquals(9_525.0, bar.zOffsetUm, 1e-9)
        flow.close()
    }

    @Test
    fun aFormatTwoMillFileWithADuplicateNameSkipsTheRow() = scope.runTest {
        harness.start(MachineProfile.create(MachineType.VerticalMill, "Bridgeport Mill"))
        val flow = flow()
        val file = "﻿# touchdro: tools\r\n# format: 2\r\n# device: 6f1c2d4e\r\n# profile: Bridgeport Mill\r\n# machine: VERTICAL_MILL\r\n" +
            "name,description,metric,diameter,tooth_count,z_offset\r\n" +
            "1/4 End Mill,2 flute HSS,no,1/4,2,1.25\r\n" +
            "\"1/2 End Mill, carbide\",4 flute,no,0.5,4.0,0\r\n" +
            "6 mm Drill,,YES,\"6,0\",2.0,-12.5\r\n" +
            "6 mm Drill,again,yes,6,2,0\r\n"
        flow.read(ImportKind.ToolCsv, "tools.csv", file.encodeToByteArray())
        val state = flow.uiState.value
        assertEquals(listOf("1/4 End Mill", "1/2 End Mill, carbide", "6 mm Drill"), state.toImport.map { it.label })
        val skipped = state.skipped.single()
        // Row 1 is the header line. The metadata lines do not count.
        assertEquals(5, skipped.row)
        assertEquals(
            "Row 5: Tool offset with this name already exists in this file.",
            skipped.problems.single().loadString(TestEnvironment),
        )
        flow.confirm()
        advanceUntilIdle()
        val tools = harness.store.tools.loadLibrary(harness.profile.id).associateBy { it.name }
        assertEquals(4, tools.getValue("1/2 End Mill, carbide").toothCount)
        assertEquals(6_000.0, tools.getValue("6 mm Drill").diameterUm, 1e-9)
        assertTrue(tools.getValue("6 mm Drill").metric)
        assertEquals(6_350.0, tools.getValue("1/4 End Mill").diameterUm, 1e-9)
        // The skipped row gives a report.
        val report = assertNotNull(flow.uiState.value.report)
        assertTrue(report.sections.any { section -> "Row 5" in section.text })
        flow.close()
    }

    @Test
    fun aSubDatumFileShowsTheRowErrorsAndImportsPointsAndGuides() = scope.runTest {
        harness.start(MachineProfile.create(MachineType.VerticalMill, "Mill").copy(metricDefault = true))
        val flow = flow()
        val file = "﻿name,note,tags,x,y,z\r\n" +
            "A,first,t1 t2,\"1,5\",2,\r\n" +
            "Guide,,,10,,\r\n" +
            ",,,1,2,\r\n" +
            "B,,,3+1/4,,\r\n"
        flow.read(ImportKind.SubDatumCsv, "points.csv", file.encodeToByteArray())
        val state = flow.uiState.value
        assertEquals(true, state.metric)
        assertEquals(listOf("A", "Guide", "B"), state.toImport.map { it.label })
        assertEquals("Row 4: Name is blank", state.skipped.single().problems.single().loadString(TestEnvironment))
        assertEquals("X 1.500 • Y 2.000 • Z — mm", state.toImport.first().detail!!.loadString(TestEnvironment))
        flow.confirm()
        advanceUntilIdle()
        assertEquals(
            "Sub-datum import finished: 2 points added, 0 points updated, 1 guides added.",
            flow.uiState.value.done!!.loadString(TestEnvironment),
        )
        val workspace = harness.readout.workspace()
        val a = workspace.subDatums.first { it.name == "A" }
        assertEquals(listOf("t1", "t2"), a.tags)
        assertEquals(1_500.0, a.x!!, 1e-9)
        assertEquals(2_000.0, a.y!!, 1e-9)
        assertNull(a.z)
        assertEquals(3_250.0, workspace.subDatums.first { it.name == "B" }.x!!, 1e-9)
        assertEquals(10_000.0, workspace.guides.single().valueUm, 1e-9)
        flow.close()
    }

    @Test
    fun theUnitChoiceReadsTheFileAgain() = scope.runTest {
        harness.start(MachineProfile.create(MachineType.VerticalMill, "Mill"))
        val flow = flow()
        flow.read(ImportKind.SubDatumCsv, "points.csv", "name,x,y\r\nA,1,2\r\n".encodeToByteArray())
        assertEquals(false, flow.uiState.value.metric)
        flow.onUnits(true)
        advanceUntilIdle()
        assertEquals(true, flow.uiState.value.metric)
        flow.confirm()
        advanceUntilIdle()
        assertEquals(1_000.0, harness.readout.workspace().subDatums.single().x!!, 1e-9)
        flow.close()
    }

    @Test
    fun aTdsetFileMakesANewProfileWithTheNameOfTheUser() = scope.runTest {
        harness.start(MachineProfile.create(MachineType.VerticalMill, "Mill"))
        val flow = flow()
        val file = """
            {"touchdro":"settings","format":1,"profile":"Bridgeport","exported":"2026-10-11 14:30:05",
             "device":"6f1c2d4e","settings":{"use_metric_mode":true,"display_format":1,"usb_baud_rate":4800,"x_label":"true","no_such_key":1}}
        """.trimIndent()
        flow.read(ImportKind.Profile, "Bridgeport.tdset", file.encodeToByteArray())
        val state = flow.uiState.value
        assertTrue(state.usable, state.fileErrors.toString())
        assertTrue(state.newProfile)
        assertEquals("Bridgeport", state.name)
        assertEquals("TouchDRO settings file (.tdset)", state.kindText!!.loadString(TestEnvironment))
        flow.onName("Mill")
        flow.confirm()
        advanceUntilIdle()
        // A name that exists stays in the preview with the refusal.
        assertEquals(ImportStage.Preview, flow.uiState.value.stage)
        assertEquals("A profile with this name already exists.", flow.uiState.value.nameError!!.loadString(TestEnvironment))
        flow.onName("My Bridgeport")
        flow.confirm()
        advanceUntilIdle()
        assertEquals(ImportStage.Closed, flow.uiState.value.stage)
        val created = harness.store.machines.profiles().first().single { it.name == "My Bridgeport" }
        assertTrue(created.metricDefault)
        assertEquals(ReadoutFormat.Format3, created.display.readoutFormat)
        // 4800 is not a valid baud rate: the profile keeps the default.
        assertEquals(115_200, created.usbBaudRate)
        // A text "true" stays a text for a text setting.
        assertEquals("true", created.x.label)
        val report = assertNotNull(flow.uiState.value.report)
        assertTrue(report.sections.isNotEmpty())
        flow.close()
    }

    @Test
    fun theSuperDroProfileFileGoesBackIntoTheSelectedProfile() = scope.runTest {
        harness.start(MachineProfile.create(MachineType.Lathe, "South Bend").copy(metricDefault = true))
        val original = harness.store.machines.loadProfile(harness.profile.id)!!
        val file = ProfileFile.export(original.copy(metricDefault = false, use3dSubDatums = false), harness.services.exportContext())
        assertFalse("device" in file.text)
        assertEquals("superdro-profile-South_Bend-20261012_080000.json", file.fileName)
        val flow = flow()
        flow.read(ImportKind.Profile, file.fileName, file.bytes())
        flow.onTarget(newProfile = false)
        advanceUntilIdle()
        val state = flow.uiState.value
        assertFalse(state.newProfile)
        assertEquals("South Bend", state.name)
        assertEquals("SuperDRO profile file", state.kindText!!.loadString(TestEnvironment))
        flow.confirm()
        advanceUntilIdle()
        val updated = harness.store.machines.loadProfile(harness.profile.id)!!
        assertEquals("South Bend", updated.name)
        assertFalse(updated.metricDefault)
        assertFalse(updated.use3dSubDatums)
        assertEquals(MachineType.Lathe, updated.machineType)
        flow.close()
    }

    @Test
    fun theReportHasATitleASummarySectionsAndATextExport() = scope.runTest {
        harness.start(MachineProfile.create(MachineType.VerticalMill, "Mill"))
        val flow = flow()
        flow.read(ImportKind.ToolCsv, "tools.csv", "name,metric,diameter,tooth_count,z_offset\r\nA,no,0,2,0\r\nB,no,1,2,0\r\n".encodeToByteArray())
        flow.confirm()
        advanceUntilIdle()
        val report = assertNotNull(flow.uiState.value.report)
        assertEquals("Import report", report.title)
        assertEquals("Imported: 1. Not imported: 1. Warnings: 0.", report.summary)
        val holder = ReportPresenter(harness.services)
        holder.show(ReportRequest(report, name = "Mill"))
        val texts = mutableListOf<UiText>()
        holder.share(texts::add)
        advanceUntilIdle()
        val (name, mime, bytes) = harness.filePicker.shared.single()
        assertEquals("superdro-import-report-Mill-20261012_080000.txt", name.replace("import_report", "import-report"))
        assertEquals("text/plain", mime)
        val text = bytes.decodeToString()
        assertTrue(text.startsWith("Import report\n=============\n\nImported: 1."), text)
        assertTrue("-".repeat(56) in text)
        assertEquals(emptyList(), texts)
        holder.close()
        flow.close()
    }

    @Test
    fun aBinaryFileOrAWrongKindGivesAProblemAndNoCrash() = scope.runTest {
        harness.start()
        val flow = flow()
        flow.read(ImportKind.ToolCsv, "image.png", byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0, 0, 0, 0x0d))
        assertEquals(ImportStage.Preview, flow.uiState.value.stage)
        assertFalse(flow.uiState.value.usable)
        assertEquals(MessageKey.FileBinary.toUiText(), flow.uiState.value.fileErrors.single())
        flow.read(ImportKind.Profile, "points.csv", "name,x,y\r\nA,1,2\r\n".encodeToByteArray())
        assertEquals("The file contains “sub-datums”. This import needs “settings”.", flow.uiState.value.fileErrors.single().loadString(TestEnvironment))
        flow.read(ImportKind.ToolCsv, "points.csv", "name,x,y\r\nA,1,2\r\n".encodeToByteArray())
        assertFalse(flow.uiState.value.usable)
        flow.close()
    }

    @Test
    fun aCancelledPickerClosesTheFlow() = scope.runTest {
        harness.start()
        val flow = flow()
        flow.start(ImportKind.ToolCsv)
        advanceUntilIdle()
        assertEquals(ImportStage.Closed, flow.uiState.value.stage)
        assertEquals(listOf("text/csv", "text/comma-separated-values", "application/csv", "text/plain"), harness.filePicker.openRequests.single())
        harness.filePicker.nextText("tools.csv", "name,metric,diameter,tooth_count,z_offset\r\nA,no,1,2,0\r\n")
        flow.start(ImportKind.ToolCsv)
        advanceUntilIdle()
        assertEquals(ImportStage.Preview, flow.uiState.value.stage)
        assertEquals("tools.csv", flow.uiState.value.fileName)
        flow.cancel()
        assertEquals(ImportStage.Closed, flow.uiState.value.stage)
        flow.close()
    }

    @Test
    fun aProfileDeleteRemovesAllItsData() = scope.runTest {
        harness.start()
        val id = harness.profile.id
        val workspace = harness.readout.workspace()
        val machines = harness.store.machines
        machines.addSubDatum(workspace.id, SubDatum(x = 1.0, y = 2.0))
        machines.saveThread(workspace.id, ThreadSpec(size = "M10 × 1.5"))
        machines.saveMachineDatum(id, MachineDatum(name = "Home", x = 1.0))
        harness.probes.saveProbeProfile(ProbeProfile(profileId = id, name = "Probe"))
        harness.drawings.replaceDrawing(Drawing(workspaceId = workspace.id))
        harness.store.tools.addTool(id, Tool(name = "T1", diameterUm = 1.0))
        assertTrue(machines.deleteProfile(id, deleteTools = true))
        advanceUntilIdle()
        assertNull(machines.loadProfile(id))
        assertNull(machines.loadWorkspace(workspace.id))
        assertEquals(emptyList(), machines.subDatums(workspace.id).first())
        assertEquals(emptyList(), machines.threads(workspace.id).first())
        assertEquals(emptyList(), machines.machineDatums(id).first())
        assertEquals(emptyList(), harness.probes.library(id).first().filter { it.profileId == id })
        assertNull(harness.drawings.loadDrawing(workspace.id))
        assertEquals(emptyList(), harness.store.tools.profileTools(id).first())
    }

    @Test
    fun theProbeValuesOfATdsetFileGoToTheAdapterAndAProbeProfile() = scope.runTest {
        harness.start(MachineProfile.create(MachineType.VerticalMill, "Mill"))
        val adapter = (harness.store.adapters.saveAdapterProfile(AdapterProfile(alias = "Mill adapter")) as StoreResult.Ok).value
        harness.store.machines.updateProfile(harness.profile.id) { it.copy(adapterProfileId = adapter.id) }
        val flow = flow()
        val file = """
            {"touchdro":"settings","format":1,"profile":"Mill","settings":{
              "probe_enable":true,"probe_invert":true,"probe_debounce_seconds":0.05,
              "probe_x_offset_compensation_microns":-12.5,"probe_edge_finder_sample_count":3}}
        """.trimIndent()
        flow.read(ImportKind.Profile, "Mill.tdset", file.encodeToByteArray())
        flow.onTarget(newProfile = false)
        advanceUntilIdle()
        flow.confirm()
        advanceUntilIdle()
        val report = assertNotNull(flow.uiState.value.report)
        val probe = report.sections.single { section -> section.title == "Where the probe values go" }
        assertTrue("“probe_debounce_seconds” = 0.05 goes to the probe settings of the adapter profile." in probe.text, probe.text)
        assertTrue("“probe_edge_finder_sample_count” = 3 goes to a probe profile in the probe library." in probe.text, probe.text)
        val stored = harness.store.adapters.loadAdapterProfile(adapter.id)!!
        assertEquals(50L, stored.probeDebounceMs)
        assertEquals(ProbeContactType.NormallyClosed, stored.probeContactType)
        val edgeFinder = harness.probes.library(harness.profile.id).first().single { it.profileId == harness.profile.id }
        assertEquals(ProbeType.EdgeFinder2D, edgeFinder.type)
        assertEquals("Imported edge finder", edgeFinder.name)
        assertEquals(-12.5, edgeFinder.xOffsetUm)
        assertEquals(3, edgeFinder.sampleCount)
        val profile = harness.store.machines.loadProfile(harness.profile.id)!!
        assertTrue(profile.probe.enabled)
        assertEquals(edgeFinder.id, profile.probeRoles.edgeFinderProfileId)
        flow.close()
    }
}
