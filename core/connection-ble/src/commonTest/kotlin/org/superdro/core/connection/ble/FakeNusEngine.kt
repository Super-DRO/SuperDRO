package org.superdro.core.connection.ble

import dev.bluefalcon.core.BlueFalconEngine
import dev.bluefalcon.core.BluetoothCharacteristic
import dev.bluefalcon.core.BluetoothCharacteristicDescriptor
import dev.bluefalcon.core.BluetoothManagerState
import dev.bluefalcon.core.BluetoothPermissionException
import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.core.BluetoothPeripheralState
import dev.bluefalcon.core.BluetoothService
import dev.bluefalcon.core.BluetoothSocket
import dev.bluefalcon.core.CentralCapabilities
import dev.bluefalcon.core.CharacteristicNotification
import dev.bluefalcon.core.CharacteristicWriteResult
import dev.bluefalcon.core.CharacteristicWriteType
import dev.bluefalcon.core.ConnectionPriority
import dev.bluefalcon.core.ConnectionStateUpdate
import dev.bluefalcon.core.NotificationSubscriptionResult
import dev.bluefalcon.core.ServiceDiscoveryPhase
import dev.bluefalcon.core.ServiceDiscoveryUpdate
import dev.bluefalcon.core.ServiceFilter
import dev.bluefalcon.core.Uuid
import dev.bluefalcon.core.toUuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** A BLE peripheral of the tests. The engine changes [services] and [mtuSize] as a real engine does. */
internal class FakePeripheral(
    override val uuid: String,
    override val name: String? = null,
    override val advertisedServiceUUIDs: List<Uuid> = emptyList(),
) : BluetoothPeripheral {
    override val rssi: Float? = null
    override var mtuSize: Int? = null
    override var services: List<BluetoothService> = emptyList()
    override val characteristics: List<BluetoothCharacteristic> get() = services.flatMap { service -> service.characteristics }
}

internal class FakeService(override val uuid: Uuid, characteristicUuids: List<Uuid>) : BluetoothService {
    override val name: String? = null
    override val characteristics: List<BluetoothCharacteristic> = characteristicUuids.map { uuid -> FakeCharacteristic(uuid, this) }
}

internal class FakeCharacteristic(override val uuid: Uuid, override val service: BluetoothService?) : BluetoothCharacteristic {
    override val name: String? = null
    override val value: ByteArray? = null
    override val notifications: SharedFlow<ByteArray> = MutableSharedFlow()
    override val descriptors: List<BluetoothCharacteristicDescriptor> = emptyList()
    override val isNotifying: Boolean = false
}

/** What the fake engine does on a connect. */
internal enum class ConnectBehavior {
    /** The link opens after 100 ms, and the services follow. */
    Succeed,

    /** The link never opens. */
    NeverConnect,

    /** The connect throws a permission failure. */
    PermissionDenied,
}

/**
 * A [BlueFalconEngine] that acts as one BLE adapter with the Nordic UART Service. It keeps each
 * call, so that a test can examine the chunks, the subscriptions, and the disconnects.
 */
internal class FakeNusEngine(override val scope: CoroutineScope) : BlueFalconEngine {

    val peripheralsFlow = MutableStateFlow<Set<BluetoothPeripheral>>(emptySet())
    override val peripherals: StateFlow<Set<BluetoothPeripheral>> get() = peripheralsFlow

    val managerStateFlow = MutableStateFlow(BluetoothManagerState.Ready)
    override val managerState: StateFlow<BluetoothManagerState> get() = managerStateFlow

    private val notifications = MutableSharedFlow<CharacteristicNotification>(extraBufferCapacity = 64)
    override val characteristicNotifications: SharedFlow<CharacteristicNotification> get() = notifications

    private val connectionUpdates = MutableSharedFlow<ConnectionStateUpdate>(extraBufferCapacity = 64)
    override val connectionStateUpdates: SharedFlow<ConnectionStateUpdate> get() = connectionUpdates

    private val discoveryUpdates = MutableSharedFlow<ServiceDiscoveryUpdate>(extraBufferCapacity = 64)
    override val serviceDiscoveryUpdates: SharedFlow<ServiceDiscoveryUpdate> get() = discoveryUpdates

    /** Set the capabilities to [CentralCapabilities] with `reliableWriteResults` to use the typed writes. */
    var capabilities: CentralCapabilities = CentralCapabilities.None
    override val centralCapabilities: CentralCapabilities get() = capabilities

    override val isScanning: Boolean = false

    /** The devices that [retrievePeripheral] knows. */
    val known = mutableMapOf<String, FakePeripheral>()

    var connectBehavior = ConnectBehavior.Succeed

    /** The services that the device gives after the connect. */
    var deviceServices: () -> List<BluetoothService> = {
        listOf(FakeService(NordicUartService.serviceUuid, listOf(NordicUartService.rxUuid, NordicUartService.txUuid)))
    }

    /** The MTU after a `changeMTU`. Null: the MTU exchange does not end. */
    var mtuAfterChange: Int? = null

    /** The value of `maximumWriteValueLength`. */
    var maxWriteLength: Int? = null

    /** The next results of the typed writes. An empty queue gives [CharacteristicWriteResult.Sent]. */
    val typedWriteResults = ArrayDeque<CharacteristicWriteResult>()

