package org.superdro.platform.android.bluetooth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Gives one value for each system Bluetooth broadcast with one of [actions]. The receiver is
 * registered while a collector collects, and removed when the collection stops.
 *
 * The receiver is exported: the Bluetooth process sends these broadcasts, and it does not have the
 * system UID, so a receiver that is not exported does not get them. Each of these actions is a
 * protected broadcast: only the system can send it. Thus another app cannot use this receiver.
 */
internal fun bluetoothBroadcasts(context: Context, actions: List<String>): Flow<Intent> = callbackFlow {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            trySend(intent)
        }
    }
    val filter = IntentFilter().apply { actions.forEach(::addAction) }
    ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    awaitClose { context.unregisterReceiver(receiver) }
}
