package org.superdro.validation.store

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.CameraSelection
import org.superdro.core.model.Drawing
import org.superdro.core.model.MeasurementLogEntry
import org.superdro.core.model.GraphicalSwitches
import org.superdro.core.model.MachineDatum
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.ProbeType
import org.superdro.core.model.ProximityTarget
import org.superdro.core.model.ReferenceImage
import org.superdro.core.model.SubDatum
import org.superdro.core.model.ThreadSpec
import org.superdro.core.model.Tool
import org.superdro.core.model.ToolKind
import org.superdro.core.store.DELETE_PROFILE_TOOLS_QUESTION
import org.superdro.core.store.StoreRefusal
import org.superdro.core.store.refusalOrNull
import org.superdro.core.store.repository.MismatchDisposal
import org.superdro.core.store.repository.MismatchKind
import org.superdro.core.store.valueOrNull

/**
 * The workspace rules and the profile rules.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceAndProfileTest {

    private val harnesses = mutableListOf<StoreHarness>()

    private fun TestScope.store(): StoreHarness = StoreHarness(StandardTestDispatcher(testScheduler)).also(harnesses::add)

    @AfterTest
    fun closeAll() {
        harnesses.forEach(StoreHarness::close)
    }

    private suspend fun StoreHarness.newProfile(name: String, type: MachineType = MachineType.VerticalMill): MachineProfile =
        assertNotNull(machines.createProfile(MachineProfile.create(type, name)).valueOrNull())

    private suspend fun StoreHarness.firstWorkspaceId(profileId: String): String = machines.workspaces(profileId).first().first().id

    @Test
    fun a_new_profile_has_one_workspace_with_the_name_default_workspace() = runTest {
        val store = store()
        val profile = store.newProfile("Mill")
        assertEquals(listOf("Default Workspace"), store.machines.workspaces(profile.id).first().map { it.name })
    }

    @Test
    fun a_blank_or_duplicate_workspace_name_is_refused_with_the_texts() = runTest {
        val store = store()
        val profile = store.newProfile("Mill")
        val blank = store.machines.createWorkspace(profile.id, "  ").refusalOrNull()
        assertEquals("Workspace name can't be blank.", blank?.message)
        assertNotNull(store.machines.createWorkspace(profile.id, "Fixture A").valueOrNull())
        val duplicate = store.machines.createWorkspace(profile.id, "Fixture A").refusalOrNull()
        assertEquals("A workspace with this name already exists.", duplicate?.message)
        // The name is unique in the profile only.
        val other = store.newProfile("Other")
        assertNotNull(store.machines.createWorkspace(other.id, "Fixture A").valueOrNull())
    }

    @Test
    fun select_stores_the_old_state_and_loads_the_state_of_the_new_workspace() = runTest {
        val store = store()
        val profile = store.newProfile("Mill")
        val first = store.firstWorkspaceId(profile.id)
        store.machines.updateWorkspace(first) { workspace ->
            workspace.copy(
                activeToolId = "tool-1",
                proximity = ProximityTarget(AxisId.Z, -5000.0, absolute = true),
                graphical = GraphicalSwitches(snapToPoint = true, automaticZero = true),
            )
        }
        val point = assertNotNull(store.machines.addSubDatum(first, SubDatum(x = 1.0)).valueOrNull())
        store.machines.updateWorkspace(first) { it.copy(activeSubDatumId = point.id) }
        val second = assertNotNull(store.machines.createWorkspace(profile.id, "Second").valueOrNull())
        val held = mapOf(AxisId.X to AxisState(absoluteOffset = 1000.0, relative = true, incrementalOffset = -20.0))
        val loaded = assertNotNull(store.machines.selectWorkspace(profile.id, second.id, held).valueOrNull())
        assertEquals(second.id, loaded.id)
        assertEquals(AxisState(), loaded.axisStates[AxisId.X])
        assertNull(loaded.activeToolId)
        assertNull(loaded.proximity)
        assertEquals(GraphicalSwitches(), loaded.graphical)
        assertEquals(second.id, store.machines.loadProfile(profile.id)!!.activeWorkspaceId)
        val old = store.machines.loadWorkspace(first)!!
        assertEquals(held[AxisId.X], old.axisStates[AxisId.X])
        assertEquals("tool-1", old.activeToolId)
        assertEquals(point.id, old.activeSubDatumId)
        assertEquals(ProximityTarget(AxisId.Z, -5000.0, absolute = true), old.proximity)
        assertEquals(GraphicalSwitches(snapToPoint = true, automaticZero = true), old.graphical)
        // Back to the first workspace: its state comes back.
        val back = assertNotNull(store.machines.selectWorkspace(profile.id, first).valueOrNull())
        assertEquals(1000.0, back.axisStates.getValue(AxisId.X).absoluteOffset)
    }

    @Test
    fun the_last_workspace_cannot_be_deleted() = runTest {
        val store = store()
        val profile = store.newProfile("Mill")
        val only = store.firstWorkspaceId(profile.id)
        val refusal = store.machines.deleteWorkspace(only).refusalOrNull()
        assertEquals("The last workspace can't be deleted.", refusal?.message)
        assertEquals(1, store.machines.workspaces(profile.id).first().size)
    }

    @Test
    fun a_multi_select_delete_of_all_workspaces_keeps_one_and_gives_no_exception() = runTest {
        val store = store()
        val profile = store.newProfile("Mill")
        val first = store.firstWorkspaceId(profile.id)
        val second = assertNotNull(store.machines.createWorkspace(profile.id, "B").valueOrNull()).id
        val third = assertNotNull(store.machines.createWorkspace(profile.id, "C").valueOrNull()).id
        store.machines.selectWorkspace(profile.id, second)
        val result = store.machines.deleteWorkspaces(profile.id, listOf(first, second, third))
        assertEquals(StoreRefusal.LastWorkspace, result.refusal)
        val left = store.machines.workspaces(profile.id).first()
        assertEquals(1, left.size)
        assertEquals(left.single().id, store.machines.loadProfile(profile.id)!!.activeWorkspaceId, "the kept workspace is active")
    }

    @Test
    fun a_workspace_delete_and_a_new_image_delete_the_image_file() = runTest {
        val store = store()
        val profile = store.newProfile("Mill")
        val keep = store.firstWorkspaceId(profile.id)
        val other = assertNotNull(store.machines.createWorkspace(profile.id, "With image").valueOrNull()).id
        val images = store.folder.resolve("images").also { it.mkdirs() }
        val firstImage = images.resolve("11111111-1111-1111-1111-111111111111.jpg").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        val secondImage = images.resolve("22222222-2222-2222-2222-222222222222.jpg").also { it.writeBytes(byteArrayOf(4, 5, 6)) }
        store.machines.setReferenceImage(keep, ReferenceImage(fileName = "images/${firstImage.name}"))
        store.machines.setReferenceImage(keep, ReferenceImage(fileName = "images/${secondImage.name}"))
        assertFalse(firstImage.exists(), "a new image deletes the old file")
        assertTrue(secondImage.exists())
        store.machines.setReferenceImage(other, ReferenceImage(fileName = "images/${firstImage.name}"))
        firstImage.writeBytes(byteArrayOf(7))
        store.machines.deleteWorkspace(other)
        assertFalse(firstImage.exists(), "a workspace delete deletes its image file")
    }

    @Test
    fun the_automatic_names_use_the_ordinal_of_the_workspace_and_do_not_repeat() = runTest {
        val store = store()
        val profile = store.newProfile("Mill")
        val workspace = store.firstWorkspaceId(profile.id)
        val one = assertNotNull(store.machines.addSubDatum(workspace, SubDatum(x = 0.0)).valueOrNull())
        assertEquals("Point 1", one.name)
        val group = assertNotNull(store.machines.addSubDatums(workspace, List(3) { SubDatum(x = it.toDouble()) }, namePrefix = "Grid").valueOrNull())
        assertEquals(listOf("Grid - 2", "Grid - 3", "Grid - 4"), group.map(SubDatum::name))
        // The workspace keeps the last number (4), so the next name does not repeat.
        assertEquals(4, store.machines.loadWorkspace(workspace)!!.lastOrdinal)
        assertEquals("Point 5", assertNotNull(store.machines.addSubDatum(workspace, SubDatum(x = 9.0)).valueOrNull()).name)
        assertEquals("Named", assertNotNull(store.machines.addSubDatum(workspace, SubDatum(name = "Named", x = 9.0)).valueOrNull()).name)
        // A delete of all sub-datums sets the counter to 0.
        store.machines.deleteAllSubDatums(workspace)
        assertEquals("Point 1", assertNotNull(store.machines.addSubDatum(workspace, SubDatum(x = 0.0)).valueOrNull()).name)
    }

    @Test
    fun a_delete_gives_the_deleted_points_for_the_undo_and_clears_the_active_point() = runTest {
        val store = store()
        val profile = store.newProfile("Mill")
        val workspace = store.firstWorkspaceId(profile.id)
        val a = assertNotNull(store.machines.addSubDatum(workspace, SubDatum(x = 1.0)).valueOrNull())
        val b = assertNotNull(store.machines.addSubDatum(workspace, SubDatum(x = 2.0)).valueOrNull())
        store.machines.updateWorkspace(workspace) { it.copy(activeSubDatumId = a.id) }
        val deleted = store.machines.deleteSubDatums(workspace, listOf(a.id))
        assertEquals(listOf(a.id), deleted.map(SubDatum::id))
        assertNull(store.machines.loadWorkspace(workspace)!!.activeSubDatumId)
        // The undo writes the deleted points back.
        store.machines.insertSubDatums(workspace, deleted)
        assertEquals(setOf(a.id, b.id), store.machines.subDatums(workspace).first().map(SubDatum::id).toSet())
    }

    @Test
    fun profile_names_are_unique_and_a_profile_can_be_renamed() = runTest {
        val store = store()
        val mill = store.newProfile("Bridgeport")
        val duplicate = store.machines.createProfile(MachineProfile.create(MachineType.Lathe, "Bridgeport")).refusalOrNull()
        assertEquals("A profile with this name already exists.", duplicate?.message)
        val lathe = store.newProfile("South Bend", MachineType.Lathe)
        assertEquals("A profile with this name already exists.", store.machines.renameProfile(lathe.id, "Bridgeport").refusalOrNull()?.message)
        assertEquals("Atlas", store.machines.renameProfile(lathe.id, "Atlas").valueOrNull()?.name)
        assertEquals("Atlas", store.machines.loadProfile(lathe.id)!!.name)
        assertNotNull(store.machines.loadProfile(mill.id))
    }

    @Test
    fun a_copy_duplicates_the_settings_and_the_tools_but_not_the_workspaces() = runTest {
        val store = store()
        val source = store.newProfile("Source")
        store.machines.updateProfile(source.id) { it.copy(x = it.x.copy(label = "Xs"), metricDefault = true) }
        store.tools.addTool(source.id, Tool(name = "Drill", diameterUm = 5000.0))
        val workspace = store.firstWorkspaceId(source.id)
        store.machines.addSubDatum(workspace, SubDatum(x = 1.0))
        store.machines.createWorkspace(source.id, "Second")
        assertEquals("A profile with this name already exists.", store.machines.copyProfile(source.id, "Source").refusalOrNull()?.message)
        val copy = assertNotNull(store.machines.copyProfile(source.id, "Copy").valueOrNull())
        assertEquals("Xs", copy.x.label)
        assertTrue(copy.metricDefault)
        val tools = store.tools.library(copy.id).first()
        assertEquals(listOf("Drill"), tools.map(Tool::name))
        assertFalse(tools.single().id in store.tools.library(source.id).first().map(Tool::id))
        val workspaces = store.machines.workspaces(copy.id).first()
        assertEquals(listOf("Default Workspace"), workspaces.map { it.name })
        assertTrue(store.machines.subDatums(workspaces.single().id).first().isEmpty())
    }

    @Test
    fun a_profile_delete_asks_about_the_tools_and_removes_all_data_of_the_profile() = runTest {
        assertEquals("Delete the tools of this profile too?", DELETE_PROFILE_TOOLS_QUESTION)
        val store = store()
        val keep = store.newProfile("Keep")
        val profile = store.newProfile("Gone")
        val workspace = store.firstWorkspaceId(profile.id)
        store.machines.addSubDatum(workspace, SubDatum(x = 1.0))
        store.machines.saveMachineDatum(profile.id, MachineDatum(name = "Vise", x = 10.0))
        store.machines.saveThread(workspace, ThreadSpec(workspaceId = workspace, tag = "M10"))
        store.tools.addTool(profile.id, Tool(name = "Own tool"))
        store.tools.addTool(keep.id, Tool(name = "Other tool"))
        store.probes.saveProbeProfile(ProbeProfile(profileId = profile.id, type = ProbeType.TouchProbe, name = "Own probe"))
        store.cameras.saveSelection(CameraSelection(profileId = profile.id, cameraKey = "046d:0825", modeKey = "0/1920/1080/333333"))
        store.logs.append(workspace, MeasurementLogEntry(workspaceId = workspace, verb = "distance", title = "D1"))
        store.drawings.replaceDrawing(Drawing(workspaceId = workspace))
        assertEquals(1, store.texts("SELECT id FROM measurement_log").size)
        assertEquals(1, store.texts("SELECT id FROM drawing").size)
        assertTrue(store.machines.deleteProfile(profile.id, deleteTools = true))
        assertEquals(emptyList(), store.texts("SELECT id FROM measurement_log"))
        assertEquals(emptyList(), store.texts("SELECT id FROM drawing"))
        assertNull(store.machines.loadProfile(profile.id))
        assertEquals(listOf("Other tool"), store.texts("SELECT name FROM tool"))
        assertEquals(listOf(store.firstWorkspaceId(keep.id)), store.texts("SELECT id FROM workspace"))
        assertEquals(emptyList(), store.texts("SELECT id FROM sub_datum"))
        assertEquals(emptyList(), store.texts("SELECT id FROM machine_datum"))
        assertEquals(emptyList(), store.texts("SELECT id FROM thread_spec"))
        assertEquals(emptyList(), store.texts("SELECT id FROM probe_profile WHERE profile_id IS NOT NULL"))
        assertTrue(store.cameras.selections(profile.id).first().isEmpty())
        // The six standard tips are for all profiles and stay.
        assertEquals(6, store.probes.standardTips().first().size)
    }

    @Test
    fun a_mill_and_a_horizontal_mill_share_the_mill_tools_in_a_type_change() = runTest {
        val store = store()
        val profile = store.newProfile("Mill")
        store.tools.addTool(profile.id, Tool(name = "End mill", diameterUm = 6000.0))
        val plan = assertNotNull(store.machines.planMachineTypeChange(profile.id, MachineType.HorizontalMill))
        assertTrue(plan.mismatches.none { it.kind == MismatchKind.ProfileTools && it.count > 0 })
        store.machines.changeMachineType(profile.id, MachineType.HorizontalMill)
        assertEquals(MachineType.HorizontalMill, store.machines.loadProfile(profile.id)!!.machineType)
        assertEquals(listOf("End mill"), store.tools.library(profile.id).first().map(Tool::name))
    }

    @Test
    fun a_type_change_lists_the_data_that_does_not_fit_and_archives_it() = runTest {
        val store = store()
        val profile = store.newProfile("Mill")
        store.tools.addTool(profile.id, Tool(name = "End mill", diameterUm = 6000.0))
        val workspace = store.firstWorkspaceId(profile.id)
        store.machines.addSubDatum(workspace, SubDatum(x = 1000.0, y = 2000.0, z = 3000.0))
        val plan = assertNotNull(store.machines.planMachineTypeChange(profile.id, MachineType.Lathe))
        assertEquals(1, plan.mismatches.single { it.kind == MismatchKind.ProfileTools }.count)
        assertEquals(1, plan.mismatches.single { it.kind == MismatchKind.SubDatumYCoordinates }.count)
        assertNotNull(store.machines.changeMachineType(profile.id, MachineType.Lathe, MismatchDisposal.Archive).valueOrNull())
        val lathe = store.machines.loadProfile(profile.id)!!
        assertEquals(MachineType.Lathe, lathe.machineType)
        assertTrue(store.tools.library(profile.id).first().none { it.kind == ToolKind.Mill })
        assertNull(store.machines.subDatums(workspace).first().single().y, "no Y on a lathe")
        assertTrue(store.machines.archivedItems(profile.id).first().isNotEmpty(), "the archive keeps the data")
    }
}
