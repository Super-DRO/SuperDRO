package org.superdro.platform.android.di

import org.superdro.core.connection.BatteryOptimization
import org.superdro.platform.android.bluetooth.BluetoothCapabilityMonitor
import org.superdro.platform.android.bluetooth.SppAdapterDiscovery
import org.superdro.platform.android.network.NetworkCapabilityMonitor
import org.superdro.platform.android.service.AndroidConnectionKeepAlive
import org.superdro.platform.android.usb.UsbDeviceMonitor
import org.superdro.platform.android.usb.UsbSerialDiscovery

/**
 * Reads the platform states again where Android sends no broadcast: the permissions, the battery
 * optimization, and the USB devices. `MainActivity` calls
 * [refreshAll] in `onResume` and after each result of a system screen, and [refreshUsb] for the
 * intent `USB_DEVICE_ATTACHED`.
 */
class PlatformRefresher(
    private val bluetooth: BluetoothCapabilityMonitor,
    private val network: NetworkCapabilityMonitor,
    private val usb: UsbDeviceMonitor,
    private val sppDiscovery: SppAdapterDiscovery,
    private val usbDiscovery: UsbSerialDiscovery,
    private val battery: BatteryOptimization,
    private val keepAlive: AndroidConnectionKeepAlive,
) {

    /** Reads each state again. Then a start of the service that failed can succeed. */
    fun refreshAll() {
        bluetooth.refresh()
        network.refresh()
        usb.refresh()
        sppDiscovery.refresh()
        usbDiscovery.refresh()
        battery.refresh()
        keepAlive.recheck()
    }

    /** Reads the attached USB devices and their permission again. */
    fun refreshUsb() {
        usb.refresh()
        usbDiscovery.refresh()
        keepAlive.recheck()
    }
}
