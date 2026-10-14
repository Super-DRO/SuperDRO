package org.superdro.core.ui.tabs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.KeepAliveStatus
import org.superdro.core.connection.LinkFailure
import org.superdro.core.connection.SessionEvent
import org.superdro.core.connection.SessionState
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.TransportKind
import org.superdro.core.store.valueOrNull
import org.superdro.core.ui.testing.FakeConnections
import org.superdro.core.ui.testing.FakeDeviceSession
import org.superdro.core.ui.testing.UiStoreFixture

/** The rules of the device tabs with virtual time. */
class DeviceTabsPresenterTest {

    private class Harness(scope: TestScope) {
        val store = UiStoreFixture(scope)
        val connections = FakeConnections()
        val presenter = DeviceTabsPresenter(connections, store.tabs, store.machines, scope.testPresenterContext())

        suspend fun machine(name: String, type: MachineType = MachineType.VerticalMill, remember: Boolean = true): DeviceId {
            val profile = store.machines.createProfile(MachineProfile.create(type, name)).valueOrNull()!!
            if (remember) store.tabs.remember(profile.id)
            return DeviceId(profile.id)
        }

        fun session(id: DeviceId, name: String, state: SessionState, address: AdapterAddress = AdapterAddress.BluetoothSpp("24:6F:28:A1:B2:C3")): FakeDeviceSession =
            FakeDeviceSession(id, name, address, state).also(connections::add)

        val names: List<String> get() = presenter.uiState.value.tabs.map { tab -> tab.name }

        fun close() {
            presenter.close()
            store.close()
        }
    }

    private suspend fun messages(harness: Harness): List<String> = harness.presenter.uiState.value.messages.map { it.text.english() }

    @Test
    fun aTabShowsWhileItsSessionIsOnline() = runTest {
        val harness = Harness(this)
        val mill = harness.machine("Mill")
        // A session that is already in a reconnect series when the presenter sees it has no tab.
        val session = harness.session(mill, "Mill", SessionState.Reconnecting(1, 1_000L, LinkFailure.Refused))
        runCurrent()
        assertEquals(emptyList(), harness.names)
        session.state.value = SessionState.AwaitingData
        runCurrent()
        assertEquals(listOf("Mill"), harness.names)
        assertEquals(TransportKind.BluetoothSpp, harness.presenter.uiState.value.tabs.single().transport)
        session.state.value = SessionState.Connected(0L)
        runCurrent()
        assertEquals(TabStatusIcon.Connected, harness.presenter.uiState.value.tabs.single().status.icon)
        session.state.value = SessionState.NoData
        runCurrent()
        assertEquals(listOf("Mill"), harness.names)
        assertEquals(false, harness.presenter.uiState.value.tabs.single().inGrace)
        harness.close()
    }

    @Test
    fun anOfflineTabStaysForTheGraceTimeOf10sThenHides() = runTest {
        val harness = Harness(this)
        val mill = harness.machine("Mill")
        val session = harness.session(mill, "Mill", SessionState.Connected(0L))
        runCurrent()
        session.state.value = SessionState.Reconnecting(2, 1_000L, LinkFailure.Lost)
        runCurrent()
        val tab = harness.presenter.uiState.value.tabs.single()
        assertTrue(tab.inGrace)
        assertEquals("Reconnecting (2)", tab.status.text.english())
        assertEquals(listOf("Mill: Reconnecting"), messages(harness))
        advanceTimeBy(9_999L)
        runCurrent()
        assertEquals(listOf("Mill"), harness.names)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(emptyList(), harness.names)
        assertEquals(listOf("Mill: Reconnecting", "Mill: Could not restore the link"), messages(harness))
        harness.close()
    }

    @Test
    fun aTabThatComesBackInTheGraceTimeStays() = runTest {
        val harness = Harness(this)
        val mill = harness.machine("Mill")
        val session = harness.session(mill, "Mill", SessionState.Connected(0L))
        runCurrent()
        session.state.value = SessionState.WaitingForDevice(LinkFailure.RadioOff)
        runCurrent()
        assertEquals("Bluetooth off", harness.presenter.uiState.value.tabs.single().status.text.english())
        assertEquals(listOf("Mill: Bluetooth off"), messages(harness))
        advanceTimeBy(5_000L)
        session.state.value = SessionState.Connected(5_000L)
        runCurrent()
        advanceTimeBy(20_000L)
        runCurrent()
        assertEquals(listOf("Mill"), harness.names)
        assertEquals(false, harness.presenter.uiState.value.tabs.single().inGrace)
        harness.close()
    }

