package org.superdro.core.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.AdapterAddress

/** The session states and timers. */
class SessionStatesTest {

    private val id = DeviceId("mill")

    @Test
    fun connectedGoesToNoDataAfterSevenSecondsWithNoValidMessage() = runTest {
        val h = Harness(this)
        h.links.responder = twoWayAdapter()
        h.setups[id] = defaultSetup()
        val session = h.manager.connect(device("mill", USB))
        runCurrent()
        val link = h.links.last
        link.receive("X1;")
        runCurrent()
        // The last answer of the start queries is at 2500 ms.
        advanceTimeBy(4_000)
        link.receive("@3E8;")
        advanceTimeBy(4_000)
        link.receive("@7D0;")
        advanceTimeBy(1_499)
        runCurrent()
        assertEquals(SessionState.Connected(0L), session.state.value)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(SessionState.NoData, session.state.value)
        assertEquals(9_500L, h.now())
        advanceTimeBy(500)
        runCurrent()
        link.receive("X2;")
        runCurrent()
        assertEquals(SessionState.Connected(10_000L), session.state.value)
        advanceTimeBy(10_000)
        runCurrent()
        // No second start query after NoData to Connected.
        assertEquals(1, link.commands.count { (_, text) -> text == "\"#get:mac;\"" })
        assertEquals(1, h.links.links.size)
        h.manager.disconnect(id)
        runCurrent()
    }

