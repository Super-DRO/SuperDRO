package org.superdro.core.fakeadapter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.AdapterIdentity
import org.superdro.core.connection.CapabilitySource
import org.superdro.core.connection.CapabilityState
import org.superdro.core.connection.ConnectionManager
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.DeviceSession
import org.superdro.core.connection.LinkFailure
import org.superdro.core.connection.LinkFailureException
import org.superdro.core.connection.RestartResult
import org.superdro.core.connection.SessionEvent
import org.superdro.core.connection.SessionState
import org.superdro.core.connection.WantedLinkSource
import org.superdro.core.debugadapter.withFakeTransport
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.AxisId
import org.superdro.core.model.IdentitySource
import org.superdro.core.protocol.AdapterCommand
import org.superdro.core.protocol.AdapterFieldValues
import org.superdro.core.protocol.AdapterMessage
import org.superdro.core.protocol.CommandFailure
import org.superdro.core.protocol.CommandResult
import org.superdro.core.protocol.FailureCause
import org.superdro.core.protocol.InputChannel
import org.superdro.core.protocol.InputMode
import org.superdro.core.protocol.ReferenceState
import org.superdro.core.protocol.ValueState
import org.superdro.core.readout.input.ProbeEvent

/** The scenarios of the fake adapter through a real session. */
class FakeAdapterScenariosTest {

    private fun x(session: DeviceSession): Long = session.inputs.value.channels.getValue('x').rawCount

    @Test
    fun stillSendsTwentyFramesEachSecond() = runTest {
        val h = FakeHarness(this)
        val session = h.connect(FakeScenarios.Still, record = true)
        runCurrent()
        advanceTimeBy(999)
        runCurrent()
        assertTrue(session.state.value is SessionState.Connected)
        assertEquals(10_000L, x(session))
        assertEquals(-5_000.0, session.inputs.value.axisValue(AxisId.Z))
        // The record gets the chunks in batches. The close of the link gives the last batch.
        h.manager.disconnectAll()
        runCurrent()
        val chunks = h.records.getValue(1)
        assertEquals((0L until 1_000L step 50L).toList(), chunks.map { it.timeMs })
        assertEquals("X10000;Y20000;Z-5000;", chunks.first().bytes.decodeToString())
    }

    @Test
    fun theMovesChangeLinearlyAlsoWithTimeStamps() = runTest {
        for ((scenario, speed) in listOf(FakeScenarios.SlowMove to 1L, FakeScenarios.FastMoveTimed to 50L)) {
            val h = FakeHarness(this)
            val start = h.now()
            val session = h.connect(scenario)
            runCurrent()
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(10_000L + 2_000L * speed, x(session))
            assertEquals(start + 2_000L, session.inputs.value.channels.getValue('x').timeMs)
            // 2 s of motion: the feed rate is the speed in µm/min.
            val rate = session.readHistory { history -> history.feedRate(AxisId.X, h.now()) }
            assertEquals(speed * 60_000.0, rate)
            h.manager.disconnectAll()
            runCurrent()
        }
    }

    @Test
    fun theHolePatternVisitsTheHoles() = runTest {
        val h = FakeHarness(this)
        val session = h.connect(FakeScenarios.HolePattern)
        runCurrent()
        advanceTimeBy(1_500)
        runCurrent()
        // Hole 1 of 6 on a circle of 25 mm around (50 mm, 50 mm): (62.500, 71.651) mm.
        assertEquals(62_500L, x(session))
        assertEquals(71_651L, session.inputs.value.channels.getValue('y').rawCount)
        h.manager.disconnectAll()
        runCurrent()
    }