    @Test
    fun disconnectPausesTheMachineAndHidesTheTabAtOnce() = runTest {
        val harness = Harness(this)
        val mill = harness.machine("Mill")
        harness.session(mill, "Mill", SessionState.Connected(0L))
        runCurrent()
        harness.presenter.onOpenMenu(mill)
        runCurrent()
        assertEquals(mill, harness.presenter.uiState.value.menuFor)
        harness.presenter.onPause(mill)
        runCurrent()
        assertEquals(listOf("disconnect ${mill.value}"), harness.connections.calls)
        assertEquals(true, harness.store.tabs.state.value.remembered.single().paused)
        assertEquals(emptyList(), harness.names)
        assertEquals(null, harness.presenter.uiState.value.menuFor)
        assertEquals(emptyList(), messages(harness))
        harness.close()
    }

    @Test
    fun theTabsHaveTheRememberedOrderThenTheOtherSessions() = runTest {
        val harness = Harness(this)
        val lathe = harness.machine("Lathe", MachineType.Lathe)
        val mill = harness.machine("Mill")
        harness.session(mill, "Mill", SessionState.Connected(0L))
        harness.session(DeviceId("demo-mill"), "Demo mill", SessionState.Connected(0L), AdapterAddress.Fake("mill"))
        harness.session(lathe, "Lathe", SessionState.Connected(0L), AdapterAddress.UsbSerial(0x0403, 0x6001))
        runCurrent()
        assertEquals(listOf("Lathe", "Mill", "Demo mill"), harness.names)
        assertEquals(2, harness.presenter.uiState.value.rememberedCount)
        harness.close()
    }

    @Test
    fun theAppOpensOnTheStoredMachineUntilTheUserSelectsATab() = runTest {
        val harness = Harness(this)
        val mill = harness.machine("Mill")
        val lathe = harness.machine("Lathe", MachineType.Lathe)
        harness.store.tabs.select(lathe.value)
        runCurrent()
        harness.session(mill, "Mill", SessionState.Connected(0L))
        runCurrent()
        assertEquals(mill, harness.presenter.uiState.value.selected)
        val latheSession = harness.session(lathe, "Lathe", SessionState.Connected(0L))
        runCurrent()
        assertEquals(lathe, harness.presenter.uiState.value.selected)
        harness.presenter.onSelect(mill)
        runCurrent()
        assertEquals(mill, harness.presenter.uiState.value.selected)
        assertEquals(mill.value, harness.store.tabs.state.value.selectedProfileId)
        latheSession.state.value = SessionState.Reconnecting(1, 0L, LinkFailure.Lost)
        runCurrent()
        latheSession.state.value = SessionState.Connected(10L)
        runCurrent()
        assertEquals(mill, harness.presenter.uiState.value.selected)
        harness.close()
    }

    @Test
    fun whenTheSelectedTabHidesItsNeighborIsSelected() = runTest {
        val harness = Harness(this)
        val a = harness.machine("A")
        val b = harness.machine("B")
        val c = harness.machine("C")
        listOf(a to "A", b to "B", c to "C").forEach { (id, name) -> harness.session(id, name, SessionState.Connected(0L)) }
        runCurrent()
        harness.presenter.onSelect(b)
        runCurrent()
        harness.presenter.onPause(b)
        runCurrent()
        assertEquals(listOf("A", "C"), harness.names)
        assertEquals(c, harness.presenter.uiState.value.selected)
        harness.presenter.onPause(c)
        runCurrent()
        assertEquals(a, harness.presenter.uiState.value.selected)
        harness.close()
    }

    @Test
    fun theSessionEventsGoToTheSnackbar() = runTest {
        val harness = Harness(this)
        val mill = harness.machine("Mill")
        val session = harness.session(mill, "Mill", SessionState.Connected(0L))
        runCurrent()
        session.events.emit(SessionEvent.RestartFailed(0L))
        session.events.emit(SessionEvent.AdapterRestarted(100L, 5L))
        runCurrent()
        assertEquals(
            listOf(
                "Mill: Could not restart the adapter. Turn the adapter power off and on.",
                "Mill: The adapter restarted. The machine datums and the error maps can need a new reference.",
            ),
            messages(harness),
        )
        val first = harness.presenter.uiState.value.messages.first().id
        harness.presenter.onMessageShown(first)
        runCurrent()
        assertEquals(1, harness.presenter.uiState.value.messages.size)
        harness.close()
    }

