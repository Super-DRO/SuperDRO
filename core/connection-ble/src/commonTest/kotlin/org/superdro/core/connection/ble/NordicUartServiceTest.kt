package org.superdro.core.connection.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import org.superdro.core.model.TransportKind

class NordicUartServiceTest {

    @Test
    fun theThreeUuidsShareTheNordicBase() {
        listOf(NordicUartService.SERVICE, NordicUartService.RX_CHARACTERISTIC, NordicUartService.TX_CHARACTERISTIC)
            .forEach { uuid -> assertEquals("-b5a3-f393-e0a9-e50e24dcca9e", uuid.substring(8)) }
        assertEquals(TransportKind.Ble, NordicUartService.kind)
        assertEquals("6E400001-B5A3-F393-E0A9-E50E24DCCA9E", NordicUartService.serviceUuid.toString().uppercase())
        assertEquals("6E400002-B5A3-F393-E0A9-E50E24DCCA9E", NordicUartService.rxUuid.toString().uppercase())
        assertEquals("6E400003-B5A3-F393-E0A9-E50E24DCCA9E", NordicUartService.txUuid.toString().uppercase())
    }
}
