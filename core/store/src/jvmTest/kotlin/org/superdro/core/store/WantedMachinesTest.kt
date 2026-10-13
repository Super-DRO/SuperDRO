package org.superdro.core.store

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.MachineProfile
import org.superdro.core.store.json.TabStore

class WantedMachinesTest {

    private val scope = TestScope()
    private val store = StoreFixture(scope)

    @AfterTest
    fun close() = store.close()

    @Test
    fun theRememberedMachinesThatAreNotPausedWantALink() = scope.runTest {
        val tabs = TabStore(store.storeIO, dispatcher = store.dispatcher, clock = store.clock)
        val mill = store.machines.createProfile(MachineProfile.create(name = "Mill").copy(adapter = AdapterAddress.BluetoothSpp("24:6F:28:A1:B2:C3"))).valueOrNull()!!
        val lathe = store.machines.createProfile(MachineProfile.create(name = "Lathe").copy(adapter = AdapterAddress.Tcp("10.0.0.5"), twoWayCommunication = false)).valueOrNull()!!
        val noLink = store.machines.createProfile(MachineProfile.create(name = "No link")).valueOrNull()!!
        listOf(mill.id, lathe.id, noLink.id, "deleted-profile").forEach(tabs::remember)

        assertEquals(
            listOf(
                WantedMachine(mill.id, "Mill", AdapterAddress.BluetoothSpp("24:6F:28:A1:B2:C3"), true),
                WantedMachine(lathe.id, "Lathe", AdapterAddress.Tcp("10.0.0.5"), false),
            ),
            wantedMachines(tabs, store.machines).first(),
        )
        tabs.setPaused(mill.id, true)
        assertEquals(listOf(lathe.id), wantedMachines(tabs, store.machines).first().map { it.profileId })
    }
}
