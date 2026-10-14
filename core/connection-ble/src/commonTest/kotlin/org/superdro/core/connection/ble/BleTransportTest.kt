package org.superdro.core.connection.ble

import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.core.BluetoothManagerState
import dev.bluefalcon.core.CentralCapabilities
import dev.bluefalcon.core.CharacteristicWriteResult
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.LinkFailure
import org.superdro.core.connection.LinkFailureException
import org.superdro.core.model.AdapterAddress

// StandardTestDispatcher gives the transport the virtual time of the test.
@OptIn(ExperimentalCoroutinesApi::class)
class BleTransportTest {

    private val deviceId = "24:6F:28:A1:B2:C3"

    private class Fixture(val engine: FakeNusEngine, val device: FakePeripheral, val transport: BleTransport)

    private fun TestScope.fixture(configure: FakeNusEngine.() -> Unit = {}): Fixture {
        val engine = FakeNusEngine(backgroundScope)
        val device = FakePeripheral(deviceId, "DRO BLE")
        engine.known[deviceId] = device
        engine.configure()
        val transport = BleTransport(AdapterAddress.Ble(deviceId), BlueFalcon(engine), StandardTestDispatcher(testScheduler))
        return Fixture(engine, device, transport)
    }

    @Test
    fun theOpenConnectsAndTurnsOnTheTxNotifications() = runTest {
        val f = fixture()
        f.transport.open()
        assertEquals(listOf(deviceId), f.engine.connectCalls)
        assertEquals(listOf(NordicUartService.txUuid), f.engine.notifyCalls)
        f.transport.close()
    }

    @Test
    fun theNotificationsArriveInOrderAlsoBeforeTheCollection() = runTest {
        val f = fixture()
        f.transport.open()
        f.engine.notify(f.device, "X1234;")
        f.engine.notify(f.device, "Y-5;")
        runCurrent()
        val chunks = f.transport.incoming.take(2).toList().map { bytes -> bytes.decodeToString() }
        assertEquals(listOf("X1234;", "Y-5;"), chunks)
        f.transport.close()
    }

    @Test
    fun aFullNotificationQueueEndsTheLinkAfterTheQueuedNotifications() = runTest {
        val f = fixture()
        f.transport.open()
        val total = BleLinkDefaults.NOTIFICATION_QUEUE + 10
        repeat(total) { index ->
            f.engine.notify(f.device, "X$index;")
            // The engine buffer holds 64 notifications.
            if (index % 32 == 31) runCurrent()
        }
        runCurrent()
        val chunks = ArrayList<String>()
        val failure = assertFailsWith<LinkFailureException> {
            f.transport.incoming.collect { bytes -> chunks += bytes.decodeToString() }
        }
        assertEquals(LinkFailure.Lost, failure.failure)
        // No gap: the collector gets each notification of the queue in order, then the failure.
        assertEquals((0 until BleLinkDefaults.NOTIFICATION_QUEUE).map { index -> "X$index;" }, chunks)
        f.transport.close()
    }

    @Test
    fun aWriteGoesToTheRxCharacteristicInChunksOfTheMtu() = runTest {
        val f = fixture()
        f.transport.open()
        val command = "#set_mode:x,1;#set_mode:y,1;#set_mode:z,1;abc".encodeToByteArray()
        assertEquals(45, command.size)
        f.transport.write(command)
        assertEquals(listOf(20, 20, 5), f.engine.writes.map { (_, bytes) -> bytes.size })
        assertTrue(f.engine.writes.all { (uuid, _) -> uuid == NordicUartService.rxUuid })
        assertContentEquals(command, f.engine.writes.flatMap { (_, bytes) -> bytes.toList() }.toByteArray())
        f.transport.close()
    }

    @Test
    fun aLargerMtuGivesLargerChunks() = runTest {
        val f = fixture { mtuAfterChange = 247 }
        f.transport.open()
        f.transport.write(ByteArray(300) { index -> (index % 100).toByte() })
        assertEquals(listOf(244, 56), f.engine.writes.map { (_, bytes) -> bytes.size })
        f.transport.close()
    }

    @Test
    fun theTypedWriteSplitsAChunkThatTheEngineRefusesAndWaitsForAFullQueue() = runTest {
        val f = fixture {
            capabilities = reliable
            typedWriteResults += CharacteristicWriteResult.Backpressured
            typedWriteResults += CharacteristicWriteResult.PayloadTooLarge(maximumLength = 8)
        }
        f.transport.open()
        f.transport.write("#get:mac;#get:uuid;".encodeToByteArray())
        assertEquals(listOf("#get:mac", ";#get:uu", "id;"), f.engine.writes.map { (_, bytes) -> bytes.decodeToString() })
        f.transport.close()
    }

    @Test
    fun aWriteThatDoesNotEndIn4000MsIsLost() = runTest {
        val f = fixture {
            capabilities = reliable
            repeat(1000) { typedWriteResults += CharacteristicWriteResult.Backpressured }
        }
        f.transport.open()
        val start = testScheduler.currentTime
        val failure = assertFailsWith<LinkFailureException> { f.transport.write(byteArrayOf(0x07)) }
        assertEquals(LinkFailure.Lost, failure.failure)
        assertEquals(4000L, testScheduler.currentTime - start)
        f.transport.close()
    }

