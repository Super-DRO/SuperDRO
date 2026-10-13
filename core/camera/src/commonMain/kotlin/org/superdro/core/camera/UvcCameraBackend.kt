package org.superdro.core.camera

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The platform contract of the USB camera. `platform:android:camera` implements it with libusb
 * and libuvc in the process `:camera`.
 * A desktop or iOS target needs a different back end. The camera sends no data to a network.
 *
 * Each suspend function gives a [Result]. A failure has a [CameraException] with the
 * [CameraError].
 */
interface UvcCameraBackend {

    /** The attached UVC cameras, in the order of [CameraList.of]. */
    val cameras: StateFlow<List<CameraIdentity>>

    /** The attach and detach events of UVC cameras. */
    val deviceEvents: SharedFlow<CameraDeviceEvent>

    /**
     * Gets the access to [camera]: first the run-time permission `CAMERA`, then the USB device
     * permission. The rules are in [CameraAccessController].
     */
    suspend fun requestAccess(camera: CameraIdentity): Result<Unit>

    /**
     * Gives all modes of [camera], with one mode for each frame interval. The list has also
     * the modes that SuperDRO cannot show. [UvcModeRules] makes the lists for the UI.
     */
    suspend fun listModes(camera: CameraIdentity): Result<List<UvcMode>>

    /**
     * Starts the video of [camera] in [mode] into [target]. The start request has the selected
     * frame interval. The flow gives the [StreamEvent] values of this run. The flow ends
     * after [StreamEvent.Ended]. A cancel of the collector stops the stream.
     *
     * The back end tries a start again with the rule of [CameraRetry].
     */
    fun start(camera: CameraIdentity, mode: UvcMode, target: VideoTarget): Flow<StreamEvent>

    /** Stops the stream, if one runs. The camera stays open. */
    suspend fun stop()

    /** Stops the stream and closes the camera. */
    suspend fun close()
}

/**
 * The video target of a start: a handle of the platform. On Android it is a `SurfaceVideoTarget`
 * with the `Surface` of a `SurfaceView` (`core:camera`, `androidMain`).
 */
interface VideoTarget

/** An attach or detach event of a UVC camera. */
sealed interface CameraDeviceEvent {
    val camera: CameraIdentity

    data class Attached(override val camera: CameraIdentity) : CameraDeviceEvent

    data class Detached(override val camera: CameraIdentity) : CameraDeviceEvent
}

/** One event of a video run. */
sealed interface StreamEvent {

    /**
     * The camera process accepted the start.
     *
     * @property negotiatedInterval The frame interval that the camera accepted, in 100 ns units,
     *   or 0 when it is not known.
     */
    data class Started(val negotiatedInterval: Long) : StreamEvent

    /**
     * The first frame came.
     *
     * @property startDelayMs The time from the start request to the first frame.
     */
    data class FirstFrame(val width: Int, val height: Int, val startDelayMs: Long) : StreamEvent

    /** The statistics of the run, at most one time each [StreamRules.STATS_INTERVAL_MS]. */
    data class Stats(val frames: Int, val width: Int, val height: Int, val fps: Double) : StreamEvent

    /** A diagnostic text of the camera process, for the log. */
    data class Diagnostic(val text: String) : StreamEvent

    /** The run ended. [error] is null for a stop by the user. */
    data class Ended(val error: CameraError?) : StreamEvent
}

/** The time limits of the camera. */
object CameraTimeouts {
    /** The wait for the bind of the camera service. */
    const val SERVICE_BIND_MS: Long = 5_000L

    /** The wait for the reply of a mode list. */
    const val LIST_MODES_MS: Long = 8_000L

    /** The wait for the reply of a stream start. */
    const val START_MS: Long = 20_000L

    /** The wait for the render thread at a stop. */
    const val RENDER_STOP_MS: Long = 1_500L

    /**
     * The time limit of each UVC control transfer. The Linux UVC driver uses the same
     * value. The native layer stops the negotiation at the first time-out.
     */
    const val CONTROL_TRANSFER_MS: Int = 5_000

    /** The back-off after a refused USB permission for the same device. */
    const val USB_PERMISSION_BACK_OFF_MS: Long = 5_000L
}
