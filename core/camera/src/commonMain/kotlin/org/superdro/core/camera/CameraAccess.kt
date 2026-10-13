package org.superdro.core.camera

import kotlinx.coroutines.CompletableDeferred
import org.superdro.core.util.Clock

/** The result of a request for the run-time permission `CAMERA`. */
enum class CameraPermissionResult {
    Granted,

    /** The user refused. The app can ask again. */
    Denied,

    /** The user refused, and Android does not show the prompt again. Only the app settings can change it. */
    Blocked,
}

/**
 * The hook for the run-time permission `CAMERA`. The app sends
 * `ConnectionRequest.RequestPermissions(setOf(PermissionKind.Camera))` on its request bus and gives
 * the result here. `MainActivity` shows the system dialog.
 */
fun interface CameraPermissionRequester {
    suspend fun request(): CameraPermissionResult
}

/** The platform side of the access sequence. */
interface CameraAccessPlatform {

    /**
     * False when the camera process reported that the native libraries did not load. Null while
     * the camera process did not report yet.
     */
    val nativeAvailable: Boolean?

    /** Starts the bind of the camera service, if it is not bound. */
    fun startServiceBind()

    /** True when `CAMERA` is granted. */
    fun hasCameraPermission(): Boolean

    /** Asks the user for `CAMERA` (through [CameraPermissionRequester]). */
    suspend fun requestCameraPermission(): CameraPermissionResult

    /** Registers the receivers of the USB permission and of the attach and detach broadcasts. */
    fun registerReceivers()

    /** True when the app has the USB permission for [camera]. */
    fun hasUsbPermission(camera: CameraIdentity): Boolean

    /**
     * Shows the USB permission prompt for [camera] with an explicit, immutable `PendingIntent`.
     * The receiver gives the result to [CameraAccessController.onUsbPermissionResult].
     */
    fun requestUsbPermission(camera: CameraIdentity)
}

/** The result of an access request. */
sealed interface CameraAccessResult {
    data object Granted : CameraAccessResult

    /**
     * The access failed with [error]. [openAppSettings] is true when only the system settings of
     * the app can give `CAMERA`: the UI then shows `camera_permission_blocked` and the button
     * "Open settings" (`ConnectionRequest.OpenAppSettings`). The default is the flag of [error].
     */
    data class Refused(val error: CameraError, val openAppSettings: Boolean = error.openAppSettings) : CameraAccessResult
}

/**
 * Gives the result of [UvcCameraBackend.requestAccess] for this access result. A refusal gives a
 * [CameraException] whose error carries [CameraAccessResult.Refused.openAppSettings], so that the
 * UI does not read the detail text of the error.
 */
fun CameraAccessResult.toBackendResult(): Result<Unit> = when (this) {
    CameraAccessResult.Granted -> Result.success(Unit)
    is CameraAccessResult.Refused -> Result.failure(CameraException(error.copy(openAppSettings = openAppSettings)))
}

/**
 * The access state machine of the USB camera.
 * The steps of [requestAccess]:
 *
 * 1. The camera process reported no native layer: [CameraErrorKind.NativeUnavailable].
 * 2. Start the bind of the camera service.
 * 3. `CAMERA` is not granted: ask for it first. A refusal gives
 *    [CameraErrorKind.CameraPermissionMissing].
 * 4. Register the receivers one time.
 * 5. No camera: [CameraErrorKind.NoDevice].
 * 6. The app has the USB permission: granted.
 * 7. The user refused the same camera less than 5000 ms ago:
 *    [CameraErrorKind.UsbPermissionDenied] with the detail "within back-off".
 * 8. Else the request waits in a queue. When no prompt is open, the prompt opens for the camera.
 *
 * The receiver result ([onUsbPermissionResult]) does not trust the extras of the broadcast: an
 * immutable `PendingIntent` can come without them. A missing device gives the device of the open
 * prompt. The grant is true when the extra tells so, or when the platform has the permission.
 *
 * Call all functions on one thread (the main thread on Android).
 */
