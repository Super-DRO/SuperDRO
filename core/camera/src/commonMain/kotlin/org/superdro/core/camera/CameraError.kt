package org.superdro.core.camera

/**
 * The 13 error kinds of the camera. [messageKey] is the name of the
 * message in `strings_camera.xml`. The kind crosses the process boundary as its [name], not as an
 * ordinal.
 */
enum class CameraErrorKind(val messageKey: String) {
    /** The native libraries did not load in the camera process, or the USB context did not start. */
    NativeUnavailable("camera_error_native"),

    /** `CAMERA` is not granted. */
    CameraPermissionMissing("camera_error_camera_permission"),

    /** No camera, or no usable file descriptor. */
    NoDevice("camera_error_no_device"),

    /** The user refused the USB permission, or the 5000 ms back-off is active. */
    UsbPermissionDenied("camera_error_usb_permission"),

    /** The native code −99 from the device. Only this case shows the hint for a powered hub. */
    NeedsPoweredHub("camera_error_powered_hub"),

    /** The mode list failed. */
    OpenFailed("camera_error_open"),

    /** The camera refused the mode, or H.264 failed before the first frame. */
    ModeFailed("camera_error_mode"),

    /** The camera streams to a different view. */
    AlreadyStreaming("camera_error_already_streaming"),

    /** The native code −11: no memory for the USB transfers. */
    UsbTransferMemory("camera_error_transfer_memory"),

    /** The stream started, but no frame came in 3000 ms. */
    NoFrames("camera_error_no_frames"),

    /** The frames stopped, or the camera was detached. */
    Disconnected("camera_error_disconnected"),

    /** The decode or the draw of the frames failed. */
    RenderFailed("camera_error_render"),

    /** The camera process stopped or did not answer. */
    CameraServiceUnavailable("camera_error_service");

    /** True when a start with this error goes again. */
    val isRetried: Boolean get() = this in CameraRetry.RETRY_SET

    companion object {
        /** Gives the kind of a wire [name], or null for an unknown name. */
        fun fromName(name: String?): CameraErrorKind? = entries.firstOrNull { kind -> kind.name == name }
    }
}

/**
 * One camera error: a [kind] and a [detail] text for the log. The UI shows the message of the
 * kind, not the detail.
 *
 * @property openAppSettings True when only the system settings of the app can give `CAMERA`
 *   (the user refused, and Android does not show the prompt again). The UI then shows
 *   `camera_permission_blocked` and the button "Open settings". The UI reads this flag, not the
 *   [detail] text.
 */
data class CameraError(val kind: CameraErrorKind, val detail: String? = null, val openAppSettings: Boolean = false)

/** The exception of a failed [Result] of [UvcCameraBackend]. */
class CameraException(val error: CameraError) :
    Exception(if (error.detail != null) "${error.kind}: ${error.detail}" else error.kind.toString())

/**
 * The error codes of the native layer and of the camera process. The values −1 to −99 are the
 * libusb and libuvc codes. SuperDRO gives an init failure its own code [INIT_FAILED], so that it
 * does not show the hint for a powered hub.
 */
object CameraCodes {
    /** `UVC_ERROR_IO` and `LIBUSB_ERROR_IO`. In a mode list reply it tells that the device is not there. */
    const val IO: Int = -1

    /** `UVC_ERROR_NO_DEVICE` and `LIBUSB_ERROR_NO_DEVICE`: the device went away. */
    const val NO_DEVICE: Int = -4

    /** `UVC_ERROR_TIMEOUT` and `LIBUSB_ERROR_TIMEOUT`: a control transfer did not end in its time limit. */
    const val TIMEOUT: Int = -7

    /** `UVC_ERROR_NO_MEM` and `LIBUSB_ERROR_NO_MEM`. */
    const val NO_MEMORY: Int = -11

    /** `UVC_ERROR_INVALID_MODE`: the camera refused the stream parameters. */
    const val INVALID_MODE: Int = -51

    /** `UVC_ERROR_OTHER` and `LIBUSB_ERROR_OTHER` from the device. */
    const val OTHER: Int = -99

    /** The native layer streams already. */
    const val ALREADY_STREAMING: Int = -100

    /** No usable file descriptor. */
    const val NO_DESCRIPTOR: Int = -101

    /** The camera process cannot run the request. */
    const val SERVICE_CANNOT_RUN: Int = -102

    /** The camera process has no native layer. */
    const val NO_NATIVE_LAYER: Int = -103

    /** No `Surface` for the video. */
    const val NO_SURFACE: Int = -104

