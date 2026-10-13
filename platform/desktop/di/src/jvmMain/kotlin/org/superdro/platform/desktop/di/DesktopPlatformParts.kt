package org.superdro.platform.desktop.di

import java.io.Closeable
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import org.superdro.core.camera.CameraDeviceEvent
import org.superdro.core.camera.CameraError
import org.superdro.core.camera.CameraErrorKind
import org.superdro.core.camera.CameraException
import org.superdro.core.camera.CameraIdentity
import org.superdro.core.camera.StreamEvent
import org.superdro.core.camera.UvcCameraBackend
import org.superdro.core.camera.UvcMode
import org.superdro.core.camera.VideoTarget
import org.superdro.core.connection.AttachedUsbDevice
import org.superdro.core.connection.CapabilitySource
import org.superdro.core.connection.CapabilityState
import org.superdro.core.connection.ConnectionKeepAlive
import org.superdro.core.connection.KeepAliveStatus
import org.superdro.core.connection.KeepAliveSummary
import org.superdro.core.connection.LinkCapability

/**
 * The desktop capability source: the network of the TCP links and the attached USB serial devices.
 *
 * The Bluetooth capability is always available. The desktop app does not read the state of the
 * radio: the operating system owns the RFCOMM port. An RFCOMM port that cannot connect gives
 * `Refused`, and the reconnect policy tries again. A port that is not there gives `DeviceGone`.
 */
class DesktopCapabilitySource(
    network: StateFlow<LinkCapability>,
    usb: StateFlow<List<AttachedUsbDevice>>,
    scope: CoroutineScope,
) : CapabilitySource {

    override val state: StateFlow<CapabilityState> =
        combine(network, usb) { networkCapability, devices -> CapabilityState(LinkCapability.Available, networkCapability, devices) }
            .stateIn(scope, SharingStarted.Eagerly, CapabilityState(LinkCapability.Available, network.value, usb.value))
}

/**
 * The desktop keep-alive: a no-op. The process lives while the window lives, so the links stay up
 * while the window is open, also when it is not in the foreground or is minimized. The app has no
 * service and no notification. The window close stops the links.
 *
 * [status] is [KeepAliveStatus.Running] while one or more sessions want a link, else
 * [KeepAliveStatus.Idle]. Thus the tab never shows the warning that the links can stop in the
 * background.
 */
class DesktopConnectionKeepAlive : ConnectionKeepAlive {

    private val current = MutableStateFlow(KeepAliveStatus.Idle)

    override val status: StateFlow<KeepAliveStatus> = current.asStateFlow()

    override fun update(summary: KeepAliveSummary?) {
        current.value = if (summary == null) KeepAliveStatus.Idle else KeepAliveStatus.Running
    }
}

/**
 * The coroutine dispatcher of the link reads and writes on the desktop.
 *
 * - The threads have `Thread.MAX_PRIORITY`. The JVM gives the priority to the operating system
 *   where the system permits it (Windows and macOS). On Linux a normal user gets no change.
 * - The pool makes a new thread when all threads are busy, and stops a thread after
 *   [keepAliveSeconds] with no work. Each open serial link holds one thread for its read loop.
 *   Thus a small fixed pool is not correct.
 */
class DesktopLinkIoDispatcher(keepAliveSeconds: Long = KEEP_ALIVE_SECONDS) : CoroutineDispatcher(), Closeable {

    private val threadNumber = AtomicInteger()

    private val executor = ThreadPoolExecutor(0, Int.MAX_VALUE, keepAliveSeconds, TimeUnit.SECONDS, SynchronousQueue()) { task ->
        Thread(task, "superdro-link-${threadNumber.incrementAndGet()}").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
        }
    }

    /** The number of threads that the pool has now. */
    val threadCount: Int get() = executor.poolSize

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        try {
            executor.execute(block)
        } catch (_: RejectedExecutionException) {
            // Only after close(): the work goes to the IO dispatcher, so that a coroutine does not stop.
            Dispatchers.IO.dispatch(context, block)
        }
    }

    /** Stops the threads after their work. */
    override fun close() {
        executor.shutdown()
    }

    override fun toString(): String = "DesktopLinkIoDispatcher"

    companion object {
        /** A thread with no work for this time stops. */
        const val KEEP_ALIVE_SECONDS: Long = 60L
    }
}

/**
 * The camera back end of the desktop app: no camera, with a reason.
 *
 * The UVC back end of SuperDRO is Android code (libusb and libuvc through
 * JNI in the process `:camera`, decode to a `Surface`). A desktop target needs a different back end,
 * and the desktop app does not have one. The list of cameras is always
 * empty. Each request fails with [CameraErrorKind.NativeUnavailable] and the detail [REASON]. The camera
 * sends no data to a network.
 */
object DesktopNoCameraBackend : UvcCameraBackend {

    /** The detail of each camera error of the desktop app. */
    const val REASON: String = "The desktop app has no UVC camera back end."

    private val error = CameraError(CameraErrorKind.NativeUnavailable, REASON)

    override val cameras: StateFlow<List<CameraIdentity>> = MutableStateFlow(emptyList<CameraIdentity>()).asStateFlow()

    override val deviceEvents: SharedFlow<CameraDeviceEvent> = MutableSharedFlow<CameraDeviceEvent>().asSharedFlow()

    override suspend fun requestAccess(camera: CameraIdentity): Result<Unit> = Result.failure(CameraException(error))

    override suspend fun listModes(camera: CameraIdentity): Result<List<UvcMode>> = Result.failure(CameraException(error))

    override fun start(camera: CameraIdentity, mode: UvcMode, target: VideoTarget): Flow<StreamEvent> = flowOf(StreamEvent.Ended(error))

    override suspend fun stop() = Unit

    override suspend fun close() = Unit
}
