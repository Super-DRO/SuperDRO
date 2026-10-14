package org.superdro.core.functions.errormap

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The fast periodic error scan of [ErrorMapBuilder] against the first scan
 * ([ReferencePeriodicError]).
 *
 * The tolerance: the same best trial period for each pass, the variance fraction within 1e-9,
 * the amplitude within 1e-9 of its value, and the phase within 1e-7 rad.
 */
class PeriodicErrorScanTest {

    /**
     * A pass of [count] samples. The gaps are random integers from [minGap] to [maxGap]. The
     * delta is `amplitude × sin(2πx / period + phase)` plus a uniform noise of ±[noise].
     */
    private fun pass(
        random: Random,
        count: Int,
        minGap: Int,
        maxGap: Int,
        amplitude: Double,
        period: Double,
        phase: Double,
        noise: Double,
        start: Double = 0.0,
    ): List<MapSample> {
        var x = start
        return List(count) {
            val sample = MapSample(x, amplitude * sin(2.0 * PI * x / period + phase) + noise * (2.0 * random.nextDouble() - 1.0))
            x += random.nextInt(minGap, maxGap + 1)
            sample
        }
    }

    /** Compares the best period of each pass and the result of the fast scan with the reference. */
    private fun assertSameAsReference(passes: List<List<MapSample>>): PeriodicError? {
        val reference = ReferencePeriodicError.bests(passes)
        val eligible = passes.filter { it.size >= 32 }
        val scan = if (eligible.size >= 2) ErrorMapBuilder.periodScan(eligible) else null
        if (reference == null) {
            assertTrue(scan == null || eligible.any { ErrorMapBuilder.bestPeriod(it, scan) == null })
        } else {
            assertNotNull(scan)
            reference.zip(eligible).forEach { (expected, pass) ->
                val actual = assertNotNull(ErrorMapBuilder.bestPeriod(pass, scan))
                assertEquals(expected.period, actual.period, "period")
                assertEquals(expected.fraction, actual.fraction, 1e-9, "fraction")
                assertEquals(expected.amplitude, actual.amplitude, 1e-9 * max(1.0, expected.amplitude), "amplitude")
                val phaseError = abs(sin((expected.phase - actual.phase) / 2.0)) * 2.0
                assertTrue(phaseError < 1e-7, "phase ${expected.phase} != ${actual.phase}")
            }
        }
        val expected = ReferencePeriodicError.periodicError(passes, reference)
        val actual = ErrorMapBuilder.periodicError(passes)
        if (expected == null) {
            assertNull(actual)
        } else {
            assertNotNull(actual)
            assertEquals(expected.periodCounts, actual.periodCounts)
            assertEquals(expected.amplitudeCounts, actual.amplitudeCounts, 1e-9 * expected.amplitudeCounts)
            assertEquals(expected.powerFraction, actual.powerFraction, 1e-9)
            assertEquals(expected.passCount, actual.passCount)
        }
        return actual
    }

    @Test
    fun aPeriodicErrorWithNoiseAndIrregularGapsGivesTheSameResult() {
        val random = Random(1)
        val passes = (0 until 3).map { pass(random, 1000, 1, 60, 6.0, 2540.0, 0.7, 3.0, start = it * 3.0) }
        val periodic = assertNotNull(assertSameAsReference(passes))
        assertTrue(abs(periodic.periodCounts - 2540.0) < 0.05 * 2540.0, "$periodic")
        assertTrue(periodic.amplitudeCounts in 3.0..7.0, "$periodic")
    }

    @Test
    fun largePositionsGiveTheSameResult() {
        val random = Random(2)
        val passes = (0 until 2).map { pass(random, 600, 5, 15, 4.0, 1000.0, 2.0, 1.0, start = -2_000_000.0) }
        assertNotNull(assertSameAsReference(passes))
    }

    @Test
    fun noiseWithNoPeriodicErrorGivesTheSameResult() {
        val random = Random(3)
        val passes = (0 until 4).map { pass(random, 400, 1, 20, 0.0, 1.0, 0.0, 2.0) }
        assertNull(assertSameAsReference(passes))
    }

