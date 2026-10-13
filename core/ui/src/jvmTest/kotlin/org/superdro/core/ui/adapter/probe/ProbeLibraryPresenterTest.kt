package org.superdro.core.ui.adapter.probe

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
import org.superdro.core.connection.DeviceId
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.ProbeRoles
import org.superdro.core.model.ProbeType
import org.superdro.core.store.files.ExportFile
import org.superdro.core.store.repository.ProbeRepository
import org.superdro.core.ui.adapter.AdapterFixture
import org.superdro.core.ui.adapter.common.PreviewLineUi
import org.superdro.core.ui.tabs.english
import org.superdro.core.util.PickedFile

/** The presenter of the probe library: the list, the delete, the export, and the import. */
class ProbeLibraryPresenterTest {

    private val mill = DeviceId("mill")

    private suspend fun TestScope.fixture(): AdapterFixture = AdapterFixture(this).also { fixture ->
        fixture.machineWithAdapter(mill)
    }

    private fun own(id: String, name: String, type: ProbeType = ProbeType.TouchProbe, port: Char? = 'p') =
        ProbeProfile(id = id, profileId = "mill", type = type, name = name, port = port, stylusDiameterUm = 2000.0)

    @Test
    fun theLibraryShowsTheOwnProfilesFirstThenTheSixStandardTips() = runTest {
        val fixture = fixture()
        fixture.probes.saveProbeProfile(own("b", "Beta probe"))
        fixture.probes.saveProbeProfile(own("a", "Alpha probe"))
        fixture.machineWithoutAdapter(DeviceId("lathe"))
        fixture.probes.saveProbeProfile(ProbeProfile(id = "other", profileId = "lathe", name = "Lathe probe"))
        val presenter = ProbeLibraryPresenter(fixture.deps, mill)
        advanceUntilIdle()
        val rows = presenter.uiState.value.rows
        assertEquals(
            listOf("Alpha probe", "Beta probe", "Edge finder 0.100\"", "Edge finder 0.200\"", "Edge finder 0.500\"", "Edge finder 4 mm", "Edge finder 6 mm", "Edge finder 10 mm"),
            rows.map(ProbeLibraryRow::name),
            "The own profiles by name, then the profiles for all machine profiles in the natural order. A profile of another machine does not show.",
        )
        assertEquals(6, rows.count(ProbeLibraryRow::standardTip), "The six standard tips are in the library of each machine profile.")
        assertEquals(2540.0, rows.first { row -> row.name == "Edge finder 0.100\"" }.diameterUm)
        assertEquals("Mill mill", presenter.uiState.value.machineName)
        assertTrue(presenter.uiState.value.canTransfer)
        assertEquals(3, presenter.selectors.size, "One selector for each function.")
        presenter.close()
        fixture.close()
    }

    @Test
    fun theStandardTipsHaveTheOrderOfF1012AndANumberComparesByItsValue() = runTest {
        val fixture = fixture()
        fixture.probes.saveProbeProfile(own("p10", "Probe 10"))
        fixture.probes.saveProbeProfile(own("p2", "probe 2"))
        fixture.probes.saveProbeProfile(own("p1", "Probe 1.5"))
        val presenter = ProbeLibraryPresenter(fixture.deps, null)
        advanceUntilIdle()
        assertEquals(
            listOf("Edge finder 0.100\"", "Edge finder 0.200\"", "Edge finder 0.500\"", "Edge finder 4 mm", "Edge finder 6 mm", "Edge finder 10 mm"),
            presenter.uiState.value.rows.map(ProbeLibraryRow::name),
        )
        presenter.close()
        val withTab = ProbeLibraryPresenter(fixture.deps, mill)
        advanceUntilIdle()
        assertEquals(listOf("Probe 1.5", "probe 2", "Probe 10"), withTab.uiState.value.rows.filterNot(ProbeLibraryRow::shared).map(ProbeLibraryRow::name))
        withTab.close()
        fixture.close()
    }

    @Test
    fun withNoTabTheLibraryShowsOnlyTheProfilesForAllMachineProfiles() = runTest {
        val fixture = fixture()
        fixture.probes.saveProbeProfile(own("a", "Alpha probe"))
        val presenter = ProbeLibraryPresenter(fixture.deps, null)
        advanceUntilIdle()
        assertEquals(6, presenter.uiState.value.rows.size)
        assertFalse(presenter.uiState.value.canTransfer, "The export and the import need a machine profile.")
        assertNull(presenter.uiState.value.machineName)
        assertTrue(presenter.selectors.isEmpty())
        presenter.close()
        fixture.close()
    }

