package org.superdro.core.functions.errormap

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import org.superdro.core.functions.errormap.MapBuilderSteps.fitNodes
import org.superdro.core.functions.errormap.MapBuilderSteps.fitValue
import org.superdro.core.functions.errormap.MapBuilderSteps.medianByPosition
import org.superdro.core.functions.errormap.MapBuilderSteps.removeSplit
import org.superdro.core.model.MapPoint

/**
 * The map builder of the error map wizard. It changes the recorded passes into an
 * error map and its report.
 *
 * The steps:
 *
 * 1. The dead band after a reversal.
 * 2. The samples `(position, delta)` of each pass.
 * 3. The direction split and its removal.
 * 4. The median delta at each unique position of each pass.
 * 5. The linear term and its removal.
 * 6. The pin.
 * 7. `K` from the cross-validation.
 * 8. The node fit, the level, and the final map.
 * 9. The statistics, the advice, and the verification.
 *
 * The run time can be long for passes with many unique positions (the periodic error tries up
 * to 2000 periods). Run it away from the main thread.
 */
object ErrorMapBuilder {

    /** The bin width of the repeatability and of the reference drift, in counts. */
    const val SMALL_BIN: Double = 20.0

    /**
     * Builds the map from [passes] (pass 1 to pass 5) and the optional [verification] pass.
     * Passes with no pairs are ignored.
     */
    fun build(passes: List<RecordedPass>, verification: RecordedPass? = null): ErrorMapBuild {
        val usable = passes.filter { !it.isEmpty }
        if (usable.isEmpty()) return ErrorMapBuild.NotEnoughData
        val deadBand = MapBuilderSteps.deadBand(usable)
        val raw = usable.map { MapBuilderSteps.passSamples(it) }
        val split = MapBuilderSteps.measureSplit(raw)
        val reduced = raw.map { medianByPosition(removeSplit(it, split)) }
        if (reduced.sumOf { it.size } < 2) return ErrorMapBuild.NotEnoughData

        val slope = MapBuilderSteps.slope(reduced.flatten())
        val targets = usable.flatMap { pass -> pass.pairs.map { it.target.toDouble() } }
        val pin = MapBuilderSteps.pin(targets, deadBand?.margin2 ?: 0.0)
        val detrended = reduced.map { pass -> pass.map { MapSample(it.position, it.delta - (it.position - pin) * slope) } }
        val folds = MapBuilderSteps.folds(detrended)
        val k = MapBuilderSteps.selectK(folds)
        val levelNodes = fitNodes(k, detrended.flatten())
        val level = fitValue(levelNodes, pin)
        val leveled = detrended.map { pass -> pass.map { MapSample(it.position, it.delta - level) } }
        val nodes = fitNodes(k, leveled.flatten())
        if (nodes.isEmpty()) return ErrorMapBuild.NotEnoughData
        val frame = MapFrame(pin, level, slope, split?.atMidTravel)

        val heldOut = if (usable.size >= 2) {
            wholePassResiduals(raw, k, pin)
        } else {
            interleavedResiduals(folds, k)
        }
        val all = leveled.flatten()
        val binWidth = rangeBinWidth(all)
        val rawRange = binMedianRange(all, binWidth)
        val correctedRange = binMedianRange(heldOut, binWidth)
        val repeatability = repeatability(leveled)

        val statistics = ErrorMapStatistics(
            passCount = usable.size,
            sampleCount = usable.sumOf { it.pairs.size },
            nodeCount = nodes.size,
            medianNodeSpacingCounts = Stats.median((1 until nodes.size).map { (nodes[it].position - nodes[it - 1].position).toDouble() }),
            spreadBeforeCounts = Stats.p95MinusP5(all.map { it.delta }),
            spreadAfterCounts = Stats.p95MinusP5(heldOut.map { it.delta }),
            repeatabilityCounts = repeatability,
            linearTermPpm = slope * 1_000_000.0,
            heldOutIsWholePass = usable.size >= 2,
            closureCountsByPass = usable.map { MapBuilderSteps.closure(it) },
            samplesPerNode = k,
            violations = MapValidation.validateErrorMap(nodes),
            directionSplitCounts = split?.atMidTravel,
            periodicError = periodicError(leveled),
            deadBand = deadBand,
            travelExtremes = travelExtremes(all),
            pinCounts = pin,
            referenceDriftCounts = referenceDrift(leveled),
            rawLow = rawRange?.first,
            rawHigh = rawRange?.second,
            correctedLow = correctedRange?.first,
            correctedHigh = correctedRange?.second,
        )
        val truncated = usable.any { it.truncated } || verification?.truncated == true
        val advice = advice(statistics, truncated)
        val verificationResult = verify(verification, nodes, frame, split)
        return ErrorMapBuild.Built(
            ErrorMapReport(
                nodes = nodes,
                frame = frame,
                statistics = statistics,
                advice = advice,
                verification = verificationResult,
                rawSeries = leveled.filter { it.size >= 2 },
            ),
        )
    }

