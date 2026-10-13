package org.superdro.core.dataoutput

import org.superdro.core.model.BindInterface
import org.superdro.core.model.DataOutputSecurity
import org.superdro.core.model.DataOutputTransport

/**
 * The state of the output of one machine profile, for the output screen and the toolbar icon.
 * The screen shows the address, the port, the security mode, and the number of
 * clients at all times.
 *
 * @property block The reason why the output does not run, or null while it runs.
 * @property listening The bound addresses as `host:port`.
 * @property clients The addresses of the TCP clients.
 * @property statistics The statistics of the current or the last start, or null before the first start.
 * @property bindWarning True when the screen must show the warning of "All interfaces".
 */
data class DataOutputState(
    val transport: DataOutputTransport,
    val security: DataOutputSecurity,
    val bindInterface: BindInterface,
    val port: Int,
    val brokerHost: String,
    val brokerPort: Int,
    val brokerTls: Boolean,
    val running: Boolean = false,
    val block: StartBlock? = StartBlock.NotEnabled,
    val sender: SenderState = SenderState.Stopped,
    val status: DataOutputStatus = DataOutputStatus.Stopped,
    val listening: List<String> = emptyList(),
    val clients: List<String> = emptyList(),
    val statistics: OutputStatistics? = null,
    val bindWarning: Boolean = false,
)

/**
 * The content of the diagnostics dialog. The UI makes
 * the lines with the strings of `strings_data_output.xml`:
 *
 * 1. TCP: the bound addresses and the number of clients. MQTT: the broker. Else: the output does not run.
 * 2. The last error and its age, when an error exists.
 * 3. The age of the last successful write, or "no record delivered since the start".
 * 4. The counts and the failure percentage, when one or more writes failed.
 *
 * The dialog buttons are "Keep trying" (close the dialog) and "Stop the output".
 */
data class Diagnostics(
    val running: Boolean,
    val transport: DataOutputTransport,
    val security: DataOutputSecurity,
    val listening: List<String>,
    val clientCount: Int,
    val broker: String?,
    val problem: DataOutputError?,
    val problemAgeMs: Long?,
    val lastSuccessAgeMs: Long?,
    val sent: Long,
    val failed: Long,
    val recentFailurePercent: Int,
) {
    /** True when the dialog shows line 4. */
    val showCounts: Boolean get() = failed > 0

    companion object {
        /** Gives the diagnostics of [state] at the monotonic time [nowMs]. */
        fun of(state: DataOutputState, nowMs: Long): Diagnostics {
            val statistics = state.statistics
            val error = (state.sender as? SenderState.Failed)?.error ?: statistics?.lastError
            return Diagnostics(
                running = state.running,
                transport = state.transport,
                security = state.security,
                listening = state.listening,
                clientCount = state.clients.size,
                broker = if (state.transport == DataOutputTransport.Mqtt) hostAndPort(state.brokerHost, state.brokerPort) else null,
                problem = error,
                problemAgeMs = error?.let { problem -> nowMs - problem.atMs },
                lastSuccessAgeMs = statistics?.lastSuccessAtMs?.let { time -> nowMs - time },
                sent = statistics?.sent ?: 0L,
                failed = statistics?.failed ?: 0L,
                recentFailurePercent = statistics?.recentFailurePercent ?: 0,
            )
        }
    }
}

/** The result of "Test connection". */
sealed interface TestConnectionResult {

    /** The port is free on each selected address. Clients can connect to [addresses] (`host:port`). */
    data class PortFree(val port: Int, val addresses: List<String>) : TestConnectionResult

    /** The port is not free on [host]. [detail] is the cause. */
    data class PortNotFree(val port: Int, val host: String, val detail: String?) : TestConnectionResult

    /** This output runs on the port now, with [clients] clients. */
    data class Running(val port: Int, val addresses: List<String>, val clients: Int) : TestConnectionResult

    /** A different output of this app uses the port. */
    data class PortTaken(val port: Int, val owner: String) : TestConnectionResult

    /** The selected interface has no address. */
    data class NoAddress(val bind: BindInterface) : TestConnectionResult

    /** The broker accepted the connection and the credentials. */
    data class BrokerReachable(val host: String, val port: Int, val tls: Boolean) : TestConnectionResult

    /** The broker link failed with [error]. */
    data class BrokerFailed(val error: DataOutputError) : TestConnectionResult

    /** The configuration has problems. */
    data class InvalidConfig(val problems: List<ConfigProblem>) : TestConnectionResult
}
