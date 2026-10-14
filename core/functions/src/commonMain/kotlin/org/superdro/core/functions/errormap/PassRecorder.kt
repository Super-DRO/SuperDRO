package org.superdro.core.functions.errormap

import kotlin.math.abs
import kotlin.math.max

/**
 * One sample pair of the error map wizard: the counts
 * of the target input and of the reference input with the same time stamp.
 *
 * @property target The raw count of the target input (the input to correct).
 * @property reference The raw count of the reference input.
 * @property timeMs The time stamp of the two values, in ms.
 */
data class SamplePair(val target: Long, val reference: Long, val timeMs: Long)

/**
 * One recorded pass of the wizard.
 *
 * @property pairs The pairs in the record order.
 * @property truncated True when the pass reached [PassRecorder.MAX_PAIRS] pairs.
 * @property glitchesDropped The number of pairs that the glitch filter dropped.
 */
data class RecordedPass(
    val pairs: List<SamplePair> = emptyList(),
    val truncated: Boolean = false,
    val glitchesDropped: Int = 0,
) {
    /** True when the pass has no pair. */
    val isEmpty: Boolean get() = pairs.isEmpty()

    /** The row data. The pass is immutable, thus the first call calculates it, and the next calls use it again. */
    private val cachedRowInfo: PassRowInfo? by lazy(LazyThreadSafetyMode.PUBLICATION) { calculateRowInfo() }

    /**
     * Gives the data of the pass row "N samples · 0 – farthest – end": the target
     * counts relative to the first pair of the pass. Null for an empty pass.
     */
    fun rowInfo(): PassRowInfo? = cachedRowInfo

    private fun calculateRowInfo(): PassRowInfo? {
        if (pairs.isEmpty()) return null
        val start = pairs.first().target
        var farthest = 0L
        for (pair in pairs) {
            val offset = pair.target - start
            if (abs(offset) > abs(farthest)) farthest = offset
        }
        return PassRowInfo(pairs.size, farthest, pairs.last().target - start)
    }
}

/**
 * The data of one pass row of the wizard: "N samples · 0 – farthest – end".
 *
 * @property sampleCount N, the number of pairs.
 * @property farthest The target offset of the pair that is farthest from the start, in counts.
 * @property end The target offset of the last pair, in counts.
 */
data class PassRowInfo(val sampleCount: Int, val farthest: Long, val end: Long)

/**
 * The recorder of one pass. The caller gives each pair of target and reference values that have the
 * same time stamp (one adapter message).
 *
 * Rules:
 *
 * - The time stamp must be newer than the time stamp of the last pair. Else the recorder ignores
 *   the pair.
 * - A pair with the same two counts as the last pair is not recorded.
 * - The maximum is [maxPairs] pairs (200000). After the limit, the pass is "truncated" and the
 *   recorder ignores each new pair.
 * - Glitch filter: the recorder keeps one pair in a buffer and tests it against its neighbors.
 *   After 8 recorded steps of an input, the buffered pair is a glitch of that input when all
 *   these conditions are true: the step in (`cur − prev`) and the step out (`next − cur`) are not
 *   0 and have opposite signs; `|next − prev| × 20 < min(|step in|, |step out|)`; and
 *   `min(|step in|, |step out|) > 20 × median` of the last 32 step sizes of that input. The
 *   recorder drops a glitch and adds 1 to [glitchesDropped].
 *
 * SuperDRO keeps only the step sizes that are not 0 in the history of the last 32 steps. Thus an
 * input that does not move does not make the median 0.
 *
 * [stop] records the buffered pair.
 */
class PassRecorder(val maxPairs: Int = MAX_PAIRS) {

    private val recorded = ArrayList<SamplePair>()
    private var buffered: SamplePair? = null
    private var lastOffered: SamplePair? = null
    private val targetSteps = ArrayDeque<Long>()
    private val referenceSteps = ArrayDeque<Long>()

    /** The number of pairs that the glitch filter dropped. */
    var glitchesDropped: Int = 0
        private set

    /** True when the pass reached [maxPairs] pairs. */
    var truncated: Boolean = false
        private set

    /** The number of pairs of the pass, with the buffered pair. */
    val size: Int get() = recorded.size + (if (buffered != null) 1 else 0)

    /**
     * Offers one pair. Gives true when the recorder accepted the pair into its buffer.
     *
     * @param target The raw target count.
     * @param reference The raw reference count.
     * @param timeMs The common time stamp of the two values.
     */
    fun offer(target: Long, reference: Long, timeMs: Long): Boolean {
        val last = lastOffered
        if (last != null && timeMs <= last.timeMs) return false
        if (last != null && last.target == target && last.reference == reference) return false
        if (truncated) return false
        if (size >= maxPairs) {
            truncated = true
            return false
        }
        val next = SamplePair(target, reference, timeMs)
        lastOffered = next
        val cur = buffered
        if (cur == null) {
            buffered = next
            return true
        }
        val prev = recorded.lastOrNull()
        if (prev != null && (isGlitch(prev.target, cur.target, next.target, targetSteps) ||
                isGlitch(prev.reference, cur.reference, next.reference, referenceSteps))
        ) {
            glitchesDropped++
        } else {
            record(cur)
        }
        buffered = next
        return true
    }

    /** Stops the pass: records the buffered pair and gives the pass. */
    fun stop(): RecordedPass {
        buffered?.let { record(it) }
        buffered = null
        return snapshot()
    }

    /** Gives the pass as it is now, with the buffered pair at the end. */
    fun snapshot(): RecordedPass =
        RecordedPass(recorded.toList() + listOfNotNull(buffered), truncated, glitchesDropped)

    private fun record(pair: SamplePair) {
        val prev = recorded.lastOrNull()
        if (prev != null) {
            addStep(targetSteps, abs(pair.target - prev.target))
            addStep(referenceSteps, abs(pair.reference - prev.reference))
        }
        recorded.add(pair)
    }

    private fun addStep(steps: ArrayDeque<Long>, size: Long) {
        if (size == 0L) return
        steps.addLast(size)
        while (steps.size > STEP_HISTORY) steps.removeFirst()
    }

    private fun isGlitch(prev: Long, cur: Long, next: Long, steps: ArrayDeque<Long>): Boolean {
        if (steps.size < MIN_RECORDED_STEPS) return false
        val stepIn = cur - prev
        val stepOut = next - cur
        if (stepIn == 0L || stepOut == 0L) return false
        if ((stepIn > 0) == (stepOut > 0)) return false
        val smaller = minOf(abs(stepIn), abs(stepOut))
        if (abs(next - prev) * 20 >= smaller) return false
        val median = Stats.median(steps.map { it.toDouble() }) ?: return false
        return smaller > 20.0 * max(median, 0.0)
    }

    companion object {
        /** The maximum number of pairs of one pass. */
        const val MAX_PAIRS: Int = 200_000

        /** The number of recorded steps before the glitch filter starts. */
        const val MIN_RECORDED_STEPS: Int = 8

        /** The number of step sizes in the history of each input. */
        const val STEP_HISTORY: Int = 32
    }
}