class CameraAccessController(
    private val platform: CameraAccessPlatform,
    private val clock: Clock,
    private val log: (String) -> Unit = {},
) {
    private class Pending(val camera: CameraIdentity, val result: CompletableDeferred<CameraAccessResult>)

    private val queue = mutableListOf<Pending>()
    private var receiversRegistered = false
    private var promptFor: CameraIdentity? = null
    private var lastRefusedDevice: String? = null
    private var lastRefusedAtMs: Long = 0L

    /** The camera of the open USB prompt, or null. */
    val openPrompt: CameraIdentity? get() = promptFor

    /** The number of requests that wait for a prompt result. */
    val waiting: Int get() = queue.size

    /** Runs the access sequence for [camera]. */
    suspend fun requestAccess(camera: CameraIdentity?): CameraAccessResult {
        if (platform.nativeAvailable == false) {
            return refused(CameraErrorKind.NativeUnavailable, "the camera process has no native layer")
        }
        platform.startServiceBind()
        if (!platform.hasCameraPermission()) {
            when (platform.requestCameraPermission()) {
                CameraPermissionResult.Granted -> Unit
                CameraPermissionResult.Denied ->
                    return refused(CameraErrorKind.CameraPermissionMissing, "CAMERA refused")
                CameraPermissionResult.Blocked ->
                    return CameraAccessResult.Refused(
                        CameraError(CameraErrorKind.CameraPermissionMissing, "CAMERA blocked", openAppSettings = true),
                    )
            }
        }
        if (!receiversRegistered) {
            platform.registerReceivers()
            receiversRegistered = true
        }
        if (camera == null) return refused(CameraErrorKind.NoDevice, "no camera")
        if (platform.hasUsbPermission(camera)) return CameraAccessResult.Granted
        if (lastRefusedDevice == camera.deviceName &&
            clock.nowMs() - lastRefusedAtMs < CameraTimeouts.USB_PERMISSION_BACK_OFF_MS
        ) {
            return refused(CameraErrorKind.UsbPermissionDenied, "within back-off")
        }
        val pending = Pending(camera, CompletableDeferred())
        queue += pending
        if (promptFor == null) {
            openPrompt(camera)
        } else {
            log("queued behind the open prompt (${queue.size - 1} waiting)")
        }
        return pending.result.await()
    }

    /**
     * Gives the result of the USB permission broadcast. [deviceName] is the device of the extra,
     * or null when the extra is absent. [permissionExtra] is the extra `permission`, or null.
     */
    fun onUsbPermissionResult(deviceName: String?, permissionExtra: Boolean?) {
        val camera = deviceName?.let { name -> cameraNamed(name) } ?: promptFor ?: return
        val hasGrant = platform.hasUsbPermission(camera)
        val granted = permissionExtra == true || hasGrant
        if (permissionExtra == false && hasGrant) {
            log("usb permission broadcast said refused, but the grant is in place")
        }
        if (granted) {
            complete(camera.deviceName, CameraAccessResult.Granted)
        } else {
            lastRefusedDevice = camera.deviceName
            lastRefusedAtMs = clock.nowMs()
            complete(camera.deviceName, CameraAccessResult.Refused(CameraError(CameraErrorKind.UsbPermissionDenied, "refused")))
        }
        if (promptFor?.deviceName == camera.deviceName) promptFor = null
        openNextPrompt()
    }

    /** An attach broadcast: clears the back-off of [camera]. */
    fun onAttached(camera: CameraIdentity) {
        if (lastRefusedDevice == camera.deviceName) lastRefusedDevice = null
    }

    /**
     * A detach broadcast: clears the open prompt of [camera], and fails its waiting requests with
     * [CameraErrorKind.Disconnected] "detached while asking".
     */
    fun onDetached(camera: CameraIdentity) {
        complete(camera.deviceName, CameraAccessResult.Refused(CameraError(CameraErrorKind.Disconnected, "detached while asking")))
        if (promptFor?.deviceName == camera.deviceName) {
            promptFor = null
            openNextPrompt()
        }
    }

    private fun cameraNamed(deviceName: String): CameraIdentity? =
        queue.firstOrNull { pending -> pending.camera.deviceName == deviceName }?.camera
            ?: promptFor?.takeIf { camera -> camera.deviceName == deviceName }

    private fun complete(deviceName: String, result: CameraAccessResult) {
        val done = queue.filter { pending -> pending.camera.deviceName == deviceName }
        queue.removeAll(done)
        done.forEach { pending -> pending.result.complete(result) }
    }

    private fun openNextPrompt() {
        if (promptFor != null) return
        queue.firstOrNull()?.let { pending -> openPrompt(pending.camera) }
    }

    private fun openPrompt(camera: CameraIdentity) {
        promptFor = camera
        platform.requestUsbPermission(camera)
    }

    private fun refused(kind: CameraErrorKind, detail: String): CameraAccessResult.Refused =
        CameraAccessResult.Refused(CameraError(kind, detail))
}
