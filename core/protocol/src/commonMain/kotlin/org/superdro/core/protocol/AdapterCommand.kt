package org.superdro.core.protocol

import org.superdro.core.util.Bytes

/** The request type of a command. */
enum class RequestKind {
    /** A query: an info answer `?` completes it. */
    Query,

    /** A command: its status function completes it. An info answer does not complete it. */
    Command,

    /** The BEL byte. It does not go through the queue. */
    Raw,
}

/**
 * The commands of the app to the adapter.
 *
 * The text is US-ASCII, `#<name>:<arg>[,<arg>];`, with no CR and no LF. The session writes each
 * command in one write. The channel argument is the lower case letter. Numbers are decimal ASCII
 * digits. The constants `15`, `1000`, and `,1` go out exactly.
 */
sealed interface AdapterCommand {

    /** The command text. */
    val text: String

    /** The request type. */
    val kind: RequestKind

    /** The channel of the command, or null. The channel is busy while the command runs. */
    val channel: InputChannel? get() = null

    /** The wait from the write to the first answer. */
    val firstWaitMs: Long get() = AdapterDialect.DEFAULT_TIMING.firstWaitMs

    /** The wait after the ack `$0aa3;`. */
    val fullWaitMs: Long get() = AdapterDialect.DEFAULT_TIMING.fullWaitMs

    /** True when an RX Busy (`$0a20;` or `!0a20;`) sends the same bytes again. */
    val resendOnBusy: Boolean get() = true

    /** Gives the exact bytes of the command. */
    fun encode(): ByteArray = Bytes.ascii(text)

    /**
     * `"#get:<field>;"`, with the `"` byte (0x22) before `#` and after `;`. Example: `"#get:mac;"`
     * is the 11 bytes `22 23 67 65 74 3A 6D 61 63 3B 22`. The answer is `?<id><value>;`.
     */
    data class GetField(val field: AdapterField) : AdapterCommand {
        init {
            require(field.gettable) { "SuperDRO does not send #get:${field.name}." }
        }

        override val text: String get() = "\"#get:${this.field.getName};\""
        override val kind: RequestKind get() = RequestKind.Query
    }

    /** `#get_mode:<ch>;`. The answer is `?01<ch><mask4><mode4>;`. */
    data class GetMode(override val channel: InputChannel) : AdapterCommand {
        override val text: String get() = "#get_mode:${channel.letter};"
        override val kind: RequestKind get() = RequestKind.Query
    }

    /** `#set_mode:<ch>,<id>;` with the decimal mode ID. The answer `$060;` completes it. */
    data class SetMode(override val channel: InputChannel, val mode: InputMode) : AdapterCommand {
        init {
            require(mode != InputMode.Unknown) { "The mode Unknown is not on the wire." }
        }

        override val text: String get() = "#set_mode:${channel.letter},${mode.id};"
        override val kind: RequestKind get() = RequestKind.Command
    }

    /**
     * `#ref_axis:<ch>,<n>;` for the channels `x y z w`. SuperDRO sends the constant 15. The full
     * wait is `n` × 100 ms, thus 1500 ms. The answer `$<ref>a0;` or `$<ref>aa;` completes it.
     */
    data class RefAxis(override val channel: InputChannel, val argument: Int = REFERENCE_ARGUMENT) : AdapterCommand {
        init {
            require(channel.referenceAllowed) { "The channel ${channel.letter} has no reference search." }
        }

        override val text: String get() = "#ref_axis:${channel.letter},$argument;"
        override val kind: RequestKind get() = RequestKind.Command
        override val fullWaitMs: Long get() = argument * AdapterDialect.DEFAULT_TIMING.referenceWaitStepMs

        companion object {
            /** The constant argument of `#ref_axis`. */
            const val REFERENCE_ARGUMENT: Int = 15
        }
    }

    /** `#clear_counter:<ch>;`. The ack `$0aa3;` completes it. */
    data class ClearCounter(override val channel: InputChannel) : AdapterCommand {
        override val text: String get() = "#clear_counter:${channel.letter};"
        override val kind: RequestKind get() = RequestKind.Command
    }

    /** `#reboot:1000;`. The ack `$0aa3;` completes it. Then the restart sequence starts. */
    data object Reboot : AdapterCommand {
        override val text: String get() = "#reboot:1000;"
        override val kind: RequestKind get() = RequestKind.Command
    }

    /**
     * `#get_sig:<seconds>,1;`. The main app does not send it. The adapter probe app sends it. The
     * full wait is 10000 ms. An RX Busy does not send it again.
     *
     * @property seconds The seconds since 2012-01-12 12:00:00 UTC. [secondsAt] gives the value.
     */
    data class GetSig(val seconds: Long) : AdapterCommand {
        override val text: String get() = "#get_sig:$seconds,1;"
        override val kind: RequestKind get() = RequestKind.Query
        override val fullWaitMs: Long get() = AdapterDialect.DEFAULT_TIMING.signatureWaitMs
        override val resendOnBusy: Boolean get() = false

        companion object {
            /** 2012-01-12 12:00:00 UTC in milliseconds since 1970. */
            const val EPOCH_MS: Long = 1_326_369_600_000L

            /** Gives `(nowMs − 1326369600000) / 1000`. [epochNowMs] is a wall clock time in ms since 1970. */
            fun secondsAt(epochNowMs: Long): Long = (epochNowMs - EPOCH_MS) / 1000L

            /** Gives the command for the wall clock time [epochNowMs]. */
            fun at(epochNowMs: Long): GetSig = GetSig(secondsAt(epochNowMs))
        }
    }

    /**
     * The BEL byte `07`. The session sends it after 500 ms with no other write, in `Connected`
     * only, and never with two-way communication off. It does not go through the
     * queue. The adapter sends no answer.
     */
    data object Bel : AdapterCommand {
        override val text: String get() = "\u0007"
        override val kind: RequestKind get() = RequestKind.Raw
        override val firstWaitMs: Long get() = 0L
        override val fullWaitMs: Long get() = 0L
        override val resendOnBusy: Boolean get() = false
        override fun encode(): ByteArray = byteArrayOf(Bytes.BEL)
    }
}
