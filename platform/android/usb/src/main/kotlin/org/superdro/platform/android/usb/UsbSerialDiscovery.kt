package org.superdro.platform.android.usb

import android.content.Context
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
 * The attached USB serial devices for the add-device list: one row for each port of each device
 * that [SuperDroUsbProber] accepts. A device with no permission has no serial number in its address
 * and `needsPermission = true`. The list changes at each attach, detach, and grant.
 */
class UsbSerialDiscovery(
    context: Context,
    requester: UsbPermissionRequester? = null,
) : AdapterDiscovery {

    private val context: Context = context.applicationContext
    private val prober = SuperDroUsbProber()
    private val refreshes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val grants: Flow<Unit>? = requester?.grants?.map { }

    override val kind: TransportKind = TransportKind.UsbSerial

    override val adapters: Flow<List<DiscoveredAdapter>> =
        listOfNotNull(flowOf(Unit), usbAttachBroadcasts(this.context).map { }, refreshes, grants)
            .merge()
            .map { UsbRules.discoveredAdapters(context.usbManager()?.serialDevices(prober).orEmpty()) }
            .distinctUntilChanged()

    /** Reads the devices again, for example after a permission result. */
    fun refresh() {
        refreshes.tryEmit(Unit)
    }
}
