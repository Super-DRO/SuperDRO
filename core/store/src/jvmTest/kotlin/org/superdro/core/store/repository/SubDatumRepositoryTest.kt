package org.superdro.core.store.repository

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.AxisId
import org.superdro.core.model.Guide
import org.superdro.core.model.MachineDatum
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.SubDatum
import org.superdro.core.model.SubDatumGroupKind
import org.superdro.core.store.StoreFixture
import org.superdro.core.store.StoreRefusal
import org.superdro.core.store.StoreResult
import org.superdro.core.store.files.FIXED_TIME
import org.superdro.core.store.files.ImportOperation
import org.superdro.core.store.files.TestEvaluator
import org.superdro.core.store.files.sequentialIds
import org.superdro.core.store.files.subdatum.SubDatumCsvImporter
import org.superdro.core.store.files.subdatum.SubDatumImportContext
import org.superdro.core.store.refusalOrNull
import org.superdro.core.store.valueOrNull

class SubDatumRepositoryTest {

    private val scope = TestScope()
    private val store = StoreFixture(scope)

    @AfterTest
    fun close() = store.close()

    private suspend fun workspaceId(): String =
        assertIs<StoreResult.Ok<MachineProfile>>(store.machines.createProfile(MachineProfile.create(name = "Mill"))).value.activeWorkspaceId

    private suspend fun names(workspaceId: String) = store.machines.loadWorkspace(workspaceId)!!.subDatums.map { it.name }

    @Test
    fun automaticNamesUseTheLastNumberOfTheWorkspace() = scope.runTest {
        val id = workspaceId()
        store.machines.addSubDatum(id, SubDatum(x = 1.0))
        store.machines.addSubDatum(id, SubDatum(name = "Corner", x = 2.0))
        store.machines.addSubDatum(id, SubDatum(x = 3.0))
        assertEquals(listOf("Point 1", "Corner", "Point 3"), names(id))
        assertEquals(3, store.machines.loadWorkspace(id)!!.lastOrdinal)

        // A group of 3 points uses the numbers 4 to 6 and keeps 6.
        val group = store.machines.addSubDatums(id, List(3) { SubDatum(groupKind = SubDatumGroupKind.Pattern) }, namePrefix = "Grid")
        assertEquals(listOf("Grid - 4", "Grid - 5", "Grid - 6"), group.valueOrNull()!!.map { it.name })
        assertEquals(6, store.machines.loadWorkspace(id)!!.lastOrdinal)
        store.machines.addSubDatum(id, SubDatum(x = 7.0))
        assertEquals("Point 7", names(id).last())
    }

    @Test
    fun theCounterStartsAtTheCountOfAnImportAndGoesTo0AfterTheLastDelete() = scope.runTest {
        val id = workspaceId()
        // The CSV import does not change the counter.
        store.machines.insertSubDatums(id, listOf(SubDatum(name = "A", x = 1.0), SubDatum(name = "B", x = 2.0)))
        assertEquals(0, store.machines.loadWorkspace(id)!!.lastOrdinal)
        assertEquals("Point 3", store.machines.addSubDatum(id, SubDatum(x = 3.0)).valueOrNull()!!.name)

        store.machines.deleteAllSubDatums(id)
        assertEquals(0, store.machines.loadWorkspace(id)!!.lastOrdinal)
        assertEquals("Point 1", store.machines.addSubDatum(id, SubDatum(x = 1.0)).valueOrNull()!!.name)
    }

    @Test
    fun allTagsOfASubDatumStay() = scope.runTest {
        val id = workspaceId()
        val point = store.machines.addSubDatum(id, SubDatum(name = "Hole", tags = listOf("holes", "left", "deep"), x = 1.0)).valueOrNull()!!
        assertEquals(listOf("holes", "left", "deep"), store.machines.loadWorkspace(id)!!.subDatums.single().tags)

        // An edit of the name keeps the other tags.
        store.machines.updateSubDatum(point.copy(name = "Hole 2"))
        assertEquals(listOf("holes", "left", "deep"), store.machines.loadWorkspace(id)!!.subDatums.single().tags)

        store.machines.setTags(listOf(point.id), listOf("right"))
        assertEquals(listOf("right"), store.machines.loadWorkspace(id)!!.subDatums.single().tags)
        store.machines.setTags(listOf(point.id), emptyList())
        assertEquals(emptyList(), store.machines.loadWorkspace(id)!!.subDatums.single().tags)
    }

    @Test
    fun aSubDatumKeepsAllItsValues() = scope.runTest {
        val id = workspaceId()
        val point = SubDatum(
            name = "Thread start",
            note = "a note",
            x = 6350.0,
            y = null,
            z = -1000.0,
            a = 3600.0,
            groupId = 7L,
            groupKind = SubDatumGroupKind.Thread,
            groupRef = "thread-1",
        )
        store.machines.insertSubDatums(id, listOf(point))
        assertEquals(point, store.machines.loadWorkspace(id)!!.subDatums.single())
    }

