package org.superdro.core.protocol

/**
 * The messages from the adapter. [AdapterParser] makes them.
 *
 * A "valid message" for the session states and the no-data watchdog is an
 * [AxisCount], [BinaryInput], [Status], [Error], [Info], or [Signature]. A [Timestamp] and an
 * [Unknown] are not valid messages.
 */
sealed interface AdapterMessage {

    /** True when the message feeds the no-data watchdog and changes `AwaitingData` or `NoData` to `Connected`. */
    val isValid: Boolean

    /**
     * A numeric readout: `<prefix>[-]<digits>;`.
     *
     * @property channel The upper case prefix letter `A`–`F`, `R`–`Z`, or `.` (the dirty status).
     *   The letter stays also for `A` to `F`, `R`, `S`, and `.`. Only the letters X, Y, Z, W, U, V,
     *   and T carry values ([carriesValue]).
     * @property count The signed 32-bit value.
     * @property timeMs The time of the readout.
     */
    data class AxisCount(val channel: Char, val count: Int, val timeMs: Long) : AdapterMessage {
        override val isValid: Boolean get() = true

        /** True when the session gives the value to an axis: X, Y, Z, W, U, V, or T. */
        val carriesValue: Boolean get() = AdapterLetters.carriesValue(channel)

        /** True for the `.` readout: the session parses and logs it only. */
        val isDirtyStatus: Boolean get() = channel == AdapterLetters.DIRTY_STATUS

        /** The lower case letter, as `org.superdro.core.model.Channels` stores it. */
        val letter: Char get() = channel.lowercaseChar()
    }

    /**
     * A boolean readout of a probe port: `P0;`, `P1;`, `Q0;`, or `Q1;`.
     *
     * @property channel `P` or `Q` (upper case).
     * @property value 0 or 1.
     * @property timeMs The time of the readout.
     */
    data class BinaryInput(val channel: Char, val value: Int, val timeMs: Long) : AdapterMessage {
        override val isValid: Boolean get() = true

        /** The lower case letter, as `org.superdro.core.model.Channels` stores it. */
        val letter: Char get() = channel.lowercaseChar()
    }

    /**
     * A time stamp `@<hex>;`.
     *
     * @property adapterTime The hex value `k`, 0x3E7FFFFFC18 or less.
     * @property timeMs The adapter time on the session clock: `tAdapter = k + offset`.
     */
    data class Timestamp(val adapterTime: Long, val timeMs: Long) : AdapterMessage {
        override val isValid: Boolean get() = false
    }

    /** A status `$<sub2><code1..4>;`. The subsystem is 0x00 to 0xFF, and the code is 0x0 to 0xFFFF. */
    data class Status(val subsystem: Int, val code: Int) : AdapterMessage {
        override val isValid: Boolean get() = true
    }

    /** An error `!<sub2><code1..4>;`. It always uses the error path of the queue. */
    data class Error(val subsystem: Int, val code: Int) : AdapterMessage {
        override val isValid: Boolean get() = true
    }

    /**
     * An information answer `?<field2><text>;` with a field that is not `64`.
     *
     * @property fieldId The field ID, 0x00 to 0xFF.
     * @property text 0 to 256 characters.
     */
    data class Info(val fieldId: Int, val text: String) : AdapterMessage {
        override val isValid: Boolean get() = true
    }

    /** A signature answer `?64<base64>;`. SuperDRO ignores it. */
    data class Signature(val base64: String) : AdapterMessage {
        override val isValid: Boolean get() = true
    }

    /**
     * A message that is not valid or not known. [text] is the text up to the character that
     * ended the message. It does not include that character.
     */
    data class Unknown(val text: String) : AdapterMessage {
        override val isValid: Boolean get() = false
    }
}

/** The rules of the readout letters. */
object AdapterLetters {

    /** The prefix of the dirty status readout. */
    const val DIRTY_STATUS: Char = '.'

    private const val VALUE_LETTERS = "XYZWUVT"
    private const val PROBE_LETTERS = "PQ"
    private const val IGNORED_LETTERS = "ABCDEFRS"

    /** True when the letter carries a value: X, Y, Z, W, U, V, and T (any case). */
    fun carriesValue(letter: Char): Boolean = letter.uppercaseChar() in VALUE_LETTERS

    /** True when the letter is a probe port: P and Q (any case). */
    fun isProbePort(letter: Char): Boolean = letter.uppercaseChar() in PROBE_LETTERS

    /** True when the parser accepts the letter and the session ignores the value: A to F, R, and S (any case). */
    fun isIgnored(letter: Char): Boolean = letter.uppercaseChar() in IGNORED_LETTERS
}
