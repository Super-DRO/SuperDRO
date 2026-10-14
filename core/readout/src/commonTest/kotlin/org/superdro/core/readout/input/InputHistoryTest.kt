package org.superdro.core.readout.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.superdro.core.model.AxisId
import org.superdro.core.model.InputConfig
import org.superdro.core.readout.Fixtures
import org.superdro.core.readout.FeedValues
import org.superdro.core.readout.assertNear

/** The input history and the feed rate. */
class InputHistoryTest {

    @Test
    fun theHistoryKeepsTheLast2048Samples() {
        val history = ChannelHistory()
        repeat(3000) { index -> history.add(index.toLong(), index.toLong()) }
        assertEquals(2048, history.size)
        assertEquals(952L, history.timeAt(0))
        assertEquals(2999L, history.newestCount)
    }

    @Test
    fun aSampleWithTheSameTimeReplacesTheNewestSample() {
        val history = ChannelHistory()
        history.add(100L, 5L)
        history.add(100L, 7L)
        assertEquals(1, history.size)
        assertEquals(7L, history.newestCount)
    }

    @Test
    fun thePositionAtATimeIsTheLinearInterpolation() {
        val history = ChannelHistory()
        history.add(1000L, 100L)
        history.add(1100L, 200L)
        assertEquals(150.0, history.countAt(1050L))
        assertEquals(100.0, history.countAt(1000L))
        assertEquals(200.0, history.countAt(1100L))
        assertTrue(history.countAt(999L).isNaN(), "a time before the oldest sample")
        assertTrue(ChannelHistory().countAt(0L).isNaN(), "no samples")
    }

    @Test
    fun aGapOfMoreThan500MsWithACountChangeHasNoPosition() {
        val history = ChannelHistory()
        history.add(1000L, 100L)
        history.add(1600L, 200L)
        assertTrue(history.countAt(1300L).isNaN())
        val still = ChannelHistory()
        still.add(1000L, 100L)
        still.add(1600L, 100L)
        assertEquals(100.0, still.countAt(1300L))
    }

    @Test
    fun aTimeAfterTheNewestSample() {
        val history = ChannelHistory()
        history.add(1000L, 100L)
        history.add(1200L, 101L)
        assertEquals(101.0, history.countAt(1700L), "500 ms or less after the newest sample")
        assertTrue(history.countAt(1701L).isNaN(), "no rest of 1000 ms")
        val rest = ChannelHistory()
        rest.add(0L, 99L)
        rest.add(600L, 101L)
        rest.add(1100L, 100L)
        assertEquals(100.0, rest.countAt(5000L), "the counts stayed within 2 counts for 1000 ms")
        val moved = ChannelHistory()
        moved.add(0L, 90L)
        moved.add(600L, 101L)
        moved.add(1100L, 100L)
        assertTrue(moved.countAt(5000L).isNaN(), "a count 10 away in the last 1000 ms")
    }

    @Test
    fun theFeedRateOf10000UmIn2000MsIs300000UmForEachMinute() {
        val stage = InputStage(InputConfiguration(mapOf('x' to Fixtures.linear('x')), InputConfiguration.axisTerms(Fixtures.mill())))
        stage.onAxisCount('x', 0L, 0L)
        stage.onAxisCount('x', 10000L, 2000L)
        assertEquals(300000.0, stage.history.feedRate(AxisId.X, 2000L))
        assertEquals(300000.0, FeedValues.feedRate(stage.history, AxisId.X, 2000L))
        assertEquals(0.0, stage.history.feedRate(AxisId.Y, 2000L), "no history gives 0")
    }

