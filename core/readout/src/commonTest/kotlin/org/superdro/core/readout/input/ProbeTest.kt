package org.superdro.core.readout.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.superdro.core.model.AxisId
import org.superdro.core.model.ProbeContactType
import org.superdro.core.readout.Fixtures
import org.superdro.core.readout.assertNear

/** The probe input stage. */
class ProbeTest {

    @Test
    fun theDebounceAcceptsAnActivationOnlyAfterTheDebounceTime() {
        val probe = ProbeInput('p', ProbeSettings(debounceMs = 200L))
        assertTrue(probe.onMessage(1, 1000L))
        assertFalse(probe.onMessage(0, 1050L))
        assertFalse(probe.active, "a change to not active is accepted at once")
        assertFalse(probe.onMessage(1, 1150L), "150 ms is not more than the debounce")
        assertFalse(probe.active)
        assertTrue(probe.onMessage(1, 1201L), "the repeated P1 is accepted after the debounce")
        assertTrue(probe.active)
    }

    @Test
    fun aDebounceOfZeroAcceptsEachActivationAndANegativeTimeDifferenceIsAccepted() {
        val probe = ProbeInput('p', ProbeSettings(debounceMs = 0L))
        assertTrue(probe.onMessage(1, 1000L))
        probe.onMessage(0, 1001L)
        assertTrue(probe.onMessage(1, 1002L))
        val clockBack = ProbeInput('p', ProbeSettings(debounceMs = 200L))
        assertTrue(clockBack.onMessage(1, 5000L))
        clockBack.onMessage(0, 5010L)
        assertTrue(clockBack.onMessage(1, 4000L), "a clock step back accepts the activation")
    }

    @Test
    fun theContactTypeSetsTheActiveRawValue() {
        assertEquals(1, ProbeSettings(ProbeContactType.Auto).activeValue)
        assertEquals(0, ProbeSettings(ProbeContactType.NormallyClosed).activeValue)
        assertEquals(1, ProbeSettings(ProbeContactType.NormallyOpen).activeValue)
        val closed = ProbeInput('p', ProbeSettings(ProbeContactType.NormallyClosed, debounceMs = 0L))
        assertFalse(closed.onMessage(1, 0L))
        assertTrue(closed.onMessage(0, 10L), "a normally closed contact opens on touch")
    }

    @Test
    fun theWatchdogPeriodIsFiveTimesTheDebounceAndAtLeast1000Ms() {
        assertEquals(1000L, ProbeSettings(debounceMs = 0L).watchdogPeriodMs)
        assertEquals(1000L, ProbeSettings(debounceMs = 200L).watchdogPeriodMs)
        assertEquals(1500L, ProbeSettings(debounceMs = 300L).watchdogPeriodMs)
    }

    @Test
    fun theStateGoesBackBetweenTAnd2TAfterTheLastActiveMessage() {
        val probe = ProbeInput('p', ProbeSettings(debounceMs = 200L))
        probe.advanceTo(0L)
        probe.onMessage(1, 100L)
        assertFalse(probe.advanceTo(1000L), "the first period end: an active message came in the period")
        assertTrue(probe.active)
        assertTrue(probe.advanceTo(2000L), "the second period end: no active message")
        assertFalse(probe.active)
    }

    @Test
    fun aRepeatedActiveMessageKeepsTheStateActive() {
        val probe = ProbeInput('p', ProbeSettings(debounceMs = 200L))
        probe.advanceTo(0L)
        probe.onMessage(1, 100L)
        (1..10).forEach { second ->
            probe.onMessage(1, second * 1000L + 100L)
            assertTrue(probe.active, "second $second")
        }
        assertTrue(probe.advanceTo(13000L))
        assertFalse(probe.active)
    }

    @Test
    fun theLatchInterpolatesBetweenTheSamplesAroundTheProbeTime() {
        val stage = onlyXStage()
        stage.onAxisCount('x', 100L, 1000L)
        assertEquals(emptyList(), stage.onBinaryInput('p', 1, 1050L), "the latch waits for the next sample")
        val events = stage.onAxisCount('x', 200L, 1100L)
        val event = events.single()
        assertEquals(1L, event.sequence)
        assertEquals('p', event.port)
        assertEquals(1050L, event.timeMs)
        assertEquals(150.0, event.axisPositions.getValue(AxisId.X))
        assertEquals(150.0, event.inputPositions.getValue('x'))
        assertTrue(event.complete)
    }

