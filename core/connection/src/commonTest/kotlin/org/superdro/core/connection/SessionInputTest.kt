package org.superdro.core.connection

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.AxisId
import org.superdro.core.model.TransportKind
import org.superdro.core.protocol.AdapterDialect
import org.superdro.core.protocol.AdapterMessage
import org.superdro.core.protocol.InputChannel
import org.superdro.core.readout.input.ProbeEvent
import org.superdro.core.util.Clock

/** The data flow of a session: probe events, the golden stream, the letters, and the setup. */
class SessionInputTest {

    private val id = DeviceId("mill")

    @Test
    fun twoProbeClosuresCloseTogetherAreBothDelivered() = runTest {
        val h = Harness(this)
        h.setups[id] = defaultSetup(debounceMs = 0L)
        val session = h.manager.connect(device("mill", USB, twoWay = false))
        val events = ArrayList<ProbeEvent>()
        // A slow collector: the flow is buffered, so it gets each event.
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
            session.probeEvents.collect { event ->
                delay(1_000)
                events += event
            }
        }
        runCurrent()
        val link = h.links.last
        advanceTimeBy(1_000)
        runCurrent()
        listOf("X100;", "P1;", "X101;", "P0;", "P1;", "X102;", "P0;", "X103;").forEach { text ->
            link.receive(text)
            advanceTimeBy(10)
            runCurrent()
        }
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(listOf(1L, 2L), events.map { event -> event.sequence })
        assertEquals(listOf(1_010L, 1_040L), events.map { event -> event.timeMs })
        assertEquals(listOf('p', 'p'), events.map { event -> event.port })
        // The latch interpolates between the samples before and after the probe time.
        assertTrue(abs(events[0].inputPositions.getValue('x') - 100.5) < 1e-9)
        assertTrue(abs(events[1].inputPositions.getValue('x') - (101.0 + 2.0 / 3.0)) < 1e-9)
        assertTrue(abs(events[0].axisPositions.getValue(AxisId.X) - 100.5) < 1e-9)
        assertEquals(2L, session.inputs.value.probeSequence)
        h.manager.disconnect(id)
        runCurrent()
    }

    @Test
    fun theIgnoredLettersAreValidMessagesButCarryNoValue() = runTest {
        val h = Harness(this)
        h.setups[id] = defaultSetup()
        val session = h.manager.connect(device("mill", USB, twoWay = false))
        runCurrent()
        h.links.last.receive("A12;f-3;r1;S0;")
        runCurrent()
        assertEquals(SessionState.Connected(0L), session.state.value)
        assertEquals(emptyMap(), session.inputs.value.channels)
        // The valid messages feed the watchdog.
        repeat(10) {
            advanceTimeBy(1_000)
            h.links.last.receive("A12;")
            runCurrent()
        }
        assertEquals(SessionState.Connected(0L), session.state.value)
        assertEquals(false, session.inputs.value.probes.getValue('p'))
        h.manager.disconnect(id)
        runCurrent()
    }

    @Test
    fun theNewSetupAppliesAtOnceAlsoInAConnectedSession() = runTest {
        val h = Harness(this)
        val setup = defaultSetup()
        h.setups[id] = setup
        val session = h.manager.connect(device("mill", USB, twoWay = false))
        runCurrent()
        h.links.last.receive("X100;Y200;")
        runCurrent()
        assertEquals(100.0, session.inputs.value.axisValue(AxisId.X))
        assertEquals(200.0, session.inputs.value.axisValue(AxisId.Y))
        // The user changes the resolution of the input x to 2540 counts per inch: 10 µm for each count.
        val adapter = setup.adapter!!
        val x = adapter.encoders.getValue('x').copy(resolution = 2540.0)
        h.setups[id] = setup.copy(adapter = adapter.copy(encoders = adapter.encoders + ('x' to x)))
        runCurrent()
        // At once, with no new sample. The input y does not change.
        assertEquals(1000.0, session.inputs.value.axisValue(AxisId.X))
        assertEquals(200.0, session.inputs.value.axisValue(AxisId.Y))
        assertTrue(session.state.value is SessionState.Connected)
        assertEquals(1, h.links.links.size)
        h.links.last.receive("X101;")
        runCurrent()
        // The new setup went to the inputs at this time: the sample waits for the end of the period.
        assertEquals(1010.0, session.currentInputs().axisValue(AxisId.X))
        advanceTimeBy(DeviceSession.INPUT_PUBLISH_MS)
        runCurrent()
        assertEquals(1010.0, session.inputs.value.axisValue(AxisId.X))
        h.manager.disconnect(id)
        runCurrent()
    }

    @Test
    fun theHistoryGivesThePositionAtATime() = runTest {
        val h = Harness(this)
        h.setups[id] = defaultSetup()
        val session = h.manager.connect(device("mill", USB, twoWay = false))
        runCurrent()
        // The slide moves 10000 µm in 2000 ms, with a sample each 250 ms.
        for (step in 0..8) {
            h.links.last.receive("X${step * 1_250};")
            runCurrent()
            if (step < 8) advanceTimeBy(250)
        }
        runCurrent()
        assertEquals(2_000L, h.now())
        assertEquals(5_000.0, session.readHistory { history -> history.positionAt('x', 1_000L) })
        // 10000 µm in 2000 ms gives 300000 µm/min.
        assertEquals(300_000.0, session.readHistory { history -> history.feedRate(AxisId.X, 2_000L) })
        h.manager.disconnect(id)
        runCurrent()
    }

    @Test
    fun theGoldenStreamPlaysThroughTheReplayTransport() = runTest {
        val clock = Clock { testScheduler.currentTime }
        val address = AdapterAddress.Fake("golden")
        val sink = MemorySink()
        val replay = ArrayList<ReplayTransport>()
        val setups = TestSetups()
        setups[id] = defaultSetup()
        val manager = ConnectionManager(
            factories = mapOf(TransportKind.Fake to TransportFactory { a -> ReplayTransport(a, GOLDEN, clock = clock).also { replay += it } }),
            capabilities = TestCapabilities(),
            wantedLinks = TestWantedLinks(),
            clock = clock,
            scope = backgroundScope,
            linkIoDispatcher = StandardTestDispatcher(testScheduler),
            reconnectPolicy = ReconnectPolicy(FixedRandom(0.5)),
            deviceSetups = setups,
            streamRecorder = StreamRecorder { _, _, _ -> sink },
            timing = AdapterDialect.DEFAULT_TIMING,
        )
        val session = manager.connect(DeviceRef(id, "Golden", address, twoWayCommunication = false, recordStream = true))
        val messages = ArrayList<AdapterMessage>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { session.messages.collect { messages += it } }
        val states = ArrayList<SessionState>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { session.state.collect { states += it } }
        runCurrent()
        assertEquals(SessionState.AwaitingData, session.state.value)
        advanceTimeBy(100)
        runCurrent()
        // X1200; at t0 = 100 ms: Connected.
        assertEquals(SessionState.Connected(100L), session.state.value)
        assertEquals(1200L, session.inputs.value.channels.getValue('x').rawCount)
        advanceTimeBy(100)
        runCurrent()
        // @3E8; and Y-350; at t1 = 200 ms: the time of Y is t1.
        assertEquals(200L, session.inputs.value.channels.getValue('y').timeMs)
        advanceTimeBy(1_000)
        runCurrent()
        // @7D0; and T1500; at 1200 ms: the time of T is t1 + 1000.
        val t = session.inputs.value.channels.getValue('t')
        assertEquals(1500L, t.rawCount)
        assertEquals(1_200L, t.timeMs)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(
            listOf(
                AdapterMessage.Status(0x0A, 0xA3),
                AdapterMessage.Info(5, "30aea4a1b2c3"),
                AdapterMessage.Error(0x0A, 0x21),
                AdapterMessage.Signature("QUJD"),
                AdapterMessage.Signature("QUJDRA=="),
                AdapterMessage.Status(0x32, 0x05),
            ),
            messages,
        )
        // X12Y-5; gives Y = −5. ?64QUJ;X1; gives X = 1. F5; and f5; carry no value.
        val channels = session.inputs.value.channels
        assertEquals(-5L, channels.getValue('y').rawCount)
        assertEquals(1L, channels.getValue('x').rawCount)
        assertTrue('f' !in channels)
        // The P1; of the golden stream activates the probe port p.
        assertEquals(1L, session.inputs.value.probeSequence)
        // The record of the stream has each chunk with its time after the open.
        assertEquals(GOLDEN, sink.chunks)
        assertEquals(GOLDEN, RecordedStream.parse(RecordedStream.format(sink.chunks)))
        // The stream ends with no more data: NoData 7 s after the last valid message.
        advanceTimeBy(7_000)
        runCurrent()
        assertTrue(SessionState.NoData in states)
        assertEquals(emptyList(), replay.first().written)
        manager.disconnect(id)
        runCurrent()
        assertTrue(sink.closed)
        assertEquals(setOf<InputChannel>(), session.adapterInfo.value.busyChannels)
    }

    companion object {
        /** The worked byte stream, in chunks with their times. */
        val GOLDEN: List<RecordedChunk> = listOf(
            100L to "X1200;",
            200L to "@3E8;Y-350;P1;",
            1_200L to "@7D0;T1500;\r\n",
            1_300L to "\$0AA3;?0530aea4a1b2c3;!0A21;",
            1_400L to "X12 3;F5;f5;X12Y-5;P10;",
            1_500L to "?64QUJD;?64QUJDRA==;?64QUJ;X1;",
            1_600L to "\$5;\$3205;?64QUJ*RA==;@;P1x;",
        ).map { (time, text) -> RecordedChunk(time, text.encodeToByteArray()) }
    }
}
