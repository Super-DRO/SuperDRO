package org.superdro.platform.android.usb

import android.content.Context
import android.hardware.usb.UsbManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import org.superdro.core.connection.AttachedUsbDevice

/**
 * Reads the attached USB serial devices with no Android type in the result. A device counts when
 * [SuperDroUsbProber] accepts it.
 */
internal fun UsbManager.serialDevices(prober: SuperDroUsbProber): List<UsbDeviceInfo> =
    deviceList.values.mapNotNull { device ->
        val driver = prober.probe(device) ?: return@mapNotNull null
        val permitted = hasPermission(device)
        UsbDeviceInfo(
            vendorId = device.vendorId,
            productId = device.productId,
            manufacturer = device.manufacturerName,
            product = device.productName,
            serial = serialOf(device),
            permitted = permitted,
            portCount = driver.ports.size,
            deviceName = device.deviceName,
        )
    }.sortedWith(UsbRules.deviceOrder)

/**
 * The USB part of the capability source: the attached USB serial devices and their permission.
 * `CapabilityState.attachedUsb` gets [attached].
 *
 * The receiver of `USB_DEVICE_ATTACHED` and `USB_DEVICE_DETACHED` stays registered while [scope] is
 * active. A detach removes the device: the capability of its address becomes `DeviceGone`. The
 * attach of the same device adds it again, and the session in `WaitingForDevice` starts again when
 * the capability is available. A detach also ends the USB permission, so the attached device is
 * usually not permitted until the user gives the permission again, or accepts the "open app" dialog
 * of the device filter.
 *
 * Call [refresh] after a permission result and when `MainActivity` gets the `USB_DEVICE_ATTACHED`
 * intent. [requester] grants refresh the list without a call.
 */
class UsbDeviceMonitor(
    context: Context,
    scope: CoroutineScope,
    requester: UsbPermissionRequester? = null,
) {
    private val context: Context = context.applicationContext
    private val prober = SuperDroUsbProber()
    private val refreshes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** The attached USB serial devices now. */
    val attached: StateFlow<List<AttachedUsbDevice>> =
        listOfNotNull(usbAttachBroadcasts(this.context).map { }, refreshes, requester?.grants?.map { })
            .merge()
            .map { current() }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, current())

    /** Reads the devices and their permission again. */
    fun refresh() {
        refreshes.tryEmit(Unit)
    }

    private fun current(): List<AttachedUsbDevice> =
        UsbRules.attachedDevices(context.usbManager()?.serialDevices(prober).orEmpty())
}
