package org.superdro.core.functions.errormap

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import org.superdro.core.model.MapPoint

/**
 * One sample of the map builder.
 *
 * @property position The target count.
 * @property delta The error `reference × 1.0 − target`, in counts, after the steps of the builder.
 */
data class MapSample(val position: Double, val delta: Double)

/**
 * The dead band after a direction reversal.
 *
 * @property counts The median of the absolute values, in counts (4 or more).
 * @property inReference True when the reference stalls: `(number of values > 0) × 2 >= number of
 *   values`. False when the target stalls.
 * @property eventCount The number of values with the majority sign.
 * @property spread `max − min` of the absolute values of these values (0 for fewer than 2).
 */
data class DeadBandResult(
    val counts: Double,
    val inReference: Boolean,
    val eventCount: Int,
    val spread: Double,
) {
    /** The margin of the pin rule: `2 × counts`. */
    val margin2: Double get() = 2.0 * counts
}

/** One knot of the direction split: the median position and the median split of a group. */
data class SplitKnot(val position: Double, val split: Double)

/**
 * The split between the two directions of travel.
 *
 * @property atMidTravel The median of all differences `median(up) − median(down)`, in counts.
 * @property knots The knots, sorted by position. With no knots, the split is [atMidTravel].
 */
data class DirectionSplit(val atMidTravel: Double, val knots: List<SplitKnot>) {

    /** Gives the split at [position]: linear between the knots, flat outside, [atMidTravel] with no knots. */
    fun at(position: Double): Double {
        if (knots.isEmpty()) return atMidTravel
        if (position <= knots.first().position) return knots.first().split
        if (position >= knots.last().position) return knots.last().split
        for (i in 1 until knots.size) {
            val b = knots[i]
            if (position <= b.position) {
                val a = knots[i - 1]
                val width = b.position - a.position
                if (width <= 0.0) return b.split
                return a.split + (b.split - a.split) * (position - a.position) / width
            }
        }
        return knots.last().split
    }
}

/**
 * The samples of one pass with the direction of each sample.
 *
 * @property upLeg For each sample: true for the up leg, false for the down leg. Null when the
 *   pass has no legs (fewer than 4 samples, or no motion).
 */
internal class PassSamples(val samples: List<MapSample>, val upLeg: BooleanArray?)

/** The steps of the map builder. */
object MapBuilderSteps {

    /** The minimum target span and reference span of a pass for the dead band, in counts. */
    const val DEAD_BAND_MIN_SPAN: Long = 2000

    /** The target hysteresis of the reversal detection, in counts. */
    const val DEAD_BAND_STEP: Long = 20

    /** The target motion after a reversal that ends one measurement, in counts. */
    const val DEAD_BAND_MOTION: Double = 500.0

    /** The smallest target motion of a measurement that counts, in counts. */
    const val DEAD_BAND_MIN_MOTION: Double = 200.0

    /** The smallest dead band, in counts. */
    const val DEAD_BAND_MIN_COUNTS: Double = 4.0

    /** The candidates of `K` (samples for each node). */
    val K_CANDIDATES: List<Int> = listOf(2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64, 96, 128)

    /** Gives the samples of a pass: `position = target`, `delta = reference × 1.0 − target`. */
    fun samples(pass: RecordedPass): List<MapSample> =
        pass.pairs.map { MapSample(it.target.toDouble(), it.reference.toDouble() - it.target.toDouble()) }

