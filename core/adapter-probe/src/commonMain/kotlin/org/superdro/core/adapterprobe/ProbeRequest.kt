package org.superdro.core.adapterprobe

import kotlinx.coroutines.CompletableDeferred
import org.superdro.core.protocol.AdapterCommand
import org.superdro.core.protocol.CommandResult

/**
 * The result of one request (`Req` of the tool). The SuperDRO command queue sends the request.
 * The session records the times and the answer.
 *
 * @property text The command text, for example `"#get:mac;"`.
 * @property command The command of the queue, or null for a raw text that does not go through the queue.
 */
class ProbeRequest internal constructor(
    val text: String,
    val command: AdapterCommand?,
    internal val auto: Boolean,
) {
    /** The time of the end of the first write, or null when the write did not go out. */
    var tSend: Double? = null
        internal set

    /** The time of the first ack `$0aa3;`, or null. */
    var tAck: Double? = null
        internal set

    /** The answer that completed the request, or the error answer, or null. */
    var answer: ProbeMessage? = null
        internal set

    /** The time of [answer], or null. */
    var tAnswer: Double? = null
        internal set

    /** True when no answer came in the wait. */
    var timeout: Boolean = false
        internal set

    /** True when the link closed before an answer. */
    var closed: Boolean = false
        internal set

    /** True when the answer is an error, or when the request ended with a failure. */
    var error: Boolean = false
        internal set

    /** The number of RX Busy answers. */
    var busy: Int = 0
        internal set

    /** The status, error, and info messages in the time after the end (the "tail"). */
    val tail: MutableList<ProbeMessage> = ArrayList()

    /** The final result of the SuperDRO command queue, or null for a raw text. */
    var result: CommandResult? = null
        internal set

    /** The sequence number of the last event that the request read. */
    internal var consumedSeq: Long = 0L

    private val done = CompletableDeferred<Unit>()

    /** True when the request ended. */
    val isDone: Boolean get() = done.isCompleted

    internal fun finish() {
        done.complete(Unit)
    }

    internal suspend fun awaitDone() {
        done.await()
    }

    /** True when the answer is an info (with the field [field], when it is not null). */
    fun okInfo(field: Int? = null): Boolean {
        val a = answer ?: return false
        return a.kind == MessageKind.Info && (field == null || a.field == field)
    }

    /** The text of the request result, the same as `Req.summary` of the tool. */
    fun summary(): String {
        val parts = ArrayList<String>()
        parts += "${PyText.repr(text)}:"
        val send = tSend
        if (send == null) {
            parts += "not sent (the link is not open)"
            return parts.joinToString(" ")
        }
        parts += "ack ${tAck?.let { PyText.ms(it - send) } ?: "none"} ms,"
        val a = answer
        when {
            a != null -> parts += "answer after ${PyText.ms((tAnswer ?: a.t) - send)} ms: ${a.text.take(60)} (${ProbeTexts.describe(a)})"
            closed -> parts += "the link closed before an answer"
            else -> parts += "no answer (timeout ${if (tAck != null) "after the ack" else "of the first 500 ms"})"
        }
        if (busy > 0) parts += "Busy answers: $busy."
        val extra = tail.filter { m -> m.kind != MessageKind.Unknown }
        if (extra.isNotEmpty()) parts += "Later: " + extra.take(6).joinToString(", ") { m -> m.text.take(30) }
        return parts.joinToString(" ")
    }
}