    /**
     * Gives the advice items, in this order: `MapMakesItWorse`, `ReferenceMoved`,
     * `TargetStalls`, `CaptureTruncated`.
     */
    fun advice(statistics: ErrorMapStatistics, captureTruncated: Boolean): List<ErrorMapAdvice> = buildList {
        if (statistics.nodeCount > 0 && statistics.improvement < 1.0) add(ErrorMapAdvice.MapMakesItWorse)
        val drift = statistics.referenceDriftCounts
        val repeatability = statistics.repeatabilityCounts
        if (drift != null && repeatability != null && repeatability > 0.0 && drift > 5.0 * repeatability) {
            add(ErrorMapAdvice.ReferenceMoved)
        }
        val band = statistics.deadBand
        if (band != null && !band.inReference && band.eventCount >= 2 && band.spread <= 0.5 * band.counts) {
            add(ErrorMapAdvice.TargetStalls)
        }
        if (captureTruncated) add(ErrorMapAdvice.CaptureTruncated)
    }

    /**
     * Verifies the map with the verification pass.
     *
     * 1. The samples of the verification pass.
     * 2. The direction split of the verification pass itself. When it has none, the split of
     *    the map ([frameSplit]).
     * 3. The median delta at each unique position.
     * 4. `residual = (delta − level) − (position − pin) × slope − map(position)`.
     * 5. The result: the smallest residual, the largest residual, and the median residual
     *    (`frameOffsetCounts`).
     */
    fun verify(
        verification: RecordedPass?,
        nodes: List<MapPoint>,
        frame: MapFrame,
        frameSplit: DirectionSplit?,
    ): VerificationResult {
        if (verification == null || verification.isEmpty) return VerificationResult.NotRecorded
        if (nodes.isEmpty()) return VerificationResult.NothingToMeasure
        val data = MapBuilderSteps.passSamples(verification)
        val split = MapBuilderSteps.measureSplit(listOf(data)) ?: frameSplit
        val samples = medianByPosition(removeSplit(data, split))
        if (samples.isEmpty()) return VerificationResult.NothingToMeasure
        val residuals = samples.map {
            MapSample(it.position, residual(it, frame.level, frame.pin, frame.slope, nodes))
        }
        val values = residuals.map { it.delta }
        return VerificationResult.Measured(values.min(), values.max(), Stats.median(values)!!, residuals)
    }

    private fun residual(sample: MapSample, level: Double, pin: Double, slope: Double, nodes: List<MapPoint>): Double =
        (sample.delta - level) - (sample.position - pin) * slope - fitValue(nodes, sample.position)

    /**
     * The held-out residuals of whole-pass folds: for each pass, the builder
     * makes the split, the slope, the level, and the nodes again from the other passes, with the
     * same pin and `K`. The residuals of the held-out pass use the formula of the verification.
     */
    private fun wholePassResiduals(raw: List<PassSamples>, k: Int, pin: Double): List<MapSample> {
        val result = ArrayList<MapSample>()
        for (p in raw.indices) {
            val training = raw.filterIndexed { index, _ -> index != p }
            val split = MapBuilderSteps.measureSplit(training)
            val reduced = training.map { medianByPosition(removeSplit(it, split)) }.flatten()
            if (reduced.size < 2 * k) continue
            val slope = MapBuilderSteps.slope(reduced)
            val detrended = reduced.map { MapSample(it.position, it.delta - (it.position - pin) * slope) }
            val level = fitValue(fitNodes(k, detrended), pin)
            val nodes = fitNodes(k, detrended.map { MapSample(it.position, it.delta - level) })
            if (nodes.isEmpty()) continue
            for (sample in medianByPosition(removeSplit(raw[p], split))) {
                result.add(MapSample(sample.position, residual(sample, level, pin, slope, nodes)))
            }
        }
        return result
    }

