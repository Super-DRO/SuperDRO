package org.superdro.core.readout.input

import org.superdro.core.model.AxisId
import org.superdro.core.model.Channels

/**
 * The input stage of one session. It processes
 * each `AxisCount` and `BinaryInput` of the parser in stream order. It must see each sample: do
 * not give it a conflated flow.
 *
 * For each sample, the stage:
 * - applies the encoder profile pipeline of the input (filter, error map, backlash, scale),
 * - ignores the letters A to F, R, S, and `.`,
 * - makes the weighted sum of the inputs of each axis with the projections,
 * - keeps the input history,
 * - does the probe debounce, the watchdog, the contact type, and the latch.
 *
 * The stage takes plain values (the channel letter, the count, and the time), not the types of
 * `core:protocol`. The stage has no coroutines. The session calls it from one coroutine, and
 * calls [onTick] at a regular interval (for example each 100 ms) for the probe watchdog and the
 * latch time limit.
 */
class InputStage(configuration: InputConfiguration = InputConfiguration()) {

    /** The current configuration. */
    var configuration: InputConfiguration = configuration
        private set

    /** The input history of the session. */
    val history: InputHistory = InputHistory()

    private val channels = LinkedHashMap<Char, EncoderChannel>()
    private val probes = linkedMapOf(
        Channels.P to ProbeInput(Channels.P, configuration.probe),
        Channels.Q to ProbeInput(Channels.Q, configuration.probe),
    )
    private val pendingLatches = ArrayList<PendingLatch>()
    private var probeSequence = 0L
    private var latestTime: Long? = null

    init {
        history.setTerms(configuration.axes, null)
    }

    /** The latest snapshot: the value of each channel, the sum of each axis, and the probe states. */
    val snapshot: InputSnapshot
        get() = InputSnapshot(
            channels = channels.values.mapNotNull { channel -> channel.snapshot() }.associateBy { value -> value.input },
            axes = configuration.axes.mapValues { (_, terms) -> axisValue(terms) },
            probes = probes.mapValues { (_, probe) -> probe.active },
            probeSequence = probeSequence,
        )

    /**
     * The value of the input after the last [onAxisCount] call, or null when that call dropped its
     * sample (an earlier time than the last sample, or a letter that is not an encoder input). The
     * session gives each such value to the stream of all samples.
     */
    var acceptedSample: ChannelValue? = null
        private set

    /**
     * Processes one numeric readout: the channel letter [channel], the count [count], and the
     * time [timeMs]. Gives the probe events that this sample completes.
     */
    fun onAxisCount(channel: Char, count: Long, timeMs: Long): List<ProbeEvent> {
        acceptedSample = null
        val letter = Channels.normalize(channel)
        if (letter !in Channels.encoderInputs) return advance(timeMs)
        val encoder = channelFor(letter)
        val accepted = encoder.process(count, timeMs)
        if (accepted) acceptedSample = encoder.snapshot()
        val events = ArrayList<ProbeEvent>()
        // The deadline comes first: a sample with a time after the deadline counts as the deadline.
        events.addAll(advance(timeMs))
        if (accepted) resolveWithSample(letter, timeMs, encoder.correctedCount, events)
        return events
    }

    /**
     * Processes one boolean readout of a probe port: the channel letter [channel] (`p` or `q`),
     * the value [value] (0 or 1), and the time [timeMs]. Gives the probe events that complete.
     */
    fun onBinaryInput(channel: Char, value: Int, timeMs: Long): List<ProbeEvent> {
        val probe = probes[Channels.normalize(channel)] ?: return advance(timeMs)
        val events = ArrayList<ProbeEvent>()
        events.addAll(advance(timeMs))
        if (probe.onMessage(value, timeMs)) {
            probeSequence += 1
            startLatch(probeSequence, probe.port, timeMs, events)
        }
        return events
    }

    /**
     * Moves the time of the stage to [nowMs] (session clock) with no sample: the probe watchdog
     * and the latch deadline. A tick at or after the deadline `t + 500 ms` of a latch gives the
     * same result as a message after the deadline. Gives the probe events that
     * complete.
     */
    fun onTick(nowMs: Long): List<ProbeEvent> = advance(nowMs, tick = true)

    /**
     * Starts all stages again at the start of a link: the first count
     * of each input after the connection has `d` = 0. The history is cleared, the probes are not
     * active, and the open latches are discarded. The last values stay in the snapshot until the
     * next sample.
     */
    fun onLinkStart() {
        channels.values.forEach { channel -> channel.restart() }
        history.clear()
        probes.values.forEach { probe -> probe.reset() }
        pendingLatches.clear()
        latestTime = null
    }

