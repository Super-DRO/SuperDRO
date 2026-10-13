package org.superdro.core.connection.ble

import dev.bluefalcon.core.BluetoothManagerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.superdro.core.connection.LinkCapability
import org.superdro.core.connection.LinkFailure

class BleChunksTest {

    @Test
    fun theChunkIsTheMtuLessTheAttHeader() {
        assertEquals(20, BleChunks.chunkSize(mtu = null, maxWriteLength = null))
        assertEquals(20, BleChunks.chunkSize(mtu = 23, maxWriteLength = null))
        assertEquals(244, BleChunks.chunkSize(mtu = 247, maxWriteLength = null))
        assertEquals(512, BleChunks.chunkSize(mtu = 517, maxWriteLength = 512))
        assertEquals(100, BleChunks.chunkSize(mtu = 247, maxWriteLength = 100))
        assertEquals(180, BleChunks.chunkSize(mtu = null, maxWriteLength = 180))
        assertEquals(20, BleChunks.chunkSize(mtu = 0, maxWriteLength = -1))
    }

    @Test
    fun aWriteLargerThanTheChunkGoesInChunks() {
        val bytes = ByteArray(45) { index -> index.toByte() }
        val chunks = BleChunks.split(bytes, 20)
        assertEquals(listOf(20, 20, 5), chunks.map { chunk -> chunk.size })
        assertEquals(bytes.toList(), chunks.flatMap { chunk -> chunk.toList() })
        assertEquals(listOf(9), BleChunks.split("#get:mac;".encodeToByteArray(), 20).map { chunk -> chunk.size })
        assertEquals(listOf(20), BleChunks.split(ByteArray(20), 20).map { chunk -> chunk.size })
        assertEquals(emptyList(), BleChunks.split(ByteArray(0), 20).map { chunk -> chunk.size })
        assertFailsWith<IllegalArgumentException> { BleChunks.split(bytes, 0) }
    }

    @Test
    fun theRadioStateGivesTheLinkCapability() {
        assertEquals(LinkCapability.Available, BluetoothManagerState.Ready.toLinkCapability())
        assertEquals(LinkCapability.Unavailable(LinkFailure.RadioOff), BluetoothManagerState.NotReady.toLinkCapability())
    }

    @Test
    fun theBlueFalconFailuresGiveTheLinkFailures() {
        assertEquals(LinkFailure.PermissionMissing, bleFailureOf(dev.bluefalcon.core.BluetoothPermissionException()))
        assertEquals(LinkFailure.RadioOff, bleFailureOf(dev.bluefalcon.core.BluetoothNotEnabledException()))
        assertEquals(LinkFailure.RadioOff, bleFailureOf(dev.bluefalcon.core.BluetoothUnsupportedException()))
        assertEquals(LinkFailure.RadioOff, bleFailureOf(dev.bluefalcon.core.BluetoothResettingException()))
        assertEquals(LinkFailure.Refused, bleFailureOf(dev.bluefalcon.core.BluetoothUnknownException()))
        assertEquals(LinkFailure.Lost, bleFailureOf(IllegalStateException(), fallback = LinkFailure.Lost))
    }
}