    /** The held-out residuals of interleaved folds with the final `K`. */
    private fun interleavedResiduals(folds: List<List<MapSample>>, k: Int): List<MapSample> {
        val result = ArrayList<MapSample>()
        for (f in folds.indices) {
            val training = folds.filterIndexed { index, _ -> index != f }.flatten()
            if (training.size < 2 * k) continue
            val nodes = fitNodes(k, training)
            folds[f].forEach { result.add(MapSample(it.position, it.delta - fitValue(nodes, it.position))) }
        }
        return result
    }

    /** The bin width of rawLow to correctedHigh: `max(20, 4 × median gap between sorted unique positions)`. */
    internal fun rangeBinWidth(samples: List<MapSample>): Double {
        val unique = samples.map { it.position }.distinct().sorted()
        val gaps = (1 until unique.size).map { unique[it] - unique[it - 1] }
        return max(SMALL_BIN, 4.0 * (Stats.median(gaps) ?: 0.0))
    }

    /** Gives the smallest and the largest bin median of the deltas, or null with no sample. */
    internal fun binMedianRange(samples: List<MapSample>, width: Double): Pair<Double, Double>? {
        if (samples.isEmpty()) return null
        val medians = samples.groupBy { Stats.bin(it.position, width) }.values.map { group -> Stats.median(group.map { it.delta })!! }
        return medians.min() to medians.max()
    }

    /**
     * The repeatability: bins of 20 counts. In each bin with samples from 2 passes
     * or more, each sample gives `delta'' − median(bin)`. The value is P95 − P5 of these values,
     * or null when no bin has 2 passes.
     */
    internal fun repeatability(passes: List<List<MapSample>>): Double? {
        val bins = HashMap<Long, MutableList<Pair<Int, Double>>>()
        passes.forEachIndexed { index, pass ->
            pass.forEach { bins.getOrPut(Stats.bin(it.position, SMALL_BIN)) { ArrayList() }.add(index to it.delta) }
        }
        val values = ArrayList<Double>()
        for (entries in bins.values) {
            if (entries.map { it.first }.distinct().size < 2) continue
            val median = Stats.median(entries.map { it.second })!!
            entries.forEach { values.add(it.second - median) }
        }
        return Stats.p95MinusP5(values)
    }

    /**
     * The reference drift, for 2 passes or more: bins of 20 counts and the median
     * delta of each bin of each pass. With the bins that all passes have (3 or more), each pass
     * gives the median of its bin values. The drift is `max − min` of these medians. Else null.
     */
    internal fun referenceDrift(passes: List<List<MapSample>>): Double? {
        if (passes.size < 2) return null
        val perPass = passes.map { pass ->
            pass.groupBy { Stats.bin(it.position, SMALL_BIN) }.mapValues { (_, group) -> Stats.median(group.map { it.delta })!! }
        }
        val common = perPass.map { it.keys }.reduce { acc, keys -> acc intersect keys }
        if (common.size < 3) return null
        val medians = perPass.map { bins -> Stats.median(common.map { bins.getValue(it) })!! }
        return medians.max() - medians.min()
    }

    /**
     * The travel extremes: 36 samples or more. The outer samples are within 5 % of
     * the span from each end. The middle samples are the rest. Each group needs 12 samples. Each
     * spread is P95 − P5 of `delta''`.
     */
    internal fun travelExtremes(samples: List<MapSample>): TravelExtremes? {
        if (samples.size < 36) return null
        val min = samples.minOf { it.position }
        val max = samples.maxOf { it.position }
        val edge = (max - min) * 0.05
        val (outer, middle) = samples.partition { it.position <= min + edge || it.position >= max - edge }
        if (outer.size < 12 || middle.size < 12) return null
        val outerSpread = Stats.p95MinusP5(outer.map { it.delta })!!
        val middleSpread = Stats.p95MinusP5(middle.map { it.delta })!!
        return TravelExtremes(outerSpread, middleSpread, if (middleSpread > 0.0) outerSpread / middleSpread else null)
    }

