package org.superdro.core.protocol

/**
 * The parser of the inbound byte stream. It is a pure state
 * machine. It keeps a part message between two chunks. The session makes a new parser for each
 * link.
 *
 * Rules:
 *
 * 1. The parser drops each byte < 0x20 or > 0x7E. This has no effect on the state.
 * 2. Out of a message, a start character starts a message and records the arrival time of its
 *    chunk. Each other character is junk. [junkCount] counts the junk characters for the log.
 * 3. In a message, a character that is not valid at its position ends the message. The parser
 *    gives [AdapterMessage.Unknown] with the text so far. A `;` always ends the message. Each other
 *    character goes to the start rule again: a start character starts a new message, and each
 *    other character is junk.
 * 4. Numeric readout: an optional `-`, then 1 or more digits, then `;`. The value has a range
 *    check at each digit. Zeros at the start are accepted.
 * 5. Boolean: one digit `0` or `1`, then `;`.
 * 6. Time stamp: 1 or more hex digits, then `;`. The value is 0x3E7FFFFFC18 or less.
 * 7. Status and error: 2 hex digits, then 1 to 4 hex digits, then `;`. A short form gives
 *    `Unknown`.
 * 8. Info: 2 hex digits, then 0 to 256 text characters, then `;`. The field `64` is a signature
 *    with the strict rule: Base64 characters, then 0 or more `=`. The parser keeps 1024 characters
 *    or less.
 * 9. A `;` after `?` and one hex digit gives `Unknown`.
 * 10. Hex digits are `0`–`9`, `A`–`F`, `a`–`f`.
 *
 * @property timeBase The time base of the readouts. The session resets it at each
 *   change from `NoData` to `Connected` ([resetTime]).
 */
class AdapterParser(val timeBase: TimeBase = TimeBase()) {

    private enum class Mode { Out, Numeric, Binary, Timestamp, Status, Error, Info, Signature }

    /** The number of junk characters since the parser was made. */
    var junkCount: Long = 0L
        private set

    /** True while the parser holds a part message. */
    val inMessage: Boolean get() = mode != Mode.Out

    private var mode = Mode.Out
    private val text = StringBuilder()
    private var prefix = ' '
    private var arrivalMs = 0L
    private var startMs = 0L
    private var negative = false
    private var digitCount = 0
    private var value = 0L
    private var subsystem = 0
    private var signatureLength = 0
    private var equalsSeen = false

    /** Parses [bytes] that arrived at [arrivalTimeMs] and gives the complete messages. */
    fun feed(bytes: ByteArray, arrivalTimeMs: Long): List<AdapterMessage> = feed(bytes, 0, bytes.size, arrivalTimeMs)

