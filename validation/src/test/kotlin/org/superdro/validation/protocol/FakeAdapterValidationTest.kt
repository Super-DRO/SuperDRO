@file:OptIn(ExperimentalCoroutinesApi::class)

package org.superdro.validation.protocol

import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.AdapterRestartState
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.DeviceSession
import org.superdro.core.connection.RecordSink
import org.superdro.core.connection.RecordedChunk
import org.superdro.core.connection.RestartResult
import org.superdro.core.connection.SessionEvent
import org.superdro.core.connection.SessionState
import org.superdro.core.connection.StreamRecorder
import org.superdro.core.fakeadapter.FakeAdapter
import org.superdro.core.fakeadapter.FakeScenarios
import org.superdro.core.fakeadapter.FakeTransportFactory
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.TransportKind
import org.superdro.core.protocol.AdapterCommand
import org.superdro.core.protocol.CommandResult
import org.superdro.core.protocol.FailureCause
import org.superdro.core.protocol.InputChannel
import org.superdro.core.protocol.InputMode
import org.superdro.core.protocol.ReferenceState
import org.superdro.core.protocol.ValueState
import org.superdro.core.util.Clock
import org.superdro.validation.protocol.SessionHarness.Companion.ref

/**
 * The scenarios of the fake adapter behind the real session, parser,
 * and queue, with virtual time. The expected values come from the protocol rules and
 * the scenario table.
 */
class FakeAdapterValidationTest {

    /** The chunks of each recorded link, for the counts of the messages. */
    private class Recorder : StreamRecorder {
        val chunks = ArrayList<RecordedChunk>()
        override fun open(device: DeviceId, address: AdapterAddress, link: Int): RecordSink = object : RecordSink {
            override fun write(chunk: RecordedChunk) {
                chunks += chunk
            }

            override fun close() = Unit
        }
    }

    private class Fixture(val harness: SessionHarness, val fakes: FakeTransportFactory, val recorder: Recorder) {
        fun connect(scenario: String, record: Boolean = false): DeviceSession = harness.connect(ref(AdapterAddress.Fake(scenario), record = record))

        fun adapter(scenario: String): FakeAdapter = fakes.adapter(scenario)!!
    }

    private fun TestScope.fixture(): Fixture {
        val recorder = Recorder()
        val fakes = FakeTransportFactory(clock = Clock { testScheduler.currentTime })
        val harness = SessionHarness(this, streamRecorder = recorder, extraFactories = mapOf(TransportKind.Fake to fakes))
        return Fixture(harness, fakes, recorder)
    }

    @Test
    fun still_x_y_z_do_not_change_and_20_messages_each_second() = runTest {
        val f = fixture()
        val session = f.connect("still", record = true)
        f.harness.advanceTo(1_000L)
        val x1 = session.inputs.value.channels.getValue('x').rawCount
        f.harness.advanceTo(5_000L)
        assertEquals(x1, session.inputs.value.channels.getValue('x').rawCount)
        assertEquals(FakeAdapter.START_Y, session.inputs.value.channels.getValue('y').rawCount)
        val text = f.recorder.chunks.filter { it.timeMs in 2_000L until 3_000L }.joinToString("") { it.bytes.decodeToString() }
        assertEquals(20, Regex("X-?[0-9]+;").findAll(text).count(), text)
        assertIs<SessionState.Connected>(session.state.value)
    }

    @Test
    fun commands_the_start_queries_get_the_documented_answers() = runTest {
        val f = fixture()
        val session = f.connect("commands")
        f.harness.advanceTo(5_000L)
        val values = session.adapterInfo.value.values
        assertEquals(FakeAdapter.MAC, values.mac.value)
        assertEquals(FakeAdapter.UUID, values.uuid.value)
        assertEquals(FakeAdapter.MODEL, values.model.value)
        assertEquals(1L, values.features.value)
        assertEquals("24:6f:28:a1:b2:c5", values.bluetoothId)
        InputChannel.entries.forEach { channel -> assertIs<ValueState.Reported>(values.modes.getValue(channel).state, "$channel") }
        val received = f.adapter("commands").received
        assertEquals(listOf("#get:mac", "#get:uuid", "#get:features", "#get:uptime", "#get:model") + "xyzwuv".map { "#get_mode:$it" }, received)
    }