    /** The libusb or libuvc context did not start (a SuperDRO code). */
    const val INIT_FAILED: Int = -105

    /** The mode list status when the camera process is not there. */
    const val SERVICE_UNAVAILABLE_STATUS: Int = Int.MIN_VALUE

    private val CODE_AT_END = Regex("""\((-?\d+)\)\s*$""")

    /** Reads the code in the last "(code)" at the end of [text], or null. */
    fun codeOf(text: String?): Int? = text?.let { value -> CODE_AT_END.find(value)?.groupValues?.get(1)?.toIntOrNull() }

    /**
     * Gives the error of a refused start. The text of the refusal
     * goes into the detail.
     */
    fun startError(text: String?): CameraError = CameraError(startKind(codeOf(text)), text)

    /** Gives the error kind of the code of a refused start, or of no code. */
    fun startKind(code: Int?): CameraErrorKind = when (code) {
        NO_MEMORY -> CameraErrorKind.UsbTransferMemory
        OTHER -> CameraErrorKind.NeedsPoweredHub
        ALREADY_STREAMING -> CameraErrorKind.AlreadyStreaming
        NO_DESCRIPTOR, NO_DEVICE -> CameraErrorKind.NoDevice
        SERVICE_CANNOT_RUN, NO_NATIVE_LAYER -> CameraErrorKind.CameraServiceUnavailable
        NO_SURFACE -> CameraErrorKind.RenderFailed
        INIT_FAILED -> CameraErrorKind.NativeUnavailable
        else -> CameraErrorKind.ModeFailed
    }

    /**
     * Gives the error kind of a failed mode list, or null for the
     * status 0.
     */
    fun listKind(status: Int): CameraErrorKind? = when (status) {
        0 -> null
        SERVICE_UNAVAILABLE_STATUS -> CameraErrorKind.CameraServiceUnavailable
        IO, NO_DESCRIPTOR, NO_DEVICE -> CameraErrorKind.NoDevice
        OTHER -> CameraErrorKind.NeedsPoweredHub
        INIT_FAILED -> CameraErrorKind.NativeUnavailable
        SERVICE_CANNOT_RUN, NO_NATIVE_LAYER -> CameraErrorKind.CameraServiceUnavailable
        else -> CameraErrorKind.OpenFailed
    }

    /** Gives the error of a failed mode list [reply]. A successful reply gives null. */
    fun listError(reply: UvcModeList): CameraError? =
        listKind(reply.status)?.let { kind -> CameraError(kind, "mode list status ${reply.status}") }
}

/**
 * The retry rule of a stream start: a start that fails with
 * [CameraErrorKind.UsbTransferMemory] or [CameraErrorKind.ModeFailed] goes again. The maximum is
 * [MAX_ATTEMPTS] attempts with a pause of [PAUSE_MS] between them.
 */
object CameraRetry {
    const val MAX_ATTEMPTS: Int = 3
    const val PAUSE_MS: Long = 400L

    /** The error kinds that a start tries again. */
    val RETRY_SET: Set<CameraErrorKind> = setOf(CameraErrorKind.UsbTransferMemory, CameraErrorKind.ModeFailed)

    /** True when the start with [error] after [attempt] attempts goes again. */
    fun shouldRetry(error: CameraError, attempt: Int): Boolean = error.kind in RETRY_SET && attempt < MAX_ATTEMPTS

    /** The log text of a retry, for example "start failed (ModeFailed), attempt 2 of 3". */
    fun logText(error: CameraError, nextAttempt: Int): String =
        "start failed (${error.kind}), attempt $nextAttempt of $MAX_ATTEMPTS"
}

/**
 * The error dialog of the video in the graphical view and in the camera screen.
 *
 * @property messageKey The name of the message in `strings_camera.xml`.
 * @property offersSetup True: the buttons are "Camera setup" and "Cancel". False: one button "OK".
 */
data class CameraErrorDialog(val messageKey: String, val offersSetup: Boolean) {
    companion object {
        /** The message for a mode that did not start. */
        const val NEEDS_SETUP_KEY: String = "camera_play_needs_setup"

        /** Gives the dialog of [kind]. */
        fun of(kind: CameraErrorKind): CameraErrorDialog = when (kind) {
            CameraErrorKind.NoFrames, CameraErrorKind.ModeFailed -> CameraErrorDialog(NEEDS_SETUP_KEY, offersSetup = true)
            CameraErrorKind.CameraPermissionMissing -> CameraErrorDialog(kind.messageKey, offersSetup = true)
            else -> CameraErrorDialog(kind.messageKey, offersSetup = false)
        }
    }
}
