@file:OptIn(ExperimentalCoroutinesApi::class)

package org.superdro.validation.protocol

import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.AdapterRestartState
import org.superdro.core.connection.AttachedUsbDevice
import org.superdro.core.connection.DeviceSetup
import org.superdro.core.connection.DeviceSetupSource
import org.superdro.core.connection.IdentityCheck
import org.superdro.core.connection.LinkCapability
import org.superdro.core.connection.LinkFailure
import org.superdro.core.connection.LinkFailureException
import org.superdro.core.connection.RestartResult
import org.superdro.core.connection.SessionEvent
import org.superdro.core.connection.SessionState
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.AxisId
import org.superdro.core.model.MachineProfile
import org.superdro.core.protocol.AdapterCommand
import org.superdro.core.protocol.AdapterField
import org.superdro.core.protocol.AdapterValues
import org.superdro.core.protocol.CommandResult
import org.superdro.validation.Traceability
import org.superdro.validation.protocol.SessionHarness.Companion.FAKE
import org.superdro.validation.protocol.SessionHarness.Companion.SPP
import org.superdro.validation.protocol.SessionHarness.Companion.TCP
import org.superdro.validation.protocol.SessionHarness.Companion.USB
import org.superdro.validation.protocol.SessionHarness.Companion.ref

/**
 * The session states, transitions, and timers; the BEL byte rule; the sequences on the wire; the
 * reset of the time offset; and the other session behavior. The
 * sessions run with scripted transports and virtual time.
 */
class SessionValidationTest {

    private fun SessionHarness.usbAttached() {
        capabilities.value = capabilities.value.copy(attachedUsb = listOf(AttachedUsbDevice(USB.vendorId, USB.productId, USB.serial, permitted = true)))
    }

    private val startCommands = QueueDriver.START_QUERIES

    // The states and the transitions.

    @Test
    fun transition_disconnected_to_connecting_1_to_awaiting_data() = runTest {
        val h = SessionHarness(this)
        h.factory.openGate = CompletableDeferred()
        val session = h.connect(ref(SPP))
        assertEquals(SessionState.Connecting(1), session.state.value)
        h.factory.openGate!!.complete(Unit)
        h.settle()
        assertEquals(SessionState.AwaitingData, session.state.value)
    }

    @Test
    fun transition_awaiting_data_to_connected_on_the_first_valid_message() = runTest {
        val h = SessionHarness(this)
        val session = h.connect(ref(SPP))
        h.advanceTo(1_000L)
        // A time stamp and an Unknown are not valid messages.
        h.factory.last.send("@3E8;X12 3;X;")
        h.settle()
        assertEquals(SessionState.AwaitingData, session.state.value)
        h.advanceTo(1_500L)
        h.factory.last.send("\$0aa3;")
        h.settle()
        assertEquals(SessionState.Connected(1_500L), session.state.value)
    }

    @Test
    fun transition_connected_to_no_data_after_7_s_with_no_valid_message() = runTest {
        val h = SessionHarness(this)
        h.usbAttached()
        val session = h.connect(ref(USB))
        h.advanceTo(1_000L)
        h.factory.last.send("X1;")
        h.advanceTo(4_000L)
        h.factory.last.send("@3E8;X;Y12 3;")
        h.advanceTo(7_999L)
        assertIs<SessionState.Connected>(session.state.value)
        h.advanceTo(8_000L)
        assertEquals(SessionState.NoData, session.state.value)
        h.advanceTo(9_000L)
        h.factory.last.send("X2;")
        h.settle()
        assertEquals(SessionState.Connected(9_000L), session.state.value)
        assertEquals(1, h.factory.transports.size)
    }

    @Test
    fun usb_no_data_limit_of_30_s_closes_the_link_and_reconnects() = runTest {
        val h = SessionHarness(this)
        h.usbAttached()
        val session = h.connect(ref(USB))
        h.advanceTo(1_000L)
        h.factory.last.send("X1;")
        h.advanceTo(8_000L)
        assertEquals(SessionState.NoData, session.state.value)
        h.advanceTo(37_999L)
        assertEquals(SessionState.NoData, session.state.value)
        assertFalse(h.factory.last.closed)
        h.advanceTo(38_000L)
        assertIs<SessionState.Reconnecting>(session.state.value)
        assertTrue(h.factory.transports[0].closed)
        h.advanceTo(39_000L)
        assertEquals(2, h.factory.transports.size)
        assertEquals(39_000L, h.factory.createdAt[1])
        assertEquals(SessionState.AwaitingData, session.state.value)
    }

