package org.superdro.platform.android.camera

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.SystemClock
import android.view.Surface
import androidx.core.content.ContextCompat
import com.toxicbakery.logging.Arbor
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.superdro.core.camera.CameraAccessController
import org.superdro.core.camera.CameraCodes
import org.superdro.core.camera.CameraDeviceEvent
import org.superdro.core.camera.CameraError
import org.superdro.core.camera.CameraErrorKind
import org.superdro.core.camera.CameraException
import org.superdro.core.camera.CameraIdentity
import org.superdro.core.camera.CameraPermissionRequester
import org.superdro.core.camera.CameraStarter
import org.superdro.core.camera.CameraTimeouts
import org.superdro.core.camera.StreamEvent
import org.superdro.core.camera.SurfaceVideoTarget
import org.superdro.core.camera.UvcCameraBackend
import org.superdro.core.camera.UvcMode
import org.superdro.core.camera.UvcModeList
import org.superdro.core.camera.VideoTarget
import org.superdro.core.camera.toBackendResult
import org.superdro.core.util.MonotonicClock

/**
 * The Android implementation of the USB camera back end. The UI process opens the USB device and
 * sends a copy of its file descriptor and the `Surface` to the camera service in the process
 * `:camera`. The video frames stay in the camera process.
 *
 * - Discovery: `UsbManager.getDeviceList()` and the interface class 14. An attach and detach
 *   receiver keeps [cameras] current.
 * - Access: [CameraAccessController] of `core:camera` on one thread, with
 *   [AndroidCameraAccessPlatform]: first `CAMERA` through [permissionRequester], then the USB
 *   permission.
 * - Requests: tokens and time limits (8000 ms for the mode list, 20000 ms for the start).
 *
 * @param permissionRequester The hook for `CAMERA` (the request bus of the app).
 */
