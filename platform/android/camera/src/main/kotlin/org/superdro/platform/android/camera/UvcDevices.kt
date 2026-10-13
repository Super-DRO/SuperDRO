package org.superdro.platform.android.camera

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.IntentCompat
import org.superdro.core.camera.CameraIdentity
import org.superdro.core.camera.CameraList
import org.superdro.core.camera.UsbVideoClass

/**
 * Finds the USB Video Class cameras: `UsbManager.getDeviceList()` and the interface class 14.
 */
object UvcDevices {

    /** The USB interface subclass "video control" of the attach filter. */
    const val SUBCLASS_VIDEO_CONTROL: Int = UsbVideoClass.SUBCLASS_VIDEO_CONTROL

    /** The interface classes of [device]. */
    fun interfaceClasses(device: UsbDevice): List<Int> =
        (0 until device.interfaceCount).map { index -> device.getInterface(index).interfaceClass }

    /** True when [device] has one or more interfaces of the class 14 (video). */
    fun isUvcCamera(device: UsbDevice): Boolean = UsbVideoClass.isCamera(interfaceClasses(device))

    /** Gives the identity of [device]. A name that Android cannot read without the permission is null. */
    fun identityOf(device: UsbDevice): CameraIdentity = CameraIdentity(
        vendorId = device.vendorId,
        productId = device.productId,
        manufacturerName = runCatching { device.manufacturerName }.getOrNull(),
        productName = runCatching { device.productName }.getOrNull(),
        deviceName = device.deviceName,
    )

    /** Gives the attached UVC cameras in the order of [CameraList.of]. */
    fun cameras(usbManager: UsbManager, lastAttached: CameraIdentity? = null): List<CameraIdentity> =
        CameraList.of(
            usbManager.deviceList.values.filter { device -> isUvcCamera(device) }.map { device -> identityOf(device) },
            lastAttached,
        )

    /** The device of a USB broadcast, or null. */
    fun deviceOf(intent: Intent): UsbDevice? =
        IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
}
