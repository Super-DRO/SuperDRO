package org.superdro.core.ui.tabs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.LinkFailure
import org.superdro.core.connection.SessionState
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.TransportKind
import org.superdro.core.store.valueOrNull
import org.superdro.core.ui.tabs.remembered.MachineLinkState
import org.superdro.core.ui.tabs.remembered.RememberedMachinesPresenter
import org.superdro.core.ui.tabs.remembered.addressText
import org.superdro.core.ui.testing.FakeConnections
import org.superdro.core.ui.testing.FakeDeviceSession
import org.superdro.core.ui.testing.UiStoreFixture

/** The remembered machines pane. */
class RememberedMachinesPresenterTest {

    private class Harness(scope: TestScope) {
        val store = UiStoreFixture(scope)
        val connections = FakeConnections(connectState = SessionState.Connecting(1))
        val presenter = RememberedMachinesPresenter(connections, store.tabs, store.machines, scope.testPresenterContext())
        val context = scope.testPresenterContext()

        suspend fun machine(name: String, address: AdapterAddress?): String {
            val profile = store.machines.createProfile(MachineProfile.create(name = name).copy(adapter = address)).valueOrNull()!!
            store.tabs.remember(profile.id)
            return profile.id
        }

        fun close() {
            presenter.close()
            store.close()
        }
    }

    @Test
    fun eachRowShowsTheNameTheLinkTheAddressAndTheState() = runTest {
        val harness = Harness(this)
        val mill = harness.machine("Mill", AdapterAddress.BluetoothSpp("24:6F:28:A1:B2:C3"))
        val lathe = harness.machine("Lathe", AdapterAddress.Tcp("192.168.4.1", 8080))
        val router = harness.machine("Router", AdapterAddress.UsbSerial(0x0403, 0x6001, serial = "A1B2"))
        harness.connections.add(FakeDeviceSession(DeviceId(mill), "Mill", initialState = SessionState.Connected(0L)))
        harness.connections.add(FakeDeviceSession(DeviceId(lathe), "Lathe", initialState = SessionState.Reconnecting(3, 0L, LinkFailure.Lost)))
        harness.store.tabs.setPaused(router, true)
        runCurrent()
        val rows = harness.presenter.uiState.value.rows
        assertEquals(listOf("Mill", "Lathe", "Router"), rows.map { it.name })
        assertEquals(listOf(MachineLinkState.Online, MachineLinkState.Offline, MachineLinkState.Paused), rows.map { it.state })
        assertEquals(listOf(TransportKind.BluetoothSpp, TransportKind.Tcp, TransportKind.UsbSerial), rows.map { it.transport })
        assertEquals(listOf("24:6F:28:A1:B2:C3", "192.168.4.1:8080", "0403:6001 A1B2"), rows.map { it.address })
        assertEquals(listOf(null, "Reconnecting (3)", null), rows.map { it.link?.text?.english() })
        harness.close()
    }

    @Test
    fun connectAndPauseChangeThePauseFlagAndTheSession() = runTest {
        val harness = Harness(this)
        val mill = harness.machine("Mill", AdapterAddress.BluetoothSpp("24:6F:28:A1:B2:C3"))
        harness.store.tabs.setPaused(mill, true)
        runCurrent()
        harness.presenter.connect(mill)
        runCurrent()
        assertEquals(false, harness.store.tabs.state.value.remembered.single().paused)
        assertEquals(listOf("connect $mill"), harness.connections.calls)
        harness.presenter.pause(mill)
        runCurrent()
        assertEquals(true, harness.store.tabs.state.value.remembered.single().paused)
        assertEquals(listOf("connect $mill", "disconnect $mill"), harness.connections.calls)
        assertEquals(MachineLinkState.Paused, harness.presenter.uiState.value.rows.single().state)
        harness.close()
    }

    @Test
    fun removeAsksFirstStopsTheSessionAndKeepsTheProfile() = runTest {
        val harness = Harness(this)
        val mill = harness.machine("Mill", AdapterAddress.BluetoothSpp("24:6F:28:A1:B2:C3"))
        harness.connections.add(FakeDeviceSession(DeviceId(mill), "Mill"))
        runCurrent()
        harness.presenter.askRemove(mill)
        assertEquals("Mill", assertNotNull(harness.presenter.uiState.value.confirmRemove).name)
        harness.presenter.cancelRemove()
        assertNull(harness.presenter.uiState.value.confirmRemove)
        assertEquals(emptyList(), harness.connections.calls)
        harness.presenter.askRemove(mill)
        harness.presenter.confirmRemove()
        runCurrent()
        assertEquals(listOf("remove $mill"), harness.connections.calls)
        assertTrue(harness.store.tabs.state.value.remembered.isEmpty())
        assertEquals(emptyList(), harness.presenter.uiState.value.rows)
        assertNotNull(harness.store.machines.loadProfile(mill))
        harness.close()
    }