    /**
     * Uses [newConfiguration]. Only the inputs whose stage values changed
     * get a new filter state. A change of the terms of an axis makes the history of that axis
     * unknown before the time of the change.
     */
    fun reconfigure(newConfiguration: InputConfiguration) {
        val old = configuration
        configuration = newConfiguration
        channels.forEach { (letter, channel) ->
            val profile = newConfiguration.encoders[letter]
            channel.reconfigure(profile)
            history.setScale(letter, channel.scale)
        }
        history.setTerms(newConfiguration.axes, latestTime)
        if (old.probe != newConfiguration.probe) probes.values.forEach { probe -> probe.settings = newConfiguration.probe }
    }

    private fun channelFor(letter: Char): EncoderChannel = channels.getOrPut(letter) {
        EncoderChannel(letter, configuration.encoders[letter], history.channelFor(letter)).also { channel ->
            history.setScale(letter, channel.scale)
        }
    }

    private fun axisValue(terms: AxisTerms): AxisValue {
        if (terms.terms.isEmpty()) return AxisValue(terms.axis, 0.0, null, AxisValueStatus.Ok, terms.projected)
        var sum = 0.0
        var time: Long? = null
        var status = AxisValueStatus.Ok
        for (term in terms.terms) {
            val channel = channels[term.input]
            if (channel == null || channel.rawCount == null) {
                status = worse(status, AxisValueStatus.NoData)
                continue
            }
            val termStatus = when (channel.status) {
                ChannelStatus.Disabled -> AxisValueStatus.NoInput
                ChannelStatus.NoProfile, ChannelStatus.InvalidResolution -> AxisValueStatus.Error
                ChannelStatus.Ok -> if (channel.value == null) AxisValueStatus.NoData else AxisValueStatus.Ok
            }
            status = worse(status, termStatus)
            val value = channel.value
            if (value != null) sum += term.factor * value
            val termTime = channel.timeMs
            val latest = time
            if (termTime != null && (latest == null || termTime > latest)) time = termTime
        }
        return AxisValue(terms.axis, if (status == AxisValueStatus.Ok) sum else null, time, status, terms.projected)
    }

    private fun worse(a: AxisValueStatus, b: AxisValueStatus): AxisValueStatus = if (rank(b) > rank(a)) b else a

    private fun rank(status: AxisValueStatus): Int = when (status) {
        AxisValueStatus.Ok -> 0
        AxisValueStatus.NoData -> 1
        AxisValueStatus.NoInput -> 2
        AxisValueStatus.Error -> 3
    }

    // The probe latch.

    /**
     * Moves the time to [timeMs]. A message with a time after the deadline `t + 500 ms`, or a
     * tick at or after the deadline, resolves each open input of the latch with its last sample
     * at or before `t` (complete).
     */
    private fun advance(timeMs: Long, tick: Boolean = false): List<ProbeEvent> {
        val latest = latestTime
        if (latest == null || timeMs > latest) latestTime = timeMs
        probes.values.forEach { probe -> probe.advanceTo(timeMs) }
        val events = ArrayList<ProbeEvent>()
        pendingLatches.forEach { latch ->
            val deadline = latch.timeMs + LATCH_WAIT_MS
            if (timeMs > deadline || (tick && timeMs >= deadline)) {
                latch.inputs.values.filter { input -> !input.resolved }.forEach { input -> input.useBefore() }
            }
        }
        emitResolved(events)
        return events
    }

    private fun startLatch(sequence: Long, port: Char, timeMs: Long, events: MutableList<ProbeEvent>) {
        val axes = configuration.axes.values.filter { terms -> terms.enabled && terms.axis != AxisId.Tachometer && terms.terms.isNotEmpty() }
        val inputs = LinkedHashMap<Char, LatchInput>()
        axes.forEach { terms ->
            terms.inputs.forEach { letter -> inputs.getOrPut(letter) { latchInput(letter, timeMs) } }
        }
        pendingLatches.add(PendingLatch(sequence, port, timeMs, axes, inputs))
        emitResolved(events)
    }

    private fun latchInput(letter: Char, timeMs: Long): LatchInput {
        val channelHistory = history.channel(letter)
        val input = LatchInput()
        if (channelHistory == null || channelHistory.isEmpty) {
            input.resolveIncomplete()
            return input
        }
        val beforeIndex = channelHistory.indexAtOrBefore(timeMs)
        if (beforeIndex < 0) {
            input.resolveIncomplete()
            return input
        }
        input.beforeTime = channelHistory.timeAt(beforeIndex)
        input.beforeCount = channelHistory.countAt(beforeIndex)
        if (beforeIndex < channelHistory.size - 1) {
            // A sample after the time is already in the history.
            val afterTime = channelHistory.timeAt(beforeIndex + 1)
            if (afterTime > timeMs + LATCH_WAIT_MS) {
                input.useBefore()
            } else {
                input.resolveWith(timeMs, afterTime, channelHistory.countAt(beforeIndex + 1))
            }
        }
        return input
    }

