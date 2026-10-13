package org.superdro.core.dataoutput

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.connection.ConnectionRequest
import org.superdro.core.connection.NetworkInterfaceInfo
import org.superdro.core.connection.PermissionKind
import org.superdro.core.model.BindInterface
import org.superdro.core.model.DataOutputActivation
import org.superdro.core.model.DataOutputConfig
import org.superdro.core.model.DataOutputSecurity
import org.superdro.core.model.DataOutputTransport

/** The rules that decide if the output runs, and the bind addresses. */
class OutputRunRuleTest {

    private val on = DataOutputConfig(enabled = true)

    @Test
    fun theOutputRunsOnlyWhenTheUserTurnedItOn() {
        assertEquals(StartBlock.NotEnabled, OutputRunRule.block(DataOutputConfig(), machineOnline = true, requested = true))
        assertEquals(
            StartBlock.NotEnabled,
            OutputRunRule.block(DataOutputConfig(activation = DataOutputActivation.AlwaysOn), machineOnline = true, requested = false),
        )
    }

    @Test
    fun onDemandRunsAfterTheRequestAndAlwaysOnRunsWhileTheMachineIsOnline() {
        assertEquals(StartBlock.NotRequested, OutputRunRule.block(on, machineOnline = true, requested = false))
        assertNull(OutputRunRule.block(on, machineOnline = true, requested = true))
        assertEquals(StartBlock.MachineOffline, OutputRunRule.block(on, machineOnline = false, requested = true))
        val always = on.copy(activation = DataOutputActivation.AlwaysOn)
        assertNull(OutputRunRule.block(always, machineOnline = true, requested = false))
        assertEquals(StartBlock.MachineOffline, OutputRunRule.block(always, machineOnline = false, requested = false))
    }

    @Test
    fun thePlainTextModeRunsOnlyAfterTheConfirmation() {
        val plain = on.copy(security = DataOutputSecurity.PlainText)
        assertEquals(StartBlock.PlainTextNotConfirmed, OutputRunRule.block(plain, machineOnline = true, requested = true))
        assertNull(OutputRunRule.block(plain.copy(plainTextConfirmed = true), machineOnline = true, requested = true))
        // The encrypted mode needs no confirmation.
        assertNull(OutputRunRule.securityBlock(on))
    }

    @Test
    fun aBrokerLinkWithNoTlsRunsOnlyAfterTheConfirmation() {
        val mqtt = on.copy(transport = DataOutputTransport.Mqtt, mqttBrokerHost = "broker.local")
        assertNull(OutputRunRule.block(mqtt, machineOnline = true, requested = true))
        val plain = mqtt.copy(mqttTls = false, mqttBrokerPort = 1883)
        assertEquals(StartBlock.MqttWithoutTlsNotConfirmed, OutputRunRule.block(plain, machineOnline = true, requested = true))
        assertNull(OutputRunRule.block(plain.copy(mqttNoTlsConfirmed = true), machineOnline = true, requested = true))
    }

    @Test
    fun aConfigurationWithProblemsDoesNotRun() {
        val block = OutputRunRule.block(on.copy(port = 80), machineOnline = true, requested = true)
        assertEquals(StartBlock.InvalidConfig(listOf(ConfigProblem.Port(80))), block)
    }

    @Test
    fun theBindAddressesComeFromTheSelectedInterfaces() {
        val interfaces = listOf(
            NetworkInterfaceInfo("wlan0", BindInterface.WiFi, listOf("192.168.1.20", "fe80::1%wlan0")),
            NetworkInterfaceInfo("lo", BindInterface.Localhost, listOf("127.0.0.1", "::1")),
            NetworkInterfaceInfo("tun0", BindInterface.Vpn, listOf("10.8.0.2")),
            NetworkInterfaceInfo("eth0", BindInterface.UsbEthernet, listOf("192.168.42.1")),
            NetworkInterfaceInfo("rmnet0", null, listOf("100.64.0.9")),
        )
        assertEquals(listOf("192.168.1.20", "fe80::1%wlan0"), BindAddresses.hosts(interfaces, BindInterface.WiFi))
        assertEquals(listOf("127.0.0.1", "::1"), BindAddresses.hosts(interfaces, BindInterface.Localhost))
        assertEquals(listOf("10.8.0.2"), BindAddresses.hosts(interfaces, BindInterface.Vpn))
        assertEquals(listOf("192.168.42.1"), BindAddresses.hosts(interfaces, BindInterface.UsbEthernet))
        // "All interfaces" never gives the cellular network.
        assertEquals(
            listOf("192.168.1.20", "fe80::1%wlan0", "127.0.0.1", "::1", "10.8.0.2", "192.168.42.1"),
            BindAddresses.hosts(interfaces, BindInterface.All),
        )
        assertTrue(BindAddresses.needsWarning(BindInterface.All))
        assertFalse(BindAddresses.needsWarning(BindInterface.WiFi))
        assertEquals(emptyList(), BindAddresses.hosts(emptyList(), BindInterface.WiFi))
    }

    @Test
    fun theOutputAsksForTheLocalNetworkPermissionExceptForLocalhost() {
        val request = ConnectionRequest.RequestPermissions(setOf(PermissionKind.LocalNetwork))
        assertEquals(request, OutputRunRule.localNetworkRequest(on))
        assertEquals(request, OutputRunRule.localNetworkRequest(on.copy(bindInterface = BindInterface.All)))
        assertNull(OutputRunRule.localNetworkRequest(on.copy(bindInterface = BindInterface.Localhost)))
        assertEquals(request, OutputRunRule.localNetworkRequest(on.copy(transport = DataOutputTransport.Mqtt, bindInterface = BindInterface.Localhost)))
    }

    @Test
    fun aPortHasOneOwner() {
        val ports = PortRegistry()
        assertNull(ports.claim(5800, "mill"))
        assertNull(ports.claim(5800, "mill"))
        assertEquals("mill", ports.claim(5800, "lathe"))
        ports.release(5800, "lathe")
        assertEquals("mill", ports.claim(5800, "lathe"))
        ports.release(5800, "mill")
        assertNull(ports.claim(5800, "lathe"))
    }
}