    @Test
    fun awaitingDataReconnectsAfterThirtySeconds() = runTest {
        val h = Harness(this)
        val session = h.manager.connect(device("mill", TCP))
        val states = h.states(session)
        runCurrent()
        advanceTimeBy(29_999)
        runCurrent()
        assertEquals(SessionState.AwaitingData, session.state.value)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(SessionState.Reconnecting(1, 31_000L, LinkFailure.Lost), session.state.value)
        assertTrue(h.links.links[0].closed)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, h.links.links.size)
        assertEquals(SessionState.AwaitingData, session.state.value)
        assertTrue(SessionState.Connecting(2) in states)
        h.manager.disconnect(id)
        runCurrent()
    }

    @Test
    fun aUsbLinkKeepsTheThirtySecondNoDataLimit() = runTest {
        val h = Harness(this)
        val session = h.manager.connect(device("mill", USB, twoWay = false))
        runCurrent()
        h.links.last.receive("X1;")
        runCurrent()
        advanceTimeBy(7_000)
        runCurrent()
        assertEquals(SessionState.NoData, session.state.value)
        advanceTimeBy(29_999)
        runCurrent()
        assertEquals(SessionState.NoData, session.state.value)
        assertEquals(1, h.links.links.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(SessionState.Reconnecting(1, 38_000L, LinkFailure.Lost), session.state.value)
        assertTrue(h.links.links[0].closed)
        h.manager.disconnect(id)
        runCurrent()
    }

    @Test
    fun sppAndTcpCloseAtNoDataAndOpenAgainAfter750Ms() = runTest {
        for (address in listOf(SPP, TCP)) {
            val h = Harness(this)
            h.links.openFailures.addAll(listOf(null, LinkFailure.Refused))
            val session = h.manager.connect(device("mill", address, twoWay = false))
            val states = h.states(session)
            runCurrent()
            val start = h.now()
            h.links.last.receive("X1;")
            runCurrent()
            advanceTimeBy(7_000)
            runCurrent()
            // NoData at once goes to Reconnecting with the first delay of 750 ms.
            assertEquals(SessionState.Reconnecting(1, start + 7_750L, LinkFailure.Lost), session.state.value)
            assertTrue(SessionState.NoData in states)
            assertTrue(h.links.links[0].closed)
            advanceTimeBy(749)
            runCurrent()
            assertEquals(1, h.links.links.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(2, h.links.links.size)
            // The open after the recovery fails: then the normal delays apply (1 s, then 2 s).
            assertEquals(SessionState.Reconnecting(2, start + 8_750L, LinkFailure.Refused), session.state.value)
            h.manager.disconnect(id)
            runCurrent()
        }
    }

    @Test
    fun aLostLinkReconnectsWithTheBackOff() = runTest {
        val h = Harness(this)
        h.links.openFailures.addAll(listOf(null, LinkFailure.Refused, LinkFailure.Refused, LinkFailure.Refused))
        val session = h.manager.connect(device("mill", TCP, twoWay = false))
        runCurrent()
        h.links.last.receive("X1;")
        advanceTimeBy(100)
        runCurrent()
        h.links.last.lose()
        runCurrent()
        assertEquals(SessionState.Reconnecting(1, 1_100L, LinkFailure.Lost), session.state.value)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(SessionState.Reconnecting(2, 3_100L, LinkFailure.Refused), session.state.value)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(SessionState.Reconnecting(3, 7_100L, LinkFailure.Refused), session.state.value)
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(SessionState.Reconnecting(4, 15_100L, LinkFailure.Refused), session.state.value)
        advanceTimeBy(8_000)
        runCurrent()
        assertEquals(SessionState.AwaitingData, session.state.value)
        assertEquals(5, h.links.links.size)
        h.manager.disconnect(id)
        runCurrent()
    }

    @Test
    fun reconnectingHoldsTheFailedAttemptAndTheNextConnectingAddsOne() = runTest {
        val h = Harness(this)
        h.links.openFailures.addAll(listOf(null, LinkFailure.Refused, LinkFailure.Refused))
        val session = h.manager.connect(device("mill", TCP, twoWay = false))
        val states = h.states(session)
        runCurrent()
        h.links.last.receive("X1;")
        advanceTimeBy(100)
        runCurrent()
        // A read failure in Connected, then two refused opens.
        h.links.last.lose()
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(
            listOf(
                SessionState.Disconnected,
                SessionState.Connecting(1),
                SessionState.AwaitingData,
                SessionState.Connected(0L),
                SessionState.Reconnecting(1, 1_100L, LinkFailure.Lost),
                SessionState.Connecting(2),
                SessionState.Reconnecting(2, 3_100L, LinkFailure.Refused),
                SessionState.Connecting(3),
                SessionState.Reconnecting(3, 7_100L, LinkFailure.Refused),
                SessionState.Connecting(4),
                SessionState.AwaitingData,
            ),
            states,
        )
        h.manager.disconnect(id)
        runCurrent()
    }

    @Test
    fun tenSecondsInConnectedStartTheDelaysAtOneSecondAgain() = runTest {
        val h = Harness(this)
        h.links.openFailures.addAll(listOf(LinkFailure.Refused, LinkFailure.Refused, null))
        val session = h.manager.connect(device("mill", TCP, twoWay = false))
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        // Two failures, then the third open at 3000 ms.
        assertEquals(SessionState.AwaitingData, session.state.value)
        repeat(12) {
            h.links.last.receive("X1;")
            advanceTimeBy(1_000)
            runCurrent()
        }
        h.links.last.lose()
        runCurrent()
        assertEquals(SessionState.Reconnecting(1, 16_000L, LinkFailure.Lost), session.state.value)
        h.manager.disconnect(id)
        runCurrent()
    }

    @Test
    fun aWriteFailureIsALinkFailure() = runTest {
        val h = Harness(this)
        val session = h.manager.connect(device("mill", SPP))
        runCurrent()
        val link = h.links.last
        link.writeFailure = LinkFailure.Lost
        link.receive("X1;")
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        // The BEL byte at 500 ms fails: the session reconnects.
        assertEquals(SessionState.Reconnecting(1, 1_500L, LinkFailure.Lost), session.state.value)
        assertTrue(link.closed)
        h.manager.disconnect(id)
        runCurrent()
    }

    @Test
    fun aRadioThatIsOffWaitsForTheCapability() = runTest {
        val h = Harness(this)
        h.capabilities.state.value = USB_ATTACHED.copy(bluetooth = LinkCapability.Unavailable(LinkFailure.RadioOff))
        val session = h.manager.connect(device("mill", SPP))
        runCurrent()
        assertEquals(SessionState.WaitingForDevice(LinkFailure.RadioOff), session.state.value)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(0, h.links.links.size)
        h.capabilities.state.value = USB_ATTACHED
        runCurrent()
        assertEquals(SessionState.AwaitingData, session.state.value)
        assertEquals(1, h.links.links.size)
        h.manager.disconnect(id)
        runCurrent()
    }

    @Test
    fun aUsbDetachGoesToWaitingForDeviceAndAnAttachConnectsAgain() = runTest {
        val h = Harness(this)
        val session = h.manager.connect(device("mill", USB, twoWay = false))
        runCurrent()
        h.links.last.receive("X1;")
        runCurrent()
        assertTrue(session.state.value is SessionState.Connected)
        h.capabilities.state.value = CapabilityState()
        runCurrent()
        assertEquals(SessionState.WaitingForDevice(LinkFailure.DeviceGone), session.state.value)
        assertTrue(h.links.links[0].closed)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, h.links.links.size)
        h.capabilities.state.value = USB_ATTACHED
        runCurrent()
        assertEquals(2, h.links.links.size)
        assertEquals(SessionState.AwaitingData, session.state.value)
        h.manager.disconnect(id)
        runCurrent()
    }

    @Test
    fun aPermissionFailureOfTheTransportWaitsForTheDevice() = runTest {
        val h = Harness(this)
        h.links.openFailures.add(LinkFailure.PermissionMissing)
        val session = h.manager.connect(device("mill", SPP))
        runCurrent()
        assertEquals(SessionState.WaitingForDevice(LinkFailure.PermissionMissing), session.state.value)
        // The source tells "available": a fallback retry after the policy delay.
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(SessionState.AwaitingData, session.state.value)
        assertEquals(2, h.links.links.size)
        h.manager.disconnect(id)
        runCurrent()
    }

    @Test
    fun aUserDisconnectStopsTheReconnectLoop() = runTest {
        val h = Harness(this)
        val session = h.manager.connect(device("mill", TCP))
        runCurrent()
        h.links.last.lose()
        runCurrent()
        assertTrue(session.state.value is SessionState.Reconnecting)
        h.manager.disconnect(id)
        runCurrent()
        assertEquals(SessionState.Disconnected, session.state.value)
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, h.links.links.size)
        h.manager.connect(device("mill", TCP))
        runCurrent()
        assertEquals(SessionState.AwaitingData, session.state.value)
        assertEquals(2, h.links.links.size)
        h.manager.disconnect(id)
        runCurrent()
    }

    @Test
    fun aNewAddressStartsANewLinkAtOnce() = runTest {
        val h = Harness(this)
        val session = h.manager.connect(device("mill", TCP))
        runCurrent()
        val other = AdapterAddress.Tcp("10.0.0.6", 8080)
        h.manager.connect(device("mill", other))
        runCurrent()
        assertTrue(h.links.links[0].closed)
        assertEquals(other, h.links.last.address)
        assertEquals(SessionState.AwaitingData, session.state.value)
        assertEquals(other, session.device.value.address)
        h.manager.disconnect(id)
        runCurrent()
    }

    @Test
    fun eachLinkHasANewParserAndANewTimeOffset() = runTest {
        val h = Harness(this)
        h.setups[id] = defaultSetup()
        val session = h.manager.connect(device("mill", USB, twoWay = false))
        runCurrent()
        val first = h.links.last
        advanceTimeBy(1_000)
        runCurrent()
        first.receive("@3E8;X1;")
        runCurrent()
        assertEquals(1_000L, session.inputs.value.channels.getValue('x').timeMs)
        advanceTimeBy(1_000)
        runCurrent()
        first.receive("@7D0;X2;")
        runCurrent()
        assertEquals(2_000L, session.inputs.value.channels.getValue('x').timeMs)
        advanceTimeBy(1_000)
        runCurrent()
        // Each readout after an `@` gets the time of that `@`.
        first.receive("X3;")
        runCurrent()
        assertEquals(2_000L, session.inputs.value.channels.getValue('x').timeMs)
        // NoData at 10000 ms. The change from NoData to Connected resets the offset.
        advanceTimeBy(8_000)
        runCurrent()
        assertEquals(SessionState.NoData, session.state.value)
        first.receive("X4;")
        runCurrent()
        assertEquals(11_000L, session.inputs.value.channels.getValue('x').timeMs)
        advanceTimeBy(500)
        runCurrent()
        first.receive("X5;")
        runCurrent()
        assertEquals(11_500L, session.inputs.value.channels.getValue('x').timeMs)
        assertEquals(5L, session.inputs.value.channels.getValue('x').rawCount)
        // A part message at the end of a link does not join the first bytes of the next link.
        first.receive("@BB8;X12")
        runCurrent()
        first.lose()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        val second = h.links.last
        assertTrue(second !== first)
        second.receive("34;X6;")
        runCurrent()
        val x = session.inputs.value.channels.getValue('x')
        assertEquals(6L, x.rawCount)
        assertEquals(12_500L, x.timeMs)
        h.manager.disconnect(id)
        runCurrent()
    }
}
