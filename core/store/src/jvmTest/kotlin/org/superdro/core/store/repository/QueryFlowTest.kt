package org.superdro.core.store.repository

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.SubDatum
import org.superdro.core.store.StoreFixture
import org.superdro.core.store.valueOrNull

/**
 * A query flow gives the current value, and a new value after a write on the writer.
 */
class QueryFlowTest {

    private val scope = TestScope()
    private val store = StoreFixture(scope)

    @AfterTest
    fun close() = store.close()

    @Test
    fun theProfileListFollowsTheWrites() = scope.runTest {
        val seen = mutableListOf<List<String>>()
        val job = launch(store.dispatcher) { store.machines.profiles().collect { list -> seen += list.map { it.name } } }
        advanceUntilIdle()
        val mill = store.machines.createProfile(MachineProfile.create(name = "Mill")).valueOrNull()!!
        advanceUntilIdle()
        store.machines.createProfile(MachineProfile.create(name = "Lathe"))
        advanceUntilIdle()
        store.machines.renameProfile(mill.id, "Bridgeport")
        advanceUntilIdle()
        store.machines.deleteProfile(mill.id, deleteTools = true)
        advanceUntilIdle()
        job.cancel()
        assertEquals(listOf(emptyList(), listOf("Mill"), listOf("Lathe", "Mill"), listOf("Bridgeport", "Lathe"), listOf("Lathe")), seen)
    }

    @Test
    fun theWorkspaceFlowSeesEachTable() = scope.runTest {
        val profile = store.machines.createProfile(MachineProfile.create(name = "Mill")).valueOrNull()!!
        val id = profile.activeWorkspaceId
        val seen = mutableListOf<Pair<Int, Double>>()
        val job = launch(store.dispatcher) {
            store.machines.activeWorkspace(profile.id).collect { workspace ->
                seen += workspace!!.subDatums.size to workspace.axisStates.getValue(AxisId.X).absoluteOffset
            }
        }
        advanceUntilIdle()
        store.machines.addSubDatum(id, SubDatum(x = 1.0))
        advanceUntilIdle()
        store.machines.setAxisState(id, AxisId.X, AxisState(absoluteOffset = 5.0))
        advanceUntilIdle()
        assertEquals(listOf(0 to 0.0, 1 to 0.0, 1 to 5.0), seen)

        // A select gives the other workspace.
        val second = store.machines.createWorkspace(profile.id, "B").valueOrNull()!!
        advanceUntilIdle()
        store.machines.selectWorkspace(profile.id, second.id)
        advanceUntilIdle()
        job.cancel()
        assertEquals(0 to 0.0, seen.last())
    }
}