class AndroidUvcCameraBackend(
    context: Context,
    permissionRequester: CameraPermissionRequester,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : UvcCameraBackend {

    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val remote = CameraRemote(appContext, scope)
    private val accessDispatcher = Executors.newSingleThreadExecutor { task -> Thread(task, "superdro-camera-access") }
        .asCoroutineDispatcher()
    private val opsDispatcher = Executors.newSingleThreadExecutor { task -> Thread(task, "superdro-camera-ops") }
        .asCoroutineDispatcher()
    private val connectionIds = AtomicInteger(0)
    private val connections = mutableMapOf<String, OpenConnection>()
    private var lastAttached: CameraIdentity? = null

    private val _cameras = MutableStateFlow(UvcDevices.cameras(usbManager))
    private val _deviceEvents = MutableSharedFlow<CameraDeviceEvent>(extraBufferCapacity = 16)

    override val cameras: StateFlow<List<CameraIdentity>> = _cameras.asStateFlow()
    override val deviceEvents: SharedFlow<CameraDeviceEvent> = _deviceEvents.asSharedFlow()

    private val access = CameraAccessController(
        platform = AndroidCameraAccessPlatform(appContext, usbManager, remote, permissionRequester) { name, granted ->
            scope.launch(accessDispatcher) { accessController().onUsbPermissionResult(name, granted) }
        },
        clock = MonotonicClock(),
        log = { text -> log.d { text } },
    )

    private val attachReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            val device = intent?.let { UvcDevices.deviceOf(it) } ?: return
            if (!UvcDevices.isUvcCamera(device)) return
            val camera = UvcDevices.identityOf(device)
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> onAttached(camera)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> onDetached(camera)
            }
        }
    }

    private class OpenConnection(val id: Int, val connection: UsbDeviceConnection)

    init {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(appContext, attachReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch {
            remote.processDied.collect {
                withContext(opsDispatcher) {
                    log.w { "dropping the USB connection the dead camera process was sharing" }
                    closeConnections()
                }
            }
        }
    }

    private fun accessController(): CameraAccessController = access

    // The error of a refusal carries openAppSettings, so the UI does not read the detail text.
    override suspend fun requestAccess(camera: CameraIdentity): Result<Unit> = withContext(accessDispatcher) {
        access.requestAccess(camera).toBackendResult()
    }

    override suspend fun listModes(camera: CameraIdentity): Result<List<UvcMode>> = withContext(opsDispatcher) {
        val service = remote.awaitService()
            ?: return@withContext failure(CameraErrorKind.CameraServiceUnavailable, "camera service did not bind within ${CameraTimeouts.SERVICE_BIND_MS} ms")
        val open = openConnection(camera)
            ?: return@withContext failure(CameraErrorKind.NoDevice, CameraWire.NO_DESCRIPTOR)
        val token = remote.requests.newToken()
        val reply = try {
            remote.requests.request(token, CameraTimeouts.LIST_MODES_MS) {
                withDescriptor(open) { descriptor -> service.listModes(token, descriptor, open.id) }
            }
        } catch (failure: RemoteException) {
            log.w(failure) { "listModes did not reach the camera process" }
            CameraReply.ProcessDied
        } catch (failure: IOException) {
            log.w(failure) { "no copy of the USB descriptor" }
            return@withContext failure(CameraErrorKind.NoDevice, CameraWire.NO_DESCRIPTOR)
        }
        val list = when (reply) {
            is CameraReply.Modes -> UvcModeList.parse(reply.values)
            CameraReply.ProcessDied -> UvcModeList(CameraCodes.SERVICE_UNAVAILABLE_STATUS, emptyList())
            else -> UvcModeList(UvcModeList.NO_REPLY, emptyList())
        }
        val error = CameraCodes.listError(list)
        if (error != null) Result.failure(CameraException(error)) else Result.success(list.modes)
    }

    override fun start(camera: CameraIdentity, mode: UvcMode, target: VideoTarget): Flow<StreamEvent> = channelFlow {
        val surface = (target as? SurfaceVideoTarget)?.surface
        if (surface == null || !surface.isValid) {
            send(StreamEvent.Ended(CameraError(CameraErrorKind.RenderFailed, CameraWire.NO_SURFACE)))
            return@channelFlow
        }
        val inbox = Channel<RunEvent>(Channel.UNLIMITED)
        val pump = launch(start = CoroutineStart.UNDISPATCHED) { remote.events.collect { event -> inbox.send(event) } }
        var run = 0
        var ended = false
        try {
            var startedAt = 0L
            val started = CameraStarter.withRetry(log = { text -> log.w { text } }) {
                startedAt = SystemClock.elapsedRealtime()
                startOnce(camera, mode, surface)
            }
            val accepted = started.getOrElse { failure ->
                val error = (failure as? CameraException)?.error ?: CameraError(CameraErrorKind.CameraServiceUnavailable, failure.message)
                send(StreamEvent.Ended(error))
                ended = true
                return@channelFlow
            }
            run = accepted.first
            send(StreamEvent.Started(accepted.second))
            log.i { "streaming ${mode.width}x${mode.height} ${mode.codec} at interval ${accepted.second}" }
            for (item in inbox) {
                if (item.run != run) continue
                val event = item.event
                if (event is StreamEvent.FirstFrame) {
                    send(event.copy(startDelayMs = SystemClock.elapsedRealtime() - startedAt))
                } else {
                    send(event)
                }
                if (event is StreamEvent.Ended) {
                    ended = true
                    break
                }
            }
        } finally {
            pump.cancel()
            if (!ended && run != 0) {
                withContext(NonCancellable) { stopRun(run) }
            }
        }
    }.flowOn(opsDispatcher)

    override suspend fun stop() {
        withContext(opsDispatcher) {
            // The camera process ends the run with a stream end event with no error.
            if (remote.currentRun.current != 0) runCatching { remote.boundService()?.stopStream() }
        }
    }

    override suspend fun close() {
        withContext(opsDispatcher) {
            remote.end(remote.currentRun.current, null)
            runCatching { remote.boundService()?.closeCamera() }
            closeConnections()
        }
    }

    /** One start attempt. Gives the run token and the negotiated interval. */
    private suspend fun startOnce(camera: CameraIdentity, mode: UvcMode, surface: Surface): Result<Pair<Int, Long>> {
        val service = remote.awaitService()
            ?: return failure(CameraErrorKind.CameraServiceUnavailable, "camera service did not bind within ${CameraTimeouts.SERVICE_BIND_MS} ms")
        val open = openConnection(camera) ?: return failure(CameraErrorKind.NoDevice, CameraWire.NO_DESCRIPTOR)
        val token = remote.requests.newToken()
        remote.currentRun.start(token)
        val reply = try {
            remote.requests.request(token, CameraTimeouts.START_MS) {
                withDescriptor(open) { descriptor ->
                    service.startStream(
                        token, descriptor, open.id, mode.codec.code, mode.width, mode.height, mode.frameInterval.toInt(), surface,
                    )
                }
            }
        } catch (failure: RemoteException) {
            log.w(failure) { "startStream did not reach the camera process" }
            CameraReply.ProcessDied
        } catch (failure: IOException) {
            log.w(failure) { "no copy of the USB descriptor" }
            remote.currentRun.clear(token)
            return failure(CameraErrorKind.NoDevice, CameraWire.NO_DESCRIPTOR)
        }
        return when (reply) {
            is CameraReply.StartAccepted -> Result.success(token to reply.negotiatedInterval)
            is CameraReply.StartRefused -> {
                remote.currentRun.clear(token)
                Result.failure(CameraException(CameraCodes.startError(reply.message)))
            }
            CameraReply.ProcessDied -> {
                remote.currentRun.clear(token)
                failure(CameraErrorKind.CameraServiceUnavailable, "camera process died with a request outstanding")
            }
            else -> {
                remote.currentRun.clear(token)
                failure(CameraErrorKind.CameraServiceUnavailable, "the camera process did not answer")
            }
        }
    }

    private fun stopRun(run: Int) {
        remote.currentRun.clear(run)
        runCatching { remote.boundService()?.stopStream() }
    }

    /** Opens the device or uses the open connection. Gives null when Android does not open it. */
    private fun openConnection(camera: CameraIdentity): OpenConnection? {
        connections[camera.deviceName]?.let { open -> return open }
        val device = usbManager.deviceList[camera.deviceName] ?: return null
        val connection = usbManager.openDevice(device) ?: return null
        val open = OpenConnection(connectionIds.incrementAndGet(), connection)
        connections[camera.deviceName] = open
        log.d { "opened ${camera.key} as connection ${open.id}" }
        return open
    }

    /** Sends a copy of the descriptor and closes the copy after the call. */
    private inline fun withDescriptor(open: OpenConnection, call: (ParcelFileDescriptor) -> Unit) {
        val descriptor = ParcelFileDescriptor.fromFd(open.connection.fileDescriptor)
        try {
            call(descriptor)
        } finally {
            descriptor.close()
        }
    }

    private fun closeConnections() {
        connections.values.forEach { open -> runCatching { open.connection.close() } }
        connections.clear()
    }

    private fun onAttached(camera: CameraIdentity) {
        lastAttached = camera
        _cameras.value = UvcDevices.cameras(usbManager, camera)
        scope.launch(accessDispatcher) { access.onAttached(camera) }
        _deviceEvents.tryEmit(CameraDeviceEvent.Attached(camera))
    }

    private fun onDetached(camera: CameraIdentity) {
        if (lastAttached?.deviceName == camera.deviceName) lastAttached = null
        _cameras.value = UvcDevices.cameras(usbManager).filterNot { item -> item.deviceName == camera.deviceName }
        scope.launch(accessDispatcher) { access.onDetached(camera) }
        scope.launch(opsDispatcher) {
            val open = connections.remove(camera.deviceName) ?: return@launch
            val run = remote.currentRun.current
            if (run != 0) {
                remote.end(run, CameraError(CameraErrorKind.Disconnected, "detached"))
                runCatching { remote.boundService()?.closeCamera() }
            }
            runCatching { open.connection.close() }
        }
        _deviceEvents.tryEmit(CameraDeviceEvent.Detached(camera))
    }

    private fun <T> failure(kind: CameraErrorKind, detail: String): Result<T> =
        Result.failure(CameraException(CameraError(kind, detail)))

    private companion object {
        val log = Arbor.tag("AndroidUvcCamera")
    }
}