    @Test
    fun aGapOfMoreThan500MsWithACountChangeMarksTheAxisNotComplete() {
        val stage = onlyXStage()
        stage.onAxisCount('x', 100L, 1000L)
        stage.onBinaryInput('p', 1, 1400L)
        // The sample comes by the deadline (1900 ms), and the gap 1000 → 1700 is 700 ms.
        val event = stage.onAxisCount('x', 300L, 1700L).single()
        assertFalse(event.complete)
        assertEquals(setOf(AxisId.X), event.incompleteAxes)
        assertEquals(100.0, event.axisPositions.getValue(AxisId.X), "the last sample at or before t, not NaN")
    }

    @Test
    fun aGapWithNoCountChangeKeepsTheLastPosition() {
        val stage = onlyXStage()
        stage.onAxisCount('x', 100L, 1000L)
        stage.onBinaryInput('p', 1, 1050L)
        val event = stage.onAxisCount('x', 100L, 1700L).single()
        assertTrue(event.complete)
        assertEquals(100.0, event.axisPositions.getValue(AxisId.X))
    }

    @Test
    fun withNoSampleIn500MsTheLatchUsesTheLastSample() {
        val stage = onlyXStage()
        stage.onAxisCount('x', 100L, 1000L)
        stage.onBinaryInput('p', 1, 1050L)
        assertEquals(emptyList(), stage.onTick(1549L))
        val event = stage.onTick(1550L).single()
        assertTrue(event.complete)
        assertEquals(100.0, event.axisPositions.getValue(AxisId.X))
    }

    @Test
    fun anAxisWithNoSampleIsNotComplete() {
        val stage = InputStage(InputConfiguration(mapOf('x' to Fixtures.linear('x')), InputConfiguration.axisTerms(Fixtures.mill()), ProbeSettings(debounceMs = 0L)))
        stage.onAxisCount('x', 100L, 1000L)
        stage.onBinaryInput('p', 1, 1050L)
        val event = stage.onTick(2000L).single()
        assertEquals(setOf(AxisId.Y, AxisId.Z), event.incompleteAxes)
        assertEquals(100.0, event.axisPositions.getValue(AxisId.X))
    }

    @Test
    fun bothPortsUseOneSequenceAndEachEventHasTheVelocities() {
        val stage = onlyXStage()
        (0..30).forEach { step -> stage.onAxisCount('x', step * 50L, step * 50L) }
        assertEquals(emptyList(), stage.onBinaryInput('p', 1, 1500L))
        val first = stage.onAxisCount('x', 1550L, 1550L).single()
        assertEquals(1L, first.sequence)
        assertEquals(1500.0, first.axisPositions.getValue(AxisId.X))
        assertNear(1000.0, first.axisVelocities.getValue(AxisId.X), 1e-9, "1 µm/ms is 1000 µm/s")
        stage.onBinaryInput('q', 1, 1550L)
        val second = stage.onAxisCount('x', 1600L, 1600L).single()
        assertEquals(2L, second.sequence)
        assertEquals('q', second.port)
        assertEquals(2L, stage.snapshot.probeSequence)
        assertEquals(mapOf('p' to true, 'q' to true), stage.snapshot.probes)
    }

    @Test
    fun aLinkStartDiscardsTheOpenLatchesAndClearsTheProbes() {
        val stage = onlyXStage()
        stage.onAxisCount('x', 100L, 1000L)
        stage.onBinaryInput('p', 1, 1050L)
        stage.onLinkStart()
        assertEquals(mapOf('p' to false, 'q' to false), stage.snapshot.probes)
        assertEquals(emptyList(), stage.onTick(5000L))
    }

    private fun onlyXStage(): InputStage = InputStage(
        InputConfiguration(
            mapOf('x' to Fixtures.linear('x')),
            InputConfiguration.axisTerms(Fixtures.onlyX()),
            ProbeSettings(debounceMs = 0L),
        ),
    )
}
