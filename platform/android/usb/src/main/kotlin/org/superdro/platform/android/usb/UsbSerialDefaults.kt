package org.superdro.platform.android.usb

import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.TransportKind

/**
 * The values of the USB serial link: CDC-ACM, CP210x, FTDI,
 * PL2303, and CH34x with 8N1 and the baud rate of the profile. [UsbSerialTransport] uses them.
 */
object UsbSerialDefaults {

    /** The link type of this module. */
    val kind: TransportKind = TransportKind.UsbSerial

    const val DATA_BITS: Int = 8
    const val STOP_BITS: Int = UsbSerialPort.STOPBITS_1
    const val PARITY: Int = UsbSerialPort.PARITY_NONE

    /** The baud rate of a new profile. A stored value does not change. */
    const val BAUD_RATE: Int = AdapterAddress.UsbSerial.DEFAULT_BAUD_RATE

    /** The timeout of one bulk read. A read with no data gives 0 bytes after this time. */
    const val READ_TIMEOUT_MS: Int = 200

    /**
     * The size of the read buffer: 16384 bytes or less. The buffer must not be
     * smaller than the packet size of the endpoint (64 bytes).
     */
    const val READ_BUFFER_BYTES: Int = 2048

    /** The largest read buffer of a bulk read with a timeout (usb-serial-for-android). */
    const val MAX_READ_BUFFER_BYTES: Int = 16384

    /** The total time limit of one write, for all chunks of the endpoint packet size. */
    const val WRITE_TIMEOUT_MS: Int = 1000

    /**
     * After a read or a write failure, the transport looks this long for the end of the device. A
     * device that goes away in this time is `LinkFailure.DeviceGone`, else the failure is
     * `LinkFailure.Lost`. The detach broadcast can come a short time after the failure.
     */
    const val DETACH_CHECK_MS: Long = 500L

    /** The interval of the checks for the end of the device. */
    const val DETACH_POLL_MS: Long = 50L

    /** The prober of usb-serial-for-android with its default list of chips. */
    fun prober(): UsbSerialProber = UsbSerialProber.getDefaultProber()
}
