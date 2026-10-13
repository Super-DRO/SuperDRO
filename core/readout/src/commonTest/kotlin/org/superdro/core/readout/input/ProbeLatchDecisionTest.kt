package org.superdro.core.readout.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.superdro.core.model.AxisId
import org.superdro.core.model.MachineProfile
import org.superdro.core.readout.Fixtures

/**
 * The probe latch: the result depends only on the message times, not on the time of a tick. Each
 * case runs with and without ticks, and both runs give the same event.
 */
class ProbeLatchDecisionTest {

    private sealed interface Step {
        data class Count(val letter: Char, val count: Long, val timeMs: Long) : Step
        data class Probe(val timeMs: Long) : Step
        data class Tick(val nowMs: Long) : Step
    }

    private fun count(letter: Char, count: Long, timeMs: Long): Step = Step.Count(letter, count, timeMs)
    private fun probe(timeMs: Long): Step = Step.Probe(timeMs)
    private fun tick(nowMs: Long): Step = Step.Tick(nowMs)

    private fun run(steps: List<Step>, profile: MachineProfile = Fixtures.onlyX()): List<ProbeEvent> {
        val encoders = listOf('x', 'y', 'z').associateWith { letter -> Fixtures.linear(letter) }
        val stage = InputStage(InputConfiguration(encoders, InputConfiguration.axisTerms(profile), ProbeSettings(debounceMs = 0L)))
        val events = ArrayList<ProbeEvent>()
        steps.forEach { step ->
            events += when (step) {
                is Step.Count -> stage.onAxisCount(step.letter, step.count, step.timeMs)
                is Step.Probe -> stage.onBinaryInput('p', 1, step.timeMs)
                is Step.Tick -> stage.onTick(step.nowMs)
            }
        }
        return events
    }

    /** Runs [steps] without ticks and with [ticks] put in at their times. Both runs must give the same events. */
    private fun bothPaths(steps: List<Step>, ticks: List<Long>, profile: MachineProfile = Fixtures.onlyX()): List<ProbeEvent> {
        val plain = run(steps, profile)
        val withTicks = ArrayList(steps)
        ticks.forEach { now ->
            val index = withTicks.indexOfFirst { step -> step is Step.Count && step.timeMs > now }.let { found -> if (found < 0) withTicks.size else found }
            withTicks.add(index, tick(now))
        }
        assertEquals(plain, run(withTicks, profile), "the ticks $ticks change the result")
        return plain
    }

    @Test
    fun theLatchWaitsForTheFirstSampleAfterTOrTheDeadline() {
        assertEquals(emptyList(), run(listOf(count('x', 300L, 100L), probe(200L), tick(699L))))
        assertEquals(emptyList(), run(listOf(count('x', 300L, 100L), probe(200L), count('y', 5L, 700L))), "a message at the deadline is not after it")
        assertEquals(emptyList(), run(listOf(count('x', 300L, 100L), probe(200L), count('x', 300L, 200L))), "a sample at t is not after t")
    }

    @Test
    fun aSampleByTheDeadlineWithAGapOf500MsOrLessGivesTheInterpolation() {
        val event = bothPaths(listOf(count('x', 100L, 1000L), probe(1050L), count('x', 200L, 1100L)), ticks = listOf(1060L, 1099L)).single()
        assertEquals(150.0, event.axisPositions.getValue(AxisId.X))
        assertTrue(event.complete)
        val edge = bothPaths(listOf(count('x', 100L, 1000L), probe(1200L), count('x', 600L, 1500L)), ticks = listOf(1300L)).single()
        assertEquals(300.0, edge.axisPositions.getValue(AxisId.X), "a gap of exactly 500 ms")
        assertTrue(edge.complete)
    }

    @Test
    fun aSampleByTheDeadlineAfterALongGapWithACountChangeIsNotComplete() {
        val event = bothPaths(listOf(count('x', 100L, 0L), probe(400L), count('x', 300L, 600L)), ticks = listOf(500L, 599L)).single()
        assertFalse(event.complete)
        assertEquals(setOf(AxisId.X), event.incompleteAxes)
        assertEquals(100.0, event.axisPositions.getValue(AxisId.X), "the last sample at or before t, not NaN")
        assertEquals(100.0, event.inputPositions.getValue('x'))
        val atDeadline = bothPaths(listOf(count('x', 300L, 100L), probe(200L), count('x', 1300L, 700L)), ticks = listOf(699L)).single()
        assertFalse(atDeadline.complete, "a sample at the deadline comes by the deadline")
        assertEquals(300.0, atDeadline.axisPositions.getValue(AxisId.X))
    }

