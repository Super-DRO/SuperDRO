package org.superdro.core.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.AdapterAddress
import org.superdro.core.protocol.AdapterCommand
import org.superdro.core.protocol.AdapterField
import org.superdro.core.protocol.CommandFailure
import org.superdro.core.protocol.CommandResult
import org.superdro.core.protocol.FailureCause
import org.superdro.core.protocol.InputChannel
import org.superdro.core.protocol.InputMode
import org.superdro.core.protocol.ReferenceState
import org.superdro.core.protocol.ValueState

/**
 * The commands of a session: the restart sequence, the result limits, the reference mark, the
 * "id" group retry, and the adapter restart detection.
 */
class SessionCommandsTest {

    private val id = DeviceId("mill")

    /** Connects a two-way session and lets the start queries end. The time is then 3000 ms. */
    private suspend fun kotlinx.coroutines.test.TestScope.connected(
        h: Harness,
        address: AdapterAddress,
        responder: (String) -> List<String> = twoWayAdapter(),
    ): DeviceSession {
        h.links.responder = responder
        h.setups[id] = defaultSetup()
        val session = h.manager.connect(device("mill", address))
        runCurrent()
        h.links.last.receive("X1;")
        runCurrent()
        repeat(3) {
            advanceTimeBy(1_000)
            h.links.last.receive("X1;")
            runCurrent()
        }
        return session
    }

    @Test
    fun restartNowClosesAfter250MsAndConnectsAfter2000Ms() = runTest {
        for (address in listOf(SPP, TCP, USB)) {
            val h = Harness(this)
            val session = connected(h, address)
            val start = h.now()
            val result = async { session.restartAdapter() }
            runCurrent()
            assertEquals(RestartResult.Accepted, result.await())
            val first = h.links.last
            assertEquals(start to "#reboot:1000;", first.commands.last())
            assertEquals(AdapterRestartState.Restarting, session.adapterInfo.value.restart)
            advanceTimeBy(249)
            runCurrent()
            assertTrue(!first.closed)
            advanceTimeBy(1)
            runCurrent()
            assertTrue(first.closed)
            assertEquals(SessionState.Reconnecting(1, start + 2_000L, LinkFailure.Lost), session.state.value)
            advanceTimeBy(1_749)
            runCurrent()
            assertEquals(1, h.links.links.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(2, h.links.links.size)
            assertEquals(address, h.links.last.address)
            assertEquals(SessionState.AwaitingData, session.state.value)
            assertEquals(AdapterRestartState.Idle, session.adapterInfo.value.restart)
            // The new link has a new queue: the start queries go out again.
            h.links.last.receive("X1;")
            runCurrent()
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals("\"#get:mac;\"", h.links.last.commands.first().second)
            h.manager.disconnect(id)
            runCurrent()
        }
    }

    @Test
    fun aFailedConnectAfterTheRestartShowsTheMessageAndTheReconnectContinues() = runTest {
        val h = Harness(this)
        h.links.openFailures.addAll(listOf(null, LinkFailure.Refused))
        val session = connected(h, TCP)
        val events = h.record(session.events)
        val start = h.now()
        val result = async { session.restartAdapter() }
        runCurrent()
        assertEquals(RestartResult.Accepted, result.await())
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf<SessionEvent>(SessionEvent.RestartFailed(start + 2_000L)), events)
        assertEquals(AdapterRestartState.Failed, session.adapterInfo.value.restart)
        assertEquals(SessionState.Reconnecting(2, start + 3_000L, LinkFailure.Refused), session.state.value)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(SessionState.AwaitingData, session.state.value)
        assertEquals(AdapterRestartState.Idle, session.adapterInfo.value.restart)
        h.manager.disconnect(id)
        runCurrent()
    }

    @Test
    fun restartNowWithNoSessionSendsNothing() = runTest {
        val h = Harness(this)
        assertEquals(RestartResult.NoSession, h.manager.restartAdapter(DeviceId("none")))
        val session = h.manager.connect(device("mill", TCP))
        runCurrent()
        // AwaitingData: no open link that can take the command.
        assertEquals(RestartResult.NoSession, h.manager.restartAdapter(id))
        assertEquals(emptyList(), h.links.last.writes)
        h.manager.disconnect(id)
        runCurrent()
        assertEquals(RestartResult.NoSession, session.restartAdapter())
    }

