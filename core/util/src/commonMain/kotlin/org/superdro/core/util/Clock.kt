package org.superdro.core.util

import kotlin.time.TimeSource

/**
 * A monotonic clock in milliseconds. The sessions, the timers (the BEL byte, the no-data
 * watchdog, the reconnect delays), and the input history use one clock of this type. A test gives a
 * [TestClock] and moves the time by hand.
 *
 * The value is not a wall clock time. It starts at an unknown point and only increases. Use it
 * only for differences between two values.
 */
fun interface Clock {
    /** The time in milliseconds since an unknown fixed point. The value never decreases. */
    fun nowMs(): Long
}

/**
 * The production [Clock]. It reads [TimeSource.Monotonic] and gives the milliseconds since the
 * object was made.
 */
class MonotonicClock(
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) : Clock {
    private val origin = timeSource.markNow()

    override fun nowMs(): Long = origin.elapsedNow().inWholeMilliseconds
}

/**
 * A [Clock] for tests. The time starts at [startMs] and changes only through [advanceBy] and
 * [set]. A test with virtual time of `kotlinx-coroutines-test` can give
 * `TestClock { testScheduler.currentTime }` instead.
 */
class TestClock(startMs: Long = 0L) : Clock {
    private var current = startMs

    override fun nowMs(): Long = current

    /** Moves the time forward by [deltaMs]. A negative value is not permitted. */
    fun advanceBy(deltaMs: Long) {
        require(deltaMs >= 0) { "A monotonic clock cannot go back ($deltaMs ms)." }
        current += deltaMs
    }

    /** Sets the time to [timeMs]. The value must not be less than the current time. */
    fun set(timeMs: Long) {
        require(timeMs >= current) { "A monotonic clock cannot go back ($current ms to $timeMs ms)." }
        current = timeMs
    }
}
