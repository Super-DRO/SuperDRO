package org.superdro.core.adapterprobe

import org.superdro.core.adapterprobe.PyText.fixed
import org.superdro.core.adapterprobe.PyText.left
import org.superdro.core.adapterprobe.PyText.ms
import org.superdro.core.adapterprobe.PyText.right

/** The limits of the statistics in memory (the same as the tool). */
object StatLimits {
    const val GAP_CAP: Int = 200_000
    const val DISTINCT_CAP: Int = 10_000
    const val EVENT_CAP: Int = 400
    const val MSG_CAP: Int = 500_000

    /** The session changes `Connected` to `NoData` after this time with no valid message. */
    const val NO_DATA_LIMIT_S: Double = 7.0
}

/** The order of the keys in the tables of the tool. */
object KeyOrder {
    private const val ORDER = "XYZWUVABCDEFRST.PQ@\$!?"

    /** The sort comparator of the keys: the place in the order, then the text. */
    val comparator: Comparator<String> = compareBy<String>({ key -> index(key) }, { key -> key })

    private fun index(key: String): Int {
        val position = ORDER.indexOf(key)
        return if (position >= 0 && key.isNotEmpty()) position else 99
    }

    /** Gives [keys] in the order of the tool. */
    fun sorted(keys: Collection<String>): List<String> = keys.sortedWith(comparator)
}

/** The statistics of one key in one phase (`KeyStats`). */
class KeyStats {
    var total: Long = 0L
        private set
    var count: Int = 0
        private set
    var first: Double? = null
        private set
    var last: Double? = null
        private set
    val gaps: MutableList<Double> = ArrayList()
    var value: Any? = null
        private set
    var firstValue: Any? = null
        private set
    val distinct: MutableSet<Any> = HashSet()
    var repeats: Int = 0
        private set

    /** Adds one message at [t] with [value]. */
    fun add(t: Double, value: Any) {
        if (count > 0) {
            if (gaps.size < StatLimits.GAP_CAP) gaps += t - (last ?: t)
            if (value == this.value) repeats++
        } else {
            first = t
            firstValue = value
        }
        count++
        last = t
        this.value = value
        if (value is Long) total += value
        if (distinct.size <= StatLimits.DISTINCT_CAP) distinct += value
    }

    /** The median time between two messages, or null. */
    fun medianGap(): Double? = Analysis.median(gaps)
}

/**
 * The counts and the times of the messages of one phase (`Stats` of the tool). The report lines
 * are the lines of the tool.
 *
 * @property name The name of the phase.
 * @property t0 The start of the phase in seconds after the start of the run.
 * @param keep True when the phase keeps its messages (for the edge analysis of T09).
 */
class PhaseStats(val name: String, val t0: Double, keep: Boolean = false) {
    var t1: Double? = null
        private set
    var tLast: Double = t0
        private set
    val keys: MutableMap<String, KeyStats> = LinkedHashMap()
    var valid: Int = 0
        private set
    var lastValid: Double? = null
        private set
    var maxGap: Double = 0.0
        private set
    val longGaps: MutableList<Pair<Double, Double>> = ArrayList()
    var unknown: Int = 0
        private set
    var rejectedChars: Int = 0
        private set
    val rejSamples: MutableList<String> = ArrayList()
    var dropped: Int = 0
        private set
    var rxBytes: Long = 0L
        private set
    var rxBlocks: Int = 0
        private set
    var txBytes: Long = 0L
        private set
    var txBel: Int = 0
        private set
    var txCmd: Int = 0
        private set

    /** The `@` messages as (arrival time, value). */
    val at: MutableList<Pair<Double, Long>> = ArrayList()
    var groupMode: String? = null
    var groupId: Any? = null
    private var groupCur: Any? = null
    private val groupKeys = LinkedHashSet<String>()

    /** The readout groups: the set of keys, and the number of groups with this set. */
    val groups: MutableMap<Set<String>, Int> = LinkedHashMap()

    /** The status, error, info, and signature messages, up to [StatLimits.EVENT_CAP]. */
    val events: MutableList<ProbeMessage> = ArrayList()