    @Test
    fun awaiting_data_limit_of_30_s_closes_the_link_and_reconnects() = runTest {
        for (address in listOf(SPP, USB, TCP)) {
            val h = SessionHarness(this)
            h.usbAttached()
            val session = h.connect(ref(address))
            assertEquals(SessionState.AwaitingData, session.state.value)
            val start = h.now
            h.advanceTo(start + 29_999L)
            assertEquals(SessionState.AwaitingData, session.state.value, "$address")
            h.advanceTo(start + 30_000L)
            assertIs<SessionState.Reconnecting>(session.state.value, "$address")
            assertTrue(h.factory.transports[0].closed)
            assertEquals(emptyList(), h.factory.transports[0].writes)
            h.advanceTo(start + 31_000L)
            assertEquals(2, h.factory.transports.size, "$address")
            h.manager.close()
            h.settle()
        }
    }

    @Test
    fun no_data_recovery_on_spp_and_tcp_closes_at_once_and_opens_again_after_750_ms() = runTest {
        for (address in listOf(SPP, TCP)) {
            val h = SessionHarness(this)
            val session = h.connect(ref(address))
            val start = h.now
            h.advanceTo(start + 1_000L)
            h.factory.last.send("X1;")
            h.advanceTo(start + 8_000L)
            val state = session.state.value
            assertIs<SessionState.Reconnecting>(state, "$address")
            assertEquals(start + 8_750L, state.retryAtMs)
            assertTrue(h.factory.transports[0].closed)
            h.advanceTo(start + 8_749L)
            assertEquals(1, h.factory.transports.size)
            h.advanceTo(start + 8_750L)
            assertEquals(2, h.factory.transports.size)
            assertEquals(SessionState.AwaitingData, session.state.value)
            h.manager.close()
            h.settle()
        }
    }

    @Test
    fun no_data_recovery_then_the_normal_reconnect_delays_apply() = runTest {
        val h = SessionHarness(this)
        h.connect(ref(SPP))
        h.advanceTo(1_000L)
        h.factory.last.send("X1;")
        h.factory.refuseNext(4)
        h.advanceTo(60_000L)
        assertEquals(listOf(0L, 8_750L, 9_750L, 11_750L, 15_750L, 23_750L), h.factory.createdAt)
    }

    @Test
    fun lost_link_reconnects_with_the_delays_1_2_4_8_16_then_30_s_and_no_end() = runTest {
        val h = SessionHarness(this)
        val session = h.connect(ref(FAKE))
        val states = h.record(session)
        h.advanceTo(1_000L)
        h.factory.last.send("X1;")
        h.factory.refuseNext(10)
        h.advanceTo(2_000L)
        h.factory.last.lose()
        h.advanceTo(220_000L)
        assertEquals(
            listOf(0L, 3_000L, 5_000L, 9_000L, 17_000L, 33_000L, 63_000L, 93_000L, 123_000L, 153_000L, 183_000L, 213_000L),
            h.factory.createdAt,
        )
        // Connecting(1) came before the recorder started.
        val attempts = states.map { it.second }.filterIsInstance<SessionState.Connecting>().map { it.attempt }
        assertEquals((2..12).toList(), attempts)
        assertTrue(states.none { it.second is SessionState.Failed })
        assertEquals(SessionState.AwaitingData, session.state.value)
    }

    @Test
    fun reconnecting_attempt_and_the_next_connecting_attempt() = runTest {
        val h = SessionHarness(this)
        val session = h.connect(ref(FAKE))
        val states = h.record(session)
        h.factory.refuseNext(2)
        h.advanceTo(1_000L)
        h.factory.last.lose()
        h.advanceTo(10_000L)
        // `Reconnecting(attempt, …)` goes to `Connecting(attempt + 1)`.
        val sequence = states.map { it.second }.filter { it is SessionState.Reconnecting || it is SessionState.Connecting }
        for (index in 0 until sequence.size - 1) {
            val current = sequence[index]
            val next = sequence[index + 1]
            if (current is SessionState.Reconnecting && next is SessionState.Connecting) {
                assertEquals(current.attempt + 1, next.attempt, "$current then $next")
            }
        }
    }

    @Test
    fun the_delay_starts_at_1_s_again_after_10_s_in_connected() = runTest {
        val h = SessionHarness(this)
        h.connect(ref(FAKE))
        h.advanceTo(1_000L)
        h.factory.last.send("X1;")
        h.advanceTo(2_000L)
        h.factory.last.lose()
        h.advanceTo(3_000L)
        assertEquals(3_000L, h.factory.createdAt[1])
        h.factory.last.send("X1;")
        h.advanceTo(4_000L)
        h.factory.last.lose()
        // 1 s in Connected: the second failure has the delay 2 s.
        h.advanceTo(6_000L)
        assertEquals(6_000L, h.factory.createdAt[2])
        h.factory.last.send("X1;")
        for (time in 7_000L..16_000L step 1_000L) {
            h.advanceTo(time)
            h.factory.last.send("X1;")
        }
        h.factory.last.lose()
        // 10 s in Connected: the next failure starts at 1 s again.
        h.advanceTo(17_000L)
        assertEquals(17_000L, h.factory.createdAt[3])
    }