    /** Each accepted write: the characteristic and the bytes. */
    val writes = mutableListOf<Pair<Uuid, ByteArray>>()

    val connectCalls = mutableListOf<String>()
    val disconnectCalls = mutableListOf<String>()
    val notifyCalls = mutableListOf<Uuid>()
    val discoverServicesCalls = mutableListOf<List<Uuid>>()
    val scanFilters = mutableListOf<List<ServiceFilter>>()
    var stopScanCount = 0

    /** Sends [text] as one notification of the TX characteristic of [device]. */
    fun notify(device: FakePeripheral, text: String) {
        val tx = device.services.first().characteristics.first { characteristic -> characteristic.uuid == NordicUartService.txUuid }
        notifications.tryEmit(CharacteristicNotification(device, tx, text.encodeToByteArray()))
    }

    /** Ends the link of [device] as a lost radio link does. */
    fun drop(device: FakePeripheral) {
        connectionUpdates.tryEmit(ConnectionStateUpdate(device, BluetoothPeripheralState.Disconnected))
    }

    override suspend fun scan(filters: List<ServiceFilter>) {
        scanFilters += filters
    }

    override suspend fun stopScanning() {
        stopScanCount++
    }

    override fun clearPeripherals() {}

    override suspend fun connect(peripheral: BluetoothPeripheral, autoConnect: Boolean) {
        connectCalls += peripheral.uuid
        val device = peripheral as FakePeripheral
        when (connectBehavior) {
            ConnectBehavior.NeverConnect -> Unit
            ConnectBehavior.PermissionDenied -> throw BluetoothPermissionException()
            ConnectBehavior.Succeed -> scope.launch {
                delay(100)
                connectionUpdates.emit(ConnectionStateUpdate(device, BluetoothPeripheralState.Connected))
                delay(100)
                device.services = deviceServices()
                discoveryUpdates.emit(ServiceDiscoveryUpdate(device, ServiceDiscoveryPhase.ServicesDiscovered))
            }
        }
    }

    override suspend fun disconnect(peripheral: BluetoothPeripheral) {
        disconnectCalls += peripheral.uuid
        connectionUpdates.emit(ConnectionStateUpdate(peripheral, BluetoothPeripheralState.Disconnected))
    }

    override fun connectionState(peripheral: BluetoothPeripheral): BluetoothPeripheralState = BluetoothPeripheralState.Unknown

    override fun retrievePeripheral(identifier: String): BluetoothPeripheral? = known[identifier]

    override fun requestConnectionPriority(peripheral: BluetoothPeripheral, priority: ConnectionPriority) {}

    override suspend fun discoverServices(peripheral: BluetoothPeripheral, serviceUUIDs: List<Uuid>) {
        discoverServicesCalls += serviceUUIDs
    }

    override suspend fun discoverCharacteristics(
        peripheral: BluetoothPeripheral,
        service: BluetoothService,
        characteristicUUIDs: List<Uuid>,
    ) {}

    override suspend fun readCharacteristic(peripheral: BluetoothPeripheral, characteristic: BluetoothCharacteristic): ByteArray? = null

    override suspend fun writeCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: String,
        writeType: Int?,
    ) {
        writes += characteristic.uuid to value.encodeToByteArray()
    }

    override suspend fun writeCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?,
    ) {
        writes += characteristic.uuid to value.copyOf()
    }

    override suspend fun writeCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: CharacteristicWriteType,
    ): CharacteristicWriteResult {
        val result = typedWriteResults.removeFirstOrNull() ?: CharacteristicWriteResult.Sent
        if (result == CharacteristicWriteResult.Sent) writes += characteristic.uuid to value.copyOf()
        return result
    }

    override suspend fun setNotificationSubscription(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        enabled: Boolean,
    ): NotificationSubscriptionResult {
        notifyCalls += characteristic.uuid
        return NotificationSubscriptionResult.Updated(enabled)
    }

    override fun maximumWriteValueLength(peripheral: BluetoothPeripheral, writeType: CharacteristicWriteType): Int? = maxWriteLength

    override suspend fun notifyCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        notify: Boolean,
    ) {
        notifyCalls += characteristic.uuid
    }

    override suspend fun indicateCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        indicate: Boolean,
    ) {}

    override suspend fun readDescriptor(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        descriptor: BluetoothCharacteristicDescriptor,
    ) {}

    override suspend fun writeDescriptor(
        peripheral: BluetoothPeripheral,
        descriptor: BluetoothCharacteristicDescriptor,
        value: ByteArray,
    ) {}

    override suspend fun changeMTU(peripheral: BluetoothPeripheral, mtuSize: Int) {
        val device = peripheral as FakePeripheral
        mtuAfterChange?.let { mtu -> device.mtuSize = mtu }
    }

    override fun refreshGattCache(peripheral: BluetoothPeripheral): Boolean = false

    override suspend fun openL2capChannel(peripheral: BluetoothPeripheral, psm: Int, secure: Boolean): BluetoothSocket =
        error("The tests do not use L2CAP.")

    override suspend fun createBond(peripheral: BluetoothPeripheral) {}

    override suspend fun removeBond(peripheral: BluetoothPeripheral) {}

    companion object {
        /** A service of a different type, for the test of a device with no Nordic UART Service. */
        val batteryService: Uuid = "180F".toUuid()
    }
}