    @Test
    fun commands_set_mode_reference_and_clear_with_the_documented_answers() = runTest {
        val f = fixture()
        val session = f.connect("commands")
        val events = f.harness.events(session)
        f.harness.advanceTo(5_000L)
        val setMode = f.harness.start { session.send(AdapterCommand.SetMode(InputChannel.X, InputMode.Tachometer)) }
        f.harness.advanceTo(6_000L)
        assertEquals(CommandResult.Completed, setMode.await())
        assertEquals(InputMode.Tachometer, f.adapter("commands").mode(InputChannel.X))
        assertTrue(events.any { it.second == SessionEvent.RestartNeeded(InputChannel.X) })
        assertEquals(setOf(InputChannel.X), session.adapterInfo.value.restartNeeded)
        val back = f.harness.start { session.send(AdapterCommand.SetMode(InputChannel.X, InputMode.QuadratureAB)) }
        f.harness.advanceTo(7_000L)
        assertEquals(CommandResult.Completed, back.await())
        val reference = f.harness.start { session.send(AdapterCommand.RefAxis(InputChannel.X)) }
        f.harness.advanceTo(8_000L)
        assertEquals(CommandResult.Completed, reference.await())
        f.harness.advanceTo(11_000L)
        assertTrue(events.any { it.second == SessionEvent.Reference(InputChannel.X, ReferenceState.InProgress) })
        assertTrue(events.any { it.second == SessionEvent.Reference(InputChannel.X, ReferenceState.Finished) })
        val clear = f.harness.start { session.send(AdapterCommand.ClearCounter(InputChannel.X)) }
        f.harness.advanceTo(12_000L)
        assertEquals(CommandResult.Completed, clear.await())
    }

    @Test
    fun commands_with_no_ack_a_clear_does_not_complete() = runTest {
        val f = fixture()
        val session = f.connect("commands-no-ack")
        f.harness.advanceTo(5_000L)
        val clear = f.harness.start { session.send(AdapterCommand.ClearCounter(InputChannel.X)) }
        f.harness.advanceTo(20_000L)
        assertTrue(clear.isCompleted)
        assertNotEquals(CommandResult.Completed, clear.await())
        assertEquals(FakeScenarios.CommandsNoAck.identity.mac, session.adapterInfo.value.values.mac.value)
    }

    @Test
    fun busy_once_each_command_completes_after_one_resend() = runTest {
        val f = fixture()
        val session = f.connect("busy-once")
        f.harness.advanceTo(6_000L)
        val values = session.adapterInfo.value.values
        assertEquals(FakeScenarios.BusyOnce.identity.mac, values.mac.value)
        assertEquals(FakeAdapter.MODEL, values.model.value)
        assertEquals(2, f.adapter("busy-once").received.count { it == "#get:mac" })
    }

    @Test
    fun busy_three_times_ends_each_request() = runTest {
        val f = fixture()
        val session = f.connect("busy-three-times")
        f.harness.advanceTo(6_900L)
        val received = f.adapter("busy-three-times").received
        assertEquals(3, received.count { it == "#get:mac" })
        assertEquals(3, received.count { it == "#get:uuid" })
        assertNull(session.adapterInfo.value.values.mac.value)
        assertIs<SessionState.Connected>(session.state.value)
    }

    @Test
    fun bad_data_marks_each_field_failed() = runTest {
        val f = fixture()
        val session = f.connect("bad-data")
        f.harness.advanceTo(4_000L)
        val state = session.adapterInfo.value.values.mac.state
        assertIs<ValueState.Failed>(state)
        assertEquals(FailureCause.Status(0x21), state.cause)
    }

    // A field whose query is still in the queue or on the wire at the 5000 ms mark
    // gets no second query when its slow answer comes. Each field gets its value, and no field gets a second query.
    @Test
    fun slow_id_group_a_slow_answer_that_comes_gives_no_second_query() = runTest {
        val f = fixture()
        val session = f.connect("slow-id-group")
        val connectedAt = (session.state.value as SessionState.Connected).sinceMs
        val retryAt = connectedAt + 2_000L + 5_000L
        f.harness.advanceTo(retryAt)
        val atMark = session.adapterInfo.value.values
        val missing = listOf("mac" to atMark.mac.value, "uuid" to atMark.uuid.value, "features" to atMark.features.value, "uptime" to atMark.uptime.value, "model" to atMark.model.value)
            .filter { it.second == null }.map { it.first }
        assertTrue(missing.isNotEmpty(), "the scenario is not slow")
        f.harness.advanceTo(retryAt + 30_000L)
        val received = f.adapter("slow-id-group").received
        for (name in listOf("mac", "uuid", "features", "uptime", "model")) {
            assertEquals(1, received.count { it == "#get:$name" }, "#get:$name in $received")
        }
        val end = session.adapterInfo.value.values
        assertTrue(listOf(end.mac.value, end.uuid.value, end.features.value, end.uptime.value, end.model.value).all { it != null }, "$end")
    }

