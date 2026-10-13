@file:OptIn(ExperimentalCoroutinesApi::class)

package org.superdro.validation.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.AttachedUsbDevice
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.SessionState
import org.superdro.core.connection.WantedLink
import org.superdro.core.model.AdapterAddress
import org.superdro.validation.protocol.SessionHarness.Companion.FAKE
import org.superdro.validation.protocol.SessionHarness.Companion.SPP
import org.superdro.validation.protocol.SessionHarness.Companion.TCP
import org.superdro.validation.protocol.SessionHarness.Companion.USB
import org.superdro.validation.protocol.SessionHarness.Companion.ref

/**
 * The sessions and many devices: two and three sessions at the same time, the failure of one
 * session, and the remembered machines.
 */
class ManySessionsValidationTest {

    @Test
    fun three_sessions_have_their_own_parser_queue_and_bel_byte_timer() = runTest {
        val h = SessionHarness(this)
        h.factory.responder = SessionHarness::adapterAnswer
        val addresses = listOf(SPP, AdapterAddress.BluetoothSpp("24:6F:28:00:00:01"), TCP)
        val sessions = addresses.mapIndexed { index, address -> h.connect(ref(address, "m$index")) }
        val transports = h.factory.transports
        assertEquals(3, transports.size)
        // The first valid messages come at 1000, 1200, and 1400 ms.
        transports.forEachIndexed { index, transport ->
            h.advanceTo(1_000L + 200L * index)
            transport.send("X${index + 1};")
        }
        h.advanceTo(3_900L)
        sessions.forEachIndexed { index, session ->
            assertEquals(SessionState.Connected(1_000L + 200L * index), session.state.value)
            assertEquals((index + 1).toLong(), session.inputs.value.channels.getValue('x').rawCount)
        }
        transports.forEachIndexed { index, transport ->
            val connectedAt = 1_000L + 200L * index
            assertEquals(listOf(connectedAt + 500L, connectedAt + 1_000L, connectedAt + 1_500L), transport.belTimes.filter { it < connectedAt + 2_000L })
            assertEquals(QueueDriver.START_QUERIES, transport.commands.map { it.text })
            assertEquals(connectedAt + 2_000L, transport.commands.first().timeMs)
        }
    }

    @Test
    fun a_failure_of_one_session_does_not_stop_the_other_sessions() = runTest {
        val h = SessionHarness(this)
        val sessions = listOf(h.connect(ref(SPP, "a")), h.connect(ref(TCP, "b")), h.connect(ref(FAKE, "c")))
        val transports = h.factory.transports
        h.advanceTo(1_000L)
        transports.forEach { it.send("X1;") }
        h.advanceTo(2_000L)
        transports[0].lose()
        for (time in 3_000L..20_000L step 1_000L) {
            h.advanceTo(time)
            transports[1].send("X$time;")
            transports[2].send("Y$time;")
        }
        h.settle()
        assertFalse(sessions[0].state.value is SessionState.Connected)
        assertEquals(SessionState.Connected(1_000L), sessions[1].state.value)
        assertEquals(SessionState.Connected(1_000L), sessions[2].state.value)
        assertEquals(20_000L, sessions[1].inputs.value.channels.getValue('x').rawCount)
        assertEquals(20_000L, sessions[2].inputs.value.channels.getValue('y').rawCount)
        assertTrue(transports[1].belTimes.any { it > 19_000L })
        assertTrue(transports[2].belTimes.any { it > 19_000L })
        // The failed session has its own reconnect loop.
        assertTrue(h.factory.transports.size >= 4)
        assertEquals(SPP, h.factory.transports[3].address)
    }

    @Test
    fun a_user_disconnect_stops_only_that_session() = runTest {
        val h = SessionHarness(this)
        val a = h.connect(ref(SPP, "a"))
        val b = h.connect(ref(TCP, "b"))
        h.advanceTo(1_000L)
        h.factory.transports.forEach { it.send("X1;") }
        h.settle()
        h.manager.disconnect(a.id)
        h.settle()
        assertEquals(SessionState.Disconnected, a.state.value)
        assertIs<SessionState.Connected>(b.state.value)
        assertTrue(h.factory.transports[0].closed)
        assertFalse(h.factory.transports[1].closed)
    }