    @Test
    fun waiting_for_device_on_the_capability_events_and_connecting_1_when_it_comes_back() = runTest {
        for (cause in listOf(LinkFailure.RadioOff, LinkFailure.PermissionMissing)) {
            val h = SessionHarness(this)
            h.capabilities.value = h.capabilities.value.copy(bluetooth = LinkCapability.Unavailable(cause))
            val session = h.connect(ref(SPP))
            val states = h.record(session)
            assertEquals(SessionState.WaitingForDevice(cause), session.state.value)
            h.advanceTo(h.now + 120_000L)
            assertEquals(SessionState.WaitingForDevice(cause), session.state.value)
            assertEquals(0, h.factory.transports.size)
            h.capabilities.value = h.capabilities.value.copy(bluetooth = LinkCapability.Available)
            h.settle()
            assertTrue(states.any { it.second == SessionState.Connecting(1) }, "$states")
            assertEquals(SessionState.AwaitingData, session.state.value)
            assertEquals(1, h.factory.transports.size)
            h.manager.close()
            h.settle()
        }
    }

    @Test
    fun capability_lost_while_connected_goes_to_waiting_for_device() = runTest {
        val h = SessionHarness(this)
        val session = h.connect(ref(SPP))
        h.advanceTo(1_000L)
        h.factory.last.send("X1;")
        h.advanceTo(1_200L)
        h.capabilities.value = h.capabilities.value.copy(bluetooth = LinkCapability.Unavailable(LinkFailure.RadioOff))
        h.settle()
        assertEquals(SessionState.WaitingForDevice(LinkFailure.RadioOff), session.state.value)
        assertTrue(h.factory.transports[0].closed)
    }

    @Test
    fun usb_detach_gives_waiting_for_device_device_gone_and_attach_starts_again() = runTest {
        val h = SessionHarness(this)
        h.usbAttached()
        val session = h.connect(ref(USB))
        h.advanceTo(1_000L)
        h.factory.last.send("X1;")
        h.advanceTo(2_000L)
        h.capabilities.value = h.capabilities.value.copy(attachedUsb = emptyList())
        h.settle()
        assertEquals(SessionState.WaitingForDevice(LinkFailure.DeviceGone), session.state.value)
        h.advanceTo(50_000L)
        assertEquals(1, h.factory.transports.size)
        h.usbAttached()
        h.settle()
        assertEquals(SessionState.AwaitingData, session.state.value)
        assertEquals(50_000L, h.factory.createdAt[1])
    }

    @Test
    fun tcp_with_no_network_waits_in_waiting_for_device_network_off() = runTest {
        val h = SessionHarness(this)
        h.capabilities.value = h.capabilities.value.copy(network = LinkCapability.Unavailable(LinkFailure.NetworkOff))
        val session = h.connect(ref(TCP))
        assertEquals(SessionState.WaitingForDevice(LinkFailure.NetworkOff), session.state.value)
        h.capabilities.value = h.capabilities.value.copy(network = LinkCapability.Available)
        h.settle()
        assertEquals(SessionState.AwaitingData, session.state.value)
    }

    @Test
    fun an_open_that_fails_with_permission_missing_goes_to_waiting_for_device() = runTest {
        val h = SessionHarness(this)
        h.factory.refuseNext(1, LinkFailure.PermissionMissing)
        val session = h.connect(ref(SPP))
        assertEquals(SessionState.WaitingForDevice(LinkFailure.PermissionMissing), session.state.value)
    }

    @Test
    fun user_disconnect_from_each_state_goes_to_disconnected_with_no_reconnect() = runTest {
        val h = SessionHarness(this)
        val session = h.connect(ref(SPP))
        h.advanceTo(1_000L)
        h.factory.last.send("X1;")
        h.settle()
        h.manager.disconnect(session.id)
        h.settle()
        assertEquals(SessionState.Disconnected, session.state.value)
        assertTrue(h.factory.transports[0].closed)
        h.advanceTo(200_000L)
        assertEquals(1, h.factory.transports.size)
        // The paused machine stays in the manager. A connect resumes it.
        assertEquals(session, h.manager.sessions.value[session.id])
        h.connect(ref(SPP))
        assertEquals(2, h.factory.transports.size)
        assertEquals(SessionState.AwaitingData, session.state.value)
        // From Reconnecting.
        h.factory.last.lose()
        h.settle()
        assertIs<SessionState.Reconnecting>(session.state.value)
        h.manager.disconnect(session.id)
        h.settle()
        assertEquals(SessionState.Disconnected, session.state.value)
        h.advanceTo(400_000L)
        assertEquals(2, h.factory.transports.size)
    }

