package org.superdro.platform.android.camera

import android.app.Activity
import android.hardware.usb.UsbManager
import android.os.Bundle
import com.toxicbakery.logging.Arbor

/**
 * The attach trampoline of the UVC cameras. Android
 * starts it when a camera that agrees with `res/xml/superdro_camera_device_filter.xml` (class 14,
 * subclass 1) is attached. The user can select "use by default", and Android then gives the USB
 * permission for the camera to the app.
 *
 * The activity has no UI and closes at once. It runs in the process `:usbattach`, where the
 * application skips its start-up ([CameraWire.isAuxiliaryProcess]). Thus an attach does not start
 * the main app and does not change the adapter links. `app:android` declares it.
 */
class CameraAttachActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val device = intent?.takeIf { it.action == UsbManager.ACTION_USB_DEVICE_ATTACHED }?.let { UvcDevices.deviceOf(it) }
        if (device != null) {
            log.i {
                CameraWire.attachText(
                    name = runCatching { device.productName }.getOrNull() ?: device.deviceName,
                    vendorId = device.vendorId,
                    productId = device.productId,
                    hasVideoInterface = UvcDevices.isUvcCamera(device),
                )
            }
        }
        finish()
    }

    private companion object {
        val log = Arbor.tag("CameraAttach")
    }
}