    /** The probe messages of `P` and `Q` as (time, value). */
    val probe: Map<String, MutableList<Pair<Double, Long>>> = mapOf("P" to ArrayList(), "Q" to ArrayList())

    /** The messages of the phase when the phase keeps them, else null. */
    val msgs: MutableList<ProbeMessage>? = if (keep) ArrayList() else null
    val linkEvents: MutableList<String> = ArrayList()

    /** True after [close]. */
    val closed: Boolean get() = t1 != null

    fun addRx(bytes: Int, dropped: Int, t: Double) {
        if (closed) return
        rxBytes += bytes
        rxBlocks++
        this.dropped += dropped
        tLast = maxOf(tLast, t)
    }

    fun addTx(bytes: Int, bel: Boolean, t: Double) {
        if (closed) return
        txBytes += bytes
        if (bel) txBel++ else txCmd++
        tLast = maxOf(tLast, t)
    }

    fun addRejected(text: String) {
        if (closed) return
        rejectedChars += text.length
        if (rejSamples.size < 6) rejSamples += text.take(100)
    }

    /** A new link opened or the link closed: the groups and the valid time start again. */
    fun linkReset(text: String, t: Double) {
        closeGroup()
        groupMode = null
        groupCur = null
        lastValid = null
        linkEvents += "${ms(t)} ms: $text"
    }

    private fun closeGroup() {
        if (groupKeys.isNotEmpty()) {
            val key = groupKeys.toSet()
            groups[key] = (groups[key] ?: 0) + 1
            groupKeys.clear()
        }
    }

    /** Adds one message. */
    fun add(m: ProbeMessage) {
        if (closed) return
        tLast = maxOf(tLast, m.t)
        val kept = msgs
        if (kept != null && kept.size < StatLimits.MSG_CAP && m.kind != MessageKind.Unknown) kept += m
        if (m.kind == MessageKind.Unknown) {
            unknown++
            return
        }
        keys.getOrPut(m.key) { KeyStats() }.add(m.t, m.statValue)
        if (m.valid) {
            valid++
            val previous = lastValid
            if (previous != null) {
                val gap = m.t - previous
                maxGap = maxOf(maxGap, gap)
                if (gap > StatLimits.NO_DATA_LIMIT_S) longGaps += previous to gap
            }
            lastValid = m.t
        }
        when (m.kind) {
            MessageKind.Time -> {
                // The readouts after an '@' get the time of that '@'.
                at += m.t to (m.number ?: 0L)
                closeGroup()
                groupMode = "@"
                groupId = "@" to at.size
                groupCur = groupId
            }
            MessageKind.Count, MessageKind.Bool -> {
                val id: Any? = if (groupMode == "@") groupId else "block" to m.t
                if (id != groupCur) {
                    closeGroup()
                    groupCur = id
                }
                groupKeys += m.key
                if (m.kind == MessageKind.Bool) probe[m.key]?.add(m.t to (m.number ?: 0L))
            }
            else -> if (events.size < StatLimits.EVENT_CAP) events += m
        }
    }

    /** Ends the phase at [t]. */
    fun close(t: Double) {
        if (t1 == null) {
            closeGroup()
            t1 = t
        }
    }

    /** The length of the phase in seconds, at least 1 µs. */
    fun duration(): Double = maxOf((t1 ?: tLast) - t0, 1e-6)

    /** The readout keys in the order of the tool (no `@ $ ! ?`). */
    fun readoutKeys(): List<String> = KeyOrder.sorted(keys.keys).filter { key -> key !in setOf("@", "$", "!", "?") }

