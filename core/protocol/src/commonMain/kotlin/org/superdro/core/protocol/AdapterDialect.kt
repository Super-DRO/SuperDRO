package org.superdro.core.protocol

/**
 * The times of the adapter protocol. All values are
 * milliseconds.
 */
data class AdapterTiming(
    /** No valid message for this time: `Connected` to `NoData`. */
    val noDataMs: Long = 7_000L,
    /** The `AwaitingData` limit and the `NoData` limit (USB). */
    val stateLimitMs: Long = 30_000L,
    /** The queue becomes active this time after the first `Connected` of a link. */
    val queueStartMs: Long = 2_000L,
    /** The wait from a queue write to the first answer. */
    val firstWaitMs: Long = 500L,
    /** The full wait after the ack. `#ref_axis` and `#get_sig` have their own full waits. */
    val fullWaitMs: Long = 2_000L,
    /** The full wait of `#ref_axis:<ch>,<n>;` is `n` × this value. */
    val referenceWaitStepMs: Long = 100L,
    /** The full wait of `#get_sig`. */
    val signatureWaitMs: Long = 10_000L,
    /** A field of the "id" group with no value this time after the queue start gets one more query. */
    val idRetryMs: Long = 5_000L,
    /** A mode write, a clear, and a reference command fail when no result comes in this time. */
    val resultLimitMs: Long = 10_000L,
    /** A value that is "Fetching" for less than this time gets no second query. */
    val readAgainLimitMs: Long = 5_000L,
    /** The `NO_DATA` recovery delay on an SPP or TCP link. */
    val noDataRecoveryMs: Long = 750L,
    /** The timeout of one TCP connect. */
    val tcpConnectMs: Long = 5_000L,
    /** A Bluetooth write that does not end in this time is `Lost`. */
    val bluetoothWriteWatchdogMs: Long = 4_000L,
    /** The time between two queue writes. */
    val queueGapMs: Long = 50L,
    /** The BEL byte goes out after this time with no other write. */
    val belPeriodMs: Long = 500L,
    /** The base of the Busy resend: the delay is 2^count × this value (100 ms, then 200 ms). */
    val busyDelayBaseMs: Long = 50L,
    /** The most transmissions of one request. */
    val maxTransmissions: Int = 3,
    /** After the ack of `#reboot`, the session closes the link after this time. */
    val restartCloseMs: Long = 250L,
    /** After the ack of `#reboot`, the session opens the link again after this time. */
    val restartConnectMs: Long = 2_000L,
)

/**
 * The adapter types and their differences.
 *
 * @property allMessageForms True when the adapter sends all forms of [AdapterMessage]. False when it
 *   sends only numeric readouts.
 * @property answersCommands True when the adapter answers the commands of [AdapterCommand].
 */
enum class AdapterDialect(val allMessageForms: Boolean, val answersCommands: Boolean) {
    /** The ESP32 two-way adapter. Its model text starts with `tda-` (inferred for the genuine adapters). */
    Esp32TwoWay(allMessageForms = true, answersCommands = true),

    /** The same dialect on a Wi-Fi TCP link. */
    Esp32TwoWayTcp(allMessageForms = true, answersCommands = true),

    /**
     * A one-way adapter: numeric readouts only, for example `X123;Y-456;Z789;T1500;`. The start
     * queries time out. Each "id" field gets one more query after 5000 ms. The user can turn
     * off two-way communication and declares the inputs.
     */
    LegacyOneWay(allMessageForms = false, answersCommands = false),
    ;

    /** The times of this dialect. The three dialects use the same times. */
    val timing: AdapterTiming get() = DEFAULT_TIMING

    companion object {
        /** The default times. */
        val DEFAULT_TIMING: AdapterTiming = AdapterTiming()

        /** True when [model] is the model of a genuine adapter: it starts with `tda-` (any case). */
        fun isGenuineModel(model: String?): Boolean = ChannelRules.isReferenceModel(model)
    }
}
