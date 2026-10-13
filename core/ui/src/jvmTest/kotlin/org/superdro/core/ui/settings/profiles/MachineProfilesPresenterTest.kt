package org.superdro.core.ui.settings.profiles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.SessionState
import org.superdro.core.model.AuxiliaryType
import org.superdro.core.model.Channels
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.PatternAngleConvention
import org.superdro.core.model.ReadoutFont
import org.superdro.core.model.ReadoutFormat
import org.superdro.core.model.SidePanelMode
import org.superdro.core.model.SubDatum
import org.superdro.core.model.Tool
import org.superdro.core.model.ToolKind
import org.superdro.core.store.repository.MismatchDisposal
import org.superdro.core.store.valueOrNull
import org.superdro.core.ui.tabs.adddevice.SetupInput
import org.superdro.core.ui.tabs.english
import org.superdro.core.ui.tabs.testPresenterContext
import org.superdro.core.ui.testing.FakeConnections
import org.superdro.core.ui.testing.FakeDeviceSession
import org.superdro.core.ui.testing.UiStoreFixture

/** The machine profiles screen. */
class MachineProfilesPresenterTest {

    private class Harness(scope: TestScope) {
        val store = UiStoreFixture(scope)
        val connections = FakeConnections()
        val presenter = MachineProfilesPresenter(store.machines, connections, scope.testPresenterContext())

        suspend fun profile(name: String, type: MachineType = MachineType.VerticalMill): String =
            store.machines.createProfile(MachineProfile.create(type, name)).valueOrNull()!!.id

        fun message(): String = presenter.uiState.value.message!!.text.english()

        fun close() {
            presenter.close()
            store.close()
        }
    }

    @Test
    fun eachRowShowsTheNameTheTypeTheUnitAndTheOnlineState() = runTest {
        val harness = Harness(this)
        val mill = harness.profile("Mill")
        val lathe = harness.profile("Lathe", MachineType.Lathe)
        harness.store.machines.updateProfile(lathe) { profile -> profile.copy(metricDefault = true) }
        harness.connections.add(FakeDeviceSession(DeviceId(mill), "Mill", initialState = SessionState.Connected(0L)))
        harness.connections.add(FakeDeviceSession(DeviceId(lathe), "Lathe", initialState = SessionState.Connecting(1)))
        runCurrent()
        val rows = harness.presenter.uiState.value.rows
        assertEquals(listOf("Lathe", "Mill"), rows.map { it.name })
        assertEquals(listOf(MachineType.Lathe, MachineType.VerticalMill), rows.map { it.machineType })
        assertEquals(listOf(true, false), rows.map { it.metric })
        assertEquals(listOf(false, true), rows.map { it.online })
        harness.connections.session(DeviceId(lathe)).state.value = SessionState.Connected(0L)
        runCurrent()
        assertEquals(listOf(true, true), harness.presenter.uiState.value.rows.map { it.online })
        harness.close()
    }

    @Test
    fun aNewLatheGetsTheDefaults() = runTest {
        val harness = Harness(this)
        runCurrent()
        harness.presenter.openCreate()
        runCurrent()
        val dialog = harness.presenter.uiState.value.dialog as ProfileDialogUiState.Create
        assertEquals("Mill", dialog.name)
        harness.presenter.updateCreateSetup { setup -> setup.copy(machineType = MachineType.Lathe) }
        runCurrent()
        assertEquals("Lathe", (harness.presenter.uiState.value.dialog as ProfileDialogUiState.Create).name)
        harness.presenter.confirmCreate()
        runCurrent()
        assertNull(harness.presenter.uiState.value.dialog)
        assertEquals("Lathe is created.", harness.message())
        val profile = harness.store.machines.profiles().first().single()
        assertEquals("Lathe", profile.name)
        assertEquals(MachineType.Lathe, profile.machineType)
        assertEquals(PatternAngleConvention.Legacy, profile.patternAngleConvention)
        assertEquals(ReadoutFormat.Format3, profile.display.readoutFormat)
        assertEquals(ReadoutFont.Dseg7, profile.display.readoutFont)
        assertTrue(profile.display.activateNewSubDatum)
        assertEquals(SidePanelMode.Off, profile.sidePanel)
        assertEquals(115_200, profile.usbBaudRate)
        assertTrue(profile.twoWayCommunication)
        assertFalse(profile.adapterConfigurationCommands)
        assertFalse(profile.useSharedToolLibrary)
        assertFalse(profile.metricDefault)
        assertEquals(AuxiliaryType.Tailstock, profile.auxiliary.type)
        assertEquals(Channels.X, profile.x.primary.channel)
        assertEquals(Channels.Z, profile.z.primary.channel)
        harness.close()
    }

