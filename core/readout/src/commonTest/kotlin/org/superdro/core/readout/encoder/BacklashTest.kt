package org.superdro.core.readout.encoder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.superdro.core.model.BacklashMode
import org.superdro.core.model.EncoderProfile
import org.superdro.core.model.EncoderType
import org.superdro.core.model.MapPoint

/** The backlash models. */
class BacklashTest {

    @Test
    fun theLagModelAddsTheConstantWhileTheMotionIsUp() {
        val lag = LagBacklash(3L)
        // The first sample with d < 0 (the raw count went up) starts in the state "up".
        assertEquals(103L, lag.apply(100L, -1L))
        assertEquals(104L, lag.apply(101L, -1L))
        assertEquals(105L, lag.apply(102L, -1L))
        // A reversal of 4 counts or less keeps the state.
        assertEquals(104L, lag.apply(101L, 1L))
        assertEquals(103L, lag.apply(100L, 1L))
        assertEquals(102L, lag.apply(99L, 1L))
        assertEquals(101L, lag.apply(98L, 1L))
        // A reversal of 5 counts changes the state to "down".
        assertEquals(97L, lag.apply(97L, 1L))
        assertEquals(485.0, 97 * 5.0)
    }

    @Test
    fun theLagModelStartsDownWhenTheFirstDeltaIsZero() {
        val lag = LagBacklash(3L)
        assertEquals(100L, lag.apply(100L, 0L))
        assertEquals(104L, lag.apply(104L, -4L), "4 counts up is not more than 4")
        assertEquals(108L, lag.apply(105L, -1L), "5 counts up changes the state")
    }

    @Test
    fun theDeadZoneModelWithTheConstant10() {
        val dead = DeadZoneBacklash.constant(10L)
        assertEquals(listOf(0L, 5L, 5L, 5L, 0L), listOf(0L, 15L, 10L, 6L, 0L).map { count -> dead.apply(count, 0L) })
    }

    @Test
    fun theLeadscrewTableOfErrorCompensation153() {
        val dead = DeadZoneBacklash.constant(30L)
        val out = listOf(1000L, 1100L, 1050L, 1060L, 1100L).map { count -> dead.apply(count, 0L) }
        assertEquals(listOf(1000L, 1070L, 1050L, 1050L, 1070L), out)
    }

    @Test
    fun theDeadZoneModelReadsTheMapAtTheOutputPosition() {
        val dead = DeadZoneBacklash.map(MapTable.of(listOf(MapPoint(0, 10), MapPoint(1000, 30))))
        assertEquals(0L, dead.apply(0L, 0L))
        assertEquals(5L, dead.apply(15L, 0L), "B(0) = 10")
        val negative = DeadZoneBacklash.map(MapTable.of(listOf(MapPoint(0, -5))))
        negative.apply(0L, 0L)
        assertEquals(7L, negative.apply(7L, 0L), "a negative value gives 0")
    }

    @Test
    fun theModelComesFromTheEncoderTypeAndTheBacklashMode() {
        assertEquals(BacklashStage.None, BacklashStage.of(EncoderProfile(backlashMode = BacklashMode.None, backlashCounts = 3L)))
        assertIs<LagBacklash>(BacklashStage.of(EncoderProfile(backlashMode = BacklashMode.Constant, backlashCounts = 3L)))
        assertIs<LagBacklash>(BacklashStage.of(EncoderProfile(type = EncoderType.RackPinion, backlashMode = BacklashMode.Map, backlashCounts = 3L)))
        assertEquals(BacklashStage.None, BacklashStage.of(EncoderProfile(backlashMode = BacklashMode.Constant, backlashCounts = 0L)))
        val leadscrew = EncoderProfile(type = EncoderType.LeadscrewEncoder, backlashMode = BacklashMode.Constant, backlashCounts = 30L)
        assertIs<DeadZoneBacklash>(BacklashStage.of(leadscrew))
        assertIs<DeadZoneBacklash>(BacklashStage.of(leadscrew.copy(backlashMode = BacklashMode.Map, backlashMap = listOf(MapPoint(0, 30)))))
        assertEquals(BacklashStage.None, BacklashStage.of(leadscrew.copy(backlashMode = BacklashMode.Map)), "an empty map gives no backlash")
    }
}
