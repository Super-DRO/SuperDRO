package org.superdro.core.store.repository

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.Drawing
import org.superdro.core.model.DrawingShape
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.MeasurementLogEntry
import org.superdro.core.model.Plane
import org.superdro.core.model.ProximityTarget
import org.superdro.core.model.ReferenceImage
import org.superdro.core.model.SubDatum
import org.superdro.core.model.ThreadSpec
import org.superdro.core.model.Workspace
import org.superdro.core.store.StoreFixture
import org.superdro.core.store.StoreRefusal
import org.superdro.core.store.StoreResult
import org.superdro.core.store.refusalOrNull
import org.superdro.core.store.valueOrNull

class WorkspaceRepositoryTest {

    private val scope = TestScope()
    private val store = StoreFixture(scope)

    @AfterTest
    fun close() = store.close()

    private suspend fun newProfile(type: MachineType = MachineType.VerticalMill, name: String = "Mill"): MachineProfile =
        assertIs<StoreResult.Ok<MachineProfile>>(store.machines.createProfile(MachineProfile.create(type, name))).value

    @Test
    fun theFirstWorkspaceIsTheDefaultWorkspace() = scope.runTest {
        val lathe = newProfile(MachineType.Lathe, "Lathe")
        val workspace = store.machines.loadFullProfile(lathe.id)!!.workspaces.single()
        assertEquals("Default Workspace", workspace.name)
        assertEquals(Plane.ZX, workspace.plane)
        assertTrue(workspace.axisStates.getValue(AxisId.X).diameterMode)
        assertFalse(workspace.axisStates.getValue(AxisId.Z).diameterMode)
    }

    @Test
    fun aNewWorkspaceNeedsAUniqueNameThatIsNotBlank() = scope.runTest {
        val profile = newProfile()
        val blank = store.machines.createWorkspace(profile.id, "   ")
        assertEquals(StoreRefusal.WorkspaceNameBlank, blank.refusalOrNull())
        assertEquals("Workspace name can't be blank.", blank.refusalOrNull()!!.message)

        val duplicate = store.machines.createWorkspace(profile.id, "default workspace")
        assertEquals(StoreRefusal.WorkspaceNameExists, duplicate.refusalOrNull())
        assertEquals("A workspace with this name already exists.", duplicate.refusalOrNull()!!.message)

        val second = store.machines.createWorkspace(profile.id, " Second op ").valueOrNull()!!
        assertEquals("Second op", second.name)
        // A name of a different profile is not a duplicate.
        val other = newProfile(name = "Other")
        assertIs<StoreResult.Ok<Workspace>>(store.machines.createWorkspace(other.id, "Second op"))
        // The new workspace does not become active.
        assertEquals(profile.activeWorkspaceId, store.machines.loadProfile(profile.id)!!.activeWorkspaceId)
        assertEquals(listOf("Default Workspace", "Second op"), store.machines.loadFullProfile(profile.id)!!.workspaces.map { it.name })
    }

    @Test
    fun aSelectStoresTheOldAxisStateAndLoadsTheNewState() = scope.runTest {
        val profile = newProfile()
        val first = profile.activeWorkspaceId
        val second = store.machines.createWorkspace(profile.id, "Second op").valueOrNull()!!
        store.machines.updateWorkspace(second.id) { it.copy(activeToolId = "tool-1", proximity = ProximityTarget(AxisId.Z, -5000.0, absolute = true)) }
        store.machines.setAxisState(second.id, AxisId.X, AxisState(absoluteOffset = -12700.0, relative = true))

        val held = mapOf(AxisId.X to AxisState(absoluteOffset = 25400.0, incrementalOffset = 100.0))
        val loaded = store.machines.selectWorkspace(profile.id, second.id, currentAxisStates = held).valueOrNull()!!

        assertEquals(second.id, store.machines.loadProfile(profile.id)!!.activeWorkspaceId)
        assertEquals("tool-1", loaded.activeToolId)
        assertEquals(ProximityTarget(AxisId.Z, -5000.0, absolute = true), loaded.proximity)
        assertEquals(AxisState(absoluteOffset = -12700.0, relative = true), loaded.axisStates.getValue(AxisId.X))
        assertEquals(AxisState(absoluteOffset = 25400.0, incrementalOffset = 100.0), store.machines.loadWorkspace(first)!!.axisStates.getValue(AxisId.X))

        assertEquals(StoreRefusal.NotFound, store.machines.selectWorkspace(profile.id, "missing").refusalOrNull())
    }

    @Test
    fun theLastWorkspaceCannotBeDeleted() = scope.runTest {
        val profile = newProfile()
        val result = store.machines.deleteWorkspace(profile.activeWorkspaceId)
        assertEquals(StoreRefusal.LastWorkspace, result.refusalOrNull())
        assertEquals("The last workspace can't be deleted.", result.refusalOrNull()!!.message)
        assertEquals(1L, store.count("workspace"))
    }

