package org.superdro.core.functions.errormap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PassRecorderTest {

    @Test
    fun aPairNeedsANewTimeAndANewCount() {
        val recorder = PassRecorder()
        assertTrue(recorder.offer(0, 0, 1))
        assertFalse(recorder.offer(0, 0, 2), "the same two counts")
        assertTrue(recorder.offer(1, 1, 2))
        assertFalse(recorder.offer(2, 2, 2), "the same time stamp")
        assertFalse(recorder.offer(2, 2, 1), "an older time stamp")
        assertTrue(recorder.offer(1, 2, 3), "one count changed")
        val pass = recorder.stop()
        assertEquals(listOf(SamplePair(0, 0, 1), SamplePair(1, 1, 2), SamplePair(1, 2, 3)), pass.pairs)
        assertFalse(pass.truncated)
    }

    @Test
    fun theDefaultLimitIs200000Pairs() {
        assertEquals(200_000, PassRecorder.MAX_PAIRS)
        assertEquals(200_000, PassRecorder().maxPairs)
    }

    @Test
    fun thePassIsTruncatedAtTheLimit() {
        val recorder = PassRecorder(maxPairs = 3)
        assertTrue(recorder.offer(0, 0, 1))
        assertTrue(recorder.offer(1, 1, 2))
        assertTrue(recorder.offer(2, 2, 3))
        assertFalse(recorder.offer(3, 3, 4))
        assertTrue(recorder.truncated)
        assertFalse(recorder.offer(4, 4, 5))
        val pass = recorder.stop()
        assertEquals(3, pass.pairs.size)
        assertTrue(pass.truncated)
    }

    @Test
    fun aSpikeInOneInputIsAGlitchAfter8Steps() {
        val recorder = PassRecorder()
        for (i in 0..10) recorder.offer(i * 10L, i * 10L, i.toLong())
        // The target jumps by 1000 counts and comes back; the reference moves on.
        recorder.offer(1100, 110, 11)
        recorder.offer(120, 120, 12)
        recorder.offer(130, 130, 13)
        val pass = recorder.stop()
        assertEquals(1, pass.glitchesDropped)
        assertEquals((0..10).map { it * 10L } + listOf(120L, 130L), pass.pairs.map { it.target })
        assertEquals((0..10).map { it * 10L } + listOf(120L, 130L), pass.pairs.map { it.reference })
    }

    @Test
    fun aSpikeBefore8StepsIsKept() {
        val recorder = PassRecorder()
        for (i in 0..3) recorder.offer(i * 10L, i * 10L, i.toLong())
        recorder.offer(1040, 40, 4)
        recorder.offer(50, 50, 5)
        val pass = recorder.stop()
        assertEquals(0, pass.glitchesDropped)
        assertEquals(6, pass.pairs.size)
    }

    @Test
    fun aRealReversalIsNotAGlitch() {
        val recorder = PassRecorder()
        for (i in 0..10) recorder.offer(i * 10L, i * 10L, i.toLong())
        // A turn: the next pair does not come back to the earlier position.
        recorder.offer(90, 90, 11)
        recorder.offer(80, 80, 12)
        assertEquals(0, recorder.stop().glitchesDropped)
    }

    @Test
    fun thePassRowGivesTheCountTheFarthestOffsetAndTheEnd() {
        val pass = RecordedPass(listOf(SamplePair(1000, 0, 0), SamplePair(1500, 0, 1), SamplePair(3000, 0, 2), SamplePair(2500, 0, 3)))
        assertEquals(PassRowInfo(sampleCount = 4, farthest = 2000, end = 1500), pass.rowInfo())
        assertEquals(null, RecordedPass().rowInfo())
    }
}
