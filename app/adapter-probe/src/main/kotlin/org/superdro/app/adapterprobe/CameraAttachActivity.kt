package org.superdro.app.adapterprobe

import android.app.Activity
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.provider.Settings
import androidx.core.content.IntentCompat
import com.toxicbakery.logging.Arbor
import org.superdro.core.adapterprobe.CameraAttach

/**
 * The invisible attach activity of TT2. The filter `res/xml/probe_video_device_filter.xml` selects
 * the USB video class 14. The activity records the time, the vendor ID, the product ID, and
 * `UsbManager.hasPermission()` of each attach, and ends at once. The user tells the app on the Test
 * screen of TT2 if Android showed a dialog.
 */
class CameraAttachActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        if (device != null) {
            val manager = getSystemService(UsbManager::class.java)
            val bootCount = runCatching { Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT) }.getOrNull()
            val attach = CameraAttach(
                epochMs = System.currentTimeMillis(),
                bootCount = bootCount,
                vendorId = device.vendorId,
                productId = device.productId,
                name = device.productName,
                hasPermission = manager?.hasPermission(device) == true,
            )
            (application as ProbeApplication).graph.attaches.add(attach)
            Arbor.tag("CameraAttach").d { "Attach of 0x%04x:0x%04x, permission %s".format(attach.vendorId, attach.productId, attach.hasPermission) }
        }
        finish()
    }
}