    @Test
    fun disconnect_all_gives_disconnected_for_each_session() = runTest {
        val h = SessionHarness(this)
        val a = h.connect(ref(SPP, "a"))
        val b = h.connect(ref(TCP, "b"))
        h.manager.disconnectAll()
        h.settle()
        assertEquals(SessionState.Disconnected, a.state.value)
        assertEquals(SessionState.Disconnected, b.state.value)
    }

    // The writes and the BEL byte.

    @Test
    fun bel_no_write_before_the_first_valid_message() = runTest {
        val h = SessionHarness(this)
        h.connect(ref(SPP))
        h.advanceTo(25_000L)
        assertEquals(emptyList(), h.factory.last.writes)
        h.factory.last.send("X1;")
        h.advanceTo(25_499L)
        assertEquals(emptyList(), h.factory.last.writes)
        h.advanceTo(25_500L)
        assertEquals(listOf(25_500L), h.factory.last.belTimes)
    }

    @Test
    fun bel_each_500_ms_then_the_start_queries_at_2000_ms() = runTest {
        val h = SessionHarness(this)
        h.factory.responder = SessionHarness::adapterAnswer
        h.connect(ref(SPP))
        h.advanceTo(1_000L)
        h.factory.last.send("x1234;")
        h.advanceTo(5_100L)
        val transport = h.factory.last
        assertEquals(listOf(1_500L, 2_000L, 2_500L), transport.belTimes.filter { it < 3_000L })
        assertEquals(startCommands, transport.commands.map { it.text })
        assertEquals((0 until 11).map { 3_000L + 50L * it }, transport.commands.map { it.timeMs })
        // Each write starts the 500 ms again: no BEL byte between the commands.
        assertTrue(transport.belTimes.none { it in 3_001L..3_500L }, "${transport.belTimes}")
        assertEquals(listOf(4_000L, 4_500L, 5_000L), transport.belTimes.filter { it > 3_000L })
        assertTrue(transport.writes.all { write -> write.isBel || write.text.startsWith("\"#get:") || write.text.startsWith("#get_mode:") })
    }

    @Test
    fun values_of_the_start_queries_in_adapter_info() = runTest {
        val h = SessionHarness(this)
        h.factory.responder = SessionHarness::adapterAnswer
        val session = h.connect(ref(TCP))
        h.advanceTo(1_000L)
        h.factory.last.send("x1234;")
        h.advanceTo(4_000L)
        val values = session.adapterInfo.value.values
        assertEquals("246f28a1b2c3", values.mac.value)
        assertEquals("tda-example", values.model.value)
        assertEquals(0xe10L, values.uptime.value)
        assertTrue(session.adapterInfo.value.queueActive)
    }

    @Test
    fun bel_no_bel_byte_in_no_data() = runTest {
        val h = SessionHarness(this)
        h.usbAttached()
        h.connect(ref(USB))
        h.advanceTo(1_000L)
        h.factory.last.send("X1;")
        h.advanceTo(8_000L)
        val before = h.factory.last.writes.size
        h.advanceTo(30_000L)
        assertEquals(before, h.factory.last.writes.size)
    }

    @Test
    fun bel_two_way_off_writes_no_byte() = runTest {
        val h = SessionHarness(this)
        h.factory.responder = SessionHarness::adapterAnswer
        val session = h.connect(ref(SPP, twoWay = false))
        for (time in 1_000L..60_000L step 1_000L) {
            h.advanceTo(time)
            h.factory.last.send("X$time;")
        }
        assertIs<SessionState.Connected>(session.state.value)
        assertEquals(emptyList(), h.factory.last.writes)
        val result = h.start { session.send(AdapterCommand.GetField(AdapterField.Name)) }
        assertEquals(CommandResult.NotSent, result.await())
        assertEquals(AdapterValues(), session.adapterInfo.value.values)
        assertFalse(session.adapterInfo.value.twoWayCommunication)
        assertEquals(60_000L, session.inputs.value.channels.getValue('x').rawCount)
        val restart = h.start { session.restartAdapter() }
        h.advanceTo(65_000L)
        assertFalse(restart.await() is RestartResult.Accepted)
        assertEquals(emptyList(), h.factory.last.writes)
    }

