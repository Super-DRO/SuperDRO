package org.superdro.core.functions.probe

import kotlin.math.abs

/** One entry of the input history of an axis: a time stamp (session clock, ms) and a position (µm). */
data class PositionSample(val timeMs: Long, val positionUm: Double)

/**
 * The input history of one axis, for the AUTO direction rule. All times use the session clock.
 *
 * The function takes plain values. The readout keeps the history and gives a copy of the entries
 * near the trigger time.
 *
 * @param samples The entries in time order, oldest first.
 */
class AxisHistory(samples: List<PositionSample>) {

    private val entries: List<PositionSample> = samples.sortedBy { sample -> sample.timeMs }

    /**
     * The position at the time [timeMs]: the interpolation
     * between the last entry at or before the time and the first entry after it, when the second
     * entry comes 500 ms or less after the first. Else the last entry at or before the time.
     * Null when no entry is at or before the time.
     */
    fun positionAt(timeMs: Long): Double? {
        val beforeIndex = entries.indexOfLast { sample -> sample.timeMs <= timeMs }
        if (beforeIndex < 0) return null
        val before = entries[beforeIndex]
        val after = entries.getOrNull(beforeIndex + 1)
        if (after == null || before.timeMs == timeMs || after.timeMs - before.timeMs > INTERPOLATION_GAP_MS) {
            return before.positionUm
        }
        val fraction = (timeMs - before.timeMs).toDouble() / (after.timeMs - before.timeMs).toDouble()
        return before.positionUm + fraction * (after.positionUm - before.positionUm)
    }

    /**
     * The direction of the approach at the time [timeMs]: +1, −1, or 0 (no motion). The direction
     * is the sign of the first position difference of 5 µm or more in the 1000 ms before the time.
     * The search looks at the entries from the newest to the oldest. A gap of more than
     * 500 ms between two entries stops the search with 0.
     *
     * ```text
     * prev = t
     * for each entry e with e.time <= t, newest first:
     *     if e.time < t − 1000: return 0
     *     if prev − e.time > 500: return 0
     *     d = pos(t) − e.position
     *     if |d| >= 5 µm: return sign(d)
     *     prev = e.time
     * return 0
     * ```
     */
    fun direction(
        timeMs: Long,
        thresholdUm: Double = DEAD_BAND_UM,
        windowMs: Long = WINDOW_MS,
    ): Int {
        val position = positionAt(timeMs) ?: return 0
        var previous = timeMs
        for (index in entries.indices.reversed()) {
            val entry = entries[index]
            if (entry.timeMs > timeMs) continue
            if (entry.timeMs < timeMs - windowMs) return 0
            if (previous - entry.timeMs > GAP_MS) return 0
            val difference = position - entry.positionUm
            if (abs(difference) >= thresholdUm) return if (difference > 0.0) 1 else -1
            previous = entry.timeMs
        }
        return 0
    }

    companion object {
        /** The motion threshold of the direction rule: 5 µm. */
        const val DEAD_BAND_UM: Double = 5.0

        /** The window of the direction rule: 1000 ms before the trigger. */
        const val WINDOW_MS: Long = 1000L

        /** The largest gap between two entries in the direction search: 500 ms. */
        const val GAP_MS: Long = 500L

        /** The largest gap between two entries for an interpolation: 500 ms. */
        const val INTERPOLATION_GAP_MS: Long = 500L
    }
}
