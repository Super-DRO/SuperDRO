package org.superdro.core.ui.functions.workspace

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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.AngularAxisConfig
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.MachineDatum
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.ReferenceImage
import org.superdro.core.store.StoreRefusal
import org.superdro.core.ui.functions.FunctionsHarness
import org.superdro.core.ui.functions.TestEnvironment
import org.superdro.core.ui.functions.common.loadString
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.toUiText

/** The workspaces sheet and the machine datums. */
class WorkspacePresentersTest {

    private val scope = TestScope()
    private val harness = FunctionsHarness(scope)
    private val messages = mutableListOf<UiText>()

    @AfterTest
    fun close() = harness.close()

    private val mill = MachineProfile.create(MachineType.VerticalMill, "Mill").copy(metricDefault = true)

    private fun absolute(axis: AxisId): Double = harness.presenter.frame.value!!.reading(axis).absolute

    private fun sheet() = WorkspacesPresenter(harness.profile.id, harness.services, harness.presenter, messages::add)

    @Test
    fun theCreateRulesAndTexts() = scope.runTest {
        harness.start(mill)
        val sheet = sheet()
        runCurrent()
        assertEquals(listOf("Default Workspace"), sheet.uiState.value.rows.map { it.name })
        assertTrue(sheet.uiState.value.rows.single().active)
        sheet.onNewName("  ")
        sheet.onCreate()
        runCurrent()
        assertEquals(StoreRefusal.WorkspaceNameBlank.toUiText(), sheet.fields.value.newNameError)
        assertEquals("Workspace name can't be blank.", sheet.fields.value.newNameError!!.loadString(TestEnvironment))
        sheet.onNewName("Default Workspace")
        sheet.onCreate()
        runCurrent()
        assertEquals("A workspace with this name already exists.", sheet.fields.value.newNameError!!.loadString(TestEnvironment))
        sheet.onNewName("Second op")
        sheet.onCreate()
        runCurrent()
        assertNull(sheet.fields.value.newNameError)
        assertEquals("", sheet.fields.value.newName)
        assertEquals(listOf("Default Workspace", "Second op"), sheet.uiState.value.rows.map { it.name })
        // The new workspace does not become active.
        assertEquals(listOf(true, false), sheet.uiState.value.rows.map { it.active })
        sheet.onRenameStart(sheet.uiState.value.rows[1].id)
        sheet.onRenameText("Op 2")
        sheet.onRenameConfirm()
        runCurrent()
        assertEquals(listOf("Default Workspace", "Op 2"), sheet.uiState.value.rows.map { it.name })
        sheet.close()
    }

    @Test
    fun aSelectKeepsTheStateOfEachWorkspace() = scope.runTest {
        harness.start(mill, states = mapOf(AxisId.X to AxisState(absoluteOffset = 1_234.5, metric = true)))
        harness.inputs(AxisId.X to 10_000.0)
        val first = harness.readout.workspace()
        val second = harness.store.machines.createWorkspace(harness.profile.id, "Second op").let { (it as org.superdro.core.store.StoreResult.Ok).value }
        advanceUntilIdle()
        val sheet = sheet()
        runCurrent()
        assertEquals(11_234.5, absolute(AxisId.X), 1e-9)
        assertTrue(sheet.select(second.id))
        advanceUntilIdle()
        assertEquals(second.id, harness.presenter.frame.value!!.workspace.id)
        assertEquals(10_000.0, absolute(AxisId.X), 1e-9)
        assertEquals("Workspace Second op is active.", messages.last().loadString(TestEnvironment))
        assertTrue(sheet.select(first.id))
        advanceUntilIdle()
        assertEquals(first.id, harness.presenter.frame.value!!.workspace.id)
        assertEquals(11_234.5, absolute(AxisId.X), 1e-9)
        sheet.close()
    }