    @Test
    fun aMultiSelectDeleteOfAllWorkspacesKeepsOneAndDoesNotCrash() = scope.runTest {
        val profile = newProfile()
        val second = store.machines.createWorkspace(profile.id, "B").valueOrNull()!!
        val third = store.machines.createWorkspace(profile.id, "C").valueOrNull()!!
        store.machines.selectWorkspace(profile.id, third.id)

        val result = store.machines.deleteWorkspaces(profile.id, listOf(profile.activeWorkspaceId, second.id, third.id))

        assertEquals(StoreRefusal.LastWorkspace, result.refusal)
        assertEquals(third.id, result.keptId)
        assertEquals(setOf(profile.activeWorkspaceId, second.id), result.deletedIds.toSet())
        assertEquals(listOf("C"), store.machines.loadFullProfile(profile.id)!!.workspaces.map { it.name })
    }

    @Test
    fun aDeleteOfTheActiveWorkspaceSelectsAWorkspaceThatStays() = scope.runTest {
        val profile = newProfile()
        val second = store.machines.createWorkspace(profile.id, "B").valueOrNull()!!
        val result = store.machines.deleteWorkspaces(profile.id, listOf(profile.activeWorkspaceId))
        assertNull(result.refusal)
        assertEquals(second.id, result.activeWorkspaceId)
        assertEquals(second.id, store.machines.loadProfile(profile.id)!!.activeWorkspaceId)
    }

    @Test
    fun aWorkspaceDeleteRemovesItsDataAndItsImageFile() = scope.runTest {
        val profile = newProfile()
        val workspace = store.machines.createWorkspace(profile.id, "B").valueOrNull()!!
        store.storeIO.putText("images/b.jpg", "jpeg")
        store.machines.setReferenceImage(workspace.id, ReferenceImage(fileName = "images/b.jpg"))
        store.machines.addSubDatum(workspace.id, SubDatum(tags = listOf("x"), x = 1.0))
        store.drawings.replaceDrawing(Drawing(workspaceId = workspace.id, shapes = listOf(DrawingShape())))
        store.logs.append(workspace.id, MeasurementLogEntry(verb = "point"))
        store.machines.saveThread(workspace.id, ThreadSpec(size = "1/4-20"))

        assertIs<StoreResult.Ok<Unit>>(store.machines.deleteWorkspace(workspace.id))

        for (table in listOf("sub_datum", "sub_datum_tag", "drawing", "drawing_shape", "measurement_log", "thread_spec")) {
            assertEquals(0L, store.count(table), table)
        }
        assertEquals(6L, store.count("axis_state"))
        assertFalse(store.storeIO.exists("images/b.jpg"))
    }

    @Test
    fun aNewReferenceImageRemovesTheOldFile() = scope.runTest {
        val profile = newProfile()
        store.storeIO.putText("images/old.jpg", "old")
        store.storeIO.putText("images/new.jpg", "new")
        store.machines.setReferenceImage(profile.activeWorkspaceId, ReferenceImage(fileName = "images/old.jpg"))
        store.machines.setReferenceImage(profile.activeWorkspaceId, ReferenceImage(fileName = "images/new.jpg", alpha = 127))
        assertFalse(store.storeIO.exists("images/old.jpg"))
        assertTrue(store.storeIO.exists("images/new.jpg"))
        assertEquals(127, store.machines.loadWorkspace(profile.activeWorkspaceId)!!.referenceImage!!.alpha)
        // A change of the matrix only keeps the file.
        store.machines.updateWorkspace(profile.activeWorkspaceId) { it.copy(referenceImage = it.referenceImage!!.copy(matrix = List(9) { 2.0 })) }
        assertTrue(store.storeIO.exists("images/new.jpg"))
        store.machines.setReferenceImage(profile.activeWorkspaceId, null)
        assertFalse(store.storeIO.exists("images/new.jpg"))
    }

    @Test
    fun theWorkspaceStateIsTyped() = scope.runTest {
        val profile = newProfile()
        val id = profile.activeWorkspaceId
        store.machines.updateWorkspace(id) { workspace ->
            workspace.copy(
                activeToolId = "a:b:c",
                activeSubDatumId = "sub:1",
                toolXUm = 6350.0,
                toothCount = 3,
                graphical = workspace.graphical.copy(snapToGuide = true, automaticZero = true),
                plane = Plane.YZ,
                axisStates = workspace.axisStates + (AxisId.Angular to AxisState(divisionUnits = true, divisionsPerRev = 24)),
            )
        }
        val stored = store.machines.loadWorkspace(id)!!
        assertEquals("a:b:c", stored.activeToolId)
        assertEquals("sub:1", stored.activeSubDatumId)
        assertEquals(6350.0, stored.toolXUm)
        assertEquals(3, stored.toothCount)
        assertTrue(stored.graphical.snapToGuide && stored.graphical.automaticZero)
        assertEquals(Plane.YZ, stored.plane)
        assertEquals(24, stored.axisStates.getValue(AxisId.Angular).divisionsPerRev)
    }

    @Test
    fun aRenameUsesTheNameRules() = scope.runTest {
        val profile = newProfile()
        val second = store.machines.createWorkspace(profile.id, "B").valueOrNull()!!
        assertEquals(StoreRefusal.WorkspaceNameExists, store.machines.renameWorkspace(second.id, "DEFAULT WORKSPACE").refusalOrNull())
        assertEquals(StoreRefusal.WorkspaceNameBlank, store.machines.renameWorkspace(second.id, "").refusalOrNull())
        assertEquals("Fixture 2", store.machines.renameWorkspace(second.id, "Fixture 2").valueOrNull()!!.name)
    }
}
