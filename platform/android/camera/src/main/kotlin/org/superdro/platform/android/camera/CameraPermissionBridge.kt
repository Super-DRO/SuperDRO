package org.superdro.platform.android.camera

import kotlinx.coroutines.CompletableDeferred
import org.superdro.core.camera.CameraPermissionRequester
import org.superdro.core.camera.CameraPermissionResult

/**
 * The hook between the camera access and the request bus of the app. [request] calls [sendRequest],
 * which sends
 * `ConnectionRequest.RequestPermissions(setOf(PermissionKind.Camera))` on the bus. Then it waits
 * until `MainActivity` gives the result of the system dialog to [onResult].
 *
 * The class has no Android type, so a JVM unit test can examine it.
 */
class CameraPermissionBridge(private val sendRequest: suspend () -> Unit) : CameraPermissionRequester {
    private var pending: CompletableDeferred<CameraPermissionResult>? = null

    override suspend fun request(): CameraPermissionResult {
        val result = synchronized(this) { pending ?: CompletableDeferred<CameraPermissionResult>().also { pending = it } }
        sendRequest()
        return result.await()
    }

    /**
     * Gives the result of the system dialog. [showRationale] is the value of
     * `shouldShowRequestPermissionRationale(CAMERA)` after the dialog.
     */
    fun onResult(granted: Boolean, showRationale: Boolean) {
        val result = resultOf(granted, showRationale)
        val waiting = synchronized(this) { pending.also { pending = null } }
        waiting?.complete(result)
    }

    companion object {
        /**
         * Gives the result of a dialog: a refusal after which Android shows no rationale is
         * [CameraPermissionResult.Blocked]. Only the app settings can change it then.
         */
        fun resultOf(granted: Boolean, showRationale: Boolean): CameraPermissionResult = when {
            granted -> CameraPermissionResult.Granted
            showRationale -> CameraPermissionResult.Denied
            else -> CameraPermissionResult.Blocked
        }
    }
}
