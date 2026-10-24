package org.superdro.core.model

import kotlinx.serialization.Serializable

/** The access scope of a group of MCP tools. */
@Serializable
enum class McpScope {
    /** The read tools and the resources. Always on while the server is on. */
    Read,

    /** The tools that write the stored data: workspaces, tool libraries, profiles. */
    Populate,

    /** The tools that change the readout: zero, set, mode, units, selection. */
    Control,
}

/** The start of the MCP server. */
@Serializable
enum class McpActivation {
    /** The server runs after a tap on the status chip. The default. */
    OnDemand,

    /** The server runs while the app runs. */
    AlwaysOn,
}

/** When a write tool call needs a tap on the tablet. */
@Serializable
enum class McpConfirmation {
    /** Only a risky call needs the tap. The default. */
    Risky,

    /** Each write call needs the tap. */
    All,

    /** No call needs the tap. Only after its own warning. */
    Off,
}

/**
 * The configuration of the MCP server. The server is off by default, and no server socket is
 * open while it is off. The bearer token stays in the `SecretStore`, not here.
 */
@Serializable
data class McpConfig(
    val enabled: Boolean = false,
    val activation: McpActivation = McpActivation.OnDemand,
    /** Only WiFi, Localhost, and UsbEthernet are valid (McpRules.ALLOWED_INTERFACES). */
    val bindInterface: BindInterface = BindInterface.WiFi,
    /** 1024 to 65535. */
    val port: Int = DEFAULT_PORT,
    /**
     * True after the user confirmed the warning "Use only on trusted networks" for a LAN
     * interface. The server does not start on a LAN interface while this is false.
     */
    val plainHttpConfirmed: Boolean = false,
    /** Read is always in the set while enabled. */
    val scopes: Set<McpScope> = setOf(McpScope.Read),
    val populateConfirmed: Boolean = false,
    val controlConfirmed: Boolean = false,
    val confirmation: McpConfirmation = McpConfirmation.Risky,
    /** The control tools that are on. A tool that is not in the set is off. */
    val controlTools: Set<String> = emptySet(),
    val liveUpdates: Boolean = false,
    /** 1 to 10. */
    val liveRateHz: Int = DEFAULT_LIVE_RATE_HZ,
) {
    companion object {
        const val DEFAULT_PORT: Int = 5810
        const val MIN_PORT: Int = 1024
        const val MAX_PORT: Int = 65535
        const val DEFAULT_LIVE_RATE_HZ: Int = 2
        const val MIN_LIVE_RATE_HZ: Int = 1
        const val MAX_LIVE_RATE_HZ: Int = 10
    }
}