    @Test
    fun theFeedRateOf250UmEach50Ms() {
        val stage = InputStage(InputConfiguration(mapOf('x' to Fixtures.linear('x')), InputConfiguration.axisTerms(Fixtures.mill())))
        (0..60).forEach { step -> stage.onAxisCount('x', step * 250L, step * 50L) }
        assertEquals(300000.0, stage.history.feedRate(AxisId.X, 3000L))
        assertNear(5000.0, stage.history.axisSpeed(AxisId.X, 3000L, 1000L))
        assertNear(5000.0, stage.history.speed('x', 3000L, 1000L))
    }

    @Test
    fun theAxisPositionIsTheWeightedSumOfItsInputs() {
        val profile = Fixtures.mill().let { mill -> mill.copy(x = mill.x.copy(secondary = InputConfig('w', sign = -1))) }
        val stage = InputStage(
            InputConfiguration(mapOf('x' to Fixtures.linear('x'), 'w' to Fixtures.linear('w', resolution = 5080.0)), InputConfiguration.axisTerms(profile)),
        )
        stage.onAxisCount('x', 1000L, 0L)
        stage.onAxisCount('w', 100L, 0L)
        assertEquals(1000.0 - 500.0, stage.history.axisPositionAt(AxisId.X, 0L))
    }

    @Test
    fun theLowestAxisPositionAfterATimeFindsAShortDip() {
        val stage = InputStage(InputConfiguration(mapOf('z' to Fixtures.linear('z')), InputConfiguration.axisTerms(Fixtures.mill())))
        stage.onAxisCount('z', 0L, 0L)
        stage.onAxisCount('z', -6_000L, 16L)
        stage.onAxisCount('z', -12_000L, 20L)
        stage.onAxisCount('z', -1_000L, 32L)
        val history = stage.history
        assertEquals(-12_000.0, history.axisLowestAfter(AxisId.Z, 16L))
        assertEquals(-1_000.0, history.axisLowestAfter(AxisId.Z, 20L))
        assertTrue(history.axisLowestAfter(AxisId.Z, 32L).isNaN(), "no sample after the time")
        assertTrue(history.axisLowestAfter(AxisId.Y, 0L).isNaN(), "no sample of the axis")
        assertEquals(32L, history.axisNewestTime(AxisId.Z))
        assertEquals(null, history.axisNewestTime(AxisId.Y))
    }

    @Test
    fun aChangeOfTheTermsMakesTheEarlierAxisPositionUnknown() {
        val mill = Fixtures.mill()
        val stage = InputStage(InputConfiguration(mapOf('x' to Fixtures.linear('x'), 'w' to Fixtures.linear('w')), InputConfiguration.axisTerms(mill)))
        stage.onAxisCount('x', 1000L, 0L)
        stage.onAxisCount('w', 5L, 500L)
        val withSecondary = mill.copy(x = mill.x.copy(secondary = InputConfig('w')))
        stage.reconfigure(stage.configuration.copy(axes = InputConfiguration.axisTerms(withSecondary)))
        stage.onAxisCount('x', 1000L, 550L)
        assertTrue(stage.history.axisPositionAt(AxisId.X, 100L).isNaN())
        assertEquals(1005.0, stage.history.axisPositionAt(AxisId.X, 600L))
    }

    @Test
    fun theDirectionIsTheSignOfTheFirstMotionOf5UmIn1000Ms() {
        val history = ChannelHistory()
        history.add(0L, 0L)
        history.add(100L, 2L)
        history.add(200L, 6L)
        history.add(300L, 8L)
        assertEquals(1, history.direction(300L, scale = 1.0, threshold = 5.0, windowMs = 1000L))
        assertEquals(-1, history.direction(300L, scale = -1.0, threshold = 5.0, windowMs = 1000L))
        assertEquals(0, history.direction(300L, scale = 1.0, threshold = 50.0, windowMs = 1000L))
        val gap = ChannelHistory()
        gap.add(0L, 0L)
        gap.add(700L, 1L)
        assertEquals(0, gap.direction(700L, scale = 1.0, threshold = 0.5, windowMs = 1000L), "a gap of more than 500 ms")
    }
}
