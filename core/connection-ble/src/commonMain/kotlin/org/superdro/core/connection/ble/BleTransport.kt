package org.superdro.core.connection.ble

import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.core.BluetoothCharacteristic
import dev.bluefalcon.core.BluetoothManagerState
import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.core.BluetoothService
import dev.bluefalcon.core.CharacteristicWriteResult
import dev.bluefalcon.core.CharacteristicWriteType
import dev.bluefalcon.core.DisconnectReason
import dev.bluefalcon.core.NotificationSubscriptionResult
import dev.bluefalcon.core.PeripheralConnectionState
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.superdro.core.connection.LinkFailure
import org.superdro.core.connection.LinkFailureException
import org.superdro.core.connection.Transport
import org.superdro.core.model.AdapterAddress

/**
 * The BLE link to one adapter with the Nordic UART Service.
 * The adapter must have a firmware with this service. The present TouchDRO compatible adapters have no BLE.
 *
 * - [open] connects with BlueFalcon, finds the service and its two characteristics, asks for a
 *   large MTU, and turns on the notifications of [NordicUartService.TX_CHARACTERISTIC]. All of this
 *   has the limit [BleLinkDefaults.OPEN_TIMEOUT_MS].
 * - [incoming] gives the payload of each notification, in the order of arrival. The transport
 *   keeps the notifications that arrive before the collection starts, up to
 *   [BleLinkDefaults.NOTIFICATION_QUEUE]. When the collector is so late that the queue is full,
 *   [incoming] gives the notifications of the queue and then ends with [LinkFailure.Lost].
 * - [write] writes to [NordicUartService.RX_CHARACTERISTIC] in chunks of the MTU less 3 bytes
 *   ([BleChunks]), with the limit [BleLinkDefaults.WRITE_TIMEOUT_MS] for all chunks.
 * - A disconnect that the transport did not ask for ends [incoming] with [LinkFailure.Lost], or
 *   with [LinkFailure.RadioOff] when the radio went off.
 *
 * The failures of [open]: [LinkFailure.RadioOff] (the radio is off), [LinkFailure.DeviceGone] (the
 * engine does not know the address), [LinkFailure.PermissionMissing], and [LinkFailure.Refused]
 * (the connect failed, timed out, or the device has no Nordic UART Service).
 *
 * @param blueFalcon The BlueFalcon client. The Android engine is
 *   `AndroidEngine(application, logger = ..., autoDiscoverAllServicesAndCharacteristics = true)`.
 * @param dispatcher The dispatcher of the internal collectors.
 */
