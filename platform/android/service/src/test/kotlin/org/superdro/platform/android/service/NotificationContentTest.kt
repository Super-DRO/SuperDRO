package org.superdro.platform.android.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.DeviceLine
import org.superdro.core.connection.KeepAliveController
import org.superdro.core.connection.LinkFailure
import org.superdro.core.connection.McpServerLine
import org.superdro.core.connection.SessionState
import org.superdro.core.model.TransportKind

/**
 * The content of the one notification for all machines: one line for each
 * online machine and the number of the offline remembered machines. The texts are the English
 * texts of `strings_service.xml` and `strings_app.xml`.
 */
class NotificationContentTest {

    private object EnglishTexts : ServiceTexts {
        override suspend fun channelName() = "Connections"
        override suspend fun disconnectAll() = "Disconnect all"
        override suspend fun title(onlineCount: Int) = when (onlineCount) {
            0 -> "No machine is online"
            1 -> "1 machine online"
            else -> "$onlineCount machines online"
        }
        override suspend fun offlineRemembered(count: Int) =
            if (count == 1) "1 remembered machine offline" else "$count remembered machines offline"
        override suspend fun deviceLine(name: String, state: SessionState, transport: TransportKind): String {
            val stateText = when (state) {
                is SessionState.Connected -> "Connected"
                SessionState.AwaitingData -> "Waiting for data"
                SessionState.NoData -> "No data"
                else -> state.toString()
            }
            val link = when (transport) {
                TransportKind.BluetoothSpp, TransportKind.Ble -> "Bluetooth"
                TransportKind.UsbSerial -> "USB"
                TransportKind.Tcp -> "Wi-Fi"
                TransportKind.Fake -> "Demo"
            }
            return "$name: $stateText ($link)"
        }
        override suspend fun bootOpenApp() = "Open SuperDRO to connect your machines"
        override suspend fun mcpServer(address: String?) = if (address == null) "MCP server: no address" else "MCP server on $address"
    }

    private fun line(id: String, name: String, transport: TransportKind, state: SessionState) =
        DeviceLine(DeviceId(id), name, transport, state)

    @Test
    fun oneLineForEachOnlineMachineAndTheNumberOfOfflineMachines() = runTest {
        val summary = KeepAliveController.summaryOf(
            listOf(
                line("mill", "Mill", TransportKind.BluetoothSpp, SessionState.Connected(sinceMs = 10L)),
                line("lathe", "Lathe", TransportKind.UsbSerial, SessionState.Reconnecting(2, 5000L, LinkFailure.Lost)),
                line("router", "Router", TransportKind.Tcp, SessionState.NoData),
                line("paused", "Paused mill", TransportKind.BluetoothSpp, SessionState.Disconnected),
            ),
        )!!
        val content = NotificationContent.of(summary, EnglishTexts)
        assertEquals("2 machines online", content.title)
        assertEquals(
            listOf("Mill: Connected (Bluetooth)", "Router: No data (Wi-Fi)", "2 remembered machines offline"),
            content.lines,
        )
        assertEquals("Mill: Connected (Bluetooth)", content.text)
        // The tap opens the tab of the first online machine.
        assertEquals(DeviceId("mill"), content.tapDevice)
    }

    @Test
    fun withNoOnlineMachineTheTitleSaysSoAndTheTapOpensTheFirstMachine() = runTest {
        val summary = KeepAliveController.summaryOf(
            listOf(line("lathe", "Lathe", TransportKind.UsbSerial, SessionState.WaitingForDevice(LinkFailure.DeviceGone))),
        )!!
        val content = NotificationContent.of(summary, EnglishTexts)
        assertEquals("No machine is online", content.title)
        assertEquals(listOf("1 remembered machine offline"), content.lines)
        assertEquals(DeviceId("lathe"), content.tapDevice)
    }

    @Test
    fun oneOnlineMachineWithNoOfflineMachineHasOneLine() = runTest {
        val summary = KeepAliveController.summaryOf(
            listOf(line("demo-mill", "Demo mill", TransportKind.Fake, SessionState.AwaitingData)),
        )!!
        val content = NotificationContent.of(summary, EnglishTexts)
        assertEquals("1 machine online", content.title)
        assertEquals(listOf("Demo mill: Waiting for data (Demo)"), content.lines)
    }

    @Test
    fun theMcpServerAloneGivesTheServerLineAndNoTapDevice() = runTest {
        val summary = KeepAliveController.summaryOf(emptyList(), McpServerLine(listOf("192.168.1.5:5810", "[fd00::5]:5810")))!!
        val content = NotificationContent.of(summary, EnglishTexts)
        assertEquals("No machine is online", content.title)
        assertEquals(listOf("MCP server on 192.168.1.5:5810"), content.lines)
        assertEquals("MCP server on 192.168.1.5:5810", content.text)
        assertNull(content.tapDevice)
    }

    @Test
    fun theMcpServerLineComesAfterTheMachineLines() = runTest {
        val summary = KeepAliveController.summaryOf(
            listOf(
                line("mill", "Mill", TransportKind.BluetoothSpp, SessionState.Connected(sinceMs = 10L)),
                line("paused", "Paused mill", TransportKind.BluetoothSpp, SessionState.Disconnected),
            ),
            McpServerLine(),
        )!!
        val content = NotificationContent.of(summary, EnglishTexts)
        assertEquals("1 machine online", content.title)
        assertEquals(listOf("Mill: Connected (Bluetooth)", "1 remembered machine offline", "MCP server: no address"), content.lines)
        assertEquals(DeviceId("mill"), content.tapDevice)
    }

    @Test
    fun aContentWithNoLineHasNoText() {
        assertNull(NotificationContent("No machine is online", emptyList(), null).text)
    }
}
