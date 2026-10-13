package org.superdro.core.readout.input

import kotlin.math.abs

/**
 * The time history of one adapter input: the last [capacity] pairs of (time, corrected count).
 * The corrected count is the count after
 * the filter, the error map, and the backlash, before the scale.
 *
 * A sample with the same time as the newest sample replaces the newest sample.
 *
 * The class is not thread-safe. The input stage writes it in the session context. Read it in the
 * same context.
 */
class ChannelHistory(val capacity: Int = CAPACITY) {

    init {
        require(capacity > 1) { "The capacity must be more than 1, but it is $capacity." }
    }

    private val times = LongArray(capacity)
    private val counts = LongArray(capacity)
    private var start = 0

    /** The number of samples, from 0 to [capacity]. */
    var size: Int = 0
        private set

    /** True when the history has no sample. */
    val isEmpty: Boolean get() = size == 0

    /** Adds the sample ([timeMs], [count]). The same time as the newest sample replaces it. */
    fun add(timeMs: Long, count: Long) {
        if (size > 0 && times[slot(size - 1)] == timeMs) {
            counts[slot(size - 1)] = count
            return
        }
        val end = slot(size)
        times[end] = timeMs
        counts[end] = count
        if (size == capacity) start = (start + 1) % capacity else size += 1
    }

    /** Removes all samples. */
    fun clear() {
        start = 0
        size = 0
    }

    /** Gives the time of the sample [index] (0 is the oldest sample). */
    fun timeAt(index: Int): Long = times[checkedSlot(index)]

    /** Gives the count of the sample [index] (0 is the oldest sample). */
    fun countAt(index: Int): Long = counts[checkedSlot(index)]

    /** The time of the newest sample, or null. */
    val newestTime: Long? get() = if (size == 0) null else times[slot(size - 1)]

    /** The count of the newest sample, or null. */
    val newestCount: Long? get() = if (size == 0) null else counts[slot(size - 1)]

    /**
     * Gives the index of the newest sample with a time at or before [timeMs], or −1 when no
     * such sample exists.
     */
    fun indexAtOrBefore(timeMs: Long): Int {
        if (size == 0 || timeAt(0) > timeMs) return -1
        var low = 0
        var high = size - 1
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (timeAt(middle) <= timeMs) low = middle else high = middle - 1
        }
        return low
    }

    /**
     * Gives the corrected count at the time [timeMs], or NaN when it is not known:
     *
     * - No samples, or a time before the oldest sample: NaN.
     * - A time at or after the newest sample, by 500 ms or less: the newest count.
     * - A time more than 500 ms after the newest sample: the newest count when the count stayed
     *   at rest. At rest means that a sample 1000 ms or more before the newest sample exists, and
     *   that all samples back to it are within 2 counts of the newest count. Else NaN.
     * - A time between two samples: the linear interpolation. NaN when the two samples are more
     *   than 500 ms apart and their counts are not the same.
     */
    fun countAt(timeMs: Long): Double {
        if (size == 0) return Double.NaN
        val newestIndex = size - 1
        val newestTime = timeAt(newestIndex)
        val newest = countAt(newestIndex)
        if (timeMs >= newestTime) {
            if (timeMs - newestTime <= GAP_MS) return newest.toDouble()
            return if (atRest()) newest.toDouble() else Double.NaN
        }
        val before = indexAtOrBefore(timeMs)
        if (before < 0) return Double.NaN
        val beforeTime = timeAt(before)
        val beforeCount = countAt(before)
        if (beforeTime == timeMs) return beforeCount.toDouble()
        val afterTime = timeAt(before + 1)
        val afterCount = countAt(before + 1)
        if (afterTime - beforeTime > GAP_MS && afterCount != beforeCount) return Double.NaN
        val fraction = (timeMs - beforeTime).toDouble() / (afterTime - beforeTime).toDouble()
        return beforeCount + fraction * (afterCount - beforeCount)
    }

    /**
     * Gives the direction of the last motion before [timeMs]: +1, −1, or 0. The value change is
     * `(count(t) − e.count) × scale` for each sample
     * `e` at or before the time, newest first. The first change of [threshold] or more gives its
     * sign. A sample older than [windowMs], or a gap of more than 500 ms between two samples,
     * gives 0.
     */
    fun direction(timeMs: Long, scale: Double, threshold: Double, windowMs: Long): Int {
        val reference = countAt(timeMs)
        if (reference.isNaN()) return 0
        var previousTime = timeMs
        var index = indexAtOrBefore(timeMs)
        while (index >= 0) {
            val sampleTime = timeAt(index)
            if (sampleTime < timeMs - windowMs) return 0
            if (previousTime - sampleTime > GAP_MS) return 0
            val change = (reference - countAt(index)) * scale
            if (abs(change) >= threshold) return if (change > 0.0) 1 else -1
            previousTime = sampleTime
            index -= 1
        }
        return 0
    }

    private fun atRest(): Boolean {
        val newestIndex = size - 1
        val newestTime = timeAt(newestIndex)
        val newest = countAt(newestIndex)
        var index = newestIndex - 1
        while (index >= 0) {
            if (abs(countAt(index) - newest) > REST_BAND_COUNTS) return false
            if (newestTime - timeAt(index) >= REST_TIME_MS) return true
            index -= 1
        }
        return false
    }

    private fun slot(index: Int): Int = (start + index) % capacity

    private fun checkedSlot(index: Int): Int {
        if (index < 0 || index >= size) throw IndexOutOfBoundsException("The index $index is not in 0 until $size.")
        return slot(index)
    }

    companion object {
        /** The samples of each input. */
        const val CAPACITY: Int = 2048

        /** The largest time between two samples for an interpolation. */
        const val GAP_MS: Long = 500L

        /** The time at rest that keeps the newest count after the gap. */
        const val REST_TIME_MS: Long = 1000L

        /** The count band of the rest rule. */
        const val REST_BAND_COUNTS: Long = 2L
    }
}