    @Test
    fun aClearAndAReferenceFailAfter10000MsWithNoResult() = runTest {
        val h = Harness(this)
        val session = h.manager.connect(device("mill", TCP))
        runCurrent()
        val start = h.now()
        // AwaitingData: the queue does not send, so no result comes.
        val clear = async { session.send(AdapterCommand.ClearCounter(InputChannel.X)) }
        val reference = async { session.send(AdapterCommand.RefAxis(InputChannel.Y)) }
        runCurrent()
        assertEquals(setOf(InputChannel.X, InputChannel.Y), session.adapterInfo.value.busyChannels)
        advanceTimeBy(9_999)
        runCurrent()
        assertTrue(clear.isActive && reference.isActive)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(CommandResult.NotConfirmed, clear.await())
        assertEquals(CommandResult.NotConfirmed, reference.await())
        assertEquals(start + 10_000L, h.now())
        assertEquals(emptySet(), session.adapterInfo.value.busyChannels)
        h.manager.disconnect(id)
        runCurrent()
    }

    @Test
    fun aModeWriteCompletesWhenTheReadBackShowsTheNewMode() = runTest {
        val h = Harness(this)
        val adapter = twoWayAdapter()
        var mode = "0002"
        val session = connected(h, TCP) { text ->
            when {
                text == "#set_mode:x,4;" -> {
                    mode = "0004"
                    listOf("\$0aa3;", "\$060;")
                }
                text == "#set_mode:x,8;" -> listOf("\$0aa3;", "\$060;")
                text == "#set_mode:x,16;" -> listOf("\$0aa3;", "!0603;")
                text == "#get_mode:x;" -> listOf("\$0aa3;", "?01x000f$mode;")
                else -> adapter(text)
            }
        }
        val events = h.record(session.events)
        assertEquals(CommandResult.Completed, session.send(AdapterCommand.SetMode(InputChannel.X, InputMode.Tachometer)))
        assertEquals(setOf(InputChannel.X), session.adapterInfo.value.restartNeeded)
        assertEquals(InputMode.Tachometer, session.adapterInfo.value.values.modes.getValue(InputChannel.X).value?.current)
        assertTrue(SessionEvent.RestartNeeded(InputChannel.X) in events)
        // The adapter accepts mode 8, but the read-back shows mode 4: not confirmed.
        assertEquals(CommandResult.NotConfirmed, session.send(AdapterCommand.SetMode(InputChannel.X, InputMode.DigitalProbe)))
        // An error on subsystem 6 shows "Command failed: Invalid argument (6:3)" and keeps the request.
        val failed = async { session.send(AdapterCommand.SetMode(InputChannel.X, InputMode.LimitXY)) }
        // The queue gap: 50 ms after the last queue write.
        advanceTimeBy(50)
        runCurrent()
        val failure = CommandFailure(6, 3, "Invalid argument")
        assertTrue(SessionEvent.CommandFailed(AdapterCommand.SetMode(InputChannel.X, InputMode.LimitXY), failure) in events)
        assertEquals("Command failed: Invalid argument (6:3)", failure.message)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(CommandResult.Failed(failure), failed.await())
        h.manager.disconnect(id)
        runCurrent()
    }

    @Test
    fun theConfigurationCommandsNeedTheProfileSwitch() = runTest {
        val h = Harness(this)
        val session = connected(h, TCP)
        h.setups[id] = defaultSetup(configurationCommands = false)
        runCurrent()
        val before = h.links.last.commands.size
        listOf(
            AdapterCommand.SetMode(InputChannel.X, InputMode.Tachometer),
            AdapterCommand.RefAxis(InputChannel.X),
            AdapterCommand.ClearCounter(InputChannel.X),
            AdapterCommand.Reboot,
        ).forEach { command -> assertEquals(CommandResult.NotSent, session.send(command), command.text) }
        assertEquals(RestartResult.NotAccepted(CommandResult.NotSent), session.restartAdapter())
        // A query does not need the switch.
        assertEquals(CommandResult.Completed, session.send(AdapterCommand.GetField(AdapterField.Name)))
        runCurrent()
        assertEquals(listOf("\"#get:name;\""), h.links.last.commands.drop(before).map { (_, text) -> text })
        // The switch applies at once when the user turns it on.
        h.setups[id] = defaultSetup(configurationCommands = true)
        runCurrent()
        assertEquals(CommandResult.Completed, session.send(AdapterCommand.ClearCounter(InputChannel.X)))
        assertEquals("#clear_counter:x;", h.links.last.commands.last().second)
        h.manager.disconnect(id)
        runCurrent()
    }