    /**
     * Gives the dead band after a reversal of all [passes], or null when there is
     * no dead band.
     *
     * For each pass with at least 8 pairs:
     *
     * 1. `ratio = targetSpan / referenceSpan`. Both spans must be 2000 counts or more.
     * 2. The pairs are examined in sequence. SuperDRO reads the rule "ignore a target step with
     *    |step| < 20" as a hysteresis of 20 counts on the target direction: a reversal occurs
     *    when the target goes back 20 counts or more from its last extreme.
     * 3. From the extreme of the reversal, the motion counts add `|target step|` and
     *    `|reference step| × ratio` over the next pairs until the target motion is 500 counts or
     *    more.
     * 4. A target motion of less than 200 counts is ignored. Else the value is
     *    `targetMotion − referenceMotion × ratio`.
     *
     * Then `counts = median(|value|)`. Less than 4 counts gives no dead band. A positive value
     * means that the reference stalls.
     */
    fun deadBand(passes: List<RecordedPass>): DeadBandResult? {
        val values = ArrayList<Double>()
        for (pass in passes) {
            val pairs = pass.pairs
            if (pairs.size < 8) continue
            val targetSpan = pairs.maxOf { it.target } - pairs.minOf { it.target }
            val referenceSpan = pairs.maxOf { it.reference } - pairs.minOf { it.reference }
            if (targetSpan < DEAD_BAND_MIN_SPAN || referenceSpan < DEAD_BAND_MIN_SPAN) continue
            val ratio = targetSpan.toDouble() / referenceSpan.toDouble()
            var direction = 0
            var extreme = 0
            for (i in 1 until pairs.size) {
                val t = pairs[i].target
                val e = pairs[extreme].target
                when (direction) {
                    0 -> if (abs(t - pairs[0].target) >= DEAD_BAND_STEP) {
                        direction = if (t > pairs[0].target) 1 else -1
                        extreme = i
                    } else if (abs(t - pairs[0].target) > abs(e - pairs[0].target)) {
                        extreme = i
                    }
                    1 -> if (t > e) {
                        extreme = i
                    } else if (e - t >= DEAD_BAND_STEP) {
                        measureAfter(pairs, extreme, ratio)?.let { values.add(it) }
                        direction = -1
                        extreme = i
                    }
                    else -> if (t < e) {
                        extreme = i
                    } else if (t - e >= DEAD_BAND_STEP) {
                        measureAfter(pairs, extreme, ratio)?.let { values.add(it) }
                        direction = 1
                        extreme = i
                    }
                }
            }
        }
        if (values.isEmpty()) return null
        val counts = Stats.median(values.map { abs(it) }) ?: return null
        if (counts < DEAD_BAND_MIN_COUNTS) return null
        val positive = values.count { it > 0 }
        val inReference = positive * 2 >= values.size
        val majority = values.filter { if (inReference) it > 0 else it <= 0 }.map { abs(it) }
        val spread = if (majority.size < 2) 0.0 else majority.max() - majority.min()
        return DeadBandResult(counts, inReference, majority.size, spread)
    }

    private fun measureAfter(pairs: List<SamplePair>, start: Int, ratio: Double): Double? {
        var targetMotion = 0.0
        var referenceMotion = 0.0
        var j = start + 1
        while (j < pairs.size && targetMotion < DEAD_BAND_MOTION) {
            targetMotion += abs(pairs[j].target - pairs[j - 1].target).toDouble()
            referenceMotion += abs(pairs[j].reference - pairs[j - 1].reference).toDouble() * ratio
            j++
        }
        if (targetMotion < DEAD_BAND_MIN_MOTION) return null
        return targetMotion - referenceMotion
    }

    /**
     * Gives the closure of a pass: the median target count of the last run of
     * equal target counts, minus the first target count. The value is kept only when
     * `maxExcursion − |closure| >= 0.5 × maxExcursion`. Else null.
     */
    fun closure(pass: RecordedPass): Double? {
        val pairs = pass.pairs
        if (pairs.isEmpty()) return null
        val first = pairs.first().target
        val last = pairs.last().target
        val run = pairs.takeLastWhile { it.target == last }.map { it.target.toDouble() }
        val closure = (Stats.median(run) ?: last.toDouble()) - first
        val maxExcursion = pairs.maxOf { abs(it.target - first) }.toDouble()
        return if (maxExcursion - abs(closure) >= 0.5 * maxExcursion) closure else null
    }

    /**
     * Gives the two legs of a pass. The pass needs at least 4 samples. The turn
     * point is the sample farthest from the first position. Leg 1 is the samples up to and with
     * the turn point. Leg 2 is the rest. The leg that moves to larger positions is the up leg.
     */
    internal fun legs(samples: List<MapSample>): BooleanArray? {
        if (samples.size < 4) return null
        val first = samples.first().position
        var turn = 0
        for (i in samples.indices) {
            if (abs(samples[i].position - first) > abs(samples[turn].position - first)) turn = i
        }
        if (samples[turn].position == first) return null
        val leg1Up = samples[turn].position > first
        return BooleanArray(samples.size) { if (it <= turn) leg1Up else !leg1Up }
    }