    @Test
    fun aMachineThatTriesToConnectShowsItsStatusAndTheLastCause() = runTest {
        val harness = Harness(this)
        val mill = harness.machine("Mill", AdapterAddress.BluetoothSpp("24:6F:28:A1:B2:C3"))
        val session = FakeDeviceSession(DeviceId(mill), "Mill", initialState = SessionState.Connecting(1))
        harness.connections.add(session)
        runCurrent()
        fun row() = harness.presenter.uiState.value.rows.single()
        assertEquals(MachineLinkState.Offline, row().state)
        assertEquals("Connecting", row().link?.text?.english())
        assertNull(row().lastCause)
        session.state.value = SessionState.Reconnecting(1, 31_000L, LinkFailure.Refused)
        runCurrent()
        assertEquals("Reconnecting (1)", row().link?.text?.english())
        assertEquals(LinkFailure.Refused, row().lastCause)
        // The next attempt keeps the last cause.
        session.state.value = SessionState.Connecting(2)
        runCurrent()
        assertEquals("Reconnecting (2)", row().link?.text?.english())
        assertEquals(LinkFailure.Refused, row().lastCause)
        // The status of WaitingForDevice is the cause.
        session.state.value = SessionState.WaitingForDevice(LinkFailure.RadioOff)
        runCurrent()
        assertEquals("Bluetooth off", row().link?.text?.english())
        assertNull(row().lastCause)
        session.state.value = SessionState.Connected(0L)
        runCurrent()
        assertEquals(MachineLinkState.Online, row().state)
        assertNull(row().link)
        assertNull(row().lastCause)
        session.state.value = SessionState.Connecting(2)
        runCurrent()
        assertNull(row().lastCause, "An online link clears the last cause")
        harness.close()
    }

    @Test
    fun aStoppedOrPausedMachineShowsNoLinkStatus() = runTest {
        val harness = Harness(this)
        val mill = harness.machine("Mill", AdapterAddress.BluetoothSpp("24:6F:28:A1:B2:C3"))
        val session = FakeDeviceSession(DeviceId(mill), "Mill", initialState = SessionState.Reconnecting(4, 0L, LinkFailure.Refused))
        harness.connections.add(session)
        runCurrent()
        assertEquals("Reconnecting (4)", harness.presenter.uiState.value.rows.single().link?.text?.english())
        harness.store.tabs.setPaused(mill, true)
        runCurrent()
        assertEquals(MachineLinkState.Paused, harness.presenter.uiState.value.rows.single().state)
        assertNull(harness.presenter.uiState.value.rows.single().link)
        assertNull(harness.presenter.uiState.value.rows.single().lastCause)
        harness.store.tabs.setPaused(mill, false)
        session.state.value = SessionState.Disconnected
        runCurrent()
        assertEquals(MachineLinkState.Offline, harness.presenter.uiState.value.rows.single().state)
        assertNull(harness.presenter.uiState.value.rows.single().link)
        harness.close()
    }

    @Test
    fun theAddressTextOfEachLinkType() {
        assertEquals("[fe80::1]:8080", addressText(AdapterAddress.Tcp("fe80::1", 8080)))
        assertEquals("0403:6001", addressText(AdapterAddress.UsbSerial(0x0403, 0x6001)))
        assertEquals("mill", addressText(AdapterAddress.Fake("mill")))
    }

    @Test
    fun aDesktopSppLinkWithNoMacAddressShowsItsSerialPort() {
        assertEquals("/dev/rfcomm0", addressText(AdapterAddress.BluetoothSpp(mac = "", serialPort = "/dev/rfcomm0")))
        assertEquals("00:11:22:33:44:55", addressText(AdapterAddress.BluetoothSpp(mac = "00:11:22:33:44:55", serialPort = "/dev/rfcomm0")))
        assertEquals("00:11:22:33:44:55", addressText(AdapterAddress.BluetoothSpp(mac = "00:11:22:33:44:55")))
    }
}
