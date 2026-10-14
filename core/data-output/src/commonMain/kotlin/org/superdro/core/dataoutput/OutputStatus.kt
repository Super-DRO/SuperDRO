package org.superdro.core.dataoutput

import org.superdro.core.model.DataOutputCadence

/** The type of an error of the data output. */
enum class DataOutputErrorType {
    /** The TCP server cannot listen on an address and the port. */
    PortBindFailed,

    /** The TCP server stopped at an `accept()` error. A new server starts after 5 s. */
    AcceptFailed,

    /** The selected interface has no address. */
    NoAddress,

    /** A different output of this app uses the port. */
    PortTaken,

    /** A client did not complete the TLS handshake, for example with a wrong key. */
    HandshakeFailed,

    /** A write to a client or a publish to the broker failed. */
    PublishFailed,

    /** The MQTT client cannot reach the broker. */
    BrokerUnreachable,

    /** The broker refused the user name or the password. */
    AuthRejected,

    /** The TLS handshake with the broker failed. */
    TlsFailure,

    /** The link to the broker stopped. */
    ConnectionLost,
}

/**
 * One error of the data output. The UI makes the message from the type and the values, with the
 * strings of `strings_data_output.xml` (core:resources).
 *
 * @property atMs The monotonic time of the error.
 * @property host The address of the error: a bind address, a client address, or the broker host.
 * @property port The port of the error, or null.
 * @property detail The message of the cause, for example the text of an exception, or null.
 */
data class DataOutputError(
    val type: DataOutputErrorType,
    val atMs: Long,
    val host: String? = null,
    val port: Int? = null,
    val detail: String? = null,
) {
    /** An English text for the log. The UI does not show this text. */
    fun logText(): String = buildString {
        append(type.name)
        if (host != null) append(' ').append(hostAndPort(host, port))
        if (detail != null) append(": ").append(detail)
    }
}

/** Gives `host:port`, with brackets for an IPv6 address: `[fe80::1]:5800`. */
fun hostAndPort(host: String, port: Int?): String {
    val shown = if (':' in host && !host.startsWith("[")) "[$host]" else host
    return if (port == null) shown else "$shown:$port"
}

/** The state of the sender: the TCP server or the MQTT client. */
sealed interface SenderState {

    /** The sender does not run. No socket is open. */
    data object Stopped : SenderState

    /** The server binds its sockets, or the client connects to the broker. */
    data object Starting : SenderState

    /** The TCP server listens and has no client. */
    data object Ready : SenderState

    /** The TCP server has one or more clients, or the MQTT client is connected. */
    data object Active : SenderState

    /**
     * The sender stopped at [error]. A new server (or a new broker link) starts at [retryAtMs],
     * 5 s after the error.
     */
    data class Failed(val error: DataOutputError, val retryAtMs: Long) : SenderState
}

/** The status of the output and of its toolbar icon. */
enum class DataOutputStatus {
    /** The output does not run. */
    Stopped,

    /** The sender starts. */
    Starting,

    /** The server listens and has no client. */
    Ready,

    /** The output sends records. */
    Sending,

    /** The sender failed, or a rule of [WarningRules] is true. */
    Warning,
}

/**
 * The statistics of one start of the output. Each write to
 * each client counts. The object is immutable: each change gives a new object.
 *
 * @property startedAtMs The monotonic time of the start.
 * @property sent The number of successful writes.
 * @property failed The number of failed writes.
 * @property lastSuccessAtMs The time of the last successful write, or null.
 * @property lastError The last error, or null.
 * @property recentFailures The results of the last [RECENT_COUNT] writes: true for a failure.
 * @property records The number of records that the output made, also with no client.
 */
data class OutputStatistics(
    val startedAtMs: Long,
    val sent: Long = 0L,
    val failed: Long = 0L,
    val lastSuccessAtMs: Long? = null,
    val lastError: DataOutputError? = null,
    val recentFailures: List<Boolean> = emptyList(),
    val records: Long = 0L,
) {
    /** The percentage of failures in the recent results, rounded down. 0 with no result. */
    val recentFailurePercent: Int
        get() = if (recentFailures.isEmpty()) 0 else recentFailures.count { failure -> failure } * 100 / recentFailures.size

    /** Gives the statistics after one write at [nowMs]. */
    fun withWrite(success: Boolean, nowMs: Long): OutputStatistics = copy(
        sent = if (success) sent + 1 else sent,
        failed = if (success) failed else failed + 1,
        lastSuccessAtMs = if (success) nowMs else lastSuccessAtMs,
        recentFailures = recentWith(!success),
    )

    /** Gives the last [RECENT_COUNT] results after [failure], in one new list. */
    private fun recentWith(failure: Boolean): List<Boolean> {
        val first = (recentFailures.size + 1 - RECENT_COUNT).coerceAtLeast(0)
        val result = ArrayList<Boolean>(recentFailures.size + 1 - first)
        for (index in first until recentFailures.size) result.add(recentFailures[index])
        result.add(failure)
        return result
    }

    /** Gives the statistics with the error [error]. */
    fun withError(error: DataOutputError): OutputStatistics = copy(lastError = error)

    /** Gives the statistics after one more record. */
    fun withRecord(): OutputStatistics = copy(records = records + 1)

    companion object {
        /** The number of write results in the ring. */
        const val RECENT_COUNT: Int = 100
    }
}

/**
 * The rules of the `WARNING` state while the sender is active.
 * The output calculates the status at each state change and each [STATUS_PERIOD_MS].
 */
object WarningRules {

    /** The age of an error that still gives a warning, in ms. */
    const val ERROR_AGE_MS: Long = 30_000L

    /** The period of the status calculation, in ms. */
    const val STATUS_PERIOD_MS: Long = 1000L

    /** The factor of the interval in rule 3. */
    const val SILENCE_FACTOR: Long = 5L

    /**
     * Gives true when one rule is true:
     * 1. The last error is less than 30000 ms old.
     * 2. More than 10 % of the last 100 writes failed.
     * 3. The cadence is not [DataOutputCadence.OnChange], and the time since the last successful
     *    write is more than 5 × [intervalMs]. Before the first successful write, the start time
     *    applies.
     */
    fun warning(statistics: OutputStatistics, cadence: DataOutputCadence, intervalMs: Long, nowMs: Long): Boolean {
        val error = statistics.lastError
        if (error != null && nowMs - error.atMs < ERROR_AGE_MS) return true
        val recent = statistics.recentFailures
        if (recent.isNotEmpty() && recent.count { failure -> failure } * 10 > recent.size) return true
        if (cadence != DataOutputCadence.OnChange) {
            val since = statistics.lastSuccessAtMs ?: statistics.startedAtMs
            if (nowMs - since > SILENCE_FACTOR * intervalMs) return true
        }
        return false
    }

    /** Gives the status of [state]. */
    fun status(
        state: SenderState,
        statistics: OutputStatistics,
        cadence: DataOutputCadence,
        intervalMs: Long,
        nowMs: Long,
    ): DataOutputStatus = when (state) {
        SenderState.Stopped -> DataOutputStatus.Stopped
        SenderState.Starting -> DataOutputStatus.Starting
        SenderState.Ready -> DataOutputStatus.Ready
        is SenderState.Failed -> DataOutputStatus.Warning
        SenderState.Active ->
            if (warning(statistics, cadence, intervalMs, nowMs)) DataOutputStatus.Warning else DataOutputStatus.Sending
    }
}
