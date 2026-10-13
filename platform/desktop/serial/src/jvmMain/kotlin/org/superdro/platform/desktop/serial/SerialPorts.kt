package org.superdro.platform.desktop.serial

import java.io.IOException

/**
 * One serial port of the operating system, as the port list gives it.
 *
 * @property id The text that opens the port and that an address keeps: the device path on Linux
 *   and macOS (for example `/dev/ttyUSB0` or `/dev/rfcomm0`), the port name on Windows (for
 *   example `COM5`).
 * @property name The system name, for example `ttyUSB0`, `rfcomm0`, `cu.usbserial-0001`, or `COM5`.
 * @property description The description that the bus gives, for example
 *   `CP2102 USB to UART Bridge Controller`.
 * @property friendlyName The name that the system gives, for example
 *   `Standard Serial over Bluetooth link (COM5)`.
 * @property vendorId The USB vendor ID, or null for a port that is not on USB.
 * @property productId The USB product ID, or null for a port that is not on USB.
 * @property serialNumber The USB serial number, or null when the device has no serial number.
 * @property manufacturer The USB manufacturer text, or null.
 * @property location The physical location of the port (the bus and the hub ports), or null.
 * @property bluetoothMac The Bluetooth address of an RFCOMM port in upper case, or null when the
 *   system does not give it.
 * @property accessible False when the user cannot read and write the device file, for example
 *   with no `dialout` group on Linux.
 */
data class SerialPortInfo(
    val id: String,
    val name: String,
    val description: String = "",
    val friendlyName: String = "",
    val vendorId: Int? = null,
    val productId: Int? = null,
    val serialNumber: String? = null,
    val manufacturer: String? = null,
    val location: String? = null,
    val bluetoothMac: String? = null,
    val accessible: Boolean = true,
)

/**
 * The serial ports of the operating system. [JSerialCommPorts] is the implementation of the app.
 * A test gives a fake.
 */
interface SerialPorts {

    /** Gives the ports that are there now. A failure gives an empty list. */
    fun list(): List<SerialPortInfo>

    /**
     * Opens the port [id] with [settings] and gives it. The call can block for some seconds: the
     * open of an RFCOMM port starts the Bluetooth connect.
     *
     * Throws an [org.superdro.core.connection.LinkFailureException]: `DeviceGone` (no such port),
     * `PermissionMissing` (no access to the device file), or `Refused` (each other failure).
     */
    fun open(id: String, settings: SerialLineSettings): OpenSerialPort
}

/** One open serial port. */
interface OpenSerialPort {

    /**
     * Reads into [buffer]. The call waits until one or more bytes come, or until the read timeout
     * of the line settings. Gives the number of bytes, 0 when no byte came. Throws an
     * [IOException] when the port fails, for example when the USB cable is removed.
     */
    fun read(buffer: ByteArray): Int

    /** Writes all of [bytes]. Throws an [IOException] when the port fails or the write timeout ends. */
    fun write(bytes: ByteArray)

    /** Closes the port. A second call does nothing. */
    fun close()
}