    @Test
    fun the_session_writes_only_in_connected() = runTest {
        val h = SessionHarness(this)
        h.usbAttached()
        val session = h.connect(ref(USB))
        val pending = h.start { session.send(AdapterCommand.GetField(AdapterField.Name)) }
        h.advanceTo(20_000L)
        assertEquals(emptyList(), h.factory.last.writes)
        h.factory.last.send("X1;")
        h.advanceTo(27_000L)
        assertTrue(h.factory.last.writes.isNotEmpty())
        val sizeAtNoData = h.factory.last.writes.size
        h.advanceTo(40_000L)
        assertEquals(SessionState.NoData, session.state.value)
        assertEquals(sizeAtNoData, h.factory.last.writes.size)
        assertTrue(pending.isCompleted)
    }

    // The failures.

    @Test
    fun dc16_a_write_failure_is_a_link_failure() = runTest {
        for (failure in listOf<Throwable>(LinkFailureException(LinkFailure.Lost, "write"), IOException("broken pipe"))) {
            val h = SessionHarness(this)
            val session = h.connect(ref(SPP))
            val start = h.now
            h.advanceTo(start + 1_000L)
            h.factory.last.send("X1;")
            h.factory.last.writeFailure = failure
            h.advanceTo(start + 1_500L)
            val state = session.state.value
            assertIs<SessionState.Reconnecting>(state, "$failure")
            assertEquals(LinkFailure.Lost, state.cause)
            assertTrue(h.factory.transports[0].closed)
            h.manager.close()
            h.settle()
        }
    }

    @Test
    fun a_read_failure_goes_to_reconnecting_lost() = runTest {
        val h = SessionHarness(this)
        val session = h.connect(ref(SPP))
        h.advanceTo(1_000L)
        h.factory.last.send("X1;")
        h.advanceTo(1_100L)
        h.factory.last.lose()
        h.settle()
        val state = session.state.value
        assertIs<SessionState.Reconnecting>(state)
        assertEquals(LinkFailure.Lost, state.cause)
        assertEquals(2_100L, state.retryAtMs)
    }

    @Test
    fun a_refused_open_goes_to_reconnecting() = runTest {
        val h = SessionHarness(this)
        h.factory.refuseNext(1)
        val session = h.connect(ref(TCP))
        val state = session.state.value
        assertIs<SessionState.Reconnecting>(state)
        assertEquals(LinkFailure.Refused, state.cause)
    }

    @Test
    fun dc8_each_link_has_a_new_parser_a_new_queue_and_a_new_time_offset() = runTest {
        val h = SessionHarness(this)
        h.factory.responder = SessionHarness::adapterAnswer
        val session = h.connect(ref(SPP))
        h.advanceTo(1_000L)
        h.factory.last.send("@3E8;Y1;X12")
        h.advanceTo(3_600L)
        assertEquals(startCommands, h.factory.last.commands.map { it.text })
        h.factory.last.lose()
        h.advanceTo(4_600L)
        assertEquals(2, h.factory.transports.size)
        h.factory.last.send("34;Z5;")
        h.settle()
        val inputs = session.inputs.value
        assertNull(inputs.channels['x'], "a part message of the old link joined the new link")
        assertEquals(5L, inputs.channels.getValue('z').rawCount)
        assertEquals(4_600L, inputs.channels.getValue('z').timeMs)
        h.advanceTo(8_000L)
        assertEquals(startCommands, h.factory.last.commands.map { it.text })
        assertEquals(6_600L, h.factory.last.commands.first().timeMs)
    }

    @Test
    fun dc11_start_queries_once_for_each_link_also_after_no_data() = runTest {
        val h = SessionHarness(this)
        h.usbAttached()
        h.factory.responder = SessionHarness::adapterAnswer
        h.connect(ref(USB))
        h.advanceTo(1_000L)
        h.factory.last.send("X1;")
        h.advanceTo(12_000L)
        h.factory.last.send("X2;")
        h.advanceTo(30_000L)
        assertEquals(1, h.factory.transports.size)
        assertEquals(startCommands, h.factory.last.commands.map { it.text })
    }

    @Test
    fun time_offset_resets_at_the_change_from_no_data_to_connected() = runTest {
        val h = SessionHarness(this)
        h.usbAttached()
        val session = h.connect(ref(USB))
        h.advanceTo(1_000L)
        h.factory.last.send("@3E8;X1;")
        h.advanceTo(5_000L)
        h.factory.last.send("X2;")
        h.settle()
        assertEquals(1_000L, session.inputs.value.channels.getValue('x').timeMs)
        h.advanceTo(12_000L)
        assertEquals(SessionState.NoData, session.state.value)
        h.advanceTo(13_000L)
        h.factory.last.send("X3;")
        h.advanceTo(13_100L)
        h.factory.last.send("X4;")
        h.settle()
        assertEquals(4L, session.inputs.value.channels.getValue('x').rawCount)
        assertEquals(13_100L, session.inputs.value.channels.getValue('x').timeMs)
    }

