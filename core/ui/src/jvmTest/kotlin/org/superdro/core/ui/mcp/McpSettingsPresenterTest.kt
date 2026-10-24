package org.superdro.core.ui.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.ConnectionRequest
import org.superdro.core.connection.PermissionKind
import org.superdro.core.mcp.McpRules
import org.superdro.core.mcp.McpServerState
import org.superdro.core.model.BindInterface
import org.superdro.core.model.McpActivation
import org.superdro.core.model.McpConfirmation
import org.superdro.core.model.McpScope
import org.superdro.core.resources.Res
import org.superdro.core.resources.mcp_block_not_requested
import org.superdro.core.resources.mcp_block_off
import org.superdro.core.resources.mcp_block_plain_http
import org.superdro.core.resources.mcp_port_range
import org.superdro.core.ui.text.UiText

/** The presenter of the screen `Settings > MCP server`. */
class McpSettingsPresenterTest {

    @Test
    fun theSwitchOnWritesTheSettingAsksForThePermissionAndShowsThePlainHttpWarning() = runTest {
        val harness = McpHarness(this)
        harness.start()
        val presenter = harness.presenter()
        harness.settle()
        assertFalse(presenter.uiState.value.loading)
        assertEquals(UiText.of(Res.string.mcp_block_off), presenter.uiState.value.status.blockText)
        assertEquals(listOf("http://192.168.1.20:5810/mcp"), presenter.uiState.value.status.urls)

        presenter.onEnabledChange(true)
        harness.settle()
        assertTrue(harness.config.enabled)
        assertEquals(listOf<ConnectionRequest>(ConnectionRequest.RequestPermissions(setOf(PermissionKind.LocalNetwork))), harness.requests.sent)
        assertEquals(McpDialog.PlainHttpWarning, presenter.uiState.value.dialog)
        assertEquals(UiText.of(Res.string.mcp_block_not_requested), presenter.uiState.value.status.blockText)

        presenter.onDismissDialog()
        assertFalse(harness.config.plainHttpConfirmed, "A dismissed warning changes nothing.")
        presenter.onStartStop()
        harness.settle()
        assertEquals(McpDialog.PlainHttpWarning, presenter.uiState.value.dialog, "The start on a LAN interface asks again.")
        assertEquals(McpServerState.Stopped, presenter.uiState.value.status.state)
        presenter.onConfirmDialog()
        harness.settle()
        assertTrue(harness.config.plainHttpConfirmed)
        assertNull(presenter.uiState.value.dialog)
        presenter.onStartStop()
        harness.settle()
        assertEquals(McpServerState.Ready, presenter.uiState.value.status.state)
        assertTrue(presenter.uiState.value.status.running)
        assertNull(presenter.uiState.value.status.blockText)
        assertEquals(listOf("http://192.168.1.20:5810/mcp"), presenter.uiState.value.status.urls)

        presenter.onStartStop()
        harness.settle()
        assertEquals(McpServerState.Stopped, presenter.uiState.value.status.state)
        presenter.onEnabledChange(false)
        harness.settle()
        assertFalse(harness.config.enabled)
        presenter.close()
        harness.close()
    }

    @Test
    fun theScopesGoOnOnlyAfterTheirWarningAndOffAtOnce() = runTest {
        val harness = McpHarness(this)
        harness.start()
        val presenter = harness.presenter()
        harness.settle()
        presenter.onScope(McpScope.Populate, true)
        assertEquals(McpDialog.PopulateWarning, presenter.uiState.value.dialog)
        assertEquals(setOf(McpScope.Read), harness.config.scopes)
        presenter.onConfirmDialog()
        harness.settle()
        assertEquals(setOf(McpScope.Read, McpScope.Populate), harness.config.scopes)
        assertTrue(harness.config.populateConfirmed)
        presenter.onScope(McpScope.Populate, false)
        harness.settle()
        assertEquals(setOf(McpScope.Read), harness.config.scopes)
        presenter.onScope(McpScope.Populate, true)
        harness.settle()
        assertNull(presenter.uiState.value.dialog, "The second time on needs no warning.")
        assertEquals(setOf(McpScope.Read, McpScope.Populate), harness.config.scopes)

        presenter.onScope(McpScope.Control, true)
        assertEquals(McpDialog.ControlWarning, presenter.uiState.value.dialog)
        presenter.onDismissDialog()
        harness.settle()
        assertFalse(McpScope.Control in harness.config.scopes)
        presenter.onScope(McpScope.Read, false)
        harness.settle()
        assertTrue(McpScope.Read in harness.config.scopes, "Read stays on.")

        presenter.onConfirmation(McpConfirmation.All)
        harness.settle()
        assertEquals(McpConfirmation.All, harness.config.confirmation)
        presenter.onConfirmation(McpConfirmation.Off)
        assertEquals(McpDialog.ConfirmationOffWarning, presenter.uiState.value.dialog)
        assertEquals(McpConfirmation.All, harness.config.confirmation)
        presenter.onConfirmDialog()
        harness.settle()
        assertEquals(McpConfirmation.Off, harness.config.confirmation)
        presenter.close()
        harness.close()
    }

