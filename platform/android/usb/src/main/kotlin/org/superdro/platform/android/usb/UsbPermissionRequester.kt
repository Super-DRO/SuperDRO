package org.superdro.platform.android.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.superdro.core.model.AdapterAddress

/**
 * The USB permission flow. `MainActivity` calls [request] for
 * `ConnectionRequest.RequestUsbPermission`.
 *
 * - The `PendingIntent` is a broadcast with the action [UsbRules.permissionAction], an explicit
 *   package (`setPackage`), and `FLAG_MUTABLE` on API 31 and higher ([UsbRules.permissionPendingIntentFlags]).
 *   The system adds `EXTRA_DEVICE` and `EXTRA_PERMISSION_GRANTED`.
 * - The receiver of the result is registered with `RECEIVER_NOT_EXPORTED` on all API levels. The
 *   request owns it: [request] removes it when the result comes or when the caller stops.
 * - The manifest declares no `USB_PERMISSION` permission: Android has no such permission.
 *
 * Each grant goes to [grants], so that the device monitor and the discovery read the device again.
 */
class UsbPermissionRequester(context: Context) {

    private val context: Context = context.applicationContext
    private val action: String = UsbRules.permissionAction(this.context.packageName)
    private val grantEvents = MutableSharedFlow<AdapterAddress.UsbSerial>(extraBufferCapacity = 16)

    /** The addresses that got the permission, in the order of the grants. */
    val grants: SharedFlow<AdapterAddress.UsbSerial> = grantEvents.asSharedFlow()

    /**
     * Asks for the permission of the device of [address]. Gives true when the user grants it, or
     * when the app has it already. Gives false when the device is not attached or the user refuses.
     */
    suspend fun request(address: AdapterAddress.UsbSerial): Boolean {
        val manager = context.usbManager() ?: return false
        val device = manager.deviceOf(address) ?: return false
        val granted = request(manager, device)
        if (granted) grantEvents.tryEmit(address)
        return granted
    }

    private suspend fun request(manager: UsbManager, device: UsbDevice): Boolean {
        if (manager.hasPermission(device)) return true
        val result = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != action) return
                val target = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                if (target != null && target.deviceName != device.deviceName) return
                val extraGranted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                result.complete(UsbRules.isGranted(extraGranted, manager.hasPermission(device)))
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val intent = Intent(action).setPackage(context.packageName)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                device.deviceId,
                intent,
                UsbRules.permissionPendingIntentFlags(Build.VERSION.SDK_INT),
            )
            manager.requestPermission(device, pendingIntent)
            return result.await()
        } finally {
            context.unregisterReceiver(receiver)
        }
    }
}
