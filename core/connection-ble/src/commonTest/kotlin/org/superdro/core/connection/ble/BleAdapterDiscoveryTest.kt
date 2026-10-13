package org.superdro.core.connection.ble

import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.core.ServiceFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.DiscoveredAdapter
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.TransportKind

// advanceUntilIdle lets the scan stop after the collection ends.
@OptIn(ExperimentalCoroutinesApi::class)
class BleAdapterDiscoveryTest {

    @Test
    fun theScanListsTheNordicUartDevicesWhileTheListIsCollected() = runTest {
        val engine = FakeNusEngine(backgroundScope)
        val discovery = BleAdapterDiscovery(BlueFalcon(engine))
        assertEquals(TransportKind.Ble, discovery.kind)
        engine.peripheralsFlow.value = setOf(
            FakePeripheral("AA:00:00:00:00:02", "Mill DRO", listOf(NordicUartService.serviceUuid)),
            FakePeripheral("AA:00:00:00:00:03", "Heart rate", listOf(FakeNusEngine.batteryService)),
            FakePeripheral("AA:00:00:00:00:01", null),
        )

        val list = discovery.adapters.first { adapters -> adapters.isNotEmpty() }
        advanceUntilIdle()

        assertEquals(
            listOf(
                DiscoveredAdapter(AdapterAddress.Ble("AA:00:00:00:00:01"), "AA:00:00:00:00:01", "AA:00:00:00:00:01"),
                DiscoveredAdapter(AdapterAddress.Ble("AA:00:00:00:00:02"), "Mill DRO", "AA:00:00:00:00:02"),
            ),
            list,
        )
        assertEquals(listOf(listOf(ServiceFilter(NordicUartService.serviceUuid))), engine.scanFilters)
        assertEquals(1, engine.stopScanCount)
    }
}
