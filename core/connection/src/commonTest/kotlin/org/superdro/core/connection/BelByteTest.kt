package org.superdro.core.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.protocol.AdapterCommand
import org.superdro.core.protocol.CommandResult
import org.superdro.core.protocol.InputChannel
import org.superdro.core.protocol.ValueState

/** The BEL byte rule. */
class BelByteTest {

    private val id = DeviceId("mill")

    @Test
    fun theBelByteGoesOut500MsAfterTheLastWrite() = runTest {
        val h = Harness(this)
        h.links.responder = twoWayAdapter()
        h.setups[id] = defaultSetup()
        val session = h.manager.connect(device("mill", TCP))
        runCurrent()
        val link = h.links.last
        link.receive("X1;")
        runCurrent()
        advanceTimeBy(3_700)
        runCurrent()
        // Connected at 0: BEL at 500, 1000, 1500. The queue writes at 2000 to 2500. Then BEL at 3000 and 3500.
        assertEquals(listOf(500L, 1_000L, 1_500L, 3_000L, 3_500L), link.belTimes)
        val result = async { session.send(AdapterCommand.ClearCounter(InputChannel.X)) }
        runCurrent()
        assertEquals(CommandResult.Completed, result.await())
        assertEquals(3_700L to "#clear_counter:x;", link.commands.last())
        advanceTimeBy(900)
        runCurrent()
        // Each write starts the 500 ms again: the next BEL byte is at 4200, not at 4000.
        assertEquals(listOf(500L, 1_000L, 1_500L, 3_000L, 3_500L, 4_200L), link.belTimes)
        h.manager.disconnect(id)
        runCurrent()
    }

    @Test
    fun theBelByteGoesOutOnlyInConnected() = runTest {
        val h = Harness(this)
        h.setups[id] = defaultSetup()
        val session = h.manager.connect(device("mill", USB))
        runCurrent()
        val link = h.links.last
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(SessionState.AwaitingData, session.state.value)
        assertEquals(emptyList(), link.writes)
        link.receive("X1;")
        runCurrent()
        advanceTimeBy(6_999)
        runCurrent()
        assertTrue(session.state.value is SessionState.Connected)
        val before = link.writes.size
        advanceTimeBy(1)
        runCurrent()
        assertEquals(SessionState.NoData, session.state.value)
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(SessionState.NoData, session.state.value)
        // No BEL byte and no queue write in NoData.
        assertEquals(before, link.writes.size)
        assertTrue(link.writes.all { (time, _) -> time < 27_000L })
        h.manager.disconnect(id)
        runCurrent()
    }

    @Test
    fun twoWayOffWritesNothing() = runTest {
        val h = Harness(this)
        h.links.responder = twoWayAdapter()
        val session = h.manager.connect(device("mill", TCP, twoWay = false))
        runCurrent()
        val link = h.links.last
        repeat(10) {
            link.receive("X1;")
            advanceTimeBy(1_000)
            runCurrent()
        }
        assertTrue(session.state.value is SessionState.Connected)
        assertEquals(emptyList(), link.writes)
        assertEquals(CommandResult.NotSent, session.send(AdapterCommand.ClearCounter(InputChannel.X)))
        assertEquals(RestartResult.NotAccepted(CommandResult.NotSent), session.restartAdapter())
        val info = session.adapterInfo.value
        assertEquals(false, info.twoWayCommunication)
        assertEquals(ValueState.Empty, info.values.mac.state)
        // The readout works with two-way off.
        assertEquals(1L, session.inputs.value.channels.getValue('x').rawCount)
        runCurrent()
        assertEquals(emptyList(), link.writes)
        h.manager.disconnect(id)
        runCurrent()
    }

    @Test
    fun theTwoWaySwitchOfTheProfileAppliesAtOnce() = runTest {
        val h = Harness(this)
        h.links.responder = twoWayAdapter()
        h.setups[id] = defaultSetup()
        val session = h.manager.connect(device("mill", TCP))
        runCurrent()
        val link = h.links.last
        link.receive("X1;")
        runCurrent()
        advanceTimeBy(1_200)
        runCurrent()
        assertEquals(listOf(500L, 1_000L), link.belTimes)
        h.setups[id] = defaultSetup(twoWay = false)
        runCurrent()
        repeat(5) {
            link.receive("X1;")
            advanceTimeBy(1_000)
            runCurrent()
        }
        assertEquals(listOf(500L, 1_000L), link.belTimes)
        assertEquals(emptyList(), link.commands)
        assertEquals(false, session.adapterInfo.value.twoWayCommunication)
        h.manager.disconnect(id)
        runCurrent()
    }

    @Test
    fun noByteGoesOutBeforeTheFirstValidMessage() = runTest {
        val h = Harness(this)
        val session = h.manager.connect(device("mill", SPP))
        runCurrent()
        val link = h.links.last
        // A time stamp, an unknown message, a short form, and line noise are not valid messages.
        link.receive("@3E8;X;\$5;\r\n\u0000junk")
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(SessionState.AwaitingData, session.state.value)
        assertEquals(emptyList(), link.writes)
        link.receive("P0;")
        runCurrent()
        assertEquals(SessionState.Connected(5_000L), session.state.value)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(listOf(5_500L), link.belTimes)
        h.manager.disconnect(id)
        runCurrent()
    }

    @Test
    fun noSecondBelByteGoesOutWhileOneWaits() = runTest {
        val h = Harness(this)
        val session = h.manager.connect(device("mill", SPP))
        runCurrent()
        val link = h.links.last
        val gate = CompletableDeferred<Unit>()
        link.writeGate = gate
        link.receive("X1;")
        runCurrent()
        // The write of the first BEL byte at 500 does not end until 1400.
        repeat(14) {
            advanceTimeBy(100)
            runCurrent()
            link.receive("X1;")
        }
        runCurrent()
        assertEquals(1_400L, h.now())
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(1_400L), link.belTimes)
        advanceTimeBy(500)
        runCurrent()
        assertTrue(session.state.value is SessionState.Connected)
        assertEquals(listOf(1_400L, 1_900L), link.belTimes)
        h.manager.disconnect(id)
        runCurrent()
    }
}
