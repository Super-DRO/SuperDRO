package org.superdro.core.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.connection.ConnectionRequest
import org.superdro.core.connection.PermissionKind
import org.superdro.core.model.BindInterface
import org.superdro.core.model.McpActivation
import org.superdro.core.model.McpConfig
import org.superdro.core.model.McpConfirmation

/** The pure rules of the MCP server. */
class McpRulesTest {

    private val lan = McpConfig(enabled = true, activation = McpActivation.AlwaysOn, plainHttpConfirmed = true)

    @Test
    fun theServerOffersWifiLocalhostAndUsbEthernetOnly() {
        assertEquals(setOf(BindInterface.WiFi, BindInterface.Localhost, BindInterface.UsbEthernet), McpRules.ALLOWED_INTERFACES)
        assertFalse(McpRules.runs(lan.copy(bindInterface = BindInterface.Vpn), startedOnDemand = true, localNetworkGranted = true, localNetworkNeeded = false))
        assertFalse(McpRules.runs(lan.copy(bindInterface = BindInterface.All), startedOnDemand = true, localNetworkGranted = true, localNetworkNeeded = false))
    }

    @Test
    fun theServerDoesNotRunWhileItIsOff() {
        assertFalse(McpRules.runs(McpConfig(), startedOnDemand = true, localNetworkGranted = true, localNetworkNeeded = false))
        assertTrue(McpRules.runs(lan, startedOnDemand = false, localNetworkGranted = true, localNetworkNeeded = false))
    }

    @Test
    fun onDemandRunsOnlyAfterTheTap() {
        val onDemand = lan.copy(activation = McpActivation.OnDemand)
        assertFalse(McpRules.runs(onDemand, startedOnDemand = false, localNetworkGranted = true, localNetworkNeeded = false))
        assertTrue(McpRules.runs(onDemand, startedOnDemand = true, localNetworkGranted = true, localNetworkNeeded = false))
    }

    @Test
    fun aLanInterfaceNeedsThePlainHttpConfirmationAndLocalhostDoesNot() {
        val unconfirmed = lan.copy(plainHttpConfirmed = false)
        assertTrue(McpRules.needsPlainHttpWarning(unconfirmed))
        assertFalse(McpRules.runs(unconfirmed, startedOnDemand = true, localNetworkGranted = true, localNetworkNeeded = false))
        val localhost = unconfirmed.copy(bindInterface = BindInterface.Localhost)
        assertFalse(McpRules.needsPlainHttpWarning(localhost))
        assertTrue(McpRules.runs(localhost, startedOnDemand = true, localNetworkGranted = false, localNetworkNeeded = true))
        assertFalse(McpRules.needsPlainHttpWarning(lan))
    }

    @Test
    fun aLanInterfaceNeedsTheLocalNetworkPermissionWhenThePlatformNeedsIt() {
        assertFalse(McpRules.runs(lan, startedOnDemand = true, localNetworkGranted = false, localNetworkNeeded = true))
        assertTrue(McpRules.runs(lan, startedOnDemand = true, localNetworkGranted = true, localNetworkNeeded = true))
        assertTrue(McpRules.runs(lan, startedOnDemand = true, localNetworkGranted = false, localNetworkNeeded = false))
    }

    @Test
    fun theLocalNetworkRequestIsNullForLocalhost() {
        assertNull(McpRules.localNetworkRequest(lan.copy(bindInterface = BindInterface.Localhost)))
        assertEquals(ConnectionRequest.RequestPermissions(setOf(PermissionKind.LocalNetwork)), McpRules.localNetworkRequest(lan))
        assertEquals(ConnectionRequest.RequestPermissions(setOf(PermissionKind.LocalNetwork)), McpRules.localNetworkRequest(lan.copy(bindInterface = BindInterface.UsbEthernet)))
    }

    @Test
    fun theConfirmationRuleFollowsTheMode() {
        assertTrue(McpRules.needsConfirmation(McpConfirmation.All, risky = false))
        assertTrue(McpRules.needsConfirmation(McpConfirmation.All, risky = true))
        assertFalse(McpRules.needsConfirmation(McpConfirmation.Risky, risky = false))
        assertTrue(McpRules.needsConfirmation(McpConfirmation.Risky, risky = true))
        assertFalse(McpRules.needsConfirmation(McpConfirmation.Off, risky = true))
    }

    @Test
    fun theUrlHasBracketsForIpv6AndNoZone() {
        assertEquals("http://192.168.1.20:5810/mcp", McpRules.url("192.168.1.20", 5810))
        assertEquals("http://[fe80::1]:5810/mcp", McpRules.url("fe80::1%wlan0", 5810))
        assertEquals("http://[fe80::1]:5810/mcp", McpRules.url("[fe80::1]", 5810))
        assertEquals("http://localhost:5810/mcp", McpRules.url("localhost", 5810))
    }

    @Test
    fun theClaudeCodeCommandNamesTheServerTheUrlAndTheBearerHeader() {
        assertEquals(
            "claude mcp add --transport http superdro http://192.168.1.20:5810/mcp --header \"Authorization: Bearer abc\"",
            McpRules.claudeCodeCommand("http://192.168.1.20:5810/mcp", "abc"),
        )
    }
}