    @Test
    fun aDeleteAsksFirstAndClearsTheSelectionOfEachFunction() = runTest {
        val fixture = fixture()
        fixture.probes.saveProbeProfile(own("a", "Alpha probe"))
        fixture.store.machines.updateProfile("mill") { profile -> profile.copy(probeRoles = ProbeRoles(edgeFinderProfileId = "a", zReferenceProfileId = "a")) }
        val presenter = ProbeLibraryPresenter(fixture.deps, mill)
        advanceUntilIdle()
        presenter.askDelete("a")
        assertEquals("a", presenter.uiState.value.confirmDelete?.id)
        presenter.cancelDelete()
        advanceUntilIdle()
        assertEquals(7, presenter.uiState.value.rows.size, "Cancel deletes nothing.")
        presenter.askDelete("a")
        presenter.confirmDelete()
        advanceUntilIdle()
        assertEquals(6, presenter.uiState.value.rows.size)
        assertEquals(ProbeRoles(), fixture.store.machines.loadProfile("mill")!!.probeRoles, "Each function loses the deleted probe.")
        presenter.close()
        fixture.close()
    }

    @Test
    fun aTapOnAStandardTipOpensAndClosesTheNoticeBelowItsRow() = runTest {
        // The dialog rule: the notice shows in the list below the row, not in a dialog.
        val fixture = fixture()
        val presenter = ProbeLibraryPresenter(fixture.deps, mill)
        advanceUntilIdle()
        val (first, second) = ProbeRepository.STANDARD_TIP_IDS.take(2)
        presenter.toggleStandardTipNotice(first)
        assertTrue(presenter.uiState.value.standardTipNotice)
        assertEquals(first, presenter.uiState.value.standardTipNoticeId)
        presenter.toggleStandardTipNotice(second)
        assertTrue(presenter.uiState.value.standardTipNotice, "A tap on a different standard tip moves the notice.")
        assertEquals(second, presenter.uiState.value.standardTipNoticeId)
        presenter.toggleStandardTipNotice(second)
        assertFalse(presenter.uiState.value.standardTipNotice, "A second tap on the same row closes the notice.")
        assertNull(presenter.uiState.value.standardTipNoticeId)
        presenter.toggleStandardTipNotice(first)
        presenter.dismissStandardTipNotice()
        assertFalse(presenter.uiState.value.standardTipNotice)
        assertNull(presenter.uiState.value.standardTipNoticeId)
        presenter.close()
        fixture.close()
    }

    @Test
    fun aDeletedStandardTipGoesForAllMachineProfiles() = runTest {
        val fixture = fixture()
        val presenter = ProbeLibraryPresenter(fixture.deps, mill)
        advanceUntilIdle()
        val tip = ProbeRepository.STANDARD_TIP_IDS.first()
        presenter.askDelete(tip)
        assertTrue(presenter.uiState.value.confirmDelete!!.standardTip)
        presenter.confirmDelete()
        advanceUntilIdle()
        assertEquals(5, fixture.probes.sharedProfiles().first().size)
        presenter.showStandardTipNotice()
        assertTrue(presenter.uiState.value.standardTipNotice)
        presenter.dismissStandardTipNotice()
        assertFalse(presenter.uiState.value.standardTipNotice)
        presenter.close()
        fixture.close()
    }

    @Test
    fun theExportWritesOnlyTheOwnProfilesInBothForms() = runTest {
        val fixture = fixture()
        fixture.probes.saveProbeProfile(own("b", "Beta probe"))
        fixture.probes.saveProbeProfile(own("a", "Alpha probe"))
        val presenter = ProbeLibraryPresenter(fixture.deps, mill)
        advanceUntilIdle()
        presenter.export(ProbeExportForm.Tdprb)
        advanceUntilIdle()
        val (name, mime, text) = fixture.files.created.single()
        assertEquals("superdro-probe_profiles-Mill_mill-20261012_080000.tdprb", name)
        assertEquals(ExportFile.MIME_TDPRB, mime)
        assertTrue(text.contains("\"touchdro\": \"probe-profiles\""))
        assertTrue(text.indexOf("Alpha probe") < text.indexOf("Beta probe"), "The profiles are sorted by name.")
        assertFalse(text.contains("Edge finder 4 mm"), "An export has no standard tips.")
        assertEquals("Saved as “superdro-probe_profiles-Mill_mill-20261012_080000.tdprb”.", presenter.uiState.value.message!!.english())
        presenter.messageShown()
        presenter.export(ProbeExportForm.SuperDro)
        advanceUntilIdle()
        assertEquals("superdro-probe_profiles-Mill_mill-20261012_080000.json", fixture.files.created.last().first)
        assertTrue(fixture.files.created.last().third.contains("\"superdro\": \"probe-profiles\""))
        fixture.files.createResult = false
        presenter.export(ProbeExportForm.SuperDro)
        advanceUntilIdle()
        assertEquals("The file was not saved.", presenter.uiState.value.message!!.english())
        presenter.close()
        fixture.close()
    }

    @Test
    fun anExportWithNoOwnProfilesShowsAMessage() = runTest {
        val fixture = fixture()
        val presenter = ProbeLibraryPresenter(fixture.deps, mill)
        advanceUntilIdle()
        presenter.export(ProbeExportForm.Tdprb)
        advanceUntilIdle()
        assertTrue(fixture.files.created.isEmpty())
        assertEquals("This machine profile has no own probe profiles to export.", presenter.uiState.value.message!!.english())
        presenter.close()
        fixture.close()
    }