    @Test
    fun dc10_no_background_timer_the_link_stays_for_31_minutes() = runTest {
        val h = SessionHarness(this)
        h.factory.responder = SessionHarness::adapterAnswer
        val session = h.connect(ref(SPP))
        var time = 1_000L
        while (time <= 31L * 60_000L) {
            h.advanceTo(time)
            h.factory.last.send("X$time;")
            time += 1_000L
        }
        h.settle()
        assertIs<SessionState.Connected>(session.state.value)
        assertEquals(1, h.factory.transports.size)
        assertEquals(1_000L, (session.state.value as SessionState.Connected).sinceMs)
    }

    @Test
    fun d17_an_exception_in_one_session_stops_only_that_session() = runTest {
        val h = SessionHarness(this)
        val a = h.connect(ref(SPP, "a"))
        val aTransport = h.factory.last
        val b = h.connect(ref(TCP, "b"))
        val bTransport = h.factory.last
        h.advanceTo(1_000L)
        aTransport.send("X1;")
        bTransport.send("X1;")
        h.advanceTo(1_100L)
        aTransport.lose(IllegalStateException("a defect in a listener"))
        h.advanceTo(1_200L)
        assertFalse(a.state.value is SessionState.Connected)
        assertIs<SessionState.Connected>(b.state.value)
        h.advanceTo(3_000L)
        bTransport.send("X2;")
        h.settle()
        assertEquals(2L, b.inputs.value.channels.getValue('x').rawCount)
        assertTrue(bTransport.belTimes.any { it > 1_200L })
    }

    @Test
    fun the_input_stage_gets_each_sample_in_stream_order() = runTest {
        val setup = DeviceSetup(MachineProfile(), AdapterProfile())
        val h = SessionHarness(this, deviceSetups = DeviceSetupSource { flowOf(setup) })
        val session = h.connect(ref(FAKE))
        h.advanceTo(1_000L)
        // One chunk with 50 samples: each `@` gives the next sample a new time.
        h.factory.last.send((1..50).joinToString("") { i -> "@${i.toString(16)};X${i * 10};" })
        h.settle()
        val times = h.start {
            session.readHistory { history ->
                val channel = history.channel('x')!!
                (0 until channel.size).map { index -> channel.timeAt(index) }
            }
        }
        assertEquals((0 until 50).map { 1_000L + it }, times.await())
        assertEquals(500L, session.inputs.value.channels.getValue('x').rawCount)
        assertEquals(2048, org.superdro.core.readout.input.ChannelHistory().capacity)
    }

    // The letters A to F, R, and S.

    @Test
    fun letters_a_to_f_r_and_s_count_for_the_watchdog_but_give_no_value() = runTest {
        val h = SessionHarness(this)
        h.usbAttached()
        val session = h.connect(ref(USB))
        h.advanceTo(1_000L)
        h.factory.last.send("A12;f-3;r1;S0;")
        h.settle()
        assertEquals(SessionState.Connected(1_000L), session.state.value)
        for (time in 2_000L..20_000L step 1_000L) {
            h.advanceTo(time)
            h.factory.last.send("A$time;")
        }
        h.settle()
        assertIs<SessionState.Connected>(session.state.value)
        val inputs = session.inputs.value
        assertTrue(inputs.channels.keys.none { it in "abcdefrs" }, "${inputs.channels.keys}")
        assertTrue(inputs.probes.values.none { it })
        assertEquals(0L, inputs.probeSequence)
    }

    // The identity and the live readout.

    @Test
    fun dc26_the_readout_is_live_in_connected_before_the_identity_is_known() = runTest {
        val setup = DeviceSetup(MachineProfile(), AdapterProfile(identity = "aa:bb:cc:dd:ee:ff"))
        val h = SessionHarness(this, deviceSetups = DeviceSetupSource { flowOf(setup) })
        val session = h.connect(ref(TCP))
        h.advanceTo(1_000L)
        h.factory.last.send("X1200;")
        h.settle()
        assertIs<SessionState.Connected>(session.state.value)
        assertNull(session.adapterInfo.value.identity)
        assertEquals(1200L, session.inputs.value.channels.getValue('x').rawCount)
        assertEquals(1200.0, session.inputs.value.axisValue(AxisId.X))
    }

