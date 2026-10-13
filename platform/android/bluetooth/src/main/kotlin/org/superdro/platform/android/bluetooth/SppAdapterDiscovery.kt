package org.superdro.platform.android.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import org.superdro.core.connection.AdapterDiscovery
import org.superdro.core.connection.DiscoveredAdapter
import org.superdro.core.model.TransportKind

/**
 * The paired adapter list: the devices of `getBondedDevices()`, with no filter and no scan. The
 * name is `getAlias()` on API 30 and higher, else `getName()` ([SppRules.displayName]). Without
 * `BLUETOOTH_CONNECT` the list is empty; the capability source reports the permission.
 *
 * The list changes at a pairing, an unpairing, a radio change, and a name change. Call [refresh]
 * after a permission result. To pair a new adapter, the UI opens
 * [BluetoothIntents.openBluetoothSettings].
 */
class SppAdapterDiscovery(context: Context) : AdapterDiscovery {

    private val context: Context = context.applicationContext
    private val refreshes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override val kind: TransportKind = TransportKind.BluetoothSpp

    override val adapters: Flow<List<DiscoveredAdapter>> =
        merge(flowOf(Unit), bluetoothBroadcasts(this.context, LIST_ACTIONS).map { }, refreshes)
            .map { pairedNow() }
            .distinctUntilChanged()

    /** Reads the paired devices again. */
    fun refresh() {
        refreshes.tryEmit(Unit)
    }

    @SuppressLint("MissingPermission")
    private fun pairedNow(): List<DiscoveredAdapter> {
        val adapter = context.bluetoothAdapter() ?: return emptyList()
        if (!Spp.hasConnectPermission(context)) return emptyList()
        val devices = try {
            adapter.bondedDevices.orEmpty().map { device ->
                SppRules.PairedDevice(
                    address = device.address,
                    alias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) device.alias else null,
                    name = device.name,
                )
            }
        } catch (_: SecurityException) {
            return emptyList()
        }
        return SppRules.pairedAdapters(Build.VERSION.SDK_INT, devices)
    }

    private companion object {
        val LIST_ACTIONS: List<String> = buildList {
            add(BluetoothAdapter.ACTION_STATE_CHANGED)
            add(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            add(BluetoothDevice.ACTION_NAME_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) add(BluetoothDevice.ACTION_ALIAS_CHANGED)
        }
    }
}