    /** The report lines of the phase (`Stats.report`). */
    fun report(pair: Pair<String, String>? = null, events: Int = 12): List<String> {
        val duration = duration()
        val out = ArrayList<String>()
        out += "Phase '$name': ${fixed(duration, 1)} s, $rxBytes bytes in $rxBlocks blocks, $valid valid messages " +
            "(${fixed(valid / duration, 1)}/s), $txCmd command writes, $txBel BEL bytes."
        val sorted = KeyOrder.sorted(keys.keys)
        if (sorted.isNotEmpty()) {
            out += "    key     count  rate/s  median ms   p95 ms    max ms  values  repeats  first -> last"
            for (key in sorted) {
                val stats = keys.getValue(key)
                val gaps = stats.gaps.sorted()
                val values = if (stats.distinct.size <= StatLimits.DISTINCT_CAP) stats.distinct.size.toString() else ">${StatLimits.DISTINCT_CAP}"
                val span = when (key) {
                    "@" -> "0x${PyText.hex(stats.firstValue as Long)} -> 0x${PyText.hex(stats.value as Long)}"
                    "$", "!", "?" -> ""
                    else -> "${PyText.str(stats.firstValue)} -> ${PyText.str(stats.value)}"
                }
                out += "    ${left(key, 4)} ${right(stats.count, 8)} ${right(fixed(stats.count / duration, 2), 7)} " +
                    "${right(ms(stats.medianGap()), 10)} ${right(ms(Analysis.pct(gaps, 0.95)), 8)} " +
                    "${right(ms(gaps.lastOrNull()), 9)} ${right(values, 7)} ${right(stats.repeats, 8)}  $span"
            }
        }
        out += Analysis.atSummary(at)
        out += Analysis.groupSummary(groups, pair)
        if (valid > 0) {
            out += "    Longest time between two valid messages: ${ms(maxGap)} ms. Times of more than 7 s (the NO_DATA limit): " +
                "${longGaps.size}."
        }
        val dots = keys["."]
        val dirty = this.events.filter { m -> m.kind == MessageKind.Status && m.sub == DIRTY_AXES }
        if (dots != null || dirty.isNotEmpty()) {
            out += "    Dirty status: ${dots?.count ?: 0} '.' messages, ${dirty.size} '\$32' messages."
        }
        val shown = this.events.filter { m -> m.kind != MessageKind.Unknown }
        if (shown.isNotEmpty()) {
            out += "    Status, error, and info messages: ${shown.size} (first ${minOf(events, shown.size)} below)."
            shown.take(events).forEach { m ->
                out += "      ${right(ms(m.t), 10)} ms  ${left(m.text.take(24), 24)} ${ProbeTexts.describe(m)}"
            }
        }
        if (unknown > 0 || rejectedChars > 0) {
            out += "    Messages that are not valid: $unknown. Characters outside valid messages: $rejectedChars. Examples: " +
                rejSamples.take(3).joinToString(" | ") { sample -> PyText.repr(sample) }
        }
        if (dropped > 0) out += "    Bytes below 0x20 or above 0x7E (removed): $dropped."
        linkEvents.forEach { event -> out += "    Link: $event" }
        return out
    }

    private companion object {
        const val DIRTY_AXES = 0x32
    }
}

/** The analysis functions of the tool that work on lists of times and values. */
object Analysis {

    /** `statistics.median`: the middle value, or the mean of the two middle values. Null for an empty list. */
    fun median(values: Collection<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
    }

    /** `pct`: the value at the place `round(q × (n − 1))` (round half to even) of a sorted list. */
    fun pct(sorted: List<Double>, q: Double): Double? {
        if (sorted.isEmpty()) return null
        val index = minOf(sorted.size - 1, kotlin.math.round(q * (sorted.size - 1)).toInt())
        return sorted[index]
    }

    /** The least squares line through the points: (slope, largest residual), or null. */
    fun fitLine(points: List<Pair<Double, Double>>): Pair<Double, Double>? {
        val n = points.size
        if (n < 3) return null
        val mx = points.sumOf { it.first } / n
        val my = points.sumOf { it.second } / n
        val sxx = points.sumOf { (it.first - mx) * (it.first - mx) }
        if (sxx <= 0.0) return null
        val slope = points.sumOf { (it.first - mx) * (it.second - my) } / sxx
        val intercept = my - slope * mx
        val residual = points.maxOf { kotlin.math.abs(it.second - (slope * it.first + intercept)) }
        return slope to residual
    }