    @Test
    fun theCreateSheetRefusesASetupWithNoAxisAndAPrefixOnTwoInputs() = runTest {
        val harness = Harness(this)
        runCurrent()
        harness.presenter.openCreate()
        harness.presenter.updateCreateSetup { setup ->
            setup.copy(x = SetupInput(false, Channels.X), y = SetupInput(false, Channels.Y), z = SetupInput(false, Channels.Z))
        }
        harness.presenter.confirmCreate()
        runCurrent()
        val noAxis = harness.presenter.uiState.value.dialog as ProfileDialogUiState.Create
        assertEquals("At least one output axis must be enabled", noAxis.setupError!!.english())
        harness.presenter.updateCreateSetup { setup -> setup.copy(z = SetupInput(true, Channels.Z), quill = SetupInput(true, Channels.Z)) }
        runCurrent()
        assertNull((harness.presenter.uiState.value.dialog as ProfileDialogUiState.Create).setupError)
        harness.presenter.confirmCreate()
        runCurrent()
        val samePrefix = harness.presenter.uiState.value.dialog as ProfileDialogUiState.Create
        assertEquals("Primary and secondary inputs can't use the same prefix", samePrefix.setupError!!.english())
        assertTrue(harness.store.machines.profiles().first().isEmpty())
        harness.close()
    }

    @Test
    fun theStoreRefusesABlankNameAndANameThatExists() = runTest {
        val harness = Harness(this)
        harness.profile("Mill")
        runCurrent()
        harness.presenter.openCreate()
        runCurrent()
        assertEquals("Mill 2", (harness.presenter.uiState.value.dialog as ProfileDialogUiState.Create).name)
        harness.presenter.setCreateName("  mill ")
        harness.presenter.confirmCreate()
        runCurrent()
        assertEquals("A profile with this name already exists.", (harness.presenter.uiState.value.dialog as ProfileDialogUiState.Create).nameError!!.english())
        harness.presenter.setCreateName("   ")
        runCurrent()
        assertNull((harness.presenter.uiState.value.dialog as ProfileDialogUiState.Create).nameError)
        harness.presenter.confirmCreate()
        runCurrent()
        assertEquals("Profile name can't be blank.", (harness.presenter.uiState.value.dialog as ProfileDialogUiState.Create).nameError!!.english())
        assertEquals(1, harness.store.machines.profiles().first().size)
        harness.presenter.closeDialog()
        runCurrent()
        assertNull(harness.presenter.uiState.value.dialog)
        harness.close()
    }

    @Test
    fun aCopyHasTheSettingsAndTheToolsButNotTheWorkspaces() = runTest {
        val harness = Harness(this)
        val mill = harness.profile("Mill")
        harness.store.machines.updateProfile(mill) { profile -> profile.copy(metricDefault = true) }
        harness.store.tools.addTool(mill, Tool(name = "6 mm end mill", diameterUm = 6000.0))
        harness.store.machines.createWorkspace(mill, "Vise")
        runCurrent()
        harness.presenter.openCopy(mill)
        runCurrent()
        val dialog = harness.presenter.uiState.value.dialog as ProfileDialogUiState.Copy
        assertEquals("Mill 2", dialog.name)
        harness.presenter.confirmCopy()
        runCurrent()
        assertEquals("Mill 2 is created as a copy of Mill.", harness.message())
        val copy = harness.store.machines.profiles().first().single { it.name == "Mill 2" }
        assertTrue(copy.metricDefault)
        assertEquals(listOf("6 mm end mill"), harness.store.tools.profileTools(copy.id).first().map { it.name })
        assertEquals(listOf("Default Workspace"), harness.store.machines.workspaces(copy.id).first().map { it.name })
        assertEquals(2, harness.store.machines.workspaces(mill).first().size)
        harness.presenter.openCopy(mill)
        runCurrent()
        assertEquals("Mill 3", (harness.presenter.uiState.value.dialog as ProfileDialogUiState.Copy).name)
        harness.presenter.setCopyName("mill 2")
        harness.presenter.confirmCopy()
        runCurrent()
        assertEquals("A profile with this name already exists.", (harness.presenter.uiState.value.dialog as ProfileDialogUiState.Copy).error!!.english())
        harness.close()
    }

    @Test
    fun aRenameNeedsAUniqueName() = runTest {
        val harness = Harness(this)
        val mill = harness.profile("Mill")
        harness.profile("Lathe", MachineType.Lathe)
        runCurrent()
        harness.presenter.openRename(mill)
        runCurrent()
        assertEquals("Mill", (harness.presenter.uiState.value.dialog as ProfileDialogUiState.Rename).name)
        harness.presenter.setRenameName("LATHE")
        harness.presenter.confirmRename()
        runCurrent()
        val refused = harness.presenter.uiState.value.dialog as ProfileDialogUiState.Rename
        assertEquals("A profile with this name already exists.", refused.error!!.english())
        assertFalse(refused.busy)
        harness.presenter.setRenameName(" Bridgeport ")
        runCurrent()
        assertNull((harness.presenter.uiState.value.dialog as ProfileDialogUiState.Rename).error)
        harness.presenter.confirmRename()
        runCurrent()
        assertNull(harness.presenter.uiState.value.dialog)
        assertEquals("The profile is now Bridgeport.", harness.message())
        assertEquals("Bridgeport", harness.store.machines.loadProfile(mill)!!.name)
        harness.close()
    }

