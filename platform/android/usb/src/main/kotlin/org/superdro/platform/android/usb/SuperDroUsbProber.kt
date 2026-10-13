package org.superdro.platform.android.usb

import android.hardware.usb.UsbDevice
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.Ch34xSerialDriver
import com.hoho.android.usbserial.driver.Cp21xxSerialDriver
import com.hoho.android.usbserial.driver.FtdiSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.ProlificSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber

/**
 * Finds the serial driver of a USB device. The custom probe table
 * has all IDs of [UsbDeviceTable]. A device that is not in the table goes to the default prober of
 * usb-serial-for-android. Thus each TouchDRO compatible USB adapter connects, and each chip of
 * the library also works.
 */
class SuperDroUsbProber {

    private val custom = UsbSerialProber(
        ProbeTable().apply {
            UsbDeviceTable.entries.forEach { (id, driver) -> addProduct(id.vendorId, id.productId, driver.driverClass()) }
        },
    )
    private val default = UsbSerialProber.getDefaultProber()

    /** Gives the driver of [device], or null when no prober accepts it. */
    fun probe(device: UsbDevice): UsbSerialDriver? = custom.probeDevice(device) ?: default.probeDevice(device)

    private fun UsbDriverKind.driverClass(): Class<out UsbSerialDriver> = when (this) {
        UsbDriverKind.CdcAcm -> CdcAcmSerialDriver::class.java
        UsbDriverKind.Cp210x -> Cp21xxSerialDriver::class.java
        UsbDriverKind.Ftdi -> FtdiSerialDriver::class.java
        UsbDriverKind.Pl2303 -> ProlificSerialDriver::class.java
        UsbDriverKind.Ch34x -> Ch34xSerialDriver::class.java
    }
}
