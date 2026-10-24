package org.superdro.core.mcp

import org.superdro.core.connection.ConnectionRequest
import org.superdro.core.connection.PermissionKind
import org.superdro.core.model.BindInterface
import org.superdro.core.model.McpActivation
import org.superdro.core.model.McpConfig
import org.superdro.core.model.McpConfirmation

/** The pure rules of the MCP server: the run rule, the warnings, the permission request, the texts. */
object McpRules {

    /** The interfaces that the server offers. `Vpn` and `All` are not valid. */
    val ALLOWED_INTERFACES: Set<BindInterface> = setOf(BindInterface.WiFi, BindInterface.Localhost, BindInterface.UsbEthernet)

    /** The name of the server in the Claude Code command. */
    const val SERVER_NAME: String = "superdro"

    /**
     * Gives true when the server runs. The server runs only when all of these are true:
     * 1. [McpConfig.enabled] is true, and the interface is in [ALLOWED_INTERFACES].
     * 2. The activation is [McpActivation.AlwaysOn], or the user tapped the chip ([startedOnDemand]).
     * 3. The interface is Localhost, or the plain HTTP warning was confirmed ([needsPlainHttpWarning]).
     * 4. The platform does not need the local network permission ([localNetworkNeeded]), or the
     *    permission is granted ([localNetworkGranted]), or the interface is Localhost.
     */
    fun runs(config: McpConfig, startedOnDemand: Boolean, localNetworkGranted: Boolean, localNetworkNeeded: Boolean): Boolean {
        if (!config.enabled || config.bindInterface !in ALLOWED_INTERFACES) return false
        if (config.activation == McpActivation.OnDemand && !startedOnDemand) return false
        if (needsPlainHttpWarning(config)) return false
        val localhost = config.bindInterface == BindInterface.Localhost
        if (localNetworkNeeded && !localNetworkGranted && !localhost) return false
        return true
    }

    /** Gives true when the server needs the warning "Use only on trusted networks" before the start. */
    fun needsPlainHttpWarning(config: McpConfig): Boolean =
        config.bindInterface != BindInterface.Localhost && !config.plainHttpConfirmed

    /**
     * Gives the permission request of the local network for [config], or null for Localhost. The
     * UI sends it on the `ConnectionRequestManager` bus before the start.
     */
    fun localNetworkRequest(config: McpConfig): ConnectionRequest? =
        if (config.bindInterface == BindInterface.Localhost) null else ConnectionRequest.RequestPermissions(setOf(PermissionKind.LocalNetwork))

    /**
     * Gives true when a write with the risk [risky] needs the confirmation on the tablet:
     * [McpConfirmation.All] always, [McpConfirmation.Risky] only for a risky write,
     * [McpConfirmation.Off] never.
     */
    fun needsConfirmation(confirmation: McpConfirmation, risky: Boolean): Boolean = when (confirmation) {
        McpConfirmation.All -> true
        McpConfirmation.Risky -> risky
        McpConfirmation.Off -> false
    }

    /**
     * Gives the URL of the server on [host]: `http://host:port/mcp`, with `[host]` for an IPv6
     * address. A zone (`%wlan0`) is removed, because a URL cannot carry it.
     */
    fun url(host: String, port: Int): String {
        val bare = host.substringBefore('%').removePrefix("[").removeSuffix("]")
        val shown = if (':' in bare) "[$bare]" else bare
        return "http://$shown:$port/mcp"
    }

    /** Gives the Claude Code command that adds the server at [url] with the bearer [token]. */
    fun claudeCodeCommand(url: String, token: String): String =
        "claude mcp add --transport http $SERVER_NAME $url --header \"Authorization: Bearer $token\""
}
