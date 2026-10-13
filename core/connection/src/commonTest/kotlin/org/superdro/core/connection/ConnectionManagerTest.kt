package org.superdro.core.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.TransportKind

/** Many sessions, the remembered machines, the pause, and the keep-alive summary. */
class ConnectionManagerTest {

    private val a = DeviceId("a")
    private val b = DeviceId("b")
    private val c = DeviceId("c")

    @Test
    fun anExceptionInOneSessionStopsOnlyThatSession() = runTest {
        val sink = object : RecordSink {
            override fun write(chunk: RecordedChunk) = error("A defect in the session code")
            override fun close() = Unit
        }
        val h = Harness(this, recorder = StreamRecorder { device, _, _ -> if (device == b) sink else null })
        h.links.responder = twoWayAdapter()
        listOf(a, b, c).forEach { id -> h.setups[id] = defaultSetup() }
        val sa = h.manager.connect(DeviceRef(a, "A", SPP))
        val sb = h.manager.connect(DeviceRef(b, "B", USB, recordStream = true))
        val sc = h.manager.connect(DeviceRef(c, "C", TCP))
        runCurrent()
        assertEquals(3, h.manager.sessions.value.size)
        h.links.links.forEach { link -> link.receive("X5;") }
        runCurrent()
        assertEquals(SessionState.Failed(LinkFailure.Lost), sb.state.value)
        assertTrue(h.links.to(USB).single().closed)
        repeat(20) {
            advanceTimeBy(1_000)
            h.links.to(SPP).last().receive("X${it + 10};")
            h.links.to(TCP).last().receive("X${it + 20};")
            runCurrent()
        }
        assertTrue(sa.state.value is SessionState.Connected)
        assertTrue(sc.state.value is SessionState.Connected)
        assertEquals(29L, sa.inputs.value.channels.getValue('x').rawCount)
        assertEquals(39L, sc.inputs.value.channels.getValue('x').rawCount)
        assertEquals(SessionState.Failed(LinkFailure.Lost), sb.state.value)
        assertEquals(1, h.links.to(USB).size)
        // A user connect starts the failed session again.
        h.manager.connect(DeviceRef(b, "B", USB))
        runCurrent()
        assertEquals(SessionState.AwaitingData, sb.state.value)
        h.manager.disconnectAll()
        runCurrent()
    }

    @Test
    fun aLinkLossOfOneSessionHasNoEffectOnTheOthers() = runTest {
        val h = Harness(this)
        val sessions = listOf(a to SPP, b to USB, c to TCP).map { (id, address) -> h.manager.connect(DeviceRef(id, id.value, address, twoWayCommunication = false)) }
        runCurrent()
        h.links.links.forEach { link -> link.receive("X1;") }
        runCurrent()
        h.links.to(USB).single().lose()
        runCurrent()
        assertTrue(sessions[1].state.value is SessionState.Reconnecting)
        assertTrue(sessions[0].state.value is SessionState.Connected)
        assertTrue(sessions[2].state.value is SessionState.Connected)
        assertTrue(!h.links.to(SPP).single().closed && !h.links.to(TCP).single().closed)
        h.manager.disconnectAll()
        runCurrent()
        assertTrue(sessions.all { session -> session.state.value == SessionState.Disconnected })
    }

    @Test
    fun theManagerHasNoLimitOnTheNumberOfSessions() = runTest {
        val h = Harness(this)
        val count = 40
        repeat(count) { index -> h.manager.connect(DeviceRef(DeviceId("m$index"), "M$index", AdapterAddress.Tcp("10.0.0.$index"), false)) }
        runCurrent()
        assertEquals(count, h.manager.sessions.value.size)
        assertTrue(h.manager.sessions.value.values.all { session -> session.state.value == SessionState.AwaitingData })
        h.manager.disconnectAll()
        runCurrent()
    }

