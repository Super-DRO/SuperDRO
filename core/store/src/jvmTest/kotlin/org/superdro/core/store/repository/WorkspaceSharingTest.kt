package org.superdro.core.store.repository

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.SubDatum
import org.superdro.core.store.StoreFixture
import org.superdro.core.store.inMemoryDriver
import org.superdro.core.store.valueOrNull

/** The collectors of one workspace share one read. */
class WorkspaceSharingTest {

    private companion object {
        /** The select of the guides: a read of the full workspace. */
        const val GUIDE_READ = "FROM guide WHERE workspace_id"
    }

    private val scope = TestScope()
    private val driver = CountingDriver(inMemoryDriver())
    private val store = StoreFixture(scope, driver)

    @AfterTest
    fun close() = store.close()

    @Test
    fun fourCollectorsOfTheActiveWorkspaceShareOneRead() = scope.runTest {
        val profile = store.machines.createProfile(MachineProfile.create(name = "Mill")).valueOrNull()!!
        val id = profile.activeWorkspaceId
        val seen = List(4) { mutableListOf<Int>() }
        val jobs = seen.map { list ->
            launch(store.dispatcher) { store.machines.activeWorkspace(profile.id).collect { workspace -> list += workspace!!.subDatums.size } }
        }
        advanceUntilIdle()
        assertEquals(1, driver.count(GUIDE_READ), "One read for four collectors.")

        driver.clear()
        store.machines.addSubDatum(id, SubDatum(x = 1.0))
        advanceUntilIdle()
        assertEquals(1, driver.count(GUIDE_READ), "One read after a write.")
        seen.forEach { list -> assertEquals(listOf(0, 1), list) }

        // A collector that comes later gets the last value immediately, with no read.
        driver.clear()
        val late = mutableListOf<Int>()
        val lateJob = launch(store.dispatcher) { store.machines.activeWorkspace(profile.id).collect { workspace -> late += workspace!!.subDatums.size } }
        advanceUntilIdle()
        assertEquals(listOf(1), late)
        assertEquals(0, driver.count(GUIDE_READ))
        (jobs + lateJob).forEach { job -> job.cancel() }
        advanceUntilIdle()

        // With no collector, the shared flow stops and forgets its value. The next collector reads again.
        store.machines.addSubDatum(id, SubDatum(x = 2.0))
        advanceUntilIdle()
        driver.clear()
        val next = mutableListOf<Int>()
        val nextJob = launch(store.dispatcher) { store.machines.activeWorkspace(profile.id).collect { workspace -> next += workspace!!.subDatums.size } }
        advanceUntilIdle()
        assertEquals(listOf(2), next)
        assertEquals(1, driver.count(GUIDE_READ))
        nextJob.cancel()
    }

    @Test
    fun writeWorkspaceWritesWithNoReadOfTheFullWorkspace() = scope.runTest {
        val profile = store.machines.createProfile(MachineProfile.create(name = "Mill")).valueOrNull()!!
        val id = profile.activeWorkspaceId
        driver.clear()
        assertTrue(store.machines.writeWorkspace(id) { workspace -> workspace.copy(axisStates = workspace.axisStates + (AxisId.X to AxisState(absoluteOffset = 7.0))) })
        assertEquals(0, driver.count(GUIDE_READ))
        assertEquals(7.0, store.machines.loadWorkspace(id)!!.axisStates.getValue(AxisId.X).absoluteOffset)
        assertEquals(false, store.machines.writeWorkspace("missing") { it })
    }
}
