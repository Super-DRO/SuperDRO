package org.superdro.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The defaults of the MCP server configuration, and its place in [AppSettings]. */
class McpConfigDefaultsTest {

    private val config = AppSettings().mcp

    @Test
    fun theServerIsOffOnDemandAndBindsToWifiOnPort5810() {
        assertFalse(config.enabled)
        assertEquals(McpActivation.OnDemand, config.activation)
        assertEquals(BindInterface.WiFi, config.bindInterface)
        assertEquals(5810, config.port)
        assertFalse(config.plainHttpConfirmed)
    }

    @Test
    fun onlyTheReadScopeIsOnAndNoWarningIsConfirmed() {
        assertEquals(setOf(McpScope.Read), config.scopes)
        assertFalse(config.populateConfirmed)
        assertFalse(config.controlConfirmed)
        assertEquals(McpConfirmation.Risky, config.confirmation)
        assertTrue(config.controlTools.isEmpty())
    }

    @Test
    fun theLiveUpdatesAreOffAt2Hz() {
        assertFalse(config.liveUpdates)
        assertEquals(2, config.liveRateHz)
    }

    @Test
    fun anOldSettingsFileWithNoMcpFieldLoadsWithTheDefaults() {
        val old = """{"schemaVersion":1,"themeMode":"Dark"}"""
        val settings = SuperDroJson.decodeFromString(AppSettings.serializer(), old)
        assertEquals(McpConfig(), settings.mcp)
    }

    @Test
    fun theConfigurationGoesThroughTheJson() {
        val settings = AppSettings(
            mcp = McpConfig(
                enabled = true,
                activation = McpActivation.AlwaysOn,
                bindInterface = BindInterface.Localhost,
                port = 6000,
                plainHttpConfirmed = true,
                scopes = setOf(McpScope.Read, McpScope.Populate),
                populateConfirmed = true,
                confirmation = McpConfirmation.All,
                controlTools = setOf("zero_axis"),
                liveUpdates = true,
                liveRateHz = 5,
            ),
        )
        val text = SuperDroJson.encodeToString(AppSettings.serializer(), settings)
        assertEquals(settings, SuperDroJson.decodeFromString(AppSettings.serializer(), text))
    }
}