    @Test
    fun theRestoreConnectsEachRememberedMachineThatIsNotPaused() = runTest {
        val h = Harness(this)
        h.wanted.wantedLinks.value = listOf(
            WantedLink(a, "Mill", SPP),
            WantedLink(b, "Lathe", USB),
            WantedLink(c, "Router", TCP, twoWayCommunication = false),
        )
        h.manager.restore()
        runCurrent()
        assertEquals(listOf(a, b, c), h.manager.sessions.value.keys.toList())
        assertEquals(listOf(SPP, USB, TCP), h.links.links.map { link -> link.address })
        assertEquals(false, h.manager.session(c)?.adapterInfo?.value?.twoWayCommunication)
        // The pause of one machine: only that session stops, and it stays in the manager.
        h.manager.disconnect(b)
        runCurrent()
        assertEquals(SessionState.Disconnected, h.manager.session(b)?.state?.value)
        assertEquals(SessionState.AwaitingData, h.manager.session(a)?.state?.value)
        assertEquals(3, h.manager.sessions.value.size)
        // The store removes the paused machine from the wanted links. A restore does not connect it.
        h.wanted.wantedLinks.value = h.wanted.wantedLinks.value.filter { link -> link.deviceId != b }
        h.manager.restore()
        runCurrent()
        assertEquals(SessionState.Disconnected, h.manager.session(b)?.state?.value)
        assertEquals(3, h.links.links.size)
        // "Connect" in the remembered machines pane.
        h.manager.connect(DeviceRef(b, "Lathe", USB))
        runCurrent()
        assertEquals(SessionState.AwaitingData, h.manager.session(b)?.state?.value)
        // The user removes a machine.
        h.manager.remove(c)
        runCurrent()
        assertNull(h.manager.session(c))
        assertTrue(h.links.to(TCP).single().closed)
        h.manager.disconnectAll()
        runCurrent()
    }

    @Test
    fun theKeepAliveSummaryWaits300MsAndRemovesRepeatedValues() = runTest {
        val h = Harness(this)
        val sent = ArrayList<Pair<Long, KeepAliveSummary?>>()
        val keepAlive = object : ConnectionKeepAlive {
            override fun update(summary: KeepAliveSummary?) {
                sent += h.now() to summary
            }
        }
        val controller = KeepAliveController(h.manager, keepAlive, backgroundScope)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(emptyList(), sent)
        assertNull(controller.summary.value)
        h.manager.connect(DeviceRef(a, "Mill", SPP, twoWayCommunication = false))
        runCurrent()
        advanceTimeBy(299)
        runCurrent()
        assertEquals(emptyList(), sent)
        advanceTimeBy(1)
        runCurrent()
        val line = DeviceLine(a, "Mill", TransportKind.BluetoothSpp, SessionState.AwaitingData)
        assertEquals(listOf<Pair<Long, KeepAliveSummary?>>(1_300L to KeepAliveSummary(listOf(line), offlineRememberedCount = 0)), sent)
        // Two changes in less than 300 ms give one update.
        h.links.last.receive("X1;")
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        // A USB adapter that is not attached waits for the device.
        h.manager.connect(DeviceRef(b, "Lathe", AdapterAddress.UsbSerial(vendorId = 1, productId = 2), twoWayCommunication = false))
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, sent.size)
        val second = sent[1].second!!
        assertEquals(1_700L, sent[1].first)
        assertEquals(SessionState.WaitingForDevice(LinkFailure.DeviceGone), second.devices[1].state)
        assertEquals(listOf(a, b), second.devices.map { it.id })
        assertTrue(second.devices[0].state is SessionState.Connected)
        assertEquals(1, second.onlineCount)
        assertEquals(1, second.offlineRememberedCount)
        // No repeated value: the controller sends nothing while nothing changes.
        h.manager.disconnect(b)
        runCurrent()
        advanceTimeBy(400)
        runCurrent()
        assertEquals(3, sent.size)
        assertEquals(KeepAliveSummary(listOf(DeviceLine(a, "Mill", TransportKind.BluetoothSpp, SessionState.Connected(1_300L))), offlineRememberedCount = 1), sent[2].second)
        h.manager.disconnectAll()
        runCurrent()
        advanceTimeBy(300)
        runCurrent()
        assertEquals(4, sent.size)
        assertNull(sent[3].second)
        assertNull(controller.summary.value)
    }

    @Test
    fun theSummaryKeepsTheProcessAliveInEachStateThatWantsALink() {
        fun line(state: SessionState) = DeviceLine(a, "Mill", TransportKind.UsbSerial, state)
        assertNull(KeepAliveController.summaryOf(emptyList()))
        assertNull(KeepAliveController.summaryOf(listOf(line(SessionState.Disconnected), line(SessionState.Failed(LinkFailure.Refused)))))
        listOf(
            SessionState.Connecting(1),
            SessionState.AwaitingData,
            SessionState.Connected(0L),
            SessionState.NoData,
            SessionState.Reconnecting(2, 0L, LinkFailure.Lost),
            SessionState.WaitingForDevice(LinkFailure.DeviceGone),
        ).forEach { state ->
            val summary = KeepAliveController.summaryOf(listOf(line(state), line(SessionState.Disconnected)))
            assertEquals(listOf(line(state)), summary?.devices)
            assertEquals(if (state.isOnline) 1 else 2, summary?.offlineRememberedCount)
        }
    }
}