    @Test
    fun disconnectAllPausesEachMachineAndStopsEachSession() = runTest {
        val harness = Harness(this)
        val mill = harness.machine("Mill")
        val lathe = harness.machine("Lathe", MachineType.Lathe)
        harness.session(mill, "Mill", SessionState.Connected(0L))
        harness.session(lathe, "Lathe", SessionState.Connected(0L))
        runCurrent()
        harness.presenter.onOpenOverflow()
        runCurrent()
        assertTrue(harness.presenter.uiState.value.overflowOpen)
        harness.presenter.onDisconnectAll()
        runCurrent()
        assertEquals(listOf("disconnectAll"), harness.connections.calls)
        assertEquals(emptyList(), harness.names)
        assertEquals(false, harness.presenter.uiState.value.overflowOpen)
        assertTrue(harness.store.tabs.state.value.remembered.all { it.paused }, "No restore connects them again")
        harness.close()
    }

    @Test
    fun disconnectAllRunsTheCommandOfTheApp() = runTest {
        val store = UiStoreFixture(this)
        val commands = mutableListOf<String>()
        val presenter = DeviceTabsPresenter(FakeConnections(), store.tabs, store.machines, testPresenterContext(), DisconnectAllCommand { commands += "host" })
        presenter.onDisconnectAll()
        runCurrent()
        assertEquals(listOf("host"), commands)
        presenter.close()
        store.close()
    }

    @Test
    fun aTapOnTheNotificationSelectsTheTabOfItsMachine() = runTest {
        val harness = Harness(this)
        val mill = harness.machine("Mill")
        val lathe = harness.machine("Lathe", MachineType.Lathe)
        harness.session(mill, "Mill", SessionState.Connected(0L))
        harness.session(lathe, "Lathe", SessionState.Connected(0L))
        runCurrent()
        harness.presenter.onSelect(mill)
        runCurrent()
        assertEquals(mill, harness.presenter.uiState.value.selected)
        // The notification writes the selection to the tab store (MainActivity, EXTRA_DEVICE_ID).
        harness.store.tabs.select(lathe.value)
        runCurrent()
        assertEquals(lathe, harness.presenter.uiState.value.selected)
        harness.close()
    }

    @Test
    fun aStoredSelectionOfAnOfflineMachineSelectsItsTabWhenItComesOnline() = runTest {
        val harness = Harness(this)
        val mill = harness.machine("Mill")
        val lathe = harness.machine("Lathe", MachineType.Lathe)
        harness.session(mill, "Mill", SessionState.Connected(0L))
        val latheSession = harness.session(lathe, "Lathe", SessionState.Reconnecting(1, 1_000L, LinkFailure.Refused))
        runCurrent()
        harness.store.tabs.select(lathe.value)
        runCurrent()
        assertEquals(mill, harness.presenter.uiState.value.selected)
        latheSession.state.value = SessionState.Connected(0L)
        runCurrent()
        assertEquals(lathe, harness.presenter.uiState.value.selected)
        harness.close()
    }

    @Test
    fun theFrameWarnsWhenTheLinksCanStopInTheBackground() = runTest {
        val store = UiStoreFixture(this)
        val status = MutableStateFlow(KeepAliveStatus.Running)
        val connections = FakeConnections()
        val presenter = DeviceTabsPresenter(connections, store.tabs, store.machines, testPresenterContext(), keepAliveStatus = status)
        runCurrent()
        assertEquals(false, presenter.uiState.value.backgroundWarning)
        status.value = KeepAliveStatus.StartNotAllowed
        runCurrent()
        assertEquals(true, presenter.uiState.value.backgroundWarning)
        status.value = KeepAliveStatus.PrerequisiteMissing
        runCurrent()
        assertEquals(true, presenter.uiState.value.backgroundWarning)
        status.value = KeepAliveStatus.Idle
        runCurrent()
        assertEquals(false, presenter.uiState.value.backgroundWarning)
        presenter.close()
        store.close()
    }

    @Test
    fun aTabShowsTheNameOfTheProfileAfterARename() = runTest {
        val harness = Harness(this)
        val mill = harness.machine("Mill")
        harness.session(mill, "Mill", SessionState.Connected(0L))
        runCurrent()
        harness.store.machines.renameProfile(mill.value, "Bridgeport")
        runCurrent()
        assertEquals(listOf("Bridgeport"), harness.names)
        harness.close()
    }

