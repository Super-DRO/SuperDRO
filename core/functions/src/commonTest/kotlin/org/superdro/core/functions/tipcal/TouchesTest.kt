package org.superdro.core.functions.tipcal

import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The touches of one clock position and the statistics of "Continue". */
class TouchesTest {

    private fun touch(x: Double, y: Double, t: Long = 0L) = Touch(x, y, -300.0, 0.0, t)

    private fun collect(vararg touches: Touch): TouchCollection =
        touches.fold(TouchCollection(ClockPosition.Nine)) { collection, next ->
            (collection.add(next) as TouchResult.Added).touches
        }

    @Test
    fun aTouchWithNoPositionIsRefused() {
        val collection = TouchCollection(ClockPosition.Nine)
        assertEquals(TouchResult.NoPosition, collection.add(touch(Double.NaN, 3.0)))
        assertEquals(TouchResult.NoPosition, collection.add(touch(-11505.4, Double.NaN)))
    }

    @Test
    fun theThirdTouchGivesTheLongBeep() {
        var collection = TouchCollection(ClockPosition.Nine)
        val beeps = (1..4).map { index ->
            val result = collection.add(touch(-11505.0 - index, 3.0, index.toLong())) as TouchResult.Added
            collection = result.touches
            result.beep
        }
        assertEquals(listOf(TouchBeep.ShortBeep, TouchBeep.ShortBeep, TouchBeep.LongBeep, TouchBeep.ShortBeep), beeps)
        assertEquals(4, collection.count)
    }

    @Test
    fun continueIsEnabledFromThreeTouches() {
        assertEquals(3, TouchCollection.MINIMUM_TOUCHES)
        assertEquals(10, TouchCollection.PROGRESS_MAXIMUM)
        val two = collect(touch(0.0, 0.0), touch(1.0, 0.0))
        assertEquals(false, two.canContinue)
        assertEquals(ContinueResult.NotEnoughTouches, TouchStatistics.capture(two, 20.0))
        assertTrue(collect(touch(0.0, 0.0), touch(1.0, 0.0), touch(2.0, 0.0)).canContinue)
    }

    @Test
    fun theWorkedTouchesGiveTheMeanAndThePopulationSigma() {
        val collection = collect(touch(-11505.4, 3.0), touch(-11507.1, 3.4), touch(-11506.1, 2.9))
        val position = assertIs<ContinueResult.Captured>(TouchStatistics.capture(collection, 20.0)).position
        assertEquals(-11506.2, position.xUm, 1e-9)
        assertEquals(3.1, position.yUm, 1e-9)
        assertEquals(sqrt(1.46 / 3.0), position.sigmaXUm, 1e-9)
        assertEquals(sqrt(0.14 / 3.0), position.sigmaYUm, 1e-9)
        // SuperDRO: the largest distance between two touches (touch 1 and touch 2).
        assertEquals(hypot(1.7, 0.4), position.spreadUm, 1e-9)
    }

    @Test
    fun theSpreadIsTheLargestDistanceAndNotTheFirstToLastDistance() {
        // The first touch and the last touch agree (1 µm). The middle touch is 30 µm away.
        val collection = collect(touch(0.0, 0.0), touch(30.0, 0.0), touch(1.0, 0.0))
        assertEquals(30.0, TouchStatistics.spreadUm(collection.touches))
        // The spread is more than the 20 µm limit, so SuperDRO refuses the position.
        val result = assertIs<ContinueResult.Scattered>(TouchStatistics.capture(collection, 20.0))
        assertEquals(30.0, result.spreadUm)
        assertEquals(20.0, result.limitUm)
    }

    @Test
    fun aSpreadEqualToTheLimitIsAccepted() {
        val collection = collect(touch(0.0, 0.0), touch(20.0, 0.0), touch(10.0, 0.0))
        assertIs<ContinueResult.Captured>(TouchStatistics.capture(collection, 20.0))
    }

    @Test
    fun thePopulationSigmaDividesByTheCount() {
        assertEquals(1.0, TouchStatistics.populationSigma(listOf(1.0, 3.0)))
        assertEquals(0.0, TouchStatistics.populationSigma(listOf(5.0)))
        assertEquals(0.0, TouchStatistics.populationSigma(emptyList()))
        assertEquals(0.0, TouchStatistics.spreadUm(listOf(touch(4.0, 4.0))))
    }
}
