package org.superdro.validation.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.SuperDroJson
import org.superdro.core.store.StoreResult
import org.superdro.core.store.StoreWriter
import org.superdro.core.store.repository.MachineRepository
import org.superdro.validation.store.StoreHarness

/**
 * The absolute offsets stay in the workspace state of the machine profile, which has one adapter
 * link.
 * The test makes a store round trip.
 */
class AbsoluteOffsetsTest {

    @Test
    fun theAbsoluteOffsetsStayInTheWorkspaceStateOfTheMachineProfile() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = StoreHarness(dispatcher)
        try {
            val first = assertIs<StoreResult.Ok<AdapterProfile>>(store.adapters.saveAdapterProfile(AdapterProfile(alias = "Mill adapter"))).value
            val second = assertIs<StoreResult.Ok<AdapterProfile>>(store.adapters.saveAdapterProfile(AdapterProfile(alias = "Spare adapter"))).value
            val created = assertIs<StoreResult.Ok<MachineProfile>>(
                store.machines.createProfile(MachineProfile.create(MachineType.VerticalMill, "Mill").copy(adapterProfileId = first.id)),
            ).value
            val workspaceId = created.activeWorkspaceId
            store.machines.setAxisState(workspaceId, AxisId.X, AxisState(absoluteOffset = -152_400.0))
            store.machines.setAxisState(workspaceId, AxisId.Z, AxisState(absoluteOffset = 12_700.0))

            // A new repository on the same database: the values come from the stored rows (a restart).
            val restarted = MachineRepository(StoreWriter(store.database, dispatcher, dispatcher), store.io)
            val loaded = restarted.loadFullProfile(created.id)!!
            val states = loaded.workspaces.single { it.id == workspaceId }.axisStates
            assertEquals(-152_400.0, states.getValue(AxisId.X).absoluteOffset)
            assertEquals(12_700.0, states.getValue(AxisId.Z).absoluteOffset)

            // A different adapter record for the machine does not change the offsets: they belong to the workspace.
            restarted.updateProfile(created.id) { profile -> profile.copy(adapterProfileId = second.id) }
            val relinked = restarted.loadFullProfile(created.id)!!
            assertEquals(second.id, relinked.adapterProfileId)
            assertEquals(-152_400.0, relinked.workspaces.single { it.id == workspaceId }.axisStates.getValue(AxisId.X).absoluteOffset)

            // The adapter record has no offsets: its JSON form has no key with "offset".
            val adapterJson = SuperDroJson.encodeToString(AdapterProfile.serializer(), store.adapters.loadAdapterProfile(first.id)!!)
            assertTrue("offset" !in adapterJson.lowercase(), adapterJson)
            // The offsets are in the axis state rows of the workspace.
            assertEquals(listOf("-152400.0"), store.texts("SELECT absolute_offset FROM axis_state WHERE workspace_id = '$workspaceId' AND axis = 'X'"))
        } finally {
            store.close()
        }
    }
}