    internal fun passSamples(pass: RecordedPass): PassSamples {
        val samples = samples(pass)
        return PassSamples(samples, legs(samples))
    }

    /**
     * Measures the direction split of [passes], or null when there is no split.
     *
     * 1. `binWidth = clamp(5 × median(|position step|), 20, max(20, span / 20))`, from all
     *    position steps that are not 0, of all passes.
     * 2. Each leg goes into bins `floor(position / binWidth)`, with the median delta of each bin.
     * 3. When the two legs of a pass have 3 bins or more each, each common bin gives
     *    `(bin × binWidth, median(up) − median(down))`.
     * 4. Fewer than 3 values give no split.
     * 5. `atMidTravel` is the median of all differences.
     * 6. Knots: the values sorted by position, `groups = clamp(N / 25, 2, 12)`,
     *    `size = N / groups`. A size less than 2 gives no knots. Each group of `size` values
     *    gives a knot (median position, median difference). A short last group (fewer than
     *    `size / 2` values) gives no knot.
     */
    internal fun measureSplit(passes: List<PassSamples>): DirectionSplit? {
        val steps = ArrayList<Double>()
        var minPosition = Double.POSITIVE_INFINITY
        var maxPosition = Double.NEGATIVE_INFINITY
        for (pass in passes) {
            val s = pass.samples
            for (i in s.indices) {
                minPosition = minOf(minPosition, s[i].position)
                maxPosition = maxOf(maxPosition, s[i].position)
                if (i > 0) {
                    val step = abs(s[i].position - s[i - 1].position)
                    if (step != 0.0) steps.add(step)
                }
            }
        }
        if (minPosition > maxPosition) return null
        val span = maxPosition - minPosition
        val medianStep = Stats.median(steps) ?: 0.0
        val binWidth = (5.0 * medianStep).coerceIn(20.0, max(20.0, span / 20.0))
        val values = ArrayList<SplitKnot>()
        for (pass in passes) {
            val up = pass.upLeg ?: continue
            val upBins = HashMap<Long, MutableList<Double>>()
            val downBins = HashMap<Long, MutableList<Double>>()
            for (i in pass.samples.indices) {
                val sample = pass.samples[i]
                val target = if (up[i]) upBins else downBins
                target.getOrPut(Stats.bin(sample.position, binWidth)) { ArrayList() }.add(sample.delta)
            }
            if (upBins.size < 3 || downBins.size < 3) continue
            for (bin in upBins.keys.filter { it in downBins }.sorted()) {
                val difference = Stats.median(upBins.getValue(bin))!! - Stats.median(downBins.getValue(bin))!!
                values.add(SplitKnot(bin * binWidth, difference))
            }
        }
        if (values.size < 3) return null
        val atMidTravel = Stats.median(values.map { it.split })!!
        val sorted = values.sortedBy { it.position }
        val n = sorted.size
        val groups = (n / 25).coerceIn(2, 12)
        val size = n / groups
        val knots = ArrayList<SplitKnot>()
        if (size >= 2) {
            var i = 0
            while (i < n) {
                val group = sorted.subList(i, minOf(i + size, n))
                if (group.size * 2 < size) break
                knots.add(SplitKnot(Stats.median(group.map { it.position })!!, Stats.median(group.map { it.split })!!))
                i += size
            }
        }
        return DirectionSplit(atMidTravel, knots)
    }

    /**
     * Removes the split from a pass: an up-leg
     * sample gets `delta − s / 2`, and a down-leg sample gets `delta + s / 2`, with `s` the split
     * at the sample position. A pass with no legs, or no split, does not change.
     */
    internal fun removeSplit(pass: PassSamples, split: DirectionSplit?): List<MapSample> {
        val up = pass.upLeg
        if (split == null || up == null) return pass.samples
        return pass.samples.mapIndexed { i, sample ->
            val half = split.at(sample.position) / 2.0
            MapSample(sample.position, if (up[i]) sample.delta - half else sample.delta + half)
        }
    }

    /** Keeps one sample for each unique position, with the median delta, sorted by position. */
    fun medianByPosition(samples: List<MapSample>): List<MapSample> =
        samples.groupBy { it.position }
            .map { (position, group) -> MapSample(position, Stats.median(group.map { it.delta })!!) }
            .sortedBy { it.position }

