package org.superdro.core.ui.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.ConnectionRequest
import org.superdro.core.connection.PermissionKind
import org.superdro.core.mcp.McpServerState
import org.superdro.core.model.BindInterface
import org.superdro.core.model.McpActivation

/** The status chip of the MCP server in the status strip. */
class McpChipPresenterTest {

    @Test
    fun theChipShowsWhenTheServerIsOnAndATapStartsAndStopsIt() = runTest {
        val harness = McpHarness(this)
        harness.start()
        val chip = harness.chip()
        harness.settle()
        assertFalse(chip.uiState.value.visible, "No chip while the server is off.")

        harness.configure { config -> config.copy(enabled = true, plainHttpConfirmed = true) }
        harness.settle()
        assertTrue(chip.uiState.value.visible)
        assertEquals(McpServerState.Stopped, chip.uiState.value.state)

        chip.onTap()
        harness.settle()
        assertEquals(McpServerState.Ready, chip.uiState.value.state)
        assertTrue(chip.uiState.value.running)
        assertEquals(listOf<ConnectionRequest>(ConnectionRequest.RequestPermissions(setOf(PermissionKind.LocalNetwork))), harness.requests.sent)
        assertEquals(1, harness.sockets.open.size)

        chip.onTap()
        harness.settle()
        assertEquals(McpServerState.Stopped, chip.uiState.value.state)
        assertTrue(harness.sockets.open.isEmpty())
        chip.close()
        harness.close()
    }

    @Test
    fun aTapOpensTheDiagnosticsWithAlwaysOnOrWhileTheWarningWaits() = runTest {
        val harness = McpHarness(this)
        harness.configure { config -> config.copy(enabled = true) }
        harness.start()
        val chip = harness.chip()
        harness.settle()
        chip.onTap()
        harness.settle()
        assertTrue(chip.uiState.value.openDetails, "The plain HTTP warning waits: the tap opens the screen.")
        assertTrue(harness.sockets.sockets.isEmpty())
        chip.detailsOpened()
        assertFalse(chip.uiState.value.openDetails)

        harness.configure { config -> config.copy(activation = McpActivation.AlwaysOn, bindInterface = BindInterface.Localhost) }
        harness.settle()
        assertTrue(chip.uiState.value.running)
        chip.onTap()
        harness.settle()
        assertTrue(chip.uiState.value.openDetails)
        assertTrue(chip.uiState.value.running, "The tap of Always on does not stop the server.")
        chip.close()
        harness.close()
    }
}
