package org.superdro.core.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.TransportKind

/** The keep-alive summary while the MCP server runs. */
class KeepAliveMcpServerTest {

    private val mill = DeviceLine(DeviceId("mill"), "Mill", TransportKind.BluetoothSpp, SessionState.Connected(sinceMs = 0L))
    private val offline = DeviceLine(DeviceId("lathe"), "Lathe", TransportKind.Tcp, SessionState.Disconnected)

    @Test
    fun theServerAloneGivesASummaryWithNoDevice() {
        val summary = KeepAliveController.summaryOf(emptyList(), McpServerLine(listOf("192.168.1.5:5810", "[fe80::1]:5810")))
        assertEquals(KeepAliveSummary(emptyList(), offlineRememberedCount = 0, mcpServer = true, mcpAddress = "192.168.1.5:5810"), summary)
        assertEquals(0, summary?.onlineCount)
    }

    @Test
    fun theServerWithNoAddressGivesASummaryWithNoAddress() {
        val summary = KeepAliveController.summaryOf(listOf(offline), McpServerLine())
        assertEquals(KeepAliveSummary(emptyList(), offlineRememberedCount = 1, mcpServer = true, mcpAddress = null), summary)
    }

    @Test
    fun theMachinesKeepTheirSummaryWithoutTheServer() {
        assertNull(KeepAliveController.summaryOf(listOf(offline), mcpServer = null))
        val summary = KeepAliveController.summaryOf(listOf(mill, offline))
        assertEquals(KeepAliveSummary(listOf(mill), offlineRememberedCount = 1), summary)
        assertFalse(summary!!.mcpServer)
        assertNull(summary.mcpAddress)
    }

    @Test
    fun theServerAndAMachineGiveOneSummary() {
        val summary = KeepAliveController.summaryOf(listOf(mill), McpServerLine(listOf("127.0.0.1:5810")))
        assertEquals(listOf(mill), summary?.devices)
        assertTrue(summary!!.mcpServer)
        assertEquals("127.0.0.1:5810", summary.mcpAddress)
    }

    @Test
    fun theControllerStartsAndStopsTheServiceWithTheServer() = runTest {
        val sent = ArrayList<KeepAliveSummary?>()
        val keepAlive = object : ConnectionKeepAlive {
            override fun update(summary: KeepAliveSummary?) {
                sent += summary
            }
        }
        val server = MutableStateFlow<McpServerLine?>(null)
        val controller = KeepAliveController(
            sessions = MutableStateFlow(emptyMap<DeviceId, DeviceSession>()),
            keepAlive = keepAlive,
            scope = backgroundScope,
            mcpServer = server,
        )
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(emptyList(), sent)
        // The server starts with no address. The address comes 50 ms later: one update.
        server.value = McpServerLine()
        advanceTimeBy(50)
        runCurrent()
        server.value = McpServerLine(listOf("10.0.0.2:5810"))
        advanceTimeBy(299)
        runCurrent()
        assertEquals(emptyList(), sent)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf<KeepAliveSummary?>(KeepAliveSummary(emptyList(), mcpServer = true, mcpAddress = "10.0.0.2:5810")), sent)
        assertEquals(sent.last(), controller.summary.value)
        // The stop of the server stops the service.
        server.value = null
        advanceTimeBy(300)
        runCurrent()
        assertEquals(2, sent.size)
        assertNull(sent.last())
        assertNull(controller.summary.value)
    }
}