    /** Gives the least-squares slope of delta against position. */
    fun slope(samples: List<MapSample>): Double =
        Stats.slope(samples.map { it.position }, samples.map { it.delta })

    /**
     * Gives the pin, the zero point of the map, from all raw target positions.
     *
     * ```text
     * margin = max(margin2, (max − min) × 0.05)
     * lo = min + margin;  hi = max − margin
     * if hi <= lo:              pin = median(all target positions)
     * else if lo > 0 or hi < 0: mid  = (lo + hi) / 2
     *                           step = first of {100000, 10000, 1000, 100, 10} with (hi − lo) / step >= 2, else 1
     *                           pin  = clamp(round(mid / step) × step, lo, hi)
     * else:                     pin = 0
     * ```
     *
     * @param margin2 `2 × counts` of the dead band, or 0 with no dead band.
     */
    fun pin(targets: List<Double>, margin2: Double = 0.0): Double {
        if (targets.isEmpty()) return 0.0
        val min = targets.min()
        val max = targets.max()
        val margin = max(margin2, (max - min) * 0.05)
        val lo = min + margin
        val hi = max - margin
        if (hi <= lo) return Stats.median(targets)!!
        if (lo > 0.0 || hi < 0.0) {
            val mid = (lo + hi) / 2.0
            val step = listOf(100_000.0, 10_000.0, 1_000.0, 100.0, 10.0).firstOrNull { (hi - lo) / it >= 2.0 } ?: 1.0
            return (Stats.roundHalfUp(mid / step) * step).coerceIn(lo, hi)
        }
        return 0.0
    }

    /**
     * Fits the nodes with [k] samples for each node.
     *
     * 1. Sort the samples by position.
     * 2. Cut the list into groups of `K` samples. Stop at the last group when it has fewer than
     *    `max(2, K / 2)` samples and a node exists.
     * 3. Each group gives a candidate node (median position, median delta). Keep a candidate
     *    only when its position is at least 5 counts above the last node.
     * 4. Make the corrected positions strictly increase: `y[i] = (position[i] + delta[i]) − i`,
     *    isotonic regression (pool adjacent violators, equal weights) so that `y` does not
     *    decrease, then `corrected[i] = iso[i] + i`.
     * 5. Node `i` = `(round(position[i]), round(corrected[i] − position[i]))`, with a half value
     *    rounded up.
     * 6. A rounded position that is not above the last node position drops the node. When
     *    `position + correction <= lastPosition + lastCorrection`, the correction becomes
     *    `lastPosition + lastCorrection + 1 − position`.
     *
     * Thus the map always passes [MapValidation.validateErrorMap].
     */
    fun fitNodes(k: Int, samples: List<MapSample>): List<MapPoint> {
        if (samples.isEmpty() || k < 1) return emptyList()
        val sorted = samples.sortedBy { it.position }
        val positions = ArrayList<Double>()
        val deltas = ArrayList<Double>()
        var i = 0
        while (i < sorted.size) {
            val end = minOf(i + k, sorted.size)
            val group = sorted.subList(i, end)
            if (group.size < max(2, k / 2) && positions.isNotEmpty()) break
            val position = Stats.median(group.map { it.position })!!
            val delta = Stats.median(group.map { it.delta })!!
            if (positions.isEmpty() || position >= positions.last() + 5.0) {
                positions.add(position)
                deltas.add(delta)
            }
            i = end
        }
        val y = DoubleArray(positions.size) { positions[it] + deltas[it] - it }
        val iso = isotonic(y)
        val nodes = ArrayList<MapPoint>()
        for (n in positions.indices) {
            val corrected = iso[n] + n
            val position = Stats.roundHalfUp(positions[n])
            var correction = Stats.roundHalfUp(corrected - positions[n])
            val last = nodes.lastOrNull()
            if (last != null) {
                if (position <= last.position) continue
                if (position + correction <= last.position + last.value) {
                    correction = last.position + last.value + 1 - position
                }
            }
            nodes.add(MapPoint(position, correction))
        }
        return nodes
    }