    @Test
    fun theProbeClosesAtKnownPositionsWithAndWithoutRepeats() = runTest {
        for (scenario in listOf(FakeScenarios.Probe, FakeScenarios.ProbeRepeated)) {
            val h = FakeHarness(this)
            val start = h.now()
            val session = h.connect(scenario)
            val events = h.record(session.probeEvents)
            runCurrent()
            advanceTimeBy(3_000)
            runCurrent()
            assertEquals(listOf(1L, 2L), events.map(ProbeEvent::sequence), scenario.name)
            assertEquals(listOf(start + 500L, start + 1_500L), events.map(ProbeEvent::timeMs))
            assertEquals(listOf(10_500.0, 11_500.0), events.map { event -> event.inputPositions.getValue('x') })
            h.manager.disconnectAll()
            runCurrent()
        }
    }

    @Test
    fun changesOnlySendsACountOnlyWhenItChanges() = runTest {
        val h = FakeHarness(this)
        val session = h.connect(FakeScenarios.ChangesOnly, record = true)
        runCurrent()
        advanceTimeBy(2_500)
        runCurrent()
        val chunks = h.records.getValue(1)
        assertEquals("X75000;Y50000;Z-5000;", chunks.first().bytes.decodeToString())
        // At hole 1 from 1000 ms to 2000 ms nothing changes, so nothing goes out.
        assertTrue(chunks.none { chunk -> chunk.timeMs in 1_050L until 2_000L })
        assertTrue(chunks.all { chunk -> !chunk.bytes.decodeToString().contains('Z') || chunk.timeMs == 0L })
        // At 2500 ms, X is half of the way from hole 1 to hole 2.
        assertEquals(50_000L, x(session))
        h.manager.disconnectAll()
        runCurrent()
    }

    @Test
    fun theCommandsGetTheDocumentedAnswers() = runTest {
        val h = FakeHarness(this)
        val session = h.connect(FakeScenarios.Commands)
        val events = h.record(session.events)
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
        val adapter = h.adapter(FakeScenarios.Commands)
        assertEquals(
            listOf("#get:mac", "#get:uuid", "#get:features", "#get:uptime", "#get:model") +
                listOf("x", "y", "z", "w", "u", "v").map { "#get_mode:$it" },
            adapter.received,
        )
        val info = session.adapterInfo.value
        assertEquals(FakeAdapter.MODEL, info.values.model.value)
        assertEquals(3_602L, info.values.uptime.value)
        assertEquals(AdapterIdentity("24:6f:28:a1:b2:c5", IdentitySource.BtMac), info.identity)
        assertEquals(InputMode.DigitalProbe, info.values.modes.getValue(InputChannel.U).value?.current)

        assertEquals(CommandResult.Completed, session.send(AdapterCommand.SetMode(InputChannel.X, InputMode.Tachometer)))
        assertEquals(InputMode.Tachometer, adapter.mode(InputChannel.X))
        assertEquals(setOf(InputChannel.X), session.adapterInfo.value.restartNeeded)
        assertEquals(CommandResult.Completed, session.send(AdapterCommand.RefAxis(InputChannel.Y)))
        advanceTimeBy(2_100)
        runCurrent()
        assertTrue(SessionEvent.Reference(InputChannel.Y, ReferenceState.Finished) in events)
        assertEquals(CommandResult.Completed, session.send(AdapterCommand.ClearCounter(InputChannel.X)))
        advanceTimeBy(100)
        runCurrent()
        assertEquals(0L, x(session))

        val restart = async { session.restartAdapter() }
        runCurrent()
        assertEquals(RestartResult.Accepted, restart.await())
        advanceTimeBy(2_000)
        runCurrent()
        // The adapter restarted: a new link opens 2000 ms after the ack.
        assertEquals(2, adapter.links)
        advanceTimeBy(3_000)
        runCurrent()
        assertTrue(session.state.value is SessionState.Connected)
        assertTrue(events.any { event -> event is SessionEvent.AdapterRestarted })
        assertEquals(emptySet(), session.adapterInfo.value.restartNeeded)
        // The mode stays after the restart, and the counter zero stays.
        assertEquals(InputMode.Tachometer, session.adapterInfo.value.values.modes.getValue(InputChannel.X).value?.current)
        h.manager.disconnectAll()
        runCurrent()
    }

