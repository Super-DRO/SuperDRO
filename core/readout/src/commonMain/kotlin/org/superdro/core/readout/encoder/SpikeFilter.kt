package org.superdro.core.readout.encoder

import kotlin.math.abs
import kotlin.math.max

/**
 * The input filter of an encoder profile.
 *
 * ```text
 * first sample: kept = x; shown = x
 * if held:                 held = false; kept = x
 * else if |x − kept| > J:  held = true; x = kept
 * else:                    kept = x
 * if |x − shown| > 1:      shown = x
 * output = shown
 * ```
 *
 * The filter holds one jump larger than [threshold] for one sample. The next sample passes. The
 * output changes only when the count moves more than 1 count (a dead band of 1 count).
 *
 * @property threshold The jump limit `J` in counts ([thresholdFor]).
 */
class SpikeFilter(val threshold: Long) {

    private var started = false
    private var kept = 0L
    private var shown = 0L

    /** True while the filter holds a spike. The next sample then goes through all stages. */
    var pending: Boolean = false
        private set

    /** Gives the filter output of the raw count [count]. */
    fun filter(count: Long): Long {
        if (!started) {
            started = true
            kept = count
            shown = count
            return count
        }
        var x = count
        if (pending) {
            pending = false
            kept = x
        } else if (abs(x - kept) > threshold) {
            pending = true
            x = kept
        } else {
            kept = x
        }
        if (abs(x - shown) > 1L) shown = x
        return shown
    }

    companion object {
        /** `J = max(1, (int)(resolution × 0.25))` counts. */
        fun thresholdFor(resolution: Double): Long = max(1L, (resolution * 0.25).toLong())
    }
}