    @Test
    fun dc25_identity_of_a_tcp_link_is_mac_plus_2_and_a_different_identity_gives_a_warning() = runTest {
        val setup = DeviceSetup(MachineProfile(), AdapterProfile(identity = "aa:bb:cc:dd:ee:ff"))
        val h = SessionHarness(this, deviceSetups = DeviceSetupSource { flowOf(setup) })
        h.factory.responder = SessionHarness::adapterAnswer
        val session = h.connect(ref(TCP))
        val events = h.events(session)
        h.advanceTo(1_000L)
        h.factory.last.send("X1200;")
        h.advanceTo(3_100L)
        assertEquals("24:6f:28:a1:b2:c5", session.adapterInfo.value.identity?.value)
        assertIs<IdentityCheck.Differs>(session.adapterInfo.value.identityCheck)
        assertTrue(events.any { it.second is SessionEvent.IdentityDiffers })
        assertIs<SessionState.Connected>(session.state.value)
        assertEquals(1200.0, session.inputs.value.axisValue(AxisId.X))
    }

    @Test
    fun dc25_identity_of_an_spp_link_is_the_bluetooth_address() = runTest {
        val h = SessionHarness(this)
        h.factory.responder = SessionHarness::adapterAnswer
        val session = h.connect(ref(SPP))
        h.advanceTo(1_000L)
        h.factory.last.send("X1;")
        h.advanceTo(4_000L)
        assertTrue(SPP.mac.equals(session.adapterInfo.value.identity?.value, ignoreCase = true), "${session.adapterInfo.value.identity}")
    }

    @Test
    fun dc25_identity_is_the_uuid_when_the_adapter_gives_no_mac() = runTest {
        val h = SessionHarness(this)
        h.factory.responder = { text -> if (text == QueueDriver.MAC) "!0a21;" else SessionHarness.adapterAnswer(text) }
        val session = h.connect(ref(TCP))
        h.advanceTo(1_000L)
        h.factory.last.send("X1;")
        h.advanceTo(4_000L)
        assertEquals("c0ffee00-1234-4abc-8def-0123456789ab", session.adapterInfo.value.identity?.value)
    }

    // The restart sequence.

    @Test
    fun restart_closes_250_ms_after_the_ack_and_connects_2000_ms_after_the_ack() = runTest {
        for (address in listOf(SPP, TCP)) {
            val h = SessionHarness(this)
            h.factory.responder = SessionHarness::adapterAnswer
            val session = h.connect(ref(address))
            val start = h.now
            h.advanceTo(start + 1_000L)
            h.factory.last.send("X1;")
            h.advanceTo(start + 5_000L)
            val result = h.start { session.restartAdapter() }
            val first = h.factory.transports[0]
            assertEquals("#reboot:1000;", first.commands.last().text)
            assertEquals(start + 5_000L, first.commands.last().timeMs)
            assertEquals(RestartResult.Accepted, result.await())
            h.advanceTo(start + 5_249L)
            assertFalse(first.closed)
            h.advanceTo(start + 5_250L)
            assertTrue(first.closed)
            h.advanceTo(start + 6_999L)
            assertEquals(1, h.factory.transports.size)
            h.advanceTo(start + 7_000L)
            assertEquals(2, h.factory.transports.size)
            assertEquals(address, h.factory.last.address)
            assertEquals(SessionState.AwaitingData, session.state.value)
            // The new link: a new parser, a new queue, and the start queries again.
            h.factory.last.send("X1;")
            h.advanceTo(start + 9_600L)
            assertEquals(startCommands, h.factory.last.commands.map { it.text })
            assertEquals(start + 9_000L, h.factory.last.commands.first().timeMs)
            h.manager.close()
            h.settle()
        }
    }

    @Test
    fun d13_restart_failed_shows_when_the_connect_after_the_restart_fails_and_the_policy_continues() = runTest {
        val h = SessionHarness(this)
        h.factory.responder = SessionHarness::adapterAnswer
        val session = h.connect(ref(SPP))
        val events = h.events(session)
        h.advanceTo(1_000L)
        h.factory.last.send("X1;")
        h.advanceTo(5_000L)
        h.start { session.restartAdapter() }
        h.factory.refuseNext(1)
        h.advanceTo(7_000L)
        assertTrue(events.any { it.second is SessionEvent.RestartFailed }, "$events")
        assertEquals(AdapterRestartState.Failed, session.adapterInfo.value.restart)
        assertIs<SessionState.Reconnecting>(session.state.value)
        h.advanceTo(20_000L)
        assertTrue(h.factory.transports.size >= 3, "the reconnect policy did not continue")
        val strings = Traceability.rootDir.resolve("core/resources/src/commonMain/composeResources/values/strings_connection.xml").readText()
        assertTrue("Could not restart the adapter. Turn the adapter power off and on." in strings)
    }