    @Test
    fun aNewSessionShowsATabWhileItConnects() = runTest {
        val harness = Harness(this)
        val mill = harness.machine("Mill")
        val session = harness.session(mill, "Mill", SessionState.Connecting(1))
        runCurrent()
        assertEquals(listOf("Mill"), harness.names)
        assertEquals(mill, harness.presenter.uiState.value.selected)
        val tab = harness.presenter.uiState.value.tabs.single()
        assertEquals("Connecting", tab.status.text.english())
        assertEquals(false, tab.inGrace)
        // A Bluetooth connect can take a long time. The tab stays while the first attempt runs.
        advanceTimeBy(40_000L)
        runCurrent()
        assertEquals(listOf("Mill"), harness.names)
        assertEquals(emptyList(), messages(harness))
        harness.close()
    }

    @Test
    fun aNewSessionThatCannotConnectHidesItsTabAfterTheGraceTimeWithAMessage() = runTest {
        val harness = Harness(this)
        val mill = harness.machine("Mill")
        val session = harness.session(mill, "Mill", SessionState.Connecting(1))
        runCurrent()
        advanceTimeBy(30_000L)
        session.state.value = SessionState.Reconnecting(1, 31_000L, LinkFailure.Refused)
        runCurrent()
        val tab = harness.presenter.uiState.value.tabs.single()
        assertEquals("Reconnecting (1)", tab.status.text.english())
        assertTrue(tab.inGrace)
        assertEquals(emptyList(), messages(harness), "No message while the tab shows the status")
        advanceTimeBy(1_000L)
        session.state.value = SessionState.Connecting(2)
        runCurrent()
        assertEquals("Reconnecting (2)", harness.presenter.uiState.value.tabs.single().status.text.english())
        advanceTimeBy(8_999L)
        runCurrent()
        assertEquals(listOf("Mill"), harness.names)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(emptyList(), harness.names)
        assertEquals(listOf("Mill: Could not connect. SuperDRO tries again in the background."), messages(harness))
        // The session continues to try. The tab does not come back until the session is online.
        session.state.value = SessionState.Reconnecting(2, 70_000L, LinkFailure.Refused)
        runCurrent()
        session.state.value = SessionState.Connecting(3)
        runCurrent()
        assertEquals(emptyList(), harness.names)
        session.state.value = SessionState.AwaitingData
        runCurrent()
        assertEquals(listOf("Mill"), harness.names)
        harness.close()
    }

    @Test
    fun aNewSessionThatComesOnlineAfterAFailureKeepsItsTab() = runTest {
        val harness = Harness(this)
        val mill = harness.machine("Mill")
        val session = harness.session(mill, "Mill", SessionState.Connecting(1))
        runCurrent()
        session.state.value = SessionState.WaitingForDevice(LinkFailure.RadioOff)
        runCurrent()
        assertEquals("Bluetooth off", harness.presenter.uiState.value.tabs.single().status.text.english())
        advanceTimeBy(4_000L)
        session.state.value = SessionState.Connecting(1)
        runCurrent()
        session.state.value = SessionState.Connected(5_000L)
        runCurrent()
        advanceTimeBy(20_000L)
        runCurrent()
        assertEquals(listOf("Mill"), harness.names)
        assertEquals(false, harness.presenter.uiState.value.tabs.single().inGrace)
        assertEquals(emptyList(), messages(harness))
        // After the session was online, a link loss has the rules of a lost link.
        session.state.value = SessionState.Reconnecting(1, 26_000L, LinkFailure.Lost)
        runCurrent()
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(emptyList(), harness.names)
        assertEquals(listOf("Mill: Reconnecting", "Mill: Could not restore the link"), messages(harness))
        harness.close()
    }

    @Test
    fun aConnectOfAPausedMachineShowsItsTabAtOnceAndADisconnectHidesIt() = runTest {
        val harness = Harness(this)
        val mill = harness.machine("Mill")
        val session = harness.session(mill, "Mill", SessionState.Disconnected)
        runCurrent()
        assertEquals(emptyList(), harness.names)
        session.state.value = SessionState.Connecting(1)
        runCurrent()
        assertEquals(listOf("Mill"), harness.names)
        session.state.value = SessionState.Disconnected
        runCurrent()
        assertEquals(emptyList(), harness.names)
        advanceTimeBy(20_000L)
        runCurrent()
        assertEquals(emptyList(), messages(harness))
        harness.close()
    }
}
