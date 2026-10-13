package org.superdro.core.protocol

/**
 * The time base of the readouts.
 *
 * All times are milliseconds of the monotonic session clock. `tStart` of a message is the arrival
 * time of the chunk with its first character.
 *
 * - On a valid `@k`, the offset becomes `tStart − k` if the offset is not set, if
 *   `tStart − (k + offset)` is more than 250 ms (the drift rule), or if `k` is less than the
 *   last `k`. A negative drift (the adapter clock is fast) does not change the offset.
 * - The adapter time is `tAdapter = k + offset`.
 * - A readout gets `tAdapter` of the last `@` if an `@` came in this link, else its own `tStart`.
 * - The session calls [reset] at the start of each link and at each change from `NoData` to
 *   `Connected`.
 */
class TimeBase {

    /** The offset between the session clock and the adapter clock, or null when it is not set. */
    var offsetMs: Long? = null
        private set

    /** The value `k` of the last `@`, or null. */
    var lastAdapterTime: Long? = null
        private set

    /** `tAdapter` of the last `@`, or null when no `@` came since the last [reset]. */
    var adapterTimeMs: Long? = null
        private set

    /**
     * Applies a valid time stamp `@k` that started at [startMs] and gives `tAdapter`.
     *
     * Example: `@3E8;` at `t1` gives the offset `t1 − 1000` and `tAdapter = t1`. Then `@7D0;`
     * gives `t1 + 1000`. `@BB8;` at `t1 + 2400` has a drift of 400 ms, so the offset becomes
     * `t1 − 600`.
     */
    fun onTimestamp(adapterTime: Long, startMs: Long): Long {
        val offset = offsetMs
        val last = lastAdapterTime
        val newOffset = when {
            offset == null -> startMs - adapterTime
            startMs - (adapterTime + offset) > DRIFT_LIMIT_MS -> startMs - adapterTime
            last != null && adapterTime < last -> startMs - adapterTime
            else -> offset
        }
        offsetMs = newOffset
        lastAdapterTime = adapterTime
        val adapterNow = adapterTime + newOffset
        adapterTimeMs = adapterNow
        return adapterNow
    }

    /** Gives the time of a readout that started at [startMs]: `tAdapter` after an `@`, else [startMs]. */
    fun readoutTime(startMs: Long): Long = adapterTimeMs ?: startMs

    /** Clears the offset and the adapter time. The next `@` sets a new offset. */
    fun reset() {
        offsetMs = null
        lastAdapterTime = null
        adapterTimeMs = null
    }

    companion object {
        /** A drift of more than this value sets a new offset. */
        const val DRIFT_LIMIT_MS: Long = 250L

        /** The largest valid `@` value: 0xFFFFFFFF × 1000. */
        const val MAX_ADAPTER_TIME: Long = 0x3E7FFFFFC18L

        /**
         * Gives the wall clock time of a record or a log line for the session time [timeMs]:
         * `wallNow − monotonicNow + t`.
         */
        fun wallClockMs(wallNowMs: Long, monotonicNowMs: Long, timeMs: Long): Long = wallNowMs - monotonicNowMs + timeMs
    }
}
