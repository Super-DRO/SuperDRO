package org.superdro.core.adapterprobe

import org.superdro.core.protocol.AdapterField
import org.superdro.core.protocol.AdapterMessage
import org.superdro.core.protocol.AdapterParser
import org.superdro.core.protocol.StatusCode
import org.superdro.core.protocol.Subsystem

/** The message kinds of `adapter_probe.py`. [pyName] is the name in the tool and in the summary lines. */
enum class MessageKind(val pyName: String) {
    Count("count"),
    Bool("bool"),
    Time("time"),
    Status("status"),
    Error("error"),
    Info("info"),
    Signature("sig"),
    Unknown("unknown"),
}

/**
 * One message of the SuperDRO [AdapterParser], with the data that the statistics of the tool
 * need: the text on the wire and the arrival time of the block with its first character.
 *
 * @property message The message of the SuperDRO parser.
 * @property text The text on the wire. A complete message ends with `;`. An [MessageKind.Unknown]
 *   has the text up to the character that ended it.
 * @property t The arrival time in seconds after the start of the run.
 */
class ProbeMessage(val message: AdapterMessage, val text: String, val t: Double) {

    /** The kind of the tool. */
    val kind: MessageKind = when (message) {
        is AdapterMessage.AxisCount -> MessageKind.Count
        is AdapterMessage.BinaryInput -> MessageKind.Bool
        is AdapterMessage.Timestamp -> MessageKind.Time
        is AdapterMessage.Status -> MessageKind.Status
        is AdapterMessage.Error -> MessageKind.Error
        is AdapterMessage.Info -> MessageKind.Info
        is AdapterMessage.Signature -> MessageKind.Signature
        is AdapterMessage.Unknown -> MessageKind.Unknown
    }

    /** The key of the statistics: the upper case prefix (`.` for the dirty status), `@`, `$`, `!`, `?`, or `unknown`. */
    val key: String = when (message) {
        is AdapterMessage.AxisCount -> message.channel.uppercaseChar().toString()
        is AdapterMessage.BinaryInput -> message.channel.uppercaseChar().toString()
        is AdapterMessage.Timestamp -> "@"
        is AdapterMessage.Status -> "$"
        is AdapterMessage.Error -> "!"
        is AdapterMessage.Info, is AdapterMessage.Signature -> "?"
        is AdapterMessage.Unknown -> "unknown"
    }

    /** The number of a count, a probe value, or a time stamp, else null. */
    val number: Long? = when (message) {
        is AdapterMessage.AxisCount -> message.count.toLong()
        is AdapterMessage.BinaryInput -> message.value.toLong()
        is AdapterMessage.Timestamp -> message.adapterTime
        else -> null
    }

    /** The text value of an info or a signature, else null. */
    val body: String? = when (message) {
        is AdapterMessage.Info -> message.text
        is AdapterMessage.Signature -> message.base64
        else -> null
    }

    /** The subsystem of a status or an error, else null. */
    val sub: Int? = when (message) {
        is AdapterMessage.Status -> message.subsystem
        is AdapterMessage.Error -> message.subsystem
        else -> null
    }

    /** The code of a status or an error, else null. */
    val code: Int? = when (message) {
        is AdapterMessage.Status -> message.code
        is AdapterMessage.Error -> message.code
        else -> null
    }

    /** The field ID of an info (or 0x64 for a signature), else null. */
    val field: Int? = when (message) {
        is AdapterMessage.Info -> message.fieldId
        is AdapterMessage.Signature -> AdapterField.Signature.id
        else -> null
    }

    /** True for a valid message. */
    val valid: Boolean get() = message.isValid

    /** The value of the statistics: the number of a count, a probe value, or a time stamp, else the text. */
    val statValue: Any get() = number ?: text

    /** True for a status or an error. */
    val isStatusOrError: Boolean get() = kind == MessageKind.Status || kind == MessageKind.Error

    /** True for a `!` message, or a `$` message with a code from 1 to 159 (the error path). */
    fun errorPath(): Boolean = kind == MessageKind.Error || (kind == MessageKind.Status && StatusCode.isErrorPath(code ?: 0))

    /** True for the ack `$0aa3;`. */
    fun isAck(): Boolean = kind == MessageKind.Status && sub == Subsystem.RX && code == StatusCode.RECEIVED

    /** True for the RX Busy, `$0a20;` or `!0a20;`. */
    fun isBusy(): Boolean = isStatusOrError && sub == Subsystem.RX && code == StatusCode.BUSY

    override fun toString(): String = "ProbeMessage(${kind.pyName}, ${PyText.repr(text)}, $t)"
}

/**
 * The result of one received block.
 *
 * @property messages The messages that ended in the block.
 * @property rejected The characters outside valid messages, in their order: the junk characters and
 *   the characters of the messages that are not valid (the text of `rejected` in the tool).
 * @property dropped The bytes below 0x20 or above 0x7E.
 */
class FeedResult(val messages: List<ProbeMessage>, val rejected: String, val dropped: Int)

/**
 * Gives each block to the SuperDRO [AdapterParser] (the app has no second parser) and keeps
 * the data that the parser does not give: the text of each message, the time of the block with
 * its first character, the junk characters, and the removed bytes.
 *
 * The feed gives the parser one byte at a time. Each byte ends one message or none, so the feed
 * knows the character that ended each message. The parser gives the same messages for a block and
 * for its bytes one at a time.
 */
class MessageFeed(val parser: AdapterParser = AdapterParser()) {

    private val text = StringBuilder()
    private var start = 0.0

    /** The bytes below 0x20 or above 0x7E since the feed was made. */
    var dropped: Long = 0L
        private set

    /** Parses one block that arrived at [t] seconds ([timeMs] on the session clock). */
    fun feed(bytes: ByteArray, t: Double, timeMs: Long = (t * 1000.0).toLong()): FeedResult {
        val messages = ArrayList<ProbeMessage>()
        val rejected = StringBuilder()
        var removed = 0
        for (index in bytes.indices) {
            val code = bytes[index].toInt() and 0xFF
            if (code < FIRST_PRINTABLE || code > LAST_PRINTABLE) {
                removed++
                continue
            }
            val character = code.toChar()
            val wasIn = parser.inMessage
            val message = parser.feed(bytes, index, 1, timeMs).firstOrNull()
            val nowIn = parser.inMessage
            when {
                message == null && !wasIn && nowIn -> begin(character, t)
                message == null && !wasIn -> rejected.append(character)
                message == null -> text.append(character)
                message !is AdapterMessage.Unknown -> {
                    text.append(character)
                    messages += ProbeMessage(message, text.toString(), start)
                    text.setLength(0)
                }
                else -> {
                    messages += ProbeMessage(message, text.toString(), start)
                    rejected.append(text)
                    text.setLength(0)
                    // The ending character starts a new message, or it is junk, or it is the `;`
                    // that the parser consumed. The tool counts the last two as rejected.
                    if (nowIn) begin(character, t) else rejected.append(character)
                }
            }
        }
        dropped += removed
        return FeedResult(messages, rejected.toString(), removed)
    }

    private fun begin(character: Char, t: Double) {
        text.setLength(0)
        text.append(character)
        start = t
    }

    private companion object {
        const val FIRST_PRINTABLE = 0x20
        const val LAST_PRINTABLE = 0x7E
    }
}