    @Test
    fun status_332_dirty_flags_drift_and_an_uptime_that_goes_back() = runTest {
        val f = fixture()
        val session = f.connect("status-332")
        val events = f.harness.events(session)
        f.harness.advanceTo(6_000L)
        val dirty = events.map { it.second }.filterIsInstance<SessionEvent.DirtyFlags>()
        assertTrue(dirty.any { it.mask == 0x05 && it.channels == setOf(InputChannel.X, InputChannel.Z) }, "$dirty")
        f.harness.manager.disconnect(session.id)
        f.harness.settle()
        f.harness.connect(ref(AdapterAddress.Fake("status-332")))
        f.harness.advanceTo(12_000L)
        assertTrue(events.any { it.second is SessionEvent.AdapterRestarted }, "$events")
        assertTrue(session.adapterInfo.value.adapterRestarts >= 1)
    }

    @Test
    fun legacy_numeric_readouts_only_the_start_queries_time_out_and_the_id_group_gets_one_more_query() = runTest {
        val f = fixture()
        val session = f.connect("legacy")
        f.harness.advanceTo(30_000L)
        assertIs<SessionState.Connected>(session.state.value)
        assertEquals(FakeAdapter.START_X, session.inputs.value.channels.getValue('x').rawCount)
        assertEquals(1500L, session.inputs.value.channels.getValue('t').rawCount)
        val received = f.adapter("legacy").received
        listOf("mac", "uuid", "features", "uptime", "model").forEach { name -> assertEquals(2, received.count { it == "#get:$name" }, name) }
        "xyzwuv".forEach { channel -> assertEquals(1, received.count { it == "#get_mode:$channel" }, "$channel") }
        assertNull(session.adapterInfo.value.values.mac.value)
    }

    @Test
    fun stop_data_gives_no_data_7_s_after_the_last_message() = runTest {
        val f = fixture()
        val session = f.connect("stop-data")
        f.harness.advanceTo(11_900L)
        assertIs<SessionState.Connected>(session.state.value)
        f.harness.advanceTo(12_100L)
        assertEquals(SessionState.NoData, session.state.value)
    }

    @Test
    fun drop_link_reconnects_and_the_readout_comes_back() = runTest {
        val f = fixture()
        val session = f.connect("drop-link")
        f.harness.advanceTo(10_000L)
        assertIs<SessionState.Reconnecting>(session.state.value)
        f.harness.advanceTo(11_100L)
        assertIs<SessionState.Connected>(session.state.value)
        assertEquals(2, f.adapter("drop-link").links)
    }

    @Test
    fun refuse_open_two_times_then_connected_after_1_s_and_2_s() = runTest {
        val f = fixture()
        val session = f.connect("refuse-open")
        assertIs<SessionState.Reconnecting>(session.state.value)
        f.harness.advanceTo(2_999L)
        assertFalse(session.state.value is SessionState.Connected)
        f.harness.advanceTo(3_000L)
        assertIs<SessionState.Connected>(session.state.value)
    }

    @Test
    fun restart_after_reboot_connects_again() = runTest {
        val f = fixture()
        val session = f.connect("commands")
        f.harness.advanceTo(5_000L)
        val result = f.harness.start { session.restartAdapter() }
        f.harness.advanceTo(5_100L)
        assertEquals(RestartResult.Accepted, result.await())
        f.harness.advanceTo(7_100L)
        assertIs<SessionState.Connected>(session.state.value)
        assertEquals(2, f.adapter("commands").links)
        assertEquals(AdapterRestartState.Idle, session.adapterInfo.value.restart)
    }

    @Test
    fun restart_fails_shows_restart_failed_and_the_policy_continues() = runTest {
        val f = fixture()
        val session = f.connect("restart-fails")
        val events = f.harness.events(session)
        f.harness.advanceTo(5_000L)
        f.harness.start { session.restartAdapter() }
        f.harness.advanceTo(7_100L)
        assertTrue(events.any { it.second is SessionEvent.RestartFailed }, "$events")
        assertEquals(AdapterRestartState.Failed, session.adapterInfo.value.restart)
        f.harness.advanceTo(200_000L)
        assertIs<SessionState.Connected>(session.state.value)
    }

    @Test
    fun noise_the_parser_recovers_and_the_readout_continues() = runTest {
        val f = fixture()
        val session = f.connect("noise")
        f.harness.advanceTo(2_000L)
        val x1 = session.inputs.value.channels.getValue('x').rawCount
        f.harness.advanceTo(4_000L)
        val x2 = session.inputs.value.channels.getValue('x').rawCount
        assertIs<SessionState.Connected>(session.state.value)
        assertTrue(x2 > x1)
        assertEquals(FakeAdapter.START_Y, session.inputs.value.channels.getValue('y').rawCount)
    }

    @Test
    fun two_inputs_target_and_reference_have_one_time_stamp() = runTest {
        val f = fixture()
        val session = f.connect("two-inputs")
        f.harness.advanceTo(3_000L)
        val channels = session.inputs.value.channels
        assertEquals(channels.getValue('x').timeMs, channels.getValue('w').timeMs)
    }
}
