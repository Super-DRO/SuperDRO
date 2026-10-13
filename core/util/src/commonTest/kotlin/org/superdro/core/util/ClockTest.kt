package org.superdro.core.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class ClockTest {

    @Test
    fun theTestClockMovesOnlyByHand() {
        val clock = TestClock(startMs = 1000L)
        assertEquals(1000L, clock.nowMs())
        clock.advanceBy(500L)
        assertEquals(1500L, clock.nowMs())
        clock.set(2000L)
        assertEquals(2000L, clock.nowMs())
    }

    @Test
    fun theTestClockDoesNotGoBack() {
        val clock = TestClock(startMs = 100L)
        assertFailsWith<IllegalArgumentException> { clock.advanceBy(-1L) }
        assertFailsWith<IllegalArgumentException> { clock.set(99L) }
    }

    @Test
    fun theMonotonicClockReadsItsTimeSource() {
        val source = TestTimeSource()
        val clock = MonotonicClock(source)
        assertEquals(0L, clock.nowMs())
        source += 750.milliseconds
        assertEquals(750L, clock.nowMs())
    }

    @Test
    fun theSystemMonotonicClockNeverDecreases() {
        val clock = MonotonicClock()
        val first = clock.nowMs()
        val second = clock.nowMs()
        assertTrue(second >= first)
        assertTrue(first >= 0L)
    }

    @Test
    fun aLambdaIsAClock() {
        var time = 5L
        val clock = Clock { time }
        time = 9L
        assertEquals(9L, clock.nowMs())
    }
}