    @Test
    fun theReferenceMarkSearchGivesItsEvents() = runTest {
        val h = Harness(this)
        val adapter = twoWayAdapter()
        val session = connected(h, TCP) { text ->
            if (text == "#ref_axis:x,15;") listOf("\$0aa3;", "\$78a0;") else adapter(text)
        }
        val events = h.record(session.events)
        assertEquals(CommandResult.Completed, session.send(AdapterCommand.RefAxis(InputChannel.X)))
        assertEquals("#ref_axis:x,15;", h.links.last.commands.last().second)
        h.links.last.receive("\$78a1;")
        runCurrent()
        h.links.last.receive("!7916;")
        runCurrent()
        assertEquals(
            listOf<SessionEvent>(
                SessionEvent.Reference(InputChannel.X, ReferenceState.InProgress),
                SessionEvent.Reference(InputChannel.X, ReferenceState.Finished),
                SessionEvent.Reference(InputChannel.Y, ReferenceState.TimedOut),
            ),
            events.filterIsInstance<SessionEvent.Reference>(),
        )
        assertEquals(ReferenceState.Finished, session.adapterInfo.value.values.references.getValue(InputChannel.X).value)
        h.manager.disconnect(id)
        runCurrent()
    }

    @Test
    fun aLegacyAdapterGetsOneMoreIdQueryAfter5000Ms() = runTest {
        val h = Harness(this)
        h.setups[id] = defaultSetup()
        val session = h.manager.connect(device("mill", USB))
        runCurrent()
        val link = h.links.last
        repeat(12) {
            link.receive("X123;Y-456;Z789;T1500;")
            advanceTimeBy(1_000)
            runCurrent()
        }
        // The queue starts at 2000. Each query waits 500 ms. The retry at 7000 goes in after the modes.
        assertEquals(
            listOf(2_000L, 2_500L, 3_000L, 3_500L, 4_000L, 7_500L, 8_000L, 8_500L, 9_000L, 9_500L),
            link.commands.filter { (_, text) -> text.startsWith("\"#get:") }.map { (time, _) -> time },
        )
        assertEquals(
            AdapterField.idGroup.map { field -> "\"#get:${field.getName};\"" }.let { group -> group + group },
            link.commands.filter { (_, text) -> text.startsWith("\"#get:") }.map { (_, text) -> text },
        )
        assertEquals(6, link.commands.count { (_, text) -> text.startsWith("#get_mode:") })
        val mac = session.adapterInfo.value.values.mac.state
        assertTrue(mac is ValueState.Failed && mac.cause == FailureCause.Timeout)
        // The legacy readouts work.
        val channels = session.inputs.value.channels
        assertEquals(listOf(123L, -456L, 789L, 1500L), listOf('x', 'y', 'z', 't').map { letter -> channels.getValue(letter).rawCount })
        assertTrue(session.state.value is SessionState.Connected)
        h.manager.disconnect(id)
        runCurrent()
    }

    @Test
    fun aSmallerUptimeTellsAnAdapterRestart() = runTest {
        val h = Harness(this)
        val adapter = twoWayAdapter()
        var uptime = "e10"
        val session = connected(h, TCP) { text ->
            if (text == "\"#get:uptime;\"") listOf("\$0aa3;", "?09$uptime;") else adapter(text)
        }
        val events = h.record(session.events)
        assertEquals(3600L, session.adapterInfo.value.values.uptime.value)
        assertEquals(1L, session.adapterInfo.value.values.features.value)
        uptime = "64"
        h.links.last.lose()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        h.links.last.receive("X1;")
        runCurrent()
        advanceTimeBy(2_150)
        runCurrent()
        // The uptime answer at 2150 ms after Connected: 100 is less than 3600.
        assertTrue(SessionEvent.AdapterRestarted(3600L, 100L) in events)
        assertEquals(1, session.adapterInfo.value.adapterRestarts)
        advanceTimeBy(1_000)
        runCurrent()
        // The feature mask is not constant: the restart cleared it, and the session queried it again.
        assertEquals(2, h.links.last.commands.count { (_, text) -> text == "\"#get:features;\"" })
        assertEquals(1L, session.adapterInfo.value.values.features.value)
        assertEquals("tda-example", session.adapterInfo.value.values.model.value)
        h.manager.disconnect(id)
        runCurrent()
    }

    @Test
    fun theAdapterDetailsQueriesGoOutAtOpen() = runTest {
        val h = Harness(this)
        val session = connected(h, TCP)
        val before = h.links.last.commands.size
        session.openAdapterDetails()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(
            listOf(
                "\"#get:name;\"", "\"#get:version;\"", "\"#get:build;\"", "\"#get:date;\"",
                "#get_mode:x;", "#get_mode:y;", "#get_mode:z;", "#get_mode:w;", "#get_mode:u;", "#get_mode:v;",
            ),
            h.links.last.commands.drop(before).map { (_, text) -> text },
        )
        val info = session.adapterInfo.value
        assertTrue(info.detailsOpen)
        assertEquals("Mill adapter", info.values.name.value)
        assertEquals("24:6f:28:a1:b2:c5", info.values.bluetoothId)
        session.closeAdapterDetails()
        runCurrent()
        h.manager.disconnect(id)
        runCurrent()
    }
}