class BleTransport(
    override val address: AdapterAddress.Ble,
    private val blueFalcon: BlueFalcon,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : Transport {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    // A bounded queue. The notification collector must not suspend: a collector that waits holds
    // the shared notification flow of the engine, and the engine then drops or holds the
    // notifications of all devices. A dropped notification makes a gap in the byte stream,
    // and a gap can join the start of one message to the end of a different message. That gives
    // a wrong position with no error. Thus a full queue ends the link: the session then opens a
    // new link with a new parser.
    private val received = Channel<ByteArray>(BleLinkDefaults.NOTIFICATION_QUEUE)
    private val stateLock = Mutex()
    private val writeLock = Mutex()

    @Volatile
    private var peripheral: BluetoothPeripheral? = null

    @Volatile
    private var rx: BluetoothCharacteristic? = null

    @Volatile
    private var openStarted = false

    @Volatile
    private var opened = false

    @Volatile
    private var closed = false

    @Volatile
    private var failure: LinkFailure? = null

    override val incoming: Flow<ByteArray> = received.receiveAsFlow()

    override suspend fun open() {
        stateLock.withLock {
            check(!openStarted) { "A BleTransport opens only one time. Make a new transport for each link." }
            openStarted = true
        }
        if (closed) throw LinkFailureException(LinkFailure.Refused, "The BLE transport for ${address.id} is closed.")
        if (blueFalcon.managerState.value != BluetoothManagerState.Ready) {
            throw LinkFailureException(LinkFailure.RadioOff, "The Bluetooth radio is not ready.")
        }
        val target = findPeripheral()
            ?: throw LinkFailureException(LinkFailure.DeviceGone, "No BLE device has the ID ${address.id}.")
        peripheral = target
        try {
            withTimeoutOrNull(BleLinkDefaults.OPEN_TIMEOUT_MS) { connectAndPrepare(target) }
                ?: throw LinkFailureException(
                    LinkFailure.Refused,
                    "The BLE open of ${address.id} did not end in ${BleLinkDefaults.OPEN_TIMEOUT_MS} ms.",
                )
        } catch (openFailure: Throwable) {
            disconnectQuietly(target)
            if (openFailure is LinkFailureException) throw openFailure
            currentCoroutineContext().ensureActive()
            throw LinkFailureException(bleFailureOf(openFailure), "The BLE open of ${address.id} failed.", openFailure)
        }
        stateLock.withLock {
            if (closed) {
                disconnectQuietly(target)
                throw LinkFailureException(LinkFailure.Refused, "The BLE transport closed during the open.")
            }
            opened = true
        }
        watchLink(target)
    }

    override suspend fun write(bytes: ByteArray) {
        writeLock.withLock {
            failure?.let { cause -> throw LinkFailureException(cause, "The BLE link to ${address.id} failed.") }
            val target = peripheral
            val characteristic = rx
            if (!opened || closed || target == null || characteristic == null) {
                throw LinkFailureException(LinkFailure.Lost, "The BLE link to ${address.id} is not open.")
            }
            if (bytes.isEmpty()) return
            val size = BleChunks.chunkSize(
                mtu = current(target).mtuSize,
                maxWriteLength = blueFalcon.maximumWriteValueLength(target, CharacteristicWriteType.WithResponse),
            )
            val written = try {
                withTimeoutOrNull(BleLinkDefaults.WRITE_TIMEOUT_MS) {
                    BleChunks.split(bytes, size).forEach { chunk -> writeChunk(target, characteristic, chunk) }
                    true
                }
            } catch (writeFailure: Throwable) {
                if (writeFailure is LinkFailureException) throw writeFailure
                currentCoroutineContext().ensureActive()
                throw LinkFailureException(LinkFailure.Lost, "The BLE write to ${address.id} failed.", writeFailure)
            }
            if (written == null) {
                throw LinkFailureException(
                    LinkFailure.Lost,
                    "The BLE write to ${address.id} did not end in ${BleLinkDefaults.WRITE_TIMEOUT_MS} ms.",
                )
            }
        }
    }

    override suspend fun close() {
        val target = stateLock.withLock {
            if (closed) return
            closed = true
            peripheral
        }
        received.close()
        scope.cancel()
        if (target != null && openStarted) disconnectQuietly(target)
    }

    private fun findPeripheral(): BluetoothPeripheral? {
        val id = address.id
        if (id.isBlank()) return null
        return blueFalcon.peripherals.value.firstOrNull { known -> known.uuid.equals(id, ignoreCase = true) }
            ?: runCatching { blueFalcon.retrievePeripheral(id) }.getOrNull()
    }

    /** The engine can replace the peripheral object after a scan. This gives the newest one. */
    private fun current(target: BluetoothPeripheral): BluetoothPeripheral =
        blueFalcon.peripherals.value.firstOrNull { known -> known.uuid == target.uuid } ?: target

    private fun stateOf(target: BluetoothPeripheral): Flow<PeripheralConnectionState> =
        blueFalcon.connectionStates
            .map { states -> states[target.uuid] ?: PeripheralConnectionState.Disconnected() }
            .distinctUntilChanged()

    private suspend fun connectAndPrepare(target: BluetoothPeripheral) {
        blueFalcon.connect(target, autoConnect = false)
        val state = stateOf(target).first { state ->
            state is PeripheralConnectionState.Connected ||
                state is PeripheralConnectionState.Ready ||
                state is PeripheralConnectionState.Disconnected
        }
        if (state is PeripheralConnectionState.Disconnected) {
            val reason = state.reason
            if (reason is DisconnectReason.ConnectFailed) {
                throw LinkFailureException(bleFailureOf(reason.cause), "The BLE connect to ${address.id} failed.", reason.cause)
            }
            throw LinkFailureException(LinkFailure.Refused, "The BLE connect to ${address.id} failed.")
        }
        val service = awaitService(target)
        val (rxCharacteristic, txCharacteristic) = awaitCharacteristics(target, service)
        negotiateMtu(target)
        startNotifications(target, txCharacteristic)
        rx = rxCharacteristic
    }

    /**
     * Waits for the Nordic UART Service. The Android engine discovers the services by itself after
     * the connect, and the facade then reports [PeripheralConnectionState.Ready]. When that does not
     * occur, the transport asks for a discovery. A service list with no Nordic UART Service is
     * [LinkFailure.Refused]: the device is not an adapter of this type.
     */
    private suspend fun awaitService(target: BluetoothPeripheral): BluetoothService {
        nordicService(target)?.let { service -> return service }
        withTimeoutOrNull(BleLinkDefaults.SERVICE_WAIT_MS) {
            stateOf(target).first { state -> state is PeripheralConnectionState.Ready }
        }
        nordicService(target)?.let { service -> return service }
        refuseWithoutNordicService(target)
        blueFalcon.discoverServices(current(target), listOf(NordicUartService.serviceUuid))
        while (true) {
            nordicService(target)?.let { service -> return service }
            refuseWithoutNordicService(target)
            checkConnected(target)
            delay(BleLinkDefaults.POLL_INTERVAL_MS)
        }
    }

    private fun refuseWithoutNordicService(target: BluetoothPeripheral) {
        if (current(target).services.isNotEmpty()) {
            throw LinkFailureException(LinkFailure.Refused, "The device ${address.id} has no Nordic UART Service.")
        }
    }

    private fun nordicService(target: BluetoothPeripheral): BluetoothService? =
        current(target).services.firstOrNull { service -> service.uuid == NordicUartService.serviceUuid }

    private suspend fun awaitCharacteristics(
        target: BluetoothPeripheral,
        service: BluetoothService,
    ): Pair<BluetoothCharacteristic, BluetoothCharacteristic> {
        characteristicsOf(service)?.let { pair -> return pair }
        blueFalcon.discoverCharacteristics(
            current(target),
            service,
            listOf(NordicUartService.rxUuid, NordicUartService.txUuid),
        )
        while (true) {
            val newest = nordicService(target) ?: service
            characteristicsOf(newest)?.let { pair -> return pair }
            if (newest.characteristics.isNotEmpty()) {
                throw LinkFailureException(
                    LinkFailure.Refused,
                    "The device ${address.id} has no RX and TX characteristics of the Nordic UART Service.",
                )
            }
            checkConnected(target)
            delay(BleLinkDefaults.POLL_INTERVAL_MS)
        }
    }

    private fun characteristicsOf(service: BluetoothService): Pair<BluetoothCharacteristic, BluetoothCharacteristic>? {
        val rxCharacteristic = service.characteristics.firstOrNull { it.uuid == NordicUartService.rxUuid }
        val txCharacteristic = service.characteristics.firstOrNull { it.uuid == NordicUartService.txUuid }
        return if (rxCharacteristic != null && txCharacteristic != null) rxCharacteristic to txCharacteristic else null
    }

    private suspend fun checkConnected(target: BluetoothPeripheral) {
        if (stateOf(target).first() is PeripheralConnectionState.Disconnected) {
            throw LinkFailureException(LinkFailure.Refused, "The BLE link to ${address.id} closed during the open.")
        }
    }

    private suspend fun negotiateMtu(target: BluetoothPeripheral) {
        try {
            blueFalcon.changeMTU(current(target), BleLinkDefaults.REQUESTED_MTU)
        } catch (mtuFailure: Throwable) {
            currentCoroutineContext().ensureActive()
            // Not all engines can change the MTU. The default MTU gives chunks of 20 bytes.
            return
        }
        withTimeoutOrNull(BleLinkDefaults.MTU_WAIT_MS) {
            while (current(target).mtuSize == null) delay(BleLinkDefaults.POLL_INTERVAL_MS)
        }
    }

    private suspend fun startNotifications(target: BluetoothPeripheral, txCharacteristic: BluetoothCharacteristic) {
        val uuid = target.uuid
        // UNDISPATCHED: the collector subscribes before the notifications start, so no byte goes away.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            blueFalcon.engine.characteristicNotifications.collect { notification ->
                if (notification.peripheral.uuid == uuid && notification.characteristic.uuid == NordicUartService.txUuid) {
                    val result = received.trySend(notification.value.copyOf())
                    if (result.isFailure && !result.isClosed) {
                        fail(
                            LinkFailure.Lost,
                            "The BLE link to ${address.id} ended: ${BleLinkDefaults.NOTIFICATION_QUEUE} notifications " +
                                "wait for the collector, so the transport cannot keep the next notification.",
                        )
                    }
                }
            }
        }
        val device = current(target)
        if (!blueFalcon.centralCapabilities.notificationSubscriptionResults) {
            blueFalcon.notifyCharacteristic(device, txCharacteristic, true)
            return
        }
        when (val result = blueFalcon.setNotificationSubscription(device, txCharacteristic, true)) {
            is NotificationSubscriptionResult.Updated -> if (!result.enabled) {
                throw LinkFailureException(LinkFailure.Refused, "The notifications of ${address.id} stayed off.")
            }
            NotificationSubscriptionResult.Unsupported -> blueFalcon.notifyCharacteristic(device, txCharacteristic, true)
            NotificationSubscriptionResult.Disconnected ->
                throw LinkFailureException(LinkFailure.Refused, "The BLE link to ${address.id} closed during the open.")
            is NotificationSubscriptionResult.Failed ->
                throw LinkFailureException(
                    result.cause?.let { cause -> bleFailureOf(cause) } ?: LinkFailure.Refused,
                    "The notifications of ${address.id} did not start.",
                    result.cause,
                )
        }
    }

    private fun watchLink(target: BluetoothPeripheral) {
        scope.launch {
            stateOf(target).first { state -> state is PeripheralConnectionState.Disconnected }
            fail(if (blueFalcon.managerState.value == BluetoothManagerState.Ready) LinkFailure.Lost else LinkFailure.RadioOff)
        }
        scope.launch {
            blueFalcon.managerState.first { state -> state != BluetoothManagerState.Ready }
            fail(LinkFailure.RadioOff)
        }
    }

    private suspend fun fail(cause: LinkFailure, message: String = "The BLE link to ${address.id} ended.") {
        stateLock.withLock {
            if (closed || failure != null) return
            failure = cause
        }
        received.close(LinkFailureException(cause, message))
    }

    private suspend fun writeChunk(target: BluetoothPeripheral, characteristic: BluetoothCharacteristic, chunk: ByteArray) {
        if (!blueFalcon.centralCapabilities.reliableWriteResults) {
            blueFalcon.writeCharacteristic(target, characteristic, chunk)
            return
        }
        while (true) {
            when (val result = blueFalcon.writeCharacteristic(target, characteristic, chunk, CharacteristicWriteType.WithResponse)) {
                CharacteristicWriteResult.Sent -> return
                CharacteristicWriteResult.Backpressured -> delay(BleLinkDefaults.BACKPRESSURE_RETRY_MS)
                is CharacteristicWriteResult.PayloadTooLarge -> {
                    val maximum = result.maximumLength
                    if (maximum <= 0 || maximum >= chunk.size) {
                        throw LinkFailureException(LinkFailure.Lost, "The engine refused a chunk of ${chunk.size} bytes.")
                    }
                    BleChunks.split(chunk, maximum).forEach { part -> writeChunk(target, characteristic, part) }
                    return
                }
                CharacteristicWriteResult.Unsupported -> {
                    blueFalcon.writeCharacteristic(target, characteristic, chunk)
                    return
                }
                CharacteristicWriteResult.Disconnected ->
                    throw LinkFailureException(LinkFailure.Lost, "The BLE link to ${address.id} is closed.")
                is CharacteristicWriteResult.Failed ->
                    throw LinkFailureException(LinkFailure.Lost, "The BLE write to ${address.id} failed.", result.cause)
            }
        }
    }

    private suspend fun disconnectQuietly(target: BluetoothPeripheral) {
        withContext(NonCancellable) {
            withTimeoutOrNull(BleLinkDefaults.DISCONNECT_TIMEOUT_MS) {
                try {
                    blueFalcon.disconnect(current(target))
                } catch (_: CancellationException) {
                    // The facade is closed. Nothing is left to disconnect.
                } catch (_: Exception) {
                    // A failed disconnect leaves nothing to do: the link is gone or the engine stopped.
                }
            }
        }
    }
}