    /** The longest part of the `@` points with no decrease of the value. */
    fun atPointsSegment(points: List<Pair<Double, Long>>): List<Pair<Double, Long>> {
        var best: List<Pair<Double, Long>> = emptyList()
        var current = ArrayList<Pair<Double, Long>>()
        for (point in points) {
            if (current.isNotEmpty() && point.second < current.last().second) {
                if (current.size > best.size) best = current
                current = ArrayList()
            }
            current += point
        }
        return if (current.size > best.size) current else best
    }

    /** The fit of the `@` rate: (units for each second, residual), or null. */
    fun atFit(points: List<Pair<Double, Long>>): Pair<Double, Double>? {
        val segment = atPointsSegment(points)
        if (segment.size <= 2) return null
        val origin = segment.first()
        return fitLine(segment.map { (t, v) -> (t - origin.first) to (v - origin.second).toDouble() })
    }

    /** The `@` lines of a phase report (`at_summary`). */
    fun atSummary(points: List<Pair<Double, Long>>): List<String> {
        if (points.isEmpty()) return listOf("    Time stamps '@': none.")
        val gaps = points.zipWithNext { a, b -> b.first - a.first }
        val decreases = points.zipWithNext().count { (a, b) -> b.second < a.second }
        val out = ArrayList<String>()
        out += "    Time stamps '@': ${points.size}, median period ${ms(median(gaps))} ms, values 0x${PyText.hex(points.first().second)} -> " +
            "0x${PyText.hex(points.last().second)}, decreases $decreases."
        val fit = atFit(points)
        if (fit != null && fit.first > 0.0) {
            val (slope, residual) = fit
            out += "    '@' rate: ${fixed(slope, 2)} units/s, probable unit: ${ProbeTexts.unitName(slope)}. Largest difference from a " +
                "line: ${fixed(residual / slope * 1000.0, 1)} ms of PC time."
        }
        return out
    }

    /** The readout group lines of a phase report (`group_summary`). */
    fun groupSummary(groups: Map<Set<String>, Int>, pair: Pair<String, String>?): List<String> {
        val total = groups.values.sum()
        if (total == 0) return emptyList()
        val out = ArrayList<String>()
        out += "    Readout groups (readouts after one '@', else readouts in one received block): $total."
        groups.entries.sortedByDescending { it.value }.take(4).forEach { (keys, n) ->
            out += "      ${left(KeyOrder.sorted(keys).joinToString(" "), 28)} ${right(fixed(100.0 * n / total, 1), 6)} %"
        }
        if (pair != null) {
            val (a, b) = pair
            val both = groups.filterKeys { keys -> a in keys && b in keys }.values.sum()
            val some = groups.filterKeys { keys -> a in keys || b in keys }.values.sum()
            out += if (some > 0) {
                "    Pair $a + $b: both in the same group in ${fixed(100.0 * both / some, 1)} % of the $some groups with $a or $b."
            } else {
                "    Pair $a + $b: no group has $a or $b."
            }
        }
        return out
    }

    /** One run of one probe value (`probe_runs`). */
    class ProbeRun(val value: Long, val first: Double) {
        var last: Double = first
        var count: Int = 1
        var maxGap: Double = 0.0
        val gaps: MutableList<Double> = ArrayList()
    }

    /** Splits (time, value) events into runs of one value. */
    fun probeRuns(events: List<Pair<Double, Long>>): List<ProbeRun> {
        val runs = ArrayList<ProbeRun>()
        for ((t, value) in events) {
            val run = runs.lastOrNull()
            if (run != null && run.value == value) {
                run.maxGap = maxOf(run.maxGap, t - run.last)
                run.gaps += t - run.last
                run.last = t
                run.count++
            } else {
                runs += ProbeRun(value, t)
            }
        }
        return runs
    }

    /** The Q16 lines (`readout_lines`). */
    fun readoutLines(st: PhaseStats, label: String): List<String> {
        val keys = st.readoutKeys().filter { key -> key != "P" && key != "Q" }
        if (keys.isEmpty()) return listOf("Q16: no numeric readout in the phase '$label'.")
        val duration = st.duration()
        return keys.map { key ->
            val stats = st.keys.getValue(key)
            val same = stats.distinct.size == 1
            "Q16: $key: ${stats.count} readouts in ${fixed(duration, 1)} s (${fixed(stats.count / duration, 2)}/s), " +
                "${stats.distinct.size} values, ${stats.repeats} repeats of the last value, median period ${ms(stats.medianGap())} ms." +
                if (same && stats.count > 2) " The adapter sends this input also when the count does not change." else ""
        }
    }