    /** Parses [length] bytes of [bytes] from [offset] that arrived at [arrivalTimeMs]. */
    fun feed(bytes: ByteArray, offset: Int, length: Int, arrivalTimeMs: Long): List<AdapterMessage> {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) {
            "The range $offset + $length is not in a buffer of ${bytes.size} bytes."
        }
        arrivalMs = arrivalTimeMs
        val messages = ArrayList<AdapterMessage>()
        for (index in offset until offset + length) {
            val code = bytes[index].toInt() and 0xFF
            if (code < FIRST_PRINTABLE || code > LAST_PRINTABLE) continue
            accept(code.toChar(), messages)
        }
        return messages
    }

    /** Parses the UTF-8 bytes of [text]. Each byte that is not printable ASCII is dropped (rule 1). */
    fun feed(text: String, arrivalTimeMs: Long): List<AdapterMessage> = feed(text.encodeToByteArray(), arrivalTimeMs)

    /** Resets the time base. The session calls it at each change from `NoData` to `Connected`. */
    fun resetTime() {
        timeBase.reset()
    }

    private fun accept(character: Char, out: MutableList<AdapterMessage>) {
        when (mode) {
            Mode.Out -> outside(character)
            Mode.Numeric -> numeric(character, out)
            Mode.Binary -> binary(character, out)
            Mode.Timestamp -> timestamp(character, out)
            Mode.Status, Mode.Error -> status(character, out)
            Mode.Info -> info(character, out)
            Mode.Signature -> signature(character, out)
        }
    }

    private fun outside(character: Char) {
        if (ProtocolFrame.isStart(character)) start(character) else junkCount++
    }

    private fun start(character: Char) {
        text.setLength(0)
        text.append(character)
        prefix = character.uppercaseChar()
        startMs = arrivalMs
        negative = false
        digitCount = 0
        value = 0L
        subsystem = 0
        signatureLength = 0
        equalsSeen = false
        mode = when (character) {
            ProtocolFrame.TIMESTAMP -> Mode.Timestamp
            ProtocolFrame.STATUS -> Mode.Status
            ProtocolFrame.ERROR -> Mode.Error
            ProtocolFrame.INFO -> Mode.Info
            'P', 'p', 'Q', 'q' -> Mode.Binary
            else -> Mode.Numeric
        }
    }

    /** Rule 3: ends the message with `Unknown`. A `;` is consumed. Each other character goes to the start rule. */
    private fun invalid(character: Char, out: MutableList<AdapterMessage>) {
        out += AdapterMessage.Unknown(text.toString())
        mode = Mode.Out
        if (character != ProtocolFrame.END) outside(character)
    }

    private fun complete(message: AdapterMessage, out: MutableList<AdapterMessage>) {
        out += message
        mode = Mode.Out
    }

    private fun numeric(character: Char, out: MutableList<AdapterMessage>) {
        when {
            character == '-' && digitCount == 0 && !negative -> {
                negative = true
                text.append(character)
            }
            character in '0'..'9' -> {
                val next = value * 10 + (character - '0')
                if (next > if (negative) NEGATIVE_LIMIT else POSITIVE_LIMIT) {
                    invalid(character, out)
                } else {
                    value = next
                    digitCount++
                    text.append(character)
                }
            }
            character == ProtocolFrame.END && digitCount > 0 -> {
                val count = (if (negative) -value else value).toInt()
                complete(AdapterMessage.AxisCount(prefix, count, timeBase.readoutTime(startMs)), out)
            }
            else -> invalid(character, out)
        }
    }

    private fun binary(character: Char, out: MutableList<AdapterMessage>) {
        when {
            digitCount == 0 && (character == '0' || character == '1') -> {
                value = (character - '0').toLong()
                digitCount = 1
                text.append(character)
            }
            digitCount == 1 && character == ProtocolFrame.END ->
                complete(AdapterMessage.BinaryInput(prefix, value.toInt(), timeBase.readoutTime(startMs)), out)
            else -> invalid(character, out)
        }
    }

    private fun timestamp(character: Char, out: MutableList<AdapterMessage>) {
        val digit = hexValue(character)
        when {
            digit >= 0 -> {
                val next = value * 16 + digit
                if (next > TimeBase.MAX_ADAPTER_TIME) {
                    invalid(character, out)
                } else {
                    value = next
                    digitCount++
                    text.append(character)
                }
            }
            character == ProtocolFrame.END && digitCount > 0 -> {
                val adapterNow = timeBase.onTimestamp(value, startMs)
                complete(AdapterMessage.Timestamp(value, adapterNow), out)
            }
            else -> invalid(character, out)
        }
    }

    private fun status(character: Char, out: MutableList<AdapterMessage>) {
        val digit = hexValue(character)
        when {
            digit >= 0 && digitCount < SUBSYSTEM_DIGITS -> {
                subsystem = subsystem * 16 + digit
                digitCount++
                text.append(character)
            }
            digit >= 0 && digitCount < SUBSYSTEM_DIGITS + MAX_CODE_DIGITS -> {
                value = value * 16 + digit
                digitCount++
                text.append(character)
            }
            character == ProtocolFrame.END && digitCount > SUBSYSTEM_DIGITS -> {
                val code = value.toInt()
                val message = if (mode == Mode.Status) {
                    AdapterMessage.Status(subsystem, code)
                } else {
                    AdapterMessage.Error(subsystem, code)
                }
                complete(message, out)
            }
            else -> invalid(character, out)
        }
    }

    private fun info(character: Char, out: MutableList<AdapterMessage>) {
        if (digitCount < FIELD_DIGITS) {
            val digit = hexValue(character)
            if (digit < 0) {
                invalid(character, out)
                return
            }
            subsystem = subsystem * 16 + digit
            digitCount++
            text.append(character)
            if (digitCount == FIELD_DIGITS && text[1] == '6' && text[2] == '4') mode = Mode.Signature
            return
        }
        when {
            character == ProtocolFrame.END -> complete(AdapterMessage.Info(subsystem, text.substring(HEADER_LENGTH)), out)
            text.length - HEADER_LENGTH < MAX_INFO_TEXT -> text.append(character)
            else -> invalid(character, out)
        }
    }

    private fun signature(character: Char, out: MutableList<AdapterMessage>) {
        when {
            character == ProtocolFrame.END -> {
                if (signatureLength in 1..MAX_SIGNATURE_TEXT && signatureLength % 4 == 0) {
                    complete(AdapterMessage.Signature(text.substring(HEADER_LENGTH)), out)
                } else {
                    invalid(character, out)
                }
            }
            character == '=' -> if (signatureLength == 0) invalid(character, out) else keepSignature(character, equals = true)
            isBase64(character) -> if (equalsSeen) invalid(character, out) else keepSignature(character, equals = false)
            else -> invalid(character, out)
        }
    }

    private fun keepSignature(character: Char, equals: Boolean) {
        if (equals) equalsSeen = true
        if (signatureLength <= MAX_SIGNATURE_TEXT) signatureLength++
        if (signatureLength <= MAX_SIGNATURE_TEXT) text.append(character)
    }

    private fun isBase64(character: Char): Boolean =
        character in 'A'..'Z' || character in 'a'..'z' || character in '0'..'9' || character == '+' || character == '/'

    private fun hexValue(character: Char): Int = when (character) {
        in '0'..'9' -> character - '0'
        in 'a'..'f' -> character - 'a' + 10
        in 'A'..'F' -> character - 'A' + 10
        else -> -1
    }

    companion object {
        /** The most text characters of an info answer. */
        const val MAX_INFO_TEXT: Int = 256

        /** The most characters of the Base64 part of a signature. */
        const val MAX_SIGNATURE_TEXT: Int = 1024

        private const val FIRST_PRINTABLE = 0x20
        private const val LAST_PRINTABLE = 0x7E
        private const val POSITIVE_LIMIT = 2_147_483_647L
        private const val NEGATIVE_LIMIT = 2_147_483_648L
        private const val SUBSYSTEM_DIGITS = 2
        private const val MAX_CODE_DIGITS = 4
        private const val FIELD_DIGITS = 2
        private const val HEADER_LENGTH = 3
    }
}