    @Test
    fun theMinimumNumberOfSamplesGivesTheSameResult() {
        val random = Random(4)
        val passes = (0 until 2).map { pass(random, 32, 3, 9, 5.0, 40.0, 1.0, 0.5) }
        assertSameAsReference(passes)
        // 31 samples in one pass: no periodic error.
        assertNull(ErrorMapBuilder.periodicError(listOf(passes[0], passes[1].take(31))))
    }

    @Test
    fun aPeriodNearTheShortestTrialPeriodGivesTheSameResult() {
        // A constant gap of 10 counts: the shortest trial period is 20 counts. The scan then has
        // an ill-conditioned system at its last frequency, and uses the direct fit there.
        val passes = (0 until 2).map { p -> pass(Random(5 + p), 1500, 10, 10, 3.0, 20.6, 0.3, 0.5) }
        assertNotNull(assertSameAsReference(passes))
    }

    @Test
    fun aPeriodNearTheLongestTrialPeriodGivesTheSameResult() {
        // The span is about 30000 counts: the longest trial period is about 10000 counts.
        val random = Random(6)
        val passes = (0 until 3).map { pass(random, 1200, 15, 35, 8.0, 9800.0, -1.2, 1.0) }
        assertSameAsReference(passes)
    }

    @Test
    fun constantDeltasGiveTheSameResult() {
        val passes = (0 until 2).map { pass(Random(7), 100, 2, 6, 0.0, 1.0, 0.0, 0.0) }
        assertNull(assertSameAsReference(passes))
    }

    @Test
    fun aScanWithOneTrialFrequencyGivesTheSameResult() {
        // Gaps of 5 counts and one gap of 1 count: the shortest period is 10 counts, the span is
        // 31 counts, and the scan has 1 trial frequency.
        val positions = listOf(0.0, 5.0, 10.0, 15.0, 20.0, 25.0, 30.0, 31.0)
        val passes = (0 until 2).map { p ->
            List(40) { MapSample(positions[it % positions.size], 3.0 * cos(positions[it % positions.size] / 2.0 + p) + it % 3) }
        }
        val scan = assertNotNull(ErrorMapBuilder.periodScan(passes))
        assertEquals(1, scan.steps)
        assertSameAsReference(passes)
    }

    @Test
    fun theFastSumsAgreeWithTheDirectSums() {
        val random = Random(8)
        val n = 500
        val count = 300
        val turns = DoubleArray(n) { random.nextDouble() }
        val re = DoubleArray(n) { random.nextDouble() * 2.0 - 1.0 }
        val im = DoubleArray(n) { random.nextDouble() * 2.0 - 1.0 }
        val sums = NonUniformFourier.sums(turns, listOf(re), listOf(im), count)
        val scale = (0 until n).sumOf { hypot(re[it], im[it]) }
        for (k in 0 until count) {
            var expectedRe = 0.0
            var expectedIm = 0.0
            for (j in 0 until n) {
                val angle = 2.0 * PI * k * turns[j]
                expectedRe += re[j] * cos(angle) - im[j] * sin(angle)
                expectedIm += re[j] * sin(angle) + im[j] * cos(angle)
            }
            assertEquals(expectedRe, sums[0][k], 1e-11 * scale, "re $k")
            assertEquals(expectedIm, sums[1][k], 1e-11 * scale, "im $k")
        }
    }

    @Test
    fun theBuildStopsWhenTheCheckThrows() {
        val passes = (0 until 2).map { p ->
            RecordedPass((0..2000L).map { SamplePair(it * 10, it * 10 + (5.0 * sin(it / 30.0)).toLong(), p * 5000L + it) })
        }
        var calls = 0
        ErrorMapBuilder.build(passes) { calls++ }
        assertTrue(calls > 5)
        var left = calls - 1
        assertFailsWith<IllegalStateException> {
            ErrorMapBuilder.build(passes) { check(left-- > 0) { "Cancelled" } }
        }
    }
}
