package org.superdro.core.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.AxisId
import org.superdro.core.readout.input.ChannelValue
import org.superdro.core.readout.input.InputSnapshot

/**
 * The throttle of [DeviceSession.inputs]: the leading edge, the trailing edge, the immediate
 * publish of a probe change, and [DeviceSession.currentInputs]. All times are virtual.
 */
class InputPublishTest {

    private val id = DeviceId("mill")
    private val period = DeviceSession.INPUT_PUBLISH_MS

    /** A connected session with one first sample. The test starts more than one period later. */
    private class Rig(val h: Harness, val session: DeviceSession, val published: List<InputSnapshot>, val samples: List<ChannelValue>) {
        val link: TestLink get() = h.links.last

        fun x(snapshot: InputSnapshot): Double? = snapshot.axisValue(AxisId.X)
    }

    private fun TestScope.rig(): Rig {
        val h = Harness(this)
        h.setups[id] = defaultSetup(debounceMs = 0L)
        val session = h.manager.connect(device("mill", USB, twoWay = false))
        runCurrent()
        val published = h.record(session.inputs)
        val samples = h.record(session.samples)
        h.links.last.receive("X1;")
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        return Rig(h, session, published, samples)
    }

    private fun TestScope.close(rig: Rig) {
        rig.h.manager.disconnect(id)
        runCurrent()
    }

    @Test
    fun aChangeAfterAQuietPeriodGoesOutAtOnce() = runTest {
        val rig = rig()
        rig.link.receive("X2;")
        runCurrent()
        assertEquals(2.0, rig.x(rig.session.inputs.value))
        close(rig)
    }

    @Test
    fun theChangesInOnePeriodGiveOneValueAtTheEndOfThePeriod() = runTest {
        val rig = rig()
        rig.link.receive("X2;")
        runCurrent()
        val before = rig.published.size
        // Three more chunks in the same period: the inputs keep 2, the current inputs follow.
        listOf("X3;", "X4;", "X5;").forEach { text ->
            advanceTimeBy(4)
            rig.link.receive(text)
            runCurrent()
            assertEquals(2.0, rig.x(rig.session.inputs.value))
        }
        assertEquals(5.0, rig.x(rig.session.currentInputs()))
        // The trailing edge: the last value always arrives, at the end of the period.
        advanceTimeBy(period - 12 - 1)
        runCurrent()
        assertEquals(2.0, rig.x(rig.session.inputs.value))
        advanceTimeBy(1)
        runCurrent()
        assertEquals(5.0, rig.x(rig.session.inputs.value))
        assertEquals(listOf(5.0), rig.published.drop(before).map(rig::x))
        // The samples stay unthrottled: each sample of each chunk.
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), rig.samples.map { sample -> sample.rawCount })
        close(rig)
    }

    @Test
    fun aSteadyStreamGivesAtMostOneValueInEachPeriod() = runTest {
        val rig = rig()
        val before = rig.published.size
        // 500 chunks each second for 1 s.
        repeat(500) { index ->
            rig.link.receive("X${index + 10};")
            advanceTimeBy(2)
            runCurrent()
        }
        advanceTimeBy(period)
        runCurrent()
        val count = rig.published.size - before
        assertTrue(count in 1_000 / period.toInt() - 2..1_000 / period.toInt() + 2, "$count values")
        assertEquals(509.0, rig.x(rig.session.inputs.value))
        assertEquals(500, rig.samples.size - 1)
        close(rig)
    }

    @Test
    fun aProbeChangeGoesOutAtOnceInsideThePeriod() = runTest {
        val rig = rig()
        rig.link.receive("X2;")
        runCurrent()
        advanceTimeBy(2)
        rig.link.receive("X3;P1;")
        runCurrent()
        // The probe contact and the position of its chunk go out with no wait.
        assertEquals(true, rig.session.inputs.value.probes.getValue('p'))
        assertEquals(1L, rig.session.inputs.value.probeSequence)
        assertEquals(3.0, rig.x(rig.session.inputs.value))
        advanceTimeBy(2)
        rig.link.receive("P0;")
        runCurrent()
        // The end of a short contact also goes out at once.
        assertEquals(false, rig.session.inputs.value.probes.getValue('p'))
        close(rig)
    }

    @Test
    fun aTickWithNoChangeMakesNoNewSnapshot() = runTest {
        val rig = rig()
        val snapshot = rig.session.currentInputs()
        val before = rig.published.size
        advanceTimeBy(5 * LinkSession.INPUT_TICK_MS)
        runCurrent()
        assertSame(snapshot, rig.session.currentInputs())
        assertSame(snapshot, rig.session.inputs.value)
        assertEquals(before, rig.published.size)
        close(rig)
    }
}