    @Test
    fun the_manager_has_no_limit_on_the_number_of_sessions() = runTest {
        val h = SessionHarness(this)
        val sessions = (1..25).map { index -> h.connect(ref(AdapterAddress.Fake("s$index"), "s$index")) }
        h.advanceTo(1_000L)
        h.factory.transports.forEach { it.send("X1;") }
        h.advanceTo(1_100L)
        assertEquals(25, h.manager.sessions.value.size)
        assertTrue(sessions.all { it.state.value is SessionState.Connected })
    }

    private fun SessionHarness.wantAll() {
        capabilities.value = capabilities.value.copy(attachedUsb = listOf(AttachedUsbDevice(USB.vendorId, USB.productId, USB.serial, permitted = true)))
        wanted.value = listOf(
            WantedLink(DeviceId("spp"), "Mill", SPP),
            WantedLink(DeviceId("usb"), "Lathe", USB),
            WantedLink(DeviceId("tcp"), "Router", TCP),
            WantedLink(DeviceId("fake"), "Demo", FAKE),
        )
    }

    @Test
    fun restore_connects_each_remembered_machine_for_all_link_types() = runTest {
        val h = SessionHarness(this)
        h.wantAll()
        h.manager.restore()
        h.settle()
        assertEquals(setOf("spp", "usb", "tcp", "fake"), h.manager.sessions.value.keys.map { it.value }.toSet())
        assertEquals(setOf(SPP, USB, TCP, FAKE), h.factory.transports.map { it.address }.toSet())
        assertTrue(h.manager.sessions.value.values.all { it.state.value == SessionState.AwaitingData })
    }

    @Test
    fun a_remembered_machine_that_goes_offline_stays_and_connects_again() = runTest {
        val h = SessionHarness(this)
        h.wantAll()
        h.manager.restore()
        h.settle()
        h.advanceTo(1_000L)
        h.factory.transports.forEach { it.send("X1;") }
        h.settle()
        val mill = h.manager.session(DeviceId("spp"))!!
        val millTransport = h.factory.transports.single { it.address == SPP }
        millTransport.lose()
        h.settle()
        assertFalse(mill.state.value.isOnline)
        assertTrue(DeviceId("spp") in h.manager.sessions.value)
        h.advanceTo(2_000L)
        assertTrue(mill.state.value.isOnline)
        h.factory.transports.last { it.address == SPP }.send("X2;")
        h.settle()
        assertIs<SessionState.Connected>(mill.state.value)
        assertEquals(2L, mill.inputs.value.channels.getValue('x').rawCount)
    }

    @Test
    fun a_paused_machine_stays_disconnected_and_a_removed_machine_leaves_the_manager() = runTest {
        val h = SessionHarness(this)
        h.wantAll()
        h.manager.restore()
        h.settle()
        h.manager.disconnect(DeviceId("tcp"))
        h.settle()
        assertEquals(SessionState.Disconnected, h.manager.session(DeviceId("tcp"))!!.state.value)
        // The source gives only the machines that are not paused.
        h.wanted.value = h.wanted.value.filterNot { it.deviceId == DeviceId("tcp") }
        val before = h.factory.transports.size
        h.manager.restore()
        h.advanceTo(10_000L)
        assertEquals(SessionState.Disconnected, h.manager.session(DeviceId("tcp"))!!.state.value)
        assertEquals(before, h.factory.transports.size)
        h.manager.remove(DeviceId("usb"))
        h.settle()
        assertNull(h.manager.session(DeviceId("usb")))
        assertTrue(h.factory.transports.single { it.address == USB }.closed)
        assertEquals(3, h.manager.sessions.value.size)
    }
}