    @Test
    fun theDeleteRulesKeepTheLastWorkspaceAndRemoveTheImageFile() = scope.runTest {
        harness.start(mill)
        val services = harness.services
        val only = harness.readout.workspace()
        assertEquals(
            "The last workspace can't be deleted.",
            WorkspacesPresenter.delete(services, only).loadString(TestEnvironment),
        )
        val second = (harness.store.machines.createWorkspace(harness.profile.id, "Second") as org.superdro.core.store.StoreResult.Ok).value
        harness.store.storeIO.files["images/second.jpg"] = byteArrayOf(1, 2, 3)
        harness.store.machines.setReferenceImage(second.id, ReferenceImage(fileName = "images/second.jpg"))
        assertEquals("Workspace Second is deleted.", WorkspacesPresenter.delete(services, second.copy(name = "Second")).loadString(TestEnvironment))
        assertFalse("images/second.jpg" in harness.store.storeIO.files)
        // A multi-select delete of all workspaces keeps one.
        val third = (harness.store.machines.createWorkspace(harness.profile.id, "Third") as org.superdro.core.store.StoreResult.Ok).value
        advanceUntilIdle()
        val sheet = sheet()
        runCurrent()
        sheet.onToggleSelected(only.id)
        sheet.onToggleSelected(third.id)
        sheet.onDeleteSelectedRequest()
        assertTrue(sheet.fields.value.confirmDeleteSelected)
        sheet.onDeleteSelectedConfirm()
        runCurrent()
        assertEquals(1, sheet.uiState.value.rows.size)
        assertEquals("The last workspace can't be deleted.", messages.last().loadString(TestEnvironment))
        sheet.close()
    }

    @Test
    fun theQuickCaptureStoresTheMachinePositionWithoutTheToolOffset() = scope.runTest {
        harness.start(mill, states = mapOf(AxisId.X to AxisState(toolOffset = 5_000.0, toolOffsetActive = true, absoluteOffset = 777.0)))
        harness.inputs(AxisId.X to 152_400.0, AxisId.Y to 76_200.0, AxisId.Z to -12_700.0)
        val panel = MachineDatumPanelPresenter(harness.profile.id, harness.services, harness.presenter, harness.tab.undo, messages::add)
        runCurrent()
        panel.onQuickCapture()
        runCurrent()
        val datum = harness.store.machines.machineDatums(harness.profile.id).first().single()
        assertEquals("Datum 1", datum.name)
        assertEquals(152_400.0, datum.x!!, 1e-9)
        assertEquals(76_200.0, datum.y!!, 1e-9)
        assertEquals(-12_700.0, datum.z!!, 1e-9)
        assertNull(datum.a)
        assertEquals("Machine datum Datum 1 is stored.", messages.last().loadString(TestEnvironment))
        panel.onQuickCapture()
        runCurrent()
        assertEquals(listOf("Datum 1", "Datum 2"), harness.store.machines.machineDatums(harness.profile.id).first().map { it.name })
        assertEquals(listOf("X 152.400", "Y 76.200", "Z -12.700"), panel.uiState.value.rows.first().position.map { it.loadString(TestEnvironment) })
        panel.close()
    }

    @Test
    fun aMachineDatumStoresAWhenTheAngularAxisIsOn() = scope.runTest {
        harness.start(mill.copy(angular = AngularAxisConfig(enabled = true)))
        harness.inputs(AxisId.X to 1_000.0, AxisId.Y to 2_000.0, AxisId.Z to 3_000.0, AxisId.Angular to 90.0 * 3600.0)
        val panel = MachineDatumPanelPresenter(harness.profile.id, harness.services, harness.presenter, harness.tab.undo, messages::add)
        runCurrent()
        panel.onQuickCapture()
        runCurrent()
        assertEquals(324_000.0, harness.store.machines.machineDatums(harness.profile.id).first().single().a!!, 1e-9)
        panel.close()
    }

    @Test
    fun theApplyOfTheWorkedExampleListsTheShiftsAndHasUndo() = scope.runTest {
        // The machine position (160, 80, 0) mm and the absolute position (10, 5, 20) mm.
        harness.start(
            mill,
            states = mapOf(
                AxisId.X to AxisState(absoluteOffset = -150_000.0),
                AxisId.Y to AxisState(absoluteOffset = -75_000.0),
                AxisId.Z to AxisState(absoluteOffset = 20_000.0),
            ),
        )
        harness.inputs(AxisId.X to 160_000.0, AxisId.Y to 80_000.0, AxisId.Z to 0.0)
        harness.store.machines.saveMachineDatum(harness.profile.id, MachineDatum(id = "d", name = "Datum 1", x = 152_400.0, y = 76_200.0, z = -12_700.0))
        advanceUntilIdle()
        val panel = MachineDatumPanelPresenter(harness.profile.id, harness.services, harness.presenter, harness.tab.undo, messages::add)
        runCurrent()
        assertEquals(10_000.0, absolute(AxisId.X), 1e-9)
        panel.onTap("d")
        runCurrent()
        val apply = assertNotNull(panel.uiState.value.apply)
        assertEquals(listOf("X -2.400 mm", "Y -1.200 mm", "Z -7.300 mm"), apply.shifts.map { it.loadString(TestEnvironment) })
        panel.onApplyConfirm()
        runCurrent()
        assertEquals(7_600.0, absolute(AxisId.X), 1e-9)
        assertEquals(3_800.0, absolute(AxisId.Y), 1e-9)
        assertEquals(12_700.0, absolute(AxisId.Z), 1e-9)
        assertNotNull(harness.presenter.uiState.value.undo)
        // The no-op test: the origin is at the datum now.
        panel.onTap("d")
        runCurrent()
        assertNull(panel.uiState.value.apply)
        assertEquals("Nothing to do: the absolute origin is at Datum 1 already.", messages.last().loadString(TestEnvironment))
        panel.close()
    }