    /**
     * The periodic error, from `delta''` of each pass.
     *
     * - It needs at least 2 passes with at least 32 samples.
     * - The periods go from `max(4, 2 × median gap)` to `span / 3`. The frequency step is
     *   `1 / (5 × span)`, with 1 to 2000 steps.
     * - For each pass, the best period has the largest fraction of the variance of a
     *   least-squares fit `a·sin + b·cos + c`.
     * - All best periods must be within 10 % of their median. Each pass must have a variance
     *   fraction of 0.1 or more. The mean amplitude must be 1 count or more. The phase coherence
     *   (the length of the mean unit phase vector) must be 0.8 or more.
     */
    internal fun periodicError(passes: List<List<MapSample>>): PeriodicError? {
        val eligible = passes.filter { it.size >= 32 }
        if (eligible.size < 2) return null
        val all = eligible.flatten()
        val unique = all.map { it.position }.distinct().sorted()
        val gaps = (1 until unique.size).map { unique[it] - unique[it - 1] }
        val span = unique.last() - unique.first()
        if (span <= 0.0) return null
        val minPeriod = max(4.0, 2.0 * (Stats.median(gaps) ?: 0.0))
        val maxPeriod = span / 3.0
        if (maxPeriod < minPeriod) return null
        val fMin = 1.0 / maxPeriod
        val fMax = 1.0 / minPeriod
        val nominalStep = 1.0 / (5.0 * span)
        val steps = (floor((fMax - fMin) / nominalStep).toLong() + 1).coerceIn(1, 2000).toInt()
        val step = if (steps > 1) (fMax - fMin) / (steps - 1) else 0.0
        data class Best(val period: Double, val fraction: Double, val amplitude: Double, val phase: Double)
        val bests = eligible.map { pass ->
            var best: Best? = null
            for (s in 0 until steps) {
                val f = fMin + s * step
                val fit = sineFit(pass, f) ?: continue
                if (best == null || fit.first > best.fraction) {
                    best = Best(1.0 / f, fit.first, fit.second, fit.third)
                }
            }
            best ?: return null
        }
        val medianPeriod = Stats.median(bests.map { it.period })!!
        if (bests.any { abs(it.period - medianPeriod) > 0.1 * medianPeriod }) return null
        if (bests.any { it.fraction < 0.1 }) return null
        val meanAmplitude = bests.sumOf { it.amplitude } / bests.size
        if (meanAmplitude < 1.0) return null
        val coherence = hypot(bests.sumOf { cos(it.phase) } / bests.size, bests.sumOf { sin(it.phase) } / bests.size)
        if (coherence < 0.8) return null
        return PeriodicError(medianPeriod, meanAmplitude, bests.sumOf { it.fraction } / bests.size, bests.size)
    }

    /**
     * Fits `y = a·sin(2πfx) + b·cos(2πfx) + c` with least squares. Gives (variance fraction,
     * amplitude `hypot(a, b)`, phase `atan2(b, a)`), or null for a singular system.
     */
    private fun sineFit(samples: List<MapSample>, frequency: Double): Triple<Double, Double, Double>? {
        val w = 2.0 * PI * frequency
        var sss = 0.0; var ssc = 0.0; var scc = 0.0; var ss = 0.0; var sc = 0.0
        var sy = 0.0; var sys = 0.0; var syc = 0.0
        val n = samples.size.toDouble()
        for (sample in samples) {
            val s = sin(w * sample.position)
            val c = cos(w * sample.position)
            sss += s * s; ssc += s * c; scc += c * c; ss += s; sc += c
            sy += sample.delta; sys += sample.delta * s; syc += sample.delta * c
        }
        val m = arrayOf(
            doubleArrayOf(sss, ssc, ss, sys),
            doubleArrayOf(ssc, scc, sc, syc),
            doubleArrayOf(ss, sc, n, sy),
        )
        val solution = solve3(m) ?: return null
        val (a, b, c) = Triple(solution[0], solution[1], solution[2])
        val mean = sy / n
        var total = 0.0
        var residual = 0.0
        for (sample in samples) {
            val fit = a * sin(w * sample.position) + b * cos(w * sample.position) + c
            total += (sample.delta - mean) * (sample.delta - mean)
            residual += (sample.delta - fit) * (sample.delta - fit)
        }
        val fraction = if (total <= 0.0) 0.0 else 1.0 - residual / total
        return Triple(fraction, hypot(a, b), atan2(b, a))
    }

    /** Solves a 3 × 3 system (augmented matrix) with partial pivots. Null for a singular system. */
    private fun solve3(m: Array<DoubleArray>): DoubleArray? {
        for (col in 0 until 3) {
            var pivot = col
            for (row in col + 1 until 3) if (abs(m[row][col]) > abs(m[pivot][col])) pivot = row
            if (abs(m[pivot][col]) < 1e-12) return null
            val tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp
            for (row in 0 until 3) {
                if (row == col) continue
                val factor = m[row][col] / m[col][col]
                for (j in col until 4) m[row][j] -= factor * m[col][j]
            }
        }
        return DoubleArray(3) { m[it][3] / m[it][it] }
    }
}