    @Test
    fun theTypeChangeListsTheDataThatDoesNotFitAndArchivesIt() = runTest {
        val harness = Harness(this)
        val mill = harness.profile("Mill")
        harness.store.tools.addTool(mill, Tool(name = "Drill", diameterUm = 5000.0))
        val workspace = harness.store.machines.workspaces(mill).first().single()
        harness.store.machines.addSubDatum(workspace.id, SubDatum(name = "Hole", x = 1000.0, y = 2000.0))
        runCurrent()
        harness.presenter.openChangeType(mill)
        harness.presenter.selectType(MachineType.Lathe)
        harness.presenter.confirmType()
        runCurrent()
        val change = assertNotNull(harness.presenter.uiState.value.typeChange)
        assertEquals(MachineType.VerticalMill, change.plan.from)
        assertEquals(MachineType.Lathe, change.plan.to)
        assertEquals(listOf("Tools of the other machine kind: 1", "Sub-datums with a Y coordinate: 1"), change.mismatchTexts.map { it.english() })
        assertEquals(
            listOf(
                "The plane of each workspace changes.",
                "The X diameter mode of each workspace changes.",
                "The pattern angle convention changes.",
            ),
            change.conversionTexts.map { it.english() },
        )
        assertEquals(MismatchDisposal.Archive, change.disposal)
        harness.presenter.typeChange.confirm()
        runCurrent()
        assertNull(harness.presenter.uiState.value.typeChange)
        assertEquals("The machine type of Mill is now Lathe.", harness.message())
        val lathe = harness.store.machines.loadProfile(mill)!!
        assertEquals(MachineType.Lathe, lathe.machineType)
        assertEquals(PatternAngleConvention.Legacy, lathe.patternAngleConvention)
        assertTrue(harness.store.tools.profileTools(mill).first().none { it.kind == ToolKind.Mill })
        assertTrue(harness.store.machines.archivedItems(mill).first().isNotEmpty())
        assertEquals(listOf<Double?>(null), harness.store.machines.subDatums(workspace.id).first().map { it.y })
        harness.close()
    }

    @Test
    fun theTypeChangeWithDeleteRemovesTheDataAndTheSameTypeDoesNothing() = runTest {
        val harness = Harness(this)
        val mill = harness.profile("Mill")
        harness.store.tools.addTool(mill, Tool(name = "Drill", diameterUm = 5000.0))
        runCurrent()
        harness.presenter.openChangeType(mill)
        harness.presenter.confirmType()
        runCurrent()
        assertNull(harness.presenter.uiState.value.typeChange)
        harness.presenter.openChangeType(mill)
        harness.presenter.selectType(MachineType.Lathe)
        harness.presenter.confirmType()
        runCurrent()
        harness.presenter.typeChange.setDisposal(MismatchDisposal.Delete)
        runCurrent()
        assertEquals(MismatchDisposal.Delete, harness.presenter.uiState.value.typeChange!!.disposal)
        harness.presenter.typeChange.confirm()
        runCurrent()
        assertEquals(MachineType.Lathe, harness.store.machines.loadProfile(mill)!!.machineType)
        assertTrue(harness.store.tools.profileTools(mill).first().isEmpty())
        assertTrue(harness.store.machines.archivedItems(mill).first().isEmpty())
        harness.close()
    }

    @Test
    fun aMillToAHorizontalMillKeepsTheMillTools() = runTest {
        val harness = Harness(this)
        val mill = harness.profile("Mill")
        harness.store.tools.addTool(mill, Tool(name = "Face mill", diameterUm = 50_000.0))
        runCurrent()
        harness.presenter.openChangeType(mill)
        harness.presenter.selectType(MachineType.HorizontalMill)
        harness.presenter.confirmType()
        runCurrent()
        val change = harness.presenter.uiState.value.typeChange!!
        assertTrue(change.plan.mismatches.isEmpty())
        assertEquals(listOf("The mill tools stay. Both mill types use them."), change.conversionTexts.map { it.english() })
        harness.presenter.typeChange.confirm()
        runCurrent()
        assertEquals(listOf("Face mill"), harness.store.tools.profileTools(mill).first().map { it.name })
        harness.close()
    }

    @Test
    fun theUniqueNamesIgnoreTheCase() {
        assertEquals("Mill", ProfileNames.unique("Mill", listOf("Lathe")))
        assertEquals("Mill 2", ProfileNames.unique("Mill", listOf("MILL")))
        assertEquals("Mill 3", ProfileNames.unique("Mill", listOf("mill", "Mill 2")))
        assertEquals("Mill 2", ProfileNames.unique("Mill", emptyList(), alwaysNumber = true))
        assertEquals("Mill", ProfileNames.defaultName(MachineType.HorizontalMill))
        assertEquals("Lathe", ProfileNames.defaultName(MachineType.Lathe))
    }

    @Test
    fun theMenuOpensAndClosesForOneRow() = runTest {
        val harness = Harness(this)
        val mill = harness.profile("Mill")
        runCurrent()
        harness.presenter.openMenu(mill)
        runCurrent()
        assertEquals(mill, harness.presenter.uiState.value.menuFor)
        harness.presenter.openRename(mill)
        runCurrent()
        assertNull(harness.presenter.uiState.value.menuFor)
        harness.close()
    }
}
