package org.superdro.platform.android.camera

import org.superdro.core.camera.CameraCodes
import org.superdro.core.camera.CameraError
import org.superdro.core.camera.CameraErrorKind
import org.superdro.core.camera.CameraIdentity

/**
 * The values that cross the process boundary, and the texts of both processes. The
 * object has no Android type, so a JVM unit test can examine it.
 */
object CameraWire {
    /** The name of the camera process (`android:process` of `CameraService`). */
    const val CAMERA_PROCESS_SUFFIX: String = ":camera"

    /** The name of the process of the attach trampolines. */
    const val ATTACH_PROCESS_SUFFIX: String = ":usbattach"

    /** The refusal of a request that the camera process cannot run (after `onDestroy`). */
    val SERVICE_CANNOT_RUN: String = "the camera process could not run this (${CameraCodes.SERVICE_CANNOT_RUN})"

    /** The refusal when the native libraries did not load. */
    val NO_NATIVE_LAYER: String = "the camera process has no native layer (${CameraCodes.NO_NATIVE_LAYER})"

    /** The refusal when the `Surface` is not valid. */
    val NO_SURFACE: String = "no surface (${CameraCodes.NO_SURFACE})"

    /** The refusal when the UI has no file descriptor. */
    val NO_DESCRIPTOR: String = "no descriptor (${CameraCodes.NO_DESCRIPTOR})"

    /** The detail of a stream that ended because the camera process died. */
    const val PROCESS_DIED: String = "camera process died"

    /** The wire value of an error kind: its name, or null for no error. */
    fun kindName(error: CameraError?): String? = error?.kind?.name

    /**
     * Gives the error of a stream end event. A null [kindName] is a stop by the user. An unknown
     * name gives [CameraErrorKind.RenderFailed], so that the UI shows an error.
     */
    fun errorOf(kindName: String?, detail: String?): CameraError? = when (kindName) {
        null -> null
        else -> CameraError(CameraErrorKind.fromName(kindName) ?: CameraErrorKind.RenderFailed, detail)
    }

    /** The mode list reply of a request that the camera process cannot run. */
    fun listRefusal(status: Int): IntArray = intArrayOf(status)

    /**
     * The log text of the attach trampoline, for example "attach trampoline: C270 (046d:0825)". A
     * device with no video class interface gets ", no video class interface". SuperDRO writes the
     * IDs as hex text, the same as the key of the mode memory.
     */
    fun attachText(name: String?, vendorId: Int, productId: Int, hasVideoInterface: Boolean): String {
        val label = name?.takeIf { text -> text.isNotBlank() } ?: "USB device"
        val head = "attach trampoline: $label (${CameraIdentity.keyOf(vendorId, productId)})"
        return if (hasVideoInterface) head else "$head, no video class interface"
    }

    /**
     * True when [processName] is the camera process or the attach process of [packageName]. The
     * application skips its start-up in these processes.
     */
    fun isAuxiliaryProcess(processName: String?, packageName: String): Boolean =
        processName == packageName + CAMERA_PROCESS_SUFFIX || processName == packageName + ATTACH_PROCESS_SUFFIX
}