    /** The Q18, Q37, and Q15 lines (`special_lines`). */
    fun specialLines(st: PhaseStats): List<String> {
        val dots = st.keys["."]?.count ?: 0
        val dirty = st.events.count { m -> m.kind == MessageKind.Status && m.sub == 0x32 }
        val q = st.keys["Q"]?.count ?: 0
        val out = ArrayList<String>()
        out += "Q18: 'Q' messages: $q. '.' messages: $dots. '\$32' messages: $dirty."
        out += "Q37: '@' time stamps: ${if (st.at.isNotEmpty()) st.at.size.toString() else "none"}."
        val fit = atFit(st.at)
        if (fit != null && fit.first > 0.0) {
            out += "Q15: '@' rate ${fixed(fit.first, 2)} units/s, probable unit ${ProbeTexts.unitName(fit.first)}."
        }
        return out
    }

    /** The Q19 line (`forms_line`). */
    fun formsLine(st: PhaseStats): String {
        val kinds = HashSet<String>()
        for (key in st.keys.keys) {
            kinds += when (key) {
                "@" -> "@"
                "$", "!", "?" -> key
                "P", "Q" -> "P/Q"
                else -> "numeric"
            }
        }
        val twoWay = kinds.any { kind -> kind in setOf("@", "$", "!", "?", "P/Q") }
        return "Q19: message forms: ${kinds.sorted().joinToString(", ").ifEmpty { "none" }}. " +
            if (twoWay) "Forms of the ESP32 two-way adapter are present." else "Only numeric readouts: this agrees with the one-way (legacy) dialect."
    }

    /** The Q37 lines about the messages near the first edges of the probe input [pk] (`edge_lines`). */
    fun edgeLines(st: PhaseStats, pk: String, limit: Int = 5): List<String> {
        val msgs = st.msgs.orEmpty()
        val axisKeys = st.readoutKeys().filter { key -> key != "P" && key != "Q" }
        val periods = axisKeys.associateWith { key -> st.keys.getValue(key).medianGap() }
        var previous: Long? = null
        var edges = 0
        val after = LinkedHashMap<String, MutableList<Double>>()
        var atBefore = 0
        val out = ArrayList<String>()
        msgs.forEachIndexed { index, m ->
            if (m.kind != MessageKind.Bool || m.key != pk) return@forEachIndexed
            if (previous == null || m.number == previous) {
                previous = m.number
                return@forEachIndexed
            }
            previous = m.number
            edges++
            if (index > 0 && msgs[index - 1].kind == MessageKind.Time && msgs[index - 1].t == m.t) atBefore++
            for (key in axisKeys) {
                val next = msgs.subList(index + 1, minOf(msgs.size, index + 60)).firstOrNull { x -> x.key == key }
                if (next != null) after.getOrPut(key) { ArrayList() } += next.t - m.t
            }
            if (edges <= limit) {
                val window = msgs.subList(maxOf(0, index - 4), minOf(msgs.size, index + 7))
                out += "    edge $edges ($pk${m.number}): " + window.joinToString(" ") { x ->
                    "${x.key}${if (x.kind == MessageKind.Bool) x.number.toString() else ""}(${PyText.signed((x.t - m.t) * 1000.0, 1)})"
                }
            }
        }
        if (edges == 0) return listOf("Q37: no edge of $pk in the recorded phases.")
        out.add(
            0,
            "Q37: $edges edges of $pk. An '@' came directly before the edge in the same block at $atBefore edges. Messages near " +
                "the first edges (key, time in ms from the edge):",
        )
        for (key in axisKeys) {
            val list = after[key] ?: continue
            if (list.isNotEmpty()) {
                out += "Q37: next $key readout after an edge: median ${ms(median(list))} ms (normal period ${ms(periods[key])} ms)."
            }
        }
        return out
    }
}
