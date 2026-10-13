package org.superdro.platform.android.camera

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import android.view.Surface
import com.toxicbakery.logging.Arbor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.superdro.core.camera.CameraCodes
import org.superdro.core.camera.CameraError
import org.superdro.core.camera.UvcCodec

/**
 * The bound camera service in its own process `:camera`.
 * The UI process opens the USB device and sends the file descriptor and the `Surface`. The
 * native code (libusb and libuvc) and the frame buffers stay in this process. A crash or a
 * blocked call of the native code stops only this process. The UI keeps the readout.
 *
 * All requests run in sequence on the thread `superdro-uvc-ops`. Each reply goes to the
 * registered [ICameraClient] with the token of the request.
 */
class CameraService : Service() {

    private val ops: ExecutorService = Executors.newSingleThreadExecutor { task -> Thread(task, "superdro-uvc-ops") }

    @Volatile
    private var client: ICameraClient? = null

    @Volatile
    private var destroyed = false

    /** The descriptor of each connection ID. The service keeps one and closes a second copy. */
    private val descriptors = mutableMapOf<Int, ParcelFileDescriptor>()
    private var worker: CameraStreamWorker? = null
    private var workerSurface: Surface? = null
    private var workerRun = 0

    private val binder = object : ICameraService.Stub() {
        override fun getPid(): Int = Process.myPid()

        override fun register(newClient: ICameraClient?) {
            client = newClient
            ops.execute {
                UvcNative.load()
                send { callback -> callback.onNativeStatus(UvcNative.loaded, UvcNative.loadError) }
            }
        }

        override fun listModes(token: Int, fd: ParcelFileDescriptor?, connection: Int) {
            if (destroyed) {
                fd?.close()
                send { callback -> callback.onModes(token, CameraWire.listRefusal(CameraCodes.SERVICE_CANNOT_RUN)) }
                return
            }
            ops.execute { runListModes(token, fd, connection) }
        }

        override fun startStream(
            token: Int,
            fd: ParcelFileDescriptor?,
            connection: Int,
            codec: Int,
            width: Int,
            height: Int,
            frameInterval: Int,
            surface: Surface?,
        ) {
            if (destroyed) {
                fd?.close()
                send { callback -> callback.onStartRefused(token, CameraWire.SERVICE_CANNOT_RUN) }
                return
            }
            ops.execute { runStart(token, fd, connection, codec, width, height, frameInterval, surface) }
        }

        override fun stopStream() {
            ops.execute { stopWorker() }
        }

        override fun closeCamera() {
            ops.execute { closeAll() }
        }
    }

    private val workerEvents = object : StreamWorkerEvents {
        override fun onFirstFrame(run: Int, width: Int, height: Int) {
            send { callback -> callback.onFirstFrame(run, width, height) }
        }

        override fun onStats(run: Int, frames: Int, width: Int, height: Int, fps: Double) {
            send { callback -> callback.onStats(run, frames, width, height, fps) }
        }

        override fun onDiagnostic(run: Int, text: String) {
            send { callback -> callback.onDiagnostic(run, text) }
        }

        override fun onEnded(run: Int, error: CameraError?) {
            send { callback -> callback.onStreamEnded(run, CameraWire.kindName(error), error?.detail) }
            // A run that ends by itself (an error) releases its surface on the ops thread.
            if (error != null && !ops.isShutdown) ops.execute { if (workerRun == run) stopWorker() }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        destroyed = true
        ops.execute { closeAll() }
        ops.shutdown()
        super.onDestroy()
    }

    private fun runListModes(token: Int, fd: ParcelFileDescriptor?, connection: Int) {
        val modes = when {
            !UvcNative.load() -> CameraWire.listRefusal(CameraCodes.NO_NATIVE_LAYER)
            else -> {
                val descriptor = keep(fd, connection)
                if (descriptor == null) {
                    CameraWire.listRefusal(CameraCodes.NO_DESCRIPTOR)
                } else {
                    try {
                        UvcNative.nativeListModes(descriptor.fd, connection) ?: CameraWire.listRefusal(CameraCodes.SERVICE_CANNOT_RUN)
                    } catch (failure: RuntimeException) {
                        log.e(failure) { "listModes failed in the camera process" }
                        CameraWire.listRefusal(CameraCodes.SERVICE_CANNOT_RUN)
                    }
                }
            }
        }
        send { callback -> callback.onModes(token, modes) }
    }

    private fun runStart(
        token: Int,
        fd: ParcelFileDescriptor?,
        connection: Int,
        codec: Int,
        width: Int,
        height: Int,
        frameInterval: Int,
        surface: Surface?,
    ) {
        stopWorker()
        if (!UvcNative.load()) {
            fd?.close()
            send { callback -> callback.onStartRefused(token, CameraWire.NO_NATIVE_LAYER) }
            return
        }
        if (surface == null || !surface.isValid) {
            fd?.close()
            send { callback -> callback.onStartRefused(token, CameraWire.NO_SURFACE) }
            return
        }
        val descriptor = keep(fd, connection)
        if (descriptor == null) {
            send { callback -> callback.onStartRefused(token, CameraWire.NO_DESCRIPTOR) }
            return
        }
        val refusal = try {
            UvcNative.nativeStartStream(descriptor.fd, connection, codec, width, height, frameInterval)
        } catch (failure: RuntimeException) {
            log.e(failure) { "start failed in the camera process" }
            CameraWire.SERVICE_CANNOT_RUN
        }
        if (refusal != null) {
            send { callback -> callback.onStartRefused(token, refusal) }
            return
        }
        val negotiated = UvcNative.nativeNegotiatedInterval().toLong() and 0xFFFF_FFFFL
        send { callback -> callback.onStartAccepted(token, negotiated.toInt()) }
        val next = CameraStreamWorker(token, UvcCodec.fromCode(codec), width, height, negotiated, surface, workerEvents)
        worker = next
        workerSurface = surface
        workerRun = token
        next.start()
    }

    /** Stops the worker, then the native stream. */
    private fun stopWorker() {
        val current = worker ?: return
        worker = null
        val stopped = current.stop()
        if (UvcNative.loaded) UvcNative.nativeStopStream()
        if (stopped) {
            workerSurface?.release()
        } else {
            log.w { "the render thread did not stop, the service keeps its surface and does not release it" }
        }
        workerSurface = null
        workerRun = 0
    }

    private fun closeAll() {
        stopWorker()
        if (UvcNative.loaded) UvcNative.nativeCloseCamera()
        descriptors.values.forEach { descriptor -> runCatching { descriptor.close() } }
        descriptors.clear()
    }

    /**
     * Keeps the descriptor of [connection]. A second copy for the same connection closes. A new
     * connection closes the camera of the old connection first.
     */
    private fun keep(fd: ParcelFileDescriptor?, connection: Int): ParcelFileDescriptor? {
        val known = descriptors[connection]
        if (known != null) {
            fd?.close()
            return known
        }
        if (fd == null) return null
        if (descriptors.isNotEmpty()) {
            if (UvcNative.loaded) UvcNative.nativeCloseCamera()
            descriptors.values.forEach { descriptor -> runCatching { descriptor.close() } }
            descriptors.clear()
        }
        descriptors[connection] = fd
        return fd
    }

    private inline fun send(action: (ICameraClient) -> Unit) {
        val callback = client ?: return
        try {
            action(callback)
        } catch (failure: RemoteException) {
            log.w(failure) { "the UI process did not get a camera event" }
        }
    }

    private companion object {
        val log = Arbor.tag("CameraService")
    }
}