    @Test
    fun theImportShowsThePreviewThenWritesTheRowsAndTheReport() = runTest {
        val fixture = fixture()
        fixture.probes.saveProbeProfile(own("a", "Alpha probe"))
        val presenter = ProbeLibraryPresenter(fixture.deps, mill)
        advanceUntilIdle()
        fixture.files.nextOpen = PickedFile("anything.txt", TDPRB.encodeToByteArray())
        presenter.startImport()
        advanceUntilIdle()
        assertEquals(listOf(listOf("*/*")), fixture.files.opened, "The import finds the type from the content.")
        val preview = assertNotNull(presenter.uiState.value.importPreview)
        assertTrue(preview.canImport)
        assertEquals(listOf("Add each profile as a new profile", "Replace all own profiles of this machine profile"), preview.operations.map { it.english() })
        assertEquals(listOf("Touch probe 2 mm", "Tool setter 50.00 mm"), preview.toImport.map(PreviewLineUi::label))
        assertEquals(listOf("A TouchDRO probe profile file (.tdprb)", "From the machine profile “Bridgeport”", "Exported: 2026-10-11 14:05:33"), preview.header.map { it.english() })
        assertEquals(
            listOf("The profiles come from “Bridgeport”. You import them into “Mill mill”."),
            preview.warnings.map { it.english() },
        )
        presenter.confirmImport()
        advanceUntilIdle()
        assertNull(presenter.uiState.value.importPreview)
        val report = assertNotNull(presenter.uiState.value.report)
        assertEquals("Imported: 2. Not imported: 0. Warnings: 1.", report.summary.english())
        val names = fixture.probes.library("mill").first().filter { profile -> profile.profileId == "mill" }.map(ProbeProfile::name)
        assertEquals(listOf("Alpha probe", "Tool setter 50.00 mm", "Touch probe 2 mm"), names)
        val setter = fixture.probes.library("mill").first().first { profile -> profile.name == "Tool setter 50.00 mm" }
        assertEquals(50000.0, setter.zOffsetUm)
        assertEquals(ProbeType.ToolHeightSetter, setter.type)
        presenter.dismissReport()
        assertNull(presenter.uiState.value.report)
        presenter.close()
        fixture.close()
    }

    @Test
    fun replaceAllDeletesTheOwnProfilesFirst() = runTest {
        val fixture = fixture()
        fixture.probes.saveProbeProfile(own("a", "Alpha probe"))
        val presenter = ProbeLibraryPresenter(fixture.deps, mill)
        advanceUntilIdle()
        fixture.files.nextOpen = PickedFile("p.tdprb", TDPRB.encodeToByteArray())
        presenter.startImport()
        advanceUntilIdle()
        presenter.selectOperation(1)
        assertEquals(1, presenter.uiState.value.importPreview!!.selectedOperation)
        presenter.confirmImport()
        advanceUntilIdle()
        val own = fixture.probes.library("mill").first().filter { profile -> profile.profileId == "mill" }.map(ProbeProfile::name)
        assertEquals(listOf("Tool setter 50.00 mm", "Touch probe 2 mm"), own)
        presenter.close()
        fixture.close()
    }

    @Test
    fun aFileOfAnotherKindCannotImport() = runTest {
        val fixture = fixture()
        val presenter = ProbeLibraryPresenter(fixture.deps, mill)
        advanceUntilIdle()
        fixture.files.nextOpen = PickedFile("tools.tdprb", """{"touchdro":"settings","format":1}""".encodeToByteArray())
        presenter.startImport()
        advanceUntilIdle()
        val preview = assertNotNull(presenter.uiState.value.importPreview)
        assertFalse(preview.canImport)
        assertTrue(preview.errors.isNotEmpty(), "The preview tells why the file cannot import.")
        presenter.confirmImport()
        advanceUntilIdle()
        assertNotNull(presenter.uiState.value.importPreview, "A refused file writes nothing.")
        presenter.cancelImport()
        assertNull(presenter.uiState.value.importPreview)
        presenter.close()
        fixture.close()
    }

    private companion object {
        /** An example probe profile file, without the session. */
        val TDPRB = """
            {
              "touchdro": "probe-profiles",
              "format": 1,
              "app": "3.32.00, Build 2026-09-20",
              "exported": "2026-10-11 14:05:33",
              "device": "6f1c2a9e-0d4b-4c1e-9a77-1b2c3d4e5f60",
              "profile-id": 1,
              "profile": "Bridgeport",
              "profiles": [
                {
                  "id": 7,
                  "type": "TOUCH_PROBE",
                  "name": "Touch probe 2 mm",
                  "input": "p",
                  "stylus-diameter": 1987.4,
                  "x-offset": 0,
                  "y-offset": 0,
                  "z-offset": 0,
                  "metric": true,
                  "sample-count": 3,
                  "max-spread": 20
                },
                {
                  "id": 9,
                  "type": "TOOL_HEIGHT_SETTER",
                  "name": "Tool setter 50.00 mm",
                  "z-offset": 50000,
                  "metric": true
                }
              ]
            }
        """.trimIndent()
    }
}
