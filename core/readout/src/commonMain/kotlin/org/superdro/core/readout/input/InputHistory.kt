package org.superdro.core.readout.input

import kotlin.math.abs
import org.superdro.core.model.AxisId
import org.superdro.core.model.Channels

/**
 * The input history of one session: the last 2048
 * (time, corrected count) pairs of each adapter input, and the position and the speed of an
 * input or an axis at a given time.
 *
 * The probe latch, the feed rate, the AUTO direction, the measurement, the error
 * map wizard, and the data output use it. All times use the session clock.
 *
 * The [InputStage] writes the history. The class is not thread-safe: read it in the session
 * context.
 */
class InputHistory internal constructor() {

    private val channels = LinkedHashMap<Char, ChannelHistory>()
    private val scales = HashMap<Char, Double>()
    private val axisTerms = LinkedHashMap<AxisId, AxisTerms>()
    private val termsChangedAt = HashMap<AxisId, Long>()

    /** The input letters that have a history. */
    val inputs: Set<Char> get() = channels.keys

    /** Gives the history of the input [input], or null. */
    fun channel(input: Char): ChannelHistory? = channels[Channels.normalize(input)]

    /** Gives the scale factor of the input [input] (µm, arc seconds, or revolutions for each count), or null. */
    fun scale(input: Char): Double? = scales[Channels.normalize(input)]

    /** Gives the terms of [axis], or null. */
    fun terms(axis: AxisId): AxisTerms? = axisTerms[axis]

    /** Gives the corrected count of [input] at [timeMs], or NaN (see [ChannelHistory.countAt]). */
    fun countAt(input: Char, timeMs: Long): Double = channel(input)?.countAt(timeMs) ?: Double.NaN

    /** Gives the value of [input] at [timeMs] (`count × k`: µm, arc seconds, or revolutions), or NaN. */
    fun positionAt(input: Char, timeMs: Long): Double {
        val k = scale(input) ?: return Double.NaN
        return countAt(input, timeMs) * k
    }

    /**
     * Gives the speed of [input] in units for each second:
     * `(pos(now) − pos(now − span)) × k / span × 1000`. NaN when a position is not known.
     */
    fun speed(input: Char, nowMs: Long, spanMs: Long): Double {
        require(spanMs > 0L) { "The span must be more than 0 ms." }
        return (positionAt(input, nowMs) - positionAt(input, nowMs - spanMs)) / spanMs * 1000.0
    }

    /**
     * Gives the direction of the last motion of [input] before [timeMs]: +1, −1, or 0.
     * [threshold] is in the units of the input.
     */
    fun direction(input: Char, timeMs: Long, threshold: Double = DIRECTION_THRESHOLD, windowMs: Long = DIRECTION_WINDOW_MS): Int {
        val history = channel(input) ?: return 0
        val k = scale(input) ?: return 0
        return history.direction(timeMs, k, threshold, windowMs)
    }

    /**
     * Gives the sum `R` of [axis] at [timeMs]: `Σ w × s × pos(input, t)`. NaN when the axis has no
     * term, when a term has no position, or when [timeMs] is
     * before the last change of the terms of the axis.
     */
    fun axisPositionAt(axis: AxisId, timeMs: Long): Double {
        val terms = axisTerms[axis] ?: return Double.NaN
        if (terms.terms.isEmpty()) return Double.NaN
        val changedAt = termsChangedAt[axis]
        if (changedAt != null && timeMs < changedAt) return Double.NaN
        var sum = 0.0
        terms.terms.forEach { term -> sum += term.factor * positionAt(term.input, timeMs) }
        return sum
    }

    /** Gives the speed of [axis] in units for each second over [spanMs] before [nowMs], or NaN. */
    fun axisSpeed(axis: AxisId, nowMs: Long, spanMs: Long): Double {
        require(spanMs > 0L) { "The span must be more than 0 ms." }
        return (axisPositionAt(axis, nowMs) - axisPositionAt(axis, nowMs - spanMs)) / spanMs * 1000.0
    }

    /**
     * Gives the feed rate of [axis] in units for each minute:
     *
     * ```text
     * speed = Σ over the inputs of the axis (w × s × (pos(now) − pos(now − 2000 ms)) × k / 2000 ms)
     * rate  = |speed| × 60000          (µm/min); 0 when the history has no value for one of the times
     * ```
     */
    fun feedRate(axis: AxisId, nowMs: Long, windowMs: Long = FEED_WINDOW_MS): Double {
        val change = axisPositionAt(axis, nowMs) - axisPositionAt(axis, nowMs - windowMs)
        if (change.isNaN()) return 0.0
        return abs(change) / windowMs * 60000.0
    }

    /**
     * Gives the direction of the last motion of [axis] before [timeMs]: +1, −1, or 0.
     * [threshold] is in the units of the axis (µm or arc
     * seconds).
     *
     * - One term: the direction of the input with the threshold `threshold / |w × s|`, with the
     *   sign of `w × s`. The window stops at the last change of the terms.
     * - Two or more terms: the first difference `f(t) − f(t − 20 ms × n)` of [threshold] or
     *   more gives its sign. A position that is not known gives 0.
     */
    fun axisDirection(axis: AxisId, timeMs: Long, threshold: Double = DIRECTION_THRESHOLD, windowMs: Long = DIRECTION_WINDOW_MS): Int {
        val terms = axisTerms[axis]?.terms.orEmpty().filter { term -> term.factor != 0.0 }
        if (terms.isEmpty()) return 0
        val changedAt = termsChangedAt[axis]
        val window = if (changedAt != null) minOf(windowMs, timeMs - changedAt) else windowMs
        if (window <= 0L) return 0
        if (terms.size == 1) {
            val term = terms.single()
            val direction = direction(term.input, timeMs, threshold / abs(term.factor), window)
            return if (term.factor < 0.0) -direction else direction
        }
        val reference = axisPositionAt(axis, timeMs)
        if (reference.isNaN()) return 0
        var step = MULTI_TERM_STEP_MS
        while (step <= window) {
            val earlier = axisPositionAt(axis, timeMs - step)
            if (earlier.isNaN()) return 0
            val change = reference - earlier
            if (abs(change) >= threshold) return if (change > 0.0) 1 else -1
            step += MULTI_TERM_STEP_MS
        }
        return 0
    }

    internal fun channelFor(input: Char): ChannelHistory = channels.getOrPut(input) { ChannelHistory() }

    internal fun setScale(input: Char, k: Double?) {
        if (k == null) scales.remove(input) else scales[input] = k
    }

    internal fun setTerms(terms: Map<AxisId, AxisTerms>, changedAtMs: Long?) {
        terms.forEach { (axis, newTerms) ->
            val old = axisTerms[axis]
            if (old != null && old.terms != newTerms.terms && changedAtMs != null) termsChangedAt[axis] = changedAtMs
        }
        axisTerms.keys.retainAll(terms.keys)
        axisTerms.putAll(terms)
    }

    internal fun clear() {
        channels.values.forEach { history -> history.clear() }
        termsChangedAt.clear()
    }

    companion object {
        /** The time span of the feed rate. */
        const val FEED_WINDOW_MS: Long = 2000L

        /** The motion that gives a direction: 5 µm. */
        const val DIRECTION_THRESHOLD: Double = 5.0

        /** The time span of the direction. */
        const val DIRECTION_WINDOW_MS: Long = 1000L

        /** The step of the direction search of an axis with two or more terms. */
        const val MULTI_TERM_STEP_MS: Long = 20L
    }
}
