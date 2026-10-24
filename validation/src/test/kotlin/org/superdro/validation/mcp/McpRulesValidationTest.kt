package org.superdro.validation.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.connection.ConnectionRequest
import org.superdro.core.connection.PermissionKind
import org.superdro.core.mcp.McpRules
import org.superdro.core.model.AppSettings
import org.superdro.core.model.BindInterface
import org.superdro.core.model.McpActivation
import org.superdro.core.model.McpConfig
import org.superdro.core.model.McpScope

/**
 * The acceptance rules of the MCP server configuration (proposal sections 1, 4.6, and 6.2): off
 * by default, the run rule, and the plain HTTP warning.
 */
class McpRulesValidationTest {

    @Test
    fun theServerIsOffByDefaultWithTheReadScopeOnlyAndNoWarningConfirmed() {
        val config = AppSettings().mcp
        assertFalse(config.enabled)
        assertEquals(McpActivation.OnDemand, config.activation)
        assertEquals(setOf(McpScope.Read), config.scopes)
        assertFalse(config.plainHttpConfirmed)
        assertFalse(config.populateConfirmed)
        assertFalse(config.controlConfirmed)
        assertEquals(5810, config.port)
        assertFalse(McpRules.runs(config, startedOnDemand = true, localNetworkGranted = true, localNetworkNeeded = false))
    }

    @Test
    fun theRunRuleNeedsTheSwitchTheActivationTheWarningAndThePermission() {
        val base = McpConfig(enabled = true, activation = McpActivation.AlwaysOn, bindInterface = BindInterface.WiFi, plainHttpConfirmed = true)
        assertTrue(McpRules.runs(base, startedOnDemand = false, localNetworkGranted = true, localNetworkNeeded = true))
        // 1. The switch.
        assertFalse(McpRules.runs(base.copy(enabled = false), startedOnDemand = true, localNetworkGranted = true, localNetworkNeeded = false))
        // 2. On demand needs the tap.
        val onDemand = base.copy(activation = McpActivation.OnDemand)
        assertFalse(McpRules.runs(onDemand, startedOnDemand = false, localNetworkGranted = true, localNetworkNeeded = false))
        assertTrue(McpRules.runs(onDemand, startedOnDemand = true, localNetworkGranted = true, localNetworkNeeded = false))
        // 3. A LAN interface needs the confirmed warning.
        assertFalse(McpRules.runs(base.copy(plainHttpConfirmed = false), startedOnDemand = true, localNetworkGranted = true, localNetworkNeeded = false))
        // 4. A LAN interface needs the local network permission where the platform needs it.
        assertFalse(McpRules.runs(base, startedOnDemand = true, localNetworkGranted = false, localNetworkNeeded = true))
        // Localhost needs no warning and no permission.
        val localhost = base.copy(bindInterface = BindInterface.Localhost, plainHttpConfirmed = false)
        assertTrue(McpRules.runs(localhost, startedOnDemand = true, localNetworkGranted = false, localNetworkNeeded = true))
    }

    @Test
    fun theServerOffersOnlyWifiLocalhostAndUsbEthernet() {
        assertEquals(setOf(BindInterface.WiFi, BindInterface.Localhost, BindInterface.UsbEthernet), McpRules.ALLOWED_INTERFACES)
        val vpn = McpConfig(enabled = true, activation = McpActivation.AlwaysOn, bindInterface = BindInterface.Vpn, plainHttpConfirmed = true)
        assertFalse(McpRules.runs(vpn, startedOnDemand = true, localNetworkGranted = true, localNetworkNeeded = false))
        assertFalse(McpRules.runs(vpn.copy(bindInterface = BindInterface.All), startedOnDemand = true, localNetworkGranted = true, localNetworkNeeded = false))
    }

    @Test
    fun thePlainHttpWarningAppliesToEachLanInterfaceAndNotToLocalhost() {
        assertTrue(McpRules.needsPlainHttpWarning(McpConfig(bindInterface = BindInterface.WiFi)))
        assertTrue(McpRules.needsPlainHttpWarning(McpConfig(bindInterface = BindInterface.UsbEthernet)))
        assertFalse(McpRules.needsPlainHttpWarning(McpConfig(bindInterface = BindInterface.WiFi, plainHttpConfirmed = true)))
        assertFalse(McpRules.needsPlainHttpWarning(McpConfig(bindInterface = BindInterface.Localhost)))
    }

    @Test
    fun theLocalNetworkRequestFollowsTheDataOutputPattern() {
        assertNull(McpRules.localNetworkRequest(McpConfig(bindInterface = BindInterface.Localhost)))
        assertEquals(
            ConnectionRequest.RequestPermissions(setOf(PermissionKind.LocalNetwork)),
            McpRules.localNetworkRequest(McpConfig(bindInterface = BindInterface.WiFi)),
        )
    }
}
