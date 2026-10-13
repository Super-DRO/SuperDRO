package org.superdro.platform.desktop.serial

import org.superdro.core.model.AdapterAddress

/**
 * The line settings of one serial link: 8 data bits, no
 * parity, 1 stop bit, no flow control, and DTR and RTS false. An ESP32 board with an auto-reset
 * circuit can reset when these lines change, so the transport never sets them to true.
 *
 * @property baudRate The baud rate. A USB link uses the stored value of the address with no
 *   change. An RFCOMM port ignores the baud rate.
 * @property readTimeoutMs The longest wait of one read with no data.
 * @property writeTimeoutMs The time limit of one write.
 * @property readBufferBytes The size of the read buffer.
 */
data class SerialLineSettings(
    val baudRate: Int,
    val readTimeoutMs: Int,
    val writeTimeoutMs: Int,
    val readBufferBytes: Int,
) {
    val dataBits: Int get() = DATA_BITS
    val stopBits: Int get() = STOP_BITS
    val dtr: Boolean get() = false
    val rts: Boolean get() = false

    companion object {
        const val DATA_BITS: Int = 8
        const val STOP_BITS: Int = 1

        /** The read timeout of a USB link. The RFCOMM port uses the same value. */
        const val READ_TIMEOUT_MS: Int = 200

        /** The total time limit of one USB write. */
        const val USB_WRITE_TIMEOUT_MS: Int = 1000

        /** The write watchdog of an SPP link: a write that does not end in this time is `Lost`. */
        const val SPP_WRITE_TIMEOUT_MS: Int = 4000

        /** The read buffer of a USB link. The Android USB transport uses the same value. */
        const val USB_READ_BUFFER_BYTES: Int = 2048

        /** The read buffer of an SPP link. */
        const val SPP_READ_BUFFER_BYTES: Int = 1024

        /** The settings of a USB serial link: the stored baud rate of [address] with no change. */
        fun usb(address: AdapterAddress.UsbSerial): SerialLineSettings =
            SerialLineSettings(address.baudRate, READ_TIMEOUT_MS, USB_WRITE_TIMEOUT_MS, USB_READ_BUFFER_BYTES)

        /** The settings of an SPP link through an RFCOMM port. The baud rate has no effect on the link. */
        fun spp(): SerialLineSettings =
            SerialLineSettings(AdapterAddress.UsbSerial.DEFAULT_BAUD_RATE, READ_TIMEOUT_MS, SPP_WRITE_TIMEOUT_MS, SPP_READ_BUFFER_BYTES)

        /** The settings of [address]. Only an SPP address and a USB address have a serial port. */
        fun of(address: AdapterAddress): SerialLineSettings = when (address) {
            is AdapterAddress.UsbSerial -> usb(address)
            is AdapterAddress.BluetoothSpp -> spp()
            else -> throw IllegalArgumentException("A ${address.kind} address has no serial port.")
        }
    }
}