    @Test
    fun aRestartThatDoesNotEndInTimeShowsTheMessage() = runTest {
        val h = FakeHarness(this)
        val session = h.connect(FakeScenarios.RestartFails)
        val events = h.record(session.events)
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
        val start = h.now()
        val restart = async { session.restartAdapter() }
        runCurrent()
        assertEquals(RestartResult.Accepted, restart.await())
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf<SessionEvent>(SessionEvent.RestartFailed(start + 2_000L)), events.filterIsInstance<SessionEvent.RestartFailed>())
        assertEquals(SessionState.Reconnecting(2, start + 3_000L, LinkFailure.Refused), session.state.value)
        h.manager.disconnectAll()
        runCurrent()
    }

    @Test
    fun withNoAckAClearTimesOut() = runTest {
        val h = FakeHarness(this)
        val session = h.connect(FakeScenarios.CommandsNoAck)
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
        // The queries complete with their answers, with no ack.
        assertEquals(FakeScenarios.CommandsNoAck.identity.mac, session.adapterInfo.value.values.mac.value)
        val start = h.now()
        val clear = async { session.send(AdapterCommand.ClearCounter(InputChannel.X)) }
        advanceTimeBy(499)
        runCurrent()
        assertTrue(clear.isActive)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(CommandResult.Failed(CommandFailure.TIMEOUT), clear.await())
        assertEquals(start + 500L, h.now())
        h.manager.disconnectAll()
        runCurrent()
    }

    @Test
    fun busyOnceSendsAgainAfter100Ms() = runTest {
        val h = FakeHarness(this)
        val session = h.connect(FakeScenarios.BusyOnce)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        val received = h.adapter(FakeScenarios.BusyOnce).received
        assertEquals(listOf("#get:mac", "#get:mac", "#get:uuid", "#get:uuid"), received.take(4))
        assertEquals(FakeScenarios.BusyOnce.identity.mac, session.adapterInfo.value.values.mac.value)
        assertEquals(FakeAdapter.MODEL, session.adapterInfo.value.values.model.value)
        h.manager.disconnectAll()
        runCurrent()
    }

    @Test
    fun busyThreeTimesEndsTheRequest() = runTest {
        val h = FakeHarness(this)
        val session = h.connect(FakeScenarios.BusyThreeTimes)
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
        val received = h.adapter(FakeScenarios.BusyThreeTimes).received
        assertEquals(listOf("#get:mac", "#get:mac", "#get:mac", "#get:uuid"), received.take(4))
        val mac = session.adapterInfo.value.values.mac
        assertEquals(null, mac.value)
        assertTrue(mac.state !is ValueState.Reported)
        h.manager.disconnectAll()
        runCurrent()
    }

    @Test
    fun badDataCallsTheErrorFunction() = runTest {
        val h = FakeHarness(this)
        val session = h.connect(FakeScenarios.BadData)
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
        val mac = session.adapterInfo.value.values.mac.state
        assertEquals(FailureCause.Status(0x21), (mac as ValueState.Failed).cause)
        assertEquals(
            CommandResult.Failed(CommandFailure(0x0A, 0x21, "Bad data")),
            session.send(AdapterCommand.ClearCounter(InputChannel.X)),
        )
        h.manager.disconnectAll()
        runCurrent()
    }

    @Test
    fun aSlowIdGroupEndsAfterMoreThan5000Ms() = runTest {
        val h = FakeHarness(this)
        val session = h.connect(FakeScenarios.SlowIdGroup)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(null, session.adapterInfo.value.values.model.value)
        advanceTimeBy(5_000)
        runCurrent()
        val values = session.adapterInfo.value.values
        assertEquals(FakeScenarios.SlowIdGroup.identity.mac, values.mac.value)
        assertEquals(FakeAdapter.MODEL, values.model.value)
        assertEquals(1, h.adapter(FakeScenarios.SlowIdGroup).received.count { it == "#get:mac" })
        h.manager.disconnectAll()
        runCurrent()
    }

    @Test
    fun the3_32_00StatusScenario() = runTest {
        val h = FakeHarness(this)
        val session = h.connect(FakeScenarios.Status332)
        val events = h.record(session.events)
        runCurrent()
        advanceTimeBy(6_000)
        runCurrent()
        assertTrue(SessionEvent.DirtyFlags(0x05, setOf(InputChannel.X, InputChannel.Z)) in events)
        assertEquals(setOf(InputChannel.X, InputChannel.Z), events.filterIsInstance<SessionEvent.DirtyFlags>().first().channels)
        // The `@` time goes back by 400 ms at 5000 ms: the drift rule sets a new offset, so the
        // readout time stays the arrival time.
        assertEquals(h.now(), session.inputs.value.channels.getValue('x').timeMs)
        assertEquals(99_000L, session.adapterInfo.value.values.uptime.value)
        // The next link reports a smaller uptime: an adapter restart.
        h.manager.disconnectAll()
        runCurrent()
        h.connect(FakeScenarios.Status332)
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
        assertTrue(SessionEvent.AdapterRestarted(99_000L, 98_000L) in events)
        h.manager.disconnectAll()
        runCurrent()
    }

    @Test
    fun aDroppedLinkReconnects() = runTest {
        val h = FakeHarness(this)
        val session = h.connect(FakeScenarios.DropLink)
        val states = h.record(session.state)
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(SessionState.Reconnecting(1, 11_000L, LinkFailure.Lost), session.state.value)
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(SessionState.AwaitingData in states)
        assertEquals(2, h.adapter(FakeScenarios.DropLink).links)
        h.manager.disconnectAll()
        runCurrent()
    }

    @Test
    fun stoppedDataGivesNoData() = runTest {
        val h = FakeHarness(this)
        val session = h.connect(FakeScenarios.StopData)
        runCurrent()
        advanceTimeBy(11_949)
        runCurrent()
        assertTrue(session.state.value is SessionState.Connected)
        advanceTimeBy(1)
        runCurrent()
        // The last frame at 4950 ms. The fake link keeps the 30 s NoData limit.
        assertEquals(SessionState.NoData, session.state.value)
        h.manager.disconnectAll()
        runCurrent()
    }

    @Test
    fun noiseBetweenTheFramesDoesNotChangeTheValues() = runTest {
        val h = FakeHarness(this)
        val session = h.connect(FakeScenarios.Noise, record = true)
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
        assertTrue(h.records.getValue(1).any { chunk -> chunk.bytes.decodeToString() == "X12 3;" })
        assertEquals(13_000L, x(session))
        assertEquals(20_000L, session.inputs.value.channels.getValue('y').rawCount)
        h.manager.disconnectAll()
        runCurrent()
    }

    @Test
    fun aRefusedOpenReconnects() = runTest {
        val h = FakeHarness(this)
        val session = h.connect(FakeScenarios.RefuseOpen)
        runCurrent()
        assertEquals(SessionState.Reconnecting(1, 1_000L, LinkFailure.Refused), session.state.value)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(SessionState.Reconnecting(2, 3_000L, LinkFailure.Refused), session.state.value)
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(session.state.value is SessionState.Connected)
        h.manager.disconnectAll()
        runCurrent()
    }

    @Test
    fun aLegacyAdapterSendsNumericReadoutsOnly() = runTest {
        val h = FakeHarness(this)
        val session = h.connect(FakeScenarios.Legacy, record = true)
        val messages = h.record(session.messages)
        runCurrent()
        advanceTimeBy(8_000)
        runCurrent()
        assertEquals("X10000;Y20000;Z-5000;T1500;", h.records.getValue(1).first().bytes.decodeToString())
        assertEquals(emptyList<AdapterMessage>(), messages)
        assertEquals(1_500L, session.inputs.value.channels.getValue('t').rawCount)
        val mac = session.adapterInfo.value.values.mac.state
        assertTrue(mac is ValueState.Failed && mac.cause == FailureCause.Timeout)
        assertTrue(session.state.value is SessionState.Connected)
        h.manager.disconnectAll()
        runCurrent()
    }

    @Test
    fun twoInputsHaveEqualTimeStamps() = runTest {
        val h = FakeHarness(this)
        val session = h.connect(FakeScenarios.TwoInputs)
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        val channels = session.inputs.value.channels
        val target = channels.getValue('x')
        val reference = channels.getValue('w')
        assertEquals(target.timeMs, reference.timeMs)
        assertEquals(12_000L, target.rawCount)
        assertEquals(11_996L, reference.rawCount)
        h.manager.disconnectAll()
        runCurrent()
    }

    @Test
    fun theDemoEntryHasTwoFakeMachinesThatGoOnline() = runTest {
        val h = FakeHarness(this)
        val debug = FakeDebugAdapter(h.clock)
        assertEquals(listOf("Demo mill", "Demo lathe"), debug.demoLinks.map { link -> link.name })
        debug.applySwitch(true)
        assertTrue(debug.active)
        debug.applySwitch(null)
        assertTrue(debug.active)
        val manager = ConnectionManager(
            factories = debug.withFakeTransport(emptyMap()),
            capabilities = object : CapabilitySource {
                override val state = MutableStateFlow(CapabilityState())
            },
            wantedLinks = object : WantedLinkSource {
                override val wantedLinks = flowOf(debug.demoLinks)
            },
            clock = h.clock,
            scope = backgroundScope,
            linkIoDispatcher = StandardTestDispatcher(testScheduler),
        )
        manager.restore()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, manager.sessions.value.size)
        assertTrue(manager.sessions.value.values.all { session -> session.state.value.isOnline })
        val lathe = manager.session(DeviceId(FakeDebugAdapter.DEMO_LATHE_ID))!!
        assertEquals(1_200L, lathe.inputs.value.channels.getValue('t').rawCount)
        assertEquals(FakeScenarios.all.size, debug.discovery.adapters.first().size)
        manager.disconnectAll()
        runCurrent()
    }

    @Test
    fun aNameWithNoScenarioIsRefused() = runTest {
        val factory = FakeTransportFactory()
        val unknown = assertFailsWith<LinkFailureException> { factory.create(AdapterAddress.Fake("no-such")).open() }
        assertEquals(LinkFailure.Refused, unknown.failure)
        val real = assertFailsWith<LinkFailureException> { factory.create(AdapterAddress.Tcp("10.0.0.5")).open() }
        assertEquals(LinkFailure.Refused, real.failure)
        assertTrue(FakeScenarios.all.map { it.name }.toSet().size == FakeScenarios.all.size)
    }

    @Test
    fun eachNamedScenarioHasItsOwnMacAndUuid() = runTest {
        val identities = FakeScenarios.all.map { scenario -> scenario.identity }
        assertEquals(identities.size, identities.map { it.mac }.toSet().size, "one MAC for each scenario")
        assertEquals(identities.size, identities.map { it.uuid }.toSet().size, "one UUID for each scenario")
        assertEquals(FakeIdentity.BASE, FakeScenarios.Commands.identity)
        identities.forEach { identity ->
            assertEquals(identity.mac, AdapterFieldValues.parseMac(identity.mac))
            assertEquals(identity.uuid, AdapterFieldValues.parseUuid(identity.uuid))
        }
        // The two demo machines connect and report two different adapters, so each gets its own adapter profile.
        val h = FakeHarness(this)
        val mill = h.connect(FakeScenarios.DemoMill)
        val lathe = h.connect(FakeScenarios.DemoLathe)
        runCurrent()
        advanceTimeBy(3_000L)
        runCurrent()
        assertEquals("246f28a1c017", mill.adapterInfo.value.values.mac.value)
        assertEquals("246f28a1c018", lathe.adapterInfo.value.values.mac.value)
        assertEquals("24:6f:28:a1:c0:19", mill.adapterInfo.value.identity?.value)
        assertEquals("24:6f:28:a1:c0:1a", lathe.adapterInfo.value.identity?.value)
    }
}