    @Test
    fun aSampleByTheDeadlineAfterALongGapWithNoCountChangeIsComplete() {
        val event = bothPaths(listOf(count('x', 100L, 0L), probe(400L), count('x', 100L, 600L)), ticks = listOf(500L)).single()
        assertTrue(event.complete)
        assertEquals(100.0, event.axisPositions.getValue(AxisId.X))
    }

    @Test
    fun aSampleAfterTheDeadlineCountsAsTheDeadlineFirst() {
        val steps = listOf(count('x', 300L, 100L), probe(200L), count('x', 1300L, 900L))
        val plain = run(steps).single()
        assertTrue(plain.complete)
        assertEquals(300.0, plain.axisPositions.getValue(AxisId.X))
        assertEquals(plain, run(listOf(count('x', 300L, 100L), probe(200L), tick(701L), count('x', 1300L, 900L))).single())
        assertEquals(plain, run(listOf(count('x', 300L, 100L), probe(200L), tick(700L), count('x', 1300L, 900L))).single(), "a tick at the deadline")
    }

    @Test
    fun aMessageOfAnotherInputAfterTheDeadlineResolvesTheLatch() {
        val profile = Fixtures.mill().let { mill -> mill.copy(z = mill.z.copy(enabled = false)) }
        val steps = listOf(count('x', 300L, 100L), count('y', 50L, 100L), probe(200L), count('y', 50L, 701L))
        val event = bothPaths(steps, ticks = listOf(650L), profile = profile).single()
        assertTrue(event.complete)
        assertEquals(mapOf(AxisId.X to 300.0, AxisId.Y to 50.0), event.axisPositions)
    }

    @Test
    fun aSampleAfterTInTheHistoryUsesTheSameRules() {
        val interpolated = run(listOf(count('x', 100L, 1000L), count('x', 200L, 1100L), probe(1050L))).single()
        assertEquals(150.0, interpolated.axisPositions.getValue(AxisId.X))
        assertTrue(interpolated.complete)
        val afterDeadline = run(listOf(count('x', 100L, 1000L), count('x', 200L, 1600L), probe(1050L))).single()
        assertEquals(100.0, afterDeadline.axisPositions.getValue(AxisId.X))
        assertTrue(afterDeadline.complete)
        val longGap = run(listOf(count('x', 100L, 0L), count('x', 300L, 600L), probe(400L))).single()
        assertEquals(100.0, longGap.axisPositions.getValue(AxisId.X))
        assertFalse(longGap.complete)
    }

    @Test
    fun onlyAnAxisWithNoSampleHasNoPosition() {
        val profile = Fixtures.mill().let { mill -> mill.copy(z = mill.z.copy(enabled = false)) }
        val steps = listOf(count('x', 300L, 100L), probe(200L), count('x', 1300L, 900L))
        val event = bothPaths(steps, ticks = listOf(650L, 701L), profile = profile).single()
        assertEquals(setOf(AxisId.Y), event.incompleteAxes)
        assertTrue(event.axisPositions.getValue(AxisId.Y).isNaN(), "Y has no sample")
        assertEquals(300.0, event.axisPositions.getValue(AxisId.X))
        listOf(
            listOf(count('x', 100L, 0L), probe(400L), count('x', 300L, 600L)),
            listOf(count('x', 100L, 1000L), probe(1050L), count('x', 200L, 1100L)),
            listOf(count('x', 300L, 100L), probe(200L), count('x', 1300L, 900L)),
            listOf(count('x', 300L, 100L), probe(200L), tick(700L)),
        ).forEach { case ->
            val x = run(case).single().axisPositions.getValue(AxisId.X)
            assertFalse(x.isNaN(), "X has a sample, so the latch gives a position: $case")
        }
    }
}