    private fun resolveWithSample(letter: Char, timeMs: Long, corrected: Long?, events: MutableList<ProbeEvent>) {
        if (corrected == null) return
        pendingLatches.forEach { latch ->
            val input = latch.inputs[letter] ?: return@forEach
            if (!input.resolved && timeMs > latch.timeMs && timeMs <= latch.timeMs + LATCH_WAIT_MS) {
                input.resolveWith(latch.timeMs, timeMs, corrected)
            }
        }
        emitResolved(events)
    }

    private fun emitResolved(events: MutableList<ProbeEvent>) {
        while (pendingLatches.isNotEmpty() && pendingLatches.first().inputs.values.all { input -> input.resolved }) {
            events.add(pendingLatches.removeAt(0).toEvent())
        }
    }

    private fun PendingLatch.toEvent(): ProbeEvent {
        val inputPositions = LinkedHashMap<Char, Double>()
        inputs.forEach { (letter, input) ->
            val k = history.scale(letter)
            val count = input.count
            inputPositions[letter] = if (k != null && count != null) count * k else Double.NaN
        }
        val axisPositions = LinkedHashMap<AxisId, Double>()
        val incomplete = LinkedHashSet<AxisId>()
        val velocities = LinkedHashMap<AxisId, Double>()
        axes.forEach { terms ->
            var sum = 0.0
            terms.terms.forEach { term -> sum += term.factor * (inputPositions[term.input] ?: Double.NaN) }
            axisPositions[terms.axis] = sum
            val allComplete = terms.terms.all { term -> inputs[term.input]?.complete == true }
            if (sum.isNaN() || !allComplete) incomplete.add(terms.axis)
            val earlier = history.axisPositionAt(terms.axis, timeMs - VELOCITY_SPAN_MS)
            velocities[terms.axis] = (sum - earlier) / VELOCITY_SPAN_MS * 1000.0
        }
        return ProbeEvent(sequence, port, timeMs, inputPositions, axisPositions, incomplete, velocities)
    }

    private class PendingLatch(
        val sequence: Long,
        val port: Char,
        val timeMs: Long,
        val axes: List<AxisTerms>,
        val inputs: Map<Char, LatchInput>,
    )

    /**
     * The latch of one input: the last sample at or before the time `t`, and the
     * first sample after `t` when it comes by the deadline `t + 500 ms`.
     */
    private class LatchInput {
        var beforeTime: Long = 0L
        var beforeCount: Long = 0L
        var resolved: Boolean = false
            private set

        /** The corrected count at `t`, or null when the input has no sample at all. */
        var count: Double? = null
            private set

        /** False when the input has no sample, or when a gap of more than 500 ms has a count change. */
        var complete: Boolean = false
            private set

        /** The input has no sample at or before `t`: "not complete", with no position. */
        fun resolveIncomplete() {
            resolved = true
            count = null
            complete = false
        }

        /** No sample came by the deadline: use the last sample at or before `t` (complete). */
        fun useBefore() {
            resolved = true
            count = beforeCount.toDouble()
            complete = true
        }

        /**
         * The sample ([afterTime], [afterCount]) came after `t` and by the deadline. A gap of 500 ms
         * or less gives the interpolation. A longer gap with no count change gives that count. A
         * longer gap with a count change gives the last sample at or before `t`, "not complete".
         */
        fun resolveWith(timeMs: Long, afterTime: Long, afterCount: Long) {
            resolved = true
            when {
                afterTime - beforeTime <= LATCH_WAIT_MS -> {
                    val fraction = (timeMs - beforeTime).toDouble() / (afterTime - beforeTime).toDouble()
                    count = beforeCount + fraction * (afterCount - beforeCount)
                    complete = true
                }
                afterCount == beforeCount -> {
                    count = beforeCount.toDouble()
                    complete = true
                }
                else -> {
                    count = beforeCount.toDouble()
                    complete = false
                }
            }
        }
    }

    companion object {
        /** The latch waits up to 500 ms for the next sample of each input. */
        const val LATCH_WAIT_MS: Long = 500L

        /** The time span of the velocity of a probe event. */
        const val VELOCITY_SPAN_MS: Long = 1000L
    }
}