    /**
     * Gives the isotonic regression of [values] with equal weights (pool adjacent violators):
     * the closest sequence in the least-squares sense that does not decrease.
     */
    fun isotonic(values: DoubleArray): DoubleArray {
        val sums = ArrayList<Double>()
        val counts = ArrayList<Int>()
        for (value in values) {
            sums.add(value)
            counts.add(1)
            while (sums.size >= 2 && sums[sums.size - 2] / counts[counts.size - 2] > sums.last() / counts.last()) {
                val s = sums.removeAt(sums.size - 1)
                val c = counts.removeAt(counts.size - 1)
                sums[sums.size - 1] += s
                counts[counts.size - 1] += c
            }
        }
        val result = DoubleArray(values.size)
        var index = 0
        for (b in sums.indices) {
            val mean = sums[b] / counts[b]
            repeat(counts[b]) { result[index++] = mean }
        }
        return result
    }

    /** Gives the fit value of [nodes] at [position]: linear between the nodes, flat outside, 0 with no node. */
    fun fitValue(nodes: List<MapPoint>, position: Double): Double {
        if (nodes.isEmpty()) return 0.0
        if (position <= nodes.first().position) return nodes.first().value.toDouble()
        if (position >= nodes.last().position) return nodes.last().value.toDouble()
        var lo = 0
        var hi = nodes.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (nodes[mid].position <= position) lo = mid else hi = mid
        }
        val a = nodes[lo]
        val b = nodes[hi]
        val frac = (position - a.position) / (b.position - a.position).toDouble()
        return a.value + (b.value - a.value) * frac
    }

    /**
     * Selects `K` with cross-validation.
     *
     * 1. [folds]: with 2 or more passes, each pass is one fold. With 1 pass, the samples go into
     *    4 folds by `index mod 4`.
     * 2. For each `K` and each fold: fit on the other folds when they have `2 × K` samples or
     *    more. Residual = `delta' − fit(position)`.
     * 3. With 20 residuals or more in 2 folds or more: `score` = population standard deviation of
     *    all residuals, `SE = sqrt(sampleVariance(foldSD) / folds)`.
     * 4. Best = the `K` with the smallest score. Select the largest `K` with
     *    `score <= best.score + best.SE`.
     * 5. When no `K` has a score: `K = 2` for fewer than 64 samples, else `K = 16`.
     *
     * The function calls [checkCancelled] before each `K`.
     */
    fun selectK(folds: List<List<MapSample>>, checkCancelled: () -> Unit = {}): Int {
        data class Score(val k: Int, val score: Double, val se: Double)
        val scores = ArrayList<Score>()
        for (k in K_CANDIDATES) {
            checkCancelled()
            val residualsByFold = heldOutResiduals(k, folds).filter { it.isNotEmpty() }
            val all = residualsByFold.flatten()
            if (all.size < 20 || residualsByFold.size < 2) continue
            val score = Stats.populationSd(all) ?: continue
            val foldSd = residualsByFold.map { Stats.populationSd(it)!! }
            val variance = Stats.sampleVariance(foldSd) ?: 0.0
            scores.add(Score(k, score, sqrt(variance / residualsByFold.size)))
        }
        val total = folds.sumOf { it.size }
        if (scores.isEmpty()) return if (total < 64) 2 else 16
        val best = scores.minBy { it.score }
        return scores.filter { it.score <= best.score + best.se }.maxOf { it.k }
    }

    /**
     * Gives the residuals `delta − fit(position)` of each fold, with the fit of the other folds.
     * A fold whose other folds have fewer than `2 × K` samples gives no residuals.
     */
    fun heldOutResiduals(k: Int, folds: List<List<MapSample>>): List<List<Double>> =
        folds.indices.map { f ->
            val training = folds.filterIndexed { index, _ -> index != f }.flatten()
            if (training.size < 2 * k) {
                emptyList()
            } else {
                val nodes = fitNodes(k, training)
                folds[f].map { it.delta - fitValue(nodes, it.position) }
            }
        }

    /**
     * Gives the cross-validation folds: each pass is one fold for 2 or more
     * passes ("Whole pass"). With 1 pass, the samples go into 4 folds by `index mod 4`
     * ("Interleaved").
     */
    fun folds(passes: List<List<MapSample>>): List<List<MapSample>> {
        if (passes.size >= 2) return passes
        val samples = passes.firstOrNull() ?: return emptyList()
        return (0 until 4).map { f -> samples.filterIndexed { index, _ -> index % 4 == f } }
    }
}
