package org.superdro.platform.android.camera

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import com.toxicbakery.logging.Arbor
import org.superdro.core.camera.CameraAccessPlatform
import org.superdro.core.camera.CameraIdentity
import org.superdro.core.camera.CameraPermissionRequester
import org.superdro.core.camera.CameraPermissionResult

/**
 * The Android side of the camera access: the run-time permission `CAMERA` through the request bus
 * of the app, then the USB device
 * permission with an explicit, immutable `PendingIntent`. The receiver has `RECEIVER_NOT_EXPORTED`.
 *
 * @param permissionRequester The hook of the app for `CAMERA`. The app sends
 *   `ConnectionRequest.RequestPermissions(setOf(PermissionKind.Camera))` on its bus and gives the
 *   result.
 * @param onPermissionResult Gets the device name of the extra (or null) and the extra
 *   `permission` (or null). [AndroidUvcCameraBackend] gives them to `CameraAccessController`.
 */
class AndroidCameraAccessPlatform(
    private val context: Context,
    private val usbManager: UsbManager,
    private val remote: CameraRemote,
    private val permissionRequester: CameraPermissionRequester,
    private val onPermissionResult: (deviceName: String?, granted: Boolean?) -> Unit,
) : CameraAccessPlatform {

    private val action = permissionAction(context.packageName)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            if (intent?.action != action) return
            val name = UvcDevices.deviceOf(intent)?.deviceName
            val granted = if (intent.hasExtra(UsbManager.EXTRA_PERMISSION_GRANTED)) {
                intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            } else {
                null
            }
            log.d { "usb permission broadcast: device $name, granted $granted" }
            onPermissionResult(name, granted)
        }
    }

    override val nativeAvailable: Boolean? get() = remote.nativeAvailable

    override fun startServiceBind() {
        remote.startBind()
    }

    override fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override suspend fun requestCameraPermission(): CameraPermissionResult = permissionRequester.request()

    override fun registerReceivers() {
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun hasUsbPermission(camera: CameraIdentity): Boolean {
        val device = usbManager.deviceList[camera.deviceName] ?: return false
        return usbManager.hasPermission(device)
    }

    override fun requestUsbPermission(camera: CameraIdentity) {
        val device = usbManager.deviceList[camera.deviceName]
        if (device == null) {
            onPermissionResult(camera.deviceName, false)
            return
        }
        val intent = Intent(action).setPackage(context.packageName)
        val pending = PendingIntent.getBroadcast(context, REQUEST_CODE, intent, PendingIntent.FLAG_IMMUTABLE)
        usbManager.requestPermission(device, pending)
    }

    companion object {
        private const val REQUEST_CODE = 0x5D_0C

        /** The action of the USB permission broadcast of the camera. */
        fun permissionAction(packageName: String): String = "$packageName.USB_CAMERA_PERMISSION"

        private val log = Arbor.tag("CameraAccess")
    }
}
