package org.superdro.core.ui.tabs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.SessionState
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.MachineProfile
import org.superdro.core.store.valueOrNull
import org.superdro.core.ui.testing.FakeConnections
import org.superdro.core.ui.testing.FakeDeviceSession
import org.superdro.core.ui.testing.UiStoreFixture

/** The rename dialog and the edit dialog of a machine. */
class EditMachinePresenterTest {

    @Test
    fun aRenameToANameThatExistsIsRefused() = runTest {
        val store = UiStoreFixture(this)
        val connections = FakeConnections()
        store.machines.createProfile(MachineProfile.create(name = "Lathe"))
        val mill = store.machines.createProfile(MachineProfile.create(name = "Mill")).valueOrNull()!!
        val presenter = EditMachinePresenter(mill.id, connections, store.machines, testPresenterContext())
        runCurrent()
        assertEquals("Mill", presenter.uiState.value.name)
        presenter.setName("lathe")
        presenter.save()
        runCurrent()
        assertEquals("A profile with this name already exists.", presenter.uiState.value.nameError?.english())
        presenter.setName("Bridgeport")
        presenter.save()
        runCurrent()
        assertTrue(presenter.uiState.value.done)
        assertEquals("Bridgeport", store.machines.loadProfile(mill.id)?.name)
        presenter.close()
        store.close()
    }

    @Test
    fun theEditOfAWifiLinkChecksTheHostAndConnectsTheRunningSessionAgain() = runTest {
        val store = UiStoreFixture(this)
        val connections = FakeConnections()
        val mill = store.machines.createProfile(MachineProfile.create(name = "Mill").copy(adapter = AdapterAddress.Tcp("192.168.4.1", 8080))).valueOrNull()!!
        connections.add(FakeDeviceSession(DeviceId(mill.id), "Mill", AdapterAddress.Tcp("192.168.4.1", 8080), SessionState.Connected(0L)))
        val presenter = EditMachinePresenter(mill.id, connections, store.machines, testPresenterContext())
        runCurrent()
        assertEquals("192.168.4.1", presenter.uiState.value.host)
        assertEquals("8080", presenter.uiState.value.port)
        presenter.setHost("bad_host")
        presenter.save()
        runCurrent()
        assertEquals("This is not a valid host name, IPv4 address, or IPv6 address.", presenter.uiState.value.hostError?.english())
        presenter.setHost("dro.local")
        presenter.setPort("23")
        presenter.save()
        runCurrent()
        assertTrue(presenter.uiState.value.done)
        assertEquals(AdapterAddress.Tcp("dro.local", 23), store.machines.loadProfile(mill.id)?.adapter)
        assertEquals(listOf("connect ${mill.id}"), connections.calls)
        assertEquals(AdapterAddress.Tcp("dro.local", 23), connections.session(DeviceId(mill.id)).device.value.address)
        presenter.close()
        store.close()
    }
}