    @Test
    fun f20_19_restart_with_no_session_sends_nothing() = runTest {
        val h = SessionHarness(this)
        val result = h.start { h.manager.restartAdapter(org.superdro.core.connection.DeviceId("none")) }
        assertEquals(RestartResult.NoSession, result.await())
    }

    // A link drop at the session.

    @Test
    fun a_link_drop_cancels_the_requests_and_marks_fetching_values_failed() = runTest {
        val h = SessionHarness(this)
        val session = h.connect(ref(SPP))
        h.advanceTo(1_000L)
        h.factory.last.send("X1;")
        h.advanceTo(3_010L)
        val result = h.start { session.send(AdapterCommand.ClearCounter(org.superdro.core.protocol.InputChannel.X)) }
        h.factory.last.lose()
        h.settle()
        assertEquals(CommandResult.Cancelled, result.await())
        val values = session.adapterInfo.value.values
        assertTrue(values.mac.state is org.superdro.core.protocol.ValueState.Failed)
        assertEquals(emptySet(), session.adapterInfo.value.busyChannels)
    }

    @Test
    fun f20_18_a_smaller_uptime_tells_an_adapter_restart() = runTest {
        val h = SessionHarness(this)
        h.factory.responder = SessionHarness::adapterAnswer
        val session = h.connect(ref(SPP))
        val events = h.events(session)
        h.advanceTo(1_000L)
        h.factory.last.send("X1;")
        h.advanceTo(4_000L)
        h.factory.last.send("?0910;")
        h.settle()
        assertTrue(events.any { (_, event) -> event is SessionEvent.AdapterRestarted && event.oldUptime == 0xe10L && event.newUptime == 0x10L }, "$events")
        assertEquals(1, session.adapterInfo.value.adapterRestarts)
    }

    @Test
    fun adapter_details_at_the_session_queue_the_group_and_again_at_connected() = runTest {
        val h = SessionHarness(this)
        h.usbAttached()
        h.factory.responder = SessionHarness::adapterAnswer
        val session = h.connect(ref(USB))
        h.advanceTo(1_000L)
        h.factory.last.send("X1;")
        h.advanceTo(4_000L)
        session.openAdapterDetails()
        h.advanceTo(5_000L)
        assertEquals(startCommands + QueueDriver.DETAILS_QUERIES, h.factory.last.commands.map { it.text })
        h.advanceTo(13_000L)
        assertEquals(SessionState.NoData, session.state.value)
        h.factory.last.send("X2;")
        h.advanceTo(14_000L)
        assertEquals(startCommands + QueueDriver.DETAILS_QUERIES + QueueDriver.DETAILS_QUERIES, h.factory.last.commands.map { it.text })
        assertEquals("Mill adapter", session.adapterInfo.value.values.name.value)
    }

    @Test
    fun f1_30_tab_status_texts() {
        val strings = File(Traceability.rootDir, "core/resources/src/commonMain/composeResources/values/strings_app.xml").readText()
        assertTrue(">Could not restore the link<" in strings)
        assertTrue(Regex(""">Reconnecting[^<]*<""").containsMatchIn(strings))
    }

    @Test
    fun link_failures_and_the_state_that_follows() {
        assertFalse(LinkFailure.Refused.waitsForCapability)
        assertFalse(LinkFailure.Lost.waitsForCapability)
        assertTrue(LinkFailure.PermissionMissing.waitsForCapability)
        assertTrue(LinkFailure.RadioOff.waitsForCapability)
        assertTrue(LinkFailure.NetworkOff.waitsForCapability)
        assertTrue(LinkFailure.DeviceGone.waitsForCapability)
    }

    @Test
    fun dc15_no_get_sig_and_no_signature_gate_on_the_commands() = runTest {
        for (twoWay in listOf(true, false)) {
            val h = SessionHarness(this)
            h.factory.responder = SessionHarness::adapterAnswer
            val session = h.connect(ref(SPP, twoWay = twoWay))
            val start = h.now
            for (time in 1_000L..40_000L step 1_000L) {
                h.advanceTo(start + time)
                h.factory.last.send("X1;")
            }
            assertTrue(h.factory.last.commands.none { it.text.startsWith("#get_sig") })
            if (twoWay) {
                // No signature check: a configuration command goes out with no signature.
                val result = h.start { session.send(AdapterCommand.ClearCounter(org.superdro.core.protocol.InputChannel.X)) }
                h.advanceTo(h.now + 100L)
                assertEquals(CommandResult.Completed, result.await())
            }
            h.manager.close()
            h.settle()
        }
    }
}