    @Test
    fun aDeleteOfTheActiveSubDatumClearsIt() = scope.runTest {
        val id = workspaceId()
        val a = store.machines.addSubDatum(id, SubDatum(x = 1.0)).valueOrNull()!!
        val b = store.machines.addSubDatum(id, SubDatum(x = 2.0)).valueOrNull()!!
        store.machines.updateWorkspace(id) { it.copy(activeSubDatumId = a.id) }
        val deleted = store.machines.deleteSubDatums(id, listOf(a.id))
        assertEquals(listOf(a), deleted)
        assertNull(store.machines.loadWorkspace(id)!!.activeSubDatumId)
        assertEquals(listOf(b.id), store.machines.loadWorkspace(id)!!.subDatums.map { it.id })
        // The counter stays while points stay.
        assertEquals(2, store.machines.loadWorkspace(id)!!.lastOrdinal)
    }

    @Test
    fun guidesAreTheirOwnEntity() = scope.runTest {
        val id = workspaceId()
        val guide = Guide(axis = AxisId.Y, valueUm = 30000.0)
        store.machines.saveGuide(id, guide)
        store.machines.saveGuide(id, guide.copy(valueUm = 20000.0))
        assertEquals(listOf(guide.copy(valueUm = 20000.0)), store.machines.loadWorkspace(id)!!.guides)
        assertEquals(0, store.machines.loadWorkspace(id)!!.subDatums.size)
        store.machines.deleteGuides(listOf(guide.id))
        assertEquals(0L, store.count("guide"))
        assertEquals(StoreRefusal.NotFound, store.machines.saveGuide("missing", guide).refusalOrNull())
    }

    @Test
    fun machineDatumsUseTheNaturalOrder() = scope.runTest {
        val profileId = store.machines.createProfile(MachineProfile.create(name = "Mill")).valueOrNull()!!.id
        listOf("Datum 10", "datum 2", "Datum 1", "Alpha").forEach { name ->
            store.machines.saveMachineDatum(profileId, MachineDatum(name = name, x = 1.0, a = 7200.0))
        }
        assertEquals(listOf("Alpha", "Datum 1", "datum 2", "Datum 10"), store.machines.loadFullProfile(profileId)!!.machineDatums.map { it.name })
        assertEquals(7200.0, store.machines.loadFullProfile(profileId)!!.machineDatums.first().a)
    }

    @Test
    fun theReplaceAllOfAnImportAndTheUndoOfADeleteAreOneTransactionEach() = scope.runTest {
        val id = workspaceId()
        store.machines.addSubDatum(id, SubDatum(name = "Old", x = 1.0))
        val guide = Guide(axis = AxisId.X, valueUm = 5_000.0)
        store.machines.saveGuide(id, guide)
        val current = store.machines.loadWorkspace(id)!!
        val context = SubDatumImportContext("p1", MachineType.VerticalMill, true, current.subDatums, current.guides)
        val importer = SubDatumCsvImporter(TestEvaluator::evaluate, now = { FIXED_TIME }, ids = sequentialIds())
        val plan = importer.read("name,x,y\nA,1.0,2.0\nB,3.0,4.0\n", context).plan(ImportOperation.ReplaceAll)
        assertIs<StoreResult.Ok<Unit>>(store.machines.applySubDatumImport(id, plan))
        val imported = store.machines.loadWorkspace(id)!!
        assertEquals(setOf("A", "B"), imported.subDatums.map { it.name }.toSet())
        assertEquals(emptyList(), imported.guides, "Replace all deletes the guides too")
        assertEquals(StoreRefusal.NotFound, store.machines.applySubDatumImport("no-such-workspace", plan).refusalOrNull())

        // The undo of a delete: the point, the guide, the active point, and the ordinal come back.
        val point = imported.subDatums.first { it.name == "A" }
        store.machines.saveGuide(id, guide)
        store.machines.updateWorkspace(id) { workspace -> workspace.copy(activeSubDatumId = point.id, lastOrdinal = 9) }
        val deleted = store.machines.deleteSubDatums(id, listOf(point.id))
        store.machines.deleteGuides(listOf(guide.id))
        assertNull(store.machines.loadWorkspace(id)!!.activeSubDatumId)
        assertIs<StoreResult.Ok<Unit>>(store.machines.restoreSubDatums(id, deleted, listOf(guide), point.id, lastOrdinal = 12))
        val restored = store.machines.loadWorkspace(id)!!
        assertEquals(setOf("A", "B"), restored.subDatums.map { it.name }.toSet())
        assertEquals(listOf(guide.id), restored.guides.map { it.id })
        assertEquals(point.id, restored.activeSubDatumId)
        assertEquals(12, restored.lastOrdinal)
        // A lower ordinal does not go back.
        store.machines.restoreSubDatums(id, emptyList(), emptyList(), null, lastOrdinal = 3)
        assertEquals(12, store.machines.loadWorkspace(id)!!.lastOrdinal)
    }
}