    @Test
    fun theDeleteHasUndoAndTheListHasTheNaturalOrder() = scope.runTest {
        harness.start(mill)
        listOf("Datum 10", "Datum 2", "vise jaw").forEach { name ->
            harness.store.machines.saveMachineDatum(harness.profile.id, MachineDatum(id = name, name = name, x = 1.0))
        }
        advanceUntilIdle()
        val panel = MachineDatumPanelPresenter(harness.profile.id, harness.services, harness.presenter, harness.tab.undo, messages::add)
        runCurrent()
        assertEquals(listOf("Datum 2", "Datum 10", "vise jaw"), panel.uiState.value.rows.map { it.name })
        panel.onDelete("Datum 2")
        runCurrent()
        assertEquals(listOf("Datum 10", "vise jaw"), panel.uiState.value.rows.map { it.name })
        val offer = assertNotNull(harness.tab.undo.pending.value)
        harness.tab.undo.undo(offer.id)
        runCurrent()
        assertEquals(listOf("Datum 2", "Datum 10", "vise jaw"), panel.uiState.value.rows.map { it.name })
        assertEquals("Datum 3", MachineDatumPanelPresenter.nextName(listOf(MachineDatum(name = "datum 1"), MachineDatum(name = "Datum 2"))))
        panel.close()
    }

    @Test
    fun theEditSheetChecksTheNameAndKeepsTheLatheRadius() = scope.runTest {
        harness.start(MachineProfile.create(MachineType.Lathe, "Lathe").copy(metricDefault = true))
        harness.inputs(AxisId.X to 10_000.0, AxisId.Z to -5_000.0)
        val edit = MachineDatumEditPresenter(harness.profile.id, null, harness.services, harness.presenter.frame, harness.presenter::positionUnavailable)
        advanceUntilIdle()
        val state = edit.uiState.value
        assertEquals("Datum 1", state.name)
        assertEquals(listOf(AxisId.X, AxisId.Z), state.axes)
        assertTrue(state.xDiameter)
        // The X field shows the diameter of the machine position.
        assertEquals("20", state.x.text)
        edit.onName("")
        assertFalse(edit.save())
        assertEquals("The name must have 1 to 15 characters.", edit.uiState.value.nameError!!.loadString(TestEnvironment))
        edit.onName("x".repeat(16))
        assertFalse(edit.save())
        edit.onName("Chuck face")
        edit.onText(AxisId.X, "30")
        assertTrue(edit.save())
        advanceUntilIdle()
        val datum = harness.store.machines.machineDatums(harness.profile.id).first().single()
        assertEquals("Chuck face", datum.name)
        assertEquals(15_000.0, datum.x!!, 1e-9)
        assertEquals(-5_000.0, datum.z!!, 1e-9)
        assertNull(datum.y)
        edit.close()
    }

    @Test
    fun theEditSheetNeedsOneValue() = scope.runTest {
        harness.start(mill, state = org.superdro.core.connection.SessionState.Disconnected)
        val edit = MachineDatumEditPresenter(harness.profile.id, null, harness.services, harness.presenter.frame, harness.presenter::positionUnavailable)
        advanceUntilIdle()
        assertEquals("", edit.uiState.value.x.text)
        assertFalse(edit.save())
        assertEquals("Enter a value for one or more axes.", edit.uiState.value.error!!.loadString(TestEnvironment))
        edit.onText(AxisId.Y, "2+")
        assertFalse(edit.save())
        assertEquals("Enter a valid number.", edit.uiState.value.fieldErrors.getValue(AxisId.Y).loadString(TestEnvironment))
        edit.close()
    }
}
