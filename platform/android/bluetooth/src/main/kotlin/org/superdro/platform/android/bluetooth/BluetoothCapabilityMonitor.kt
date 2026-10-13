package org.superdro.platform.android.bluetooth

import android.bluetooth.BluetoothAdapter
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import org.superdro.core.connection.LinkCapability

/**
 * The Bluetooth part of the capability source: the radio state and the `BLUETOOTH_CONNECT`
 * permission. It applies to the SPP and the BLE links. `CapabilityState.bluetooth` gets
 * [capability].
 *
 * A receiver of `BluetoothAdapter.ACTION_STATE_CHANGED` follows the radio. It stays registered
 * while [scope] is active. Android sends no broadcast for a permission change: call [refresh] after
 * a permission result and when the activity comes back to the foreground.
 */
class BluetoothCapabilityMonitor(context: Context, scope: CoroutineScope) {

    private val context: Context = context.applicationContext
    private val refreshes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** The capability now: available, or unavailable with `RadioOff` or `PermissionMissing`. */
    val capability: StateFlow<LinkCapability> =
        merge(
            bluetoothBroadcasts(this.context, listOf(BluetoothAdapter.ACTION_STATE_CHANGED)).map { },
            refreshes,
        )
            .map { current() }
            .stateIn(scope, SharingStarted.Eagerly, current())

    /** Reads the permission and the radio again. */
    fun refresh() {
        refreshes.tryEmit(Unit)
    }

    private fun current(): LinkCapability {
        val adapter = context.bluetoothAdapter()
        return SppRules.capability(
            hasAdapter = adapter != null,
            hasConnectPermission = Spp.hasConnectPermission(context),
            radioOn = adapter?.isEnabled == true,
        )
    }
}
