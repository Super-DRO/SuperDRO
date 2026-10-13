package org.superdro.core.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.AxisId
import org.superdro.core.model.IdentitySource
import org.superdro.core.protocol.InputChannel
import org.superdro.core.protocol.InputMode
import org.superdro.core.protocol.ValueState

/** A session from the open to the start queries. */
class SessionStartTest {

    private val mill = DeviceId("mill")

    @Test
    fun aSessionGoesFromTheOpenToTheStartQueriesWithTheExactBytesAndTimes() = runTest {
        val h = Harness(this)
        h.links.responder = twoWayAdapter()
        h.setups[mill] = defaultSetup()
        val session = h.manager.connect(device("mill", TCP))
        val states = h.states(session)
        runCurrent()
        assertEquals(SessionState.AwaitingData, session.state.value)
        val link = h.links.last
        assertEquals(1, link.collections)

        // The session writes nothing before the first valid message.
        advanceTimeBy(100)
        runCurrent()
        assertEquals(emptyList(), link.writes)
        link.receive("X1234;")
        runCurrent()
        assertEquals(SessionState.Connected(100L), session.state.value)
        assertEquals(1234L, session.inputs.value.channels.getValue('x').rawCount)
        assertEquals(1234.0, session.inputs.value.axisValue(AxisId.X))

        advanceTimeBy(2_600)
        runCurrent()
        // BEL bytes 500 ms after Connected and after each write. The queue starts 2000 ms after
        // the first Connected. The queue writes go out 50 ms apart.
        assertEquals(listOf(600L, 1_100L, 1_600L), link.belTimes)
        assertEquals(
            listOf(
                2_100L to "\"#get:mac;\"",
                2_150L to "\"#get:uuid;\"",
                2_200L to "\"#get:features;\"",
                2_250L to "\"#get:uptime;\"",
                2_300L to "\"#get:model;\"",
                2_350L to "#get_mode:x;",
                2_400L to "#get_mode:y;",
                2_450L to "#get_mode:z;",
                2_500L to "#get_mode:w;",
                2_550L to "#get_mode:u;",
                2_600L to "#get_mode:v;",
            ),
            link.commands,
        )
        assertEquals(
            listOf(0x22, 0x23, 0x67, 0x65, 0x74, 0x3A, 0x6D, 0x61, 0x63, 0x3B, 0x22),
            link.commands.first().second.encodeToByteArray().map { it.toInt() and 0xFF },
        )
        advanceTimeBy(500)
        runCurrent()
        assertEquals(listOf(600L, 1_100L, 1_600L, 3_100L), link.belTimes)

        val info = session.adapterInfo.value
        assertEquals("246f28a1b2c3", info.values.mac.value)
        assertEquals("tda-example", info.values.model.value)
        assertEquals(3600L, info.values.uptime.value)
        assertEquals(InputMode.QuadratureAB, info.values.modes.getValue(InputChannel.V).value?.current)
        assertTrue(info.values.mac.state is ValueState.Reported)
        assertEquals(AdapterIdentity("24:6f:28:a1:b2:c5", IdentitySource.BtMac), info.identity)
        assertEquals(IdentityCheck.NotStored, info.identityCheck)
        assertTrue(info.queueActive)
        assertEquals(
            listOf(SessionState.Disconnected, SessionState.Connecting(1), SessionState.AwaitingData, SessionState.Connected(100L)),
            states,
        )

        // The start queries go out once for each link: the "id" retry has nothing to do.
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, link.commands.count { (_, text) -> text == "\"#get:mac;\"" })
        h.manager.disconnect(mill)
        runCurrent()
    }

    @Test
    fun aBluetoothLinkHasTheIdentityOfItsAddressAtOnce() = runTest {
        val h = Harness(this)
        h.setups[mill] = defaultSetup(adapterIdentity = "24:6F:28:A1:B2:C5")
        val session = h.manager.connect(device("mill", SPP))
        runCurrent()
        val info = session.adapterInfo.value
        assertEquals(AdapterIdentity("24:6f:28:a1:b2:c5", IdentitySource.BtMac), info.identity)
        assertEquals(IdentityCheck.Confirmed, info.identityCheck)
        h.manager.disconnect(mill)
        runCurrent()
    }

    @Test
    fun adifferentAdapterGivesAWarningAndTheReadoutStaysLive() = runTest {
        val h = Harness(this)
        h.links.responder = twoWayAdapter()
        h.setups[mill] = defaultSetup(adapterIdentity = "24:6f:28:00:00:01")
        val session = h.manager.connect(device("mill", USB))
        val events = h.record(session.events)
        runCurrent()
        val link = h.links.last
        link.receive("X10;")
        runCurrent()
        // The readout is live at Connected, before the identity check.
        assertEquals(10.0, session.inputs.value.axisValue(AxisId.X))
        assertEquals(IdentityCheck.Pending, session.adapterInfo.value.identityCheck)
        advanceTimeBy(2_100)
        runCurrent()
        val actual = AdapterIdentity("24:6f:28:a1:b2:c5", IdentitySource.BtMac)
        assertEquals(IdentityCheck.Differs("24:6f:28:00:00:01", actual), session.adapterInfo.value.identityCheck)
        assertEquals(listOf<SessionEvent>(SessionEvent.IdentityDiffers("24:6f:28:00:00:01", actual)), events)
        link.receive("X20;")
        runCurrent()
        assertEquals(20.0, session.inputs.value.axisValue(AxisId.X))
        h.manager.disconnect(mill)
        runCurrent()
    }

    @Test
    fun withNoMacTheIdentityIsTheUuid() = runTest {
        val h = Harness(this)
        val adapter = twoWayAdapter()
        h.links.responder = { text -> if (text == "\"#get:mac;\"") listOf("\$0aa3;", "!0a01;") else adapter(text) }
        h.setups[mill] = defaultSetup(adapterIdentity = "C0FFEE00-1234-4ABC-8DEF-0123456789AB")
        val session = h.manager.connect(device("mill", TCP))
        runCurrent()
        h.links.last.receive("X1;")
        runCurrent()
        advanceTimeBy(2_200)
        runCurrent()
        val info = session.adapterInfo.value
        assertEquals(AdapterIdentity("c0ffee00-1234-4abc-8def-0123456789ab", IdentitySource.AdapterReported), info.identity)
        assertEquals(IdentityCheck.Confirmed, info.identityCheck)
        h.manager.disconnect(mill)
        runCurrent()
    }
}
