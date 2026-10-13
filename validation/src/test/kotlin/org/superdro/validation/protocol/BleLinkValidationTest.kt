package org.superdro.validation.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import org.superdro.core.connection.ble.BleChunks
import org.superdro.core.connection.ble.NordicUartService

/**
 * The BLE option with the Nordic UART Service. The UI does not
 * offer BLE, and no BLE adapter is available, so only the constants and the chunk
 * rule are checked.
 */
class BleLinkValidationTest {

    @Test
    fun nordic_uart_service_uuids() {
        assertEquals("6E400001-B5A3-F393-E0A9-E50E24DCCA9E", NordicUartService.SERVICE.uppercase())
        assertEquals("6E400002-B5A3-F393-E0A9-E50E24DCCA9E", NordicUartService.RX_CHARACTERISTIC.uppercase())
        assertEquals("6E400003-B5A3-F393-E0A9-E50E24DCCA9E", NordicUartService.TX_CHARACTERISTIC.uppercase())
    }

    @Test
    fun a_write_larger_than_the_mtu_goes_in_chunks() {
        val command = "\"#get:features;\"#get_mode:x;".encodeToByteArray()
        val size = BleChunks.chunkSize(mtu = 23, maxWriteLength = null)
        val chunks = BleChunks.split(command, size)
        assertEquals(listOf(20, command.size - 20), chunks.map { it.size })
        assertContentEquals(command, chunks.reduce { a, b -> a + b })
    }
}