    @Test
    fun theNetworkSectionLimitsTheInterfacesAndADifferentLanInterfaceNeedsTheConfirmationAgain() = runTest {
        val harness = McpHarness(this)
        harness.start()
        val presenter = harness.presenter()
        harness.settle()
        assertEquals(McpSettingsPresenter.INTERFACES, presenter.uiState.value.interfaces.map { choice -> choice.bind })
        assertEquals(listOf("192.168.1.20"), presenter.uiState.value.interfaces.first { choice -> choice.bind == BindInterface.WiFi }.addresses)
        presenter.onConfirmPlainHttp()
        harness.settle()
        assertTrue(harness.config.plainHttpConfirmed)
        presenter.onInterface(BindInterface.Localhost)
        harness.settle()
        assertEquals(BindInterface.Localhost, harness.config.bindInterface)
        assertTrue(harness.config.plainHttpConfirmed, "Localhost keeps the flag.")
        assertEquals(listOf("http://127.0.0.1:5810/mcp"), presenter.uiState.value.status.urls)
        presenter.onInterface(BindInterface.UsbEthernet)
        harness.settle()
        assertFalse(harness.config.plainHttpConfirmed, "A different LAN interface needs the confirmation again.")
        assertTrue(McpRules.needsPlainHttpWarning(harness.config))
        presenter.onInterface(BindInterface.All)
        harness.settle()
        assertEquals(BindInterface.UsbEthernet, harness.config.bindInterface, "All interfaces is not offered.")

        presenter.onPortText("80")
        harness.settle()
        assertEquals(UiText.of(Res.string.mcp_port_range), presenter.uiState.value.portProblem)
        assertEquals(5810, harness.config.port)
        presenter.onPortText("5900")
        harness.settle()
        assertNull(presenter.uiState.value.portProblem)
        assertEquals(5900, harness.config.port)
        presenter.close()
        harness.close()
    }

    @Test
    fun aNewTokenShowsTheTokenTheCommandAndTheMessageOneTime() = runTest {
        val harness = McpHarness(this)
        harness.configure { config -> config.copy(enabled = true, activation = McpActivation.AlwaysOn, bindInterface = BindInterface.Localhost) }
        harness.start()
        val presenter = harness.presenter()
        harness.settle()
        assertNull(presenter.uiState.value.token)
        presenter.onNewToken()
        assertEquals(McpDialog.NewToken, presenter.uiState.value.dialog)
        presenter.onConfirmDialog()
        harness.settle()
        val token = assertNotNull(presenter.uiState.value.token)
        assertEquals(43, token.length)
        assertEquals(McpRules.claudeCodeCommand("http://127.0.0.1:5810/mcp", token), presenter.uiState.value.command)
        assertEquals(token, harness.runner.token(create = false))
        assertNotNull(presenter.uiState.value.message)
        presenter.onMessageShown(presenter.uiState.value.message!!.id)
        assertNull(presenter.uiState.value.message)
        presenter.onTokenCopied()
        assertNotNull(presenter.uiState.value.message)
        presenter.onHideToken()
        assertNull(presenter.uiState.value.token)
        assertNull(presenter.uiState.value.command)
        presenter.close()
        harness.close()
    }

    @Test
    fun theDiagnosticsShowTheAuditRowsNewestFirst() = runTest {
        val harness = McpHarness(this)
        harness.configure { config -> config.copy(enabled = true, activation = McpActivation.AlwaysOn, bindInterface = BindInterface.Localhost) }
        harness.start()
        val presenter = harness.presenter()
        harness.settle()
        val link = harness.connect()
        harness.settle()
        link.request("POST", "/mcp", """{"jsonrpc":"2.0","id":1,"method":"ping"}""", harness.authorization(), host = "127.0.0.1:5810")
        harness.settle()
        link.request("POST", "/mcp", """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""", harness.authorization(), host = "127.0.0.1:5810")
        harness.settle()
        presenter.onShowAudit()
        harness.settle()
        assertTrue(presenter.uiState.value.auditOpen)
        val lines = presenter.uiState.value.audit
        assertEquals(listOf("tools/list", "ping"), lines.map { line -> line.name })
        assertTrue(lines.all { line -> line.result == "ok" }, lines.toString())
        assertTrue(lines.first().time.startsWith("2026-10-23"), lines.first().time)
        presenter.onHideAudit()
        assertFalse(presenter.uiState.value.auditOpen)
        assertEquals(UiText.of(Res.string.mcp_block_plain_http), McpTexts.block(harness.config.copy(bindInterface = BindInterface.WiFi), requested = true, permissionGranted = null))
        presenter.close()
        harness.close()
    }
}
