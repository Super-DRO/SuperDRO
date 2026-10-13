package org.superdro.core.ui.dataoutput

import androidx.compose.runtime.Immutable
import org.superdro.core.dataoutput.DataOutputStatus
import org.superdro.core.model.BindInterface
import org.superdro.core.model.DataOutputActivation
import org.superdro.core.model.DataOutputConfig
import org.superdro.core.model.DataOutputSecurity
import org.superdro.core.model.DataOutputTransport
import org.superdro.core.ui.text.UiText

/** One machine profile of the machine list of the data output screen. */
@Immutable
data class ProfileChoice(val id: String, val name: String)

/** One choice of the network interface list with the addresses that it has now. */
@Immutable
data class InterfaceChoice(val bind: BindInterface, val addresses: List<String>)

/**
 * The status of the output: the screen shows the address, the port, the security mode, and the
 * number of clients at all times.
 *
 * @property addresses The bound addresses as `host:port` while the output runs, else the
 *   addresses that the selected interface gives now.
 * @property blockText The reason why the output does not run, or null while it runs.
 * @property canStartStop True when the start and stop button shows: the output is on and the
 *   activation is "On demand".
 */
@Immutable
data class OutputStatusUiState(
    val status: DataOutputStatus = DataOutputStatus.Stopped,
    val running: Boolean = false,
    val enabled: Boolean = false,
    val blockText: UiText? = null,
    val transport: DataOutputTransport = DataOutputTransport.Tcp,
    val addresses: List<String> = emptyList(),
    val port: Int = DataOutputConfig.DEFAULT_PORT,
    val security: DataOutputSecurity = DataOutputSecurity.Psk,
    val brokerTls: Boolean = true,
    val clients: Int = 0,
    val records: Long? = null,
    val sent: Long? = null,
    val failed: Long? = null,
    val lastError: UiText? = null,
    val activation: DataOutputActivation = DataOutputActivation.OnDemand,
    val canStartStop: Boolean = false,
)

/**
 * The lines of the diagnostics. They show in the status section of the data output screen, not
 * in a dialog (the dialog rule).
 *
 * @property listening The bound addresses, or null for MQTT or an output that does not run.
 * @property clients The number of TCP clients for the line of the addresses.
 * @property broker The broker `host:port` of MQTT, or null.
 * @property problem The last error and its age, or null.
 * @property delivery The age of the last successful write, or "no record delivered".
 * @property counts The counts and the failure percentage, or null when no write failed.
 * @property warning True when the status is the warning: the title is "Data output problem".
 */
@Immutable
data class DiagnosticsUiState(
    val running: Boolean,
    val listening: String?,
    val clients: Int,
    val broker: String?,
    val problem: UiText?,
    val delivery: UiText,
    val counts: UiText?,
    val warning: Boolean,
)

/** The result of "Test connection". */
@Immutable
data class TestResultUiState(val text: UiText, val passed: Boolean)

/** A message for the snackbar. [id] keeps two equal texts apart. */
@Immutable
data class OutputMessage(val id: Long, val text: UiText)

/**
 * The UI state of the data output screen. The screen edits a
 * draft of the configuration. "Save" writes it. The switch writes at once: the switch off closes
 * the server and the clients at once.
 *
 * @property draft The configuration that the screen shows.
 * @property stored The stored configuration of the profile.
 * @property portText The text of the port field. The other texts are the same for their fields.
 * @property problems The problems of the draft. "Save" and the switch on need an empty list.
 * @property labelRefusals The last refused edit of each label, until the next edit of the label.
 * @property psk The share text `<identity>:<64 hex>` of the PSK, or null.
 */
@Immutable
data class DataOutputUiState(
    val loading: Boolean = true,
    val profiles: List<ProfileChoice> = emptyList(),
    val profileId: String? = null,
    val draft: DataOutputConfig = DataOutputConfig(),
    val stored: DataOutputConfig = DataOutputConfig(),
    val portText: String = DataOutputConfig.DEFAULT_PORT.toString(),
    val intervalText: String = DataOutputConfig.DEFAULT_INTERVAL_MS.toString(),
    val maxRateText: String = DataOutputConfig.DEFAULT_MAX_RATE_HZ.toString(),
    val brokerPortText: String = DataOutputConfig.DEFAULT_MQTT_TLS_PORT.toString(),
    val mqttUsername: String = "",
    val mqttPassword: String = "",
    val credentialsChanged: Boolean = false,
    val problems: List<FormProblem> = emptyList(),
    val labelRefusals: Map<org.superdro.core.model.DataOutputFieldId, UiText> = emptyMap(),
    val interfaces: List<InterfaceChoice> = BindInterface.entries.map { bind -> InterfaceChoice(bind, emptyList()) },
    val status: OutputStatusUiState = OutputStatusUiState(),
    val psk: String? = null,
    val dialog: DataOutputDialog? = null,
    val diagnostics: DiagnosticsUiState? = null,
    val testing: Boolean = false,
    val testResult: TestResultUiState? = null,
    val message: OutputMessage? = null,
    val saving: Boolean = false,
) {
    /** True when the draft or the credentials differ from the stored values. */
    val dirty: Boolean get() = draft != stored || credentialsChanged || textsDiffer

    /** True when "Save" can write the draft. */
    val canSave: Boolean get() = profileId != null && dirty && problems.isEmpty() && !saving

    /** The problems of [target]. */
    fun problemsOf(target: ProblemTarget): List<UiText> = problems.filter { problem -> problem.target == target }.map { problem -> problem.text }

    private val textsDiffer: Boolean
        get() = portText != draft.port.toString() || intervalText != draft.intervalMs.toString() ||
            maxRateText != draft.maxRateHz.toString() || brokerPortText != draft.mqttBrokerPort.toString()
}
