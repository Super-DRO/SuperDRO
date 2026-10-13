package org.superdro.core.dataoutput

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.superdro.core.connection.ConnectionRequest
import org.superdro.core.connection.NetworkInterfaceInfo
import org.superdro.core.connection.PermissionKind
import org.superdro.core.connection.selectedBy
import org.superdro.core.model.BindInterface
import org.superdro.core.model.DataOutputActivation
import org.superdro.core.model.DataOutputConfig
import org.superdro.core.model.DataOutputSecurity
import org.superdro.core.model.DataOutputTransport

/** The bind addresses of the TCP server. */
object BindAddresses {

    /**
     * Gives the addresses of the interfaces that [bind] selects ([selectedBy]). The default
     * [BindInterface.WiFi] gives the addresses of the Wi-Fi interface. [BindInterface.All] gives
     * the addresses of each interface with a kind, so never the cellular network.
     */
    fun hosts(interfaces: List<NetworkInterfaceInfo>, bind: BindInterface): List<String> =
        interfaces.selectedBy(bind).flatMap { info -> info.addresses }.distinct()

    /**
     * Gives true when the settings screen must show the warning text of "All interfaces"
     * (CAUTION: do not bind the server to all interfaces without a reason).
     */
    fun needsWarning(bind: BindInterface): Boolean = bind == BindInterface.All
}

/** The reason why an output does not run. */
sealed interface StartBlock {

    /** The user did not turn on the output. */
    data object NotEnabled : StartBlock

    /** The machine of the output is offline. */
    data object MachineOffline : StartBlock

    /** The activation is "On demand" and the user did not tap the status chip. */
    data object NotRequested : StartBlock

    /** The plain text mode needs the confirmation of the warning "Use only on trusted networks". */
    data object PlainTextNotConfirmed : StartBlock

    /** A broker link with no TLS needs the confirmation of the same warning. */
    data object MqttWithoutTlsNotConfirmed : StartBlock

    /** The configuration has problems. */
    data class InvalidConfig(val problems: List<ConfigProblem>) : StartBlock

    /** A different output of this app uses the port. */
    data class PortTaken(val port: Int, val owner: String) : StartBlock
}

/** The rules that decide if an output runs. */
object OutputRunRule {

    /**
     * Gives the reason why the output of [config] does not run, or null when it runs.
     *
     * - The output runs only when the user turned it on, and its machine is online.
     * - "Always on" runs while the machine is online. "On demand" (the default) runs after a tap
     *   on the status chip ([requested]).
     * - The plain text mode and a broker link with no TLS run only after the confirmation of the
     *   warning "Use only on trusted networks".
     */
    fun block(config: DataOutputConfig, machineOnline: Boolean, requested: Boolean): StartBlock? = when {
        !config.enabled -> StartBlock.NotEnabled
        !machineOnline -> StartBlock.MachineOffline
        config.activation == DataOutputActivation.OnDemand && !requested -> StartBlock.NotRequested
        else -> securityBlock(config) ?: DataOutputValidation.problems(config).takeIf { problems -> problems.isNotEmpty() }
            ?.let { problems -> StartBlock.InvalidConfig(problems) }
    }

    /** Gives the confirmation that the security mode of [config] needs, or null. */
    fun securityBlock(config: DataOutputConfig): StartBlock? = when (config.transport) {
        DataOutputTransport.Tcp ->
            if (config.security == DataOutputSecurity.PlainText && !config.plainTextConfirmed) StartBlock.PlainTextNotConfirmed else null
        DataOutputTransport.Mqtt ->
            if (!config.mqttTls && !config.mqttNoTlsConfirmed) StartBlock.MqttWithoutTlsNotConfirmed else null
    }

    /**
     * Gives the permission request of the local network for [config], or null when
     * the output uses only localhost. The UI sends it on the `ConnectionRequestManager` bus when
     * the user turns on or starts the output.
     */
    fun localNetworkRequest(config: DataOutputConfig): ConnectionRequest? =
        if (config.transport == DataOutputTransport.Tcp && config.bindInterface == BindInterface.Localhost) {
            null
        } else {
            ConnectionRequest.RequestPermissions(setOf(PermissionKind.LocalNetwork))
        }
}

/**
 * The ports of the TCP outputs that run in this process. Two outputs cannot use the same port.
 * The app has one registry for all outputs.
 */
class PortRegistry {
    private val _owners = MutableStateFlow<Map<Int, String>>(emptyMap())

    /** The owner of each port in use. */
    val owners: StateFlow<Map<Int, String>> = _owners.asStateFlow()

    /** Claims [port] for [owner]. Gives null on success, else the owner that holds the port. */
    fun claim(port: Int, owner: String): String? {
        var holder: String? = null
        _owners.update { map ->
            val current = map[port]
            if (current != null && current != owner) {
                holder = current
                map
            } else {
                holder = null
                map + (port to owner)
            }
        }
        return holder
    }

    /** Releases [port] if [owner] holds it. */
    fun release(port: Int, owner: String) {
        _owners.update { map -> if (map[port] == owner) map - port else map }
    }
}
