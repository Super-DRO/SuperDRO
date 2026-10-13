package org.superdro.validation.protocol

import org.superdro.core.protocol.AdapterCommand
import org.superdro.core.protocol.AdapterParser
import org.superdro.core.protocol.CommandQueue
import org.superdro.core.protocol.CommandResult
import org.superdro.core.protocol.LinkPhase
import org.superdro.core.protocol.QueueOutput

/**
 * Runs a [CommandQueue] with a virtual time. The driver calls [CommandQueue.onTimer] at each
 * timer time of the queue, and it records each output with its time. The inbound messages come
 * from the wire text through a new [AdapterParser], as the session does.
 */
class QueueDriver(val queue: CommandQueue = CommandQueue()) {

    /** One write of the queue. */
    data class Wire(val timeMs: Long, val text: String, val transmission: Int)

    var nowMs: Long = 0L
        private set

    val outputs = ArrayList<Pair<Long, QueueOutput>>()

    private val parser = AdapterParser()

    /** The number of writes that [runWithAdapter] answered or skipped. */
    private var answered = 0

    /** The writes, in their order. */
    val writes: List<Wire>
        get() = outputs.mapNotNull { (time, output) ->
            (output as? QueueOutput.Write)?.let { Wire(time, ascii(it.bytes), it.transmission) }
        }

    /** The texts of the writes, in their order. */
    val texts: List<String> get() = writes.map { it.text }

    /** The results, in their order. */
    val results: List<Pair<Long, QueueOutput.Result>>
        get() = outputs.mapNotNull { (time, output) -> (output as? QueueOutput.Result)?.let { time to it } }

    /** Gives the outputs of the type [T], in their order. */
    inline fun <reified T : QueueOutput> all(): List<T> = outputs.map { it.second }.filterIsInstance<T>()

    /** Gives the result of the request [id], or null when it has no result. */
    fun resultOf(id: Long): CommandResult? = results.firstOrNull { it.second.requestId == id }?.second?.result

    /** Gives the time of the result of the request [id], or null. */
    fun resultTimeOf(id: Long): Long? = results.firstOrNull { it.second.requestId == id }?.first

    private fun record(list: List<QueueOutput>) {
        list.forEach { output -> outputs += nowMs to output }
    }

    /** Runs the timers up to [timeMs], with [timeMs] included. */
    fun advanceTo(timeMs: Long) {
        require(timeMs >= nowMs) { "The time cannot go back from $nowMs to $timeMs." }
        var guard = 0
        while (true) {
            val next = queue.nextTimerAtMs ?: break
            if (next > timeMs) break
            val at = maxOf(nowMs, next)
            if (at == nowMs && next <= nowMs) guard++ else guard = 0
            if (guard > 50) break
            nowMs = at
            record(queue.onTimer(at))
        }
        nowMs = timeMs
    }

    fun phase(timeMs: Long, phase: LinkPhase) {
        advanceTo(timeMs)
        record(queue.onLinkPhase(phase, timeMs))
    }

    fun connected(timeMs: Long) = phase(timeMs, LinkPhase.Connected)

    /** The adapter sends [text] at [timeMs]. */
    fun receive(timeMs: Long, text: String) {
        advanceTo(timeMs)
        for (message in parser.feed(text.encodeToByteArray(), timeMs)) record(queue.onMessage(message, timeMs))
    }

    fun submit(timeMs: Long, command: AdapterCommand): Long {
        advanceTo(timeMs)
        val submission = queue.submit(command, timeMs)
        record(submission.outputs)
        return submission.requestId
    }

    fun openDetails(timeMs: Long) {
        advanceTo(timeMs)
        record(queue.openAdapterDetails(timeMs))
    }

    /**
     * Answers each write after the last answer with [answer], 1 ms after the write, up to
     * [untilMs]. [answer] gives the wire text for a command text, or null for no answer.
     */
    fun runWithAdapter(untilMs: Long, answer: (String) -> String?) {
        var steps = 0
        while (steps++ < 100_000) {
            val next = queue.nextTimerAtMs
            val pending = writes.drop(answered)
            if (pending.isNotEmpty()) {
                val write = pending.first()
                answered += 1
                val text = answer(write.text) ?: continue
                val at = maxOf(nowMs, write.timeMs + 1L)
                if (at > untilMs) break
                receive(at, text)
                continue
            }
            if (next == null || next > untilMs) break
            advanceTo(maxOf(nowMs, next))
        }
        advanceTo(untilMs)
    }

    companion object {
        fun ascii(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 0xFF).toChar().toString() }

        const val MAC = "\"#get:mac;\""
        const val UUID = "\"#get:uuid;\""
        const val FEATURES = "\"#get:features;\""
        const val UPTIME = "\"#get:uptime;\""
        const val MODEL = "\"#get:model;\""
        const val NAME = "\"#get:name;\""
        const val VERSION = "\"#get:version;\""
        const val BUILD = "\"#get:build;\""
        const val DATE = "\"#get:date;\""

        val START_QUERIES = listOf(MAC, UUID, FEATURES, UPTIME, MODEL) + "xyzwuv".map { "#get_mode:$it;" }
        val DETAILS_QUERIES = listOf(NAME, VERSION, BUILD, DATE) + "xyzwuv".map { "#get_mode:$it;" }

        /** The answers of the worked connect sequence and of a genuine adapter. */
        fun goodAdapter(text: String): String? = when (text) {
            MAC -> "?05246f28a1b2c3;"
            UUID -> "?02c0ffee00-1234-4abc-8def-0123456789ab;"
            FEATURES -> "?0a1;"
            UPTIME -> "?09e10;"
            MODEL -> "?04tda-example;"
            NAME -> "?03Mill adapter;"
            VERSION -> "?061.2.3;"
            BUILD -> "?07b42;"
            DATE -> "?086955b900;"
            else -> if (text.startsWith("#get_mode:")) "?01${text[10]}000f0002;" else null
        }
    }
}