    @Test
    fun aWriteOnAClosedEngineLinkIsLost() = runTest {
        val f = fixture {
            capabilities = reliable
            typedWriteResults += CharacteristicWriteResult.Disconnected
        }
        f.transport.open()
        val failure = assertFailsWith<LinkFailureException> { f.transport.write("#get:mac;".encodeToByteArray()) }
        assertEquals(LinkFailure.Lost, failure.failure)
        f.transport.close()
    }

    @Test
    fun aDisconnectThatTheTransportDidNotAskForIsLost() = runTest {
        val f = fixture()
        f.transport.open()
        val reader = async { runCatching { f.transport.incoming.toList() } }
        runCurrent()
        f.engine.drop(f.device)
        val failure = reader.await().exceptionOrNull()
        assertIs<LinkFailureException>(failure)
        assertEquals(LinkFailure.Lost, failure.failure)
        val write = assertFailsWith<LinkFailureException> { f.transport.write(byteArrayOf(0x07)) }
        assertEquals(LinkFailure.Lost, write.failure)
        f.transport.close()
    }

    @Test
    fun aRadioThatGoesOffEndsTheLinkWithRadioOff() = runTest {
        val f = fixture()
        f.transport.open()
        val reader = async { runCatching { f.transport.incoming.toList() } }
        runCurrent()
        f.engine.managerStateFlow.value = BluetoothManagerState.NotReady
        val failure = reader.await().exceptionOrNull()
        assertIs<LinkFailureException>(failure)
        assertEquals(LinkFailure.RadioOff, failure.failure)
        f.transport.close()
    }

    @Test
    fun theCloseEndsTheFlowAndDisconnects() = runTest {
        val f = fixture()
        f.transport.open()
        val reader = async { f.transport.incoming.toList() }
        runCurrent()
        f.transport.close()
        f.transport.close()
        assertEquals(emptyList<ByteArray>(), reader.await())
        assertEquals(listOf(deviceId), f.engine.disconnectCalls)
        val write = assertFailsWith<LinkFailureException> { f.transport.write(byteArrayOf(0x07)) }
        assertEquals(LinkFailure.Lost, write.failure)
    }

    @Test
    fun aRadioThatIsOffIsRadioOff() = runTest {
        val f = fixture { managerStateFlow.value = BluetoothManagerState.NotReady }
        assertEquals(LinkFailure.RadioOff, assertFailsWith<LinkFailureException> { f.transport.open() }.failure)
        assertTrue(f.engine.connectCalls.isEmpty())
    }

    @Test
    fun anUnknownDeviceIsDeviceGone() = runTest {
        val f = fixture { known.clear() }
        assertEquals(LinkFailure.DeviceGone, assertFailsWith<LinkFailureException> { f.transport.open() }.failure)
    }

    @Test
    fun aRefusedPermissionIsPermissionMissing() = runTest {
        val f = fixture { connectBehavior = ConnectBehavior.PermissionDenied }
        assertEquals(LinkFailure.PermissionMissing, assertFailsWith<LinkFailureException> { f.transport.open() }.failure)
    }

    @Test
    fun aConnectThatDoesNotEndIsRefusedAfterTheOpenLimit() = runTest {
        val f = fixture { connectBehavior = ConnectBehavior.NeverConnect }
        val failure = assertFailsWith<LinkFailureException> { f.transport.open() }
        assertEquals(LinkFailure.Refused, failure.failure)
        assertEquals(BleLinkDefaults.OPEN_TIMEOUT_MS, testScheduler.currentTime)
        assertEquals(listOf(deviceId), f.engine.disconnectCalls)
    }

    @Test
    fun aDeviceWithNoNordicUartServiceIsRefused() = runTest {
        val f = fixture { deviceServices = { listOf(FakeService(FakeNusEngine.batteryService, emptyList())) } }
        val failure = assertFailsWith<LinkFailureException> { f.transport.open() }
        assertEquals(LinkFailure.Refused, failure.failure)
        assertTrue(testScheduler.currentTime < BleLinkDefaults.OPEN_TIMEOUT_MS)
        assertEquals(listOf(deviceId), f.engine.disconnectCalls)
    }

    @Test
    fun theFactoryMakesBleTransportsOnly() = runTest {
        val factory = BleTransportFactory(BlueFalcon(FakeNusEngine(backgroundScope)))
        assertEquals(AdapterAddress.Ble(deviceId), factory.create(AdapterAddress.Ble(deviceId)).address)
        assertFailsWith<IllegalArgumentException> { factory.create(AdapterAddress.Tcp("dro.local")) }
    }

    private val reliable = CentralCapabilities(
        reliableWriteResults = true,
        writeWithoutResponseReadiness = true,
        perConnectionMaximumWriteLength = true,
        notificationSubscriptionResults = true,
        restoration = false,
    )
}
