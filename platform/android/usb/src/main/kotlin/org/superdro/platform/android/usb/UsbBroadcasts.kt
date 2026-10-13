package org.superdro.platform.android.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Gives one value for each `USB_DEVICE_ATTACHED` and `USB_DEVICE_DETACHED` broadcast. The
 * receiver is registered at run time with `RECEIVER_NOT_EXPORTED`, while a collector collects,
 * and removed when the collection stops. The system server sends these
 * broadcasts, so a receiver that is not exported gets them.
 */
internal fun usbAttachBroadcasts(context: Context): Flow<Intent> = callbackFlow {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            trySend(intent)
        }
    }
    val filter = IntentFilter().apply {
        addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
    }
    ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    awaitClose { context.unregisterReceiver(receiver) }
}
